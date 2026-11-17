"""Discriminating structural controls and the already admitted extraction example."""

import json

import pytest

from storymodel.alignment import AlignmentPolicy, PredicateAliases, align_account, verify_alignment
from storymodel.analysis import project
from storymodel.extraction import resolve_extraction
from storymodel.extraction_demo import example_proposal, example_request
from storymodel.model import (
    Account,
    Binding,
    Context,
    Detail,
    Document,
    Entity,
    Event,
    Hypothesis,
    Inventory,
    Participation,
    Recipe,
    Reference,
    Span,
    Support,
    Unit,
    validate_account,
)


def control(source_specs, recall_specs):
    """Predicate/participant labels are nonnarrative control tokens, not linguistic gold."""
    docs, units, entities, events, details, inventories = [], [], [], [], [], []
    roots = {side: Context(side=side, kind="narrated") for side in ("source", "recall")}
    for side, specs in (("source", source_specs), ("recall", recall_specs)):
        if not specs:
            continue
        doc = Document.create(side, side, "\n".join(["AA BB CC"] * len(specs)))
        docs.append(doc)
        for i, (predicate, label, role) in enumerate(specs):
            support = Support(document=doc.id, spans=(Span(start=i * 9, end=i * 9 + 8),))
            unit = Unit(kind="row" if side == "source" else "recall", support=support)
            units.append(unit)
            participants = ()
            if label is not None:
                entity = Entity(source_document=doc.id, label=label, evidence=(support,))
                entities.append(entity)
                participants = (Participation(entity=entity.id, role=role, evidence=(support,)),)
            event = Event(
                side=side,
                context=roots[side].id,
                predicate=predicate,
                participants=participants,
                evidence=(support,),
            )
            events.append(event)
            if side == "recall":
                details.append(
                    Detail(
                        unit=unit.id,
                        kind="event",
                        content="AA",
                        support=support,
                        address="unresolved",
                        event=event.id,
                    )
                )
                inventories.append(Inventory(unit=unit.id, status="completed"))
    return Account(
        schema_version="storymodel.account/v2",
        documents=tuple(docs),
        units=tuple(units),
        contexts=tuple(roots.values()),
        entities=tuple(entities),
        events=tuple(events),
        details=tuple(details),
        inventories=tuple(inventories),
        recipe=Recipe(name="structural-control", input_sha256=tuple(d.sha256 for d in docs)),
        export_permission="allowed",
    )


def test_connected_extraction_alignment_preserves_fidelity_and_missing_source_event():
    original = resolve_extraction(example_request(), example_proposal()).account
    policy = AlignmentPolicy(
        aliases=(PredicateAliases(terms=("left", "placing"), basis="synthetic diagnostic"),)
    )
    baseline = align_account(original)
    assert all(d.reference.outcome == "unresolved" for d in baseline.details)
    result = align_account(original, policy)
    by_detail = {r.detail: r for r in result.details}
    assert [by_detail[d.id].reference.outcome for d in original.details] == [
        "resolved",
        "resolved",
        "unresolved",
    ]
    resolved = next(g for g in result.events if g.candidates)
    assert resolved.candidates[0].predicate_match == "declared-alias"
    assert resolved.candidates[0].role_matches[0].label == "courier"
    assert result.account.details == original.details
    assert result.account.events == original.events and result.account.entities == original.entities
    assert result.account.inventories == original.inventories
    assert all(not b.assessments for b in result.account.bindings)
    assert {r.coverage for r in project(result.account).source} == {"not-assessable"}
    assert project(result.account).counts == project(original).counts
    assert verify_alignment(original, result.model_dump_json()) == result


