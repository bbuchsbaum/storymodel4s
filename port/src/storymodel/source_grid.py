"""Project an account posterior onto declared source-time supports and a recall window."""

from collections import defaultdict
from collections.abc import Iterable, Iterator
from dataclasses import dataclass
from fractions import Fraction
from pathlib import Path
from typing import Literal, Self, TypedDict, overload

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
from .local_reference import LocalReferenceResult, LocalReferenceSpec, local_reference
from .playback import MediaSource, PlaybackSupport, TickInterval, contains_playback
from .prepared_construction import PreparedConstruction, PreparedConstructionResult
from .sequence import SequenceState
from .serialization import digest
from .sherlock_composition import _derive, _native_row_supports
from .sherlock_source import SherlockSource
from .source_transitions import SourceProjection
from .transport import TransportAlignment, TransportSpec, align_transport


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


class _GridGeometry(Model):
    """Disjoint query bins and the explicit location assumptions shared by both measures."""

    selection: Literal["part", "composition"]
    axis: Name
    bins: tuple[TickInterval, ...] = Field(max_length=100_000)
    declarations: dict[Name, SupportDeclaration]
    exposure: ExposureQuery | None = None


class SourceGridQuery(_GridGeometry):
    """Project a sequence posterior onto one native part or a complete Sherlock composition."""

    schema_version: Literal["storymodel.source-grid-query/v1"] = "storymodel.source-grid-query/v1"
    inference: RefinedInferenceSpec | LocalEvidenceSpec


class LocalScoreGridQuery(_GridGeometry):
    """Project independent local scores over the admitted source and external destinations."""

    schema_version: Literal["storymodel.local-score-grid-query/v1"] = (
        "storymodel.local-score-grid-query/v1"
    )
    reference: LocalReferenceSpec

    @model_validator(mode="after")
    def includes_external_destinations(self) -> Self:
        if self.reference.population != "supplied-destinations":
            raise ValueError(
                "source grid requires scores over supplied destinations, including external states"
            )
        return self


class TransportGridQuery(_GridGeometry):
    """Project the achieved source mass of an account transport plan into film coordinates."""

    schema_version: Literal["storymodel.transport-grid-query/v1"] = (
        "storymodel.transport-grid-query/v1"
    )
    transport: TransportSpec


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


type _RowStatus = Literal[
    "computed",
    "outside-inference-selection",
    "local-reference-unavailable",
    "no-transport-candidates",
    "transport-iteration-limit",
]


class UnitSourceGrid(Model):
    """Sparse bins; absent bins have zero mass in a computed row."""

    unit: Name
    status: _RowStatus
    ledger: SourceLedger | None
    cells: dict[int, GridQuantity] | None
    duration_seconds: Fraction | None
    weighted_seconds: dict[int, GridQuantity] | None


class _GridReadout(Model):
    analysis_sha256: Digest
    axis: MediaSource | PlaybackComposition
    exposure: RecallExposure | None
    domains: dict[str, TargetDomain]
    rows: tuple[UnitSourceGrid, ...]


class SourceGrid(_GridReadout):
    """Source-bin quantities with their original posterior and optional recall exposure."""

    schema_version: Literal["storymodel.source-grid/v1"] = "storymodel.source-grid/v1"
    measure: Literal["model-posterior"] = "model-posterior"
    query: SourceGridQuery
    inference: RefinedAccountInference | AccountInferenceResult


class LocalScoreGrid(_GridReadout):
    """The local evidence, score population and unavailable rows that produced the grid."""

    schema_version: Literal["storymodel.local-score-grid/v1"] = "storymodel.local-score-grid/v1"
    measure: Literal["normalized-score-mass"] = "normalized-score-mass"
    query: LocalScoreGridQuery
    reference: LocalReferenceResult


class TransportGrid(_GridReadout):
    """Transported mass and declared budgets with the fitted baseline and optional exposure."""

    schema_version: Literal["storymodel.transport-grid/v1"] = "storymodel.transport-grid/v1"
    measure: Literal["transport-mass"] = "transport-mass"
    query: TransportGridQuery
    transport: TransportAlignment


def _source_geometry(
    replay: AnalysisReplay, query: _GridGeometry
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
    weights: Iterable[tuple[SequenceState | str, Fraction]],
    projected: dict[str, BinProjection],
    row_budget: Fraction = Fraction(1),
) -> tuple[SourceLedger, dict[int, GridQuantity]]:
    """Project each admitted state independently, retaining its original posterior weight."""
    supplied = allocated = unallocated = unresolved = outside = Fraction()
    external: dict[str, Fraction] = defaultdict(Fraction)
    amounts: dict[int, Fraction] = defaultdict(Fraction)
    lower: dict[int, Fraction] = defaultdict(Fraction)
    upper: dict[int, Fraction] = defaultdict(Fraction)
    for state, mass in weights:
        supplied += mass
        if isinstance(state, SequenceState):
            if state.external is not None:
                external[state.external] += mass
                continue
            target = state.anchor
        else:
            target = state
        grid = projected.get(target) if target is not None else None
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
        supplied=supplied,
        row_budget=row_budget,
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
    projection: SourceProjection,
    measured_targets: set[str],
    query: _GridGeometry,
    index: BinIndex,
    supports: dict[str, PlaybackSupport],
) -> dict[str, TargetDomain]:
    known = {node.anchor for node in projection.nodes}
    if query.declarations.keys() - known:
        raise ValueError("source grid declaration names a foreign target")
    domains = {
        t: TargetDomain(status="no-declaration", support=None)
        for t in sorted(measured_targets - query.declarations.keys())
    }
    children: dict[str, list[str]] = defaultdict(list)
    for node in projection.nodes:
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


