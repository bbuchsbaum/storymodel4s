"""Independent expected structure on admitted text; context controls are nonnarrative tokens."""

import json

import pytest

from storymodel.analysis import project
from storymodel.demo import example_account
from storymodel.extraction import (
    prepare_extraction,
    request_digest,
    resolve_extraction,
    validate_request,
)
from storymodel.extraction_demo import example_proposal, example_request
from storymodel.model import (
    Event,
    Participation,
    Span,
    canonical,
    validate_account,
)


def quote(text, start, end):
    return {"span": {"start": start, "end": end}, "text": Span(start=start, end=end).slice(text)}


def scoped(kind="believed"):
    text = "AA BB CC DD EE FF"
    request = prepare_extraction("scope-control", text.encode())
    unit = {
        "u": 1,
        "status": "completed",
        "contexts": [
            {
                "key": "outer",
                "kind": kind,
                "parent": "narrated",
                "cue": quote(text, 0, 2),
                "scope": quote(text, 3, 17),
            }
        ],
        "events": [
            {
                "key": "event",
                "context": "outer",
                "evidence": quote(text, 12, 17),
                "predicate": quote(text, 15, 17),
            }
        ],
        "details": [
            {"key": "detail", "event": "event", "kind": "event", "evidence": quote(text, 12, 17)}
        ],
    }
    return request, {"schema_version": "storymodel.extraction-proposal/v1", "units": [unit]}


def test_admitted_raw_recall_becomes_events_details_and_unresolved_identity():
    request = example_request()
    result = resolve_extraction(request, example_proposal())
    account = result.account
    assert account.schema_version == "storymodel.account/v2"
    assert [e.predicate for e in account.events if e.side == "recall"] == ["left", "watched"]
    assert [
        (d.kind, d.support.spans[0].start, d.support.spans[0].end) for d in account.details
    ] == [
        ("event", 4, 43),
        ("attribute", 19, 23),
        ("event", 49, 64),
    ]
    assert account.details[0].event == account.details[1].event != account.details[2].event
    local = [e for e in account.entities if e.source_document == request.recall.document.id]
    assert [e.label for e in local] == ["courier", "someone"]
    assert local[0].id != request.source.entities[0].id
    assert all(
        p.entity in {e.id for e in local}
        for e in account.events
        if e.side == "recall"
        for p in e.participants
    )
    assert result.resolutions[0].status == "partial"
    assert result.resolutions[0].unresolved[0].key == "actor-identity"
    assert account.inventories[0].status == "search-limited"
    assert not account.bindings
    analysis = project(account)
    assert [r.reference_status for r in analysis.recall] == ["not-attempted"] * 3
    assert analysis.counts.unresolved == 3 and analysis.counts.internal_fraction is None
    assert {r.coverage for r in analysis.source} == {"not-assessable"}
    assert project(account, "target-event-only/v1").counts.excluded == 1
    assert validate_account(account.model_dump_json()) == account


@pytest.mark.parametrize("kind", ["believed", "reported", "intended"])
def test_attributed_content_cannot_be_promoted_to_root_world(kind):
    request, packet = scoped(kind)
    good = resolve_extraction(request, canonical(packet))
    assert good.resolutions[0].status == "admitted"
    context = next(c for c in good.account.contexts if c.kind == kind)
    assert good.account.events[0].context == context.id
    packet["units"][0]["events"][0]["context"] = "narrated"
    bad = resolve_extraction(request, canonical(packet))
    assert bad.resolutions[0].status == "rejected"
    assert "context differs" in bad.resolutions[0].reason
    assert not bad.account.events and not bad.account.details
    assert bad.account.inventories[0].status == "failed"


def test_nested_scopes_select_deepest_context_and_reject_ancestor_assignment():
    request, packet = scoped()
    unit = packet["units"][0]
    text = request.recall.document.text
    unit["contexts"].append(
        {
            "key": "inner",
            "kind": "reported",
            "parent": "outer",
            "cue": quote(text, 6, 8),
            "scope": quote(text, 9, 17),
        }
    )
    unit["events"][0]["context"] = "inner"
    good = resolve_extraction(request, canonical(packet))
    assert good.resolutions[0].status == "admitted"
    unit["events"][0]["context"] = "outer"
    assert resolve_extraction(request, canonical(packet)).resolutions[0].status == "rejected"


