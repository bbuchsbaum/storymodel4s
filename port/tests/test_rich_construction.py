"""Rich v4 construction preserves literal support and per-row refusal boundaries."""

import pytest

from storymodel.model import Span
from storymodel.rich_construction import (
    RichConstructionResult,
    compile_rich_construction,
    prepare_rich_construction,
    verify_rich_construction,
)
from storymodel.serialization import canonical
from storymodel.source import SourceRow


def quote(text, start, end):
    return {"span": {"start": start, "end": end}, "text": text[start:end]}


def request(side="source"):
    return prepare_rich_construction(
        "rich-control", side, "AA BB CC DD", (SourceRow(key="row", span=Span(start=0, end=11)),)
    )


def proposal(value, *, polarity="unknown", modality="unknown"):
    text = value.document.text
    return {
        "schema_version": "storymodel.rich-construction-proposal/v1",
        "rows": [
            {
                "row": "row",
                "status": "completed",
                "entities": [{"key": "place", "mention": quote(text, 3, 5)}],
                "states": [
                    {
                        "key": "condition",
                        "context": "narrated",
                        "evidence": quote(text, 0, 11),
                        "predicate": quote(text, 0, 2),
                        "participants": [
                            {"entity": "place", "role": "location", "evidence": quote(text, 3, 5)}
                        ],
                        "polarity": polarity,
                        "modality": modality,
                    }
                ],
                "circumstances": [
                    {
                        "key": "when",
                        "situation": "condition",
                        "kind": "time",
                        "evidence": quote(text, 6, 8),
                    },
                    {
                        "key": "how",
                        "situation": "condition",
                        "kind": "manner",
                        "evidence": quote(text, 9, 11),
                    },
                ],
            }
        ],
    }


def test_source_and_recall_rich_construction_preserve_states_locations_and_literal_circumstances():
    source = request()
    source_result = compile_rich_construction(source, canonical(proposal(source)))
    assert source_result.account.schema_version == "storymodel.account/v4"
    assert source_result.account.units[0].kind == "row"
    state = source_result.account.states[0]
    assert state.polarity == state.modality == "unknown"
    assert [(p.role, p.entity) for p in state.participants] == [
        ("location", source_result.account.entities[0].id)
    ]
    assert [(item.kind, item.label) for item in source_result.account.circumstances] == [
        ("time", "CC"),
        ("manner", "DD"),
    ]
    assert (
        verify_rich_construction(
            source,
            canonical(proposal(source)),
            RichConstructionResult.model_validate_json(source_result.model_dump_json()),
        )
        == source_result
    )

    recall = request("recall")
    recall_result = compile_rich_construction(recall, canonical(proposal(recall)))
    assert recall_result.account.units[0].kind == "recall"
    assert recall_result.account.inventories[0].status == "not-attempted"
    assert not recall_result.account.details


@pytest.mark.parametrize(
    "change", ["root-promotion", "wrong-quote", "foreign-situation", "duplicate-key", "bad-role"]
)
def test_invalid_rich_claim_rejects_the_row_without_inventing_graph(change):
    value = request()
    packet = proposal(value)
    row = packet["rows"][0]
    if change == "root-promotion":
        row["contexts"] = [
            {
                "key": "reported",
                "kind": "reported",
                "parent": "narrated",
                "cue": quote(value.document.text, 0, 2),
                "scope": quote(value.document.text, 3, 11),
            }
        ]
        row["states"][0]["evidence"] = quote(value.document.text, 6, 8)
        row["states"][0]["predicate"] = quote(value.document.text, 6, 8)
        row["states"][0]["context"] = "narrated"
        row["states"][0]["participants"] = []
        row["circumstances"] = []
    elif change == "wrong-quote":
        row["states"][0]["predicate"]["text"] = "wrong"
    elif change == "foreign-situation":
        row["circumstances"][0]["situation"] = "foreign"
    elif change == "duplicate-key":
        row["circumstances"][0]["key"] = "condition"
    else:
        row["states"][0]["participants"] *= 2
    result = compile_rich_construction(value, canonical(packet))
    assert result.resolutions[0].status == "rejected"
    if change == "root-promotion":
        assert "context differs" in (result.resolutions[0].reason or "")
    assert not result.account.entities and not result.account.events and not result.account.states


def test_reported_state_requires_its_deepest_declared_context():
    value = request()
    packet = proposal(value)
    row = packet["rows"][0]
    row["contexts"] = [
        {
            "key": "reported",
            "kind": "reported",
            "parent": "narrated",
            "cue": quote(value.document.text, 0, 2),
            "scope": quote(value.document.text, 3, 11),
        }
    ]
    row["states"][0]["evidence"] = quote(value.document.text, 6, 8)
    row["states"][0]["predicate"] = quote(value.document.text, 6, 8)
    row["states"][0]["context"] = "reported"
    row["states"][0]["participants"] = []
    row["circumstances"] = [row["circumstances"][0]]
    result = compile_rich_construction(value, canonical(packet))
    assert result.resolutions[0].status == "admitted"
    reported = next(context for context in result.account.contexts if context.kind == "reported")
    assert result.account.states[0].context == reported.id


