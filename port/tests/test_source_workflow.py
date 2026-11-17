"""Recorded source compilation stays source-bound, offline, and replayable."""

import json
import subprocess
import sys
from hashlib import sha256
from pathlib import Path

import pytest

from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.execution import RecordedRun, TransportFailure
from storymodel.model import Span, canonical
from storymodel.source import (
    SourceProposal,
    SourceRequest,
    SourceResult,
    SourceRow,
    prepare_source,
    source_request_digest,
)
from storymodel.source_workflow import (
    SourceRecording,
    _profile,
    import_source,
    read_source_request,
    replay_source,
    source_prompt,
)

PILOT = Path(__file__).resolve().parents[1]


def request(*, export_permission="allowed"):
    return prepare_source(
        "independent-observations",
        "AA BB\nCC DD",
        (
            SourceRow(key="first", span=Span(start=0, end=5)),
            SourceRow(key="second", span=Span(start=6, end=11)),
        ),
        export_permission=export_permission,
    )


def reply(value):
    return canonical(
        {
            "schema_version": "storymodel.source-proposal/v1",
            "rows": [
                {"row": row.key, "status": "unsupported", "reason": "synthetic input"}
                for row in value.rows
            ],
        }
    )


def recording(value, raw=None):
    return SourceRecording(
        request_sha256=source_request_digest(value),
        origin="synthetic diagnostic",
        reply=reply(value) if raw is None else raw,
    )


def test_import_and_replay_bind_exact_raw_reply_offline(tmp_path, monkeypatch):
    value = request()
    monkeypatch.setattr("socket.socket", lambda *a, **k: pytest.fail("unexpected network"))
    root = tmp_path / "recording"
    import_source(value, recording(value), root)
    result = replay_source(value, root)
    assert tuple(item.status for item in result.resolutions) == ("unsupported", "unsupported")
    assert result.response_sha256 == sha256(reply(value).encode()).hexdigest()
    assert json.loads(source_prompt(value))["source"] == value.model_dump(mode="json")
    raw_import = (root / "import.json").read_bytes()
    assert (
        replay_source(value, root, expected_import_sha256=sha256(raw_import).hexdigest()) == result
    )
    with pytest.raises(ValueError, match="external SHA"):
        replay_source(value, root, expected_import_sha256="0" * 64)
    imported = json.loads(raw_import)
    imported["reply"] = "{}"
    (root / "import.json").write_text(canonical(imported))
    with pytest.raises(ValueError, match="differs from imported"):
        replay_source(value, root)


def test_malformed_foreign_and_interrupted_recordings_refuse(tmp_path):
    value = request()
    root = tmp_path / "recording"
    import_source(value, recording(value, "not JSON"), root)
    assert json.loads((root / "import.json").read_text())["reply"] == "not JSON"
    with pytest.raises(ValueError):
        replay_source(value, root)
    with pytest.raises(FileExistsError):
        import_source(value, recording(value), root)

    foreign = request()
    changed = prepare_source(
        "changed-observations",
        foreign.document.text,
        foreign.rows,
        export_permission="allowed",
    )
    foreign_root = tmp_path / "foreign"
    with pytest.raises(ValueError, match="another request"):
        import_source(changed, recording(foreign), foreign_root)
    assert not foreign_root.exists()

    interrupted_root = tmp_path / "interrupted"
    with pytest.raises(TransportFailure):
        RecordedRun(interrupted_root, _profile(value)).run(
            (source_prompt(value),),
            transport=lambda _: (_ for _ in ()).throw(RuntimeError("interrupted")),
            max_new_calls=1,
        )
    with pytest.raises(FileExistsError):
        import_source(value, recording(value), interrupted_root)
    with pytest.raises(FileNotFoundError):
        replay_source(value, interrupted_root)


