"""CLI/API equality, export refusal, and generated schema drift."""

import json
import subprocess
import sys
from pathlib import Path

from storymodel.alignment import (
    AlignmentPolicy,
    AlignmentResult,
    align_account,
    validate_policy,
    verify_alignment,
)
from storymodel.analysis import project
from storymodel.artifacts import publish_json, read_account
from storymodel.cli import main
from storymodel.extraction import resolve_extraction
from storymodel.extraction_demo import example_proposal, example_request

PILOT = Path(__file__).resolve().parents[1]


def test_demo_and_cli_outside_checkout_match_api_without_network(tmp_path, monkeypatch):
    process = subprocess.run(
        [sys.executable, str(PILOT / "examples/alignment_workflow.py"), str(tmp_path / "demo")],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert process.returncode == 0, process.stderr
    root = tmp_path / "demo"
    original = read_account(root / "input-account.json")
    policy = validate_policy((root / "policy.json").read_bytes())
    monkeypatch.setattr(
        "socket.socket", lambda *a, **k: (_ for _ in ()).throw(AssertionError("network"))
    )
    expected = align_account(original, policy)
    assert verify_alignment(original, (root / "alignment.json").read_bytes()) == expected
    assert read_account(root / "account.json") == expected.account
    assert json.loads((root / "analysis.json").read_text()) == project(expected.account).model_dump(
        mode="json"
    )
    output = tmp_path / "cli-account.json"
    assert (
        main(
            [
                "align",
                str(root / "input-account.json"),
                "--policy-file",
                str(root / "policy.json"),
                "--account-only",
                "--output",
                str(output),
            ]
        )
        == 0
    )
    assert read_account(output) == expected.account
    # Exclusive publication retains the old complete result.
    before = output.read_bytes()
    assert main(["align", str(root / "input-account.json"), "--output", str(output)]) == 2
    assert output.read_bytes() == before


def test_denied_export_precedes_policy_read_and_output_creation(tmp_path, capsys):
    account = resolve_extraction(example_request(), example_proposal()).account
    account = account.model_copy(update={"export_permission": "denied"})
    path, output = tmp_path / "account.json", tmp_path / "out.json"
    publish_json(path, account.model_dump(mode="json"))
    assert (
        main(
            [
                "align",
                str(path),
                "--policy-file",
                str(tmp_path / "missing"),
                "--output",
                str(output),
            ]
        )
        == 2
    )
    assert "denies alignment export" in capsys.readouterr().err
    assert not output.exists()


def test_generated_alignment_schema_has_no_drift():
    assert json.loads((PILOT / "schemas/alignment.json").read_text()) == {
        "policy": AlignmentPolicy.model_json_schema(),
        "result": AlignmentResult.model_json_schema(),
    }
