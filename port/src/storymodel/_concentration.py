"""Exact sparse concentration queries over an integer tick lattice."""

from __future__ import annotations

from bisect import bisect_right
from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass
from fractions import Fraction


@dataclass(frozen=True)
class TickMass:
    """Why: query interval mass without materializing every tick in its extent."""

    start: int
    end: int
    _edges: tuple[int, ...]
    _densities: tuple[Fraction, ...]
    _prefix: tuple[Fraction, ...]
    total: Fraction

    @classmethod
    def from_segments(
        cls,
        start: int,
        end: int,
        segments: Iterable[tuple[int, int, Fraction]],
    ) -> TickMass:
        """Create a mass field from uniformly distributed half-open segments."""
        _require_tick("start", start)
        _require_tick("end", end)
        if end <= start:
            raise ValueError("extent must be a nonempty half-open interval")

        changes: defaultdict[int, Fraction] = defaultdict(Fraction)
        for left, right, mass in segments:
            _require_tick("segment start", left)
            _require_tick("segment end", right)
            if not isinstance(mass, Fraction):
                raise TypeError("segment mass must be a Fraction")
            if mass < 0:
                raise ValueError("segment mass must be nonnegative")
            if left < start or right > end or right <= left:
                raise ValueError("segment must be a positive range inside the extent")
            density = mass / (right - left)
            changes[left] += density
            changes[right] -= density

        edges = tuple(sorted({start, end, *changes}))
        densities: list[Fraction] = []
        prefix: list[Fraction] = [Fraction()]
        current = Fraction()
        for index, edge in enumerate(edges[:-1]):
            current += changes[edge]
            following = edges[index + 1]
            densities.append(current)
            prefix.append(prefix[-1] + current * (following - edge))
        return cls(start, end, edges, tuple(densities), tuple(prefix), prefix[-1])

    def best(self, width: int) -> tuple[int, Fraction]:
        """Return the earliest maximum-mass window of ``width`` ticks."""
        self._check_width(width)
        latest = self.end - width
        candidates = {self.start, latest}
        for edge in self._edges:
            for candidate in (edge, edge - width):
                if self.start <= candidate <= latest:
                    candidates.add(candidate)

        best_start = self.start
        best_mass = self.mass_between(best_start, best_start + width)
        for candidate in sorted(candidates):
            amount = self.mass_between(candidate, candidate + width)
            if amount > best_mass:
                best_start, best_mass = candidate, amount
        return best_start, best_mass

    def shortest(self, needed: Fraction) -> tuple[int, int, Fraction]:
        """Return the shortest window reaching ``needed``, or reject impossible targets."""
        if not isinstance(needed, Fraction):
            raise TypeError("needed mass must be a Fraction")
        if needed < 0:
            raise ValueError("needed mass must be nonnegative")
        if needed == 0:
            return self.start, 0, Fraction()
        if needed > self.total:
            raise ValueError("needed mass exceeds total mass")

        low, high = 1, self.end - self.start
        while low < high:
            width = (low + high) // 2
            if self.best(width)[1] >= needed:
                high = width
            else:
                low = width + 1
        window_start, achieved = self.best(low)
        return window_start, low, achieved

    def mass_between(self, left: int, right: int) -> Fraction:
        """Return exact mass in a valid half-open subinterval."""
        if left < self.start or right > self.end or right < left:
            raise ValueError("interval must be inside the extent")
        return self._integral(right) - self._integral(left)

    def _integral(self, tick: int) -> Fraction:
        if tick == self.end:
            return self.total
        index = bisect_right(self._edges, tick) - 1
        return self._prefix[index] + self._densities[index] * (tick - self._edges[index])

    def _check_width(self, width: int) -> None:
        _require_tick("width", width)
        if width <= 0 or width > self.end - self.start:
            raise ValueError("width must be within the nonempty extent")


def _require_tick(name: str, value: int) -> None:
    if isinstance(value, bool) or not isinstance(value, int):
        raise TypeError(f"{name} must be an integer tick")
