"""Exact interval projections with explicit quantity meaning and measured coverage."""

from fractions import Fraction
from typing import Literal, Self

from pydantic import model_validator

from .model import Model


def rational(value: str) -> Fraction:
    """Accept exact integer/fraction strings, avoiding JSON/JavaScript precision loss."""
    if not isinstance(value, str) or not value or any(c not in "-0123456789/" for c in value):
        raise ValueError("expected an exact integer or fraction string")
    try:
        return Fraction(value)
    except (ValueError, ZeroDivisionError) as error:
        raise ValueError("invalid exact fraction") from error


class Interval(Model):
    """A measured half-open interval on a named clock; point events are not durations."""

    clock: str
    start: str
    end: str

    @model_validator(mode="after")
    def ordered(self) -> Self:
        if not self.clock or rational(self.end) <= rational(self.start):
            raise ValueError("interval needs a clock and strictly positive duration")
        return self


class TemporalCell(Model):
    interval: Interval
    overlap: str
    coverage: str
    value: str | None


def allocate(
    value: str,
    support: Interval,
    bins: tuple[Interval, ...],
    mode: Literal["held-level", "uniform-amount"],
) -> tuple[TemporalCell, ...]:
    """Hold a level on observed overlap, or distribute an amount over its support.

    A held-level cell is the mean level over its observed portion, not an integral or
    an imputed whole-bin value. Every cell reports coverage; no overlap is unavailable.
    Bins must be ordered and nonoverlapping. Uncovered support remains unallocated.
    """
    quantity = rational(value)
    checked = Interval.model_validate(support)
    start, end = rational(checked.start), rational(checked.end)
    if mode not in ("held-level", "uniform-amount"):
        raise ValueError("unknown allocation")
    result: list[TemporalCell] = []
    previous: Fraction | None = None
    for raw in bins:
        cell = Interval.model_validate(raw)
        lo, hi = rational(cell.start), rational(cell.end)
        if cell.clock != checked.clock:
            raise ValueError("foreign clock")
        if previous is not None and lo < previous:
            raise ValueError("bins must be ordered and nonoverlapping")
        previous = hi
        overlap = max(Fraction(0), min(end, hi) - max(start, lo))
        measured = quantity if mode == "held-level" else quantity * overlap / (end - start)
        result.append(
            TemporalCell(
                interval=cell,
                overlap=str(overlap),
                coverage=str(overlap / (hi - lo)),
                value=str(measured) if overlap else None,
            )
        )
    return tuple(result)
