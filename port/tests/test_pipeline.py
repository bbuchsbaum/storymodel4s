"""Offline end-to-end replay uses admitted text and an in-process synthetic transport only."""

import pytest

from storymodel.analysis import project
from storymodel.demo import example_account
from storymodel.execution import RecordedRun, RunProfile
from storymodel.model import Document
from storymodel.pipeline import replay_account


def profile(account):
    return RunProfile(
        provider="synthetic",
        model="fixture/v1",
        endpoint="loopback",
        max_tokens=100,
        recipe=account.recipe.name,
        input_sha256=account.recipe.input_sha256,
    )


def test_recorded_proposal_to_account_and_three_views_without_network(tmp_path, monkeypatch):
    account = example_account()
    run = RecordedRun(tmp_path / "recordings", profile(account))
    calls = []

    def fixture(prompt):
        calls.append(prompt)
        return account.model_dump_json()

    run.run(("fixture-account/v1",), transport=fixture, max_new_calls=1)
    monkeypatch.setattr("socket.socket", lambda *a, **kw: pytest.fail("network during replay"))
    rebuilt = replay_account(run, "fixture-account/v1", expected_documents=account.documents)
    assert calls == ["fixture-account/v1"]
    assert rebuilt == account
    assert project(rebuilt) == project(account)


def test_intake_substitution_refuses_before_reading_recording(tmp_path):
    account = example_account()
    run = RecordedRun(tmp_path / "does-not-exist", profile(account))
    other = Document.create("control", "recall", "AB")
    with pytest.raises(ValueError, match="exact expected document inputs"):
        replay_account(run, "fixture-account/v1", expected_documents=(other,))
    assert not run.root.exists()


def test_same_text_different_session_cannot_substitute_for_expected_document(tmp_path):
    account = example_account()
    # Equal text hashes do not identify the same edition or participant session.
    source = next(d for d in account.documents if d.side == "source")
    other = Document.create("another-edition", "source", source.text)
    expected = tuple(other if d.id == source.id else d for d in account.documents)
    run = RecordedRun(tmp_path, profile(account))
    run.run(("fixture-account/v1",), transport=lambda _: account.model_dump_json(), max_new_calls=1)
    with pytest.raises(ValueError, match="independent intake"):
        replay_account(run, "fixture-account/v1", expected_documents=expected)


def test_malformed_recording_stays_recorded_after_admission_failure(tmp_path):
    account = example_account()
    run = RecordedRun(tmp_path, profile(account))
    run.run(("fixture-account/v1",), transport=lambda _: "{", max_new_calls=1)
    with pytest.raises(ValueError):
        replay_account(run, "fixture-account/v1", expected_documents=account.documents)
    assert run.run(("fixture-account/v1",)) == ("{",)
