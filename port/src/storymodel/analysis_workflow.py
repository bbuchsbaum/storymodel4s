"""Replay an explicit saved account chain through existing construction and assessment APIs."""

from __future__ import annotations

from hashlib import sha256
from pathlib import Path
from typing import Literal, Self, cast

from pydantic import model_validator

from .accounts import pair_accounts
from .analysis import Analysis, Policy, project
from .detail_admission import DetailAdmissionResult, DetailRequest, validate_detail_request
from .detail_workflow import replay_details
from .direct_mapping import MappingInput, MappingOutput, validate_mapping_request
from .direct_workflow import replay_mapping
from .fidelity import FidelityInput, FidelityOutput, validate_fidelity_request
from .fidelity_workflow import replay_fidelity
from .model import Digest, Model, Name, _validate_instance
from .playback_construction import PlaybackRequest, PlaybackResult, validate_playback_request
from .playback_workflow import replay_playback
from .rich_account import (
    RichAccount,
    SupportedAccount,
    pair_rich_accounts,
    upgrade_assessment_account,
    validate_supported_account,
)
from .rich_construction import (
    RichConstructionRequest,
    RichConstructionResult,
    validate_rich_construction_request,
)
from .rich_workflow import replay_rich
from .serialization import canonical, strict_json


class AnalysisFile(Model):
    """An explicit input path, optionally pinned by independently retained exact bytes."""

    path: Name
    sha256: Digest | None = None


class AnalysisAccountInput(Model):
    """Read an existing snapshot or replay an independently recorded account construction."""

    kind: Literal["account", "rich", "playback"]
    file: AnalysisFile
    recording: Name | None = None
    import_sha256: Digest | None = None

    @model_validator(mode="after")
    def recording_shape(self) -> Self:
        if self.kind == "account" and (
            self.recording is not None or self.import_sha256 is not None
        ):
            raise ValueError("account snapshot input cannot declare a construction recording")
        if self.kind != "account" and self.recording is None:
            raise ValueError("construction input requires a recording directory")
        return self


class AnalysisStage(Model):
    """One named saved request/recording pair; order is explicit and never inferred."""

    name: Name
    kind: Literal["details", "mapping", "fidelity"]
    request: AnalysisFile
    recording: Name
    import_sha256: Digest | None = None


class AnalysisPlan(Model):
    """One source and recall branch plus an explicit linear chain of saved evidence."""

    schema_version: Literal["storymodel.analysis-plan/v1"] = "storymodel.analysis-plan/v1"
    source: AnalysisAccountInput
    recall: AnalysisAccountInput
    recall_details: tuple[AnalysisStage, ...] = ()
    stages: tuple[AnalysisStage, ...] = ()
    upgrade_native_assessment: bool = False
    policy: Policy = "target-episode-novel-content/v1"
    eligible_rows: tuple[Name, ...] | None = None

    @model_validator(mode="after")
    def stage_names(self) -> Self:
        names = [s.name for s in (*self.recall_details, *self.stages)]
        if len(set(names)) != len(names):
            raise ValueError("analysis stage names must be unique")
        if any(s.kind != "details" for s in self.recall_details):
            raise ValueError("before pairing, only recall detail admission is supported")
        return self


class AnalysisAccountTrace(Model):
    """Retain the actual snapshot and its construction evidence, not merely a path label."""

    file_sha256: Digest
    account: SupportedAccount
    request: RichConstructionRequest | PlaybackRequest | None = None
    construction: RichConstructionResult | PlaybackResult | None = None
    import_sha256: Digest | None = None


class AnalysisStageTrace(Model):
    """A full input snapshot and typed resolver result expose processing gaps at every step."""

    name: Name
    request_file_sha256: Digest
    import_sha256: Digest
    request: DetailRequest | MappingInput | FidelityInput
    result: DetailAdmissionResult | MappingOutput | FidelityOutput


class AnalysisReplay(Model):
    """The checked chain and its three views remain inspectable and replayable."""

    schema_version: Literal["storymodel.analysis-replay/v1"] = "storymodel.analysis-replay/v1"
    plan: AnalysisPlan
    source: AnalysisAccountTrace
    recall: AnalysisAccountTrace
    recall_details: tuple[AnalysisStageTrace, ...]
    paired: SupportedAccount
    stages: tuple[AnalysisStageTrace, ...]
    account: SupportedAccount
    views: Analysis


def _read(file: AnalysisFile, base: Path) -> tuple[bytes, str]:
    raw = (base / file.path).read_bytes()
    actual = sha256(raw).hexdigest()
    if file.sha256 is not None and file.sha256 != actual:
        raise ValueError("analysis input differs from expected file SHA-256")
    return raw, actual


def _grant(permission: str) -> None:
    if permission != "allowed":
        raise PermissionError("producer denies saved analysis workflow export")


def _import_pin(root: Path, expected: str | None) -> str:
    actual = sha256((root / "import.json").read_bytes()).hexdigest()
    if expected is not None and expected != actual:
        raise ValueError("analysis recording differs from expected import SHA-256")
    return actual


