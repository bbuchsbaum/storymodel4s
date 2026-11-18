"""Source-grid tests exercise the saved plan, independent geometry and the CLI verifier."""

import json
from fractions import Fraction
from pathlib import Path

import pytest
from test_recall_clock import build_clock_example, two_unit_recording

from storymodel.cli import main
from storymodel.local_evidence import RefinedInferenceSpec
from storymodel.playback import TickInterval
from storymodel.refinement import RefinementPolicy
from storymodel.sherlock_composition import compose_sherlock_source
from storymodel.sherlock_source import SherlockSource
from storymodel.source_grid import SourceGrid, SourceGridQuery, project_source_grid

artifacts = pytest.fixture(scope="module")(build_clock_example)


def query(artifacts: Path) -> SourceGridQuery:
    return SourceGridQuery.model_validate_json((artifacts / "source-grid-query.json").read_bytes())


def test_crop_preserves_incoming_posterior_and_measured_seconds(artifacts):
    q = query(artifacts)
    result = project_source_grid(artifacts / "analysis-1.json", q)
    assert result == SourceGrid.model_validate_json((artifacts / "source-grid.json").read_bytes())
    target = next(iter(q.declarations))
    states = {c.state.id: c.state for c in result.inference.evidence.sequence.request.rows[0].costs}
    weights = [
        (states[m.state], Fraction(m.probability))
        for m in result.inference.inference.posterior[0].mass
    ]
    placed = sum(w for state, w in weights if state.anchor == target)
    assert placed > 0
    (row,) = result.rows
    ledger = row.ledger
    assert ledger.row_budget == 1
    assert ledger.allocated_source == placed
    assert (
        ledger.unavailable_location
        == sum(w for state, w in weights if state.anchor and state.anchor != target)
        > 0
    )
    assert ledger.external == {s.external: w for s, w in weights if s.external}
    assert ledger.supplied == placed + ledger.unavailable_location + sum(ledger.external.values())
    assert ledger.support_without_allocation == 0
    assert ledger.allocated_outside == placed / 5
    assert row.duration_seconds == Fraction(3, 4)
    for i in (0, 1):
        cell = row.cells[i]
        assert (cell.allocated, cell.lower, cell.upper) == (2 * placed / 5, 0, placed)
        assert row.weighted_seconds[i].allocated == 3 * placed / 10
    assert sum(c.allocated for c in row.cells.values()) + ledger.allocated_outside == placed


@pytest.mark.parametrize(
    "allocation,status",
    [("uniform-points", "incompatible-geometry"), ("support-only", "support-only")],
)
def test_containment_bounds_survive_without_numeric_allocation(artifacts, allocation, status):
    q = query(artifacts)
    target, declaration = next(iter(q.declarations.items()))
    q = q.model_copy(
        update={
            "bins": (TickInterval(start="0", end="25000"),),
            "declarations": {target: declaration.model_copy(update={"allocation": allocation})},
            "exposure": None,
        }
    )
    result = project_source_grid(artifacts / "analysis-1.json", q)
    (row,) = result.rows
    assert result.domains[target].status == status
    assert row.cells[0].lower == row.cells[0].upper == row.ledger.support_without_allocation > 0
    assert row.cells[0].allocated == row.ledger.allocated_outside == 0
    assert row.duration_seconds is row.weighted_seconds is None


def test_hierarchy_rejects_unlocated_descendant_and_preserves_its_own_missingness(artifacts):
    q = query(artifacts)
    target, declaration = next(iter(q.declarations.items()))
    source = json.loads((artifacts / "analysis-1-result.json").read_bytes())["source"]
    child = source["construction"]["construction"]["resolutions"][0]["accepted"]["condition"]
    spec = q.inference.model_copy(
        update={
            "projection": q.inference.projection.model_copy(update={"memberships": {child: target}})
        }
    )
    result = project_source_grid(
        artifacts / "analysis-1.json",
        q.model_copy(
            update={"inference": spec, "declarations": {target: declaration, child: declaration}}
        ),
    )
    assert result.domains[target].status == "missing-descendant"
    assert result.domains[child].status == "no-temporal-support"
    (row,) = result.rows
    assert not row.cells and row.ledger.allocated_source == 0
    assert (
        row.ledger.unavailable_location + sum(row.ledger.external.values()) == row.ledger.supplied
    )


