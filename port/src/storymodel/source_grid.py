"""Project an account posterior onto declared source-time supports and a recall window."""

from collections import defaultdict
from fractions import Fraction
from pathlib import Path
from typing import Literal, Self

from pydantic import Field, model_validator

from ._base import Digest, Model, Name, _validate_instance
from ._coordinates import exact, tick
from ._source_bins import BinIndex, BinProjection
from .alignment_method import _recall_before_pairing
from .analysis_workflow import AnalysisReplay, replay_analysis_file
from .composition import PlaybackComposition
from .exposure import ExposureQuery, RecallExposure, _exposure_from_replay
from .local_evidence import (
    AccountInferenceResult,
    LocalEvidenceSpec,
    RefinedAccountInference,
    RefinedInferenceSpec,
    _resolve_account_inference,
)
from .playback import MediaSource, PlaybackSupport, TickInterval, contains_playback
from .prepared_construction import PreparedConstruction, PreparedConstructionResult
from .sequence import SequenceState
from .serialization import digest
from .sherlock_composition import _derive, _native_row_supports
from .sherlock_source import SherlockSource


class SupportDeclaration(Model):
    """Declare containment and the allocation assumption independently of inference."""

    assumption: Literal["supplied-support-contains-referent"]
    allocation: Literal["support-only", "uniform-intervals", "uniform-points", "mixture"]
    interval_share: str | None = None

    @model_validator(mode="after")
    def mixture_share(self) -> Self:
        if self.allocation == "mixture":
            if self.interval_share is None or not 0 <= exact(self.interval_share) <= 1:
                raise ValueError("mixture requires an exact interval share between zero and one")
            object.__setattr__(self, "interval_share", str(exact(self.interval_share)))
        elif self.interval_share is not None:
            raise ValueError("interval share requires mixture allocation")
        return self


class SourceGridQuery(Model):
    """Query disjoint bins on one native part or the complete ordered Sherlock composition."""

    schema_version: Literal["storymodel.source-grid-query/v1"] = "storymodel.source-grid-query/v1"
    inference: RefinedInferenceSpec | LocalEvidenceSpec
    selection: Literal["part", "composition"]
    axis: Name
    bins: tuple[TickInterval, ...] = Field(max_length=100_000)
    declarations: dict[Name, SupportDeclaration]
    exposure: ExposureQuery | None = None


class TargetDomain(Model):
    """The actual target geometry and the result of applying its declared allocation."""

    support: PlaybackSupport | None
    projection: BinProjection | None = None
    status: Literal[
        "allocated",
        "support-only",
        "incompatible-geometry",
        "no-temporal-support",
        "other-coordinate",
        "no-declaration",
        "missing-descendant",
        "descendant-outside-domain",
    ]


class GridQuantity(Model):
    """Allocated quantity and conditional bounds on the resolved support."""

    allocated: Fraction
    lower: Fraction
    upper: Fraction

    def scaled(self, duration: Fraction) -> "GridQuantity":
        return GridQuantity(
            allocated=self.allocated * duration,
            lower=self.lower * duration,
            upper=self.upper * duration,
        )


class SourceLedger(Model):
    """Account for the full incoming measure before cropping to the requested bins."""

    supplied: Fraction
    row_budget: Fraction
    allocated_source: Fraction
    support_without_allocation: Fraction
    unavailable_location: Fraction
    external: dict[str, Fraction]
    allocated_outside: Fraction


class UnitSourceGrid(Model):
    """Sparse bins; absent bins have zero mass in a computed row."""

    unit: Name
    status: Literal["computed", "outside-inference-selection"]
    ledger: SourceLedger | None
    cells: dict[int, GridQuantity] | None
    duration_seconds: Fraction | None
    weighted_seconds: dict[int, GridQuantity] | None


class SourceGrid(Model):
    """Reproducible source-bin quantities, original posterior and optional recall exposure."""

    schema_version: Literal["storymodel.source-grid/v1"] = "storymodel.source-grid/v1"
    measure: Literal["model-posterior"] = "model-posterior"
    query: SourceGridQuery
    analysis_sha256: Digest
    axis: MediaSource | PlaybackComposition
    inference: RefinedAccountInference | AccountInferenceResult
    exposure: RecallExposure | None
    domains: dict[str, TargetDomain]
    rows: tuple[UnitSourceGrid, ...]


def _source_geometry(
    replay: AnalysisReplay, query: SourceGridQuery
) -> tuple[MediaSource | PlaybackComposition, dict[str, PlaybackSupport]]:
    request, result = replay.source.request, replay.source.construction
    if not (
        isinstance(request, PreparedConstruction)
        and isinstance(request.intake, SherlockSource)
        and isinstance(result, PreparedConstructionResult)
    ):
        raise ValueError("source grid requires a prepared Sherlock source recording")
    intake = request.intake
    if query.selection == "composition":
        composition, rows, _ = _derive(intake)
        if query.axis != composition.id:
            raise ValueError("foreign source composition axis")
        by_key = {row.key: row.support for row in rows}
        return composition, {row.unit: by_key[row.row] for row in result.construction.resolutions}
    media, by_key = _native_row_supports(intake)
    physical = next((part for part in media if part.id == query.axis), None)
    if physical is None:
        raise ValueError("foreign source part axis")
    return physical, {row.unit: by_key[row.row] for row in result.construction.resolutions}


