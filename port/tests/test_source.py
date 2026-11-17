"""Independent source-construction expectations; additional controls are nonnarrative tokens."""

import json

import pytest

from storymodel import (
    account_digest,
    pair_accounts,
    prepare_extraction,
    project,
    resolve_extraction,
)
from storymodel.artifacts import export_account, read_account
from storymodel.demo import RECALL_TEXT
from storymodel.extraction_demo import example_proposal
from storymodel.model import Document, Span, validate_account
from storymodel.serialization import canonical
from storymodel.source import (
    SourceResult,
    SourceRow,
    compile_source,
    prepare_source,
    validate_source_request,
    verify_source,
)
from storymodel.source_demo import example_source_proposal, example_source_request


def quote(text, start, end):
    # All controls below except the dedicated Unicode test contain ASCII tokens.
    return {"span": {"start": start, "end": end}, "text": text[start:end]}


def control():
    text = "AA BB CC DD EE FF"
    request = prepare_source(
        "scope-control", text, (SourceRow(key="row", span=Span(start=0, end=17)),)
    )
    row = {
        "row": "row",
        "status": "completed",
        "contexts": [
            {
                "key": "outer",
                "kind": "believed",
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
    }
    return request, {"schema_version": "storymodel.source-proposal/v1", "rows": [row]}


def test_source_rows_compile_both_events_and_local_roles_from_recorded_proposals():
    request = example_source_request()
    raw = example_source_proposal()
    result = compile_source(request, raw)
    account = result.account
    assert account.schema_version == "storymodel.account/v3"
    assert [e.predicate for e in account.events] == ["places", "watches"]
    assert [e.label for e in account.entities] == ["courier", "guard"]
    assert [
        (e.evidence[0].spans[0].start, e.evidence[0].spans[0].end) for e in account.entities
    ] == [(2, 9), (45, 50)]
    assert len(account.units) == 2 and all(u.kind == "row" for u in account.units)
    assert len(account.contexts) == 1
    assert account.contexts[0].side == "source" and account.contexts[0].kind == "narrated"
    assert [r.status for r in result.resolutions] == ["admitted", "admitted"]
    assert [r.row for r in result.resolutions] == ["observation-1", "observation-2"]
    for event, entity, row, resolution in zip(
        account.events, account.entities, account.units, result.resolutions, strict=True
    ):
        assert event.context == account.contexts[0].id
        assert event.evidence == (row.support,)
        assert [(p.role, p.entity, p.evidence) for p in event.participants] == [
            ("agent", entity.id, entity.evidence)
        ]
        assert resolution.accepted == {"actor": entity.id, "action": event.id}
        assert resolution.unit == row.id
    assert not account.details and not account.inventories and not account.bindings
    assert validate_account(account.model_dump_json()) == account
    assert (
        verify_source(request, raw, SourceResult.model_validate_json(result.model_dump_json()))
        == result
    )


def test_saved_source_reused_unchanged_by_two_independent_recall_sessions(tmp_path):
    compiled = compile_source(example_source_request(), example_source_proposal())
    path = tmp_path / "source.json"
    export_account(path, compiled.account)
    before = path.read_bytes()
    source = read_account(path)
    original_digest = account_digest(source)
    recalls = []
    for session in ("session-A", "session-B"):
        recall = resolve_extraction(
            prepare_extraction(session, RECALL_TEXT.encode()), example_proposal()
        ).account
        recalls.append(recall)
        paired = pair_accounts(source, recall)
        assert [e for e in paired.events if e.side == "source"] == list(source.events)
        assert [e for e in paired.entities if e.origin_document == source.documents[0].id] == list(
            source.entities
        )
        assert paired.recipe.input_sha256[-2] == original_digest
        analysis = project(paired)
        assert len(analysis.recall) == 3
        assert [r.coverage for r in analysis.source] == ["not-assessable", "not-assessable"]
        assert paired.export_permission == "denied"
    assert {e.id for e in recalls[0].entities}.isdisjoint(e.id for e in recalls[1].entities)
    assert path.read_bytes() == before and account_digest(source) == original_digest


@pytest.mark.parametrize("kind", ["reported", "believed", "intended"])
def test_source_attributed_content_cannot_be_promoted_to_root(kind):
    request, packet = control()
    packet["rows"][0]["contexts"][0]["kind"] = kind
    good = compile_source(request, canonical(packet))
    assert good.resolutions[0].status == "admitted"
    context = next(c for c in good.account.contexts if c.kind == kind)
    assert context.side == "source" and good.account.events[0].context == context.id
    packet["rows"][0]["events"][0]["context"] = "narrated"
    bad = compile_source(request, canonical(packet))
    assert bad.resolutions[0].status == "rejected"
    assert "context differs" in bad.resolutions[0].reason
    assert not bad.account.events and len(bad.account.contexts) == 1


def test_nested_source_scope_requires_deepest_context():
    request, packet = control()
    row = packet["rows"][0]
    text = request.document.text
    row["contexts"].append(
        {
            "key": "inner",
            "kind": "reported",
            "parent": "outer",
            "cue": quote(text, 6, 8),
            "scope": quote(text, 9, 17),
        }
    )
    row["events"][0]["context"] = "inner"
    good = compile_source(request, canonical(packet))
    assert good.resolutions[0].status == "admitted"
    assert good.account.events[0].context == good.resolutions[0].accepted["inner"]
    row["events"][0]["context"] = "outer"
    assert compile_source(request, canonical(packet)).resolutions[0].status == "rejected"


@pytest.mark.parametrize(
    "change",
    [
        "false-quote",
        "foreign-row-evidence",
        "foreign-context",
        "foreign-entity",
        "duplicate-role",
        "duplicate-claim",
        "duplicate-key",
        "detail",
        "cross-scope",
        "cyclic-scope",
    ],
)
def test_invalid_source_claim_rejects_only_its_own_row(change):
    request = example_source_request()
    packet = json.loads(example_source_proposal())
    row, other = packet["rows"]
    if change == "false-quote":
        row["events"][0]["predicate"]["text"] = "AA"
    elif change == "foreign-row-evidence":
        row["entities"][0]["mention"] = other["entities"][0]["mention"]
    elif change == "foreign-context":
        row["events"][0]["context"] = "unknown"
    elif change == "foreign-entity":
        row["events"][0]["participants"][0]["entity"] = "unknown"
    elif change == "duplicate-role":
        row["events"][0]["participants"] *= 2
    elif change == "duplicate-claim":
        duplicate = dict(row["events"][0], key="other")
        row["events"].append(duplicate)
    elif change == "duplicate-key":
        row["events"][0]["key"] = "actor"
    elif change == "detail":
        row["details"] = []
    else:
        row["contexts"] = [
            {
                "key": "c",
                "kind": "reported",
                "parent": "c" if change == "cyclic-scope" else "narrated",
                "cue": row["entities"][0]["mention"],
                "scope": row["events"][0]["predicate"],
            }
        ]
    result = compile_source(request, canonical(packet))
    assert [r.status for r in result.resolutions] == ["rejected", "admitted"]
    assert [e.predicate for e in result.account.events] == ["watches"]
    assert [e.label for e in result.account.entities] == ["guard"]
    assert len(result.account.units) == 2


def test_row_accounting_distinguishes_unsupported_failed_missing_partial_and_malformed():
    text = "AA BB CC DD EE FF"
    rows = tuple(SourceRow(key=str(n), span=Span(start=3 * n, end=3 * n + 2)) for n in range(6))
    request = prepare_source("accounting", text, rows)
    packet = {
        "schema_version": "storymodel.source-proposal/v1",
        "rows": [
            {"row": "0", "status": "unsupported", "reason": "outside declared extraction profile"},
            {"row": "1", "status": "failed", "reason": "provider failed"},
            {
                "row": "2",
                "status": "completed",
                "unresolved": [
                    {
                        "key": "a",
                        "kind": "event",
                        "reason": "ambiguous",
                        "evidence": quote(text, 6, 8),
                    }
                ],
            },
            {"row": "3", "status": "completed", "unexpected": True},
            {"row": "5", "status": "search-limited", "reason": "budget exhausted"},
        ],
    }
    result = compile_source(request, canonical(packet))
    assert [r.status for r in result.resolutions] == [
        "unsupported",
        "failed",
        "partial",
        "rejected",
        "not-attempted",
        "partial",
    ]
    assert len(result.account.units) == 6 and not result.account.events
    assert all(r.reason and not r.accepted for r in result.resolutions)
    assert result.resolutions[2].unresolved[0].key == "a"


@pytest.mark.parametrize(
    "row",
    [
        {"row": "row", "status": "completed"},
        {"row": "row", "status": "unsupported"},
        {
            "row": "row",
            "status": "unsupported",
            "reason": "out of profile",
            "entities": [{"key": "a", "mention": quote("AA", 0, 2)}],
        },
    ],
)
def test_empty_success_and_unsupported_with_claims_refuse(row):
    request, _ = control()
    result = compile_source(
        request, canonical({"schema_version": "storymodel.source-proposal/v1", "rows": [row]})
    )
    assert result.resolutions[0].status == "rejected"


@pytest.mark.parametrize(
    "change", ["duplicate", "foreign", "boolean", "duplicate-json", "nonfinite"]
)
def test_ambiguous_source_reply_framing_refuses(change):
    request, packet = control()
    if change == "duplicate":
        packet["rows"] *= 2
    elif change in ("foreign", "boolean"):
        packet["rows"][0]["row"] = "foreign" if change == "foreign" else True
    raw = canonical(packet)
    if change == "duplicate-json":
        raw = raw.replace('"row":"row"', '"row":"row","row":"row"')
    elif change == "nonfinite":
        raw = raw.replace('"row":"row"', '"row":NaN')
    with pytest.raises(ValueError):
        compile_source(request, raw)


def test_row_input_boundary_refuses_overlap_foreign_side_and_forged_document():
    request = example_source_request()
    for rows in (
        (request.rows[1], request.rows[0]),
        (request.rows[0], request.rows[0]),
        (SourceRow(key="outside", span=Span(start=0, end=1000)),),
    ):
        with pytest.raises(ValueError):
            validate_source_request(request.model_copy(update={"rows": rows}))
    for document in (
        Document.create("recall", "recall", request.document.text),
        request.document.model_copy(update={"text": "AA"}),
    ):
        with pytest.raises(ValueError):
            compile_source(
                request.model_copy(update={"document": document}), example_source_proposal()
            )


def test_unicode_source_quotes_use_utf16_and_surrogate_splits_refuse():
    request = prepare_source(
        "unicode", "AA 😀 BB", (SourceRow(key="x", span=Span(start=0, end=8)),)
    )
    q = {"span": {"start": 3, "end": 5}, "text": "😀"}
    packet = {
        "schema_version": "storymodel.source-proposal/v1",
        "rows": [
            {
                "row": "x",
                "status": "completed",
                "events": [{"key": "e", "context": "narrated", "evidence": q, "predicate": q}],
            }
        ],
    }
    assert compile_source(request, canonical(packet)).account.events[0].predicate == "😀"
    q["span"]["end"] = 4
    bad = compile_source(request, canonical(packet))
    assert bad.resolutions[0].status == "rejected" and "surrogate" in bad.resolutions[0].reason
    with pytest.raises(ValueError, match="surrogate"):
        prepare_source("split-row", "😀", (SourceRow(key="x", span=Span(start=0, end=1)),))


def test_receipt_verification_detects_edited_outcomes_dropped_rows_and_other_input():
    request = example_source_request()
    raw = example_source_proposal()
    result = compile_source(request, raw)
    for changed in (
        result.model_copy(update={"resolutions": result.resolutions[:1]}),
        result.model_copy(update={"response_sha256": "0" * 64}),
        result.model_copy(update={"account": result.account.model_copy(update={"events": ()})}),
    ):
        with pytest.raises(ValueError):
            verify_source(request, raw, changed)
    with pytest.raises(ValueError, match="differs"):
        verify_source(request, raw + "\n", result)
