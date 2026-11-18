"""Sparse exact integration of interval exposure across named units."""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Iterable
from fractions import Fraction
from typing import Literal


def integrate_exposure(
    intervals: Iterable[tuple[str, int, int]],
    start: int,
    end: int,
    overlap: Literal["refuse", "equal-share"],
) -> tuple[dict[str, Fraction], Fraction]:
    """Integrate selected half-open ticks, sharing only distinct-unit overlap.

    Repeated intervals for one unit form a union.  Cross-unit overlap is checked
    over the entire supplied axis, including portions outside ``[start, end)``.
    """
    if end <= start:
        raise ValueError("selection needs positive duration")
    if overlap not in ("refuse", "equal-share"):
        raise ValueError("unknown overlap policy")

    events: dict[int, dict[str, list[int]]] = defaultdict(dict)
    for unit, interval_start, interval_end in intervals:
        if not unit:
            raise ValueError("exposure unit must be nonempty")
        if interval_end <= interval_start:
            raise ValueError("exposure interval needs positive duration")
        at_start = events[interval_start].setdefault(unit, [0, 0])
        at_start[0] += 1
        at_end = events[interval_end].setdefault(unit, [0, 0])
        at_end[1] += 1

    exposure: dict[str, Fraction] = {}
    active_depth: dict[str, int] = {}
    entered_at: dict[str, Fraction] = {}
    shared_clock = Fraction()
    uncovered = Fraction(end - start)
    previous: int | None = None

    for point in sorted(events):
        if previous is not None and point > previous:
            selected_ticks = max(0, min(point, end) - max(previous, start))
            active_units = len(active_depth)
            if overlap == "refuse" and active_units > 1:
                raise ValueError("cross-unit exposure overlaps")
            if selected_ticks and active_units:
                uncovered -= selected_ticks
                shared_clock += Fraction(selected_ticks, active_units)

        changes = events[point]
        # Apply all departures before arrivals at this coordinate. This makes
        # adjacent half-open intervals touch without overlapping.
        for unit, (_, departures) in changes.items():
            if not departures:
                continue
            remaining = active_depth[unit] - departures
            if remaining < 0:
                raise AssertionError("unbalanced exposure endpoint")
            if remaining == 0:
                exposure[unit] = (
                    exposure.get(unit, Fraction()) + shared_clock - entered_at.pop(unit)
                )
                del active_depth[unit]
            else:
                active_depth[unit] = remaining
        for unit, (arrivals, _) in changes.items():
            if not arrivals:
                continue
            if unit not in active_depth:
                active_depth[unit] = arrivals
                entered_at[unit] = shared_clock
            else:
                active_depth[unit] += arrivals
        previous = point

    return exposure, uncovered