def _project_mass(
    weights: tuple[tuple[SequenceState, Fraction], ...],
    projected: dict[str, BinProjection],
) -> tuple[SourceLedger, dict[int, GridQuantity]]:
    """Project each admitted state independently, retaining its original posterior weight."""
    allocated = unallocated = unresolved = outside = Fraction()
    external: dict[str, Fraction] = defaultdict(Fraction)
    amounts: dict[int, Fraction] = defaultdict(Fraction)
    lower: dict[int, Fraction] = defaultdict(Fraction)
    upper: dict[int, Fraction] = defaultdict(Fraction)
    for state, mass in weights:
        if state.external is not None:
            external[state.external] += mass
            continue
        grid = projected.get(state.anchor) if state.anchor is not None else None
        if grid is None:
            unresolved += mass
            continue
        if grid.outside is None:
            unallocated += mass
        else:
            allocated += mass
            outside += mass * grid.outside
        if not mass:
            continue
        for bin_index, cell in grid.cells.items():
            upper[bin_index] += mass
            if cell.contained:
                lower[bin_index] += mass
            if cell.fraction is not None:
                amounts[bin_index] += mass * cell.fraction
    ledger = SourceLedger(
        supplied=sum((w for _, w in weights), Fraction()),
        row_budget=Fraction(1),
        allocated_source=allocated,
        support_without_allocation=unallocated,
        unavailable_location=unresolved,
        external=dict(external),
        allocated_outside=outside,
    )
    return ledger, {
        i: GridQuantity(allocated=amounts[i], lower=lower[i], upper=hi)
        for i, hi in sorted(upper.items())
    }


def _target_domains(
    inference: RefinedAccountInference | AccountInferenceResult,
    query: SourceGridQuery,
    index: BinIndex,
    supports: dict[str, PlaybackSupport],
) -> dict[str, TargetDomain]:
    known = {node.anchor for node in inference.evidence.sequence.projection.nodes}
    if query.declarations.keys() - known:
        raise ValueError("source grid declaration names a foreign target")
    measured_targets = {
        c.state.anchor
        for row in inference.evidence.sequence.request.rows
        for c in row.costs
        if c.state.anchor is not None
    }
    domains = {
        t: TargetDomain(status="no-declaration", support=None)
        for t in sorted(measured_targets - query.declarations.keys())
    }
    children: dict[str, list[str]] = defaultdict(list)
    for node in inference.evidence.sequence.projection.nodes:
        if node.parent is not None:
            children[node.parent].append(node.anchor)
    for target in sorted(measured_targets & query.declarations.keys()):
        declaration = query.declarations[target]
        support = supports.get(target)
        if support is None:
            domains[target] = TargetDomain(support=None, status="no-temporal-support")
        elif support.source != query.axis:
            domains[target] = TargetDomain(support=support, status="other-coordinate")
        else:
            pending = list(children.get(target, ()))
            problem: (
                Literal["missing-descendant", "descendant-outside-domain", "other-coordinate"]
                | None
            ) = None
            while pending:
                child = pending.pop()
                observed = supports.get(child)
                if observed is None:
                    problem = "missing-descendant"
                    break
                if observed.source != query.axis:
                    problem = "other-coordinate"
                    break
                if not contains_playback(observed, support):
                    problem = "descendant-outside-domain"
                    break
                pending.extend(children.get(child, ()))
            if problem is not None:
                domains[target] = TargetDomain(support=support, status=problem)
                continue
            part = index.project(
                support,
                declaration.allocation,
                exact(declaration.interval_share)
                if declaration.interval_share is not None
                else None,
            )
            domains[target] = TargetDomain(
                support=support, status=part.allocation_status, projection=part
            )
    return domains


def project_source_grid(plan_path: Path, query: SourceGridQuery) -> SourceGrid:
    """Replay source and recall, infer the declared model and project onto the selected axis."""
    query = _validate_instance(SourceGridQuery, query)
    replay = replay_analysis_file(plan_path)
    axis, supports = _source_geometry(replay, query)
    index = BinIndex(
        bins=query.bins,
        start=tick(axis.start_tick) if isinstance(axis, MediaSource) else 0,
        end=tick(axis.end_tick),
    )
    recall = _recall_before_pairing(replay)
    inference = _resolve_account_inference(replay.source.account, recall, query.inference, None)
    domains = _target_domains(inference, query, index, supports)
    projected = {t: d.projection for t, d in domains.items() if d.projection is not None}
    exposure = _exposure_from_replay(replay, query.exposure) if query.exposure is not None else None
    durations = (
        {r.inventory.unit: Fraction(r.duration_seconds) for r in exposure.units} if exposure else {}
    )
    rows = []
    for row, posterior in zip(
        inference.evidence.sequence.request.rows, inference.inference.posterior, strict=True
    ):
        states = {c.state.id: c.state for c in row.costs}
        weights = tuple((states[m.state], Fraction(m.probability)) for m in posterior.mass)
        ledger, cells = _project_mass(weights, projected)
        duration = durations[row.unit] if exposure else None
        rows.append(
            UnitSourceGrid(
                unit=row.unit,
                status="computed",
                ledger=ledger,
                cells=cells,
                duration_seconds=duration,
                weighted_seconds={i: q.scaled(duration) for i, q in cells.items() if duration}
                if duration is not None
                else None,
            )
        )
    selected = {row.unit for row in rows}
    for unit in recall.units:
        if unit.kind == "recall" and unit.id not in selected:
            rows.append(
                UnitSourceGrid(
                    unit=unit.id,
                    status="outside-inference-selection",
                    ledger=None,
                    cells=None,
                    duration_seconds=durations.get(unit.id),
                    weighted_seconds=None,
                )
            )
    return SourceGrid(
        query=query,
        analysis_sha256=exposure.analysis_sha256
        if exposure
        else digest(replay.model_dump(mode="json")),
        axis=axis,
        inference=inference,
        exposure=exposure,
        domains=domains,
        rows=tuple(rows),
    )
