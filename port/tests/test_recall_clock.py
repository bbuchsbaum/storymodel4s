"""Timed-recall clock readouts reuse the retained synthetic Sherlock workflow."""

import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.playback import TickInterval
from storymodel.recall_clock import RecordingQuery, TimedRecall, UnitInterval, timed_recall


@pytest.fixture(scope="module")
def artifacts(tmp_path_factory: pytest.TempPathFactory) -> Path:
    root = tmp_path_factory.mktemp("timed-recall") / "example"
    script = Path(__file__).resolve().parents[1] / "examples/timed_recall.py"
    process = subprocess.run(
        [sys.executable, str(script), str(root)], capture_output=True, text=True
    )
    assert process.returncode == 0, process.stdout + process.stderr
    return root


def query(artifacts: Path, number: int = 1) -> RecordingQuery:
    return RecordingQuery.model_validate_json(
        (artifacts / f"timed-recall-{number}-query.json").read_bytes()
    )


def test_retained_example_binds_joined_details_to_first_onsets_and_allocations(artifacts: Path):
    first = timed_recall(artifacts / "analysis-1.json", query(artifacts))
    second = timed_recall(artifacts / "analysis-2.json", query(artifacts, 2))

    assert first == TimedRecall.model_validate_json(
        (artifacts / "timed-recall-1.json").read_bytes()
    )
    assert first.total_details == len(first.entries) == 1
    assert first.entries[0].correspondence
    assert first.unit_onsets == {1: "105"}
    assert sorted(first.word_onsets.values()) == ["105", "110"]
    assert first.entries[0].onset_tick == "105"
    assert first.entries[0].onset_bin == 0
    assert first.onset_counts == (1, 0)
    assert [(cell.overlap, cell.value) for cell in first.entries[0].allocations] == [
        ("8", "1/2"),
        ("8", "1/2"),
    ]
    # The first assigned lexical word is missing in this retained CSV. A later
    # onset must not be substituted as the unit's onset.
    assert second.unit_onsets == {1: None}
    assert second.entries[0].onset_tick is None
    assert second.entries[0].onset_bin is None
    assert second.missing_onset_details == 1


def test_timed_recall_retains_details_before_mapping(artifacts: Path, tmp_path: Path):
    copied = tmp_path / "saved"
    shutil.copytree(artifacts, copied)
    plan = json.loads((copied / "analysis-1.json").read_text())
    plan["stages"] = plan["stages"][:1]
    truncated = copied / "details-only.json"
    publish_json(truncated, plan)

    result = timed_recall(truncated, query(copied))
    assert result.total_details == 1
    assert result.entries[0].onset_tick == "105"
    assert result.onset_counts == (1, 0)
    assert result.entries[0].correspondence == ()
    assert result.entries[0].recall.reference_status == "not-attempted"


def test_timed_recall_refuses_foreign_or_invalid_recording_geometry(artifacts: Path):
    plan = artifacts / "analysis-1.json"
    base = query(artifacts)
    with pytest.raises(ValueError, match="another recall intake"):
        timed_recall(plan, base.model_copy(update={"intake_sha256": "0" * 64}))
    with pytest.raises(ValueError, match="timing requires recall audio"):
        timed_recall(
            plan,
            base.model_copy(
                update={"recording": base.recording.model_copy(update={"side": "source", "id": ""})}
            ),
        )
    with pytest.raises(ValueError, match="integral recording tick"):
        timed_recall(
            plan,
            base.model_copy(
                update={
                    "recording": base.recording.model_copy(
                        update={"seconds_per_tick": "1/3", "id": ""}
                    )
                }
            ),
        )
    with pytest.raises(ValueError, match="outside recording extent"):
        timed_recall(plan, base.model_copy(update={"zero_tick": "118"}))
    with pytest.raises(ValueError, match="outside its declared unit interval"):
        timed_recall(
            plan,
            base.model_copy(
                update={
                    "intervals": (
                        UnitInterval(unit=1, native=TickInterval(start="100", end="108")),
                    )
                }
            ),
        )