@pytest.mark.parametrize("change", ["overlap", "cycle", "foreign", "crossing", "discontinuous"])
def test_ambiguous_or_unsupported_scopes_refuse_without_guessing(change):
    request, packet = scoped()
    unit = packet["units"][0]
    text = request.recall.document.text
    if change == "overlap":
        unit["contexts"].append(
            {
                "key": "sibling",
                "kind": "reported",
                "parent": "narrated",
                "cue": quote(text, 0, 2),
                "scope": quote(text, 9, 17),
            }
        )
    elif change in ("cycle", "foreign"):
        unit["contexts"][0]["parent"] = "outer" if change == "cycle" else "missing"
    elif change == "crossing":
        unit["events"][0]["evidence"] = quote(text, 0, 17)
    else:
        unit["contexts"][0]["scope"]["spans"] = [{"start": 3, "end": 8}, {"start": 12, "end": 17}]
    result = resolve_extraction(request, canonical(packet))
    assert result.resolutions[0].status == "rejected" and not result.account.events


@pytest.mark.parametrize(
    "change",
    [
        "unsupported",
        "foreign-entity",
        "foreign-event",
        "missing-context",
        "duplicate-key",
        "duplicate-role",
        "outside",
        "address",
        "source-id",
    ],
)
def test_invalid_claim_rejects_unit_atomically(change):
    request = example_request()
    packet = json.loads(example_proposal())
    unit = packet["units"][0]
    if change == "unsupported":
        unit["events"][0]["predicate"]["text"] = "unsupported"
    elif change == "foreign-entity":
        unit["events"][0]["participants"][0]["entity"] = "foreign"
    elif change == "foreign-event":
        unit["details"][0]["event"] = "foreign"
    elif change == "missing-context":
        del unit["events"][0]["context"]
    elif change == "duplicate-key":
        unit["details"][0]["key"] = unit["events"][0]["key"]
    elif change == "duplicate-role":
        unit["events"][0]["participants"] *= 2
    elif change == "outside":
        unit["details"][0]["evidence"]["span"]["end"] = 200
    elif change == "address":
        unit["details"][0]["address"] = "target"
    else:
        unit["events"][0]["participants"][0]["entity"] = request.source.entities[0].id
    result = resolve_extraction(request, canonical(packet))
    assert result.resolutions[0].status == "rejected"
    assert not result.account.details
    assert all(e.side == "source" for e in result.account.events)
    assert result.account.entities == request.source.entities


def test_non_bmp_spans_are_exact_and_surrogate_splits_refuse():
    text = "AA 😀 BB CC"
    request = prepare_extraction("unicode", text.encode())
    packet = {
        "schema_version": "storymodel.extraction-proposal/v1",
        "units": [
            {
                "u": 1,
                "status": "completed",
                "events": [
                    {
                        "key": "e",
                        "context": "narrated",
                        "evidence": quote(text, 3, 5),
                        "predicate": quote(text, 3, 5),
                    }
                ],
            }
        ],
    }
    assert resolve_extraction(request, canonical(packet)).account.events[0].predicate == "😀"
    packet["units"][0]["events"][0]["predicate"]["span"]["end"] = 4
    result = resolve_extraction(request, canonical(packet))
    assert "surrogate" in result.resolutions[0].reason and not result.account.events


def test_missing_failed_and_zero_detail_completion_stay_distinct():
    request = prepare_extraction("inventory", b"AA BB\n\nCC DD\n\nEE FF")
    packet = {
        "schema_version": "storymodel.extraction-proposal/v1",
        "units": [
            {"u": 1, "status": "completed"},
            {"u": 2, "status": "failed", "reason": "provider failure"},
        ],
    }
    result = resolve_extraction(request, canonical(packet))
    assert [i.status for i in result.account.inventories] == [
        "completed",
        "failed",
        "not-attempted",
    ]
    assert [r.status for r in result.resolutions] == ["admitted", "failed", "not-attempted"]


