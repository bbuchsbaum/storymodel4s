"""Raw text and native source data flow through the existing checked replay path."""

import hashlib
import json
import subprocess
import sys
from pathlib import Path

import pytest

from storymodel.artifacts import publish_json
from storymodel.intake_workflow import (
    PreparedTimeStudy,
    prepare_time_study,
    prepare_time_study_files,
)
from storymodel.source_intake import NativeSource, compile_source
from storymodel.study_workflow import (
    ImportedReplies,
    import_time_replies,
    read_request,
    replay_time_study,
    request_digest,
)

PILOT = Path(__file__).resolve().parents[1]
FIXTURES = PILOT / "tests/fixtures"


def inputs():
    supplied = read_request(FIXTURES / "time-study-request.json")
    raw = "\n\n".join(u.text for u in supplied.recall.units).encode("utf-8")
    native = NativeSource.model_validate_json((FIXTURES / "native-time-source.json").read_bytes())
    return supplied, native, raw


def test_raw_intake_to_replay_matches_all_scala_interpretations(tmp_path, monkeypatch):
    supplied, native, raw = inputs()
    prepared = prepare_time_study(native, supplied.recall.key, raw, export_permission="allowed")
    assert prepared.request.source == supplied.source
    assert tuple(u.text for u in prepared.request.recall.units) == tuple(
        u.text for u in supplied.recall.units
    )
    for unit in prepared.recall.units:
        assert unit.support.slice(prepared.recall.document.text) == unit.text
    replies = ImportedReplies.model_validate_json(
        (FIXTURES / "time-study-replies.json").read_bytes()
    )
    # Explicitly reconstruct a synthetic response for this new input/prompt profile.
    replies = replies.model_copy(update={"request_sha256": request_digest(prepared.request)})
    root = tmp_path / "recording"
    import_time_replies(prepared.request, replies, root)
    monkeypatch.setattr(
        "socket.socket", lambda *a, **kw: pytest.fail("network during intake/replay")
    )
    result = replay_time_study(prepared.request, root)
    golden = json.loads(
        (
            PILOT.parent
            / "pipeline/src/test/resources/agentmapping/synthetic-study-time.bundle.json"
        ).read_text()
    )
    assert [
        {k: r[k] for k in ("u", "processing", "narrative", "referent", "extras")}
        for r in result.rows
    ] == [
        {k: r[k] for k in ("u", "processing", "narrative", "referent", "extras")}
        for r in golden["units"]
    ]
    assert [r["row"] for r in result.rows] == [1, 2, 3, 4, 5, 6]


def test_prepared_request_read_recomputes_dependencies_and_old_profile_is_unchanged(tmp_path):
    supplied, native, raw = inputs()
    prepared = prepare_time_study(native, supplied.recall.key, raw)
    path = tmp_path / "prepared.json"
    publish_json(path, prepared.model_dump(mode="json"))
    assert read_request(path) == prepared.request
    replies = ImportedReplies.model_validate_json(
        (FIXTURES / "time-study-replies.json").read_bytes()
    )
    assert request_digest(supplied) == replies.request_sha256
    bad = json.loads(path.read_text())
    bad["recall"]["units"][0]["text"] += "A"
    path.write_text(json.dumps(bad))
    with pytest.raises(ValueError):
        read_request(path)


def test_raw_change_that_canonicalizes_identically_cannot_reuse_recordings(tmp_path):
    supplied, native, raw = inputs()
    a = prepare_time_study(native, supplied.recall.key, raw)
    b = prepare_time_study(native, supplied.recall.key, raw.replace(b"\n", b"\r\n"))
    assert a.recall.document.text == b.recall.document.text
    assert a.recall.raw_sha256 != b.recall.raw_sha256
    assert request_digest(a.request) != request_digest(b.request)
    replies = ImportedReplies(
        origin="synthetic empty chunk",
        request_sha256=request_digest(a.request),
        replies=({"chunk": 0, "attempt": 0, "text": '{"units":[]}'},),
    )
    import_time_replies(a.request, replies, tmp_path / "recording")
    with pytest.raises(ValueError, match="another request"):
        replay_time_study(b.request, tmp_path / "recording")


def test_source_compilation_is_independent_of_recall_and_prepared_copies_refuse():
    supplied, native, raw = inputs()
    a = prepare_time_study(native, supplied.recall.key, raw)
    b = prepare_time_study(native, "other-session", b"AB CD EF.")
    assert a.request.source == b.request.source == compile_source(native)
    assert (
        a.request.admission["canonical_native_source_sha256"]
        == b.request.admission["canonical_native_source_sha256"]
    )
    corrupt = a.model_copy(update={"recall": a.recall.model_copy(update={"raw_text": "AB"})})
    with pytest.raises(ValueError):
        _ = corrupt.request


def test_strict_file_intake_pins_and_utf8(tmp_path):
    _, _, raw = inputs()
    transcript = tmp_path / "recall.txt"
    transcript.write_bytes(raw)
    prepared = prepare_time_study_files(
        FIXTURES / "native-time-source.json",
        transcript,
        "test",
        expected_recall_sha256=hashlib.sha256(raw).hexdigest(),
    )
    assert len(prepared.recall.units) == 6
    with pytest.raises(ValueError, match="external SHA"):
        prepare_time_study_files(
            FIXTURES / "native-time-source.json",
            transcript,
            "test",
            expected_recall_sha256="0" * 64,
        )
    transcript.write_bytes(b"\xff")
    with pytest.raises(ValueError):
        prepare_time_study_files(FIXTURES / "native-time-source.json", transcript, "test")


def test_prepare_cli_and_demonstration_outside_checkout(tmp_path):
    example = subprocess.run(
        [sys.executable, str(PILOT / "examples/native_time_workflow.py"), str(tmp_path / "demo")],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert example.returncode == 0, example.stderr
    out = tmp_path / "cli-prepared.json"
    command = [
        sys.executable,
        "-m",
        "storymodel.cli",
        "prepare-time-study",
        str(tmp_path / "demo/source.json"),
        str(tmp_path / "demo/recall.txt"),
        "--recall-key",
        "p1_s1",
        "--allow-export",
        "--output",
        str(out),
    ]
    prepared = subprocess.run(command, cwd=tmp_path, capture_output=True, text=True)
    assert prepared.returncode == 0, prepared.stderr
    assert json.loads(out.read_text()) == json.loads((tmp_path / "demo/prepared.json").read_text())
    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "storymodel.cli",
            "replay-time-study",
            str(out),
            str(tmp_path / "demo/recording"),
        ],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == json.loads((tmp_path / "demo/result.json").read_text())


def test_intake_schema_is_current():
    expected = {
        "native_source": NativeSource.model_json_schema(),
        "prepared_study": PreparedTimeStudy.model_json_schema(),
    }
    assert json.loads((PILOT / "schemas/intake.json").read_text()) == expected
