import json
import subprocess
import sys
from pathlib import Path

from storymodel.analysis import project
from storymodel.cli import main
from storymodel.demo import example_account


def test_cli_from_outside_checkout_matches_api(tmp_path):
    account = tmp_path / "account.json"
    output = tmp_path / "analysis.json"
    for arguments in (
        ["example", "--output", str(account)],
        ["validate", str(account)],
        ["analyze", str(account), "--output", str(output)],
    ):
        result = subprocess.run(
            [sys.executable, "-m", "storymodel.cli", *arguments],
            cwd=tmp_path,
            capture_output=True,
            text=True,
        )
        assert result.returncode == 0, result.stderr
    assert json.loads(output.read_text()) == project(example_account()).model_dump(mode="json")


def test_cli_refuses_overwrite_and_malformed_input(tmp_path, capsys):
    path = tmp_path / "output.json"
    path.write_text("retained")
    assert main(["example", "--output", str(path)]) == 2
    assert path.read_text() == "retained"
    assert main(["validate", str(path)]) == 2
    assert "refused" in capsys.readouterr().err


def test_generated_schema_is_current():
    from storymodel.analysis import Analysis
    from storymodel.model import Account

    artifact = Path(__file__).parents[1] / "schemas" / "exchange.json"
    assert json.loads(artifact.read_text()) == {
        "account": Account.model_json_schema(),
        "analysis": Analysis.model_json_schema(),
    }
