"""The installed-shape recorded rich-detail workflow crosses API, CLI and analysis boundaries."""

import json
import subprocess
import sys
from hashlib import sha256
from pathlib import Path

import pytest

from storymodel import export_supported_account, project, read_supported_account
from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.detail_admission import (
    DetailAdmissionResult,
    DetailProposal,
    DetailRequest,
    admit_details,
    detail_prompt,
    detail_request_digest,
    prepare_details,
)
from storymodel.detail_workflow import (
    DetailRecording,
    import_details,
    read_detail_request,
    replay_details,
)
from storymodel.direct_mapping import RichMappingRequest, RichMappingResult
from storymodel.direct_workflow import read_mapping_request, replay_mapping
from storymodel.serialization import canonical

PILOT = Path(__file__).resolve().parents[1]


def test_complete_rich_recorded_workflow_cli_matches_api(tmp_path, capsys):
    destination = tmp_path / "example"
    process = subprocess.run(
        [sys.executable, str(PILOT / "examples/rich_detail_workflow.py"), str(destination)],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert process.returncode == 0, process.stdout + process.stderr
    assert json.loads(process.stdout)["rich_collections_preserved"]
    source = read_supported_account(destination / "source/account.json")
    for name in ("recall-a", "recall-b"):
        directory = destination / name
        request = read_detail_request(directory / "detail-request.json")
        foreign = json.loads(json.loads((directory / "detail-reply.json").read_text())["reply"])
        foreign["units"][0]["details"][0]["situation"] = source.states[0].id
        refused = admit_details(request, canonical(foreign))
        assert refused.receipts[0].status == "rejected"
        assert "existing recall situation" in refused.receipts[0].reason
        prepared = directory / "cli-request.json"
        assert (
            main(
                [
                    "prepare-details",
                    str(directory / "paired.json"),
                    *request.units,
                    "--output",
                    str(prepared),
                ]
            )
            == 0
        )
        assert read_detail_request(prepared) == request
        assert main(["detail-prompt", str(prepared)]) == 0
        assert json.loads(capsys.readouterr().out) == json.loads(detail_prompt(request))
        recording = directory / "cli-detail-recording"
        assert (
            main(
                [
                    "import-details",
                    str(prepared),
                    str(directory / "detail-reply.json"),
                    str(recording),
                ]
            )
            == 0
        )
        output = directory / "cli-detailed.json"
        assert (
            main(
                [
                    "replay-details",
                    str(prepared),
                    str(recording),
                    "--account-only",
                    "--output",
                    str(output),
                ]
            )
            == 0
        )
        detailed = replay_details(request, recording).account
        assert read_supported_account(output) == detailed
        mapping_request = read_mapping_request(directory / "mapping-request.json")
        prepared_mapping = directory / "cli-mapping-request.json"
        assert (
            main(
                [
                    "prepare-mapping",
                    str(output),
                    request.units[0],
                    "--output",
                    str(prepared_mapping),
                ]
            )
            == 0
        )
        assert read_mapping_request(prepared_mapping) == mapping_request
        mapped = replay_mapping(mapping_request, directory / "mapping-recording").account
        assert main(["validate", str(directory / "account.json")]) == 0
        assert json.loads(capsys.readouterr().out)["states"] == 2
        views = directory / "cli-views.json"
        assert main(["analyze", str(directory / "account.json"), "--output", str(views)]) == 0
        assert json.loads(views.read_text()) == project(mapped).model_dump(mode="json")
        assert source.states[0] in mapped.states and source.circumstances[0] in mapped.circumstances
        assert mapped.details[0].event in {state.id for state in mapped.states}


def test_record_before_parse_and_private_input_export_boundaries(tmp_path, monkeypatch, capsys):
    from test_detail_admission import account

    value = account().model_copy(update={"export_permission": "allowed"})
    request = prepare_details(value, (value.units[0].id,))
    recording = DetailRecording(
        request_sha256=detail_request_digest(request), origin="control", reply="not JSON"
    )
    monkeypatch.setattr("socket.socket", lambda *a, **k: pytest.fail("unexpected network"))
    root = tmp_path / "recording"
    import_details(request, recording, root)
    assert json.loads((root / "import.json").read_text())["reply"] == "not JSON"
    with pytest.raises(ValueError):
        replay_details(request, root)
    with pytest.raises(FileExistsError):
        import_details(request, recording, root)
    with pytest.raises(ValueError, match="external SHA"):
        replay_details(request, root, expected_import_sha256="0" * 64)
    imported = json.loads((root / "import.json").read_bytes())
    imported["reply"] = "{}"
    (root / "import.json").write_text(canonical(imported))
    with pytest.raises(ValueError, match="differs from imported"):
        replay_details(request, root)
    untouched = tmp_path / "untouched"
    with pytest.raises(ValueError, match="another request"):
        import_details(
            request, recording.model_copy(update={"request_sha256": "0" * 64}), untouched
        )
    assert not untouched.exists()
    path = tmp_path / "request.json"
    publish_json(path, request.model_dump(mode="json"))
    assert (
        read_detail_request(path, expected_sha256=sha256(path.read_bytes()).hexdigest()) == request
    )
    with pytest.raises(ValueError, match="external SHA"):
        read_detail_request(path, expected_sha256="0" * 64)
    denied = request.model_copy(
        update={"account": value.model_copy(update={"export_permission": "denied"})}
    )
    with pytest.raises(PermissionError):
        import_details(denied, recording, untouched)
    with pytest.raises(PermissionError):
        replay_details(denied, untouched)
    denied_path = tmp_path / "denied.json"
    publish_json(denied_path, denied.model_dump(mode="json"))
    assert (
        main(["import-details", str(denied_path), str(tmp_path / "missing"), str(untouched)]) == 2
    )
    assert "denies detail export" in capsys.readouterr().err
    with pytest.raises(PermissionError):
        export_supported_account(untouched, denied.account)
    assert not untouched.exists()


def test_detail_and_rich_mapping_schema_drift():
    assert json.loads((PILOT / "schemas/detail-admission.json").read_text()) == {
        "request": DetailRequest.model_json_schema(),
        "proposal": DetailProposal.model_json_schema(),
        "result": DetailAdmissionResult.model_json_schema(),
        "recording": DetailRecording.model_json_schema(),
    }
    assert json.loads((PILOT / "schemas/rich-mapping.json").read_text()) == {
        "request": RichMappingRequest.model_json_schema(),
        "result": RichMappingResult.model_json_schema(),
    }