def test_same_actor_different_actions_need_predicate_support():
    account = control([("P", "X", "agent"), ("Q", "X", "agent")], [("Q", "X", "agent")])
    result = align_account(account)
    assert len(result.events[0].candidates) == 2
    assert sum(c.eligible for c in result.events[0].candidates) == 1
    chosen = next(c for c in result.events[0].candidates if c.eligible)
    assert chosen.source_predicate == "Q"
    assert result.details[0].reference.hypotheses[0].targets == chosen.source_rows
    assert result.details[0].reference.outcome == "resolved"


def test_reordered_recall_and_revisits_do_not_force_monotone_or_one_to_one_alignment():
    account = control(
        [("P", "X", "agent"), ("Q", "X", "agent")],
        [("Q", "X", "agent"), ("P", "X", "agent"), ("Q", "X", "agent")],
    )
    result = align_account(account)
    bindings = {b.detail: b.reference for b in result.account.bindings}
    targets = [bindings[d.id].hypotheses[0].targets for d in account.details]
    assert targets[0] == targets[2] != targets[1]
    source_rows = [u for u in account.units if u.kind == "row"]
    assert targets == [(source_rows[1].id,), (source_rows[0].id,), (source_rows[1].id,)]
    shuffled = account.model_copy(
        update={"events": tuple(reversed(account.events)), "units": tuple(reversed(account.units))}
    )
    assert align_account(shuffled).details == result.details


def test_competing_occurrences_remain_alternatives_not_tie_broken_by_order():
    account = control([("P", "X", "agent"), ("P", "X", "agent")], [("P", "X", "agent")])
    reference = align_account(account).details[0].reference
    assert reference.outcome == "alternative" and len(reference.hypotheses) == 2
    assert {h.targets for h in reference.hypotheses} == {
        (u.id,) for u in account.units if u.kind == "row"
    }


@pytest.mark.parametrize(
    "recall",
    [("P", None, "agent"), ("P", "Y", "agent"), ("P", "X", "patient"), ("Q", "X", "agent")],
)
def test_weak_or_conflicting_channels_do_not_become_resolved_matches(recall):
    result = align_account(control([("P", "X", "agent")], [recall]))
    assert result.details[0].reference.outcome == "unresolved"
    assert not any(c.eligible for c in result.events[0].candidates)


def test_no_match_and_no_source_are_unresolved_not_external():
    for source in ([], [("P", "X", "agent")]):
        result = align_account(control(source, [("Q", "Z", "agent")]))
        ref = result.details[0].reference
        assert ref.status == "attempted" and ref.outcome == "unresolved" and not ref.hypotheses
        assert result.events[0].retrieved_count_lower_bound == 0


def test_explicit_external_and_existing_fidelity_are_preserved_not_overwritten():
    account = control([("P", "X", "agent")], [("P", "X", "agent")])
    binding = Binding(
        detail=account.details[0].id,
        reference=Reference(
            status="attempted",
            outcome="resolved",
            hypotheses=(Hypothesis(kind="external", external="personal"),),
        ),
    )
    account = account.model_copy(update={"bindings": (binding,)})
    result = align_account(account)
    assert result.account.bindings == (binding,) and result.details[0].action == "preserved"
    assert result.details[0].reference.hypotheses[0].external == "personal"
    assert not result.events
    from storymodel.demo import example_account

    previous = example_account()
    assert align_account(previous).account.bindings == previous.bindings


@pytest.mark.parametrize("both", [False, True])
def test_attributed_contexts_require_correspondence_not_just_equal_kind(both):
    account = control([("P", "X", "agent")], [("P", "X", "agent")])
    contexts, events = list(account.contexts), []
    for event in account.events:
        if event.side == "recall" or both:
            context = Context(
                side=event.side, kind="believed", parent=event.context, evidence=event.evidence
            )
            contexts.append(context)
            events.append(
                Event(
                    side=event.side,
                    context=context.id,
                    predicate=event.predicate,
                    participants=event.participants,
                    evidence=event.evidence,
                )
            )
        else:
            events.append(event)
    details = tuple(d.model_copy(update={"event": events[-1].id}) for d in account.details)
    changed = account.model_copy(
        update={"contexts": tuple(contexts), "events": tuple(events), "details": details}
    )
    result = align_account(changed)
    assert result.details[0].reference.outcome == "unresolved"
    assert result.events[0].candidates[0].context == (
        "attributed-unverified" if both else "kind-mismatch"
    )