def _account(value: AnalysisAccountInput, base: Path) -> AnalysisAccountTrace:
    raw, file_sha = _read(value.file, base)
    if value.kind == "account":
        account = validate_supported_account(raw)
        _grant(account.export_permission)
        return AnalysisAccountTrace(file_sha256=file_sha, account=account)
    request: RichConstructionRequest | PlaybackRequest
    result: RichConstructionResult | PlaybackResult
    root = base / str(value.recording)
    if value.kind == "rich":
        request = validate_rich_construction_request(raw)
        _grant(request.export_permission)
        imported_sha = _import_pin(root, value.import_sha256)
        result = replay_rich(request, root, expected_import_sha256=imported_sha)
    else:
        request = validate_playback_request(raw)
        _grant(request.export_permission)
        imported_sha = _import_pin(root, value.import_sha256)
        result = replay_playback(request, root, expected_import_sha256=imported_sha)
    return AnalysisAccountTrace(
        file_sha256=file_sha,
        account=result.account,
        request=request,
        construction=result,
        import_sha256=imported_sha,
    )


def _stage(value: AnalysisStage, account: SupportedAccount, base: Path) -> AnalysisStageTrace:
    _grant(account.export_permission)
    raw, request_sha = _read(value.request, base)
    request: DetailRequest | MappingInput | FidelityInput
    result: DetailAdmissionResult | MappingOutput | FidelityOutput
    root = base / value.recording
    if value.kind == "details":
        request = validate_detail_request(raw)
    elif value.kind == "mapping":
        request = validate_mapping_request(raw)
    else:
        request = validate_fidelity_request(raw)
    # Prompt equality is insufficient: repeated occurrences can have identical prompts.
    if request.account != account:
        raise ValueError(f"analysis stage {value.name!r} input differs from preceding account")
    imported_sha = _import_pin(root, value.import_sha256)
    if isinstance(request, DetailRequest):
        result = replay_details(request, root, expected_import_sha256=imported_sha)
    elif value.kind == "mapping":
        result = replay_mapping(
            cast(MappingInput, request),
            root,
            expected_import_sha256=imported_sha,
        )
    else:
        result = replay_fidelity(
            cast(FidelityInput, request),
            root,
            expected_import_sha256=imported_sha,
        )
    return AnalysisStageTrace(
        name=value.name,
        request_file_sha256=request_sha,
        import_sha256=imported_sha,
        request=request,
        result=result,
    )


def replay_analysis(value: AnalysisPlan, base: Path) -> AnalysisReplay:
    """Replay saved stages relative to base; no providers, skipped steps or implicit rebinding."""
    plan = _validate_instance(AnalysisPlan, value)
    source = _account(plan.source, base)
    recall = _account(plan.recall, base)
    current = recall.account
    recall_traces = []
    for stage in plan.recall_details:
        trace = _stage(stage, current, base)
        recall_traces.append(trace)
        current = trace.result.account
    paired: SupportedAccount
    if isinstance(source.account, RichAccount) and isinstance(current, RichAccount):
        paired = pair_rich_accounts(source.account, current)
    elif not isinstance(source.account, RichAccount) and not isinstance(current, RichAccount):
        paired = pair_accounts(source.account, current)
    else:
        raise ValueError(
            "analysis pairing requires compatible account families; upgrade explicitly"
        )
    if plan.upgrade_native_assessment:
        if not isinstance(paired, RichAccount) or not paired.media_sources:
            raise ValueError("native assessment upgrade requires a native source account")
        paired = upgrade_assessment_account(paired)
    current = paired
    traces = []
    for stage in plan.stages:
        trace = _stage(stage, current, base)
        traces.append(trace)
        current = trace.result.account
    views = project(current, plan.policy, eligible_rows=plan.eligible_rows)
    return AnalysisReplay(
        plan=plan,
        source=source,
        recall=recall,
        recall_details=tuple(recall_traces),
        paired=paired,
        stages=tuple(traces),
        account=current,
        views=views,
    )


def read_analysis_plan(path: Path) -> AnalysisPlan:
    """Read a strict manifest; relative references are resolved by replay_analysis_file."""
    return AnalysisPlan.model_validate_json(canonical(strict_json(path.read_bytes())))


def replay_analysis_file(path: Path) -> AnalysisReplay:
    """Use the manifest's parent directory, independent of the caller's current directory."""
    return replay_analysis(read_analysis_plan(path), path.parent)


def verify_analysis(value: AnalysisPlan, base: Path, receipt: AnalysisReplay) -> AnalysisReplay:
    """Recompute every stage from independently retained inputs before trusting derived views."""
    checked = _validate_instance(AnalysisReplay, receipt)
    expected = replay_analysis(value, base)
    if checked != expected:
        raise ValueError("analysis replay differs from original plan, inputs or recordings")
    return expected
