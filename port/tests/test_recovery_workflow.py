"""Interrupted imports must rejoin the real saved analysis without changing results."""

import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

from storymodel.analysis_workflow import replay_analysis_file
from storymodel.cli import main


@pytest.fixture(scope="module")
def artifacts(tmp_path_factory):
    root = tmp_path_factory.mktemp("recovery") / "example"
    script = Path(__file__).resolve().parents[1] / "examples/sherlock_workflow.py"
    process = subprocess.run(
        [sys.executable, str(script), str(root)], capture_output=True, text=True
    )
    assert process.returncode == 0, process.stdout + process.stderr
    return root


def snapshot(root):
    return {
        str(p.relative_to(root)): p.read_bytes()
        for p in root.rglob("*")
        if p.is_file() and p.name != ".recorded-run.lock"
    }


def import_args(root, stage):
    recall = root / "recall-1"
    if stage == "prepared-construction":
        return [
            "import-prepared-construction",
            str(recall / "prepared.json"),
            str(root / "recovery-reply.json"),
            str(recall / "recording"),
        ]
    return [
        f"import-{stage}",
        str(recall / f"{stage}-request.json"),
        str(root / "recovery-reply.json"),
        str(recall / f"{stage}-recording"),
    ]


@pytest.mark.parametrize("stage", ["prepared-construction", "details", "mapping", "fidelity"])
@pytest.mark.parametrize("missing", ["receipt", "reply"])
def test_recovered_stage_rejoins_analysis_without_changing_completed_evidence(
    artifacts, tmp_path, stage, missing
):
    root = tmp_path / "example"
    shutil.copytree(artifacts, root)
    expected = replay_analysis_file(root / "analysis-1.json")
    args = import_args(root, stage)
    recording = Path(args[-1])
    manifest = recording / "import.json"
    Path(args[2]).write_bytes(manifest.read_bytes())
    original = snapshot(recording)
    victim = manifest if missing == "receipt" else next((recording / "replies").glob("*.json"))
    victim.unlink()
    assert main(args) != 0  # Recovery is never implicit.
    assert main([*args, "--recover"]) == 0
    assert snapshot(recording) == original
    assert replay_analysis_file(root / "analysis-1.json") == expected
    assert main([*args, "--recover"]) == 0  # Completed imports are idempotent.
    assert snapshot(recording) == original


@pytest.mark.parametrize("field", ["reply", "origin"])
def test_recovery_refuses_changed_supplied_provenance_without_touching_recording(
    artifacts, tmp_path, field
):
    root = tmp_path / "example"
    shutil.copytree(artifacts, root)
    args = import_args(root, "prepared-construction")
    recording = Path(args[-1])
    supplied = json.loads((recording / "import.json").read_bytes())
    supplied[field] += " AA"
    Path(args[2]).write_text(json.dumps(supplied))
    before = snapshot(recording)
    assert main([*args, "--recover"]) != 0
    assert snapshot(recording) == before


def test_legacy_replay_remains_available_without_implicit_recovery_adoption(artifacts, tmp_path):
    root = tmp_path / "example"
    shutil.copytree(artifacts, root)
    expected = replay_analysis_file(root / "analysis-1.json")
    args = import_args(root, "prepared-construction")
    recording = Path(args[-1])
    Path(args[2]).write_bytes((recording / "import.json").read_bytes())
    (recording / "import-intent.json").unlink()
    before = snapshot(recording)
    assert main([*args, "--recover"]) != 0
    assert snapshot(recording) == before
    assert replay_analysis_file(root / "analysis-1.json") == expected


def test_recovery_keeps_export_permission_check_before_reply_access(artifacts, tmp_path, capsys):
    request = json.loads((artifacts / "recall-1/prepared.json").read_bytes())
    request["export_permission"] = "denied"
    denied = tmp_path / "denied.json"
    denied.write_text(json.dumps(request))
    recording = tmp_path / "absent-recording"
    assert (
        main(
            [
                "import-prepared-construction",
                str(denied),
                str(tmp_path / "absent-reply"),
                str(recording),
                "--recover",
            ]
        )
        == 2
    )
    assert "denies" in capsys.readouterr().err
    assert not recording.exists()


def test_multi_chunk_import_recovers_missing_reply_without_changing_scala_compatible_result(
    tmp_path,
):
    from storymodel.study_workflow import (
        ImportedReplies,
        RawReply,
        import_time_replies,
        read_request,
        replay_time_study,
        request_digest,
    )

    fixtures = Path(__file__).parent / "fixtures"
    request = read_request(fixtures / "time-study-request.json").model_copy(
        update={"chunk_size": 3}
    )
    replies = ImportedReplies(
        origin="AA",
        request_sha256=request_digest(request),
        replies=(
            RawReply(chunk=0, attempt=0, text='{"units":[]}'),
            RawReply(chunk=1, attempt=0, text='{"units":[]}'),
        ),
    )
    root = tmp_path / "recording"
    import_time_replies(request, replies, root)
    expected = replay_time_study(request, root)
    before = snapshot(root)
    next((root / "replies").glob("*.json")).unlink()
    import_time_replies(request, replies, root, recover=True)
    assert snapshot(root) == before
    assert replay_time_study(request, root) == expected
