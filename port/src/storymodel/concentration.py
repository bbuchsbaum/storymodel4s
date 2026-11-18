"""Find connected source windows or sets of declared bins for one recall unit's measure."""

from collections import defaultdict
from collections.abc import Iterable, Iterator
from fractions import Fraction
from functools import cached_property
from pathlib import Path
from typing import Literal, Self

from pydantic import Field, field_validator, model_validator

from ._base import Digest, Model, Name, _validate_instance
from ._concentration import TickMass
from ._coordinates import exact, tick
from ._source_bins import BinIndex
from .playback import MediaSource, PlaybackSupport, TickInterval
from .sequence import SequenceState
from .serialization import digest
from .source_grid import (
    GridQuantity,
    LocalScoreGrid,
    LocalScoreGridQuery,
    SourceGrid,
    SourceGridQuery,
    SourceLedger,
    SupportDeclaration,
    TransportGrid,
    TransportGridQuery,
    UnitSourceGrid,
    _posterior_rows,
    _RowStatus,
    _score_rows,
    project_source_grid,
)


class CoverageTarget(Model):
    """Amount to capture, with an explicit denominator for fractional coverage."""

    basis: Literal["absolute", "share-of-supplied", "share-of-allocated-source"]
    value: str

    @model_validator(mode="after")
    def valid(self) -> Self:
        value = exact(self.value)
        if value < 0 or (self.basis != "absolute" and value > 1):
            raise ValueError("invalid concentration target")
        object.__setattr__(self, "value", str(value))
        return self

    def quantity(self, ledger: SourceLedger) -> Fraction | None:
        value = exact(self.value)
        if self.basis == "absolute":
            return value
        scale = ledger.supplied if self.basis == "share-of-supplied" else ledger.allocated_source
        return value * scale if scale else None


class BestWindow(Model):
    """Maximize the allocated mass inside a fixed number of native ticks."""

    kind: Literal["best-window"] = "best-window"
    width_ticks: str

    @field_validator("width_ticks")
    @classmethod
    def positive(cls, value: str) -> str:
        width = tick(value)
        if width <= 0:
            raise ValueError("window width must be positive")
        return str(width)


class ShortestWindow(Model):
    """Find the shortest connected interval reaching the requested amount."""

    kind: Literal["shortest-window"] = "shortest-window"
    target: CoverageTarget


class DisconnectedBins(Model):
    """Select the fewest declared bins reaching the target, largest mass first."""

    kind: Literal["disconnected-bins"] = "disconnected-bins"
    target: CoverageTarget


class ConcentrationQuery(Model):
    """Identify the original measure, source coordinate, recall unit and coverage question."""

    schema_version: Literal["storymodel.concentration-query/v1"] = (
        "storymodel.concentration-query/v1"
    )
    grid: SourceGridQuery | LocalScoreGridQuery | TransportGridQuery
    unit: Name
    operation: BestWindow | ShortestWindow | DisconnectedBins = Field(discriminator="kind")


class WindowContribution(Model):
    """An original alternative's weight and its support bounds within the selected window."""

    destination: SequenceState | Name
    weight: Fraction
    within: GridQuantity | None


class ConcentratedWindow(Model):
    """The empty region has width zero and no interval; every other region is half-open."""

    kind: Literal["window"] = "window"
    width_ticks: str
    interval: TickInterval | None
    needed: Fraction | None
    within: GridQuantity
    contributions: tuple[WindowContribution, ...]


class ConcentratedBins(Model):
    """Selected original bin positions and their achieved, unnormalized mass."""

    kind: Literal["bins"] = "bins"
    bins: tuple[int, ...]
    needed: Fraction
    achieved: Fraction


class ConcentrationRefusal(Model):
    """An absent measurement, undefined denominator or impossible coverage has its own reason."""

    kind: Literal["unavailable"] = "unavailable"
    reason: Literal[
        "measure-unavailable", "undefined-conditioning", "unavailable-allocation", "unattainable"
    ]
    needed: Fraction | None = None
    available: Fraction | None = None


