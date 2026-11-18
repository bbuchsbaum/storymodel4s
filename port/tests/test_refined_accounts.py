"""Account-level witnesses for lifting actual claims into relation-aware inference."""

import json
import math
from pathlib import Path

import pytest

from storymodel.chronology import TemporalClaim
from storymodel.cli import main
from storymodel.local_evidence import (
    RefinedAccountInference,
    RefinedInferenceSpec,
    infer_accounts,
    infer_refined_accounts,
    verify_refined_accounts,
)
from storymodel.model import Context, Document, Relation, Span, Support, Unit
from storymodel.rich_account import validate_rich_account


@pytest.fixture
def example(monkeypatch):
    monkeypatch.syspath_prepend(str(Path(__file__).resolve().parents[1] / "examples"))
    from refined_alignment import example

    return example()


def test_refinement_keeps_evidence_and_changes_only_relation_supported_costs(example):
    source, recall, spec = example
    plain = infer_accounts(source, recall, spec.local)
    result = infer_refined_accounts(source, recall, spec)
    assert result.evidence == plain.evidence
    assert result.refinement.request.transitions == plain.evidence.sequence.request.transitions
    left, right = source.states[0].id, source.states[2].id
    for row, old, wanted in zip(
        result.refinement.request.rows,
        plain.evidence.sequence.request.rows,
        (left, right),
        strict=True,
    ):
        for entry, base in zip(row.costs, old.costs, strict=True):
            assert entry.state == base.state
            if entry.state.anchor == wanted:
                assert entry.cost < base.cost
            else:
                assert entry.cost == base.cost
    assert [r.claim for r in result.refinement.relations] == [recall.relations[0].id]
    assert result.refinement.preservation["causal"].stated == 1
    assert result.refinement.preservation["causal"].evaluated == 1
    assert not result.excluded_claims
    assert verify_refined_accounts(source, recall, spec, result) == result
    with pytest.raises(ValueError, match="differs"):
        verify_refined_accounts(
            source, recall, spec, result.model_copy(update={"excluded_claims": {"x": "not-stated"}})
        )


def test_two_pass_flow_and_best_path_match_exhaustive_reweighting(example):
    source, recall, spec = example
    result = infer_refined_accounts(source, recall, spec)
    request = result.evidence.sequence.request
    first, last = source.states[0].id, source.states[2].id
    left, right = ({c.state.id: c for c in row.costs} for row in request.rows)
    transitions = {
        (edge.from_state, edge.to_state): edge.probability for edge in request.transitions[0].edges
    }
    left_bonus = right_bonus = 0.0
    for _ in range(spec.policy.passes + 1):
        weights = {}
        for (i, j), probability in transitions.items():
            cost = left[i].cost + right[j].cost
            if left[i].state.anchor == first:
                cost -= left_bonus
            if right[j].state.anchor == last:
                cost -= right_bonus
            weights[i, j] = probability * math.exp(-cost / request.temperature)
        total = math.fsum(weights.values())
        flow = {pair: w / total for pair, w in weights.items()}
        on_first = sum(p for (i, _), p in flow.items() if left[i].state.anchor == first)
        on_last = sum(p for (_, j), p in flow.items() if right[j].state.anchor == last)
        left_bonus, right_bonus = spec.policy.weight * on_last, spec.policy.weight * on_first
    refined = result.refinement
    observed = {(e.from_state, e.to_state): e.probability for e in refined.inference.flow[0].mass}
    assert observed == pytest.approx(flow)
    assert refined.inference.best_path == max(flow, key=flow.get)
    anchored_left = sum(p for (i, _), p in flow.items() if left[i].state.anchor)
    anchored_right = sum(p for (_, j), p in flow.items() if right[j].state.anchor)
    assert refined.preservation["causal"].mean == pytest.approx(
        (on_first / anchored_left) * (on_last / anchored_right)
    )


def test_text_only_content_still_has_relation_evidence_and_reports_unusable_claims(example):
    source, recall, spec = example
    selections = tuple(u.model_copy(update={"content": "text-only"}) for u in spec.local.units)

    def run(account, selected):
        return infer_refined_accounts(
            source,
            account,
            spec.model_copy(update={"local": spec.local.model_copy(update={"units": selected})}),
        )

    edge = recall.relations[0]
    assert run(recall, selections).refinement.relations[0].claim == edge.id
    assert run(recall, selections[:1]).excluded_claims == {edge.id: "outside-selected-units"}
    wide = Unit(
        kind="recall",
        support=Support(
            document=recall.documents[0].id,
            spans=(Span(start=0, end=len(recall.documents[0].text)),),
        ),
    )
    overlap = validate_rich_account(recall.model_copy(update={"units": (*recall.units, wide)}))
    broad = selections[0].model_copy(update={"unit": wide.id})
    assert run(overlap, (*selections, broad)).excluded_claims == {
        edge.id: "ambiguous-unit-membership"
    }
    assert run(overlap, (broad,)).excluded_claims == {edge.id: "within-one-unit"}


