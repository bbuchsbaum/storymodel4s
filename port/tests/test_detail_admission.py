"""Recorded rich detail admission preserves explicit recall processing outcomes."""

import pytest

from storymodel.detail_admission import (
    admit_details,
    detail_request_digest,
    prepare_details,
    verify_details,
)
from storymodel.model import Span
from storymodel.rich_construction import compile_rich_construction, prepare_rich_construction
from storymodel.serialization import canonical
from storymodel.source import SourceRow


def account():
    request = prepare_rich_construction(
        "recall", "recall", "AA BB", (SourceRow(key="row", span=Span(start=0, end=5)),)
    )
    raw = canonical(
        {
            "schema_version": "storymodel.rich-construction-proposal/v1",
            "rows": [
                {
                    "row": "row",
                    "status": "completed",
                    "states": [
                        {
                            "key": "state",
                            "context": "narrated",
                            "evidence": {"span": {"start": 0, "end": 5}, "text": "AA BB"},
                            "predicate": {"span": {"start": 0, "end": 2}, "text": "AA"},
                        }
                    ],
                }
            ],
        }
    )
    return compile_rich_construction(request, raw).account


def multi_account():
    request = prepare_rich_construction(
        "multi",
        "recall",
        "AA BB CC DD",
        tuple(
            SourceRow(key=key, span=Span(start=start, end=start + 2))
            for key, start in zip(("a", "b", "c", "d"), (0, 3, 6, 9), strict=True)
        ),
    )
    raw = canonical(
        {
            "schema_version": "storymodel.rich-construction-proposal/v1",
            "rows": [
                {
                    "row": "a",
                    "status": "completed",
                    "entities": [
                        {"key": "entity", "mention": {"span": {"start": 0, "end": 1}, "text": "A"}}
                    ],
                    "states": [
                        {
                            "key": "state",
                            "context": "narrated",
                            "evidence": {"span": {"start": 0, "end": 1}, "text": "A"},
                            "predicate": {"span": {"start": 0, "end": 1}, "text": "A"},
                        }
                    ],
                    "circumstances": [
                        {
                            "key": "when",
                            "situation": "state",
                            "kind": "time",
                            "evidence": {"span": {"start": 0, "end": 1}, "text": "A"},
                        }
                    ],
                },
                *(
                    {"row": key, "status": "unsupported", "reason": "control"}
                    for key in ("b", "c", "d")
                ),
            ],
        }
    )
    return compile_rich_construction(request, raw).account


def proposal(request, unit, *, status="completed", details=(), reason="recorded control"):
    return canonical(
        {
            "schema_version": "storymodel.detail-proposal/v1",
            "request_sha256": detail_request_digest(request),
            "units": [{"unit": unit, "status": status, "reason": reason, "details": details}],
        }
    )


def test_admits_state_linked_discontinuous_detail_with_explicit_address_and_discourse():
    value = account()
    unit = value.units[0]
    request = prepare_details(value, (unit.id,))
    raw = proposal(
        request,
        unit.id,
        details=(
            {
                "key": "d",
                "kind": "event",
                "address": "target",
                "discourse": "content",
                "situation": value.states[0].id,
                "quotes": [
                    {"span": {"start": 0, "end": 2}, "text": "AA"},
                    {"span": {"start": 3, "end": 5}, "text": "BB"},
                ],
            },
        ),
    )
    result = admit_details(request, raw)
    detail = result.account.details[0]
    assert result.receipts[0].status == "admitted"
    assert (
        detail.address == "target"
        and detail.discourse == "content"
        and detail.event == value.states[0].id
    )
    assert [(span.start, span.end) for span in detail.support.spans] == [(0, 2), (3, 5)]
    assert detail.content == "AA … BB"
    assert verify_details(request, raw, result) == result


