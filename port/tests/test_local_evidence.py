"""Adversarial controls for account-derived local lexical evidence.

The tokens are deliberately synthetic: these tests establish checked mechanics,
not semantic accuracy.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Literal

import pytest

from storymodel.accounts import make_account
from storymodel.cli import main
from storymodel.local_evidence import (
    AccountInferenceResult,
    LocalCell,
    LocalCostPolicy,
    LocalEvidenceResult,
    LocalEvidenceSpec,
    RecallSelection,
    SemanticCell,
    SemanticEstimate,
    derive_local_evidence,
    infer_accounts,
    verify_account_inference,
    verify_local_evidence,
)
from storymodel.model import Context, Document, Entity, Participation, Span, State, Support, Unit
from storymodel.rich_account import RichAccount, validate_rich_account
from storymodel.source_transitions import SourceProjectionPolicy


def support(document: Document, start: int, end: int) -> Support:
    return Support(document=document.id, spans=(Span(start=start, end=end),))


def rich(
    side: Literal["source", "recall"],
    text: str,
    unit_ranges: tuple[tuple[Literal["row", "scene", "recall"], int, int, str | None], ...],
    states: tuple[
        tuple[
            str,
            int,
            int,
            Literal["positive", "negative", "unknown"],
            Literal[
                "asserted",
                "possible",
                "probable",
                "necessary",
                "intended",
                "desired",
                "counterfactual",
                "reported",
                "unknown",
            ],
            tuple[
                tuple[
                    Literal["agent", "patient", "speaker", "recipient", "experiencer", "location"],
                    str,
                ],
                ...,
            ],
        ],
        ...,
    ],
    *,
    grant: Literal["allowed", "denied"] = "allowed",
) -> RichAccount:
    document = Document.create(f"{side}-{text}", side, text)
    root = Context(side=side, kind="narrated")
    units = tuple(
        Unit(kind=kind, support=support(document, start, end), parent=parent)
        for kind, start, end, parent in unit_ranges
    )
    entities, built = [], []
    for predicate, start, end, polarity, modality, roles in states:
        evidence = support(document, start, end)
        participants = []
        for number, (role, label) in enumerate(roles):
            entity = Entity.create(document.id, label, (evidence,))
            entities.append(entity)
            participants.append(Participation(entity=entity.id, role=role, evidence=(evidence,)))
        built.append(
            State(
                side=side,
                context=root.id,
                predicate=predicate,
                participants=tuple(participants),
                evidence=(evidence,),
                polarity=polarity,
                modality=modality,
            )
        )
    seed = make_account((document,), contexts=(root,), export_permission=grant)
    return validate_rich_account(
        RichAccount(
            documents=(document,),
            units=units,
            contexts=(root,),
            entities=tuple(entities),
            states=tuple(built),
            recipe=seed.recipe,
            export_permission=grant,
        )
    )


def one_pair(
    *, source_predicate: str = "P", recall_predicate: str = "P"
) -> tuple[RichAccount, RichAccount, State, Unit, State]:
    source = rich("source", "SA", (), ((source_predicate, 0, 2, "positive", "asserted", ()),))
    recall = rich(
        "recall",
        "RB",
        (("recall", 0, 2, None),),
        ((recall_predicate, 0, 2, "positive", "asserted", ()),),
    )
    return source, recall, source.states[0], recall.units[0], recall.states[0]


def segmented_source(
    *, second_predicate: str = "PP", second_polarity: Literal["positive", "negative"] = "positive"
) -> tuple[RichAccount, Unit, tuple[State, State]]:
    """Two declared row members under one scene; hierarchy is never inferred from text."""
    document = Document.create("segmented", "source", "PP QQ")
    root = Context(side="source", kind="narrated")
    whole, first, second = support(document, 0, 5), support(document, 0, 2), support(document, 3, 5)
    scene = Unit(kind="scene", support=whole)
    row_p = Unit(kind="row", parent=scene.id, support=first)
    row_q = Unit(kind="row", parent=scene.id, support=second)
    states = (
        State(
            side="source",
            context=root.id,
            predicate="PP",
            evidence=(first,),
            polarity="positive",
            modality="asserted",
        ),
        State(
            side="source",
            context=root.id,
            predicate=second_predicate,
            evidence=(second,),
            polarity=second_polarity,
            modality="asserted",
        ),
    )
    return (
        validate_rich_account(
            RichAccount(
                documents=(document,),
                units=(scene, row_p, row_q),
                contexts=(root,),
                states=states,
                recipe=make_account((document,), contexts=(root,)).recipe,
                export_permission="allowed",
            )
        ),
        scene,
        states,
    )


def reported_source() -> tuple[RichAccount, State]:
    document = Document.create("reported", "source", "PP")
    root = Context(side="source", kind="narrated")
    evidence = support(document, 0, 2)
    reported = Context(side="source", kind="reported", parent=root.id, evidence=(evidence,))
    state = State(side="source", context=reported.id, predicate="PP", evidence=(evidence,))
    return (
        validate_rich_account(
            RichAccount(
                documents=(document,),
                units=(),
                contexts=(root, reported),
                states=(state,),
                recipe=make_account((document,), contexts=(root, reported)).recipe,
                export_permission="allowed",
            )
        ),
        state,
    )


def test_auto_and_explicit_selection_require_all_evidence_while_text_only_bypasses_ambiguity() -> (
    None
):
    source, _, target, _, _ = one_pair()
    recall = rich(
        "recall",
        "AA BB CC",
        (("recall", 0, 5, None),),
        (
            ("P", 0, 2, "positive", "asserted", ()),
            ("P", 3, 5, "positive", "asserted", ()),
            ("P", 3, 8, "positive", "asserted", ()),
        ),
    )
    unit = recall.units[0]
    with pytest.raises(ValueError, match="several situations"):
        derive_local_evidence(
            source, recall, LocalEvidenceSpec(units=(RecallSelection(unit=unit.id),))
        )
    with pytest.raises(ValueError, match="all evidence within"):
        derive_local_evidence(
            source,
            recall,
            LocalEvidenceSpec(
                units=(RecallSelection(unit=unit.id, situation=recall.states[2].id),)
            ),
        )
    result = derive_local_evidence(
        source,
        recall,
        LocalEvidenceSpec(
            units=(RecallSelection(unit=unit.id, content="text-only", targets=(target.id,)),)
        ),
    )
    assert result.retrieval[0].situation is None and result.retrieval[0].status == "supplied"


def test_sparse_nomination_is_bounded_and_supplied_targets_reject_foreign_or_duplicates() -> None:
    source, recall, target, unit, situation = one_pair()
    selection = RecallSelection(unit=unit.id, situation=situation.id)
    limited = derive_local_evidence(
        source, recall, LocalEvidenceSpec(units=(selection,), max_postings=1, max_candidates=1)
    ).retrieval[0]
    assert limited.status == "search-limited" and limited.postings_read == 1
    with pytest.raises(ValueError, match="duplicate nominated target"):
        RecallSelection(unit=unit.id, targets=(target.id, target.id))
    with pytest.raises(ValueError, match="outside the checked source"):
        derive_local_evidence(
            source,
            recall,
            LocalEvidenceSpec(units=(RecallSelection(unit=unit.id, targets=("foreign",)),)),
        )


def test_default_missing_sensory_scaling_has_literal_energy_and_support() -> None:
    source, recall, target, unit, situation = one_pair(source_predicate="P", recall_predicate="Q")
    result = derive_local_evidence(
        source,
        recall,
        LocalEvidenceSpec(
            units=(
                RecallSelection(
                    unit=unit.id, situation=situation.id, targets=(target.id,), function="inference"
                ),
            )
        ),
    )
    cell = result.cells[0]
    assert cell.missing["sensory"] == "all-missing"
    assert cell.support == pytest.approx(3.2 / 3.35)
    assert cell.total == pytest.approx(1.7 * 3.35 / 3.2 + 0.1)


def test_semantic_observed_missing_and_ineligible_keep_separate_measurement_statuses() -> None:
    source, recall, target, unit, situation = one_pair(source_predicate="P", recall_predicate="Q")
    selected = RecallSelection(unit=unit.id, situation=situation.id, targets=(target.id,))
    observed = derive_local_evidence(
        source,
        recall,
        LocalEvidenceSpec(
            units=(selected,),
            semantic="table",
            semantic_cells=(
                SemanticCell(
                    unit=unit.id,
                    target=target.id,
                    estimate=SemanticEstimate(status="observed", value=0.25),
                ),
            ),
        ),
    ).cells[0]
    missing = derive_local_evidence(
        source, recall, LocalEvidenceSpec(units=(selected,), semantic="table")
    ).cells[0]
    ineligible = derive_local_evidence(
        source,
        recall,
        LocalEvidenceSpec(
            units=(selected,),
            semantic="table",
            semantic_cells=(
                SemanticCell(
                    unit=unit.id,
                    target=target.id,
                    estimate=SemanticEstimate(status="ineligible", reason="no-provider"),
                ),
            ),
        ),
    ).cells[0]
    assert "semantic" in observed.measured and observed.terms["semantic"] == 0.25
    assert (
        missing.imputed["semantic"] == "provider-abstained" and "semantic" not in missing.measured
    )
    assert ineligible.missing["semantic"] == "no-provider" and "semantic" not in ineligible.eligible
    assert observed.support == pytest.approx(3.2 / 3.35)
    assert missing.support == pytest.approx(2.2 / 3.35)
    assert ineligible.support == pytest.approx(2.2 / 2.35)


def test_unrepresented_chart_and_structural_weights_are_inert_and_zero_support_is_explicit() -> (
    None
):
    source, recall, target, unit, situation = one_pair()
    selected = RecallSelection(unit=unit.id, situation=situation.id, targets=(target.id,))
    baseline = derive_local_evidence(source, recall, LocalEvidenceSpec(units=(selected,))).cells[0]
    weights = dict(LocalCostPolicy().weights) | {"chart": 1e100, "structural": 1e100}
    changed = derive_local_evidence(
        source, recall, LocalEvidenceSpec(units=(selected,), costs=LocalCostPolicy(weights=weights))
    ).cells[0]
    assert (changed.total, changed.support, changed.eligible) == (
        baseline.total,
        baseline.support,
        baseline.eligible,
    )
    zero = LocalCostPolicy(weights={name: 0.0 for name in LocalCostPolicy().weights})
    retained = derive_local_evidence(
        source, recall, LocalEvidenceSpec(units=(selected,), costs=zero)
    ).cells[0]
    assert (
        retained.support is None
        and retained.support_reason == "zero-eligible-weight"
        and not retained.excluded
    )
    only_missing = LocalCostPolicy(weights=dict(zero.weights) | {"sensory": 1.0})
    excluded = derive_local_evidence(
        source, recall, LocalEvidenceSpec(units=(selected,), costs=only_missing)
    ).cells[0]
    assert excluded.support == 0 and excluded.excluded and excluded.total is None


def test_state_gates_cover_role_polarity_context_and_unrealized_modality_before_pricing() -> None:
    source, recall, target, unit, situation = one_pair()

    def cell(source_account: RichAccount, source_target: State) -> LocalCell:
        return derive_local_evidence(
            source_account,
            recall,
            LocalEvidenceSpec(
                units=(
                    RecallSelection(
                        unit=unit.id,
                        situation=situation.id,
                        targets=(source_target.id,),
                    ),
                )
            ),
        ).cells[0]

    # Separate controls retain state-only polarity/modality; events cannot express them.
    negative = rich("source", "SA", (), (("P", 0, 2, "negative", "asserted", ()),))
    assert "polarity" in cell(negative, negative.states[0]).state.facets
    unrealized = rich("source", "SA", (), (("P", 0, 2, "positive", "intended", ()),))
    assert "modality" in cell(unrealized, unrealized.states[0]).state.facets


def test_role_reversal_gates_before_costing_and_four_role_terms_ignore_input_order() -> None:
    roles: tuple[
        tuple[Literal["agent", "patient", "speaker", "recipient", "experiencer", "location"], str],
        ...,
    ] = (
        ("agent", "A"),
        ("agent", "C"),
        ("patient", "B"),
        ("patient", "D"),
    )
    source = rich("source", "SA", (), (("P", 0, 2, "positive", "asserted", roles),))
    recall = rich(
        "recall",
        "RB",
        (("recall", 0, 2, None),),
        (
            (
                "P",
                0,
                2,
                "positive",
                "asserted",
                (("agent", "C"), ("agent", "A"), ("patient", "D"), ("patient", "B")),
            ),
        ),
    )
    selected = RecallSelection(
        unit=recall.units[0].id,
        situation=recall.states[0].id,
        targets=(source.states[0].id,),
    )
    ordered = derive_local_evidence(source, recall, LocalEvidenceSpec(units=(selected,))).cells[0]
    assert ordered.terms["entity"] == 0 and "role-reversal" not in ordered.state.facets
    reversed_roles = rich(
        "recall",
        "RB",
        (("recall", 0, 2, None),),
        (("P", 0, 2, "positive", "asserted", (("agent", "B"), ("patient", "A"))),),
    )
    reversed_selected = RecallSelection(
        unit=reversed_roles.units[0].id,
        situation=reversed_roles.states[0].id,
        targets=(source.states[0].id,),
    )
    gated = derive_local_evidence(
        source, reversed_roles, LocalEvidenceSpec(units=(reversed_selected,))
    ).cells[0]
    assert gated.state.kind == "distorted" and gated.state.facets == ("role-reversal",)


@pytest.mark.parametrize(
    ("predicate", "polarity", "declare_members", "kind", "facets", "engaged"),
    [
        ("QQ", "positive", True, "distorted", ("polarity",), [False, True]),
        ("PP", "positive", True, "distorted", ("polarity",), [True, True]),
        ("PP", "negative", True, "source", (), [True, True]),
        ("PP", "positive", False, "source", (), []),
    ],
)
def test_segment_gate_uses_every_engaged_actual_member(
    predicate, polarity, declare_members, kind, facets, engaged
):
    source, scene, states = segmented_source(second_predicate=predicate, second_polarity=polarity)
    recall = rich(
        "recall", "PP", (("recall", 0, 2, None),), (("PP", 0, 2, "negative", "asserted", ()),)
    )
    selection = RecallSelection(unit=recall.units[0].id, targets=(scene.id,))
    memberships = {s.id: u.id for s, u in zip(states, source.units[1:])} if declare_members else {}
    cell = derive_local_evidence(
        source,
        recall,
        LocalEvidenceSpec(
            units=(selection,), projection=SourceProjectionPolicy(memberships=memberships)
        ),
    ).cells[0]
    assert cell.state.kind == kind and cell.state.facets == facets
    assert sorted(g.engaged for g in cell.gate_members) == engaged


def test_reported_context_and_modality_gate_only_asserted_recall_state() -> None:
    source, target = reported_source()
    asserted = rich(
        "recall", "PP", (("recall", 0, 2, None),), (("PP", 0, 2, "positive", "asserted", ()),)
    )
    selected = RecallSelection(
        unit=asserted.units[0].id, situation=asserted.states[0].id, targets=(target.id,)
    )
    context_cell = derive_local_evidence(
        source, asserted, LocalEvidenceSpec(units=(selected,))
    ).cells[0]
    assert "context" in context_cell.state.facets
    unknown = rich(
        "recall", "PP", (("recall", 0, 2, None),), (("PP", 0, 2, "positive", "unknown", ()),)
    )
    unknown_selected = selected.model_copy(
        update={"unit": unknown.units[0].id, "situation": unknown.states[0].id}
    )
    assert (
        derive_local_evidence(source, unknown, LocalEvidenceSpec(units=(unknown_selected,)))
        .cells[0]
        .state.kind
        == "source"
    )


def test_inference_uses_derived_modes_and_external_populations_without_mutating_accounts() -> None:
    source, recall, target, unit, situation = one_pair()
    before_source, before_recall = source.model_dump_json(), recall.model_dump_json()
    normal = infer_accounts(
        source,
        recall,
        LocalEvidenceSpec(
            units=(RecallSelection(unit=unit.id, situation=situation.id, targets=(target.id,)),)
        ),
    )
    assert any(
        cost.state.kind == "source" for cost in normal.evidence.sequence.request.rows[0].costs
    )
    assert source.model_dump_json() == before_source and recall.model_dump_json() == before_recall
    blank = rich("recall", "the", (("recall", 0, 3, None),), ())
    abstained = infer_accounts(
        source,
        blank,
        LocalEvidenceSpec(units=(RecallSelection(unit=blank.units[0].id, content="text-only"),)),
    )
    assert abstained.evidence.retrieval[0].status == "abstained"
    assert {cost.state.external for cost in abstained.evidence.sequence.request.rows[0].costs} == {
        "unranked"
    }
    no_hit = rich("recall", "ZZ", (("recall", 0, 2, None),), ())
    ranked = infer_accounts(
        source,
        no_hit,
        LocalEvidenceSpec(units=(RecallSelection(unit=no_hit.units[0].id, content="text-only"),)),
    )
    assert ranked.evidence.retrieval[0].status == "empty"
    assert {cost.state.external for cost in ranked.evidence.sequence.request.rows[0].costs} == {
        "association",
        "commentary",
        "source-consistent-inference",
        "intrusion",
        "uninterpretable",
    }
    assert all(cost.cost == 1.0 for cost in ranked.evidence.sequence.request.rows[0].costs)


def test_verifiers_refuse_tampering_forged_boolean_and_denied_readout() -> None:
    source, recall, target, unit, situation = one_pair()
    spec = LocalEvidenceSpec(
        units=(RecallSelection(unit=unit.id, situation=situation.id, targets=(target.id,)),)
    )
    evidence = derive_local_evidence(source, recall, spec)
    with pytest.raises(ValueError, match="local evidence differs"):
        verify_local_evidence(source, recall, spec, evidence.model_copy(update={"cells": ()}))
    inference = infer_accounts(source, recall, spec)
    forged = inference.inference.model_copy(
        update={"log_normalizer": inference.inference.log_normalizer + 0.1}
    )
    with pytest.raises(ValueError, match="account inference differs"):
        verify_account_inference(
            source,
            recall,
            spec,
            inference.model_copy(update={"inference": forged}),
        )
    with pytest.raises(ValueError):
        LocalEvidenceSpec(units=(RecallSelection(unit=unit.id),), max_candidates=True)
    denied = source.model_copy(update={"export_permission": "denied"})
    assert (
        derive_local_evidence(denied, recall, spec).sequence.request.export_permission == "denied"
    )


def test_subnormal_priced_weight_scales_without_losing_the_measured_energy():
    source, recall, target, unit, _ = one_pair()
    spec = LocalEvidenceSpec(
        units=(RecallSelection(unit=unit.id, targets=(target.id,)),),
        semantic="table",
        semantic_cells=(
            SemanticCell(
                unit=unit.id,
                target=target.id,
                estimate=SemanticEstimate(status="observed", value=0.5),
            ),
        ),
        costs=LocalCostPolicy(weights={"semantic": 5e-324, "sensory": 1.0}),
    )
    cell = derive_local_evidence(source, recall, spec).cells[0]
    assert cell.support == 5e-324 and not cell.excluded
    assert cell.total == 0.5  # (.5 * 5e-324) * (1 + 5e-324) / 5e-324
    with pytest.raises(ValueError, match="aggregate"):
        LocalCostPolicy(weights={"semantic": 1e308, "entity": 1e308})
    with pytest.raises(ValueError, match="positive support"):
        derive_local_evidence(
            source,
            recall,
            spec.model_copy(
                update={
                    "semantic_cells": (),
                    "costs": LocalCostPolicy(weights={"semantic": 1e308, "entity": 1e-308}),
                }
            ),
        )


def test_semantic_imputation_or_ineligibility_cannot_rank_without_weighted_measurement():
    source, recall, target, unit, _ = one_pair()
    for estimate in (
        SemanticEstimate(status="missing", reason="abstained"),
        SemanticEstimate(status="ineligible", reason="not-comparable"),
    ):
        spec = LocalEvidenceSpec(
            units=(RecallSelection(unit=unit.id, targets=(target.id,)),),
            semantic="table",
            semantic_cells=(SemanticCell(unit=unit.id, target=target.id, estimate=estimate),),
            costs=LocalCostPolicy(weights={"semantic": 1.0}),
        )
        result = derive_local_evidence(source, recall, spec)
        assert result.cells[0].excluded and result.cells[0].total is None
        assert all(c.state.kind == "external" for c in result.sequence.request.rows[0].costs)
        assert result.cells[0].support == (0.0 if estimate.status == "missing" else None)


def test_zero_cost_and_perfect_semantic_score_cannot_readmit_gated_faithful_mode():
    source, recall, target, unit, _ = one_pair()
    negative = rich(
        "recall", "RB", (("recall", 0, 2, None),), (("P", 0, 2, "negative", "asserted", ()),)
    )
    spec = LocalEvidenceSpec(
        units=(RecallSelection(unit=unit.id, targets=(target.id,)),),
        semantic="table",
        semantic_cells=(
            SemanticCell(
                unit=unit.id,
                target=target.id,
                estimate=SemanticEstimate(status="observed", value=0.0),
            ),
        ),
        costs=LocalCostPolicy(weights={"semantic": 1.0}),
    )
    result = infer_accounts(source, negative, spec)
    cell = result.evidence.cells[0]
    assert cell.total == 0.0 and cell.state.facets == ("polarity",)
    assert not any(c.state.kind == "source" for c in result.evidence.sequence.request.rows[0].costs)


def test_local_evidence_cli_schema_and_both_export_grants(tmp_path, capsys):
    source, recall, target, unit, _ = one_pair()
    spec = LocalEvidenceSpec(units=(RecallSelection(unit=unit.id, targets=(target.id,)),))
    paths = [tmp_path / name for name in ("source.json", "recall.json", "spec.json")]
    for path, artifact in zip(paths, (source, recall, spec)):
        path.write_text(artifact.model_dump_json())
    expected = infer_accounts(source, recall, spec)
    for command, options, name, value in (
        ("derive-local-evidence", [], "local", expected.evidence),
        (
            "derive-local-evidence",
            ["--request-only"],
            "request",
            expected.evidence.sequence.request,
        ),
        ("infer-accounts", [], "inference", expected),
    ):
        output = tmp_path / (name + ".json")
        assert main([command, *map(str, paths), *options, "--output", str(output)]) == 0
        assert json.loads(output.read_text()) == value.model_dump(mode="json")
    assert (
        main(["verify-account-inference", *map(str, paths), str(tmp_path / "inference.json")]) == 0
    )
    assert json.loads(capsys.readouterr().out)["valid"]
    schema = Path(__file__).resolve().parents[1] / "schemas/local-evidence.json"
    assert json.loads(schema.read_text()) == {
        "spec": LocalEvidenceSpec.model_json_schema(),
        "evidence": LocalEvidenceResult.model_json_schema(),
        "inference": AccountInferenceResult.model_json_schema(),
    }
    for account_index in (0, 1):
        paths[0].write_text(source.model_dump_json())
        paths[1].write_text(recall.model_dump_json())
        denied = (source, recall)[account_index].model_copy(update={"export_permission": "denied"})
        paths[account_index].write_text(denied.model_dump_json())
        assert (
            main(
                [
                    "infer-accounts",
                    str(paths[0]),
                    str(paths[1]),
                    str(tmp_path / "must-not-read.json"),
                ]
            )
            == 2
        )
        assert "denies" in capsys.readouterr().err


def test_unrepresentable_distortion_term_refuses_before_weighting_and_cli_publication(
    tmp_path, capsys
):
    source = rich("source", "SA", (), (("P", 0, 2, "positive", "intended", ()),))
    recall = rich(
        "recall", "RB", (("recall", 0, 2, None),), (("P", 0, 2, "negative", "asserted", ()),)
    )
    for weights in ({}, {"semantic": 1.0}, {"distortion": 1e-308}):
        spec = LocalEvidenceSpec(
            units=(RecallSelection(unit=recall.units[0].id, targets=(source.states[0].id,)),),
            costs=LocalCostPolicy(weights=weights, distortion_penalty=1e308),
        )
        with pytest.raises(ValueError, match="raw distortion term"):
            derive_local_evidence(source, recall, spec)
    paths = [tmp_path / name for name in ("source.json", "recall.json", "spec.json")]
    for path, artifact in zip(paths, (source, recall, spec)):
        path.write_text(artifact.model_dump_json())
    output = tmp_path / "must-not-publish.json"
    assert main(["infer-accounts", *map(str, paths), "--output", str(output)]) == 2
    assert "raw distortion term" in capsys.readouterr().err and not output.exists()
    nearby = derive_local_evidence(
        source,
        recall,
        spec.model_copy(update={"costs": LocalCostPolicy(weights={}, distortion_penalty=8e307)}),
    ).cells[0]
    assert nearby.state.facets == ("polarity", "modality")
    assert nearby.terms["distortion"] == 1.6e308 and nearby.total == 0.0
    # A large but representable raw term remains lawful; do not impose a global penalty cap.
    one = rich("source", "SA", (), (("P", 0, 2, "positive", "asserted", ()),))
    spec = LocalEvidenceSpec(
        units=(RecallSelection(unit=recall.units[0].id, targets=(one.states[0].id,)),),
        costs=LocalCostPolicy(weights={}, distortion_penalty=1e308),
    )
    cell = derive_local_evidence(one, recall, spec).cells[0]
    assert cell.state.facets == ("polarity",) and cell.terms["distortion"] == 1e308
    assert cell.total == 0.0 and cell.support is None
