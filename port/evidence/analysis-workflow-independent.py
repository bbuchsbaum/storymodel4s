"""Cold saved-workflow probes; set STORYMODEL_ANALYSIS_ARTIFACTS to generated examples."""

import json
import os
from pathlib import Path

import pytest

import storymodel.analysis_workflow as aw
from storymodel.detail_admission import (
    detail_prompt,
    detail_request_digest,
    validate_detail_request,
)
from storymodel.detail_workflow import DetailRecording, import_details
from storymodel.direct_mapping import (
    mapping_prompt,
    mapping_request_digest,
    validate_mapping_request,
)
from storymodel.direct_workflow import MappingRecording, import_mapping
from storymodel.fidelity import fidelity_prompt, fidelity_request_digest, validate_fidelity_request
from storymodel.fidelity_workflow import FidelityRecording, import_fidelity
from storymodel.serialization import canonical, digest

ROOT = Path(os.environ.get("STORYMODEL_ANALYSIS_ARTIFACTS", Path(__file__).parent / "artifacts"))


def plan(branch="context", number=1):
    root = ROOT / branch
    return aw.read_analysis_plan(root / f"analysis-{number}.json"), root


@pytest.mark.parametrize(
    "branch,index,validate,prompt,request_digest,record_cls,importer",
    [
        (
            "context",
            0,
            validate_detail_request,
            detail_prompt,
            detail_request_digest,
            DetailRecording,
            import_details,
        ),
        (
            "context",
            1,
            validate_mapping_request,
            mapping_prompt,
            mapping_request_digest,
            MappingRecording,
            import_mapping,
        ),
        (
            "native",
            1,
            validate_fidelity_request,
            fidelity_prompt,
            fidelity_request_digest,
            FidelityRecording,
            import_fidelity,
        ),
    ],
)
def test_valid_foreign_snapshot_rejected_even_when_prompt_equal(
    tmp_path, branch, index, validate, prompt, request_digest, record_cls, importer
):
    p, root = plan(branch)
    stage = p.stages[index]
    original = validate((root / stage.request.path).read_bytes())
    data = original.model_dump(mode="json")
    data["account"]["recipe"]["input_sha256"].append("c" * 64)
    foreign = validate(canonical(data))
    assert foreign.account != original.account
    if branch == "context" and index == 0:
        assert prompt(foreign) != prompt(original)  # details expose the full request
    else:
        assert prompt(foreign) == prompt(original)
    raw_record = json.loads((root / stage.recording / "import.json").read_bytes())
    if branch == "context" and index == 0:
        reply = json.loads(raw_record["reply"])
        reply["request_sha256"] = request_digest(foreign)
        raw_record["reply"] = canonical(reply)
    recording = tmp_path / "valid-foreign-recording"
    importer(
        foreign,
        record_cls(
            request_sha256=request_digest(foreign),
            origin="independent-review",
            reply=raw_record["reply"],
        ),
        recording,
    )
    request_file = tmp_path / "request.json"
    request_file.write_text(canonical(data))
    altered = stage.model_copy(
        update={"request": aw.AnalysisFile(path=str(request_file)), "recording": str(recording)}
    )
    changed = p.model_copy(update={"stages": (*p.stages[:index], altered)})
    with pytest.raises(ValueError, match="input differs from preceding account"):
        aw.replay_analysis(changed, root)


@pytest.mark.parametrize(
    "branch,side,kind",
    [
        ("context", "source", "rich"),
        ("context", "recall", "rich"),
        ("native", "source", "playback"),
        ("context", "source", "account"),
    ],
)
def test_grant_precedes_dependent_read(tmp_path, monkeypatch, branch, side, kind):
    p, root = plan(branch)
    original = getattr(p, side)
    if kind == "account":
        raw = aw.replay_analysis(p, root).source.account.model_dump(mode="json")
        original = aw.AnalysisAccountInput(kind="account", file=aw.AnalysisFile(path="unused"))
    else:
        raw = json.loads((root / original.file.path).read_bytes())
    raw["export_permission"] = "denied"
    file = tmp_path / "denied.json"
    file.write_text(canonical(raw))
    blocked = tmp_path / "blocked-recording"
    update = {"file": aw.AnalysisFile(path=str(file))}
    if kind != "account":
        update["recording"] = str(blocked)
    changed = p.model_copy(update={side: original.model_copy(update=update)})
    original_read = Path.read_bytes
    reads = []

    def observed(path):
        reads.append(path)
        assert blocked not in path.parents, "denied account recording was opened"
        return original_read(path)

    monkeypatch.setattr(Path, "read_bytes", observed)
    with pytest.raises(PermissionError, match="denies"):
        aw.replay_analysis(changed, root)
    if side == "source":
        assert root / p.recall.file.path not in reads


