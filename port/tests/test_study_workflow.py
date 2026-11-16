"""Independent Scala-output parity and adversarial recorded workflow controls."""

import hashlib
import json
import subprocess
import sys
from pathlib import Path

import pytest

from storymodel.execution import RecordingMissing
from storymodel.model import canonical
from storymodel.study_workflow import (
    ImportedReplies,
    RawReply,
    SuppliedUnit,
    TimeStudy,
    TimeStudyResult,
    import_time_replies,
    read_request,
    replay_time_study,
    request_digest,
)

PILOT = Path(__file__).resolve().parents[1]
FIXTURES = PILOT / "tests/fixtures"
GOLDEN = PILOT.parent / "pipeline/src/test/resources/agentmapping/synthetic-study-time.bundle.json"


def fixture():
    request = read_request(FIXTURES / "time-study-request.json")
    replies = ImportedReplies.model_validate_json(
        (FIXTURES / "time-study-replies.json").read_bytes()
    )
    return request, replies


def recorded(tmp_path, request, items):
    replies = ImportedReplies(
        origin="structural test control; no provider",
        request_sha256=request_digest(request),
        replies=tuple(RawReply(chunk=c, attempt=a, text=t) for c, a, t in items),
    )
    root = tmp_path / "recording"
    import_time_replies(request, replies, root)
    return root


def test_all_computed_rows_match_pinned_scala_artifact(tmp_path, monkeypatch):
    request, replies = fixture()
    pins = json.loads((FIXTURES / "time-study-reference.json").read_text())
    assert hashlib.sha256(GOLDEN.read_bytes()).hexdigest() == pins["golden_sha256"]
    assert request_digest(request) == replies.request_sha256
    root = tmp_path / "recording"
    import_time_replies(request, replies, root)
    monkeypatch.setattr("socket.socket", lambda *a, **kw: pytest.fail("network during replay"))
    result = replay_time_study(request, root)
    golden = json.loads(GOLDEN.read_text())["units"]
    # New pilot occurrence IDs deliberately differ from Scala IDs; every semantic field agrees.
    assert [{k: v for k, v in row.items() if k != "unit"} for row in result.rows] == [
        {k: v for k, v in row.items() if k != "unit"} for row in golden
    ]
    assert len({r["unit"] for r in result.rows}) == 6
    assert result.counts == {
        "narrative": {"scene": 2, "film": 1, "invalid": 2, "omitted": 1},
        "referent": {"scene": 1, "time-outside": 1, "none": 1, "invalid": 2, "omitted": 1},
    }
    assert result.receipts[0].attempts == 1 and result.receipts[0].accepted


def test_api_cli_equal_and_result_cannot_overwrite(tmp_path):
    request, replies = fixture()
    root = tmp_path / "recording"
    imported = subprocess.run(
        [
            sys.executable,
            "-m",
            "storymodel.cli",
            "import-time-recording",
            str(FIXTURES / "time-study-request.json"),
            str(FIXTURES / "time-study-replies.json"),
            str(root),
        ],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert imported.returncode == 0, imported.stderr
    output = tmp_path / "result.json"
    command = [
        sys.executable,
        "-m",
        "storymodel.cli",
        "replay-time-study",
        str(FIXTURES / "time-study-request.json"),
        str(root),
        "--output",
        str(output),
    ]
    first = subprocess.run(command, cwd=tmp_path, capture_output=True, text=True)
    assert first.returncode == 0, first.stderr
    assert json.loads(output.read_text()) == replay_time_study(request, root).model_dump(
        mode="json"
    )
    before = output.read_bytes()
    second = subprocess.run(command, cwd=tmp_path, capture_output=True, text=True)
    assert second.returncode == 2 and "refused" in second.stderr
    assert output.read_bytes() == before


def test_strict_chunk_processing_keeps_receipts_and_last_duplicate(tmp_path):
    request, _ = fixture()
    request = request.model_copy(update={"chunk_size": 3})
    items = [
        {"u": 1, "referent_film": 1, "referent_time": "0:10"},
        {"u": 1, "referent_film": None, "referent_time": None},
        {"u": 4},
        {"u": True},
        {"u": 2.0},
        {"u": "2"},
        None,
    ]
    root = recorded(
        tmp_path, request, [(0, 0, json.dumps({"units": items})), (1, 0, '{"units":[]}')]
    )
    result = replay_time_study(request, root)
    assert result.receipts[0].items_outside_chunk == 5
    assert result.receipts[0].repeated_units == 1
    assert result.rows[0]["referent"]["judgement"] == {"kind": "none"}
    assert [r["processing"] for r in result.rows] == ["answered"] + ["omitted"] * 5


@pytest.mark.parametrize(
    "raw", ["{", '{"units":null}', '{"units":[],"units":[]}', '{"units":[],"x":NaN}']
)
def test_unparsable_chunk_is_not_omission(tmp_path, raw):
    request, _ = fixture()
    root = recorded(tmp_path, request, [(0, 0, raw)])
    result = replay_time_study(request, root)
    assert not result.receipts[0].accepted
    assert {r["processing"] for r in result.rows} == {"unparsable-chunk"}
    assert {r["referent"]["judgement"]["kind"] for r in result.rows} == {"unparsable-chunk"}


def test_only_recorded_attempts_can_be_retried(tmp_path):
    request, _ = fixture()
    request = request.model_copy(update={"max_attempts": 2})
    root = recorded(tmp_path, request, [(0, 0, "{"), (0, 1, '{"units":[]}')])
    result = replay_time_study(request, root)
    assert result.receipts[0].attempts == 2 and result.receipts[0].accepted
    missing = recorded(tmp_path / "missing", request, [(0, 0, "{")])
    with pytest.raises(RecordingMissing):
        replay_time_study(request, missing)


def test_import_binding_and_existing_root_refuse_before_writes(tmp_path):
    request, replies = fixture()
    root = tmp_path / "recording"
    wrong = request.model_copy(update={"source_key": "foreign-edition"})
    with pytest.raises(ValueError, match="another request"):
        import_time_replies(wrong, replies, root)
    assert not root.exists()
    import_time_replies(request, replies, root)
    with pytest.raises(FileExistsError):
        import_time_replies(request, replies, root)
    with pytest.raises(ValueError, match="another request"):
        replay_time_study(wrong, root)


def test_import_cache_and_external_pin_disagreement_refuse(tmp_path):
    request, replies = fixture()
    root = tmp_path / "recording"
    import_time_replies(request, replies, root)
    pin = hashlib.sha256((root / "import.json").read_bytes()).hexdigest()
    assert replay_time_study(request, root, expected_import_sha256=pin).import_sha256 == pin
    with pytest.raises(ValueError, match="external SHA"):
        replay_time_study(request, root, expected_import_sha256="0" * 64)
    manifest = json.loads((root / "import.json").read_text())
    manifest["replies"][0]["text"] = '{"units":[]}'
    (root / "import.json").write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match="cached response differs"):
        replay_time_study(request, root)


