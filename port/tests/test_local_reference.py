"""Account-to-reference tests for the local, non-sequential score producer."""

from __future__ import annotations

import json
import math
from pathlib import Path

import pytest
from test_local_evidence import rich

from storymodel.local_evidence import (
    LocalCostPolicy,
    LocalEvidenceSpec,
    RecallSelection,
    SemanticCell,
    SemanticEstimate,
)
from storymodel.local_reference import LocalReferenceSpec, local_reference


def _accounts():
    """Two source states and one recall unit make the complete level cut observable."""
    source = rich(
        "source",
        "AA BB",
        (),
        (
            ("alpha", 0, 2, "positive", "asserted", ()),
            ("alpha", 3, 5, "negative", "asserted", ()),
        ),
    )
    recall = rich(
        "recall",
        "RR",
        (("recall", 0, 2, None),),
        (("alpha", 0, 2, "positive", "asserted", ()),),
    )
    return source, recall, recall.units[0], recall.states[0]


def _semantic_evidence(source, unit, situation, values):
    return LocalEvidenceSpec(
        units=(RecallSelection(unit=unit.id, situation=situation.id),),
        semantic="table",
        semantic_cells=tuple(
            SemanticCell(
                unit=unit.id,
                target=target.id,
                estimate=estimate,
            )
            for target, estimate in zip(
                sorted(source.states, key=lambda s: s.polarity != "positive"), values, strict=True
            )
        ),
        temperature=1 / math.log(2),
    )


def _measured_policy() -> LocalCostPolicy:
    return LocalCostPolicy(
        weights={"semantic": 1.0},
        function_prior={},
        distortion_penalty=0.0,
        external_floor=100.0,
        external_mismatch=0.0,
    )


def test_measured_source_targets_match_jvm_shifted_exponential_and_skip_sequence(monkeypatch):
    source, recall, unit, situation = _accounts()
    evidence = _semantic_evidence(
        source,
        unit,
        situation,
        (
            SemanticEstimate(status="observed", value=0.0),
            SemanticEstimate(status="observed", value=1.0),
        ),
    ).model_copy(update={"costs": _measured_policy()})

    import storymodel.local_evidence as local_evidence

    def no_sequence(*args, **kwargs):
        pytest.fail("local reference invoked sequence construction")

    monkeypatch.setattr(local_evidence, "build_source_sequence", no_sequence)
    result = local_reference(
        source,
        recall,
        LocalReferenceSpec(
            evidence=evidence,
            level=0,
            population="admitted-source-targets",
            pricing="measured-semantic",
        ),
    )

    (row,) = result.rows
    assert row.status == "computed"
    assert row.information == "distinguished-costs"
    assert {entry.cost for entry in row.costs if entry.state.anchor is not None} == {0.0, 1.0}
    assert {entry.state.external for entry in row.costs if entry.state.external} == {
        "association",
        "commentary",
        "source-consistent-inference",
        "intrusion",
        "uninterpretable",
    }
    assert {e.state.kind: e.weight for e in row.mass} == pytest.approx(
        {"source": 2 / 3, "distorted": 1 / 3}
    )
    (receipt,) = result.retrieval
    assert (receipt.observed, receipt.missing, receipt.ineligible) == (2, 0, 0)
    assert result.source_sha256 == result.projection.account_sha256
    assert result.recall_sha256
    assert result.universe == tuple(sorted(state.id for state in source.states))


def test_tie_budget_withholds_all_prices_before_normalization():
    source, recall, unit, situation = _accounts()
    evidence = _semantic_evidence(
        source,
        unit,
        situation,
        (
            SemanticEstimate(status="observed", value=0.0),
            SemanticEstimate(status="observed", value=0.0),
        ),
    ).model_copy(update={"costs": _measured_policy(), "max_candidates": 1})
    result = local_reference(
        source,
        recall,
        LocalReferenceSpec(
            evidence=evidence,
            level=0,
            pricing="measured-semantic",
            tie_budget=1,
        ),
    )
    (receipt,) = result.retrieval
    (row,) = result.rows
    assert receipt.status == "search-limited" and row.status == "tie-overflow"
    assert receipt.nominations == () and result.cells == () and row.mass == ()
    assert row.information is None


def test_raw_missing_and_ineligible_measurements_are_not_silently_priced():
    source, recall, unit, situation = _accounts()
    evidence = _semantic_evidence(
        source,
        unit,
        situation,
        (
            SemanticEstimate(status="missing", reason="provider-abstained"),
            SemanticEstimate(status="ineligible", reason="different-ontology"),
        ),
    ).model_copy(update={"costs": _measured_policy()})
    result = local_reference(
        source,
        recall,
        LocalReferenceSpec(
            evidence=evidence,
            level=0,
            population="admitted-source-targets",
            pricing="measured-semantic",
        ),
    )
    (receipt,) = result.retrieval
    (row,) = result.rows
    assert (receipt.observed, receipt.missing, receipt.ineligible) == (0, 1, 1)
    assert receipt.nominations == () and result.cells == ()
    assert row.status == "unranked" and row.mass == ()