@dataclass(frozen=True)
class _MeasureRow:
    unit: str
    status: _RowStatus
    weights: Iterable[tuple[SequenceState | str, Fraction]] | None
    budget: Fraction = Fraction(1)


def _posterior_rows(
    inference: RefinedAccountInference | AccountInferenceResult,
) -> Iterator[_MeasureRow]:
    for row, posterior in zip(
        inference.evidence.sequence.request.rows, inference.inference.posterior, strict=True
    ):
        states = {c.state.id: c.state for c in row.costs}
        yield _MeasureRow(
            row.unit,
            "computed",
            tuple((states[m.state], Fraction(m.probability)) for m in posterior.mass),
        )


def _score_rows(reference: LocalReferenceResult) -> Iterator[_MeasureRow]:
    for row in reference.rows:
        if row.status == "computed":
            yield _MeasureRow(
                row.unit, "computed", tuple((m.state, Fraction(m.weight)) for m in row.mass)
            )
        else:
            yield _MeasureRow(row.unit, "local-reference-unavailable", None)


def _transport_rows(plan: TransportAlignment) -> Iterator[_MeasureRow]:
    for i, row in enumerate(plan.retrieval):
        if plan.scaling is None:
            yield _MeasureRow(row.unit, "no-transport-candidates", None)
        else:
            yield _MeasureRow(
                row.unit,
                "computed" if plan.scaling.converged else "transport-iteration-limit",
                ((anchor, Fraction(mass)) for anchor, mass in plan.iter_row(i)),
                Fraction(plan.row_targets[i]),
            )


def _include_unselected(
    measures: Iterable[_MeasureRow], units: Iterable[str]
) -> Iterator[_MeasureRow]:
    selected = set()
    for row in measures:
        selected.add(row.unit)
        yield row
    for unit in units:
        if unit not in selected:
            yield _MeasureRow(unit, "outside-inference-selection", None)


class _GridFields(TypedDict):
    analysis_sha256: Digest
    axis: MediaSource | PlaybackComposition
    exposure: RecallExposure | None
    domains: dict[str, TargetDomain]
    rows: tuple[UnitSourceGrid, ...]


@overload
def project_source_grid(plan_path: Path, query: SourceGridQuery) -> SourceGrid: ...


@overload
def project_source_grid(plan_path: Path, query: LocalScoreGridQuery) -> LocalScoreGrid: ...


@overload
def project_source_grid(plan_path: Path, query: TransportGridQuery) -> TransportGrid: ...


def project_source_grid(
    plan_path: Path, query: SourceGridQuery | LocalScoreGridQuery | TransportGridQuery
) -> SourceGrid | LocalScoreGrid | TransportGrid:
    """Replay both accounts and project the selected measure with its own mass and budget."""
    if isinstance(query, TransportGridQuery):
        query = _validate_instance(TransportGridQuery, query)
    elif isinstance(query, LocalScoreGridQuery):
        query = _validate_instance(LocalScoreGridQuery, query)
    else:
        query = _validate_instance(SourceGridQuery, query)
    replay = replay_analysis_file(plan_path)
    axis, supports = _source_geometry(replay, query)
    index = BinIndex(
        bins=query.bins,
        start=tick(axis.start_tick) if isinstance(axis, MediaSource) else 0,
        end=tick(axis.end_tick),
    )
    recall = _recall_before_pairing(replay)
    exposure = _exposure_from_replay(replay, query.exposure) if query.exposure is not None else None
    durations = (
        {r.inventory.unit: Fraction(r.duration_seconds) for r in exposure.units} if exposure else {}
    )

    def place(
        projection: SourceProjection, targets: set[str], measures: Iterable[_MeasureRow]
    ) -> _GridFields:
        domains = _target_domains(projection, targets, query, index, supports)
        projected = {t: d.projection for t, d in domains.items() if d.projection is not None}
        rows = []
        for entry in _include_unselected(
            measures, (u.id for u in recall.units if u.kind == "recall")
        ):
            duration = durations.get(entry.unit)
            ledger, cells = (
                _project_mass(entry.weights, projected, entry.budget)
                if entry.weights is not None
                else (None, None)
            )
            rows.append(
                UnitSourceGrid(
                    unit=entry.unit,
                    status=entry.status,
                    ledger=ledger,
                    cells=cells,
                    duration_seconds=duration,
                    weighted_seconds={i: q.scaled(duration) for i, q in cells.items() if duration}
                    if duration is not None and cells is not None
                    else None,
                )
            )
        return _GridFields(
            analysis_sha256=exposure.analysis_sha256
            if exposure
            else digest(replay.model_dump(mode="json")),
            axis=axis,
            exposure=exposure,
            domains=domains,
            rows=tuple(rows),
        )

    if isinstance(query, TransportGridQuery):
        plan = align_transport(replay.source.account, recall, query.transport)
        return TransportGrid(
            query=query,
            transport=plan,
            **place(plan.projection, set(plan.columns), _transport_rows(plan)),
        )
    if isinstance(query, LocalScoreGridQuery):
        reference = local_reference(replay.source.account, recall, query.reference)
        targets = {
            m.state.anchor for row in reference.rows for m in row.mass if m.state.anchor is not None
        }
        return LocalScoreGrid(
            query=query,
            reference=reference,
            **place(reference.projection, targets, _score_rows(reference)),
        )
    inference = _resolve_account_inference(replay.source.account, recall, query.inference, None)
    targets = {
        c.state.anchor
        for r in inference.evidence.sequence.request.rows
        for c in r.costs
        if c.state.anchor is not None
    }
    return SourceGrid(
        query=query,
        inference=inference,
        **place(inference.evidence.sequence.projection, targets, _posterior_rows(inference)),
    )