def test_mixed_event_state_and_cross_row_rejection_preserve_prior_admission():
    value = request()
    packet = proposal(value)
    row = packet["rows"][0]
    row["events"] = [
        {
            "key": "action",
            "context": "narrated",
            "evidence": quote(value.document.text, 0, 11),
            "predicate": quote(value.document.text, 0, 2),
            "participants": row["states"][0]["participants"],
        }
    ]
    row["circumstances"][0]["situation"] = "action"
    result = compile_rich_construction(value, canonical(packet))
    assert result.resolutions[0].status == "admitted"
    assert len(result.account.events) == len(result.account.states) == 1
    circumstances = {item.kind: item.situation for item in result.account.circumstances}
    assert circumstances["time"] == result.account.events[0].id
    assert circumstances["manner"] == result.account.states[0].id

    two = prepare_rich_construction(
        "two-rows",
        "source",
        "AA BB CC DD EE FF",
        (
            SourceRow(key="one", span=Span(start=0, end=11)),
            SourceRow(key="two", span=Span(start=12, end=17)),
        ),
    )
    first = proposal(request())["rows"][0]
    first["row"] = "one"
    second = {
        "row": "two",
        "status": "completed",
        "states": [
            {
                "key": "bad",
                "context": "narrated",
                "evidence": quote(two.document.text, 12, 17),
                "predicate": {"span": {"start": 12, "end": 14}, "text": "wrong"},
            }
        ],
    }
    result = compile_rich_construction(
        two,
        canonical(
            {"schema_version": "storymodel.rich-construction-proposal/v1", "rows": [first, second]}
        ),
    )
    assert [item.status for item in result.resolutions] == ["admitted", "rejected"]
    assert len(result.account.states) == 1


def test_missing_row_and_foreign_framing_are_explicit_and_refuse_without_partial_selection():
    value = prepare_rich_construction(
        "two-rows",
        "source",
        "AA BB",
        (
            SourceRow(key="a", span=Span(start=0, end=2)),
            SourceRow(key="b", span=Span(start=3, end=5)),
        ),
    )
    result = compile_rich_construction(
        value, canonical({"schema_version": "storymodel.rich-construction-proposal/v1", "rows": []})
    )
    assert [item.status for item in result.resolutions] == ["not-attempted", "not-attempted"]
    packet = proposal(request())
    packet["rows"].append(dict(packet["rows"][0]))
    with pytest.raises(ValueError, match="duplicate, foreign"):
        compile_rich_construction(request(), canonical(packet))


def test_processing_accounting_forged_boundaries_and_non_bmp_utf16_quotes():
    value = prepare_rich_construction(
        "statuses",
        "source",
        "AA BB",
        (
            SourceRow(key="a", span=Span(start=0, end=2)),
            SourceRow(key="b", span=Span(start=3, end=5)),
        ),
    )
    result = compile_rich_construction(
        value,
        canonical(
            {
                "schema_version": "storymodel.rich-construction-proposal/v1",
                "rows": [
                    {"row": "a", "status": "unsupported", "reason": "outside control"},
                    {"row": "b", "status": "search-limited", "reason": "bounded search"},
                ],
            }
        ),
    )
    assert [item.status for item in result.resolutions] == ["unsupported", "partial"]
    with pytest.raises(ValueError):
        compile_rich_construction(value.model_copy(update={"rows": ()}), "{}")
    with pytest.raises(ValueError, match="differs from recorded"):
        verify_rich_construction(
            value,
            canonical(
                {
                    "schema_version": "storymodel.rich-construction-proposal/v1",
                    "rows": [
                        {"row": "a", "status": "unsupported", "reason": "outside control"},
                        {"row": "b", "status": "search-limited", "reason": "bounded search"},
                    ],
                }
            ),
            result.model_copy(update={"response_sha256": "0" * 64}),
        )

    non_bmp = prepare_rich_construction(
        "non-bmp", "source", "😀 AA", (SourceRow(key="row", span=Span(start=0, end=5)),)
    )
    raw = {
        "schema_version": "storymodel.rich-construction-proposal/v1",
        "rows": [
            {
                "row": "row",
                "status": "completed",
                "states": [
                    {
                        "key": "state",
                        "context": "narrated",
                        "evidence": {"span": {"start": 3, "end": 5}, "text": "AA"},
                        "predicate": {"span": {"start": 3, "end": 5}, "text": "AA"},
                    }
                ],
            }
        ],
    }
    assert compile_rich_construction(non_bmp, canonical(raw)).resolutions[0].status == "admitted"
