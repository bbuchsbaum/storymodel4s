import json

import pytest

from storymodel.prepared_construction import (
    PreparedConstructionRecording,
    import_prepared_construction,
    prepare_construction,
    replay_prepared_construction,
)
from storymodel.serialization import canonical, digest
from storymodel.source_preparation import prepare_source_utf8, validate_prepared_source


def source():
    return {
        "schema_version": "storymodel.source-annotations/v1",
        "parts": [
            {
                "key": "a",
                "ordinal": 1,
                "clock": {
                    "key": "a",
                    "start_tick": "0",
                    "end_tick": "100",
                    "seconds_per_tick": "1/2",
                },
            },
            {
                "key": "b",
                "ordinal": 2,
                "clock": {
                    "key": "b",
                    "start_tick": "0",
                    "end_tick": "100",
                    "seconds_per_tick": "2",
                },
            },
        ],
        "rows": [
            {"key": "b2", "part": "b", "ordinal": 2, "text": "DD", "native_start_tick": "2"},
            {
                "key": "a2",
                "part": "a",
                "ordinal": 2,
                "text": "BB 😀",
                "native_start_tick": "80",
                "native_end_tick": "80",
            },
            {"key": "b1", "part": "b", "ordinal": 1, "text": "CC", "native_start_tick": "1"},
            {"key": "a1", "part": "a", "ordinal": 1, "text": "AA", "native_start_tick": "90"},
        ],
    }


def test_declared_order_overrides_both_clock_resets_and_within_part_times():
    p = prepare_source_utf8("source", json.dumps(source()).encode())
    assert [r.key for r in p.rows] == ["a1", "a2", "b1", "b2"]
    assert p.document.text == "AA\nBB 😀\nCC\nDD"
    assert [(r.span.start, r.span.end) for r in p.rows] == [(0, 3), (3, 9), (9, 12), (12, 14)]
    assert p.annotations.rows[0].native_end_tick is None
    assert p.annotations.rows[1].native_end_tick == "80"


def test_changed_raw_metadata_cannot_keep_stale_parsed_receipt():
    from hashlib import sha256

    raw = json.dumps(source()).encode()
    p = prepare_source_utf8("source", raw)
    changed = source()
    changed["rows"][0]["scene"] = "different-scene"
    text = json.dumps(changed)
    stale = p.model_copy(update={"raw_text": text, "raw_sha256": sha256(text.encode()).hexdigest()})
    with pytest.raises(ValueError, match="annotations differ"):
        validate_prepared_source(stale)
    q = prepare_source_utf8("source", text.encode())
    assert q.document == p.document and q.rows == p.rows
    assert q.annotations != p.annotations


def test_import_refuses_foreign_raw_before_root_created(tmp_path):
    a = prepare_construction("recall", "recall", b"AA BB\r\n", export_permission="allowed")
    b = prepare_construction("recall", "recall", b"AA BB\n", export_permission="allowed")
    assert a.request == b.request
    recording = PreparedConstructionRecording(
        prepared_sha256=digest(a.model_dump(mode="json")),
        origin="review",
        reply=canonical({"schema_version": "storymodel.rich-construction-proposal/v1", "rows": []}),
    )
    import_prepared_construction(a, recording, tmp_path / "positive")
    assert (
        replay_prepared_construction(a, tmp_path / "positive").prepared_sha256
        == recording.prepared_sha256
    )
    with pytest.raises(ValueError, match="another prepared input"):
        import_prepared_construction(b, recording, tmp_path / "foreign")
    assert not (tmp_path / "foreign").exists()
