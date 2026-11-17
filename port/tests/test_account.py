"""Behavioral witnesses using the admitted worked example and nonnarrative control tokens."""

import json

import pytest
from hypothesis import given
from hypothesis import strategies as st
from pydantic import ValidationError

from storymodel.accounts import make_account
from storymodel.analysis import project
from storymodel.demo import example_account
from storymodel.model import (
    Account,
    Assessment,
    Binding,
    Context,
    Document,
    Event,
    Hypothesis,
    Inventory,
    Reference,
    Relation,
    Span,
    Support,
    Unit,
    account_digest,
    contained,
    validate_account,
)
from storymodel.rich_account import upgrade_account, validate_supported_account


def revise_binding(account, index=0, *, reference=None, grade="supported", status="completed"):
    old = account.bindings[index]
    reference = reference or old.reference
    assessments = tuple(
        Assessment(
            hypothesis=h.id,
            status=status,
            grade=grade if status == "completed" else None,
            reason=None if status == "completed" else "control",
            evidence=old.assessments[0].evidence,
        )
        for h in reference.hypotheses
    )
    binding = Binding(detail=old.detail, reference=reference, assessments=assessments)
    return account.model_copy(
        update={"bindings": account.bindings[:index] + (binding,) + account.bindings[index + 1 :]}
    )


def test_two_policies_reuse_same_account_and_match_independent_worked_example():
    account = example_account()
    full = project(account)
    events = project(account, "target-event-only/v1")
    assert full.counts.model_dump() == dict(
        internal=3, external=0, unresolved=0, excluded=0, denominator=3, internal_fraction=1.0
    )
    assert events.counts.model_dump() == dict(
        internal=2, external=0, unresolved=0, excluded=1, denominator=2, internal_fraction=1.0
    )
    assert full.account_sha256 == events.account_sha256 == account_digest(account)
    assert [row.coverage for row in full.source] == ["present", "present"]
    assert len(full.recall) == len(full.correspondence) == 3


@pytest.mark.parametrize("grade", ["contradicted", "invented", "unverifiable", "misread"])
def test_fidelity_never_changes_address_classification(grade):
    account = example_account()
    changed = revise_binding(account, grade=grade)
    assert project(changed).counts == project(account).counts
    assert [d.id for d in changed.details] == [d.id for d in account.details]


@pytest.mark.parametrize("kind", ["single", "broad"])
def test_scene_reference_is_not_definite_row_coverage(kind):
    account = example_account()
    scene = next(u for u in account.units if u.kind == "scene")
    reference = Reference(
        status="attempted",
        outcome="resolved",
        hypotheses=(Hypothesis(kind=kind, targets=(scene.id,)),),
    )
    # Remove other detail bindings explicitly: they become not-assessed, not omitted.
    changed = revise_binding(account, reference=reference)
    changed = changed.model_copy(
        update={"details": changed.details[:1], "bindings": changed.bindings[:1]}
    )
    assert {row.coverage for row in project(changed).source} == {"uncertain"}


def test_joint_credits_rows_but_alternative_does_not():
    account = example_account()
    rows = tuple(u for u in account.units if u.kind == "row")
    evidence = tuple(u.support for u in rows)
    for outcome, hypotheses, expected in (
        ("resolved", (Hypothesis(kind="joint", targets=tuple(u.id for u in rows)),), "present"),
        (
            "alternative",
            tuple(Hypothesis(kind="single", targets=(u.id,)) for u in rows),
            "uncertain",
        ),
    ):
        binding = Binding(
            detail=account.details[0].id,
            reference=Reference(status="attempted", outcome=outcome, hypotheses=hypotheses),
            assessments=tuple(
                Assessment(
                    hypothesis=h.id,
                    status="completed",
                    grade="supported",
                    evidence=evidence if h.kind == "joint" else (rows[i].support,),
                )
                for i, h in enumerate(hypotheses)
            ),
        )
        changed = account.model_copy(
            update={"details": account.details[:1], "bindings": (binding,)}
        )
        assert {r.coverage for r in project(changed).source} == {expected}


