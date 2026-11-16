from __future__ import annotations

import json
from hashlib import sha256
from pathlib import Path

import pytest

from storymodel.execution import (
    BudgetExhausted,
    CacheTampered,
    InvalidRequest,
    PreviousAttemptUnknown,
    ProfileMismatch,
    RecordedRun,
    RecordingMissing,
    RunProfile,
    WriterBusy,
)


@pytest.fixture
def profile() -> RunProfile:
    return make_profile("one")


def _input_id(label: str) -> str:
    return sha256(label.encode("ascii")).hexdigest()


def make_profile(*inputs: str) -> RunProfile:
    return RunProfile(
        "loopback",
        "numeric-fixture",
        "http://127.0.0.1:9/v1",
        16,
        "numeric-control/v1",
        tuple(_input_id(input_) for input_ in inputs),
    )


def test_replay_identical_needs_zero_calls(tmp_path: Path, profile: RunProfile) -> None:
    run = RecordedRun(tmp_path / "recordings", make_profile("one", "two"))
    assert run.run(["1", "2"], transport=lambda prompt: f"r:{prompt}", max_new_calls=2) == (
        "r:1",
        "r:2",
    )
    calls = 0

    def offline(_: str) -> str:
        nonlocal calls
        calls += 1
        raise AssertionError("replay must not dispatch")

    assert run.run(["1", "2"], transport=offline, max_new_calls=2) == ("r:1", "r:2")
    assert calls == 0
    assert run.run(["1", "2"]) == ("r:1", "r:2")


def test_budget_stop_preserves_reply_for_resume(tmp_path: Path, profile: RunProfile) -> None:
    run = RecordedRun(tmp_path / "recordings", make_profile("one", "two"))
    calls: list[str] = []
    with pytest.raises(BudgetExhausted):
        run.run(
            ["1", "2"],
            transport=lambda prompt: calls.append(prompt) or f"r:{prompt}",
            max_new_calls=1,
        )
    assert calls == ["1"]
    assert run.run(
        ["1", "2"],
        transport=lambda prompt: calls.append(prompt) or f"r:{prompt}",
        max_new_calls=1,
    ) == (
        "r:1",
        "r:2",
    )
    assert calls == ["1", "2"]


def test_started_or_failed_without_reply_refuses_retry(tmp_path: Path, profile: RunProfile) -> None:
    run = RecordedRun(tmp_path / "recordings", profile)
    with pytest.raises(BudgetExhausted):
        run.run(["1"], transport=lambda _: "unused", max_new_calls=0)
    # Construct the durable uncertainty state a process interruption can leave.
    run.root.mkdir(exist_ok=True)
    (run.root / "profile.json").write_text(json.dumps(profile.descriptor()), encoding="utf-8")
    key = run._key("1")
    (run.root / "journal.json").write_text(
        json.dumps(
            {"schema": "storymodel.port.recorded-run-journal/v1", "entries": {key: "started"}}
        ),
        encoding="utf-8",
    )
    with pytest.raises(PreviousAttemptUnknown):
        run.run(["1"], transport=lambda _: pytest.fail("must not retry"), max_new_calls=1)


def test_valid_reply_rescues_stale_started_state(tmp_path: Path, profile: RunProfile) -> None:
    run = RecordedRun(tmp_path / "recordings", profile)
    assert run.run(["1"], transport=lambda _: "r:1", max_new_calls=1) == ("r:1",)
    assert run.run(["1"]) == ("r:1",)


def test_changed_profile_refuses_before_transport(tmp_path: Path, profile: RunProfile) -> None:
    root = tmp_path / "recordings"
    assert RecordedRun(root, profile).run(["1"], transport=lambda _: "r:1", max_new_calls=1) == (
        "r:1",
    )
    changed = RunProfile(
        "loopback",
        "other-model",
        "http://127.0.0.1:9/v1",
        16,
        "numeric-control/v1",
        profile.input_sha256,
    )
    with pytest.raises(ProfileMismatch):
        RecordedRun(root, changed).run(
            ["1"],
            transport=lambda _: pytest.fail("must not dispatch"),
            max_new_calls=1,
        )


