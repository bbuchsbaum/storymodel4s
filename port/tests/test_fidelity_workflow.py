"""Recorded conditional assessment uses the real cache, CLI and installed-style example."""

import json
import subprocess
import sys
from hashlib import sha256
from pathlib import Path

import pytest
from test_direct_mapping import decision, fixture, quote, raw

from storymodel import prepare_fidelity, resolve_mapping
from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.execution import RecordedRun, TransportFailure
from storymodel.fidelity import (
    FidelityReply,
    FidelityRequest,
    FidelityResult,
    fidelity_prompt,
    fidelity_request_digest,
)
from storymodel.fidelity_workflow import (
    FidelityRecording,
    _profile,
    _prompt,
    import_fidelity,
    read_fidelity_request,
    replay_fidelity,
)
from storymodel.serialization import canonical


def control():
    mapping, rows, _ = fixture()
    mapped = resolve_mapping(mapping, raw(mapping, decision((rows[0],))))
    request = prepare_fidelity(mapped.account, mapping.unit)
    reply = canonical(
        {
            "schema_version": "storymodel.fidelity-proposal/v1",
            "packet_sha256": json.loads(fidelity_prompt(request))["packet_sha256"],
            "assessments": [
                {
                    "hypothesis": "a0",
                    "status": "completed",
                    "grade": "supported",
                    "reason": "abstract control",
                    "evidence": [quote(rows[0])],
                }
            ],
        }
    )
    return request, FidelityRecording(
        request_sha256=fidelity_request_digest(request), origin="scripted-control/v1", reply=reply
    )


def test_fidelity_offline_cache_keeps_raw_reply_and_refuses_foreign_or_tampered_input(
    tmp_path, monkeypatch
):
    request, recording = control()
    monkeypatch.setattr("socket.socket", lambda *a, **k: pytest.fail("unexpected network"))
    root = tmp_path / "recording"
    import_fidelity(request, recording, root)
    result = replay_fidelity(request, root)
    assert result.account.bindings[0].reference == request.account.bindings[0].reference
    assert result.account.bindings[0].assessments[0].grade == "supported"
    assert replay_fidelity(request, root) == result
    imported = (root / "import.json").read_bytes()
    assert (
        replay_fidelity(request, root, expected_import_sha256=sha256(imported).hexdigest())
        == result
    )
    with pytest.raises(ValueError, match="external SHA"):
        replay_fidelity(request, root, expected_import_sha256="0" * 64)
    with pytest.raises(FileExistsError):
        import_fidelity(request, recording, root)
    tampered = json.loads(imported)
    tampered["reply"] = "{}"
    (root / "import.json").write_text(canonical(tampered))
    with pytest.raises(ValueError, match="cached fidelity differs"):
        replay_fidelity(request, root)
    bad_root = tmp_path / "malformed"
    import_fidelity(request, recording.model_copy(update={"reply": "not JSON"}), bad_root)
    assert json.loads((bad_root / "import.json").read_text())["reply"] == "not JSON"
    with pytest.raises(ValueError):
        replay_fidelity(request, bad_root)
    for bad in (
        recording.model_copy(update={"request_sha256": "0" * 64}),
        recording.model_copy(update={"origin": True}),
        recording.model_copy(update={"reply": "\ud800"}),
    ):
        with pytest.raises(ValueError):
            import_fidelity(request, bad, tmp_path / "untouched")
        assert not (tmp_path / "untouched").exists()
    with pytest.raises(TransportFailure):
        RecordedRun(tmp_path / "interrupted", _profile(request)).run(
            (_prompt(request),),
            transport=lambda _: (_ for _ in ()).throw(RuntimeError("interrupted")),
            max_new_calls=1,
        )
    with pytest.raises(FileExistsError):
        import_fidelity(request, recording, tmp_path / "interrupted")


