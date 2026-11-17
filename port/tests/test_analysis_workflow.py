"""Saved chains must prove full snapshot continuity, not merely prompt compatibility."""

import json
import subprocess
import sys
from hashlib import sha256
from pathlib import Path

import pytest

from storymodel.analysis_workflow import (
    AnalysisAccountInput,
    AnalysisFile,
    AnalysisReplay,
    read_analysis_plan,
    replay_analysis,
    replay_analysis_file,
    verify_analysis,
)
from storymodel.cli import main
from storymodel.direct_mapping import mapping_prompt, validate_mapping_request
from storymodel.serialization import canonical


@pytest.fixture(scope="module")
def artifacts(tmp_path_factory):
    root = tmp_path_factory.mktemp("analysis-chain") / "examples"
    script = Path(__file__).resolve().parents[1] / "examples/analysis_workflow.py"
    process = subprocess.run(
        [sys.executable, str(script), str(root)], capture_output=True, text=True
    )
    assert process.returncode == 0, process.stdout + process.stderr
    return root


@pytest.mark.parametrize(
    "branch,number", [("context", 1), ("context", 2), ("native", 1), ("native", 2)]
)
def test_connected_saved_chain_retains_receipts_views_and_pending_outcomes(
    artifacts, branch, number
):
    root = artifacts / branch
    plan = read_analysis_plan(root / f"analysis-{number}.json")
    result = replay_analysis(plan, root)
    assert result == AnalysisReplay.model_validate_json(
        (root / f"analysis-{number}-result.json").read_bytes()
    )
    assert verify_analysis(plan, root, result) == result
    assert len(result.recall_details) + len(result.stages) == 3
    assert result.source.construction is not None and result.recall.construction is not None
    assert result.account.bindings[0].assessments[0].status == (
        "completed" if number == 1 else "pending"
    )
    assert result.stages[-1].result.decisions[0].status == (
        "admitted" if number == 1 else "rejected"
    )
    if branch == "native":
        original = json.loads((root / f"recall-{number}/fidelity-result.json").read_text())
        assert result.stages[-1].result.model_dump(mode="json") == original
        assert result.account.schema_version == "storymodel.account/v8"
        assert result.recall_details[0].request.account == result.recall.account
    else:
        prefix = "named" if number == 1 else "rejected"
        original = json.loads((root / f"results/{prefix}-fidelity-receipt.json").read_text())
        assert result.stages[-1].result.model_dump(mode="json") == original
        assert result.stages[-1].request.context_evidence is not None
    assert (
        result.views.account_sha256
        == sha256(canonical(result.account.model_dump(mode="json")).encode()).hexdigest()
    )


def test_source_is_identical_across_recalls_and_manifest_paths_ignore_cwd(
    artifacts, tmp_path, monkeypatch
):
    plans = [artifacts / "context" / f"analysis-{i}.json" for i in (1, 2)]
    first = replay_analysis_file(plans[0])
    monkeypatch.chdir(tmp_path)
    second = replay_analysis_file(plans[1])
    assert first.source == second.source
    assert first.recall != second.recall


@pytest.mark.parametrize("mutation", ["reverse", "skip-details", "repeat-mapping"])
def test_reordered_or_missing_predecessor_fails_before_recording_io(artifacts, mutation):
    root = artifacts / "context"
    plan = read_analysis_plan(root / "analysis-1.json")
    details, mapping, fidelity = plan.stages
    broken = mapping.model_copy(update={"recording": "absent-recording"})
    stages = (fidelity, mapping, details) if mutation == "reverse" else (broken, fidelity)
    if mutation == "repeat-mapping":
        stages = (details, mapping, broken.model_copy(update={"name": "second-mapping"}))
    changed = plan.model_copy(update={"stages": stages})
    with pytest.raises(ValueError, match="input differs from preceding account"):
        replay_analysis(changed, root)


def test_equal_prompt_does_not_authorize_a_changed_private_snapshot(artifacts, tmp_path):
    root = artifacts / "context"
    plan = read_analysis_plan(root / "analysis-1.json")
    step = plan.stages[1]
    original = validate_mapping_request((root / step.request.path).read_bytes())
    data = original.model_dump(mode="json")
    data["account"]["recipe"]["input_sha256"].append("c" * 64)
    altered = validate_mapping_request(canonical(data))
    assert altered.account != original.account
    assert mapping_prompt(altered) == mapping_prompt(original)
    path = tmp_path / "changed-request.json"
    path.write_text(canonical(data))
    changed = step.model_copy(
        update={"request": AnalysisFile(path=str(path)), "recording": "absent-recording"}
    )
    changed_plan = plan.model_copy(update={"stages": (plan.stages[0], changed, plan.stages[2])})
    with pytest.raises(ValueError, match="stage 'mapping' input differs"):
        replay_analysis(changed_plan, root)


def test_exchanged_recurring_recording_refuses_full_request_binding(artifacts):
    root = artifacts / "context"
    plan = read_analysis_plan(root / "analysis-1.json")
    other = read_analysis_plan(root / "analysis-2.json")
    changed = plan.stages[1].model_copy(update={"recording": other.stages[1].recording})
    with pytest.raises(ValueError, match="another request"):
        replay_analysis(
            plan.model_copy(update={"stages": (plan.stages[0], changed, plan.stages[2])}), root
        )


