"""Saved text identity decisions have an executed CLI and evidence-preserving role consumer."""

import json
import subprocess
import sys
from pathlib import Path

from storymodel import identity_role_table, read_account, resolve_text_identity
from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.identity import IdentityAnnotations, IdentityResult, IdentityRoleRow

PILOT = Path(__file__).resolve().parents[1]


def test_installed_shape_identity_example_and_cli_role_table(tmp_path):
    destination = tmp_path / "example"
    process = subprocess.run(
        [sys.executable, str(PILOT / "examples/identity_workflow.py"), str(destination)],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert process.returncode == 0, process.stderr
    account = read_account(destination / "account.json")
    original = account.model_dump_json()
    raw = (destination / "annotations.json").read_text()
    result = resolve_text_identity(account, raw)
    assert len(result.referents) == 1
    assert set(result.referents[0].members) == {entity.id for entity in account.entities}
    assert (
        main(
            [
                "resolve-identity",
                str(destination / "account.json"),
                str(destination / "annotations.json"),
                "--output",
                str(destination / "cli-result.json"),
            ]
        )
        == 0
    )
    assert json.loads((destination / "cli-result.json").read_text()) == result.model_dump(
        mode="json"
    )
    assert (
        main(
            [
                "identity-roles",
                str(destination / "account.json"),
                str(destination / "annotations.json"),
                str(destination / "cli-result.json"),
                "--output",
                str(destination / "cli-roles.json"),
            ]
        )
        == 0
    )
    table = identity_role_table(account, raw, result)
    assert [row.identity.outcome for row in table] == ["introducing", "resolved"]
    assert [row.event for row in table] == [event.id for event in account.events]
    assert [row.context for row in table] == [event.context for event in account.events]
    assert [row.evidence for row in table] == [entity.evidence for entity in account.entities]
    assert json.loads((destination / "cli-roles.json").read_text()) == [
        row.model_dump(mode="json") for row in table
    ]
    assert account.model_dump_json() == original

    tampered = result.model_copy(update={"mentions": ()})
    publish_json(destination / "tampered.json", tampered.model_dump(mode="json"))
    refused = destination / "refused.json"
    assert (
        main(
            [
                "identity-roles",
                str(destination / "account.json"),
                str(destination / "annotations.json"),
                str(destination / "tampered.json"),
                "--output",
                str(refused),
            ]
        )
        == 2
    )
    assert not refused.exists()


def test_identity_export_denies_before_reading_annotations(tmp_path, capsys):
    from storymodel.source import compile_source
    from storymodel.source_demo import example_source_proposal, example_source_request

    account = compile_source(example_source_request(), example_source_proposal()).account
    denied = account.model_copy(update={"export_permission": "denied"})
    path = tmp_path / "account.json"
    publish_json(path, denied.model_dump(mode="json"))
    output = tmp_path / "output.json"
    assert (
        main(["resolve-identity", str(path), str(tmp_path / "missing"), "--output", str(output)])
        == 2
    )
    assert "producer denies identity export" in capsys.readouterr().err
    assert not output.exists()


def test_identity_schema_drift():
    assert json.loads((PILOT / "schemas/identity.json").read_text()) == {
        "annotations": IdentityAnnotations.model_json_schema(),
        "result": IdentityResult.model_json_schema(),
        "role": IdentityRoleRow.model_json_schema(),
    }