def test_tampering_fails_closed(tmp_path: Path, profile: RunProfile) -> None:
    run = RecordedRun(tmp_path / "recordings", profile)
    assert run.run(["1"], transport=lambda _: "r:1", max_new_calls=1) == ("r:1",)
    path = next((run.root / "replies").iterdir())
    payload = json.loads(path.read_text(encoding="utf-8"))
    payload["response"] = "altered"
    path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(CacheTampered):
        run.run(["1"])


def test_concurrent_writer_is_refused(tmp_path: Path, profile: RunProfile) -> None:
    run = RecordedRun(tmp_path / "recordings", profile)
    run.root.mkdir()
    with run._writer_lock():
        with pytest.raises(WriterBusy):
            run.run(["1"], transport=lambda _: "r:1", max_new_calls=1)


def test_malformed_request_preflight_dispatches_zero_calls(
    tmp_path: Path, profile: RunProfile
) -> None:
    calls = 0

    def transport(_: str) -> str:
        nonlocal calls
        calls += 1
        return "never"

    with pytest.raises(InvalidRequest):
        RecordedRun(tmp_path / "recordings", profile).run(
            ["1", ""], transport=transport, max_new_calls=2
        )
    assert calls == 0
    with pytest.raises(InvalidRequest):
        RecordedRun(tmp_path / "recordings", profile).run(
            ["1"], transport=transport, max_new_calls=True
        )
    assert calls == 0


def test_reply_is_recorded_before_caller_parses_it(tmp_path: Path, profile: RunProfile) -> None:
    run = RecordedRun(tmp_path / "recordings", profile)
    reply = run.run(["1"], transport=lambda _: "{not-json", max_new_calls=1)[0]
    with pytest.raises(json.JSONDecodeError):
        json.loads(reply)
    assert run.run(["1"]) == ("{not-json",)


def test_offline_missing_reply_never_calls_transport(tmp_path: Path, profile: RunProfile) -> None:
    with pytest.raises(RecordingMissing):
        RecordedRun(tmp_path / "recordings", profile).run(["1"])


def test_three_prompts_share_two_run_input_pins_and_reuse_duplicate_prompt(tmp_path: Path) -> None:
    run = RecordedRun(tmp_path / "recordings", make_profile("source-a", "source-b"))
    calls = 0

    def transport(_: str) -> str:
        nonlocal calls
        calls += 1
        return f"reply-{calls}"

    assert run.run(["first", "same", "same"], transport=transport, max_new_calls=2) == (
        "reply-1",
        "reply-2",
        "reply-2",
    )
    assert run.run(["first", "same", "same"]) == ("reply-1", "reply-2", "reply-2")
    assert calls == 2


def test_changed_input_identity_refuses_before_transport(tmp_path: Path) -> None:
    root = tmp_path / "recordings"
    original = make_profile("source-a")
    assert RecordedRun(root, original).run(["same"], transport=lambda _: "reply", max_new_calls=1)
    with pytest.raises(ProfileMismatch):
        RecordedRun(root, make_profile("source-b")).run(
            ["same"], transport=lambda _: pytest.fail("must not dispatch"), max_new_calls=1
        )


def test_profile_rejects_bool_budget_and_duplicate_inputs() -> None:
    digest = _input_id("source")
    with pytest.raises(InvalidRequest):
        RunProfile("p", "m", "endpoint", True, "recipe/v1", (digest,))
    with pytest.raises(InvalidRequest):
        RunProfile("p", "m", "endpoint", 1, "recipe/v1", (digest, digest))


def test_valid_json_profile_with_boolean_budget_fails_closed(tmp_path: Path) -> None:
    profile = RunProfile(
        "loopback",
        "numeric-fixture",
        "http://127.0.0.1:9/v1",
        1,
        "numeric-control/v1",
        (_input_id("one"),),
    )
    run = RecordedRun(tmp_path / "recordings", profile)
    assert run.run(["1"], transport=lambda _: "reply", max_new_calls=1) == ("reply",)
    path = run.root / "profile.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    payload["maxTokens"] = True
    path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(CacheTampered):
        run.run(["1"])
