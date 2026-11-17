"""Real prepared/analysis consumers must retain both original study-format receipts."""

import json
import subprocess
import sys
from hashlib import sha256
from pathlib import Path

import pytest

from storymodel.analysis_workflow import replay_analysis_file
from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.prepared_construction import (
    read_prepared_construction,
    replay_prepared_construction,
    validate_prepared_construction,
)
from storymodel.sherlock_recall import SherlockRecall, sherlock_recall_timing
from storymodel.sherlock_source import SherlockSource


@pytest.fixture(scope="module")
def artifacts(tmp_path_factory):
    root = tmp_path_factory.mktemp("sherlock") / "example"
    script = Path(__file__).resolve().parents[1] / "examples/sherlock_workflow.py"
    process = subprocess.run(
        [sys.executable, str(script), str(root)], capture_output=True, text=True
    )
    assert process.returncode == 0, process.stdout + process.stderr
    return root


def test_study_receipts_survive_real_construction_and_conditional_analysis(artifacts):
    a = replay_analysis_file(artifacts / "analysis-1.json")
    b = replay_analysis_file(artifacts / "analysis-2.json")
    assert a.source == b.source
    assert isinstance(a.source.request.intake, SherlockSource)
    assert isinstance(a.recall.request.intake, SherlockRecall)
    source = a.source.request.intake
    assert source.raw_text.encode() == (artifacts / "source/raw-input").read_bytes()
    assert source.repair_text.encode() == (artifacts / "source/repair.json").read_bytes()
    assert [(s.first_row, s.last_row) for s in source.scenes] == [(1, 4), (5, 6)]
    assert [(locus.kind, locus.start_tick, locus.end_tick) for locus in source.loci] == [
        ("extent", 0, 25000),
        ("extent", 25000, 50000),
        ("instant", 50000, 50000),
        ("extent", 50000, 75000),
        ("extent", 0, 12500),
        ("extent", 12500, 30000),
    ]
    assert a.recall.request.request == b.recall.request.request
    assert a.recall.account.recipe != b.recall.account.recipe
    assert a.account.bindings[0].assessments[0].status == "completed"
    assert b.account.bindings[0].assessments[0].status == "pending"
    assert "present" in [r.coverage for r in a.views.source]
    assert "present" not in [r.coverage for r in b.views.source]
    assert a.stages[0].request.account == a.paired
    assert len(a.stages) == len(b.stages) == 3
    for result in (a, b):
        assert (
            result.recall.request.intake.raw_text.encode()
            == (artifacts / ("recall-1" if result == a else "recall-2") / "raw-input").read_bytes()
        )


def test_source_and_recall_cli_preserve_pins_and_actual_formats(artifacts, tmp_path):
    for name, command, extra in (
        ("source", "prepare-sherlock-source", []),
        ("recall-1", "prepare-sherlock-recall", ["--source-id", "synthetic-recall"]),
    ):
        directory = artifacts / name
        raw = directory / "raw-input"
        record = directory / ("repair.json" if name == "source" else "lineage.json")
        output = tmp_path / f"{name}.json"
        args = [
            command,
            str(raw),
            str(record),
            "--key",
            "source" if name == "source" else "recall",
            *extra,
            "--allow-export",
            "--output",
            str(output),
        ]
        assert (
            main(
                args
                + [
                    "--expected-input-sha256",
                    sha256(raw.read_bytes()).hexdigest(),
                    "--expected-record-sha256",
                    sha256(record.read_bytes()).hexdigest(),
                ]
            )
            == 0
        )
        assert read_prepared_construction(output) == read_prepared_construction(
            directory / "prepared.json"
        )
        assert main(args + ["--expected-record-sha256", "0" * 64]) != 0
        assert main(args + ["--expected-input-sha256", "0" * 64]) != 0


def test_csv_timing_is_checked_and_permission_gated(artifacts, tmp_path, capsys):
    for number, expected in ((1, "5/4"), (2, None)):
        path = artifacts / f"recall-{number}/prepared.json"
        value = read_prepared_construction(path)
        assert value.schema_version == "storymodel.prepared-construction/v2"
        for clock in ("princeton", "openneuro"):
            out = tmp_path / f"{number}-{clock}.json"
            assert (
                main(["sherlock-recall-timing", str(path), "--clock", clock, "--output", str(out)])
                == 0
            )
            timing = sherlock_recall_timing(value.intake, clock)
            assert json.loads(out.read_bytes()) == timing.model_dump(mode="json")
            assert timing.unit_onsets[0].onset_seconds == (
                expected if clock == "princeton" else "405/4"
            )
    denied = read_prepared_construction(artifacts / "recall-1/prepared.json").model_copy(
        update={"export_permission": "denied"}
    )
    denied_path = tmp_path / "denied.json"
    publish_json(denied_path, denied.model_dump(mode="json"))
    out = tmp_path / "must-not-exist.json"
    assert (
        main(
            [
                "sherlock-recall-timing",
                str(denied_path),
                "--clock",
                "princeton",
                "--output",
                str(out),
            ]
        )
        != 0
    )
    assert not out.exists() and "denies" in capsys.readouterr().err
    assert (
        main(
            [
                "sherlock-recall-timing",
                str(artifacts / "source/prepared.json"),
                "--clock",
                "princeton",
            ]
        )
        != 0
    )


def test_study_wrapper_requires_v2_and_foreign_csv_recording_is_refused(artifacts):
    first = read_prepared_construction(artifacts / "recall-1/prepared.json")
    second = read_prepared_construction(artifacts / "recall-2/prepared.json")
    assert first.request == second.request
    with pytest.raises(ValueError, match="requires prepared-construction/v2"):
        validate_prepared_construction(
            first.model_copy(update={"schema_version": "storymodel.prepared-construction/v1"})
        )
    with pytest.raises(ValueError, match="another prepared input"):
        replay_prepared_construction(second, artifacts / "recall-1/recording")