def test_reset_native_part_clock_maps_to_composed_interval_and_refined_posterior(artifacts):
    q = query(artifacts)
    source = json.loads((artifacts / "analysis-1-result.json").read_bytes())["source"]
    intake = SherlockSource.model_validate_json(json.dumps(source["request"]["intake"]))
    receipt = compose_sherlock_source(intake)
    second = next(row for row in receipt.rows if row.anchors[0].native.source != q.axis)
    resolutions = source["construction"]["construction"]["resolutions"]
    target = next(r["unit"] for r in resolutions if r["row"] == second.key)
    (interval,) = second.support.intervals
    t = int(interval.start)
    assert t > int(second.anchors[0].native.intervals[0].start)
    declaration = next(iter(q.declarations.values()))
    (selection,) = q.inference.units
    local = q.inference.model_copy(
        update={"units": (selection.model_copy(update={"targets": (target,)}),)}
    )
    q = q.model_copy(
        update={
            "declarations": {target: declaration},
            "inference": RefinedInferenceSpec(local=local, policy=RefinementPolicy(passes=1)),
            "exposure": None,
        }
    )
    native = project_source_grid(artifacts / "analysis-1.json", q)
    assert native.domains[target].status == "other-coordinate"
    result = project_source_grid(
        artifacts / "analysis-1.json",
        q.model_copy(
            update={
                "selection": "composition",
                "axis": receipt.composition.id,
                "bins": (
                    TickInterval(start=str(t - 1), end=str(t)),
                    TickInterval(start=str(t), end=interval.end),
                ),
            }
        ),
    )
    assert result.inference == native.inference
    (row,) = result.rows
    assert set(row.cells) == {1}
    assert (
        row.cells[1].allocated
        == row.cells[1].lower
        == row.cells[1].upper
        == native.rows[0].ledger.unavailable_location
        > 0
    )


def test_unselected_recall_has_no_computed_ledger(artifacts, tmp_path, monkeypatch):
    root, _, ordered = two_unit_recording(artifacts, tmp_path, monkeypatch)
    q = query(root)
    selection = q.inference.units[0].model_copy(update={"unit": ordered[0].id})
    spec = q.inference.model_copy(update={"units": (selection,)})
    result = project_source_grid(
        root / "multi.json", q.model_copy(update={"inference": spec, "exposure": None, "bins": ()})
    )
    measured, unselected = result.rows
    assert measured.unit == ordered[0].id and unselected.unit == ordered[1].id
    assert (
        measured.cells == {}
        and measured.ledger.allocated_outside == measured.ledger.allocated_source > 0
    )
    assert unselected.status == "outside-inference-selection"
    assert unselected.ledger is unselected.cells is unselected.weighted_seconds is None


def test_grid_rejects_invalid_bindings_and_tampered_receipt(artifacts, tmp_path):
    q = query(artifacts)
    plan = artifacts / "analysis-1.json"
    declaration = next(iter(q.declarations.values()))
    for update, message in [
        ({"axis": "other"}, "foreign source part"),
        ({"declarations": {"foreign": declaration}}, "foreign target"),
        ({"bins": (q.bins[0], q.bins[0])}, "nonoverlapping"),
        (
            {"exposure": q.exposure.model_copy(update={"intake_sha256": "0" * 64})},
            "another recall intake",
        ),
    ]:
        with pytest.raises(ValueError, match=message):
            project_source_grid(plan, q.model_copy(update=update))
    args = [str(plan), str(artifacts / "source-grid-query.json")]
    output = tmp_path / "grid.json"
    assert main(["source-time-grid", *args, "--output", str(output)]) == 0
    assert output.read_bytes() == (artifacts / "source-grid.json").read_bytes()
    assert main(["verify-source-time-grid", *args, str(output)]) == 0
    changed = json.loads(output.read_bytes())
    changed["rows"][0]["ledger"]["allocated_outside"] = "0"
    output.write_text(json.dumps(changed))
    receipt = tmp_path / "receipt.json"
    assert main(["verify-source-time-grid", *args, str(output), "--output", str(receipt)]) == 2
    assert not receipt.exists()