def test_overflow_is_search_limited_even_if_kept_candidates_look_unique():
    account = control([("P", "X", "agent"), ("Q", "X", "agent")], [("P", "X", "agent")])
    result = align_account(account, AlignmentPolicy(max_candidates=1))
    assert len(result.events[0].candidates) == 1 and result.events[0].truncated
    assert result.events[0].retrieved_count_lower_bound == 2
    assert result.details[0].reference.status == "search-limited"
    assert result.details[0].reference.outcome is None


def test_sparse_retrieval_does_not_materialize_all_source_recall_pairs():
    account = control(
        [(f"P{i}", f"X{i}", "agent") for i in range(100)],
        [(f"P{i}", f"X{i}", "agent") for i in (1, 40, 99)],
    )
    result = align_account(account)
    assert len(result.source_event_universe) == 100
    assert sum(g.retrieved_count_lower_bound for g in result.events) == 3
    assert sum(len(g.candidates) for g in result.events) == 3


def test_unsupported_row_localization_and_same_row_event_ambiguity_do_not_collapse():
    account = control([("P", "X", "agent"), ("P", "X", "agent")], [("P", "X", "agent")])
    source_doc = next(d for d in account.documents if d.side == "source")
    full = Support(document=source_doc.id, spans=(Span(start=0, end=len(source_doc.text)),))
    # Two source events share one containing row; row hypotheses cannot express that ambiguity.
    row = Unit(kind="row", support=full)
    changed = account.model_copy(
        update={"units": (row,) + tuple(u for u in account.units if u.kind == "recall")}
    )
    result = align_account(changed)
    assert result.details[0].reference.outcome == "unresolved"
    assert "share a row" in result.details[0].reference.reason
    # No row at all is not silently treated as a broad or joint reference.
    no_rows = account.model_copy(
        update={"units": tuple(u for u in account.units if u.kind == "recall")}
    )
    assert align_account(no_rows).details[0].reference.outcome == "unresolved"


def test_aliases_are_explicit_bound_and_not_implicit_stemming_or_negation_removal():
    account = control([("P", "X", "agent")], [("Q", "X", "agent")])
    plain = align_account(account)
    policy = AlignmentPolicy(aliases=(PredicateAliases(terms=("P", "Q"), basis="test control"),))
    changed = align_account(account, policy)
    assert plain.details[0].reference.outcome == "unresolved"
    assert changed.details[0].reference.outcome == "resolved"
    assert changed.account.recipe != plain.account.recipe
    assert (
        align_account(control([("P", "X", "agent")], [("not P", "X", "agent")]))
        .details[0]
        .reference.outcome
        == "unresolved"
    )
    with pytest.raises(ValueError, match="overlapping"):
        AlignmentPolicy(aliases=(PredicateAliases(terms=("P", "p"), basis="invalid"),))


def test_forged_receipts_and_copied_inputs_are_revalidated():
    account = control([("P", "X", "agent")], [("P", "X", "agent")])
    result = align_account(account)
    altered = result.model_dump(mode="json")
    altered["events"][0]["candidates"][0]["eligible"] = False
    with pytest.raises(ValueError, match="recomputation"):
        verify_alignment(account, json.dumps(altered))
    # Wrong Python types refuse before a serializer can coerce or warn about them.
    with pytest.raises(ValueError, match="valid integer"):
        align_account(
            account, AlignmentPolicy().model_copy(update={"max_candidates": float("nan")})
        )
    forged = account.model_copy(update={"events": account.events[:-1]})
    with pytest.raises(ValueError, match="detail event"):
        align_account(forged)
    assert validate_account(result.account) == result.account
