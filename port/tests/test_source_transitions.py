"""Independent source-graph projection and transition-mixture witnesses.

All fixtures use abstract tokens.  Expected probabilities are derived here from
the stated Bernoulli/softmax model rather than from the production helpers.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import pytest

from storymodel.accounts import make_account
from storymodel.cli import main
from storymodel.model import (
    Context,
    Document,
    Entity,
    Event,
    Participation,
    Relation,
    Span,
    State,
    Support,
    Unit,
)
from storymodel.rich_account import upgrade_account, validate_rich_account
from storymodel.sequence import SequenceRow, SequenceState, StateCost, infer_sequence
from storymodel.source_transitions import (
    SemanticLink,
    SourceProjectionPolicy,
    SourceSequenceResult,
    SourceSequenceSpec,
    TransitionWeights,
    build_source_sequence,
    project_source,
    verify_source_projection,
    verify_source_sequence,
)


def support(document: Document, start: int, end: int) -> Support:
    return Support(document=document.id, spans=(Span(start=start, end=end),))


def source_account(*, grant: str = "allowed"):
    """A small layered source graph: only declared memberships make hierarchy."""

    first = Document.create("tokens-one", "source", "😀 AA BB CC")
    second = Document.create("tokens-two", "source", "AA BB")
    aa, bb, cc = support(first, 3, 5), support(first, 6, 8), support(first, 9, 11)
    other = support(second, 0, 2)
    root = Context(side="source", kind="narrated")
    attributed = Context(side="source", kind="reported", parent=root.id, evidence=(other,))
    named_aa = Entity.create(first.id, "AA", (aa,))
    same_label_other_id = Entity.create(first.id, "AA", (cc,))
    a = Event(
        side="source",
        context=root.id,
        predicate="AA",
        participants=(Participation(entity=named_aa.id, role="agent", evidence=(aa,)),),
        evidence=(aa,),
    )
    b = Event(
        side="source",
        context=root.id,
        predicate="BB",
        participants=(Participation(entity=named_aa.id, role="agent", evidence=(bb,)),),
        evidence=(bb,),
    )
    c = Event(
        side="source",
        context=root.id,
        predicate="CC",
        participants=(Participation(entity=same_label_other_id.id, role="agent", evidence=(cc,)),),
        evidence=(cc,),
    )
    attributed_a = Event(side="source", context=attributed.id, predicate="AA", evidence=(other,))
    attributed_b = Event(side="source", context=attributed.id, predicate="BB", evidence=(other,))
    foreign_document = Event(side="source", context=root.id, predicate="AA", evidence=(other,))
    multi_document = Event(side="source", context=root.id, predicate="AA", evidence=(aa, other))
    outer = Unit(kind="scene", support=support(first, 3, 11))
    inner = Unit(kind="scene", parent=outer.id, support=support(first, 3, 8))
    row_a = Unit(kind="row", parent=inner.id, support=aa)
    row_b = Unit(kind="row", parent=inner.id, support=bb)
    row_c = Unit(kind="row", parent=outer.id, support=cc)
    relations = (
        Relation(kind="before", source=a.id, target=b.id, context=root.id, evidence=(aa,)),
        Relation(kind="before", source=b.id, target=c.id, context=root.id, evidence=(bb,)),
        # The shortcut must be removed by the independent cover expectation below.
        Relation(kind="before", source=a.id, target=c.id, context=root.id, evidence=(aa,)),
        Relation(
            kind="before",
            source=attributed_b.id,
            target=attributed_a.id,
            context=attributed.id,
            evidence=(other,),
        ),
        Relation(kind="causes", source=a.id, target=b.id, context=root.id, evidence=(aa,)),
        # Generic relations are not a substitute for account-unit containment.
        Relation(kind="part_of", source=a.id, target=c.id, context=root.id, evidence=(aa,)),
    )
    account = make_account(
        (first, second),
        units=(outer, inner, row_a, row_b, row_c),
        contexts=(root, attributed),
        entities=(named_aa, same_label_other_id),
        events=(a, b, c, attributed_a, attributed_b, foreign_document, multi_document),
        relations=relations,
        export_permission=grant,
    )
    return account, (a, b, c, foreign_document, multi_document), (outer, inner, row_a, row_b, row_c)


def recall_account(*, grant: str = "allowed"):
    document = Document.create("recall-tokens", "recall", "AA BB CC")
    units = tuple(
        Unit(kind="recall", support=support(document, start, start + 2)) for start in (0, 3, 6)
    )
    return make_account((document,), units=units, export_permission=grant), units


def source_state(anchor: str) -> SequenceState:
    return SequenceState(kind="source", anchor_kind="situation", anchor=anchor)


def distorted_state(anchor: str) -> SequenceState:
    return SequenceState(
        kind="distorted", anchor_kind="situation", anchor=anchor, facets=("action",)
    )


def external(kind: str) -> SequenceState:
    return SequenceState(kind="external", external=kind)


def sequence_row(unit: str, *states: SequenceState) -> SequenceRow:
    return SequenceRow(unit=unit, costs=tuple(StateCost(state=state, cost=0.0) for state in states))


def edge_probability(result, step: int, left: SequenceState, right: SequenceState) -> float:
    return next(
        edge.probability
        for edge in result.request.transitions[step].edges
        if edge.from_state == left.id and edge.to_state == right.id
    )


def total_external_mass(result, step: int, origin: SequenceState) -> float:
    return math.fsum(
        edge.probability
        for edge in result.request.transitions[step].edges
        if edge.from_state == origin.id
        and edge.to_state.startswith("sequence-state:")
        and any(
            entry.state.id == edge.to_state and entry.state.external is not None
            for entry in result.request.rows[step + 1].costs
        )
    )


def test_project_source_uses_declared_containment_and_checked_layers_only():
    account, (a, b, c, foreign, multi), (outer, inner, row_a, _, _) = source_account()
    policy = SourceProjectionPolicy(
        scoring_documents=(account.documents[0].id,),
        memberships={a.id: row_a.id},
        entity_fanout=2,
        semantic_links=(SemanticLink(source=a.id, target=c.id, weight=0.25),),
    )
    projection = project_source(account, policy)
    nodes = {node.anchor: node for node in projection.nodes}
    edges = {(edge.layer, edge.source, edge.target): edge.weight for edge in projection.edges}

    # AA starts at UTF-16 offset 3, ends at 5, and the document has 11 UTF-16 units.
    assert nodes[a.id].position == pytest.approx(4 / 11)
    assert nodes[foreign.id].position is None
    assert nodes[multi.id].document is nodes[multi.id].onset is nodes[multi.id].position is None
    assert nodes[a.id].parent == row_a.id
    assert nodes[row_a.id].parent == inner.id and nodes[inner.id].parent == outer.id
    assert nodes[outer.id].level > nodes[inner.id].level > nodes[row_a.id].level > nodes[a.id].level

    assert ("world-time", a.id, b.id) in edges and ("world-time", b.id, c.id) in edges
    assert ("world-time", a.id, c.id) not in edges  # transitive shortcut is covered
    reported = [event for event in account.events if event.context != a.context]
    assert len(reported) == 2
    assert not any(
        edge.layer == "world-time" and edge.source in {event.id for event in reported}
        for edge in projection.edges
    )  # attributed before is not root-world time
    assert ("hierarchy", row_a.id, a.id) in edges
    assert ("hierarchy", a.id, c.id) not in edges  # generic part_of is not unit hierarchy
    assert ("entity", a.id, b.id) in edges
    assert ("entity", b.id, c.id) not in edges  # same label is not entity identity
    assert ("semantic", a.id, c.id) in edges
    assert verify_source_projection(account, policy, projection) == projection
    with pytest.raises(ValueError, match="differs"):
        verify_source_projection(account, policy, projection.model_copy(update={"nodes": ()}))


def test_graph_transition_probabilities_features_and_receipts_are_independent():
    source, (a, b, c, _, _), (_, _, row_a, _, _) = source_account()
    recall, units = recall_account()
    faithful_a, faithful_b, faithful_c = source_state(a.id), source_state(b.id), source_state(c.id)
    distorted_b = distorted_state(b.id)
    association, commentary, unranked = (
        external("association"),
        external("commentary"),
        external("unranked"),
    )
    policy = SourceProjectionPolicy(
        scoring_documents=(source.documents[0].id,),
        memberships={a.id: row_a.id},
        semantic_links=(SemanticLink(source=a.id, target=c.id, weight=0.25),),
    )
    # Scores from A are B=2+3+0.7 and C=0.25; all omitted weights are exactly zero.
    weights = TransitionWeights(
        theta={
            "discourse-successor": 2.0,
            "causal-neighbor": 3.0,
            "same-entity-thread": 0.7,
            "semantic-neighbor": 1.0,
            "external-in": 0.0,
            "external-stay": 0.0,
        }
    )
    spec = SourceSequenceSpec(
        rows=(
            sequence_row(units[0].id, faithful_a, association),
            sequence_row(units[1].id, faithful_b, distorted_b, faithful_c, commentary, association),
            sequence_row(units[2].id, unranked),
        ),
        projection=policy,
        weights=weights,
    )
    result = build_source_sequence(source, recall, spec)
    diagnostics = {(item.source, item.target): item for item in result.diagnostics}
    ab, ac = diagnostics[a.id, b.id], diagnostics[a.id, c.id]
    assert ab.features == {
        "stay": 0.0,
        "discourse-successor": 1.0,
        "world-time-successor": 1.0,
        "causal-neighbor": 1.0,
        "hierarchy-up": 0.0,
        "hierarchy-down": 0.0,
        "same-entity-thread": 1.0,
        "semantic-neighbor": 0.0,
        "cause-to-effect": 1.0,
        "effect-to-cause": 0.0,
        "backward": 0.0,
        "long-jump": pytest.approx(3 / 11),
    }
    assert ab.score == pytest.approx(5.7)
    assert ac.score == pytest.approx(0.25)

    # Zero logit gives 1/2 external mass.  The source half uses a three-way softmax,
    # where faithful and distorted B have equal A->B scores but distinct state IDs.
    denominator = 2 * math.exp(5.7) + math.exp(0.25)
    expected_b = 0.5 * math.exp(5.7) / denominator
    expected_c = 0.5 * math.exp(0.25) / denominator
    assert faithful_b.id != distorted_b.id
    assert edge_probability(result, 0, faithful_a, faithful_b) == pytest.approx(expected_b)
    assert edge_probability(result, 0, faithful_a, distorted_b) == pytest.approx(expected_b)
    assert edge_probability(result, 0, faithful_a, faithful_c) == pytest.approx(expected_c)
    assert edge_probability(result, 0, faithful_a, commentary) == pytest.approx(0.25)
    assert edge_probability(result, 0, faithful_a, association) == pytest.approx(0.25)
    # External origins do not inherit source attractiveness: their zero logit is also half/half.
    assert edge_probability(result, 0, association, faithful_b) == pytest.approx(1 / 6)
    assert edge_probability(result, 0, association, distorted_b) == pytest.approx(1 / 6)
    assert edge_probability(result, 0, association, faithful_c) == pytest.approx(1 / 6)
    assert edge_probability(result, 0, association, commentary) == pytest.approx(0.25)
    assert edge_probability(result, 0, association, association) == pytest.approx(0.25)
    assert edge_probability(result, 1, faithful_b, unranked) == pytest.approx(1.0)
    assert result.request.export_permission == "allowed"
    assert verify_source_sequence(source, recall, spec, result) == result
    with pytest.raises(ValueError, match="differs"):
        verify_source_sequence(
            source, recall, spec, result.model_copy(update={"spec_sha256": "0" * 64})
        )


def test_built_request_has_exhaustive_independent_path_marginals_and_flows():
    source, (a, b, c, _, _), _ = source_account()
    recall, units = recall_account()
    a_state, b_state, c_state = source_state(a.id), source_state(b.id), source_state(c.id)
    association, commentary = external("association"), external("commentary")
    weights = TransitionWeights(
        theta={"causal-neighbor": 2.0, "semantic-neighbor": 1.0, "external-in": 0.0}
    )
    spec = SourceSequenceSpec(
        rows=(
            sequence_row(units[0].id, a_state, association),
            sequence_row(units[1].id, b_state, c_state, commentary),
        ),
        projection=SourceProjectionPolicy(
            semantic_links=(SemanticLink(source=a.id, target=c.id, weight=0.5),)
        ),
        weights=weights,
    )
    built = build_source_sequence(source, recall, spec)
    inferred = infer_sequence(built.request)

    # Source A has B score 2 and C score .5; its external half is explicit.
    score_b, score_c = 2.0, 0.5
    denominator = math.exp(score_b) + math.exp(score_c)
    transitions = {
        (a_state.id, b_state.id): 0.5 * math.exp(score_b) / denominator,
        (a_state.id, c_state.id): 0.5 * math.exp(score_c) / denominator,
        (a_state.id, commentary.id): 0.5,
        # An external origin has no source-attractiveness score: equal source split plus exit.
        (association.id, b_state.id): 0.25,
        (association.id, c_state.id): 0.25,
        (association.id, commentary.id): 0.5,
    }
    path_weights = {
        pair: 0.5 * probability for pair, probability in transitions.items()
    }  # equal first-layer prior and zero state costs
    normalizer = math.fsum(path_weights.values())
    expected_first = {
        state: math.fsum(weight for (left, _), weight in path_weights.items() if left == state)
        / normalizer
        for state in (a_state.id, association.id)
    }
    expected_second = {
        state: math.fsum(weight for (_, right), weight in path_weights.items() if right == state)
        / normalizer
        for state in (b_state.id, c_state.id, commentary.id)
    }
    assert inferred.log_normalizer == pytest.approx(math.log(normalizer))
    assert {mass.state: mass.probability for mass in inferred.posterior[0].mass} == pytest.approx(
        expected_first
    )
    assert {mass.state: mass.probability for mass in inferred.posterior[1].mass} == pytest.approx(
        expected_second
    )
    assert {
        (mass.from_state, mass.to_state): mass.probability for mass in inferred.flow[0].mass
    } == pytest.approx({pair: weight / normalizer for pair, weight in path_weights.items()})


def test_position_features_omit_unpositioned_pairs_and_guard_hierarchy_upward_moves():
    source, (a, _, c, foreign, multi), (outer, _, _, _, row_c) = source_account()
    recall, units = recall_account()
    a_state, c_state = source_state(a.id), source_state(c.id)
    outer_state = SequenceState(kind="source", anchor_kind="segment", anchor=outer.id)
    foreign_state, multi_state = source_state(foreign.id), source_state(multi.id)
    policy = SourceProjectionPolicy(
        scoring_documents=tuple(d.id for d in source.documents), memberships={c.id: row_c.id}
    )
    spec = SourceSequenceSpec(
        rows=(
            sequence_row(units[0].id, a_state, c_state, external("association")),
            sequence_row(
                units[1].id,
                a_state,
                outer_state,
                foreign_state,
                multi_state,
                external("commentary"),
            ),
        ),
        projection=policy,
    )
    diagnostics = {
        (item.source, item.target): item.features
        for item in build_source_sequence(source, recall, spec).diagnostics
    }
    assert diagnostics[a.id, a.id]["backward"] == diagnostics[a.id, a.id]["long-jump"] == 0
    assert "backward" not in diagnostics[a.id, foreign.id]
    assert "long-jump" not in diagnostics[a.id, foreign.id]
    assert "backward" not in diagnostics[a.id, multi.id]
    assert "long-jump" not in diagnostics[a.id, multi.id]
    unmeasured = build_source_sequence(
        source, recall, spec.model_copy(update={"projection": SourceProjectionPolicy()})
    )
    assert all(
        "backward" not in d.features and "long-jump" not in d.features
        for d in unmeasured.diagnostics
    )
    # C (10/11) moves to its outer scene (7/11): ancestry, not coordinate order, wins.
    assert diagnostics[c.id, outer.id]["hierarchy-up"] == 1
    assert diagnostics[c.id, outer.id]["backward"] == 0


def test_reverse_cause_and_external_mass_are_independent_of_target_attractiveness():
    source, (a, b, c, _, _), _ = source_account()
    recall, units = recall_account()
    a_state, b_state, c_state = source_state(a.id), source_state(b.id), source_state(c.id)
    association, commentary = external("association"), external("commentary")
    sparse = SourceSequenceSpec(
        rows=(
            sequence_row(units[0].id, b_state, association),
            sequence_row(units[1].id, a_state, commentary),
        ),
        weights=TransitionWeights(theta={"external-in": 0.0}),
    )
    rich = SourceSequenceSpec(
        rows=(
            sequence_row(units[0].id, b_state, association),
            sequence_row(units[1].id, a_state, c_state, commentary, association),
        ),
        projection=SourceProjectionPolicy(
            semantic_links=(SemanticLink(source=b.id, target=c.id, weight=1.0),)
        ),
        weights=TransitionWeights(theta={"semantic-neighbor": 30.0, "external-in": 0.0}),
    )
    sparse_result = build_source_sequence(source, recall, sparse)
    rich_result = build_source_sequence(source, recall, rich)
    reverse = {(item.source, item.target): item.features for item in sparse_result.diagnostics}[
        b.id, a.id
    ]
    assert reverse["causal-neighbor"] == reverse["effect-to-cause"] == 1
    assert reverse["cause-to-effect"] == 0
    assert total_external_mass(sparse_result, 0, b_state) == pytest.approx(0.5)
    assert total_external_mass(rich_result, 0, b_state) == pytest.approx(0.5)


def test_primary_memberships_reject_wrong_rows_and_multidocument_evidence():
    account, (a, _, _, _, multi), (_, _, row_a, row_b, _) = source_account()
    with pytest.raises(ValueError, match="contain"):
        project_source(account, SourceProjectionPolicy(memberships={a.id: row_b.id}))
    with pytest.raises(ValueError, match="contain"):
        project_source(account, SourceProjectionPolicy(memberships={multi.id: row_a.id}))


def test_projection_refuses_empty_primary_parent_in_direct_and_forged_accounts():
    document = Document.create("parent-control", "source", "AA BB")
    scene = Unit(kind="scene", support=support(document, 0, 5))
    row = Unit(kind="row", support=support(document, 0, 2))
    # Both legal forms continue to construct hierarchy, including the root None value.
    unparented = make_account((document,), units=(row,))
    assert project_source(unparented).nodes[0].parent is None
    child = Unit(kind="row", support=row.support, parent=scene.id)
    parented = make_account((document,), units=(scene, child))
    assert (
        next(n for n in project_source(parented).nodes if n.anchor == child.id).parent == scene.id
    )

    # Existing account validation permits an empty parent; this consumer must refuse
    # it explicitly rather than indexing a nonexistent hierarchy endpoint.
    direct = Unit(kind="row", support=row.support, parent="")
    invalid = make_account((document,), units=(direct,))
    forged = unparented.model_copy(
        update={"units": (row.model_copy(update={"id": "", "parent": ""}),)}
    )
    for account in (invalid, forged):
        with pytest.raises(ValueError, match="primary parent"):
            project_source(account)


@pytest.mark.parametrize("invalid", [False, 0, "", []])
def test_projection_refuses_invalid_falsy_policies(invalid):
    account = make_account((Document.create("policy-control", "source", "AA"),))
    expected = project_source(account, SourceProjectionPolicy())
    assert project_source(account, None) == expected
    assert project_source(account, {}) == expected  # structurally valid default fields
    with pytest.raises(ValueError):
        project_source(account, invalid)


def test_extreme_external_logit_retains_tiny_source_mass_and_overflowed_scores_refuse():
    source, (a, b, _, _, _), _ = source_account()
    recall, units = recall_account()
    a_state, b_state, association, commentary = (
        source_state(a.id),
        source_state(b.id),
        external("association"),
        external("commentary"),
    )
    normal = SourceSequenceSpec(
        rows=(
            sequence_row(units[0].id, a_state, association),
            sequence_row(units[1].id, b_state, commentary),
        ),
        weights=TransitionWeights(theta={"external-in": 40.0}),
    )
    result = build_source_sequence(source, recall, normal)
    expected_source = math.exp(-40) / (1 + math.exp(-40))
    assert edge_probability(result, 0, a_state, b_state) == pytest.approx(
        expected_source, abs=0, rel=1e-12
    )
    assert edge_probability(result, 0, a_state, commentary) == pytest.approx(1 - expected_source)
    overflowing = normal.model_copy(
        update={
            "weights": TransitionWeights(
                theta={"discourse-successor": 1e308, "causal-neighbor": 1e308}
            )
        }
    )
    with pytest.raises(ValueError, match="finite numeric range"):
        build_source_sequence(source, recall, overflowing)


def test_upgraded_rich_account_projects_states_and_checked_state_relations():
    account, (a, _, _, _, _), _ = source_account()
    rich = upgrade_account(account)
    state = State(
        side="source",
        context=a.context,
        predicate="AA",
        evidence=a.evidence,
        polarity="unknown",
        modality="unknown",
    )
    relation = Relation(
        kind="references", source=a.id, target=state.id, context=a.context, evidence=a.evidence
    )
    checked = validate_rich_account(
        rich.model_copy(update={"states": (state,), "relations": (relation,)})
    )
    projection = project_source(checked)
    assert any(node.anchor == state.id and node.kind == "situation" for node in projection.nodes)
    assert any(
        edge.layer == "reference" and (edge.source, edge.target) == (a.id, state.id)
        for edge in projection.edges
    )


def test_transition_refuses_foreign_inputs_missing_external_and_forged_booleans():
    source, (a, _, _, _, _), _ = source_account(grant="allowed")
    recall, units = recall_account(grant="denied")
    anchored, outside = source_state(a.id), source_state("not-an-anchor")
    regular, foreign = (
        sequence_row(units[0].id, anchored, external("association")),
        sequence_row("not-a-recall-unit", anchored, external("association")),
    )
    destination = sequence_row(units[1].id, anchored, external("commentary"))
    spec = SourceSequenceSpec(rows=(regular, destination))
    assert build_source_sequence(source, recall, spec).request.export_permission == "denied"
    with pytest.raises(ValueError, match="outside the independent recall"):
        build_source_sequence(source, recall, SourceSequenceSpec(rows=(foreign, destination)))
    with pytest.raises(ValueError, match="anchor or kind"):
        build_source_sequence(
            source,
            recall,
            SourceSequenceSpec(
                rows=(sequence_row(units[0].id, outside, external("association")), destination)
            ),
        )
    with pytest.raises(ValueError, match="explicit external"):
        build_source_sequence(
            source, recall, SourceSequenceSpec(rows=(regular, sequence_row(units[1].id, anchored)))
        )
    # Frozen models can be forged with model_copy; consumption must still reject
    # strict Boolean input.
    with pytest.raises(ValueError):
        build_source_sequence(source, recall, spec.model_copy(update={"temperature": True}))


def test_source_transition_cli_matches_api_schema_and_checks_grants_before_spec_read(
    tmp_path, capsys
):
    source, (a, _, _, _, _), _ = source_account()
    recall, units = recall_account()
    spec = SourceSequenceSpec(
        rows=(
            sequence_row(units[0].id, source_state(a.id), external("association")),
            sequence_row(units[1].id, external("unranked")),
        )
    )
    source_path, recall_path, spec_path = (
        tmp_path / name for name in ("source.json", "recall.json", "spec.json")
    )
    result_path, request_path = tmp_path / "result.json", tmp_path / "request.json"
    source_path.write_text(source.model_dump_json())
    recall_path.write_text(recall.model_dump_json())
    spec_path.write_text(spec.model_dump_json())
    expected = build_source_sequence(source, recall, spec)
    assert (
        main(
            [
                "build-source-sequence",
                str(source_path),
                str(recall_path),
                str(spec_path),
                "--output",
                str(result_path),
            ]
        )
        == 0
    )
    assert SourceSequenceResult.model_validate_json(result_path.read_bytes()) == expected
    assert (
        main(
            [
                "build-source-sequence",
                str(source_path),
                str(recall_path),
                str(spec_path),
                "--request-only",
                "--output",
                str(request_path),
            ]
        )
        == 0
    )
    assert json.loads(request_path.read_text()) == expected.request.model_dump(mode="json")
    assert (
        main(
            [
                "verify-source-sequence",
                str(source_path),
                str(recall_path),
                str(spec_path),
                str(result_path),
            ]
        )
        == 0
    )
    assert json.loads(capsys.readouterr().out)["valid"] is True

    root = Path(__file__).resolve().parents[1]
    assert json.loads((root / "schemas/source-transitions.json").read_text()) == {
        "policy": SourceProjectionPolicy.model_json_schema(),
        "projection": type(expected.projection).model_json_schema(),
        "spec": SourceSequenceSpec.model_json_schema(),
        "result": SourceSequenceResult.model_json_schema(),
    }

    denied_recall = recall.model_copy(update={"export_permission": "denied"})
    recall_path.write_text(denied_recall.model_dump_json())
    missing_spec = tmp_path / "must-not-read.json"
    assert (
        main(["build-source-sequence", str(source_path), str(recall_path), str(missing_spec)]) == 2
    )
    assert "denies recall" in capsys.readouterr().err