def test_one_bad_unit_does_not_erase_other_valid_units():
    request = prepare_extraction("two", b"AA BB\n\nCC DD")
    packet = {
        "schema_version": "storymodel.extraction-proposal/v1",
        "units": [
            {
                "u": 1,
                "status": "completed",
                "events": [
                    {
                        "key": "e",
                        "context": "narrated",
                        "evidence": quote(request.recall.document.text, 0, 5),
                        "predicate": quote(request.recall.document.text, 0, 2),
                    }
                ],
            },
            {"u": 2, "status": "completed", "surprise": 1},
        ],
    }
    result = resolve_extraction(request, canonical(packet))
    assert [r.status for r in result.resolutions] == ["admitted", "rejected"]
    assert len(result.account.events) == 1


@pytest.mark.parametrize(
    "change", ["duplicate", "foreign", "boolean", "duplicate-json", "nonfinite"]
)
def test_ambiguous_response_identity_refuses(change):
    request, packet = scoped()
    if change == "duplicate":
        packet["units"] *= 2
    elif change == "foreign":
        packet["units"][0]["u"] = 100
    elif change == "boolean":
        packet["units"][0]["u"] = True
    raw = canonical(packet)
    if change == "duplicate-json":
        raw = raw.replace('"u":1', '"u":1,"u":1')
    elif change == "nonfinite":
        raw = raw.replace('"u":1', '"u":NaN')
    with pytest.raises(ValueError):
        resolve_extraction(request, raw)


def test_recall_entity_identity_is_session_local_and_v1_remains_unchanged():
    old = example_account()
    assert validate_account(old.model_dump_json()) == old
    request = example_request()
    account = resolve_extraction(request, example_proposal()).account
    other_request = prepare_extraction("other-session", request.recall.raw_text.encode())
    other = resolve_extraction(other_request, example_proposal()).account
    a = next(e for e in account.entities if e.source_document == request.recall.document.id)
    assert a.id != other.entities[0].id
    with pytest.raises(ValueError, match="v1"):
        validate_account(account.model_copy(update={"schema_version": "storymodel.account/v1"}))
    # Same-side but foreign-session use also refuses, not merely source/recall mismatch.
    event = next(e for e in other.events if e.side == "recall")
    role = Participation(entity=a.id, role="agent", evidence=event.evidence)
    foreign = Event(
        side="recall",
        context=event.context,
        predicate=event.predicate,
        evidence=event.evidence,
        participants=(role,),
    )
    mixed = account.model_copy(
        update={
            "documents": account.documents + other.documents,
            "events": account.events + (foreign,),
            "recipe": account.recipe.model_copy(
                update={"input_sha256": (*account.recipe.input_sha256, other.documents[0].sha256)}
            ),
        }
    )
    with pytest.raises(ValueError, match="another event document"):
        validate_account(mixed)
    source_event = next(e for e in account.events if e.side == "source")
    wrong = Event(
        side="source",
        context=source_event.context,
        predicate=source_event.predicate,
        evidence=source_event.evidence,
        participants=(Participation(entity=a.id, role="agent", evidence=source_event.evidence),),
    )
    with pytest.raises(ValueError, match="another event document or side"):
        validate_account(account.model_copy(update={"events": (wrong,)}))


def test_source_is_independent_and_permissions_and_copies_are_checked():
    request = example_request()
    source_before = request.source.model_dump_json()
    result = resolve_extraction(request, example_proposal())
    assert request.source.model_dump_json() == source_before
    assert [e for e in result.account.events if e.side == "source"] == list(request.source.events)
    with pytest.raises(ValueError, match="source-only"):
        prepare_extraction("bad", b"AA", source=example_account())
    denied = request.source.model_copy(update={"export_permission": "denied"})
    with pytest.raises(ValueError, match="elevate"):
        prepare_extraction("bad", b"AA", source=denied, export_permission="allowed")
    forged = request.model_copy(
        update={"recall": request.recall.model_copy(update={"raw_text": "AA"})}
    )
    with pytest.raises(ValueError):
        validate_request(forged)
    changed_raw = prepare_extraction(
        request.recall.key, (request.recall.raw_text + "\n").encode(), source=request.source
    )
    assert changed_raw.recall.canonical_text == request.recall.canonical_text
    assert request_digest(changed_raw) != request_digest(request)