def test_equal_stated_relation_is_recorded_but_has_no_direction_and_inference_is_excluded(example):
    source, recall, spec = example
    edge = recall.relations[0]

    def temporal(status):
        return TemporalClaim.create(
            source=edge.source,
            target=edge.target,
            relation="equal",
            context=edge.context,
            status=status,
            origin="synthetic",
            procedure="fixture",
            evidence=edge.evidence,
            premises=(edge.id,) if status != "surface-explicit" else (),
        )

    direct, indirect = temporal("surface-explicit"), temporal("world-knowledge-inferred")
    account = validate_rich_account(
        recall.model_copy(
            update={
                "schema_version": "storymodel.account/v7",
                "temporal_relations": (direct,),
            }
        )
    )
    result = infer_refined_accounts(source, account, spec)
    score = result.refinement.preservation["world-time"]
    assert (score.stated, score.evaluated, score.mean) == (1, 0, None)
    inferred = validate_rich_account(account.model_copy(update={"temporal_relations": (indirect,)}))
    second = infer_refined_accounts(source, inferred, spec)
    assert second.excluded_claims == {indirect.id: "not-stated"}
    assert second.refinement.preservation["world-time"].stated == 0


def test_repeated_evidence_counts_one_edge_and_attributed_events_stay_attributed(example):
    source, recall, spec = example
    edge = recall.relations[0]
    first, last = recall.states
    corroboration = Relation(
        kind="causes",
        source=first.id,
        target=last.id,
        context=first.context,
        evidence=first.evidence,
    )
    frame = Context(side="recall", kind="reported", parent=first.context, evidence=first.evidence)
    nested = tuple(s.model_copy(update={"id": "", "context": frame.id}) for s in (first, last))
    # Reconstruct identified states before citing their content addresses.
    nested = tuple(type(s).model_validate(s) for s in nested)
    attributed = Relation(
        kind="causes",
        source=nested[0].id,
        target=nested[1].id,
        context=frame.id,
        evidence=edge.evidence,
    )
    account = validate_rich_account(
        recall.model_copy(
            update={
                "contexts": (*recall.contexts, frame),
                "relations": (edge, corroboration, attributed),
                "states": (*recall.states, *nested),
            }
        )
    )
    selections = tuple(s.model_copy(update={"content": "text-only"}) for s in spec.local.units)
    result = infer_refined_accounts(
        source,
        account,
        spec.model_copy(update={"local": spec.local.model_copy(update={"units": selections})}),
    )
    assert len(result.refinement.relations) == 1
    kept, duplicate = sorted((edge.id, corroboration.id))
    assert result.refinement.relations[0].claim == kept
    assert result.excluded_claims == {
        attributed.id: "attributed-context",
        duplicate: "duplicate-unit-edge",
    }


def test_refinement_refuses_implicit_cross_document_sequence(example):
    source, recall, spec = example
    other = Document.create("another", "recall", "AA")
    unit = Unit(kind="recall", support=Support(document=other.id, spans=(Span(start=0, end=2),)))
    multi = validate_rich_account(
        recall.model_copy(
            update={
                "documents": (*recall.documents, other),
                "units": (*recall.units, unit),
                "recipe": recall.recipe.model_copy(
                    update={"input_sha256": (*recall.recipe.input_sha256, other.sha256)}
                ),
            }
        )
    )
    selections = (*spec.local.units, spec.local.units[0].model_copy(update={"unit": unit.id}))
    with pytest.raises(ValueError, match="one recall document"):
        infer_refined_accounts(
            source,
            multi,
            spec.model_copy(update={"local": spec.local.model_copy(update={"units": selections})}),
        )


def test_cli_verifies_refinement_and_requires_both_export_grants(example, tmp_path, capsys):
    source, recall, spec = example
    files = [tmp_path / f"{name}.json" for name in ("source", "recall", "spec")]
    for path, value in zip(files, (source, recall, spec), strict=True):
        path.write_text(value.model_dump_json())
    args = list(map(str, files))
    dest = tmp_path / "result.json"
    assert main(["infer-accounts", *args, "--output", str(dest)]) == 0
    assert RefinedAccountInference.model_validate_json(dest.read_bytes()) == infer_refined_accounts(
        source, recall, spec
    )
    assert main(["verify-account-inference", *args, str(dest)]) == 0
    assert json.loads(capsys.readouterr().out)["valid"]
    files[1].write_text(recall.model_copy(update={"export_permission": "denied"}).model_dump_json())
    refused = tmp_path / "refused.json"
    assert main(["infer-accounts", *args, "--output", str(refused)]) != 0
    assert not refused.exists()
    schemas = Path(__file__).resolve().parents[1] / "schemas/refined-inference.json"
    assert json.loads(schemas.read_bytes()) == {
        "spec": RefinedInferenceSpec.model_json_schema(),
        "result": RefinedAccountInference.model_json_schema(),
    }
