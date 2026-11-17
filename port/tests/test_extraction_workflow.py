"""Recorded intake, CLI, provenance and export boundaries on the admitted example."""

import json
import subprocess
import sys
from hashlib import sha256
from pathlib import Path

import pytest

from storymodel.analysis import project
from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.extraction import (
    ExtractionProposal,
    ExtractionRequest,
    ExtractionResult,
    prepare_extraction,
    request_digest,
)
from storymodel.extraction_demo import example_proposal, example_request
from storymodel.extraction_workflow import (
    ExtractionRecording,
    extraction_prompt,
    import_extraction,
    read_extraction_request,
    replay_extraction,
)
from storymodel.model import canonical

PILOT = Path(__file__).resolve().parents[1]


def record(request, reply=None):
    return ExtractionRecording(
        request_sha256=request_digest(request),
        origin="synthetic diagnostic",
        reply=example_proposal() if reply is None else reply,
    )


def test_import_and_replay_are_offline_and_bind_exact_raw_response(tmp_path, monkeypatch):
    request = example_request()
    monkeypatch.setattr("socket.socket", lambda *a, **k: pytest.fail("unexpected network"))
    root = tmp_path / "recording"
    import_extraction(request, record(request), root)
    result = replay_extraction(request, root)
    assert result.response_sha256 == sha256(example_proposal().encode()).hexdigest()
    assert len(result.account.details) == 3
    assert replay_extraction(request, root) == result
    assert '"source":' not in extraction_prompt(request)
    raw = (root / "import.json").read_bytes()
    assert (
        replay_extraction(request, root, expected_import_sha256=sha256(raw).hexdigest()) == result
    )
    with pytest.raises(ValueError, match="external SHA"):
        replay_extraction(request, root, expected_import_sha256="0" * 64)
    manifest = json.loads(raw)
    manifest["reply"] = "{}"
    (root / "import.json").write_text(canonical(manifest))
    with pytest.raises(ValueError, match="differs from imported"):
        replay_extraction(request, root)


def test_malformed_raw_reply_is_recorded_before_parse_and_cannot_be_overwritten(tmp_path):
    request = example_request()
    root = tmp_path / "recording"
    import_extraction(request, record(request, "not JSON"), root)
    assert json.loads((root / "import.json").read_text())["reply"] == "not JSON"
    assert len(list((root / "replies").glob("*.json"))) == 1
    with pytest.raises(ValueError):
        replay_extraction(request, root)
    with pytest.raises(FileExistsError):
        import_extraction(request, record(request), root)


def test_foreign_request_and_recording_refuse_before_import(tmp_path):
    request = example_request()
    changed = prepare_extraction("another-session", request.recall.raw_text.encode())
    root = tmp_path / "recording"
    with pytest.raises(ValueError, match="another request"):
        import_extraction(changed, record(request), root)
    assert not root.exists()
    import_extraction(request, record(request), root)
    with pytest.raises(ValueError, match="another request"):
        replay_extraction(changed, root)
    path = tmp_path / "request.json"
    publish_json(path, request.model_dump(mode="json"))
    assert (
        read_extraction_request(path, expected_sha256=sha256(path.read_bytes()).hexdigest())
        == request
    )
    with pytest.raises(ValueError, match="external SHA"):
        read_extraction_request(path, expected_sha256="0" * 64)


def test_denied_cli_export_refuses_before_reading_recording_or_creating_output(tmp_path, capsys):
    request = prepare_extraction("denied", b"AA BB")
    path, output = tmp_path / "request.json", tmp_path / "output.json"
    publish_json(path, request.model_dump(mode="json"))
    assert (
        main(["replay-extraction", str(path), str(tmp_path / "missing"), "--output", str(output)])
        == 2
    )
    assert "denies extraction export" in capsys.readouterr().err
    assert not output.exists()


def test_cli_from_raw_text_to_existing_analysis_matches_api(tmp_path):
    request = example_request()
    (tmp_path / "recall.txt").write_bytes(request.recall.raw_text.encode())
    publish_json(tmp_path / "source.json", request.source.model_dump(mode="json"))
    publish_json(tmp_path / "reply.json", record(request).model_dump(mode="json"))

    def run(*args):
        process = subprocess.run(
            [sys.executable, "-m", "storymodel.cli", *args],
            cwd=tmp_path,
            text=True,
            capture_output=True,
        )
        assert process.returncode == 0, process.stderr
        return process.stdout

    run(
        "prepare-extraction",
        "recall.txt",
        "--recall-key",
        request.recall.key,
        "--source-account",
        "source.json",
        "--allow-export",
        "--output",
        "request.json",
    )
    assert read_extraction_request(tmp_path / "request.json") == request
    assert json.loads(run("extraction-prompt", "request.json")) == json.loads(
        extraction_prompt(request)
    )
    run("import-extraction", "request.json", "reply.json", "recording")
    run(
        "replay-extraction",
        "request.json",
        "recording",
        "--account-only",
        "--output",
        "account.json",
    )
    run("analyze", "account.json", "--output", "analysis.json")
    expected = replay_extraction(request, tmp_path / "recording")
    assert json.loads((tmp_path / "account.json").read_text()) == expected.account.model_dump(
        mode="json"
    )
    assert json.loads((tmp_path / "analysis.json").read_text()) == project(
        expected.account
    ).model_dump(mode="json")


def test_example_outside_checkout_and_schema_drift(tmp_path):
    result = subprocess.run(
        [sys.executable, str(PILOT / "examples/extraction_workflow.py"), str(tmp_path / "demo")],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stderr
    assert len(json.loads((tmp_path / "demo/account.json").read_text())["details"]) == 3
    assert json.loads((PILOT / "schemas/extraction.json").read_text()) == {
        "request": ExtractionRequest.model_json_schema(),
        "proposal": ExtractionProposal.model_json_schema(),
        "recording": ExtractionRecording.model_json_schema(),
        "result": ExtractionResult.model_json_schema(),
    }
