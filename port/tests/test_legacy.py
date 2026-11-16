"""Compatibility tests against the committed synthetic Scala StudyBundle artifacts."""

from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

import pytest

from storymodel.legacy import LegacyBundleError, legacy_rows, legacy_summary, load_legacy

FIXTURES = Path(__file__).parents[2] / "pipeline/src/test/resources/agentmapping"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_bundle(tmp_path: Path, name: str, bundle: object) -> tuple[Path, str]:
    path = tmp_path / name
    path.write_text(json.dumps(bundle), encoding="utf-8")
    return path, digest(path)


def test_categorical_fixture_preserves_rows_extras_and_original_outcomes() -> None:
    path = FIXTURES / "synthetic-study.bundle.json"
    bundle = load_legacy(path, expected_sha256=digest(path))

    assert [row["referent"]["judgement"]["kind"] for row in legacy_rows(bundle)] == [
        "scene",
        "invalid-scene",
        "film",
        "none",
        "omitted",
        "unknown-scene",
    ]
    assert [row["narrative"]["judgement"]["kind"] for row in legacy_rows(bundle)] == [
        "scene",
        "scene",
        "unknown-film",
        "none",
        "omitted",
        "invalid",
    ]
    assert legacy_rows(bundle)[0]["extras"] == {"conf": 0.8}
    assert (
        "referent: film=1, invalid-scene=1, none=1, omitted=1, scene=1, unknown-scene=1"
        in legacy_summary(bundle)
    )


def test_timed_fixture_retains_time_outside_and_supported_protocol() -> None:
    path = FIXTURES / "synthetic-study-time.bundle.json"
    bundle = load_legacy(path, expected_sha256=digest(path))
    rows = legacy_rows(bundle)

    assert bundle["provenance"]["protocol"] == "strict-chunked-time/v1"
    assert rows[1]["referent"]["judgement"] == {"kind": "time-outside", "collection": 3}
    assert rows[0]["referent"]["stated"]["time"] == "0:10"


def test_caller_pin_refuses_bytes_changed_with_coordinated_row_and_record_edits(
    tmp_path: Path,
) -> None:
    source = FIXTURES / "synthetic-study.bundle.json"
    original_hash = digest(source)
    changed = json.loads(source.read_text())
    changed["units"][0]["referent"]["stated"]["scene"] = 4
    changed["units"][0]["referent"]["judgement"] = {"kind": "scene", "scene": 4, "collection": 3}
    receipt = next(
        item
        for item in changed["interpretations"]["referent"]["record"]["stage_assumption_receipts"]
        if item["stage"] == "Decision"
    )
    receipt["provenance"]["asserted"]["value"]["inputs"] = ["coordinated-but-untrusted"]
    path, _ = write_bundle(tmp_path, "coordinated.bundle.json", changed)

    with pytest.raises(LegacyBundleError, match="SHA-256 differs"):
        load_legacy(path, expected_sha256=original_hash)


def test_unknown_schema_and_malformed_payload_are_typed_refusals(tmp_path: Path) -> None:
    unknown, unknown_hash = write_bundle(
        tmp_path, "unknown.bundle.json", {"schema": "example/v999"}
    )
    malformed = tmp_path / "malformed.bundle.json"
    malformed.write_bytes(b"{not json")

    with pytest.raises(LegacyBundleError, match="unsupported legacy schema"):
        load_legacy(unknown, expected_sha256=unknown_hash)
    with pytest.raises(LegacyBundleError, match="malformed legacy JSON"):
        load_legacy(malformed, expected_sha256=digest(malformed))


def test_utf16_transcript_and_timed_inconsistencies_are_refused(tmp_path: Path) -> None:
    source = FIXTURES / "synthetic-study-time.bundle.json"
    timed = json.loads(source.read_text())
    timed["units"][0]["referent"]["stated"]["time"] = "0:19"
    bad_time, bad_time_hash = write_bundle(tmp_path, "bad-time.bundle.json", timed)

    with pytest.raises(LegacyBundleError, match="not what its film and time give"):
        load_legacy(bad_time, expected_sha256=bad_time_hash)

    utf16 = copy.deepcopy(json.loads(source.read_text()))
    utf16["provenance"]["segmentation"] = {
        "kind": "segmenter",
        "recipe": "recall-segmenter/v1",
        "artifactSha256": "0" * 64,
    }
    utf16["recall"] = {
        "recipe": "recall-segmenter/v1",
        "rawSha256": "0" * 64,
        "canonicalSha256": hashlib.sha256("😀".encode()).hexdigest(),
        "canonicalization": "story-source/v1",
        "offsetUnit": "utf-16-code-units",
        "canonicalText": "😀",
        "units": [
            {
                "u": index + 1,
                "unit": row["unit"],
                "text": "😀",
                "spans": [{"start": 0, "endExclusive": 1}],
            }
            for index, row in enumerate(utf16["units"])
        ],
    }
    for index, row in enumerate(utf16["units"], start=1):
        row["row"] = index
    # This artificial shape reaches the UTF-16 validator before its copied records can be checked.
    for field in ("narrative", "referent"):
        utf16["interpretations"][field]["record"]["inventory"] = {
            "transcript_checksum": utf16["recall"]["canonicalSha256"],
            "word_id_policy": {"input_artifact": "0" * 64},
            "units": [],
        }
    utf16_path, utf16_hash = write_bundle(tmp_path, "split-surrogate.bundle.json", utf16)
    with pytest.raises(LegacyBundleError, match="splits a surrogate pair"):
        load_legacy(utf16_path, expected_sha256=utf16_hash)
