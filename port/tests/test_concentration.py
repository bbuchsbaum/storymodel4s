"""Source concentration from original account measures, including crop and denominator controls."""

import json
from fractions import Fraction
from pathlib import Path

import pytest
from test_recall_clock import build_clock_example

from storymodel.cli import main
from storymodel.concentration import (
    BestWindow,
    ConcentratedBins,
    ConcentratedWindow,
    ConcentrationQuery,
    ConcentrationRefusal,
    CoverageTarget,
    DisconnectedBins,
    PreparedConcentration,
    ShortestWindow,
    SourceConcentration,
    _segments,
    source_concentration,
)
from storymodel.playback import PlaybackSupport, TickInterval
from storymodel.serialization import digest
from storymodel.source_grid import (
    LocalScoreGridQuery,
    SourceGridQuery,
    SupportDeclaration,
    TransportGridQuery,
)

example = pytest.fixture(scope="module")(build_clock_example)


def _request(root):
    return ConcentrationQuery.model_validate_json((root / "concentration-query.json").read_bytes())


def _run(root, q, **changes):
    return source_concentration(root / "analysis-1.json", q.model_copy(update=changes))


def _coverage(value, basis="share-of-allocated-source"):
    return CoverageTarget(basis=basis, value=value)


@pytest.mark.parametrize(
    "filename,schema,measure",
    [
        ("source-grid", SourceGridQuery, "model-posterior"),
        ("local-score-grid", LocalScoreGridQuery, "normalized-score-mass"),
        ("transport-grid", TransportGridQuery, "transport-mass"),
    ],
)
def test_all_measures_find_the_window_from_full_geometry(example, filename, schema, measure):
    grid_query = schema.model_validate_json((example / f"{filename}-query.json").read_bytes())
    original = json.loads((example / f"{filename}.json").read_bytes())
    result = _run(
        example, _request(example), grid=grid_query, operation=ShortestWindow(target=_coverage("1"))
    )
    assert result.grid_sha256 == digest(original) and result.measure == measure
    assert result.row_status == "computed" and result.duration_seconds == Fraction(3, 4)
    assert result.ledger.allocated_outside == result.ledger.allocated_source / 5 > 0
    assert result.ledger.unavailable_location > 0
    selection = result.result
    assert isinstance(selection, ConcentratedWindow)
    assert selection.interval == TickInterval(start="0", end="25000")  # grid crop ends at 20000
    assert (
        selection.within.lower
        == selection.within.allocated
        == selection.within.upper
        == selection.needed
    )
    assert selection.needed == result.ledger.allocated_source
    assert sum(c.weight for c in selection.contributions) == result.ledger.supplied
    assert sum(c.weight for c in selection.contributions if c.within is None) == (
        result.ledger.unavailable_location + sum(result.ledger.external.values())
    )
    assert result.joint_status == "unavailable-without-joint-evidence"


def test_cropped_disconnected_bins_and_supplied_share_use_different_limits(example):
    q = _request(example)
    # Caller order is reversed; equal bins favour original position zero.
    grid = q.grid.model_copy(update={"bins": tuple(reversed(q.grid.bins))})
    one = _run(example, q, grid=grid, operation=DisconnectedBins(target=_coverage("2/5")))
    assert one.result == ConcentratedBins(
        bins=(0,),
        needed=one.ledger.allocated_source * 2 / 5,
        achieved=one.ledger.allocated_source * 2 / 5,
    )
    for operation, limit in (
        (DisconnectedBins(target=_coverage("1")), Fraction(4, 5) * one.ledger.allocated_source),
        (ShortestWindow(target=_coverage("1", "share-of-supplied")), one.ledger.allocated_source),
    ):
        r = _run(example, q, grid=grid, operation=operation)
        assert r.result.kind == "unavailable"
        assert r.result.reason == "unattainable" and r.result.available == limit
        assert r.result.needed == (
            one.ledger.supplied
            if isinstance(operation, ShortestWindow)
            else one.ledger.allocated_source
        )