def test_quote_partition_does_not_create_new_occurrence_or_double_count():
    value = account()
    unit = value.units[0]
    request = prepare_details(value, (unit.id,))
    whole = {
        "key": "whole",
        "kind": "attribute",
        "address": "target",
        "discourse": "content",
        "situation": value.states[0].id,
        "quotes": [{"span": {"start": 0, "end": 5}, "text": "AA BB"}],
    }
    split = {
        **whole,
        "key": "split",
        "quotes": [
            {"span": {"start": 0, "end": 3}, "text": "AA "},
            {"span": {"start": 3, "end": 5}, "text": "BB"},
        ],
    }
    original = admit_details(request, proposal(request, unit.id, details=(whole,)))
    partitioned = admit_details(request, proposal(request, unit.id, details=(split,)))
    assert original.account.details[0] == partitioned.account.details[0]
    assert partitioned.account.details[0].support.spans == (Span(start=0, end=5),)
    duplicate = admit_details(request, proposal(request, unit.id, details=(whole, split)))
    assert duplicate.receipts[0].status == "rejected"
    assert "duplicate detail" in duplicate.receipts[0].reason
    assert not duplicate.account.details and duplicate.account.inventories[0].status == "failed"
    gapped = {
        **whole,
        "key": "gapped",
        "quotes": [
            {"span": {"start": 0, "end": 2}, "text": "AA"},
            {"span": {"start": 3, "end": 5}, "text": "BB"},
        ],
    }
    distinct = admit_details(request, proposal(request, unit.id, details=(gapped,)))
    assert distinct.account.details[0].id != original.account.details[0].id
    assert distinct.account.details[0].content == "AA … BB"
    assert distinct.account.details[0].support.spans == (Span(start=0, end=2), Span(start=3, end=5))


def test_search_limited_details_admit_atomically_and_reject_reversed_quotes():
    value = account()
    unit = value.units[0]
    request = prepare_details(value, (unit.id,))
    draft = {
        "key": "d",
        "kind": "event",
        "address": "unresolved",
        "discourse": "content",
        "quotes": [
            {"span": {"start": 0, "end": 2}, "text": "AA"},
            {"span": {"start": 3, "end": 5}, "text": "BB"},
        ],
    }
    result = admit_details(
        request,
        proposal(request, unit.id, status="search-limited", details=(draft,), reason="bounded"),
    )
    assert result.receipts[0].status == "partial"
    assert result.account.inventories[0].status == "search-limited"
    assert result.account.details[0].content == "AA … BB"
    draft["quotes"] = list(reversed(draft["quotes"]))
    refused = admit_details(request, proposal(request, unit.id, details=(draft,)))
    assert refused.receipts[0].status == "rejected"


@pytest.mark.parametrize(
    "status, expected",
    [
        ("completed", "admitted"),
        ("not-attempted", "not-attempted"),
        ("failed", "failed"),
        ("search-limited", "partial"),
    ],
)
def test_explicit_zero_and_processing_statuses_remain_distinct(status, expected):
    value = account()
    request = prepare_details(value, (value.units[0].id,))
    result = admit_details(request, proposal(request, value.units[0].id, status=status))
    assert result.receipts[0].status == expected
    assert not result.account.details


@pytest.mark.parametrize("change", ["quote", "outside-unit", "foreign-situation"])
def test_bad_unit_isolated_and_existing_detail_requests_refuse(change):
    value = account()
    request = prepare_details(value, (value.units[0].id,))
    draft = {
        "key": "d",
        "kind": "event",
        "address": "unresolved",
        "discourse": "content",
        "quotes": [{"span": {"start": 0, "end": 2}, "text": "AA"}],
    }
    if change == "quote":
        draft["quotes"][0]["text"] = "bad"
    elif change == "outside-unit":
        draft["quotes"][0]["span"] = {"start": 0, "end": 6}
    else:
        draft["situation"] = "foreign"
    result = admit_details(request, proposal(request, value.units[0].id, details=(draft,)))
    assert result.receipts[0].status == "rejected" and not result.account.details
    valid = admit_details(
        request,
        proposal(
            request,
            value.units[0].id,
            details=(
                {
                    "key": "valid",
                    "kind": "event",
                    "address": "unresolved",
                    "discourse": "content",
                    "quotes": [{"span": {"start": 0, "end": 2}, "text": "AA"}],
                },
            ),
        ),
    )
    with pytest.raises(ValueError):
        prepare_details(valid.account, (value.units[0].id,))