class SourceConcentration(Model):
    """One replay-bound readout with the original row ledger and fit status."""

    schema_version: Literal["storymodel.source-concentration/v1"] = (
        "storymodel.source-concentration/v1"
    )
    query: ConcentrationQuery
    analysis_sha256: Digest
    grid_sha256: Digest
    measure: Literal["model-posterior", "normalized-score-mass", "transport-mass"]
    row_status: _RowStatus
    ledger: SourceLedger | None
    duration_seconds: Fraction | None
    joint_status: Literal["unavailable-without-joint-evidence"] = (
        "unavailable-without-joint-evidence"
    )
    result: ConcentratedWindow | ConcentratedBins | ConcentrationRefusal


type _Grid = SourceGrid | LocalScoreGrid | TransportGrid
type _Weights = tuple[tuple[SequenceState | str, Fraction], ...]


def _weights(grid: _Grid, unit: str) -> _Weights:
    if isinstance(grid, TransportGrid):
        return tuple((t, Fraction(w)) for t, w in grid.transport.iter_row(unit))
    rows = (
        _score_rows(grid.reference)
        if isinstance(grid, LocalScoreGrid)
        else _posterior_rows(grid.inference)
    )
    selected = next(row for row in rows if row.unit == unit)
    assert selected.weights is not None
    return tuple(selected.weights)


def _anchor(destination: SequenceState | str) -> str | None:
    return destination if isinstance(destination, str) else destination.anchor


def _segments(
    support: PlaybackSupport,
    declaration: SupportDeclaration,
    mass: Fraction,
) -> Iterator[tuple[int, int, Fraction]]:
    """Spread one allocated mass over the canonical interval union and individual point atoms."""
    if declaration.interval_share is not None:
        share = exact(declaration.interval_share)
    else:
        share = Fraction(bool(support.intervals))
    if support.intervals and share:
        intervals = [(tick(i.start), tick(i.end)) for i in support.intervals]
        rate = mass * share / sum(hi - lo for lo, hi in intervals)
        for lo, hi in intervals:
            yield lo, hi, rate * (hi - lo)
    if support.points and share < 1:
        per_point = mass * (1 - share) / len(support.points)
        for point in support.points:
            lo = tick(point)
            yield lo, lo + 1, per_point


def _allocated_segments(grid: _Grid, weights: _Weights) -> Iterable[tuple[int, int, Fraction]]:
    by_target: dict[str, Fraction] = defaultdict(Fraction)
    for dest, weight in weights:
        anchor = _anchor(dest)
        if anchor is not None and weight:
            by_target[anchor] += weight
    for target, mass in by_target.items():
        domain = grid.domains.get(target)
        if domain is not None and domain.status == "allocated":
            assert domain.support is not None
            yield from _segments(domain.support, grid.query.declarations[target], mass)


_ZERO = GridQuantity(allocated=Fraction(), lower=Fraction(), upper=Fraction())


def _window(
    grid: _Grid,
    weights: _Weights,
    axis_start: int,
    axis_end: int,
    start: int,
    width: int,
    achieved: Fraction,
    needed: Fraction | None,
) -> ConcentratedWindow:
    interval = TickInterval(start=str(start), end=str(start + width)) if width else None
    index = BinIndex(start=axis_start, end=axis_end, bins=(interval,) if interval else ())
    shares: dict[str, GridQuantity] = {}
    for target in {anchor for dest, _ in weights if (anchor := _anchor(dest)) is not None}:
        domain = grid.domains.get(target)
        if domain is None or domain.status != "allocated":
            continue
        assert domain.support is not None
        declaration = grid.query.declarations[target]
        projection = index.project(
            domain.support,
            declaration.allocation,
            exact(declaration.interval_share) if declaration.interval_share is not None else None,
        )
        cell = projection.cells.get(0)
        if cell is None:
            shares[target] = _ZERO
        else:
            assert cell.fraction is not None
            shares[target] = GridQuantity(
                allocated=cell.fraction, lower=Fraction(cell.contained), upper=Fraction(1)
            )
    contributions = []
    allocated = lower = upper = Fraction()
    for dest, weight in weights:
        anchor_id = _anchor(dest)
        share = shares.get(anchor_id) if anchor_id is not None else None
        quantity = share.scaled(weight) if share is not None else None
        if quantity is not None:
            allocated += quantity.allocated
            lower += quantity.lower
            upper += quantity.upper
        contributions.append(WindowContribution(destination=dest, weight=weight, within=quantity))
    if allocated != achieved:
        raise ValueError("window search and support allocation disagree")
    return ConcentratedWindow(
        width_ticks=str(width),
        interval=interval,
        needed=needed,
        within=GridQuantity(allocated=allocated, lower=lower, upper=upper),
        contributions=tuple(contributions),
    )


