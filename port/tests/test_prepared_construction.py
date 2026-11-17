"""Raw input lineage must survive canonicalization, construction and actual analysis."""

import json
import subprocess
import sys
from hashlib import sha256
from pathlib import Path

import pytest

from storymodel.analysis_workflow import (
    AnalysisAccountInput,
    AnalysisFile,
    AnalysisPlan,
    AnalysisReplay,
    replay_analysis,
    replay_analysis_file,
)
from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.prepared_construction import (
    PreparedConstructionRecording,
    compile_prepared_construction,
    import_prepared_construction,
    prepare_construction,
    prepared_construction_prompt,
    read_prepared_construction,
    replay_prepared_construction,
    validate_prepared_construction,
    verify_prepared_construction,
)
from storymodel.rich_construction import compile_rich_construction, verify_rich_construction
from storymodel.rich_workflow import RichRecording, import_rich, replay_rich
from storymodel.serialization import canonical, digest


def prepared(raw=b"AA BB", key="recall", **kwargs):
    return prepare_construction(key, "recall", raw, export_permission="allowed", **kwargs)


def reply(value):
    return canonical(
        {
            "schema_version": "storymodel.rich-construction-proposal/v2"
            if value.full_contexts
            else "storymodel.rich-construction-proposal/v1",
            "rows": [
                {"row": row.key, "status": "not-attempted", "reason": "control gap"}
                for row in value.request.rows
            ],
        }
    )


def recording(value, raw=None):
    return PreparedConstructionRecording(
        prepared_sha256=digest(value.model_dump(mode="json")),
        origin="abstract input-lineage control",
        reply=reply(value) if raw is None else raw,
    )


@pytest.mark.parametrize("full_contexts", [False, True])
def test_same_canonical_request_different_raw_inputs_cannot_share_recording(
    tmp_path, full_contexts
):
    left = prepared(b"AA BB\r\n", full_contexts=full_contexts)
    right = prepared(b"AA BB\n", full_contexts=full_contexts)
    assert left.request == right.request
    assert left.intake.document.id == right.intake.document.id
    assert left.intake.raw_sha256 == sha256(b"AA BB\r\n").hexdigest()
    assert left.intake.raw_sha256 != right.intake.raw_sha256
    assert prepared_construction_prompt(left) != prepared_construction_prompt(right)
    root = tmp_path / "recording"
    import_prepared_construction(left, recording(left), root)
    first = replay_prepared_construction(left, root)
    with pytest.raises(ValueError, match="another prepared input"):
        replay_prepared_construction(right, root)
    with pytest.raises(ValueError, match="another prepared input"):
        import_prepared_construction(right, recording(left), tmp_path / "foreign")
    assert not (tmp_path / "foreign").exists()
    second = compile_prepared_construction(right, reply(right))
    assert first.construction == second.construction
    assert first.account.documents == second.account.documents
    assert first.account.units == second.account.units
    assert first.account.recipe != second.account.recipe
    assert first.account != second.account
    assert verify_prepared_construction(left, reply(left), first) == first
    with pytest.raises(ValueError, match="original intake"):
        verify_prepared_construction(right, reply(right), first)


def test_inner_receipt_and_legacy_replay_are_unchanged(tmp_path):
    value = prepared()
    raw = reply(value)
    result = compile_prepared_construction(value, raw)
    old = compile_rich_construction(value.request, raw)
    assert result.construction == old
    assert verify_rich_construction(value.request, raw, result.construction) == old
    assert result.account.recipe.input_sha256[-2:] == (
        digest(value.model_dump(mode="json")),
        digest(old.model_dump(mode="json")),
    )
    ordinary = RichRecording(
        request_sha256=digest(value.request.model_dump(mode="json")), origin="control", reply=raw
    )
    import_rich(value.request, ordinary, tmp_path / "old")
    assert replay_rich(value.request, tmp_path / "old") == old
    with pytest.raises(ValueError):
        replay_prepared_construction(value, tmp_path / "old")


def test_supplementary_unicode_and_separator_coverage_preserve_occurrences():
    value = prepared("AA 😀 BB.\n\nCC DD EE.".encode())
    intake = value.intake
    assert intake.canonical_text == "AA 😀 BB.\n\nCC DD EE."
    assert [(u.support.start, u.support.end) for u in intake.units] == [(0, 9), (11, 20)]
    assert [(r.span.start, r.span.end) for r in value.request.rows] == [(0, 11), (11, 20)]
    assert "".join(r.span.slice(intake.document.text) for r in value.request.rows) == (
        intake.canonical_text
    )
    assert intake.separator_ranges
    # Coverage rows include separators; the original extractive occurrence stays separate.
    assert intake.units[0].text == "AA 😀 BB."


@pytest.mark.parametrize("field,bad", [("canonical_text", "forged"), ("raw_sha256", "0" * 64)])
def test_forged_intake_is_rejected_before_directory_creation(tmp_path, field, bad):
    value = prepared()
    forged = value.model_copy(update={"intake": value.intake.model_copy(update={field: bad})})
    with pytest.raises(ValueError):
        import_prepared_construction(forged, recording(value), tmp_path / "forged")
    assert not (tmp_path / "forged").exists()
    with pytest.raises(ValueError):
        validate_prepared_construction(forged)