def test_multiunit_isolation_missing_unselected_and_receipt_boundaries():
    value = multi_account()
    first, second, third, fourth = value.units
    original = (value.states, value.circumstances, value.contexts, value.entities, value.bindings)
    request = prepare_details(value, (first.id, second.id, third.id))
    valid = {
        "key": "ok",
        "kind": "event",
        "address": "target",
        "discourse": "content",
        "quotes": [{"span": {"start": 0, "end": 1}, "text": "A"}],
    }
    bad = {
        "key": "bad",
        "kind": "event",
        "address": "unresolved",
        "discourse": "content",
        "quotes": [{"span": {"start": 3, "end": 5}, "text": "bad"}],
    }
    raw = canonical(
        {
            "schema_version": "storymodel.detail-proposal/v1",
            "request_sha256": detail_request_digest(request),
            "units": [
                {"unit": first.id, "status": "completed", "reason": "ok", "details": [valid]},
                {"unit": second.id, "status": "completed", "reason": "bad", "details": [bad]},
            ],
        }
    )
    result = admit_details(request, raw)
    assert [item.status for item in result.receipts] == ["admitted", "rejected", "not-attempted"]
    statuses = {item.unit: item.status for item in result.account.inventories}
    assert [statuses[unit.id] for unit in (first, second, third, fourth)] == [
        "completed",
        "failed",
        "not-attempted",
        "not-attempted",
    ]
    assert (
        result.account.states,
        result.account.circumstances,
        result.account.contexts,
        result.account.entities,
        result.account.bindings,
    ) == original
    assert len(result.account.details) == 1


def test_completed_zero_locks_selected_unit_and_strict_envelopes_refuse():
    value = account()
    request = prepare_details(value, (value.units[0].id,))
    raw = proposal(request, value.units[0].id, details=())
    result = admit_details(request, raw)
    assert result.receipts[0].status == "admitted" and not result.account.details
    assert result.account.inventories[0].status == "completed"
    with pytest.raises(ValueError, match="completed inventories"):
        prepare_details(result.account, (value.units[0].id,))
    duplicate = canonical(
        {
            "schema_version": "storymodel.detail-proposal/v1",
            "request_sha256": detail_request_digest(request),
            "units": [
                {"unit": value.units[0].id, "status": "completed", "reason": "one"},
                {"unit": value.units[0].id, "status": "completed", "reason": "two"},
            ],
        }
    )
    with pytest.raises(ValueError, match="duplicate or foreign"):
        admit_details(request, duplicate)
    foreign = canonical(
        {
            "schema_version": "storymodel.detail-proposal/v1",
            "request_sha256": detail_request_digest(request),
            "units": [{"unit": "foreign", "status": "completed", "reason": "x"}],
        }
    )
    with pytest.raises(ValueError, match="duplicate or foreign"):
        admit_details(request, foreign)


def test_detail_boundaries_and_forged_request_result_refuse():
    value = multi_account()
    first = value.units[0]
    request = prepare_details(value, (first.id,))
    outside_unit = {
        "key": "outside",
        "kind": "event",
        "address": "unresolved",
        "discourse": "content",
        "quotes": [{"span": {"start": 3, "end": 5}, "text": "BB"}],
    }
    outside_state = {
        "key": "state-outside",
        "kind": "event",
        "address": "unresolved",
        "discourse": "content",
        "situation": value.states[0].id,
        "quotes": [{"span": {"start": 1, "end": 2}, "text": "A"}],
    }
    for draft in (outside_unit, outside_state):
        result = admit_details(request, proposal(request, first.id, details=(draft,)))
        assert result.receipts[0].status == "rejected"
    with pytest.raises(ValueError):
        admit_details(request.model_copy(update={"units": ()}), "{}")
    accepted = admit_details(request, proposal(request, first.id, details=()))
    with pytest.raises(ValueError, match="differs from recorded"):
        verify_details(
            request,
            proposal(request, first.id, details=()),
            accepted.model_copy(update={"response_sha256": "0" * 64}),
        )