def test_source_target_population_refuses_heterogeneous_local_feature_prices():
    source, recall, unit, situation = _accounts()
    evidence = _semantic_evidence(
        source,
        unit,
        situation,
        (
            SemanticEstimate(status="observed", value=0.0),
            SemanticEstimate(status="observed", value=1.0),
        ),
    )
    result = local_reference(
        source,
        recall,
        LocalReferenceSpec(
            evidence=evidence,
            level=0,
            population="admitted-source-targets",
            pricing="local-features",
        ),
    )
    (row,) = result.rows
    assert row.status == "incomparable-target-cost"
    assert row.mass == () and row.information is None


def test_reference_rejects_caller_selected_target_subset():
    source, _, unit, situation = _accounts()
    evidence = LocalEvidenceSpec(
        units=(
            RecallSelection(
                unit=unit.id,
                situation=situation.id,
                targets=(source.states[0].id,),
            ),
        )
    )
    with pytest.raises(ValueError, match="bypass complete-level nomination"):
        LocalReferenceSpec(evidence=evidence, level=0)


_JVM_CASES = {
    case["name"]: case
    for case in json.loads(
        (
            Path(__file__).resolve().parents[1]
            / "evidence/local-reference/local-reference-jvm.json"
        ).read_bytes()
    )["cases"]
}


@pytest.mark.parametrize(
    "name",
    [
        "supplied-destinations",
        "source-targets-measured",
        "source-targets-placeholder-refusal",
        "tie-overflow",
        "incomparable-source-targets",
    ],
)
def test_real_account_prices_modes_and_outcomes_against_jvm(name):
    case = _JVM_CASES[name]
    source, recall, unit, situation = _accounts()
    is_measured = name == "source-targets-measured"
    policy = _measured_policy().model_copy(
        update={
            "external_floor": 100.0 if is_measured else 1.0,
            "distortion_penalty": 0.3 if name == "incomparable-source-targets" else 0.0,
            "weights": {"semantic": 1.0, "distortion": 1.0}
            if name == "incomparable-source-targets"
            else {"semantic": 1.0},
        }
    )
    distances = (
        (0.0, 0.0) if name in ("tie-overflow", "incomparable-source-targets") else (0.0, 1.0)
    )
    evidence = _semantic_evidence(
        source, unit, situation, [SemanticEstimate(status="observed", value=d) for d in distances]
    ).model_copy(
        update={
            "costs": policy,
            "temperature": case["temperature"],
            "max_candidates": 1 if name == "tie-overflow" else 2,
        }
    )
    result = local_reference(
        source,
        recall,
        LocalReferenceSpec(
            evidence=evidence,
            population="admitted-source-targets"
            if case["population"] == "AdmittedSourceTargets"
            else "supplied-destinations",
            pricing="measured-semantic" if is_measured else "local-features",
            tie_budget=1 if name == "tie-overflow" else None,
        ),
    )
    (row,) = result.rows
    expected = case["outcome"]
    if expected["status"] != "computed":
        reason = {
            "IncomparableTargetCosts": "incomparable-target-cost",
            "TieOverflow": "tie-overflow",
        }[expected["reason"]]
        assert row.status == reason and row.mass == ()
        return

    def key(state):
        if state.external:
            return "ext:" + "".join(v.capitalize() for v in state.external.split("-"))
        (positive,) = (s.id for s in source.states if s.polarity == "positive")
        if state.anchor == positive:
            assert state.kind == "source"
            return "sit:reference-alpha"
        assert state.kind == "distorted" and state.facets == ("polarity",)
        return "sit:reference-beta/distorted/Polarity"

    assert row.status == "computed" and row.information == "distinguished-costs"
    assert {key(e.state): e.cost for e in row.costs} == {
        e["state"]: e["cost"] for e in expected["costs"]
    }
    assert {key(e.state): e.weight for e in row.mass} == pytest.approx(
        {e["state"]: e["mass"] for e in expected["mass"]}, abs=2e-16, rel=0
    )


def test_complete_ties_keep_every_equal_target_at_the_cutoff():
    source, recall, unit, situation = _accounts()
    spec = _semantic_evidence(
        source, unit, situation, [SemanticEstimate(status="observed", value=0.25)] * 2
    )
    ref = local_reference(
        source,
        recall,
        LocalReferenceSpec(
            evidence=spec.model_copy(update={"max_candidates": 1, "costs": _measured_policy()}),
            population="admitted-source-targets",
            pricing="measured-semantic",
        ),
    )
    assert ref.retrieval[0].uniform_semantic and ref.rows[0].information == "uniform-costs"
    assert {m.state.anchor: m.weight for m in ref.rows[0].mass} == {
        s.id: 0.5 for s in source.states
    }


