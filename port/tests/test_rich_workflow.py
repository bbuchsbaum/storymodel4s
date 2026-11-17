"""Recorded rich construction remains offline, inspectable and bound to its complete input."""

import json
import subprocess
import sys
from hashlib import sha256
from pathlib import Path

import pytest

from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.rich_account import (
    RichAccount,
    SituationRow,
    situation_table,
    validate_rich_account,
)
from storymodel.rich_construction import (
    RichConstructionProposal,
    RichConstructionRequest,
    RichConstructionResult,
)
from storymodel.rich_workflow import (
    RichRecording,
    import_rich,
    read_rich_request,
    replay_rich,
    rich_prompt,
)
from storymodel.serialization import canonical, digest

PILOT = Path(__file__).resolve().parents[1]


def test_recorded_example_two_recalls_and_cli_readback(tmp_path, capsys):
    destination = tmp_path / "example"
    process = subprocess.run(
        [sys.executable, str(PILOT / "examples/rich_construction.py"), str(destination)],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert process.returncode == 0, process.stdout + process.stderr
    assert json.loads(process.stdout)["source_unchanged"]
    request = read_rich_request(destination / "source/request.json")
    text, rows, prepared = tmp_path / "text", tmp_path / "rows.json", tmp_path / "prepared.json"
    text.write_bytes(request.document.text.encode())
    publish_json(rows, [r.model_dump(mode="json") for r in request.rows])
    assert (
        main(
            [
                "prepare-rich",
                str(text),
                str(rows),
                "--document-key",
                "source",
                "--side",
                "source",
                "--allow-export",
                "--output",
                str(prepared),
            ]
        )
        == 0
    )
    assert read_rich_request(prepared) == request
    assert main(["rich-prompt", str(prepared)]) == 0
    assert json.loads(capsys.readouterr().out) == json.loads(rich_prompt(request))
    recording = tmp_path / "cli-recording"
    assert (
        main(["import-rich", str(prepared), str(destination / "source/reply.json"), str(recording)])
        == 0
    )
    result = replay_rich(request, recording)
    out = tmp_path / "cli-result.json"
    assert main(["replay-rich", str(prepared), str(recording), "--output", str(out)]) == 0
    assert json.loads(out.read_text()) == result.model_dump(mode="json")
    for index in (0, 1):
        paired = validate_rich_account((destination / f"paired-{index}.json").read_bytes())
        output = tmp_path / f"table-{index}.json"
        assert (
            main(
                [
                    "situation-table",
                    str(destination / f"paired-{index}.json"),
                    "--output",
                    str(output),
                ]
            )
            == 0
        )
        assert json.loads(output.read_text()) == [
            row.model_dump(mode="json") for row in situation_table(paired)
        ]
        assert len(paired.events) == len(paired.states) == 2
        assert all(i.status == "not-attempted" for i in paired.inventories)


def test_raw_before_parse_input_binding_pins_and_export_refusal(tmp_path, monkeypatch, capsys):
    from test_rich_construction import proposal, request

    value = request().model_copy(update={"export_permission": "allowed"})
    recording = RichRecording(
        request_sha256=digest(value.model_dump(mode="json")),
        origin="control",
        reply=canonical(proposal(value)),
    )
    monkeypatch.setattr("socket.socket", lambda *a, **k: pytest.fail("unexpected network"))
    root = tmp_path / "recording"
    import_rich(value, recording, root)
    result = replay_rich(value, root)
    assert result.resolutions[0].status == "admitted"
    imported = (root / "import.json").read_bytes()
    assert replay_rich(value, root, expected_import_sha256=sha256(imported).hexdigest()) == result
    with pytest.raises(ValueError, match="external SHA"):
        replay_rich(value, root, expected_import_sha256="0" * 64)
    with pytest.raises(FileExistsError):
        import_rich(value, recording, root)
    malformed = tmp_path / "malformed"
    import_rich(value, recording.model_copy(update={"reply": "not JSON"}), malformed)
    assert json.loads((malformed / "import.json").read_text())["reply"] == "not JSON"
    with pytest.raises(ValueError):
        replay_rich(value, malformed)
    foreign = tmp_path / "foreign"
    with pytest.raises(ValueError, match="another request"):
        import_rich(value, recording.model_copy(update={"request_sha256": "0" * 64}), foreign)
    assert not foreign.exists()
    tampered = json.loads(imported)
    tampered["reply"] = "{}"
    (root / "import.json").write_text(canonical(tampered))
    with pytest.raises(ValueError, match="differs from imported"):
        replay_rich(value, root)
    path = tmp_path / "request.json"
    publish_json(path, value.model_dump(mode="json"))
    assert read_rich_request(path, expected_sha256=sha256(path.read_bytes()).hexdigest()) == value
    with pytest.raises(ValueError, match="external SHA"):
        read_rich_request(path, expected_sha256="0" * 64)
    denied = value.model_copy(update={"export_permission": "denied"})
    deny_path = tmp_path / "denied.json"
    publish_json(deny_path, denied.model_dump(mode="json"))
    for operation in (
        lambda: rich_prompt(denied),
        lambda: replay_rich(denied, foreign),
        lambda: import_rich(denied, recording, foreign),
    ):
        with pytest.raises(PermissionError):
            operation()
    assert not foreign.exists()
    assert main(["import-rich", str(deny_path), str(tmp_path / "missing"), str(foreign)]) == 2
    assert "denies rich construction export" in capsys.readouterr().err
    denied_account = tmp_path / "denied-account.json"
    publish_json(
        denied_account,
        result.account.model_copy(update={"export_permission": "denied"}).model_dump(mode="json"),
    )
    refused = tmp_path / "refused.json"
    assert main(["situation-table", str(denied_account), "--output", str(refused)]) == 2
    assert not refused.exists()


def test_committed_rich_schema_matches_public_models():
    assert json.loads((PILOT / "schemas/rich-account.json").read_text()) == {
        "account": RichAccount.model_json_schema(),
        "request": RichConstructionRequest.model_json_schema(),
        "proposal": RichConstructionProposal.model_json_schema(),
        "result": RichConstructionResult.model_json_schema(),
        "recording": RichRecording.model_json_schema(),
        "situation": SituationRow.model_json_schema(),
    }
