from __future__ import annotations

from fractions import Fraction
from random import Random

import pytest

from storymodel._concentration import TickMass


def _brute(start: int, end: int, segments: list[tuple[int, int, Fraction]]) -> list[Fraction]:
    ticks = [Fraction() for _ in range(end - start)]
    for left, right, mass in segments:
        density = mass / (right - left)
        for tick in range(left, right):
            ticks[tick - start] += density
    return ticks


def _brute_best(ticks: list[Fraction], start: int, width: int) -> tuple[int, Fraction]:
    windows = [
        (start + offset, sum(ticks[offset : offset + width], Fraction()))
        for offset in range(len(ticks) - width + 1)
    ]
    return max(windows, key=lambda item: (item[1], -item[0]))


def test_sparse_queries_match_independent_tick_oracle() -> None:
    random = Random(7901)
    for _ in range(80):
        start, end = 3, random.randrange(7, 15)
        segments = [
            (
                left := random.randrange(start, end),
                random.randrange(left + 1, end + 1),
                Fraction(random.randrange(5), random.randrange(1, 5)),
            )
            for _ in range(random.randrange(1, 8))
        ]
        mass = TickMass.from_segments(start, end, segments)
        ticks = _brute(start, end, segments)
        assert mass.total == sum(ticks, Fraction())
        for width in range(1, end - start + 1):
            assert mass.best(width) == _brute_best(ticks, start, width)
        for needed in {Fraction(), *ticks, mass.total / 2, mass.total}:
            if needed == 0:
                assert mass.shortest(needed) == (start, 0, Fraction())
                continue
            expected = next(
                width
                for width in range(1, len(ticks) + 1)
                if _brute_best(ticks, start, width)[1] >= needed
            )
            window_start, width, achieved = mass.shortest(needed)
            assert width == expected
            assert (window_start, achieved) == _brute_best(ticks, start, width)


def test_overlaps_points_and_tied_peaks_choose_earliest() -> None:
    mass = TickMass.from_segments(
        10,
        20,
        [
            (10, 11, Fraction(3)),
            (12, 15, Fraction(6)),
            (13, 14, Fraction(3)),
            (16, 17, Fraction(5)),
        ],
    )
    assert mass.total == Fraction(17)
    assert mass.best(1) == (13, Fraction(5))
    assert mass.best(3) == (12, Fraction(9))
    assert mass.shortest(Fraction(5)) == (13, 1, Fraction(5))


def test_large_coordinates_remain_sparse() -> None:
    origin = 10**15
    mass = TickMass.from_segments(
        origin,
        origin + 10**12,
        [
            (origin + 100, origin + 101, Fraction(2)),
            (origin + 10**12 - 1, origin + 10**12, Fraction(2)),
        ],
    )
    assert len(mass._edges) == 5
    assert mass.best(1) == (origin + 100, Fraction(2))
    assert mass.shortest(Fraction(2)) == (origin + 100, 1, Fraction(2))


def test_negative_origin_is_a_translation_of_the_same_lattice() -> None:
    segments = [(0, 2, Fraction(3)), (1, 4, Fraction(6)), (5, 6, Fraction(4))]
    translated_segments = [(left - 20, right - 20, amount) for left, right, amount in segments]
    original = TickMass.from_segments(0, 8, segments)
    translated = TickMass.from_segments(-20, -12, translated_segments)
    assert translated.total == original.total
    for width in range(1, 9):
        start, amount = original.best(width)
        assert translated.best(width) == (start - 20, amount)
    start, width, amount = original.shortest(Fraction(4))
    assert translated.shortest(Fraction(4)) == (start - 20, width, amount)


@pytest.mark.parametrize(
    ("start", "end", "segments"),
    [
        (2, 2, []),
        (2, 4, [(2, 2, Fraction())]),
        (2, 4, [(1, 3, Fraction())]),
        (2, 4, [(2, 3, Fraction(-1))]),
    ],
)
def test_invalid_extent_or_segment_is_rejected(
    start: int, end: int, segments: list[tuple[int, int, Fraction]]
) -> None:
    with pytest.raises(ValueError):
        TickMass.from_segments(start, end, segments)


def test_invalid_queries_and_unattainable_target_are_rejected() -> None:
    mass = TickMass.from_segments(0, 2, [(0, 1, Fraction(1))])
    with pytest.raises(ValueError):
        mass.best(0)
    with pytest.raises(ValueError):
        mass.shortest(Fraction(-1))
    with pytest.raises(ValueError):
        mass.shortest(Fraction(2))
