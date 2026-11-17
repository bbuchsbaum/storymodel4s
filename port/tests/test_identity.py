"""Conservative, document-local identity sidecar checks on control-token accounts."""

from __future__ import annotations

from hashlib import sha256
from typing import Literal

import pytest

from storymodel.accounts import pair_accounts
from storymodel.identity import identity_role_table, resolve_text_identity, verify_identity
from storymodel.model import (
    Account,
    Context,
    Document,
    Entity,
    Event,
    Participation,
    Recipe,
    Span,
    Support,
    digest,
)
from storymodel.serialization import canonical


def fixture(
    documents: tuple[tuple[str, Literal["source", "recall"], str], ...],
    mentions: tuple[tuple[str, str, tuple[tuple[int, int], ...]], ...],
    *,
    held: tuple[str, ...] = (),
) -> Account:
    docs = {key: Document.create(key, side, text) for key, side, text in documents}
    lengths = {key: len(document.text.encode("utf-16-le")) // 2 for key, document in docs.items()}
    roots = {side: Context(side=side, kind="narrated") for _, side, _ in documents}
    held_contexts = {
        key: Context(
            side=docs[key].side,
            kind="reported",
            parent=roots[docs[key].side].id,
            evidence=(Support(document=docs[key].id, spans=(Span(start=0, end=lengths[key]),)),),
        )
        for key in held
    }
    entities: list[Entity] = []
    events: list[Event] = []
    for key, label, spans in mentions:
        document = docs[key]
        support = Support(document=document.id, spans=tuple(Span(start=a, end=b) for a, b in spans))
        entity = Entity(source_document=document.id, label=label, evidence=(support,))
        entities.append(entity)
        full = Support(document=document.id, spans=(Span(start=0, end=lengths[key]),))
        events.append(
            Event(
                side=document.side,
                context=(
                    held_contexts[key].id if key in held_contexts else roots[document.side].id
                ),
                predicate=f"event-{len(events)}",
                evidence=(full,),
                participants=(Participation(entity=entity.id, role="agent", evidence=(support,)),),
            )
        )
    return Account(
        schema_version="storymodel.account/v3",
        documents=tuple(docs.values()),
        units=(),
        contexts=(*roots.values(), *held_contexts.values()),
        entities=tuple(entities),
        events=tuple(events),
        recipe=Recipe(
            name="identity-control/v1", input_sha256=tuple(d.sha256 for d in docs.values())
        ),
    )


def annotation(entity: Entity, document: Document, span: tuple[int, int], **values):
    start, end = span
    result = {
        "mention": entity.id,
        "status": "provided",
        "form": "name",
        "quote": {
            "span": {"start": start, "end": end},
            "text": Span(start=start, end=end).slice(document.text),
        },
    }
    result.update(values)
    return result


def raw(value: Account, mentions, *, origin="control") -> str:
    return canonical(
        {
            "schema_version": "storymodel.identity-annotations/v1",
            "account_sha256": digest(value.model_dump(mode="json")),
            "origin": origin,
            "mentions": mentions,
        }
    )


def outcomes(result):
    return {item.mention: item for item in result.mentions}


def test_exact_name_type_groups_and_missing_type_singletons_never_cross_documents():
    value = fixture(
        (
            ("source", "source", "AA AA"),
            ("recall-one", "recall", "AA"),
            ("recall-two", "recall", "AA"),
        ),
        (
            ("source", "AA", ((0, 2),)),
            ("source", "AA", ((3, 5),)),
            ("recall-one", "AA", ((0, 2),)),
            ("recall-two", "AA", ((0, 2),)),
        ),
    )
    source, recall_one, recall_two = value.documents
    first, second, third, fourth = value.entities
    result = resolve_text_identity(
        value,
        raw(
            value,
            (
                annotation(first, source, (0, 2), entity_type={"kind": "person"}),
                annotation(second, source, (3, 5), entity_type={"kind": "person"}),
                annotation(third, recall_one, (0, 2)),
                annotation(fourth, recall_two, (0, 2)),
            ),
        ),
    )
    decided = outcomes(result)
    assert decided[first.id].outcome == decided[second.id].outcome == "introducing"
    assert decided[first.id].referent == decided[second.id].referent
    assert decided[third.id].referent != decided[fourth.id].referent
    assert all(item.outcome != "open" or item.referent is None for item in result.mentions)


def test_unique_third_person_is_preceding_and_missing_or_ambiguous_inputs_stay_open():
    unique = fixture(
        (("source", "source", "AA he"),), (("source", "AA", ((0, 2),)), ("source", "he", ((3, 5),)))
    )
    document = unique.documents[0]
    antecedent, pronoun = unique.entities
    base = annotation(antecedent, document, (0, 2), entity_type={"kind": "person"})
    third = annotation(pronoun, document, (3, 5), form="pronominal", person="third")
    resolved = resolve_text_identity(unique, raw(unique, (base, third)))
    assert outcomes(resolved)[pronoun.id].outcome == "resolved"
    assert outcomes(resolved)[pronoun.id].referent == outcomes(resolved)[antecedent.id].referent

    no_future = resolve_text_identity(unique, raw(unique, (third,)))
    assert outcomes(no_future)[pronoun.id].outcome == "open"
    assert outcomes(no_future)[pronoun.id].reason == "incomplete-antecedent-input"

    no_antecedent = fixture((("source", "source", "he"),), (("source", "he", ((0, 2),)),))
    lone_pronoun = no_antecedent.entities[0]
    no_prior = resolve_text_identity(
        no_antecedent,
        raw(
            no_antecedent,
            (
                annotation(
                    lone_pronoun,
                    no_antecedent.documents[0],
                    (0, 2),
                    form="pronominal",
                    person="third",
                ),
            ),
        ),
    )
    assert outcomes(no_prior)[lone_pronoun.id].reason == "no-antecedent"

    malformed = annotation(antecedent, document, (0, 2), entity_type={"kind": "person"})
    malformed["quote"]["text"] = "bad"
    blocked = resolve_text_identity(unique, raw(unique, (malformed, third)))
    assert outcomes(blocked)[antecedent.id].reason == "invalid-annotation"
    assert outcomes(blocked)[pronoun.id].reason == "incomplete-antecedent-input"

    other = annotation(antecedent, document, (0, 2), form="other", reason="unrecognized")
    blocked_other = resolve_text_identity(unique, raw(unique, (other, third)))
    assert outcomes(blocked_other)[antecedent.id].reason == "unrecognized-form"
    assert outcomes(blocked_other)[pronoun.id].reason == "incomplete-antecedent-input"

    several = fixture(
        (("source", "source", "AA BB he"),),
        (("source", "AA", ((0, 2),)), ("source", "BB", ((3, 5),)), ("source", "he", ((6, 8),))),
    )
    document = several.documents[0]
    first, second, pronoun = several.entities
    result = resolve_text_identity(
        several,
        raw(
            several,
            (
                annotation(first, document, (0, 2), entity_type={"kind": "person"}),
                annotation(second, document, (3, 5), entity_type={"kind": "person"}),
                annotation(pronoun, document, (6, 8), form="pronominal", person="third"),
            ),
        ),
    )
    assert outcomes(result)[pronoun.id].outcome == "open"
    assert outcomes(result)[pronoun.id].reason == "several-antecedents"


def test_future_introduction_never_resolves_an_earlier_pronoun():
    value = fixture(
        (("source", "source", "he AA"),),
        (("source", "he", ((0, 2),)), ("source", "AA", ((3, 5),))),
    )
    document = value.documents[0]
    pronoun, later = value.entities
    result = resolve_text_identity(
        value,
        raw(
            value,
            (
                annotation(pronoun, document, (0, 2), form="pronominal", person="third"),
                annotation(later, document, (3, 5), entity_type={"kind": "person"}),
            ),
        ),
    )
    decision = outcomes(result)[pronoun.id]
    assert decision.outcome == "open" and decision.referent is None
    assert decision.reason == "no-antecedent"


def test_missing_earlier_form_cannot_manufacture_a_unique_antecedent():
    value = fixture(
        (("source", "source", "AA BB he"),),
        (
            ("source", "AA", ((0, 2),)),
            ("source", "BB", ((3, 5),)),
            ("source", "he", ((6, 8),)),
        ),
    )
    document = value.documents[0]
    first, second, pronoun = value.entities
    known_first = annotation(first, document, (0, 2), entity_type={"kind": "person"})
    pronoun_form = annotation(pronoun, document, (6, 8), form="pronominal", person="third")
    missing = resolve_text_identity(value, raw(value, (known_first, pronoun_form)))
    open_pronoun = outcomes(missing)[pronoun.id]
    assert open_pronoun.reason == "incomplete-antecedent-input"
    assert open_pronoun.referent is None and len(open_pronoun.candidates) == 1

    complete = resolve_text_identity(
        value,
        raw(
            value,
            (
                known_first,
                annotation(second, document, (3, 5), entity_type={"kind": "person"}),
                pronoun_form,
            ),
        ),
    )
    assert outcomes(complete)[pronoun.id].reason == "several-antecedents"


def test_pronominals_and_label_type_groups_remain_conservative():
    pronouns = fixture(
        (("source", "source", "he he"),),
        (("source", "he", ((0, 2),)), ("source", "he", ((3, 5),))),
    )
    document = pronouns.documents[0]
    first, second = pronouns.entities
    result = resolve_text_identity(
        pronouns,
        raw(
            pronouns,
            (
                annotation(first, document, (0, 2), form="pronominal", person="third"),
                annotation(second, document, (3, 5), form="pronominal", person="third"),
            ),
        ),
    )
    assert not result.referents
    assert all(item.reason == "no-antecedent" and item.referent is None for item in result.mentions)

    labels = fixture(
        (("source", "source", "AA AA"),),
        (("source", "AA", ((0, 2),)), ("source", "AA", ((3, 5),))),
    )
    document = labels.documents[0]
    first, second = labels.entities
    singleton = resolve_text_identity(
        labels,
        raw(labels, (annotation(first, document, (0, 2)), annotation(second, document, (3, 5)))),
    )
    assert len(singleton.referents) == 2
    typed = resolve_text_identity(
        labels,
        raw(
            labels,
            (
                annotation(first, document, (0, 2), entity_type={"kind": "person"}),
                annotation(second, document, (3, 5), entity_type={"kind": "object"}),
            ),
        ),
    )
    assert len(typed.referents) == 2


def test_root_and_reported_occurrences_share_identity_without_rewriting_role_contexts():
    value = fixture(
        (("source", "source", "AA AA"),),
        (("source", "AA", ((0, 2),)), ("source", "AA", ((3, 5),))),
        held=("source",),
    )
    root = next(context for context in value.contexts if context.kind == "narrated")
    original = value.events[0]
    root_event = Event(
        side=original.side,
        context=root.id,
        predicate=original.predicate,
        participants=original.participants,
        evidence=original.evidence,
    )
    events = (root_event, value.events[1])
    value = value.model_copy(update={"events": events})
    document = value.documents[0]
    first, second = value.entities
    raw_annotations = raw(
        value,
        (
            annotation(first, document, (0, 2), entity_type={"kind": "person"}),
            annotation(second, document, (3, 5), entity_type={"kind": "person"}),
        ),
    )
    result = resolve_text_identity(value, raw_annotations)
    assert outcomes(result)[first.id].referent == outcomes(result)[second.id].referent
    rows = identity_role_table(value, raw_annotations, result)
    assert [(row.event, row.context) for row in rows] == [
        (event.id, event.context) for event in value.events
    ]


def test_entity_support_order_quantity_and_nonlocal_support_prevent_manufactured_resolution():
    value = fixture(
        (("source", "source", "AA he"),),
        (("source", "AA", ((0, 1), (1, 2))), ("source", "he", ((3, 5),))),
    )
    document = value.documents[0]
    antecedent, pronoun = value.entities
    result = resolve_text_identity(
        value,
        raw(
            value,
            (
                annotation(antecedent, document, (0, 2), entity_type={"kind": "person"}),
                annotation(pronoun, document, (3, 5), form="pronominal", person="third"),
            ),
        ),
    )
    assert outcomes(result)[antecedent.id].reason == "nonlocal-mention-support"
    assert outcomes(result)[pronoun.id].outcome == "open"
    assert outcomes(result)[pronoun.id].reason == "incomplete-antecedent-input"

    quantity = fixture((("source", "source", "he"),), (("source", "he", ((0, 2),)),))
    entity = quantity.entities[0]
    result = resolve_text_identity(
        quantity,
        raw(
            quantity,
            (
                annotation(
                    entity,
                    quantity.documents[0],
                    (0, 2),
                    form="name",
                    number="singular",
                ),
            ),
        ),
    )
    assert outcomes(result)[entity.id].reason == "invalid-annotation"
    assert "antecedent quantity" in (outcomes(result)[entity.id].explanation or "")

    broad = fixture(
        (("source", "source", "AA he"),),
        (("source", "AA", ((0, 5),)), ("source", "he", ((3, 5),))),
    )
    antecedent, pronoun = broad.entities
    shifted_quote = resolve_text_identity(
        broad,
        raw(
            broad,
            (
                annotation(
                    antecedent,
                    broad.documents[0],
                    (0, 2),
                    entity_type={"kind": "person"},
                ),
                annotation(
                    pronoun,
                    broad.documents[0],
                    (3, 5),
                    form="pronominal",
                    person="third",
                ),
            ),
        ),
    )
    assert outcomes(shifted_quote)[pronoun.id].reason == "incomplete-antecedent-input"


def test_first_second_person_contexts_and_verified_role_consumer_preserve_occurrences():
    root = fixture((("source", "source", "I"),), (("source", "I", ((0, 1),)),))
    entity = root.entities[0]
    result = resolve_text_identity(
        root,
        raw(
            root,
            (annotation(entity, root.documents[0], (0, 1), form="pronominal", person="first"),),
        ),
    )
    assert outcomes(result)[entity.id].reason == "outside-held-context"
    assert (
        identity_role_table(
            root,
            raw(
                root,
                (annotation(entity, root.documents[0], (0, 1), form="pronominal", person="first"),),
            ),
            result,
        )[0].identity
        == outcomes(result)[entity.id]
    )
    with pytest.raises(ValueError, match="differs from recorded"):
        identity_role_table(
            root,
            raw(
                root,
                (annotation(entity, root.documents[0], (0, 1), form="pronominal", person="first"),),
            ),
            result.model_copy(update={"origin": "forged"}),
        )

    held = fixture(
        (("source", "source", "you"),), (("source", "you", ((0, 3),)),), held=("source",)
    )
    entity = held.entities[0]
    result = resolve_text_identity(
        held,
        raw(
            held,
            (annotation(entity, held.documents[0], (0, 3), form="pronominal", person="second"),),
        ),
    )
    assert outcomes(result)[entity.id].reason == "holder-not-supplied"

    unknown = root.model_copy(update={"events": ()})
    entity = unknown.entities[0]
    unknown_result = resolve_text_identity(
        unknown,
        raw(
            unknown,
            (
                annotation(
                    entity,
                    unknown.documents[0],
                    (0, 1),
                    form="pronominal",
                    person="first",
                ),
            ),
        ),
    )
    assert outcomes(unknown_result)[entity.id].reason == "context-unknown"


def test_non_bmp_offsets_are_utf16_and_annotation_quotes_cannot_reposition_mentions():
    value = fixture(
        (("source", "source", "😀 AA he"),),
        (("source", "AA", ((3, 5),)), ("source", "he", ((6, 8),))),
    )
    document = value.documents[0]
    antecedent, pronoun = value.entities
    result = resolve_text_identity(
        value,
        raw(
            value,
            (
                annotation(antecedent, document, (3, 5), entity_type={"kind": "person"}),
                annotation(pronoun, document, (6, 8), form="pronominal", person="third"),
            ),
        ),
    )
    assert outcomes(result)[pronoun.id].outcome == "resolved"


def test_annotation_boundaries_shuffle_and_result_verification_are_strict():
    value = fixture(
        (("source", "source", "AA he"),), (("source", "AA", ((0, 2),)), ("source", "he", ((3, 5),)))
    )
    document = value.documents[0]
    first, second = value.entities
    annotations = (
        annotation(first, document, (0, 2), entity_type={"kind": "person"}),
        annotation(second, document, (3, 5), form="pronominal", person="third"),
    )
    forward, reverse = raw(value, annotations), raw(value, tuple(reversed(annotations)))
    a, b = resolve_text_identity(value, forward), resolve_text_identity(value, reverse)
    assert a.referents == b.referents and a.mentions == b.mentions
    assert a.response_sha256 == sha256(forward.encode()).hexdigest() != b.response_sha256
    with pytest.raises(ValueError, match="duplicate or foreign"):
        resolve_text_identity(value, raw(value, (annotations[0], annotations[0])))
    foreign = annotation(first, document, (0, 2)) | {"mention": "foreign"}
    with pytest.raises(ValueError, match="duplicate or foreign"):
        resolve_text_identity(value, raw(value, (foreign,)))
    bad_quote = annotation(first, document, (0, 2), entity_type={"kind": "person"})
    bad_quote["quote"]["text"] = "tampered"
    invalid = resolve_text_identity(value, raw(value, (bad_quote, annotations[1])))
    assert outcomes(invalid)[first.id].reason == "invalid-annotation"
    assert "form quote differs" in (outcomes(invalid)[first.id].explanation or "")
    with pytest.raises(ValueError, match="differs from recorded"):
        verify_identity(value, forward, a.model_copy(update={"origin": "forged"}))


def test_source_identity_classes_keep_ids_after_pairing_another_document():
    source = fixture((("source", "source", "AA"),), (("source", "AA", ((0, 2),)),))
    recall = fixture((("recall", "recall", "BB"),), (("recall", "BB", ((0, 2),)),))
    source_entity = source.entities[0]
    source_raw = raw(
        source,
        (annotation(source_entity, source.documents[0], (0, 2), entity_type={"kind": "person"}),),
    )
    source_result = resolve_text_identity(source, source_raw)
    paired = pair_accounts(source, recall)
    paired_entity = next(entity for entity in paired.entities if entity.id == source_entity.id)
    paired_raw = raw(
        paired,
        (annotation(paired_entity, paired.documents[0], (0, 2), entity_type={"kind": "person"}),),
    )
    paired_result = resolve_text_identity(paired, paired_raw)
    assert source_result.referents[0].id == paired_result.referents[0].id


def test_nonintroducing_type_metadata_is_not_silently_ignored():
    value = fixture(
        (("source", "source", "AA he"),),
        (("source", "AA", ((0, 2),)), ("source", "he", ((3, 5),))),
    )
    first, pronoun = value.entities
    base = annotation(first, value.documents[0], (0, 2), entity_type={"kind": "person"})
    third = annotation(pronoun, value.documents[0], (3, 5), form="pronominal", person="third")
    good = resolve_text_identity(value, raw(value, (base, third)))
    assert outcomes(good)[pronoun.id].outcome == "resolved"
    typed = third | {"entity_type": {"kind": "object"}}
    refused = resolve_text_identity(value, raw(value, (base, typed)))
    assert outcomes(refused)[pronoun.id].reason == "invalid-annotation"
    assert outcomes(refused)[pronoun.id].referent is None
    assert "only for introducing forms" in outcomes(refused)[pronoun.id].explanation
