"""Adversarial controls for contextual conditional fidelity v2."""

import json
from pathlib import Path

import pytest
from test_direct_mapping import decision, fixture, quote, raw
from test_identity import annotation
from test_identity import raw as identity_raw

from storymodel.accounts import make_account
from storymodel.context_fidelity import ContextAccounting, ContextEvidence
from storymodel.direct_mapping import prepare_mapping, resolve_mapping
from storymodel.fidelity import (
    ContextFidelityReply,
    ContextFidelityRequest,
    ContextFidelityResult,
    fidelity_prompt,
    prepare_fidelity,
    resolve_fidelity,
)
from storymodel.model import (
    Assessment,
    Context,
    Document,
    Entity,
    Event,
    Participation,
    Span,
    Support,
)
from storymodel.serialization import canonical, digest

PILOT = Path(__file__).resolve().parents[1]


def contextual_mapping(
    *,
    unresolved_recall_holder=False,
    missing_detail_event=False,
    pronoun: tuple[str, str] | None = None,
    transitive_source_member=False,
):
    """Build a mapped control with target-local and deliberately withheld neighbours."""
    request, rows, _ = fixture(suffix=" I NEIGHBOR-SECRET")
    base = request.account
    source, recall = base.documents
    target, unrelated = rows
    recall_unit = next(unit for unit in base.units if unit.kind == "recall")
    source_root = next(c for c in base.contexts if c.side == "source")
    recall_root = next(c for c in base.contexts if c.side == "recall")
    source_context = Context(
        side="source", kind="reported", parent=source_root.id, evidence=(target.support,)
    )
    linked_recall_support = Support(document=recall.id, spans=(Span(start=0, end=7),))
    recall_context = Context(
        side="recall",
        kind="intended",
        parent=recall_root.id,
        evidence=(linked_recall_support,),
    )
    source_holder = Entity.create(
        source.id, "AA", (Support(document=source.id, spans=(Span(start=0, end=2),)),)
    )
    recall_holder = Entity.create(
        recall.id, "AA", (Support(document=recall.id, spans=(Span(start=0, end=2),)),)
    )
    unrelated_source = Entity.create(
        source.id, "DD", (Support(document=source.id, spans=(Span(start=9, end=11),)),)
    )
    transitive_member = (
        Entity.create(
            source.id, "AA", (Support(document=source.id, spans=(Span(start=6, end=8),)),)
        )
        if transitive_source_member
        else None
    )
    pronoun_entity = (
        Entity.create(recall.id, "I", (Support(document=recall.id, spans=(Span(start=6, end=7),)),))
        if pronoun is not None
        else None
    )
    other_document = Document.create("other-recall", "recall", "OTHER-DOC-SECRET")
    other_entity = Entity.create(
        other_document.id,
        "OTHER",
        (Support(document=other_document.id, spans=(Span(start=0, end=5),)),),
    )
    source_event = Event(
        side="source",
        context=source_context.id,
        predicate="target-only",
        evidence=(target.support,),
        participants=(
            Participation(entity=source_holder.id, role="agent", evidence=source_holder.evidence),
        ),
    )
    foreign_source_event = Event(
        side="source",
        context=source_root.id,
        predicate="OUT-OF-SCOPE-SOURCE",
        evidence=(unrelated.support,),
        participants=(
            Participation(
                entity=unrelated_source.id, role="agent", evidence=unrelated_source.evidence
            ),
            *(
                (
                    Participation(
                        entity=transitive_member.id,
                        role="patient",
                        evidence=transitive_member.evidence,
                    ),
                )
                if transitive_member is not None
                else ()
            ),
        ),
    )
    recall_event = Event(
        side="recall",
        context=recall_context.id,
        predicate="linked-recall",
        evidence=(linked_recall_support,),
        participants=(
            Participation(entity=recall_holder.id, role="speaker", evidence=recall_holder.evidence),
            *(
                (
                    Participation(
                        entity=pronoun_entity.id,
                        role="agent",
                        evidence=pronoun_entity.evidence,
                    ),
                )
                if pronoun_entity is not None
                else ()
            ),
        ),
    )
    neighbor_support = Support(
        document=recall.id,
        spans=(Span(start=6, end=len(recall.text.encode("utf-16-le")) // 2),),
    )
    neighbor = Event(
        side="recall",
        context=recall_root.id,
        predicate="NEIGHBOR-ONLY",
        evidence=(neighbor_support,),
    )
    other_event = Event(
        side="recall",
        context=recall_root.id,
        predicate="OTHER-DOC-ONLY",
        evidence=other_entity.evidence,
        participants=(
            Participation(entity=other_entity.id, role="agent", evidence=other_entity.evidence),
        ),
    )
    detail = base.details[0].model_copy(
        update={"event": None if missing_detail_event else recall_event.id}
    )
    account = make_account(
        (*base.documents, other_document),
        units=base.units,
        contexts=(source_root, recall_root, source_context, recall_context),
        entities=(
            source_holder,
            recall_holder,
            unrelated_source,
            *(() if transitive_member is None else (transitive_member,)),
            *(() if pronoun_entity is None else (pronoun_entity,)),
            other_entity,
        ),
        events=(source_event, foreign_source_event, recall_event, neighbor, other_event),
        details=(detail,),
        inventories=base.inventories,
        export_permission="allowed",
    )
    mapping_request = prepare_mapping(account, recall_unit.id)
    mapped = resolve_mapping(mapping_request, raw(mapping_request, decision((target,))))
    reference = mapped.account.bindings[0].reference
    protected = mapped.account.bindings[0].model_copy(
        update={
            "reference": reference.model_copy(update={"interpretation": "narrative"}),
            "assessments": (
                Assessment(
                    hypothesis=reference.hypotheses[0].id,
                    status="refused",
                    reason="PRIVATE-OLD-GRADE",
                ),
            ),
        }
    )
    mapped = mapped.model_copy(
        update={
            "account": mapped.account.model_copy(
                update={"bindings": (*mapped.account.bindings, protected)}
            )
        }
    )
    forms = [
        annotation(source_holder, source, (0, 2), entity_type={"kind": "person"}),
        annotation(unrelated_source, source, (9, 11), entity_type={"kind": "person"}),
    ]
    if unresolved_recall_holder:
        forms.append(
            annotation(
                recall_holder,
                recall,
                (0, 2),
                form="pronominal",
                person="first",
                number="singular",
            )
        )
    else:
        forms.append(annotation(recall_holder, recall, (0, 2), entity_type={"kind": "person"}))
    if transitive_member is not None:
        forms.append(annotation(transitive_member, source, (6, 8), entity_type={"kind": "person"}))
    if pronoun_entity is not None:
        person, number = pronoun
        forms.append(
            annotation(
                pronoun_entity,
                recall,
                (6, 7),
                form="pronominal",
                person=person,
                number=number,
            )
        )
    forms.append(
        {"mention": other_entity.id, "status": "unsupported", "reason": "ANNOTATION-SECRET"}
    )
    # Mapping changes the account receipt/bindings but not any supplied context evidence.
    # Recordings must bind the exact post-mapping account passed to fidelity.
    annotations = identity_raw(mapped.account, forms)
    holders = canonical(
        {
            "schema_version": "storymodel.holder-proposals/v1",
            "account_sha256": digest(mapped.account.model_dump(mode="json")),
            "origin": "context-fidelity-control",
            "contexts": [
                {
                    "context": source_context.id,
                    "status": "completed",
                    "candidates": [source_holder.id],
                    "evidence": [target.support.model_dump(mode="json")],
                },
                {
                    "context": recall_context.id,
                    "status": "completed",
                    "candidates": [recall_holder.id],
                    "evidence": [recall_unit.support.model_dump(mode="json")],
                },
            ],
        }
    )
    return (
        mapped,
        target,
        ContextEvidence(identity_annotations=annotations, holder_proposals=holders),
    )


def contextual_request(**kwargs):
    mapped, target, evidence = contextual_mapping(**kwargs)
    return (
        prepare_fidelity(mapped.account, mapped.account.details[0].unit, context_evidence=evidence),
        target,
    )


def contextual_reply(request, *assessments):
    return canonical(
        {
            "schema_version": "storymodel.fidelity-proposal/v2",
            "packet_sha256": json.loads(fidelity_prompt(request))["packet_sha256"],
            "assessments": list(assessments),
        }
    )


def contextual_assessment(request, key="a0", *, status="completed", grade="supported", **extra):
    view = json.loads(fidelity_prompt(request))["context_evidence"][key]
    row = {
        "hypothesis": key,
        "status": status,
        "reason": "bounded control",
        "context_accounting": {
            "considered_contexts": [c["key"] for c in view["contexts"]],
            "required_holders": [],
            "required_mentions": [],
            "reason": "no identity dependency",
        },
        **extra,
    }
    if status == "completed":
        row["grade"] = grade
    return row


def test_contextual_prompt_is_bounded_to_linked_detail_and_hypothesis_targets():
    request, _ = contextual_request()
    assert isinstance(request, ContextFidelityRequest)
    payload = json.loads(fidelity_prompt(request))
    view = payload["context_evidence"]["a0"]

    assert payload["recipe"] == "context-evidence-conditional-fidelity/v2"
    assert set(payload["context_evidence"]) == {"a0"}
    assert view["recall_placement"] == "linked"
    assert view["source_placement"] == "contained-situations"
    assert {row["predicate"] for row in view["situations"]} == {"target-only", "linked-recall"}
    encoded = canonical(view)
    assert "OUT-OF-SCOPE-SOURCE" not in encoded
    assert "NEIGHBOR-ONLY" not in encoded and "NEIGHBOR-SECRET" not in encoded
    assert "normalized-hidden" in payload["questions"][0]["claim"]
    assert "previous grades" in payload["instruction"]


def test_missing_detail_event_has_no_recorded_recall_placement_or_invented_root():
    request, _ = contextual_request(missing_detail_event=True)
    view = json.loads(fidelity_prompt(request))["context_evidence"]["a0"]

    assert view["recall_placement"] == "no-recorded-situation"
    assert {row["side"] for row in view["contexts"]} == {"source"}
    assert all(row["side"] == "source" for row in view["situations"])


def test_linked_recall_closure_exposes_only_local_excerpt_coordinates_and_no_private_state():
    request, _ = contextual_request()
    prompt = fidelity_prompt(request)
    view = json.loads(prompt)["context_evidence"]["a0"]
    linked = next(row for row in view["situations"] if row["predicate"] == "linked-recall")

    assert linked["evidence"] == [
        {"side": "recall", "span": {"start": 0, "end": 7}, "text": "AA BB I"}
    ]
    excerpts = [
        *linked["evidence"],
        *(row for context in view["contexts"] for row in context["background_evidence"]),
        *(row for mention in view["mentions"] for row in mention["evidence"]),
    ]
    assert excerpts and all(row["span"]["start"] == 0 and "document" not in row for row in excerpts)
    for withheld in (
        "NEIGHBOR-SECRET",
        "OTHER-DOC-SECRET",
        "OTHER-DOC-ONLY",
        "PRIVATE-OLD-GRADE",
        "ANNOTATION-SECRET",
    ):
        assert withheld not in prompt


def test_contextual_accounting_rejects_foreign_duplicate_and_missing_aliases():
    request, target = contextual_request()
    valid = contextual_assessment(
        request,
        evidence=[quote(target)],
        context_accounting={
            "considered_contexts": ["foreign"],
            "required_holders": [],
            "required_mentions": [],
            "reason": "bad alias",
        },
    )
    foreign = resolve_fidelity(request, contextual_reply(request, valid))
    assert foreign.decisions[0].status == "rejected"
    assert "consider every supplied" in foreign.decisions[0].reason

    view = json.loads(fidelity_prompt(request))["context_evidence"]["a0"]
    aliases = [c["key"] for c in view["contexts"]]
    duplicate = contextual_assessment(
        request,
        evidence=[quote(target)],
        context_accounting={
            "considered_contexts": [*aliases, aliases[0]],
            "required_holders": [],
            "required_mentions": [],
            "reason": "duplicate alias",
        },
    )
    duplicate_result = resolve_fidelity(request, contextual_reply(request, duplicate))
    assert duplicate_result.decisions[0].status == "rejected"
    assert "duplicate context accounting alias" in duplicate_result.decisions[0].reason


def test_context_recordings_remain_bound_to_the_post_mapping_account():
    mapped, _, evidence = contextual_mapping()
    forged = json.loads(evidence.holder_proposals)
    forged["account_sha256"] = "0" * 64
    swapped = evidence.model_copy(update={"holder_proposals": canonical(forged)})

    with pytest.raises(ValueError, match="another account snapshot"):
        prepare_fidelity(mapped.account, mapped.account.details[0].unit, context_evidence=swapped)


def test_unresolved_required_holder_blocks_grade_but_independent_answer_is_admitted():
    request, target = contextual_request(unresolved_recall_holder=True)
    view = json.loads(fidelity_prompt(request))["context_evidence"]["a0"]
    holder_context = next(
        c for c in view["contexts"] if c["side"] == "recall" and c["holder"]["applicable"]
    )
    assert not holder_context["holder"]["available"]
    blocked = contextual_assessment(
        request,
        evidence=[quote(target)],
        context_accounting={
            "considered_contexts": [c["key"] for c in view["contexts"]],
            "required_holders": [holder_context["key"]],
            "required_mentions": [],
            "reason": "depends on named holder",
        },
    )
    rejected = resolve_fidelity(request, contextual_reply(request, blocked))
    assert rejected.decisions[0].status == "rejected"
    assert "unresolved or withheld" in rejected.decisions[0].reason

    independent = contextual_assessment(request, evidence=[quote(target)])
    admitted = resolve_fidelity(request, contextual_reply(request, independent))
    assert admitted.decisions[0].status == "admitted"
    assert isinstance(admitted, ContextFidelityResult)
    assert admitted.context_accounting["a0"].reason == "no identity dependency"


def test_unresolved_required_mention_allows_only_unverifiable_completion():
    request, target = contextual_request(unresolved_recall_holder=True)
    view = json.loads(fidelity_prompt(request))["context_evidence"]["a0"]
    unresolved = next(m["key"] for m in view["mentions"] if not m["identity"]["available"])
    assert any(m["identity"]["reason"] == "holder-unresolved" for m in view["mentions"])
    base = {
        "considered_contexts": [c["key"] for c in view["contexts"]],
        "required_holders": [],
        "required_mentions": [unresolved],
        "reason": "pronoun identity required",
    }
    bad = contextual_assessment(request, evidence=[quote(target)], context_accounting=base)
    assert (
        resolve_fidelity(request, contextual_reply(request, bad)).decisions[0].status == "rejected"
    )
    good = contextual_assessment(
        request, grade="unverifiable", evidence=[], context_accounting=base
    )
    admitted = resolve_fidelity(request, contextual_reply(request, good))
    assert admitted.decisions[0].status == "admitted"


@pytest.mark.parametrize(
    ("person", "number", "reason"),
    (("first", "unknown", "speaker-number-unknown"), ("second", "singular", "needs-addressee")),
)
def test_named_holder_does_not_resolve_unknown_speaker_or_addressee(person, number, reason):
    request, target = contextual_request(pronoun=(person, number))
    view = json.loads(fidelity_prompt(request))["context_evidence"]["a0"]
    recall_context = next(
        c for c in view["contexts"] if c["side"] == "recall" and c["holder"]["applicable"]
    )
    unresolved = next(m for m in view["mentions"] if m["identity"]["reason"] == reason)
    accounting = {
        "considered_contexts": [c["key"] for c in view["contexts"]],
        "required_holders": [],
        "required_mentions": [unresolved["key"]],
        "reason": "the open pronoun is material",
    }

    assert recall_context["holder"]["status"] == "named"
    assert recall_context["holder"]["available"]
    before = request.account.bindings
    result = resolve_fidelity(
        request,
        contextual_reply(
            request,
            contextual_assessment(request, evidence=[quote(target)], context_accounting=accounting),
        ),
    )
    assert result.decisions[0].status == "rejected"
    assert result.account.bindings == before
    assert result.account.bindings[1].assessments[0].reason == "PRIVATE-OLD-GRADE"


def test_transitive_identity_member_outside_visible_closure_withholds_named_holder():
    request, target = contextual_request(transitive_source_member=True)
    view = json.loads(fidelity_prompt(request))["context_evidence"]["a0"]
    source_context = next(
        c for c in view["contexts"] if c["side"] == "source" and c["holder"]["applicable"]
    )
    accounting = {
        "considered_contexts": [c["key"] for c in view["contexts"]],
        "required_holders": [source_context["key"]],
        "required_mentions": [],
        "reason": "the named source holder is material",
    }

    assert source_context["holder"]["status"] == "named"
    assert not source_context["holder"]["available"]
    assert source_context["holder"]["witnesses_withheld"]
    rejected = resolve_fidelity(
        request,
        contextual_reply(
            request,
            contextual_assessment(request, evidence=[quote(target)], context_accounting=accounting),
        ),
    )
    assert rejected.decisions[0].status == "rejected"


def test_missing_context_accounting_and_foreign_required_mention_leave_bindings_untouched():
    request, target = contextual_request()
    baseline = request.account.bindings
    without_accounting = contextual_assessment(request, evidence=[quote(target)])
    without_accounting.pop("context_accounting")
    missing = resolve_fidelity(request, contextual_reply(request, without_accounting))
    assert missing.decisions[0].status == "rejected" and missing.account.bindings == baseline

    view = json.loads(fidelity_prompt(request))["context_evidence"]["a0"]
    invalid = contextual_assessment(
        request,
        evidence=[quote(target)],
        context_accounting={
            "considered_contexts": [],
            "required_holders": [],
            "required_mentions": ["m999"],
            "reason": "foreign witness",
        },
    )
    rejected = resolve_fidelity(request, contextual_reply(request, invalid))
    assert rejected.decisions[0].status == "rejected"
    assert rejected.account.bindings == baseline
    assert rejected.account.bindings[1].assessments[0].reason == "PRIVATE-OLD-GRADE"

    foreign_mention = contextual_assessment(
        request,
        evidence=[quote(target)],
        context_accounting={
            "considered_contexts": [c["key"] for c in view["contexts"]],
            "required_holders": [],
            "required_mentions": ["m999"],
            "reason": "foreign witness",
        },
    )
    foreign = resolve_fidelity(request, contextual_reply(request, foreign_mention))
    assert foreign.decisions[0].status == "rejected"
    assert "foreign context accounting mention" in foreign.decisions[0].reason
    assert foreign.account.bindings == baseline


def test_context_background_cannot_promote_root_or_replace_target_owned_quote():
    request, target = contextual_request()
    payload = json.loads(fidelity_prompt(request))
    view = payload["context_evidence"]["a0"]
    assert any(c["parent"] is None and c["kind"] == "narrated" for c in view["contexts"])
    # A correct source string outside the fixed target remains inadmissible even when it appears
    # in the private account and the contextual profile includes source background.
    outside = {
        "document": request.account.documents[0].id,
        "span": {"start": 6, "end": 11},
        "text": "CC DD",
    }
    result = resolve_fidelity(
        request,
        contextual_reply(request, contextual_assessment(request, evidence=[outside])),
    )
    assert result.decisions[0].status == "rejected"
    assert "outside its source hypothesis" in result.decisions[0].reason


def test_context_fidelity_schema_drift():
    assert json.loads((PILOT / "schemas/context-fidelity.json").read_text()) == {
        "evidence": ContextEvidence.model_json_schema(),
        "accounting": ContextAccounting.model_json_schema(),
        "request": ContextFidelityRequest.model_json_schema(),
        "reply": ContextFidelityReply.model_json_schema(),
        "result": ContextFidelityResult.model_json_schema(),
    }


def test_cli_preserves_exact_crlf_context_recordings(tmp_path):
    from storymodel.cli import main
    from storymodel.fidelity import fidelity_request_digest, validate_fidelity_request

    mapped, _, evidence = contextual_mapping()
    account_path = tmp_path / "account.json"
    account_path.write_text(mapped.account.model_dump_json())
    exact = {}
    for field in ("identity_annotations", "holder_proposals"):
        raw = (json.dumps(json.loads(getattr(evidence, field)), indent=2) + "\n").replace(
            "\n", "\r\n"
        )
        (tmp_path / f"{field}.json").write_bytes(raw.encode("utf-8"))
        exact[field] = raw
    expected = prepare_fidelity(
        mapped.account, mapped.account.details[0].unit, context_evidence=ContextEvidence(**exact)
    )
    output = tmp_path / "request.json"
    assert (
        main(
            [
                "prepare-fidelity",
                str(account_path),
                expected.unit,
                "--identity-annotations",
                str(tmp_path / "identity_annotations.json"),
                "--holder-proposals",
                str(tmp_path / "holder_proposals.json"),
                "--output",
                str(output),
            ]
        )
        == 0
    )
    actual = validate_fidelity_request(output.read_bytes())
    assert isinstance(actual, ContextFidelityRequest)
    for field in exact:
        assert (
            getattr(actual.context_evidence, field).encode("utf-8")
            == (tmp_path / f"{field}.json").read_bytes()
        )
    assert fidelity_request_digest(actual) == fidelity_request_digest(expected)
    assert actual == expected
