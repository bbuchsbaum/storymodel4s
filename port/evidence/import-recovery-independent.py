import json
import shutil
from hashlib import sha256

import pytest

from storymodel.execution import CacheTampered, PreviousAttemptUnknown, RecordedRun, RunProfile

P = ("AA", "BB")
R = ("BB", "AA")
M = {"origin": "AA", "reply": "BB"}


def profile(tokens=16):
    return RunProfile("offline", "AA", "offline:AA", tokens, "AA/BB", (sha256(b"AA").hexdigest(),))


def snapshot(root):
    return {str(p.relative_to(root)): p.read_bytes() for p in root.rglob("*") if p.is_file()}


def fresh(tmp_path):
    run = RecordedRun(tmp_path / "recording", profile())
    run.import_replies(P, R, M)
    return run


@pytest.mark.parametrize("changed", ["profile", "prompt", "response", "manifest"])
def test_intent_only_state_binds_every_input(tmp_path, changed):
    run = fresh(tmp_path)
    (run.root / "profile.json").unlink()
    (run.root / "import.json").unlink()
    shutil.rmtree(run.root / "replies")
    before = snapshot(run.root)
    candidate = RecordedRun(run.root, profile(17) if changed == "profile" else profile())
    with pytest.raises(CacheTampered):
        candidate.import_replies(
            ("BB", "AA") if changed == "prompt" else P,
            ("AA", "BB") if changed == "response" else R,
            {**M, "origin": "BB"} if changed == "manifest" else M,
            recover=True,
        )
    assert snapshot(run.root) == before


def test_late_bad_reply_cannot_create_missing_profile(tmp_path):
    run = fresh(tmp_path)
    (run.root / "profile.json").unlink()
    path = next((run.root / "replies").glob("*.json"))
    path.write_text("{}")
    before = snapshot(run.root)
    with pytest.raises(CacheTampered):
        run.import_replies(P, R, M, recover=True)
    assert snapshot(run.root) == before


@pytest.mark.parametrize("kind", ["root", "reply", "journal"])
def test_foreign_entries_cannot_repair_missing_profile(tmp_path, kind):
    run = fresh(tmp_path)
    (run.root / "profile.json").unlink()
    if kind == "root":
        (run.root / "foreign").write_text("AA")
    if kind == "reply":
        (run.root / "replies" / "foreign.json").write_text("{}")
    if kind == "journal":
        (run.root / "journal.json").write_text(
            json.dumps(
                {
                    "schema": "storymodel.port.recorded-run-journal/v1",
                    "entries": {"0" * 64: "started"},
                }
            )
        )
    before = snapshot(run.root)
    with pytest.raises(CacheTampered):
        run.import_replies(P, R, M, recover=True)
    assert snapshot(run.root) == before


def test_uncertain_attempt_cannot_be_filled_from_supplied_reply(tmp_path):
    run = fresh(tmp_path)
    key = run._key(P[0])
    (run.root / "replies" / f"{key}.json").unlink()
    (run.root / "journal.json").write_text(
        json.dumps(
            {"schema": "storymodel.port.recorded-run-journal/v1", "entries": {key: "started"}}
        )
    )
    before = snapshot(run.root)
    with pytest.raises(PreviousAttemptUnknown):
        run.import_replies(P, R, M, recover=True)
    assert snapshot(run.root) == before


def test_completed_bytes_survive_recovery_of_missing_manifest(tmp_path):
    run = fresh(tmp_path)
    (run.root / "import.json").unlink()
    for path in [run.root / "profile.json", *(run.root / "replies").glob("*.json")]:
        path.write_text(json.dumps(json.loads(path.read_text()), indent=4) + "\n")
    before = snapshot(run.root)
    run.import_replies(P, R, M, recover=True)
    assert all((run.root / path).read_bytes() == raw for path, raw in before.items())
    assert run.run(P) == R
