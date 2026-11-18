"""Verify both local score populations from actual accounts and their distinct grid contract."""

import json
from fractions import Fraction
from pathlib import Path

import pytest
from test_recall_clock import build_clock_example, two_unit_recording

from storymodel.artifacts import read_supported_account
from storymodel.cli import main
from storymodel.local_evidence import LocalCostPolicy, RecallSelection
from storymodel.local_reference import LocalReferenceSpec, local_reference
from storymodel.source_grid import (
    LocalScoreGrid,
    LocalScoreGridQuery,
    SourceGridQuery,
    project_source_grid,
)
from storymodel.source_transitions import TransitionWeights

artifacts = pytest.fixture(scope="module")(build_clock_example)


def _query(root):
    return LocalScoreGridQuery.model_validate_json(
        (root / "local-score-grid-query.json").read_bytes()
    )


def test_installed_workflow_recomputes_source_scores_and_seconds(artifacts, tmp_path, monkeypatch):
    from storymodel import source_grid

    def unexpected_decoder(*args, **kwargs):
        pytest.fail("local scores invoked sequence inference")

    monkeypatch.setattr(source_grid, "_resolve_account_inference", unexpected_decoder)
    q = _query(artifacts)
    result = project_source_grid(artifacts / "analysis-1.json", q)
    assert result == LocalScoreGrid.model_validate_json(
        (artifacts / "local-score-grid.json").read_bytes()
    )
    assert result.measure == "normalized-score-mass"
    inputs = [
        str(artifacts / f)
        for f in ("local-source.json", "local-recall.json", "local-reference-spec.json")
    ]
    verified_reference = local_reference(
        read_supported_account(Path(inputs[0])),
        read_supported_account(Path(inputs[1])),
        q.reference,
    )
    assert result.reference == verified_reference
    (scored,) = result.reference.rows
    (row,) = result.rows
    assert scored.status == row.status == "computed"
    external = {m.state.external: Fraction(m.weight) for m in scored.mass if m.state.external}
    assert len(external) == 5 and all(w > 0 for w in external.values())
    assert external == row.ledger.external
    (target,) = q.declarations
    individual = sum(Fraction(m.weight) for m in scored.mass if m.state.anchor == target)
    assert row.ledger.allocated_source == individual > 0
    assert row.ledger.allocated_outside == individual / 5
    assert row.weighted_seconds[0].allocated == individual * Fraction(3, 10)
    ref_path, grid_path = tmp_path / "local.json", tmp_path / "grid.json"
    assert main(["local-reference", *inputs, "--output", str(ref_path)]) == 0
    assert ref_path.read_bytes() == (artifacts / "local-reference.json").read_bytes()
    assert main(["verify-local-reference", *inputs, str(ref_path)]) == 0
    args = [str(artifacts / "analysis-1.json"), str(artifacts / "local-score-grid-query.json")]
    assert main(["source-time-grid", *args, "--output", str(grid_path)]) == 0
    assert grid_path.read_bytes() == (artifacts / "local-score-grid.json").read_bytes()
    assert main(["verify-source-time-grid", *args, str(grid_path)]) == 0
    # A sequence posterior for the same plan cannot stand in for the local calculation.
    assert main(["verify-source-time-grid", *args, str(artifacts / "source-grid.json")]) == 2
    changed = json.loads(ref_path.read_bytes())
    changed["rows"][0]["mass"][0]["weight"] = 0
    ref_path.write_text(json.dumps(changed))
    assert main(["verify-local-reference", *inputs, str(ref_path)]) == 2


def test_grid_requires_external_population_and_keeps_unavailable_duration(artifacts):
    q = _query(artifacts)
    semantic_only = LocalCostPolicy(
        weights={"semantic": 1.0}, function_prior={}, distortion_penalty=0
    )
    spec = LocalReferenceSpec(
        level=q.reference.level,
        evidence=q.reference.evidence.model_copy(update={"costs": semantic_only}),
        population="admitted-source-targets",
    )
    with pytest.raises(ValueError, match="including external"):
        project_source_grid(artifacts / "analysis-1.json", q.model_copy(update={"reference": spec}))
    no_measurements = q.reference.evidence.model_copy(update={"costs": LocalCostPolicy(weights={})})
    absent = project_source_grid(
        artifacts / "analysis-1.json",
        q.model_copy(
            update={"reference": q.reference.model_copy(update={"evidence": no_measurements})}
        ),
    )
    (row,) = absent.rows
    assert absent.reference.rows[0].status == "prior-only-target-cost"
    assert row.status == "local-reference-unavailable"
    assert row.cells is row.ledger is row.weighted_seconds is None
    assert row.duration_seconds == Fraction(3, 4)


def test_local_scores_isolate_content_from_order_on_same_two_unit_population(
    artifacts, tmp_path, monkeypatch
):
    root, _, ordered = two_unit_recording(artifacts, tmp_path, monkeypatch)
    query = _query(root)
    policy = TransitionWeights(theta={"external-in": 15, "external-stay": 15})
    evidence = query.reference.evidence.model_copy(
        update={"units": tuple(RecallSelection(unit=u.id) for u in ordered), "transitions": policy}
    )
    spec = query.reference.model_copy(update={"evidence": evidence})
    query = query.model_copy(update={"reference": spec, "exposure": None})
    result = project_source_grid(root / "multi.json", query)
    assert [r.unit for r in result.rows] == [u.id for u in ordered]
    assert all(r.status == "computed" for r in result.rows)
    # Give the sequence model the exact same nominees, costs and five external states.
    units = tuple(
        s.model_copy(update={"targets": tuple(n.target for n in receipt.nominations)})
        for s, receipt in zip(evidence.units, result.reference.retrieval, strict=True)
    )
    sequence_query = SourceGridQuery(
        inference=evidence.model_copy(update={"units": units}),
        **query.model_dump(exclude={"reference", "schema_version"}, mode="python"),
    )
    posterior = project_source_grid(root / "multi.json", sequence_query)
    score_cells = {(c.unit, c.target): c.total for c in result.reference.cells}
    assert score_cells == {(c.unit, c.target): c.total for c in posterior.inference.evidence.cells}
    local_external = sum(result.rows[1].ledger.external.values())
    posterior_external = sum(posterior.rows[1].ledger.external.values())
    assert 0 < local_external < Fraction(9, 10)
    assert posterior_external > Fraction(99, 100)
    reversed_spec = spec.model_copy(
        update={
            "evidence": evidence.model_copy(
                update={
                    "units": tuple(reversed(evidence.units)),
                    "transitions": TransitionWeights(),
                }
            )
        }
    )
    other = project_source_grid(
        root / "multi.json", query.model_copy(update={"reference": reversed_spec})
    )
    assert {r.unit: r.ledger for r in result.rows} == {r.unit: r.ledger for r in other.rows}


def test_local_reference_checks_both_export_grants_before_writing(artifacts, tmp_path):
    args = [
        str(artifacts / p)
        for p in ("local-source.json", "local-recall.json", "local-reference-spec.json")
    ]
    for index in (0, 1):
        denied = tmp_path / f"denied-{index}.json"
        changed = json.loads(Path(args[index]).read_bytes())
        changed["export_permission"] = "denied"
        denied.write_text(json.dumps(changed))
        files = list(args)
        files[index] = str(denied)
        output = tmp_path / f"refused-{index}.json"
        assert main(["local-reference", *files, "--output", str(output)]) == 2
        assert not output.exists()