def test_supported_evidence_wins_over_an_unavailable_other_binding():
    account = example_account()
    changed = revise_binding(account, status="pending")
    assert {r.coverage for r in project(changed).source} == {"present"}


def test_unlocalized_content_is_visible_and_does_not_assert_absence():
    account = example_account()
    reference = Reference(status="attempted", outcome="unresolved", reason="no supported target")
    changed = revise_binding(account, reference=reference)
    changed = changed.model_copy(
        update={"details": changed.details[:1], "bindings": changed.bindings[:1]}
    )
    result = project(changed)
    assert result.recall[0].reference_outcome == "unresolved"
    assert {r.coverage for r in result.source} == {"not-assessable"}
    assert result.counts.internal == 1


@pytest.mark.parametrize("status", ["not-attempted", "failed", "search-limited"])
def test_processing_failure_cannot_become_a_reference_outcome(status):
    with pytest.raises(ValidationError, match="needs only a reason"):
        Reference(status=status, outcome="unresolved", reason="control")


def test_empty_counts_have_no_plausible_fraction():
    account = example_account().model_copy(update={"details": (), "bindings": ()})
    result = project(account)
    assert result.counts.denominator == 0 and result.counts.internal_fraction is None


def test_missing_inventory_never_asserts_complete_absence():
    account = example_account().model_copy(
        update={"details": (), "bindings": (), "inventories": ()}
    )
    result = project(account)
    assert len(result.inventories) == 1
    assert result.inventories[0].status == "not-attempted"
    assert result.inventories[0].detail_count == 0
    assert {r.coverage for r in result.source} == {"not-assessable"}
    complete = example_account().model_copy(update={"details": (), "bindings": ()})
    assert {r.coverage for r in project(complete).source} == {"absent"}


def test_partial_inventory_remains_visible_with_proven_positive_coverage():
    account = example_account()
    unit = account.details[0].unit
    partial = account.model_copy(
        update={
            "inventories": (
                Inventory(unit=unit, status="search-limited", reason="bounded extraction"),
            )
        }
    )
    result = project(partial)
    assert result.inventories[0].status == "search-limited"
    assert {r.coverage for r in result.source} == {"present"}
    with pytest.raises(ValidationError, match="foreign or duplicate recall inventory"):
        project(
            account.model_copy(
                update={"inventories": (Inventory(unit="missing", status="completed"),)}
            )
        )


@pytest.mark.parametrize("partial", [False, True])
def test_missing_or_partial_recall_segmentation_cannot_assert_absence(partial):
    account = example_account()
    recall = next(d for d in account.documents if d.side == "recall")
    source_units = tuple(u for u in account.units if u.kind != "recall")
    first_character = Unit(
        kind="recall", support=Support(document=recall.id, spans=(Span(start=0, end=1),))
    )
    changed = account.model_copy(
        update={
            "units": source_units + ((first_character,) if partial else ()),
            "details": (),
            "bindings": (),
            "inventories": (Inventory(unit=first_character.id, status="completed"),)
            if partial
            else (),
        }
    )
    result = project(changed)
    assert {r.coverage for r in result.source} == {"not-assessable"}


def test_source_rows_cannot_parent_other_rows():
    account = example_account()
    parent = next(u for u in account.units if u.kind == "row")
    nested = Unit(
        kind="row",
        parent=parent.id,
        support=Support(document=parent.support.document, spans=(Span(start=0, end=1),)),
    )
    with pytest.raises(ValidationError, match="scene containers"):
        project(account.model_copy(update={"units": account.units + (nested,)}))


def test_content_identity_and_canonical_roundtrip_are_deterministic():
    account = example_account()
    assert validate_account(account.model_dump_json()) == account
    assert account_digest(account) == account_digest(example_account())
    doc = json.loads(account.model_dump_json())
    doc["documents"][0]["id"] = "forged"
    with pytest.raises(ValidationError, match="identity differs"):
        validate_account(json.dumps(doc))


