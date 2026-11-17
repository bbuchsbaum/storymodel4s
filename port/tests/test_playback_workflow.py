"""Recorded native playback stays byte-bound across API, CLI, and readout verification."""

import json
from hashlib import sha256
from pathlib import Path

import pytest
from test_playback_construction import fixture

from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.execution import ProfileMismatch
from storymodel.playback import TickInterval
from storymodel.playback_construction import (
    PlaybackProposals,
    PlaybackRequest,
    PlaybackResult,
    SituationPlaybackReadout,
    situation_playback,
    verify_situation_playback,
)
from storymodel.playback_workflow import (
    PlaybackRecording,
    import_playback,
    playback_prompt,
    read_playback_request,
    replay_playback,
)
from storymodel.serialization import canonical, digest

PILOT = Path(__file__).resolve().parents[1]


def control():
    request, packet = fixture()
    raw = canonical(packet) + "\n"
    return (
        request,
        raw,
        PlaybackRecording(
            request_sha256=digest(request.model_dump(mode="json")),
            origin="scripted-native-control/v1",
            reply=raw,
        ),
    )


def test_playback_replay_preserves_raw_reply_and_rejects_mismatched_bindings(tmp_path, monkeypatch):
    request, raw, recording = control()
    monkeypatch.setattr("socket.socket", lambda *a, **k: pytest.fail("unexpected network"))
    root = tmp_path / "recording"
    import_playback(request, recording, root)
    cached = next((root / "replies").glob("*.json"))
    assert json.loads(cached.read_text())["response"].encode() == raw.encode()
    result = replay_playback(request, root)
    assert result == replay_playback(request, root)
    assert result.response_sha256 == sha256(raw.encode()).hexdigest()
    imported = (root / "import.json").read_bytes()
    assert (
        replay_playback(request, root, expected_import_sha256=sha256(imported).hexdigest())
        == result
    )
    with pytest.raises(ValueError, match="external SHA"):
        replay_playback(request, root, expected_import_sha256="0" * 64)
    request_path = tmp_path / "request.json"
    publish_json(request_path, request.model_dump(mode="json"))
    assert (
        read_playback_request(
            request_path, expected_sha256=sha256(request_path.read_bytes()).hexdigest()
        )
        == request
    )
    with pytest.raises(ValueError, match="external SHA"):
        read_playback_request(request_path, expected_sha256="0" * 64)
    changed = request.model_copy(
        update={
            "observations": (request.observations[0].model_copy(update={"procedure": "other"}),)
        }
    )
    with pytest.raises(ValueError, match="another request"):
        replay_playback(changed, root)
    profile = root / "profile.json"
    profile_value = json.loads(profile.read_text())
    profile_value["recipe"] = "foreign-profile/v1"
    profile.write_text(canonical(profile_value) + "\n")
    with pytest.raises(ProfileMismatch):
        replay_playback(request, root)
    with pytest.raises(FileExistsError):
        import_playback(request, recording, root)


def test_playback_export_refusal_precedes_import_replay_and_cli_output(tmp_path, capsys):
    request, _, recording = control()
    denied = request.model_copy(update={"export_permission": "denied"})
    denied_path = tmp_path / "denied.json"
    reply_path = tmp_path / "reply.json"
    output = tmp_path / "out.json"
    root = tmp_path / "untouched"
    publish_json(denied_path, denied.model_dump(mode="json"))
    publish_json(reply_path, recording.model_dump(mode="json"))
    for operation in (
        lambda: playback_prompt(denied),
        lambda: import_playback(denied, recording, root),
        lambda: replay_playback(denied, root),
    ):
        with pytest.raises(PermissionError, match="denies"):
            operation()
    assert not root.exists()
    for args in (
        ["playback-prompt", str(denied_path), "--output", str(output)],
        ["import-playback", str(denied_path), str(reply_path), str(root)],
        ["replay-playback", str(denied_path), str(root), "--output", str(output)],
    ):
        assert main(args) == 2
        assert "denies playback construction export" in capsys.readouterr().err
    assert not root.exists() and not output.exists()