def test_grants_pins_raw_before_parse_and_existing_directory(tmp_path):
    value = prepared()
    denied = value.model_copy(update={"export_permission": "denied"})
    with pytest.raises(PermissionError):
        replay_prepared_construction(denied, tmp_path / "absent")
    with pytest.raises(PermissionError):
        prepared_construction_prompt(denied)
    root = tmp_path / "recording"
    import_prepared_construction(value, recording(value, "invalid reply\r\n"), root)
    assert json.loads((root / "import.json").read_bytes())["reply"] == "invalid reply\r\n"
    with pytest.raises(ValueError):
        replay_prepared_construction(value, root)
    with pytest.raises(FileExistsError):
        import_prepared_construction(value, recording(value), root)
    with pytest.raises(ValueError, match="external SHA"):
        replay_prepared_construction(value, root, expected_import_sha256="0" * 64)
    path = tmp_path / "prepared.json"
    publish_json(path, value.model_dump(mode="json"))
    assert (
        read_prepared_construction(path, expected_sha256=sha256(path.read_bytes()).hexdigest())
        == value
    )
    with pytest.raises(ValueError, match="external SHA"):
        read_prepared_construction(path, expected_sha256="0" * 64)


def test_prepared_analysis_requires_explicit_version_and_grant_before_import(tmp_path):
    value = prepare_construction("recall", "recall", b"AA BB")
    publish_json(tmp_path / "input.json", value.model_dump(mode="json"))
    item = AnalysisAccountInput(
        kind="prepared-rich", file=AnalysisFile(path="input.json"), recording="nonexistent"
    )
    with pytest.raises(ValueError, match="analysis-plan/v2"):
        AnalysisPlan(source=item, recall=item)
    plan = AnalysisPlan(schema_version="storymodel.analysis-plan/v2", source=item, recall=item)
    with pytest.raises(PermissionError):
        replay_analysis(plan, tmp_path)


def test_cli_preserves_exact_input_bytes_and_checks_export_before_secondary_reads(tmp_path, capsys):
    raw = tmp_path / "raw.txt"
    raw.write_bytes(b"AA BB\r\n")
    request = tmp_path / "request.json"
    args = [
        "prepare-construction",
        str(raw),
        "--key",
        "recall",
        "--side",
        "recall",
        "--allow-export",
        "--output",
        str(request),
    ]
    assert main(args) == 0
    value = read_prepared_construction(request)
    assert value.intake.raw_text == "AA BB\r\n"
    assert main(args + ["--expected-input-sha256", "0" * 64]) != 0
    supplied = tmp_path / "reply.json"
    publish_json(supplied, recording(value).model_dump(mode="json"))
    root = tmp_path / "recorded"
    assert main(["import-prepared-construction", str(request), str(supplied), str(root)]) == 0
    out = tmp_path / "result.json"
    assert (
        main(["replay-prepared-construction", str(request), str(root), "--output", str(out)]) == 0
    )
    assert json.loads(out.read_bytes()) == replay_prepared_construction(value, root).model_dump(
        mode="json"
    )
    proposal = tmp_path / "proposal.json"
    proposal.write_bytes(reply(value).encode())
    assert main(["verify-prepared-construction", str(request), str(proposal), str(out)]) == 0
    denied = value.model_copy(update={"export_permission": "denied"})
    request = tmp_path / "denied.json"
    publish_json(request, denied.model_dump(mode="json"))
    assert main(["import-prepared-construction", str(request), "absent", str(root)]) != 0
    assert "denies" in capsys.readouterr().err


def test_installed_shape_example_replays_two_branches_through_real_analysis(tmp_path):
    root = tmp_path / "example"
    script = Path(__file__).resolve().parents[1] / "examples/prepared_workflow.py"
    process = subprocess.run(
        [sys.executable, str(script), str(root)], capture_output=True, text=True
    )
    assert process.returncode == 0, process.stdout + process.stderr
    first = replay_analysis_file(root / "analysis-1.json")
    second = replay_analysis_file(root / "analysis-2.json")
    assert first.source == second.source
    assert first.recall.request.request == second.recall.request.request
    assert first.recall.account.documents == second.recall.account.documents
    assert first.recall.account.recipe != second.recall.account.recipe
    assert len(first.stages) == len(second.stages) == 3
    assert first.schema_version == second.schema_version == "storymodel.analysis-replay/v2"
    assert first.stages[-1].result.decisions[0].status == "admitted"
    assert second.stages[-1].result.decisions[0].status == "rejected"
    assert first.account.bindings[0].assessments[0].status == "completed"
    assert second.account.bindings[0].assessments[0].status == "pending"
    for number, result in ((1, first), (2, second)):
        assert result == AnalysisReplay.model_validate_json(
            (root / f"analysis-{number}-result.json").read_bytes()
        )