def test_read_request_and_cli_api_parity_with_source_row_outcomes(tmp_path, capsys):
    value = request()
    text_path = tmp_path / "source.txt"
    rows_path = tmp_path / "rows.json"
    request_path = tmp_path / "request.json"
    reply_path = tmp_path / "reply.json"
    output_path = tmp_path / "result.json"
    text_path.write_text(value.document.text)
    publish_json(rows_path, [row.model_dump(mode="json") for row in value.rows])
    assert (
        main(
            [
                "prepare-source",
                str(text_path),
                str(rows_path),
                "--source-key",
                value.document.key,
                "--allow-export",
                "--output",
                str(request_path),
            ]
        )
        == 0
    )
    assert read_source_request(request_path) == value
    assert (
        read_source_request(
            request_path, expected_sha256=sha256(request_path.read_bytes()).hexdigest()
        )
        == value
    )
    with pytest.raises(ValueError, match="external SHA"):
        read_source_request(request_path, expected_sha256="0" * 64)
    assert main(["source-prompt", str(request_path)]) == 0
    assert json.loads(capsys.readouterr().out) == json.loads(source_prompt(value))
    publish_json(reply_path, recording(value).model_dump(mode="json"))
    root = tmp_path / "recording"
    assert main(["import-source", str(request_path), str(reply_path), str(root)]) == 0
    assert main(["replay-source", str(request_path), str(root), "--output", str(output_path)]) == 0
    expected = replay_source(value, root)
    assert json.loads(output_path.read_text()) == expected.model_dump(mode="json")

    denied = request(export_permission="denied")
    denied_path = tmp_path / "denied.json"
    denied_output = tmp_path / "denied-output.json"
    publish_json(denied_path, denied.model_dump(mode="json"))
    assert (
        main(
            [
                "replay-source",
                str(denied_path),
                str(tmp_path / "missing"),
                "--output",
                str(denied_output),
            ]
        )
        == 2
    )
    assert "denies source export" in capsys.readouterr().err
    assert not denied_output.exists()


def test_prepare_source_preserves_crlf_and_refuses_invalid_input_before_output(tmp_path, capsys):
    text_path = tmp_path / "source.txt"
    rows_path = tmp_path / "rows.json"
    output_path = tmp_path / "request.json"
    text_path.write_bytes(b"AA\r\nBB")
    publish_json(rows_path, [{"key": "row", "span": {"start": 0, "end": 6}}])
    assert (
        main(
            [
                "prepare-source",
                str(text_path),
                str(rows_path),
                "--source-key",
                "crlf",
                "--output",
                str(output_path),
            ]
        )
        == 0
    )
    assert read_source_request(output_path).document.text == "AA\r\nBB"

    invalid_text = tmp_path / "invalid.txt"
    invalid_output = tmp_path / "invalid-output.json"
    invalid_text.write_bytes(b"\x80")
    assert (
        main(
            [
                "prepare-source",
                str(invalid_text),
                str(rows_path),
                "--source-key",
                "invalid",
                "--output",
                str(invalid_output),
            ]
        )
        == 2
    )
    assert "utf-8" in capsys.readouterr().err.lower()
    assert not invalid_output.exists()

    malformed_rows = tmp_path / "malformed-rows.json"
    malformed_output = tmp_path / "malformed-output.json"
    publish_json(malformed_rows, {"key": "not-an-array"})
    assert (
        main(
            [
                "prepare-source",
                str(text_path),
                str(malformed_rows),
                "--source-key",
                "malformed-rows",
                "--output",
                str(malformed_output),
            ]
        )
        == 2
    )
    assert "rows must be a JSON array" in capsys.readouterr().err
    assert not malformed_output.exists()


def test_example_runs_outside_checkout_and_source_schema_stays_current(tmp_path):
    process = subprocess.run(
        [sys.executable, str(PILOT / "examples/source_workflow.py"), str(tmp_path / "demo")],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert process.returncode == 0, process.stderr
    result = json.loads((tmp_path / "demo/source-result.json").read_text())
    assert len(result["resolutions"]) == 2
    assert len(json.loads((tmp_path / "demo/source-account.json").read_text())["events"]) == 2
    assert json.loads((PILOT / "schemas/source.json").read_text()) == {
        "request": SourceRequest.model_json_schema(),
        "proposal": SourceProposal.model_json_schema(),
        "recording": SourceRecording.model_json_schema(),
        "result": SourceResult.model_json_schema(),
    }
