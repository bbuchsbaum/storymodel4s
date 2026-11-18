"""Sparse, exact projection of playback support onto one admitted time axis."""

from bisect import bisect_left
from collections import defaultdict
from fractions import Fraction
from typing import Literal, Self

from pydantic import PrivateAttr, model_validator

from ._base import Model, _validate_instance
from ._coordinates import tick
from .playback import PlaybackSupport, TickInterval

_Allocation = Literal["support-only", "uniform-intervals", "uniform-points", "mixture"]
_AllocationStatus = Literal["allocated", "support-only", "incompatible-geometry"]
_MAXIMUM_BINS = 100_000


class BinCell(Model):
    """One intersecting caller bin; containment is the lower bound for this support."""

    contained: bool
    fraction: Fraction | None


class BinProjection(Model):
    """Sparse projection keeps unassigned allocation outside instead of renormalizing cells."""

    allocation_status: _AllocationStatus
    cells: dict[int, BinCell]
    outside: Fraction | None


class BinIndex(Model):
    """Checked bins on one half-open integer axis, preserving caller bin indexes after sorting."""

    bins: tuple[TickInterval, ...]
    start: int
    end: int
    _ordered: tuple[tuple[int, int, int], ...] = PrivateAttr(default=())
    _ends: tuple[int, ...] = PrivateAttr(default=())

    @model_validator(mode="after")
    def checked(self) -> Self:
        if self.end <= self.start:
            raise ValueError("bin axis must have positive extent")
        if len(self.bins) > _MAXIMUM_BINS:
            raise ValueError("too many bins")
        ordered = tuple(
            sorted(
                (
                    (tick(interval.start), tick(interval.end), index)
                    for index, interval in enumerate(self.bins)
                ),
                key=lambda entry: (entry[0], entry[1], entry[2]),
            )
        )
        previous_end: int | None = None
        for lo, hi, _ in ordered:
            if not self.start <= lo < hi <= self.end:
                raise ValueError("bin escapes axis extent")
            if previous_end is not None and lo < previous_end:
                raise ValueError("bins must be nonoverlapping")
            previous_end = hi
        object.__setattr__(self, "_ordered", ordered)
        object.__setattr__(self, "_ends", tuple(hi for _, hi, _ in ordered))
        return self

    def project(
        self,
        support: PlaybackSupport,
        allocation: _Allocation,
        interval_share: Fraction | None = None,
    ) -> BinProjection:
        """Project support exactly without scanning every bin for every support primitive.

        Unordered caller bins are indexed once by their geometry; returned keys remain their
        original zero-based positions. Constructing the index is O(n log n), while each
        interval/point locates its candidates with binary search.
        """
        checked = _validate_instance(PlaybackSupport, support)
        allocation_status = _status(checked, allocation, interval_share)
        ordered = self._ordered
        interval_overlap: dict[int, int] = defaultdict(int)
        point_hits: dict[int, int] = defaultdict(int)
        total_duration = sum(
            (tick(interval.end) - tick(interval.start) for interval in checked.intervals), 0
        )
        total_points = len(checked.points)

        for interval in checked.intervals:
            lo, hi = tick(interval.start), tick(interval.end)
            position = bisect_left(self._ends, lo + 1)
            while position < len(ordered) and ordered[position][0] < hi:
                bin_start, bin_end, original = ordered[position]
                interval_overlap[original] += min(hi, bin_end) - max(lo, bin_start)
                position += 1
        for point in checked.points:
            position = bisect_left(self._ends, tick(point) + 1)
            if position < len(ordered):
                lo, hi, original = ordered[position]
                if lo <= tick(point) < hi:
                    point_hits[original] += 1

        cells: dict[int, BinCell] = {}
        for original in sorted(interval_overlap.keys() | point_hits.keys()):
            overlap = interval_overlap[original]
            hits = point_hits[original]
            contained = overlap == total_duration and hits == total_points
            fraction = (
                _allocated_fraction(
                    interval_overlap=overlap,
                    total_duration=total_duration,
                    point_hits=hits,
                    total_points=total_points,
                    allocation=allocation,
                    interval_share=interval_share,
                )
                if allocation_status == "allocated"
                else None
            )
            cells[original] = BinCell(contained=contained, fraction=fraction)
        outside = (
            Fraction(1) - sum((cell.fraction or Fraction() for cell in cells.values()), Fraction())
            if allocation_status == "allocated"
            else None
        )
        return BinProjection(
            allocation_status=allocation_status,
            cells=cells,
            outside=outside,
        )


def _status(
    support: PlaybackSupport, allocation: _Allocation, interval_share: Fraction | None
) -> _AllocationStatus:
    if allocation == "support-only":
        if interval_share is not None:
            raise ValueError("support-only allocation has no interval share")
        return "support-only"
    if allocation == "uniform-intervals":
        if interval_share is not None:
            raise ValueError("uniform-intervals allocation has no interval share")
        return "allocated" if support.intervals and not support.points else "incompatible-geometry"
    if allocation == "uniform-points":
        if interval_share is not None:
            raise ValueError("uniform-points allocation has no interval share")
        return "allocated" if support.points and not support.intervals else "incompatible-geometry"
    if allocation != "mixture":
        raise ValueError("unknown allocation")
    if interval_share is None or not Fraction() <= interval_share <= Fraction(1):
        raise ValueError("mixture allocation needs an interval share in [0, 1]")
    return "allocated" if support.intervals and support.points else "incompatible-geometry"


def _allocated_fraction(
    *,
    interval_overlap: int,
    total_duration: int,
    point_hits: int,
    total_points: int,
    allocation: _Allocation,
    interval_share: Fraction | None,
) -> Fraction:
    interval_fraction = Fraction(interval_overlap, total_duration) if total_duration else Fraction()
    point_fraction = Fraction(point_hits, total_points) if total_points else Fraction()
    if allocation == "uniform-intervals":
        return interval_fraction
    if allocation == "uniform-points":
        return point_fraction
    if allocation == "mixture":
        assert interval_share is not None
        return interval_share * interval_fraction + (Fraction(1) - interval_share) * point_fraction
    raise AssertionError("only allocated modes reach fraction")