def test_copy_bypass_is_revalidated_at_consumption():
    account = example_account()
    detail = account.details[0]
    forged = detail.model_copy(
        update={
            "support": Support(document=detail.support.document, spans=(Span(start=0, end=10000),)),
            "id": "",
        }
    )
    with pytest.raises(ValueError, match="span exceeds"):
        project(account.model_copy(update={"details": (forged,), "bindings": ()}))


def test_exact_union_containment_does_not_fill_gaps():
    support = Support(document="control", spans=(Span(start=0, end=2), Span(start=4, end=8)))
    assert contained(Support(document="control", spans=(Span(start=4, end=7),)), support)
    assert not contained(Support(document="control", spans=(Span(start=1, end=5),)), support)
    assert not contained(Support(document="foreign", spans=(Span(start=4, end=7),)), support)


def test_non_bmp_support_uses_utf16_and_refuses_surrogate_split():
    assert Span(start=1, end=3).slice("A😀B") == "😀"
    with pytest.raises(ValueError, match="surrogate"):
        Span(start=1, end=2).slice("A😀B")
    with pytest.raises(ValueError, match="exceeds"):
        Span(start=3, end=5).slice("A😀B")


@given(st.text(alphabet="abc😀é", min_size=1, max_size=30))
def test_full_utf16_span_recovers_exact_text(text):
    assert Span(start=0, end=len(text.encode("utf-16-le")) // 2).slice(text) == text


def test_repeated_content_at_distinct_positions_has_distinct_occurrence_ids():
    document = Document.create("control", "recall", "AB AB")
    first = Unit(
        kind="recall", support=Support(document=document.id, spans=(Span(start=0, end=2),))
    )
    second = Unit(
        kind="recall", support=Support(document=document.id, spans=(Span(start=3, end=5),))
    )
    assert first.id != second.id


def test_recall_unit_accounting_does_not_deduplicate_repeated_text():
    document = Document.create("session-A", "recall", "AB")
    other = Document.create("session-B", "recall", "AB")
    assert document.sha256 == other.sha256
    assert document.id != other.id


def test_attributed_event_stays_in_its_context():
    account = example_account()
    original = account.events[0]
    reported = Context(
        side="source",
        kind="reported",
        parent=original.context,
        evidence=original.evidence,
    )
    event = Event(
        side="source", context=reported.id, predicate="control", evidence=original.evidence
    )
    changed = account.model_copy(
        update={
            "contexts": account.contexts + (reported,),
            "events": account.events + (event,),
        }
    )
    checked = validate_account(changed)
    assert checked.events[-1].context == reported.id
    assert checked.events[-1].context != original.context
    crosses = Relation(
        kind="causes",
        source=original.id,
        target=event.id,
        context=original.context,
        evidence=original.evidence,
    )
    with pytest.raises(ValueError, match="crosses attributed"):
        validate_account(changed.model_copy(update={"relations": (crosses,)}))


@pytest.mark.parametrize("kind", ["part_of", "before", "causes"])
def test_evidenced_relations_have_real_endpoints_and_refuse_cycles(kind):
    account = example_account()
    original = account.events[0]
    other = Event(
        side="source", context=original.context, predicate="control", evidence=original.evidence
    )
    relation = Relation(
        kind=kind,
        source=original.id,
        target=other.id,
        context=original.context,
        evidence=original.evidence,
    )
    changed = account.model_copy(
        update={"events": account.events + (other,), "relations": (relation,)}
    )
    assert validate_account(changed).relations == (relation,)
    reverse = Relation(
        kind=kind,
        source=other.id,
        target=original.id,
        context=original.context,
        evidence=original.evidence,
    )
    with pytest.raises(ValueError, match="cyclic"):
        validate_account(changed.model_copy(update={"relations": (relation, reverse)}))
    foreign = Relation(
        kind=kind,
        source=original.id,
        target="foreign",
        context=original.context,
        evidence=original.evidence,
    )
    with pytest.raises(ValueError, match="existing event endpoints"):
        validate_account(account.model_copy(update={"relations": (foreign,)}))


def test_source_entity_and_role_support_survive_roundtrip():
    account = validate_account(example_account())
    assert account.events[0].participants[0].entity == account.entities[0].id
    assert account.events[0].participants[0].role == "agent"
    assert account.events[1].participants[0].entity == account.entities[0].id


def test_fidelity_cannot_cite_unrelated_source_rows():
    account = example_account()
    binding = account.bindings[0]
    foreign = account.bindings[2].assessments[0].evidence
    assessment = binding.assessments[0].model_copy(update={"evidence": foreign})
    altered = binding.model_copy(update={"assessments": (assessment,)})
    with pytest.raises(ValueError, match="outside its source hypothesis"):
        project(account.model_copy(update={"bindings": (altered,) + account.bindings[1:]}))


def test_unknown_fields_and_foreign_policy_refuse():
    doc = example_account().model_dump(mode="json")
    doc["confidence"] = 0.5
    with pytest.raises(ValidationError, match="Extra inputs"):
        Account.model_validate_json(json.dumps(doc))
    with pytest.raises(ValueError, match="unknown analysis policy"):
        project(example_account(), "unknown")


def test_duplicate_and_foreign_eligible_rows_refuse():
    account = example_account()
    row = next(u.id for u in account.units if u.kind == "row")
    with pytest.raises(ValueError, match="eligible universe"):
        project(account, eligible_rows=(row, row))
    with pytest.raises(ValueError, match="eligible universe"):
        project(account, eligible_rows=("foreign",))


def test_forged_document_hash_refuses_even_with_recomputed_identity():
    document = example_account().documents[0].model_dump(mode="json")
    document.update(sha256="0" * 64, id="")
    with pytest.raises(ValidationError, match="checksum differs"):
        Document.model_validate_json(json.dumps(document))


@pytest.mark.parametrize("version", [1, 2, 3, 4])
@pytest.mark.parametrize("side", ["source", "recall"])
def test_parent_absence_and_existing_parent_survive_all_wire_versions(version, side):
    account, child = parent_account(version, side)
    assert validate_supported_account(account.model_dump_json()) == account
    assert child.parent is not None
    unparented = Unit(kind=child.kind, support=child.support)
    root_account = type(account)(**{**account.model_dump(), "units": (unparented,)})
    assert validate_supported_account(root_account).units[0].parent is None


def parent_account(version, side):
    documents = tuple(Document.create(s, s, "AA BB") for s in ("source", "recall"))
    document = next(d for d in documents if d.side == side)
    parent = Unit(
        kind="scene" if side == "source" else "recall",
        support=Support(document=document.id, spans=(Span(start=0, end=5),)),
    )
    child = Unit(
        kind="row" if side == "source" else "recall",
        parent=parent.id,
        support=Support(document=document.id, spans=(Span(start=0, end=2),)),
    )
    account = make_account(documents, units=(parent, child))
    if version == 4:
        return upgrade_account(account), child
    return Account(
        **{**account.model_dump(), "schema_version": f"storymodel.account/v{version}"}
    ), child


@pytest.mark.parametrize("version", [1, 2, 3, 4])
@pytest.mark.parametrize("side", ["source", "recall"])
@pytest.mark.parametrize("boundary", ["construction", "copy", "json"])
def test_empty_parent_is_not_absence_at_account_admission(version, side, boundary):
    account, child = parent_account(version, side)
    invalid = Unit(kind=child.kind, support=child.support, parent="")
    with pytest.raises(ValueError, match="unit parent must contain"):
        if boundary == "construction":
            type(account)(**{**account.model_dump(), "units": (invalid,)})
        elif boundary == "copy":
            validate_supported_account(account.model_copy(update={"units": (invalid,)}))
        else:
            value = account.model_dump(mode="json")
            value["units"] = [invalid.model_dump(mode="json")]
            validate_supported_account(json.dumps(value))