def _scala_number(value):
    import struct

    return struct.unpack(">d", bytes.fromhex(value.removeprefix("0x")))[0]


def _interval(value):
    return TickInterval(start=value["start"], end=value["endExclusive"])


_WITNESS = json.loads(
    (
        Path(__file__).resolve().parents[1] / "evidence/source-grid/source-time-grid-witness.json"
    ).read_bytes()
)
_SUCCESS_CASES = [
    case for case in _WITNESS["cases"] if case["expected"]["processing"] == "Complete"
]


@pytest.mark.parametrize("case", _SUCCESS_CASES, ids=[case["id"] for case in _SUCCESS_CASES])
def test_scala_grid_for_same_supplied_components(case):
    from storymodel._source_bins import BinIndex
    from storymodel.playback import PlaybackSupport
    from storymodel.sequence import SequenceState
    from storymodel.source_grid import GridQuantity, _project_mass

    data = case["input"]
    axis = data["axis"]
    index = BinIndex(
        bins=tuple(_interval(region["intervals"][0]) for region in data["bins"]),
        start=int(axis["extent"]["start_tick"]),
        end=int(axis["extent"]["end_exclusive_tick"]),
    )
    declarations = {d["target"]: d["allocation"] for d in data["declarations"]}
    weights, projected = [], {}
    for component in data["rawComponents"]:
        location = component["location"]
        if location["kind"] == "external":
            state = SequenceState(kind="external", external=location["state"].lower())
        else:
            target = location.get("target", component["alternative"])
            state = SequenceState(kind="source", anchor_kind="situation", anchor=target)
            if location["kind"] == "resolved":
                support = PlaybackSupport(
                    source=axis["id"],
                    intervals=tuple(_interval(v) for v in location["support"]["intervals"]),
                    points=tuple(location["support"]["points"]),
                )
                allocation, share = {
                    "Mixture(0.5)": ("mixture", Fraction(1, 2)),
                    "UniformPoints": ("uniform-points", None),
                    "UniformIntervals": ("uniform-intervals", None),
                    "SupportOnly": ("support-only", None),
                }[declarations[target]]
                projected[target] = index.project(support, allocation, share)
        weights.append((state, Fraction(_scala_number(component["weight"]))))
    ledger, cells = _project_mass(tuple(weights), projected)
    expected = case["expected"]["grid"]
    zero = GridQuantity(allocated=Fraction(), lower=Fraction(), upper=Fraction())
    for b in expected["perBin"]:
        cell = cells.get(b["index"], zero)
        for field in ("allocated", "lower", "upper"):
            assert float(getattr(cell, field)) == _scala_number(b[field])
    for py, scala in [
        ("supplied", "suppliedTotal"),
        ("row_budget", "rowBudget"),
        ("support_without_allocation", "supportWithoutAllocation"),
        ("unavailable_location", "unavailableLocation"),
        ("allocated_source", "allocatedSource"),
    ]:
        assert float(getattr(ledger, py)) == _scala_number(expected["ledger"][scala])
    assert ledger.external == {
        e["state"].lower(): Fraction(_scala_number(e["mass"]))
        for e in expected["ledger"]["external"]
    }
    assert float(ledger.allocated_outside) == pytest.approx(
        _scala_number(expected["allocatedOutside"]), abs=1e-15, rel=0
    )
    assert ledger.allocated_source - sum(
        c.allocated for c in cells.values()
    ) - ledger.allocated_outside == _scala_number(expected["ledger"]["allocationDiscrepancy"])