@pytest.mark.parametrize("field", ["identity_annotations", "holder_proposals"])
def test_context_evidence_bound_to_mapped_snapshot(tmp_path, monkeypatch, field):
    p, root = plan()
    result = aw.replay_analysis(p, root)
    stage = p.stages[-1]
    data = json.loads((root / stage.request.path).read_bytes())
    original = validate_fidelity_request(canonical(data))
    inner = json.loads(data["context_evidence"][field])
    assert inner["account_sha256"] == digest(original.account.model_dump(mode="json"))
    inner["account_sha256"] = digest(result.recall.account.model_dump(mode="json"))
    data["context_evidence"][field] = canonical(inner)
    file = tmp_path / "stale-context.json"
    file.write_text(canonical(data))
    changed = stage.model_copy(
        update={"request": aw.AnalysisFile(path=str(file)), "recording": str(tmp_path / "blocked")}
    )
    with pytest.raises(ValueError, match="foreign identity annotation|another account snapshot"):
        aw.replay_analysis(p.model_copy(update={"stages": (*p.stages[:-1], changed)}), root)


@pytest.mark.parametrize("branch", ["context", "native"])
def test_import_change_between_pin_and_consumer_rejected(monkeypatch, branch):
    p, root = plan(branch)
    target = root / p.source.recording / "import.json"
    actual = Path.read_bytes
    count = 0

    def replaced(path):
        nonlocal count
        raw = actual(path)
        if path == target:
            count += 1
            if count == 2:
                return raw + b"\n"
        return raw

    monkeypatch.setattr(Path, "read_bytes", replaced)
    with pytest.raises(ValueError, match="import differs from external SHA-256"):
        aw.replay_analysis(p, root)
    assert count == 2


@pytest.mark.parametrize(
    "branch,number",
    [
        ("context", 1),
        ("context", 2),
        ("native", 1),
        ("native", 2),
        ("correspondence", 1),
        ("correspondence", 2),
    ],
)
def test_full_saved_result_and_reference_preservation(branch, number):
    p, root = plan(branch, number)
    r = aw.replay_analysis(p, root)
    saved = json.loads((root / f"analysis-{number}-result.json").read_bytes())
    assert r.model_dump(mode="json") == saved
    assert aw.verify_analysis(p, root, r) == r
    if branch == "native":
        assert r.source.account.schema_version == "storymodel.account/v6"
        assert r.paired.schema_version == "storymodel.account/v8"
        assert r.source.account.media_sources == r.paired.media_sources
    if branch != "correspondence":
        assert r.stages[-1].request.account.bindings[0].reference == r.account.bindings[0].reference
        assert r.account.bindings[0].assessments[0].status == (
            "completed" if number == 1 else "pending"
        )
    else:
        completed = {}
        for trace in r.stages:
            for binding in trace.result.account.bindings:
                for assessment in binding.assessments:
                    key = (binding.detail, binding.reference.interpretation, assessment.hypothesis)
                    if key in completed:
                        assert assessment == completed[key]
                    if assessment.status == "completed":
                        completed[key] = assessment
        assert completed


@pytest.mark.parametrize("mutation", ["no-native-upgrade", "upgrade-text"])
def test_upgrade_must_be_explicit_and_applicable(mutation):
    p, root = plan("native" if mutation == "no-native-upgrade" else "context")
    p = p.model_copy(update={"upgrade_native_assessment": mutation == "upgrade-text"})
    with pytest.raises(ValueError, match="input differs|native source account"):
        aw.replay_analysis(p, root)
