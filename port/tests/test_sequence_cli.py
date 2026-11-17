"""Saved numerical artifacts use the same checked API as installed command-line consumers."""

import json
import math
import runpy
from pathlib import Path

import pytest

from storymodel import infer_sequence, verify_sequence
from storymodel.cli import main
from storymodel.sequence import (
    SequenceRequest,
    SequenceResult,
    SequenceRow,
    SequenceState,
    StateCost,
)
from storymodel.serialization import canonical


def request():
    return SequenceRequest(
        rows=(
            SequenceRow(
                unit="u",
                costs=tuple(
                    StateCost(
                        state=SequenceState(kind="source", anchor_kind="situation", anchor=label),
                        cost=cost,
                    )
                    for label, cost in (("a", 0.0), ("b", math.log(3)))
                ),
            ),
        ),
        export_permission="allowed",
    )


def test_saved_cli_matches_public_api_and_refuses_changed_or_forged_results(tmp_path, capsys):
    source = tmp_path / "input.json"
    output = tmp_path / "output.json"
    value = request()
    source.write_text(value.model_dump_json())
    assert main(["infer-sequence", str(source), "--output", str(output)]) == 0
    result = verify_sequence(value, output.read_bytes())
    assert result == infer_sequence(value)
    assert [m.probability for m in result.posterior[0].mass] == pytest.approx([0.75, 0.25])
    assert main(["verify-sequence", str(source), str(output)]) == 0
    assert json.loads(capsys.readouterr().out)["valid"]

    assert main(["infer-sequence", str(source), "--output", str(output)]) == 2
    assert "refused" in capsys.readouterr().err
    tampered = json.loads(output.read_text())
    tampered["log_normalizer"] += 0.1
    output.write_text(canonical(tampered))
    assert main(["verify-sequence", str(source), str(output)]) == 2
    assert "recomputed" in capsys.readouterr().err

    output.write_text(result.model_dump_json())
    source.write_text(value.model_copy(update={"temperature": 2.0}).model_dump_json())
    assert main(["verify-sequence", str(source), str(output)]) == 2
    assert "recomputed" in capsys.readouterr().err


def test_denied_export_and_invalid_json_create_no_output(tmp_path, capsys):
    source, output = tmp_path / "input.json", tmp_path / "output.json"
    source.write_text(
        request().model_copy(update={"export_permission": "denied"}).model_dump_json()
    )
    assert main(["infer-sequence", str(source), "--output", str(output)]) == 2
    assert "denies" in capsys.readouterr().err
    assert not output.exists()
    source.write_text('{"temperature":1,"temperature":2}')
    assert main(["infer-sequence", str(source), "--output", str(output)]) == 2
    assert "duplicate JSON" in capsys.readouterr().err
    assert not output.exists()


def test_numeric_example_and_schema_contract(tmp_path, monkeypatch):
    root = Path(__file__).resolve().parents[1]
    output = tmp_path / "sequence"
    monkeypatch.setattr("sys.argv", ["sequence_workflow.py", str(output)])
    runpy.run_path(str(root / "examples/sequence_workflow.py"), run_name="__main__")
    result = verify_sequence(
        (output / "request.json").read_bytes(), (output / "result.json").read_bytes()
    )
    assert [m.probability for m in result.posterior[1].mass] == pytest.approx([0.75, 0.25])
    assert [m.probability for m in result.posterior[2].mass] == pytest.approx([0.75, 0.25])
    assert json.loads((root / "schemas/sequence.json").read_text()) == {
        "request": SequenceRequest.model_json_schema(),
        "result": SequenceResult.model_json_schema(),
    }