def test_zero_target_is_distinct_from_undefined_or_undeclared_allocation(example):
    q = _request(example)
    grid = q.grid.model_copy(update={"declarations": {}})
    empty = _run(example, q, grid=grid, operation=ShortestWindow(target=_coverage("0", "absolute")))
    assert empty.result.width_ticks == "0" and empty.result.interval is None
    assert empty.result.within.allocated == 0 and empty.ledger.unavailable_location > 0
    undefined = _run(example, q, grid=grid, operation=ShortestWindow(target=_coverage("0")))
    assert undefined.result == ConcentrationRefusal(reason="undefined-conditioning")
    target, declaration = next(iter(q.grid.declarations.items()))
    support = q.grid.model_copy(
        update={
            "declarations": {target: declaration.model_copy(update={"allocation": "support-only"})}
        }
    )
    unavailable = _run(example, q, grid=support, operation=BestWindow(width_ticks="5"))
    assert unavailable.ledger.support_without_allocation > 0
    assert unavailable.result == ConcentrationRefusal(reason="unavailable-allocation")
    prepared = PreparedConcentration(example / "analysis-1.json", support, q.unit)
    for method in (ShortestWindow, DisconnectedBins):
        zero = prepared.query(method(target=_coverage("0", "absolute")))
        assert zero.result == ConcentrationRefusal(
            reason="unavailable-allocation", needed=Fraction(0)
        )
    missed = q.grid.transport.model_copy(
        update={
            "units": tuple(u.model_copy(update={"targets": ()}) for u in q.grid.transport.units)
        }
    )
    absent = _run(example, q, grid=q.grid.model_copy(update={"transport": missed}))
    assert absent.result == ConcentrationRefusal(reason="measure-unavailable")
    assert absent.row_status == "no-transport-candidates" and absent.ledger is None


def test_mass_bounds_and_iteration_status_survive_window_selection(example):
    q = _request(example)
    transport = q.grid.transport
    limited = transport.model_copy(
        update={"config": transport.config.model_copy(update={"max_iterations": 1})}
    )
    r = _run(
        example,
        q,
        grid=q.grid.model_copy(update={"transport": limited}),
        operation=BestWindow(width_ticks="10000"),
    )
    assert r.row_status == "transport-iteration-limit" and r.result.needed is None
    assert r.result.interval == TickInterval(start="0", end="10000")
    assert r.result.within.lower == 0 and r.result.within.upper == r.ledger.allocated_source
    assert r.result.within.allocated == r.ledger.allocated_source * Fraction(2, 5)


def test_mixture_overlapping_point_retains_both_declared_channels():
    support = PlaybackSupport(
        source="axis",
        intervals=(TickInterval(start="-3", end="0"), TickInterval(start="7", end="8")),
        points=("-1", "7"),
    )
    policy = SupportDeclaration(
        assumption="supplied-support-contains-referent", allocation="mixture", interval_share="1/4"
    )
    assert list(_segments(support, policy, Fraction(8))) == [
        (-3, 0, Fraction(3, 2)),
        (7, 8, Fraction(1, 2)),
        (-1, 0, Fraction(3)),
        (7, 8, Fraction(3)),
    ]


def test_cli_replays_typed_query_and_refuses_changed_receipt(example, tmp_path):
    q = _request(example)
    args = [str(example / "analysis-1.json"), str(example / "concentration-query.json")]
    output = tmp_path / "receipt.json"
    assert main(["source-concentration", *args, "--output", str(output)]) == 0
    assert output.read_bytes() == (example / "concentration.json").read_bytes()
    r = SourceConcentration.model_validate_json(output.read_bytes())
    assert r.result.interval == TickInterval(start="0", end="12500")
    assert main(["verify-source-concentration", *args, str(output)]) == 0
    raw = json.loads(output.read_bytes())
    raw["result"]["interval"]["end"] = "12501"
    output.write_text(json.dumps(raw))
    assert main(["verify-source-concentration", *args, str(output)]) == 2
    with pytest.raises(ValueError, match="unknown recall unit"):
        _run(example, q, unit="different")
    with pytest.raises(ValueError, match="exceeds source axis"):
        _run(example, q, operation=BestWindow(width_ticks="9999999"))
    with pytest.raises(ValueError, match="positive"):
        BestWindow(width_ticks="0")
    with pytest.raises(ValueError, match="target"):
        _coverage("101/100")
    with pytest.raises(ValueError, match="target"):
        _coverage("-1", "absolute")