def _select_bins(row: UnitSourceGrid, needed: Fraction) -> ConcentratedBins | ConcentrationRefusal:
    assert row.ledger is not None and row.cells is not None
    available = row.ledger.allocated_source - row.ledger.allocated_outside
    if needed > available:
        return ConcentrationRefusal(reason="unattainable", needed=needed, available=available)
    total = Fraction()
    selected = []
    cells = row.cells
    for i in sorted(cells, key=lambda i: (-cells[i].allocated, i)):
        if total >= needed:
            break
        selected.append(i)
        total += cells[i].allocated
    return ConcentratedBins(bins=tuple(sorted(selected)), needed=needed, achieved=total)


class PreparedConcentration:
    """Replay one account measure once and ask several coverage questions of the same unit."""

    def __init__(
        self,
        plan_path: Path,
        grid: SourceGridQuery | LocalScoreGridQuery | TransportGridQuery,
        unit: str,
    ) -> None:
        self._grid = project_source_grid(plan_path, grid)
        row = next((r for r in self._grid.rows if r.unit == unit), None)
        if row is None:
            raise ValueError("unknown recall unit in concentration query")
        self._row = row
        axis = self._grid.axis
        self._lo = tick(axis.start_tick) if isinstance(axis, MediaSource) else 0
        self._hi = tick(axis.end_tick)
        self._digest = digest(self._grid.model_dump(mode="json"))

    @cached_property
    def _weights(self) -> _Weights:
        return _weights(self._grid, self._row.unit)

    @cached_property
    def _index(self) -> TickMass:
        index = TickMass.from_segments(
            self._lo, self._hi, _allocated_segments(self._grid, self._weights)
        )
        if self._row.ledger is None or index.total != self._row.ledger.allocated_source:
            raise ValueError("concentration geometry differs from the allocated source ledger")
        return index

    def query(
        self, operation: BestWindow | ShortestWindow | DisconnectedBins
    ) -> SourceConcentration:
        """Return the selected region or the exact reason this row cannot answer the question."""
        query = ConcentrationQuery(grid=self._grid.query, unit=self._row.unit, operation=operation)
        return SourceConcentration(
            query=query,
            analysis_sha256=self._grid.analysis_sha256,
            grid_sha256=self._digest,
            measure=self._grid.measure,
            row_status=self._row.status,
            ledger=self._row.ledger,
            duration_seconds=self._row.duration_seconds,
            result=self._select(query.operation),
        )

    def _select(
        self,
        operation: BestWindow | ShortestWindow | DisconnectedBins,
    ) -> ConcentratedWindow | ConcentratedBins | ConcentrationRefusal:
        if isinstance(operation, BestWindow) and tick(operation.width_ticks) > self._hi - self._lo:
            raise ValueError("window width exceeds source axis extent")
        ledger = self._row.ledger
        if ledger is None:
            return ConcentrationRefusal(reason="measure-unavailable")
        needed = None
        if not isinstance(operation, BestWindow):
            needed = operation.target.quantity(ledger)
            if needed is None:
                return ConcentrationRefusal(reason="undefined-conditioning")
        if ledger.support_without_allocation:
            return ConcentrationRefusal(reason="unavailable-allocation", needed=needed)
        if isinstance(operation, DisconnectedBins):
            assert needed is not None
            return _select_bins(self._row, needed)
        if needed is not None and needed > ledger.allocated_source:
            return ConcentrationRefusal(
                reason="unattainable", needed=needed, available=ledger.allocated_source
            )
        index = self._index
        if isinstance(operation, BestWindow):
            width = tick(operation.width_ticks)
            start, achieved = index.best(width)
        else:
            assert needed is not None
            start, width, achieved = index.shortest(needed)
        return _window(
            self._grid, self._weights, self._lo, self._hi, start, width, achieved, needed
        )


def source_concentration(plan_path: Path, query: ConcentrationQuery) -> SourceConcentration:
    """Answer one coverage question from its original analysis plan and query."""
    query = _validate_instance(ConcentrationQuery, query)
    return PreparedConcentration(plan_path, query.grid, query.unit).query(query.operation)