def test_playback_cli_matches_api_and_readout_verifier_refuses_tampering(tmp_path, capsys):
    request, _, recording = control()
    source_path, observations_path = tmp_path / "source.json", tmp_path / "observations.json"
    prepared, reply_path = tmp_path / "request.json", tmp_path / "reply.json"
    publish_json(source_path, request.source.model_dump(mode="json"))
    publish_json(observations_path, [o.model_dump(mode="json") for o in request.observations])
    assert (
        main(
            [
                "prepare-playback",
                str(source_path),
                str(observations_path),
                "--allow-export",
                "--output",
                str(prepared),
            ]
        )
        == 0
    )
    assert read_playback_request(prepared) == request
    prompt_path = tmp_path / "prompt.json"
    assert main(["playback-prompt", str(prepared), "--output", str(prompt_path)]) == 0
    assert json.loads(prompt_path.read_text()) == json.loads(playback_prompt(request))
    publish_json(reply_path, recording.model_dump(mode="json"))
    cli_root = tmp_path / "cli-recording"
    assert main(["import-playback", str(prepared), str(reply_path), str(cli_root)]) == 0
    result = replay_playback(request, cli_root)
    result_path, account_path = tmp_path / "result.json", tmp_path / "account.json"
    assert (
        main(["replay-playback", str(prepared), str(cli_root), "--output", str(result_path)]) == 0
    )
    assert json.loads(result_path.read_text()) == result.model_dump(mode="json")
    assert (
        main(
            [
                "replay-playback",
                str(prepared),
                str(cli_root),
                "--account-only",
                "--output",
                str(account_path),
            ]
        )
        == 0
    )
    assert json.loads(account_path.read_text()) == result.account.model_dump(mode="json")

    bins = (TickInterval(start="0", end="10"), TickInterval(start="20", end="30"))
    bins_path, readout_path = tmp_path / "bins.json", tmp_path / "readout.json"
    publish_json(bins_path, [b.model_dump(mode="json") for b in bins])
    event = result.account.events[0]
    expected = situation_playback(result.account, event.id, request.source.id, bins)
    assert (
        main(
            [
                "situation-playback",
                str(account_path),
                event.id,
                request.source.id,
                "--bins",
                str(bins_path),
                "--output",
                str(readout_path),
            ]
        )
        == 0
    )
    saved = SituationPlaybackReadout.model_validate_json(readout_path.read_bytes())
    assert saved == expected
    assert (
        main(
            [
                "verify-situation-playback",
                str(account_path),
                event.id,
                request.source.id,
                str(readout_path),
                "--bins",
                str(bins_path),
            ]
        )
        == 0
    )
    tampered = saved.model_copy(update={"account_sha256": "0" * 64})
    with pytest.raises(ValueError, match="differs"):
        verify_situation_playback(result.account, event.id, request.source.id, bins, tampered)
    with pytest.raises(ValueError, match="differs"):
        verify_situation_playback(
            result.account.model_copy(update={"export_permission": "denied"}),
            event.id,
            request.source.id,
            bins,
            saved,
        )
    with pytest.raises(ValueError, match="differs"):
        verify_situation_playback(result.account, event.id, request.source.id, (), saved)
    readout_path.write_text(canonical(tampered.model_dump(mode="json")) + "\n")
    verification = tmp_path / "verified.json"
    assert (
        main(
            [
                "verify-situation-playback",
                str(account_path),
                event.id,
                request.source.id,
                str(readout_path),
                "--bins",
                str(bins_path),
                "--output",
                str(verification),
            ]
        )
        == 2
    )
    assert "differs" in capsys.readouterr().err and not verification.exists()


def test_committed_playback_schema_matches_public_models():
    assert json.loads((PILOT / "schemas/playback.json").read_text()) == {
        "request": PlaybackRequest.model_json_schema(),
        "proposals": PlaybackProposals.model_json_schema(),
        "result": PlaybackResult.model_json_schema(),
        "recording": PlaybackRecording.model_json_schema(),
        "readout": SituationPlaybackReadout.model_json_schema(),
    }