def test_repeated_questions_reuse_one_replay_and_tick_index(example, monkeypatch):
    import storymodel.concentration as module

    q = _request(example)
    prepared = PreparedConcentration(example / "analysis-1.json", q.grid, q.unit)

    def disallow(*args, **kwargs):
        pytest.fail("the checked account measure or tick index was rebuilt")

    monkeypatch.setattr(module, "project_source_grid", disallow)
    first = prepared.query(BestWindow(width_ticks="2500"))
    monkeypatch.setattr(module.TickMass, "from_segments", disallow)
    second = prepared.query(ShortestWindow(target=_coverage("1/2")))
    bins = prepared.query(DisconnectedBins(target=_coverage("1/2")))
    assert first.grid_sha256 == second.grid_sha256 == bins.grid_sha256
    assert first.result.interval.end == "2500" and second.result.width_ticks == "12500"
    assert bins.result.bins == (0, 1)


def test_mixed_components_match_the_executed_jvm_concentration_readouts():
    from storymodel._concentration import TickMass
    from storymodel._source_bins import BinIndex
    from storymodel.concentration import _select_bins
    from storymodel.sequence import SequenceState
    from storymodel.source_grid import UnitSourceGrid, _project_mass

    witness = (
        Path(__file__).resolve().parents[1]
        / "evidence/concentration/temporal-concentration-jvm.json"
    )
    oracle = json.loads(witness.read_bytes())
    data, expected = oracle["input"], oracle["expected"]
    lo, hi = data["axis"]["start"], data["axis"]["endExclusive"]
    components, weights, segments = [], [], []
    for component in data["components"]:
        weight = Fraction(component["weight"])
        target = component["alternative"]
        if component["location"] == "external":
            destination = SequenceState(kind="external", external=component["state"].lower())
        else:
            destination = SequenceState(kind="source", anchor_kind="situation", anchor=target)
        weights.append((destination, weight))
        if component["location"] != "resolved":
            continue
        geometry = component["support"]
        support = PlaybackSupport(
            source="oracle",
            intervals=tuple(
                TickInterval(start=str(a), end=str(b)) for a, b in geometry["intervals"]
            ),
            points=tuple(map(str, geometry["points"])),
        )
        allocation = {"UniformIntervals": "uniform-intervals", "UniformPoints": "uniform-points"}[
            component["allocation"]
        ]
        declaration = SupportDeclaration(
            assumption="supplied-support-contains-referent", allocation=allocation
        )
        components.append((target, support, allocation))
        segments.extend(_segments(support, declaration, weight))
    index = TickMass.from_segments(lo, hi, segments)
    assert index.total == data["ledger"]["allocatedSource"]
    best, shortest = expected["bestWindowWidth1"], expected["shortestAbsoluteHalf"]
    assert index.best(1) == (best["region"]["intervals"][0][0], best["allocatedRegionMass"])
    assert index.shortest(Fraction(1, 2)) == (
        shortest["region"]["intervals"][0][0],
        shortest["width"],
        shortest["allocatedRegionMass"],
    )

    def grid_for(n):
        bins = tuple(TickInterval(start=str(i), end=str(i + 1)) for i in range(n))
        projection = BinIndex(start=lo, end=hi, bins=bins)
        projected = {
            target: projection.project(support, allocation)
            for target, support, allocation in components
        }
        ledger, cells = _project_mass(weights, projected)
        return UnitSourceGrid(
            unit="oracle",
            status="computed",
            ledger=ledger,
            cells=cells,
            duration_seconds=None,
            weighted_seconds=None,
        )

    grid = grid_for(10)
    assert [grid.cells[i].allocated if i in grid.cells else 0 for i in range(10)] == expected[
        "unitGridAllocated"
    ]
    selected = _select_bins(grid, Fraction(1, 2))
    assert list(selected.bins) == expected["disconnectedAbsoluteHalf"]["selected"]
    assert selected.achieved == expected["disconnectedAbsoluteHalf"]["quantity"]
    crop = grid_for(2)
    assert crop.ledger.allocated_outside == expected["crop0To2"]["allocatedOutside"]
    assert crop.ledger.unavailable_location == data["ledger"]["unavailableLocation"]
    assert crop.ledger.external == {"intrusion": Fraction(1, 8)}
    assert crop.ledger.row_budget == data["ledger"]["rowBudget"]
    assert _coverage("9/10", "share-of-supplied").quantity(grid.ledger) > index.total
    assert expected["refusals"]["unattainableSupplied90"] == "Unattainable(0.9,0.75)"
