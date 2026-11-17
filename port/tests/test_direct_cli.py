"""The saved graph-free mapping example is consumed through the installed-shape CLI."""

import json
import subprocess
import sys
from pathlib import Path

from storymodel import project
from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.direct_mapping import MappingProposal, MappingRequest, MappingResult, mapping_prompt
from storymodel.direct_workflow import (
    MappingRecording,
    read_mapping_request,
    replay_mapping,
)

PILOT = Path(__file__).resolve().parents[1]


def test_example_cli_and_three_views_match_api(tmp_path, capsys):
    destination = tmp_path / "example"
    process = subprocess.run(
        [sys.executable, str(PILOT / "examples/direct_mapping.py"), str(destination)],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert process.returncode == 0, process.stdout + process.stderr
    assert json.loads(process.stdout)["coverage"] == "present"
    request_path = destination / "request.json"
    request = read_mapping_request(request_path)
    original_path = destination / "original.json"
    publish_json(original_path, request.account.model_dump(mode="json"))
    prepared = destination / "cli-request.json"
    assert (
        main(["prepare-mapping", str(original_path), request.unit, "--output", str(prepared)]) == 0
    )
    assert read_mapping_request(prepared) == request
    assert main(["mapping-prompt", str(prepared)]) == 0
    assert json.loads(capsys.readouterr().out) == json.loads(mapping_prompt(request))
    recording = destination / "cli-recording"
    assert (
        main(["import-mapping", str(prepared), str(destination / "reply.json"), str(recording)])
        == 0
    )
    result = replay_mapping(request, recording)
    output = destination / "cli-result.json"
    assert main(["replay-mapping", str(prepared), str(recording), "--output", str(output)]) == 0
    assert json.loads(output.read_text()) == result.model_dump(mode="json")
    output_account = destination / "cli-account.json"
    assert (
        main(
            [
                "replay-mapping",
                str(prepared),
                str(recording),
                "--account-only",
                "--output",
                str(output_account),
            ]
        )
        == 0
    )
    views = destination / "cli-views.json"
    assert main(["analyze", str(output_account), "--output", str(views)]) == 0
    assert json.loads(views.read_text()) == project(result.account).model_dump(mode="json")


def test_cli_export_denied_before_additional_io_and_unattempted_receipt_not_hidden(
    tmp_path, capsys
):
    from test_direct_mapping import fixture, raw

    from storymodel.direct_mapping import mapping_request_digest
    from storymodel.direct_workflow import import_mapping

    request, _, _ = fixture()
    denied = request.model_copy(
        update={"account": request.account.model_copy(update={"export_permission": "denied"})}
    )
    path = tmp_path / "denied.json"
    publish_json(path, denied.model_dump(mode="json"))
    for command, args in (
        ("mapping-prompt", []),
        ("import-mapping", [str(tmp_path / "missing"), str(tmp_path / "recording")]),
        ("replay-mapping", [str(tmp_path / "missing")]),
    ):
        assert main([command, str(path), *args]) == 2
        assert "denies mapping export" in capsys.readouterr().err
    assert not (tmp_path / "recording").exists()
    denied_account = tmp_path / "denied-account.json"
    publish_json(denied_account, denied.account.model_dump(mode="json"))
    assert main(["prepare-mapping", str(denied_account), request.unit]) == 2
    assert "denies mapping export" in capsys.readouterr().err
    path = tmp_path / "request.json"
    publish_json(path, request.model_dump(mode="json"))
    recording = tmp_path / "recording"
    import_mapping(
        request,
        MappingRecording(
            request_sha256=mapping_request_digest(request), origin="control", reply=raw(request)
        ),
        recording,
    )
    refused = tmp_path / "refused.json"
    assert (
        main(
            [
                "replay-mapping",
                str(path),
                str(recording),
                "--account-only",
                "--output",
                str(refused),
            ]
        )
        == 2
    )
    assert "hide unsuccessful" in capsys.readouterr().err
    assert not refused.exists()


def test_committed_mapping_schema_matches_public_models():
    assert json.loads((PILOT / "schemas/direct-mapping.json").read_text()) == {
        "request": MappingRequest.model_json_schema(),
        "proposal": MappingProposal.model_json_schema(),
        "recording": MappingRecording.model_json_schema(),
        "result": MappingResult.model_json_schema(),
    }