def test_duplicate_attempts_and_foreign_ordinals_refuse(tmp_path):
    request, replies = fixture()
    with pytest.raises(ValueError, match="duplicate or out-of-range"):
        import_time_replies(
            request, replies.model_copy(update={"replies": replies.replies * 2}), tmp_path / "bad"
        )
    changed = request.model_copy(
        update={"recall": request.recall.model_copy(update={"units": request.recall.units[::-1]})}
    )
    with pytest.raises(ValueError, match="contiguous"):
        replay_time_study(changed, tmp_path / "does-not-exist")


def test_repeated_text_has_distinct_occurrence_and_session_identity(tmp_path):
    request, _ = fixture()
    first = request.recall.units[0]
    recall = request.recall.model_copy(
        update={
            "units": (
                first,
                first.model_copy(update={"u": 2, "row": 11}),
            )
        }
    )
    request = request.model_copy(update={"recall": recall})
    root = recorded(tmp_path, request, [(0, 0, '{"units":[]}')])
    result = replay_time_study(request, root)
    assert result.rows[0]["unit"] != result.rows[1]["unit"]
    other = request.model_copy(
        update={"recall": recall.model_copy(update={"key": "other-session"})}
    )
    with pytest.raises(ValueError, match="another request"):
        replay_time_study(other, root)


def test_workflow_schema_has_no_drift():
    expected = {
        "request": TimeStudy.model_json_schema(),
        "replies": ImportedReplies.model_json_schema(),
        "result": TimeStudyResult.model_json_schema(),
    }
    assert json.loads((PILOT / "schemas/time-study.json").read_text()) == json.loads(
        canonical(expected)
    )


def test_overflowed_json_number_cannot_enter_a_successful_chunk(tmp_path):
    request, _ = fixture()
    root = recorded(tmp_path, request, [(0, 0, '{"units":[],"extra":1e999}')])
    result = replay_time_study(request, root)
    assert not result.receipts[0].accepted
    assert {r["processing"] for r in result.rows} == {"unparsable-chunk"}


def test_cli_denied_export_refuses_before_recording_read(tmp_path, capsys):
    from storymodel.cli import main

    request, _ = fixture()
    path = tmp_path / "request.json"
    path.write_text(request.model_copy(update={"export_permission": "denied"}).model_dump_json())
    output = tmp_path / "result.json"
    assert (
        main(
            [
                "replay-time-study",
                str(path),
                str(tmp_path / "missing"),
                "--output",
                str(output),
            ]
        )
        == 2
    )
    assert "producer denies" in capsys.readouterr().err
    assert not output.exists()


@pytest.mark.parametrize("number", [float("nan"), float("inf"), float("-inf")])
@pytest.mark.parametrize("nested", [False, True])
def test_nonfinite_api_row_is_refused_before_identity_or_io(tmp_path, number, nested):
    request, replies = fixture()
    first = request.recall.units[0]
    row = {"key": [number]} if nested else number
    with pytest.raises(ValueError):
        SuppliedUnit(u=first.u, row=row, text=first.text)
    forged = request.model_copy(
        update={
            "recall": request.recall.model_copy(
                update={
                    "units": (first.model_copy(update={"row": row}),) + request.recall.units[1:],
                }
            ),
        }
    )
    with pytest.raises(ValueError):
        request_digest(forged)
    root = tmp_path / "rejected"
    with pytest.raises(ValueError):
        import_time_replies(forged, replies, root)
    assert not root.exists()


def test_explicit_null_study_row_survives_api_import_and_replay(tmp_path):
    request, _ = fixture()
    first = request.recall.units[0]
    unit = SuppliedUnit(u=1, row=None, text=first.text)
    request = request.model_copy(
        update={"recall": request.recall.model_copy(update={"units": (unit,)})}
    )
    root = recorded(tmp_path, request, [(0, 0, '{"units":[]}')])
    assert replay_time_study(request, root).rows[0]["row"] is None
