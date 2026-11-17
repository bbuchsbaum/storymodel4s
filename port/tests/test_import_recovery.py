from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
from hashlib import sha256
from pathlib import Path

import pytest

import storymodel.execution as execution
from storymodel.execution import (
    CacheTampered,
    PreviousAttemptUnknown,
    RecordedRun,
    RunProfile,
    WriterBusy,
)


def _profile(*, max_tokens: int = 16) -> RunProfile:
    return RunProfile(
        "offline",
        "AA",
        "offline:AA",
        max_tokens,
        "AA/BB",
        (sha256(b"AA").hexdigest(),),
    )


def _run(root: Path, *, max_tokens: int = 16) -> RecordedRun:
    return RecordedRun(root, _profile(max_tokens=max_tokens))


def _args() -> tuple[tuple[str, ...], tuple[str, ...], dict[str, str]]:
    return (("AA", "BB"), ("BB", "AA"), {"origin": "AA", "reply": "BB"})


def _snapshot(root: Path) -> dict[Path, bytes]:
    return {path.relative_to(root): path.read_bytes() for path in root.rglob("*") if path.is_file()}


def test_malformed_journal_value_refuses_without_untyped_exception(tmp_path: Path) -> None:
    prompts, responses, manifest = _args()
    run = _run(tmp_path / "recording")
    run.import_replies(prompts, responses, manifest)
    (run.root / "journal.json").write_text(
        json.dumps(
            {
                "schema": "storymodel.port.recorded-run-journal/v1",
                "entries": {run._key(prompts[0]): []},
            }
        )
    )
    before = _snapshot(run.root)
    with pytest.raises(CacheTampered, match="journal entry"):
        run.import_replies(prompts, responses, manifest, recover=True)
    assert _snapshot(run.root) == before


def test_import_replies_requires_a_new_root_unless_recovery_is_explicit(tmp_path: Path) -> None:
    prompts, responses, manifest = _args()
    root = tmp_path / "recording"
    root.mkdir()
    with pytest.raises(FileExistsError):
        _run(root).import_replies(prompts, responses, manifest)
    with pytest.raises(PreviousAttemptUnknown, match="durable import intent"):
        _run(root).import_replies(prompts, responses, manifest, recover=True)


@pytest.mark.parametrize("phase", ("profile", "reply", "receipt"))
def test_import_recovery_completes_each_interrupted_phase(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, phase: str
) -> None:
    prompts, responses, manifest = _args()
    root = tmp_path / phase
    run = _run(root)
    if phase == "profile":
        original = run._check_profile

        def stop_after_profile(writable: bool) -> None:
            original(writable)
            raise RuntimeError("interrupted")

        monkeypatch.setattr(run, "_check_profile", stop_after_profile)
    elif phase == "reply":
        original = run._write_reply
        calls = 0

        def stop_after_reply(key: str, prompt: str, response: str) -> None:
            nonlocal calls
            original(key, prompt, response)
            calls += 1
            if calls == 1:
                raise RuntimeError("interrupted")

        monkeypatch.setattr(run, "_write_reply", stop_after_reply)
    else:
        original = execution._atomic_json_new

        def stop_before_receipt(path: Path, value: object, *, newline: bool = False) -> None:
            if path.name == "import.json":
                raise RuntimeError("interrupted")
            original(path, value, newline=newline)

        monkeypatch.setattr(execution, "_atomic_json_new", stop_before_receipt)
    with pytest.raises(RuntimeError, match="interrupted"):
        run.import_replies(prompts, responses, manifest)
    monkeypatch.undo()
    _run(root).import_replies(prompts, responses, manifest, recover=True)
    assert (root / "import.json").read_bytes() == b'{"origin":"AA","reply":"BB"}\n'
    assert _run(root).run(prompts) == responses


def test_recovery_refuses_changed_input_without_mutating_completed_bytes(tmp_path: Path) -> None:
    prompts, responses, manifest = _args()
    root = tmp_path / "recording"
    _run(root).import_replies(prompts, responses, manifest)
    before = _snapshot(root)
    with pytest.raises(CacheTampered, match="durable import intent"):
        _run(root).import_replies(prompts, ("BB", "BB"), manifest, recover=True)
    assert _snapshot(root) == before


def test_recovery_refuses_tampered_reply_and_noncanonical_manifest(tmp_path: Path) -> None:
    prompts, responses, manifest = _args()
    root = tmp_path / "recording"
    _run(root).import_replies(prompts, responses, manifest)
    reply = next((root / "replies").glob("*.json"))
    reply.write_text("{}", encoding="utf-8")
    with pytest.raises(CacheTampered):
        _run(root).import_replies(prompts, responses, manifest, recover=True)
    root = tmp_path / "manifest"
    _run(root).import_replies(prompts, responses, manifest)
    (root / "import.json").write_text('{"origin":"AA","reply":"BB"}', encoding="utf-8")
    with pytest.raises(CacheTampered, match="canonical"):
        _run(root).import_replies(prompts, responses, manifest, recover=True)