def test_fidelity_request_pins_and_export_grants_precede_reply_io(tmp_path, capsys):
    request, recording = control()
    path = tmp_path / "request.json"
    publish_json(path, request.model_dump(mode="json"))
    assert (
        read_fidelity_request(path, expected_sha256=sha256(path.read_bytes()).hexdigest())
        == request
    )
    with pytest.raises(ValueError, match="external SHA"):
        read_fidelity_request(path, expected_sha256="0" * 64)
    denied = request.model_copy(
        update={"account": request.account.model_copy(update={"export_permission": "denied"})}
    )
    publish_json(tmp_path / "denied.json", denied.model_dump(mode="json"))
    publish_json(tmp_path / "account.json", denied.account.model_dump(mode="json"))
    with pytest.raises(PermissionError):
        _prompt(denied)
    with pytest.raises(PermissionError):
        import_fidelity(denied, recording, tmp_path / "untouched")
    with pytest.raises(PermissionError):
        replay_fidelity(denied, tmp_path / "missing")
    for args in (
        [
            "prepare-fidelity",
            str(tmp_path / "account.json"),
            denied.unit,
            "--output",
            str(tmp_path / "out.json"),
        ],
        ["fidelity-prompt", str(tmp_path / "denied.json"), "--output", str(tmp_path / "out.json")],
        [
            "import-fidelity",
            str(tmp_path / "denied.json"),
            str(tmp_path / "missing"),
            str(tmp_path / "untouched"),
        ],
        [
            "replay-fidelity",
            str(tmp_path / "denied.json"),
            str(tmp_path / "missing"),
            "--output",
            str(tmp_path / "out.json"),
        ],
    ):
        assert main(args) != 0
        assert "denies" in capsys.readouterr().err
    assert not (tmp_path / "untouched").exists() and not (tmp_path / "out.json").exists()


def test_connected_rich_fidelity_example_cli_and_schema(tmp_path, capsys):
    port = Path(__file__).resolve().parents[1]
    root = tmp_path / "example"
    run = subprocess.run(
        [sys.executable, str(port / "examples/fidelity_workflow.py"), str(root)],
        capture_output=True,
        text=True,
    )
    assert run.returncode == 0, run.stdout + run.stderr
    assert json.loads(run.stdout.splitlines()[-1])["conditional_coverage"] == {
        "recall-a": "present",
        "recall-b": "absent",
    }
    case = root / "recall-a"
    request = read_fidelity_request(case / "request.json")
    assert (
        main(
            [
                "prepare-fidelity",
                str(root / "mapping/recall-a/account.json"),
                request.unit,
                "--output",
                str(case / "cli-request.json"),
            ]
        )
        == 0
    )
    assert json.loads((case / "cli-request.json").read_text()) == json.loads(
        (case / "request.json").read_text()
    )
    assert (
        main(
            [
                "fidelity-prompt",
                str(case / "request.json"),
                "--output",
                str(case / "cli-prompt.json"),
            ]
        )
        == 0
    )
    assert json.loads((case / "cli-prompt.json").read_text()) == json.loads(
        (case / "prompt.json").read_text()
    )
    assert (
        main(
            [
                "import-fidelity",
                str(case / "request.json"),
                str(case / "reply.json"),
                str(case / "cli-recording"),
            ]
        )
        == 0
    )
    assert (
        main(
            [
                "replay-fidelity",
                str(case / "request.json"),
                str(case / "cli-recording"),
                "--output",
                str(case / "cli-result.json"),
            ]
        )
        == 0
    )
    assert json.loads((case / "cli-result.json").read_text()) == json.loads(
        (case / "result.json").read_text()
    )
    assert (
        main(
            [
                "replay-fidelity",
                str(case / "request.json"),
                str(case / "cli-recording"),
                "--account-only",
                "--output",
                str(case / "cli-account.json"),
            ]
        )
        == 0
    )
    assert json.loads((case / "cli-account.json").read_text()) == json.loads(
        (case / "account.json").read_text()
    )
    absent = FidelityRecording(
        request_sha256=fidelity_request_digest(request),
        origin="missing control",
        reply=canonical(
            {
                "schema_version": "storymodel.fidelity-proposal/v1",
                "packet_sha256": json.loads(fidelity_prompt(request))["packet_sha256"],
                "assessments": [],
            }
        ),
    )
    import_fidelity(request, absent, case / "missing-recording")
    assert (
        main(
            [
                "replay-fidelity",
                str(case / "request.json"),
                str(case / "missing-recording"),
                "--account-only",
                "--output",
                str(case / "no-out.json"),
            ]
        )
        != 0
    )
    assert "discard" in capsys.readouterr().err
    assert not (case / "no-out.json").exists()
    assert json.loads((port / "schemas/fidelity.json").read_text()) == {
        "request": FidelityRequest.model_json_schema(),
        "reply": FidelityReply.model_json_schema(),
        "result": FidelityResult.model_json_schema(),
        "recording": FidelityRecording.model_json_schema(),
    }
