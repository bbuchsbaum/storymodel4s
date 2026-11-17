"""Execute saved relation proposals through the installed API and CLI surface."""

import json
import subprocess
import sys
from pathlib import Path

from storymodel import admit_relations, read_account, relation_table
from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.relations import RelationProposals, RelationResult, RelationRow

PILOT = Path(__file__).resolve().parents[1]


def test_recorded_relation_example_cli_and_table_match_independent_expected_rows(tmp_path):
    destination = tmp_path / "example"
    process = subprocess.run(
        [sys.executable, str(PILOT / "examples/relation_workflow.py"), str(destination)],
        cwd=tmp_path,
        text=True,
        capture_output=True,
    )
    assert process.returncode == 0, process.stderr
    account = read_account(destination / "input.json")
    raw = (destination / "proposals.json").read_text()
    expected = admit_relations(account, raw)
    result_path = destination / "cli.json"
    assert (
        main(
            [
                "admit-relations",
                str(destination / "input.json"),
                str(destination / "proposals.json"),
                "--output",
                str(result_path),
            ]
        )
        == 0
    )
    assert json.loads(result_path.read_text()) == expected.model_dump(mode="json")
    table = relation_table(read_account(destination / "account.json"))
    assert len(table) == 1
    assert (table[0].kind, table[0].source_predicate, table[0].target_predicate) == (
        "before",
        "AA BB",
        "CC DD",
    )
    assert [(q.span.start, q.span.end, q.text) for q in table[0].evidence] == [
        (0, 2, "AA"),
        (9, 11, "DD"),
    ]
    assert (
        main(
            [
                "relation-table",
                str(destination / "account.json"),
                "--output",
                str(destination / "cli-table.json"),
            ]
        )
        == 0
    )
    assert json.loads((destination / "cli-table.json").read_text()) == json.loads(
        (destination / "table.json").read_text()
    )


def test_cli_export_denial_precedes_missing_proposal_read_and_result_publication(tmp_path, capsys):
    from storymodel.source import compile_source
    from storymodel.source_demo import example_source_proposal, example_source_request

    account = compile_source(example_source_request(), example_source_proposal()).account
    account = account.model_copy(update={"export_permission": "denied"})
    path = tmp_path / "account.json"
    publish_json(path, account.model_dump(mode="json"))
    output = tmp_path / "output.json"
    assert (
        main(["admit-relations", str(path), str(tmp_path / "missing"), "--output", str(output)])
        == 2
    )
    assert "producer denies relation export" in capsys.readouterr().err
    assert not output.exists()
    assert main(["relation-table", str(path), "--output", str(output)]) == 2
    assert not output.exists()


def test_relation_schema_drift():
    assert json.loads((PILOT / "schemas/relations.json").read_text()) == {
        "proposals": RelationProposals.model_json_schema(),
        "result": RelationResult.model_json_schema(),
        "row": RelationRow.model_json_schema(),
    }