def test_advisory_lock_refuses_live_writer_and_releases_after_process_death(tmp_path: Path) -> None:
    root = tmp_path / "recording"
    root.mkdir()
    source = Path(__file__).resolve().parents[1] / "src"
    script = (
        "from pathlib import Path\n"
        "from storymodel.execution import RecordedRun, RunProfile\n"
        "from hashlib import sha256\n"
        "import sys, time\n"
        "p = RunProfile('offline', 'AA', 'offline:AA', 16, 'AA/BB', (sha256(b'AA').hexdigest(),))\n"
        f"r = RecordedRun(Path({str(root)!r}), p)\n"
        "with r._writer_lock():\n"
        " print('locked', flush=True)\n"
        " time.sleep(60)\n"
    )
    environment = {**os.environ, "PYTHONPATH": str(source)}
    child = subprocess.Popen(
        [sys.executable, "-c", script], stdout=subprocess.PIPE, text=True, env=environment
    )
    try:
        assert child.stdout is not None
        assert child.stdout.readline().strip() == "locked"
        with pytest.raises(WriterBusy):
            with _run(root)._writer_lock():
                pass
        os.kill(child.pid, signal.SIGTERM)
        assert child.wait(timeout=5) != 0
        with _run(root)._writer_lock():
            pass
    finally:
        if child.poll() is None:
            child.terminate()
            child.wait(timeout=5)


@pytest.mark.parametrize("destination", ("profile.json", "replies"))
def test_recovery_keeps_crash_left_atomic_staging_and_completes_identically(
    tmp_path: Path, destination: str
) -> None:
    prompts, responses, manifest = _args()
    root = tmp_path / destination
    source = Path(__file__).resolve().parents[1] / "src"
    condition = (
        "Path(destination).name == 'profile.json'"
        if destination == "profile.json"
        else "Path(destination).parent.name == 'replies'"
    )
    script = (
        "from pathlib import Path\n"
        "import os, signal\n"
        "import storymodel.execution as execution\n"
        "from storymodel.execution import RecordedRun, RunProfile\n"
        "from hashlib import sha256\n"
        "p = RunProfile('offline', 'AA', 'offline:AA', 16, 'AA/BB', (sha256(b'AA').hexdigest(),))\n"
        f"root = Path({str(root)!r})\n"
        "original = execution.os.replace\n"
        "def stop(source, destination):\n"
        f" if {condition}: os.kill(os.getpid(), signal.SIGKILL)\n"
        " return original(source, destination)\n"
        "execution.os.replace = stop\n"
        "RecordedRun(root, p).import_replies(\n"
        " ('AA', 'BB'), ('BB', 'AA'), {'origin': 'AA', 'reply': 'BB'}\n"
        ")\n"
    )
    child = subprocess.run(
        [sys.executable, "-c", script], env={**os.environ, "PYTHONPATH": str(source)}, check=False
    )
    assert child.returncode < 0
    staging = tuple(root.rglob(".*.tmp"))
    assert staging
    before = {path.relative_to(root): path.read_bytes() for path in staging}
    _run(root).import_replies(prompts, responses, manifest, recover=True)
    assert {path.relative_to(root): path.read_bytes() for path in staging} == before
    clean = tmp_path / f"clean-{destination}"
    _run(clean).import_replies(prompts, responses, manifest)
    for path in ("profile.json", "import.json"):
        assert (root / path).read_bytes() == (clean / path).read_bytes()
    assert sorted(path.read_bytes() for path in (root / "replies").glob("*.json")) == sorted(
        path.read_bytes() for path in (clean / "replies").glob("*.json")
    )


def test_recovery_refuses_duplicate_key_or_nonfinite_durable_json(tmp_path: Path) -> None:
    prompts, responses, manifest = _args()
    root = tmp_path / "recording"
    _run(root).import_replies(prompts, responses, manifest)
    (root / "profile.json").write_text(
        '{"schema":"first","schema":"storymodel.port.recorded-run-profile/v1"}', encoding="utf-8"
    )
    with pytest.raises(CacheTampered, match="malformed"):
        _run(root).import_replies(prompts, responses, manifest, recover=True)
    root = tmp_path / "overflow"
    _run(root).import_replies(prompts, responses, manifest)
    (root / "import.json").write_text('{"origin":1e10000}', encoding="utf-8")
    with pytest.raises(CacheTampered, match="malformed"):
        _run(root).import_replies(prompts, responses, manifest, recover=True)
    root = tmp_path / "nonfinite"
    _run(root).import_replies(prompts, responses, manifest)
    (root / "import.json").write_text('{"origin":NaN}', encoding="utf-8")
    with pytest.raises(CacheTampered, match="malformed"):
        _run(root).import_replies(prompts, responses, manifest, recover=True)


def test_recovery_distinguishes_json_boolean_from_numeric_intent_value(tmp_path: Path) -> None:
    prompts, responses, manifest = _args()
    root = tmp_path / "boolean"
    _run(root, max_tokens=1).import_replies(prompts, responses, manifest)
    intent = (root / "import-intent.json").read_text(encoding="utf-8")
    (root / "import-intent.json").write_text(
        intent.replace('"maxTokens":1', '"maxTokens":true'), encoding="utf-8"
    )
    with pytest.raises(CacheTampered, match="durable import intent"):
        _run(root, max_tokens=1).import_replies(prompts, responses, manifest, recover=True)


@pytest.mark.parametrize("artifact", ("profile.json", "import.json", "reply"))
def test_recovery_refuses_dangling_expected_artifacts_without_replacing_them(
    tmp_path: Path, artifact: str
) -> None:
    prompts, responses, manifest = _args()
    root = tmp_path / artifact
    _run(root).import_replies(prompts, responses, manifest)
    path = next((root / "replies").glob("*.json")) if artifact == "reply" else root / artifact
    path.unlink()
    path.symlink_to(tmp_path / "missing")
    with pytest.raises(CacheTampered):
        _run(root).import_replies(prompts, responses, manifest, recover=True)
    assert path.is_symlink()
