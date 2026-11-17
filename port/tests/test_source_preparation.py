"""Checks for exact, nonsemantic v1 source annotation intake."""

import hashlib
import json

import pytest

from storymodel.source_preparation import prepare_source_utf8, validate_prepared_source


def receipt() -> bytes:
    return json.dumps(
        {
            "schema_version": "storymodel.source-annotations/v1",
            "parts": [
                {"key": "later", "ordinal": 2},
                {
                    "key": "first",
                    "ordinal": 1,
                    "clock": {
                        "key": "first",
                        "start_tick": "10",
                        "end_tick": "30",
                        "seconds_per_tick": "1/2",
                    },
                },
            ],
            "rows": [
                {"key": "later-row", "part": "later", "ordinal": 1, "text": "z"},
                {
                    "key": "emoji",
                    "part": "first",
                    "ordinal": 2,
                    "text": "😀",
                    "native_start_tick": "20",
                    "native_end_tick": "20",
                    "scene": "scene-1",
                    "cast": ["a", "b"],
                    "location": "room",
                },
                {
                    "key": "first-row",
                    "part": "first",
                    "ordinal": 1,
                    "text": "alpha",
                    "native_start_tick": "10",
                },
            ],
        },
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode()


def test_preparation_preserves_raw_bytes_but_derives_declared_order_and_utf16_coverage():
    raw = receipt()
    prepared = prepare_source_utf8("source-edition", raw)
    assert prepared.raw_text.encode() == raw
    assert prepared.raw_sha256 == hashlib.sha256(raw).hexdigest()
    assert prepared.document.text == "alpha\n😀\nz"
    assert [row.key for row in prepared.rows] == ["first-row", "emoji", "later-row"]
    assert [row.span.model_dump() for row in prepared.rows] == [
        {"start": 0, "end": 6},
        {"start": 6, "end": 9},
        {"start": 9, "end": 10},
    ]
    assert [row.span.slice(prepared.document.text) for row in prepared.rows] == [
        "alpha\n",
        "😀\n",
        "z",
    ]
    assert prepared.annotations.rows[0].key == "later-row"  # Raw JSON order remains receipt data.
    assert prepared.annotations.rows[1].native_end_tick == "20"  # Point != unknown duration.
    assert prepared.annotations.rows[2].native_end_tick is None
    assert validate_prepared_source(prepared) == prepared


@pytest.mark.parametrize(
    "replace, message",
    [
        (b'{"schema_version":"storymodel.source-annotations/v1","parts":[],"rows":[]}', "parts"),
        (
            b'{"schema_version":"storymodel.source-annotations/v1",'
            b'"parts":[],"parts":[],"rows":[]}',
            "duplicate JSON",
        ),
        (b"\xff", "UTF-8"),
        (
            b'{"schema_version":"storymodel.source-annotations/v1","parts":['
            b'{"key":"p","ordinal":1}],"rows":[{"key":"r","part":"p",'
            b'"ordinal":1,"text":"x","native_end_tick":"2"}]}',
            "end requires",
        ),
        (
            b'{"schema_version":"storymodel.source-annotations/v1","parts":['
            b'{"key":"p","ordinal":1}],"rows":[{"key":"r","part":"p",'
            b'"ordinal":1,"text":"x","native_start_tick":"2"}]}',
            "requires its part clock",
        ),
    ],
)
def test_preparation_refuses_invalid_or_nonexact_receipts(replace, message):
    with pytest.raises(ValueError, match=message):
        prepare_source_utf8("source", replace)


def test_validation_refuses_copied_or_unknown_derived_values():
    prepared = prepare_source_utf8("source", receipt())
    with pytest.raises(ValueError, match="document or rows"):
        validate_prepared_source(prepared.model_copy(update={"rows": prepared.rows[1:]}))
    unknown = json.loads(receipt())
    unknown["unknown"] = "value"
    with pytest.raises(ValueError, match="extra"):
        prepare_source_utf8("source", json.dumps(unknown).encode())
    with pytest.raises(ValueError):
        validate_prepared_source(
            prepared.model_copy(
                update={"annotations": prepared.annotations.model_copy(update={"rows": ()})}
            )
        )


def test_part_clock_reset_does_not_reorder_rows_or_imply_unknown_duration_is_a_point():
    value = {
        "schema_version": "storymodel.source-annotations/v1",
        "parts": [
            {
                "key": key,
                "ordinal": ordinal,
                "clock": {
                    "key": key,
                    "start_tick": "0",
                    "end_tick": "100",
                    "seconds_per_tick": "1/2",
                },
            }
            for key, ordinal in (("a", 1), ("b", 2))
        ],
        "rows": [
            {"key": "b1", "part": "b", "ordinal": 1, "text": "CC DD", "native_start_tick": "0"},
            {
                "key": "a1",
                "part": "a",
                "ordinal": 1,
                "text": "AA BB",
                "native_start_tick": "90",
                "native_end_tick": "90",
            },
        ],
    }
    prepared = prepare_source_utf8("reset", json.dumps(value).encode())
    assert [r.key for r in prepared.rows] == ["a1", "b1"]
    assert prepared.document.text == "AA BB\nCC DD"
    assert prepared.annotations.rows[0].native_end_tick is None
    assert prepared.annotations.rows[1].native_end_tick == "90"


@pytest.mark.parametrize("fault", ["part-key", "part-ordinal", "row-key", "row-ordinal", "bounds"])
def test_ambiguous_ordinals_and_out_of_bounds_native_times_are_refused(fault):
    value = json.loads(receipt())
    if fault == "part-key":
        value["parts"][0]["key"] = "first"
    elif fault == "part-ordinal":
        value["parts"][0]["ordinal"] = 1
    elif fault == "row-key":
        value["rows"][0]["key"] = "first-row"
    elif fault == "row-ordinal":
        value["rows"][1]["ordinal"] = 1
    else:
        value["rows"][1]["native_end_tick"] = "31"
    with pytest.raises(ValueError):
        prepare_source_utf8("source", json.dumps(value).encode())