def test_timed_recall_cli_matches_api_and_verifier_refuses_changed_readout(
    artifacts: Path, tmp_path: Path, capsys: pytest.CaptureFixture[str]
):
    plan = artifacts / "analysis-1.json"
    request = query(artifacts)
    query_path, output = tmp_path / "query.json", tmp_path / "result.json"
    publish_json(query_path, request.model_dump(mode="json"))
    expected = timed_recall(plan, request)
    assert main(["timed-recall", str(plan), str(query_path), "--output", str(output)]) == 0
    assert TimedRecall.model_validate_json(output.read_bytes()) == expected
    verified = tmp_path / "verified.json"
    assert (
        main(
            [
                "verify-timed-recall",
                str(plan),
                str(query_path),
                str(output),
                "--output",
                str(verified),
            ]
        )
        == 0
    )
    assert json.loads(verified.read_text()) == {
        "verified": True,
        "analysis_sha256": expected.analysis_sha256,
    }
    changed = expected.model_copy(update={"total_details": 0})
    changed_path = tmp_path / "changed.json"
    publish_json(changed_path, changed.model_dump(mode="json"))
    assert (
        main(
            [
                "verify-timed-recall",
                str(plan),
                str(query_path),
                str(changed_path),
                "--output",
                str(tmp_path / "must-not-exist.json"),
            ]
        )
        == 2
    )
    assert "differs from exact plan" in capsys.readouterr().err

    aliases = json.loads(output.read_text())
    aliases["unit_onsets"] = {"1": "105", "01": "105"}
    alias_path = tmp_path / "aliases.json"
    alias_path.write_text(json.dumps(aliases))
    assert main(["verify-timed-recall", str(plan), str(query_path), str(alias_path)]) == 2
    assert "differs from exact plan" in capsys.readouterr().err

    omitted = json.loads(output.read_text())
    del omitted["schema_version"]
    omitted_path = tmp_path / "omitted.json"
    omitted_path.write_text(json.dumps(omitted))
    assert main(["verify-timed-recall", str(plan), str(query_path), str(omitted_path)]) == 2
    assert "differs from exact plan" in capsys.readouterr().err


def test_two_recall_units_use_construction_identities_across_separators(
    artifacts, tmp_path, monkeypatch
):
    from hashlib import sha256

    from storymodel.detail_admission import detail_request_digest, prepare_details
    from storymodel.detail_workflow import DetailRecording, import_details
    from storymodel.prepared_construction import prepare_sherlock_construction
    from storymodel.rich_account import pair_rich_accounts, validate_supported_account
    from storymodel.serialization import canonical, digest
    from storymodel.sherlock_recall import prepare_sherlock_recall

    monkeypatch.syspath_prepend(str(Path(__file__).resolve().parents[1] / "examples"))
    from prepared_workflow import record_construction
    from sherlock_workflow import recall_inputs

    root = tmp_path / "multi"
    shutil.copytree(artifacts, root)
    raw, lineage = recall_inputs(1)
    # Repeat the synthetic sentence with its own timestamps and a separating space.
    lines = raw.splitlines()
    raw = (
        b"\n".join(
            (
                lines[0],
                lines[1],
                lines[2].replace(b"BB,", b"BB.,"),
                lines[1].replace(b"1.25,", b"3.25,", 1),
                lines[2].replace(b"2.5,", b"4.5,", 1),
            )
        )
        + b"\n"
    )
    meta = json.loads(lineage)
    meta["sources"][0].update(
        localCsvByteLength=len(raw), localCsvSha256=sha256(raw).hexdigest(), recordCount=4
    )
    intake = prepare_sherlock_recall(
        "multi", raw, json.dumps(meta).encode(), source_id="synthetic-recall"
    )
    prepared = prepare_sherlock_construction(intake, export_permission="allowed")
    recall = record_construction(root / "multi-recall", prepared, raw)
    assert (
        len(recall.units) == 2 and prepared.request.rows[0].span != intake.prepared.units[0].support
    )
    snapshot = json.loads((root / "analysis-1-result.json").read_text())["source"]["account"]
    source = validate_supported_account(canonical(snapshot))
    paired = pair_rich_accounts(source, recall)
    ordered = sorted(recall.units, key=lambda u: u.support.spans[0].start)
    request = prepare_details(paired, tuple(u.id for u in ordered))
    proposals = []
    for unit in ordered:
        start = unit.support.spans[0].start
        proposals.append(
            dict(
                unit=unit.id,
                status="completed",
                reason="abstract control",
                details=[
                    dict(
                        key="attribute",
                        kind="attribute",
                        address="target",
                        discourse="content",
                        quotes=[dict(span=dict(start=start, end=start + 2), text="AA")],
                    )
                ],
            )
        )
    request_id = detail_request_digest(request)
    reply = canonical(
        dict(
            schema_version="storymodel.detail-proposal/v1",
            request_sha256=request_id,
            units=proposals,
        )
    )
    publish_json(root / "request.json", request.model_dump(mode="json"))
    import_details(
        request,
        DetailRecording(request_sha256=request_id, origin="abstract control", reply=reply),
        root / "multi-details",
    )
    plan = json.loads((root / "analysis-1.json").read_text())
    plan["recall"]["file"]["path"] = "multi-recall/prepared.json"
    plan["recall"]["recording"] = "multi-recall/recording"
    plan["stages"] = [
        dict(
            name="details",
            kind="details",
            request=dict(path="request.json"),
            recording="multi-details",
        )
    ]
    publish_json(root / "multi.json", plan)
    declaration = query(root).model_copy(
        update={"intake_sha256": digest(intake.model_dump(mode="json")), "intervals": ()}
    )
    result = timed_recall(root / "multi.json", declaration)
    assert result.unit_onsets == {1: "105", 2: "113"}
    assert {e.recall.unit: e.onset_tick for e in result.entries} == {
        ordered[0].id: "105",
        ordered[1].id: "113",
    }
    assert result.onset_counts == (1, 1)