def test_equal_content_shares_one_measurement_without_collapsing_occurrences():
    source = rich(
        "source",
        "AA AA",
        (),
        (("test", 0, 2, "positive", "asserted", ()), ("test", 3, 5, "positive", "asserted", ())),
    )
    recall = rich("recall", "XX XX", (("recall", 0, 2, None), ("recall", 3, 5, None)), ())
    a, b = source.states
    u, v = recall.units
    first = SemanticCell(
        unit=u.id, target=a.id, estimate=SemanticEstimate(status="observed", value=0.2)
    )
    evidence = LocalEvidenceSpec(
        units=(RecallSelection(unit=u.id), RecallSelection(unit=v.id)),
        semantic="table",
        semantic_cells=(first,),
        costs=_measured_policy(),
    )
    spec = LocalReferenceSpec(
        evidence=evidence, pricing="measured-semantic", population="admitted-source-targets"
    )
    result = local_reference(source, recall, spec)
    assert result.universe == tuple(sorted((a.id, b.id)))
    assert {row.unit for row in result.rows} == {u.id, v.id}
    for receipt, row in zip(result.retrieval, result.rows, strict=True):
        assert (receipt.observed, receipt.missing) == (2, 0)
        assert {m.state.anchor: m.weight for m in row.mass} == {a.id: 0.5, b.id: 0.5}
    conflicting = SemanticCell(
        unit=v.id, target=b.id, estimate=SemanticEstimate(status="observed", value=0.8)
    )
    with pytest.raises(ValueError, match="equal content pairs carry conflicting"):
        local_reference(
            source,
            recall,
            spec.model_copy(
                update={
                    "evidence": evidence.model_copy(update={"semantic_cells": (first, conflicting)})
                }
            ),
        )


def test_conditional_prior_splits_a_target_share_between_equal_cost_modes():
    from storymodel.local_evidence import LocalCell
    from storymodel.local_reference import ReferenceRetrieval, _score_row
    from storymodel.sequence import SequenceRow, SequenceState, StateCost

    faithful = SequenceState(kind="source", anchor_kind="situation", anchor="a")
    other = SequenceState(kind="source", anchor_kind="situation", anchor="b")
    distorted = SequenceState(
        kind="distorted", anchor_kind="situation", anchor="b", facets=("polarity",)
    )
    states = (faithful, other, distorted)
    prices = SequenceRow(unit="r", costs=tuple(StateCost(state=s, cost=0.0) for s in states))
    cells = [
        LocalCell(
            unit="r",
            target=s.anchor,
            state=s,
            gate_members=(),
            terms={"semantic": 0.0},
            slot_defaults={},
            missing={},
            imputed={},
            eligible=("semantic",),
            measured=("semantic",),
            support=1.0,
            support_reason="assessed",
            excluded=False,
            total=0.0,
        )
        for s in states
    ]
    receipt = ReferenceRetrieval(
        unit="r",
        situation=None,
        status="ranked",
        nominations=(),
        postings_read=0,
        candidates_seen=2,
        observed=2,
        missing=0,
        ineligible=0,
        uniform_semantic=True,
        lexical_hits=(),
        cutoff_distance=0.0,
    )
    spec = LocalReferenceSpec(
        evidence=LocalEvidenceSpec(units=(RecallSelection(unit="r"),)),
        population="admitted-source-targets",
    )
    row = _score_row(prices, receipt, cells, spec)
    assert {m.state: m.weight for m in row.mass} == {faithful: 0.5, other: 0.25, distorted: 0.25}


def test_equal_segment_words_retain_distinct_member_polarity_in_score_table():
    from test_local_evidence import rich

    from storymodel.source_transitions import SourceProjectionPolicy

    source = rich(
        "source",
        "AA AA",
        (("row", 0, 2, None), ("row", 3, 5, None)),
        (("act", 0, 2, "positive", "asserted", ()), ("act", 3, 5, "negative", "asserted", ())),
    )
    recall = rich(
        "recall", "AA", (("recall", 0, 2, None),), (("act", 0, 2, "positive", "asserted", ()),)
    )
    rows = sorted(source.units, key=lambda r: r.support.spans[0].start)
    situations = sorted(source.states, key=lambda s: s.polarity != "positive")
    policy = SourceProjectionPolicy(
        memberships={s.id: r.id for s, r in zip(situations, rows, strict=True)}
    )
    evidence = LocalEvidenceSpec(
        units=(RecallSelection(unit=recall.units[0].id),),
        costs=_measured_policy(),
        projection=policy,
        semantic="table",
        semantic_cells=tuple(
            SemanticCell(
                unit=recall.units[0].id,
                target=r.id,
                estimate=SemanticEstimate(status="observed", value=distance),
            )
            for r, distance in zip(rows, (0.0, 1.0), strict=True)
        ),
    )
    result = local_reference(
        source,
        recall,
        LocalReferenceSpec(
            evidence=evidence,
            level=1,
            population="admitted-source-targets",
            pricing="measured-semantic",
        ),
    )
    assert {c.target: (c.total, c.state.kind) for c in result.cells} == {
        rows[0].id: (0.0, "source"),
        rows[1].id: (1.0, "distorted"),
    }