@pytest.mark.parametrize(
    "pin", ["source-file", "stage-file", "construction-import", "stage-import"]
)
def test_independent_pins_cover_each_file_boundary(artifacts, pin):
    root = artifacts / "context"
    plan = read_analysis_plan(root / "analysis-1.json")
    if pin == "source-file":
        source = plan.source.model_copy(
            update={"file": plan.source.file.model_copy(update={"sha256": "c" * 64})}
        )
        plan = plan.model_copy(update={"source": source})
    elif pin == "construction-import":
        plan = plan.model_copy(
            update={"source": plan.source.model_copy(update={"import_sha256": "c" * 64})}
        )
    else:
        step = plan.stages[0]
        update = (
            {"request": step.request.model_copy(update={"sha256": "c" * 64})}
            if pin == "stage-file"
            else {"import_sha256": "c" * 64}
        )
        plan = plan.model_copy(
            update={"stages": (step.model_copy(update=update), *plan.stages[1:])}
        )
    with pytest.raises(ValueError, match="expected .*SHA-256"):
        replay_analysis(plan, root)


def test_missing_stage_is_an_error_not_an_implicit_skipped_assessment(artifacts):
    root = artifacts / "context"
    plan = read_analysis_plan(root / "analysis-1.json")
    bad = plan.stages[-1].model_copy(update={"recording": "missing"})
    with pytest.raises(FileNotFoundError):
        replay_analysis(plan.model_copy(update={"stages": (*plan.stages[:-1], bad)}), root)


@pytest.mark.parametrize("side", ["source", "recall"])
def test_export_denial_precedes_later_reads_and_result_read(artifacts, tmp_path, capsys, side):
    root = artifacts / "context"
    plan = read_analysis_plan(root / "analysis-1.json")
    account_input = getattr(plan, side)
    request = json.loads((root / account_input.file.path).read_bytes())
    request["export_permission"] = "denied"
    denied = tmp_path / "denied-request.json"
    denied.write_text(canonical(request))
    replacement = account_input.model_copy(
        update={"file": AnalysisFile(path=str(denied)), "recording": "missing-recording"}
    )
    # Other account inputs retain valid absolute paths; denial must precede recording reads.
    for item in (plan.source, plan.recall):
        object.__setattr__(item.file, "path", str(root / item.file.path))
        object.__setattr__(item, "recording", str(root / item.recording))
    altered = plan.model_copy(update={side: replacement})
    with pytest.raises(PermissionError, match="denies"):
        replay_analysis(altered, root)
    manifest = tmp_path / "plan.json"
    manifest.write_text(canonical(altered.model_dump(mode="json")))
    assert main(["verify-analysis", str(manifest), str(tmp_path / "missing-result")]) != 0
    assert "denies" in capsys.readouterr().err


def test_receipt_tampering_is_detected_against_original_plan(artifacts):
    root = artifacts / "context"
    plan = read_analysis_plan(root / "analysis-1.json")
    result = replay_analysis(plan, root)
    tampered = result.model_copy(update={"stages": result.stages[:-1]})
    with pytest.raises(ValueError, match="differs from original"):
        verify_analysis(plan, root, tampered)


def test_snapshot_inputs_reproduce_construction_pairing_without_fabricating_receipts(
    artifacts, tmp_path
):
    root = artifacts / "context"
    plan = read_analysis_plan(root / "analysis-1.json")
    expected = replay_analysis(plan, root)
    replacements = {}
    for side in ("source", "recall"):
        path = tmp_path / f"{side}.json"
        path.write_text(getattr(expected, side).account.model_dump_json())
        replacements[side] = AnalysisAccountInput(kind="account", file=AnalysisFile(path=str(path)))
    result = replay_analysis(plan.model_copy(update=replacements), root)
    assert result.account == expected.account and result.views == expected.views
    assert result.source.construction is None and result.recall.construction is None


def test_duplicate_names_and_non_detail_prepair_stage_are_refused(artifacts):
    root = artifacts / "context"
    plan = read_analysis_plan(root / "analysis-1.json")
    for update, message in (
        ({"stages": (plan.stages[0], plan.stages[0])}, "unique"),
        ({"recall_details": (plan.stages[1],), "stages": ()}, "before pairing"),
    ):
        with pytest.raises(ValueError, match=message):
            replay_analysis(plan.model_copy(update=update), root)


def test_cli_replay_emits_complete_receipt_and_verification_succeeds(artifacts, capsys):
    path = artifacts / "context/analysis-1.json"
    assert main(["replay-analysis", str(path)]) == 0
    result = json.loads(capsys.readouterr().out)
    assert len(result["stages"]) == 3 and result["source"]["construction"] is not None
    assert main(["verify-analysis", str(path), str(path.with_name("analysis-1-result.json"))]) == 0
    assert json.loads(capsys.readouterr().out) == {"verified": True}


@pytest.mark.parametrize("number", [1, 2])
def test_joint_broad_alternative_external_and_completed_grades_survive_chain(artifacts, number):
    root = artifacts / "correspondence"
    result = replay_analysis_file(root / f"analysis-{number}.json")
    name = "recall-a" if number == 1 else "recall-b"
    assert result.account.model_dump(mode="json") == json.loads(
        (root / name / "account.json").read_bytes()
    )
    assert result.views.model_dump(mode="json") == json.loads(
        (root / name / "analysis.json").read_bytes()
    )
    assert len(result.account.bindings) == 9 and len(result.stages) == 19
    references = [b.reference for b in result.account.bindings]
    assert any(r.outcome == "alternative" for r in references)
    assert {h.kind for r in references for h in r.hypotheses} == {
        "single",
        "joint",
        "broad",
        "external",
    }
    for previous, current in zip(result.stages, result.stages[1:]):
        assert current.request.account == previous.result.account
