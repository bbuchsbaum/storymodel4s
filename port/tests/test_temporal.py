"""Exact arithmetic and measurement semantics, with independent expected values."""

from fractions import Fraction

import pytest
from hypothesis import given
from hypothesis import strategies as st

from storymodel.temporal import Interval, allocate


def interval(start, end, clock="recall"):
    return Interval(clock=clock, start=str(start), end=str(end))


def test_holding_level_differs_from_distributing_amount():
    support = interval(0, 4)
    bins = (interval(0, 1), interval(1, 4), interval(4, 5))
    held = allocate("12", support, bins, "held-level")
    amount = allocate("12", support, bins, "uniform-amount")
    assert [x.value for x in held] == ["12", "12", None]
    assert [x.value for x in amount] == ["3", "9", None]
    assert [x.coverage for x in amount] == ["1", "1", "0"]


def test_partial_coverage_does_not_impute_whole_bin_level():
    result = allocate("3", interval(1, 2), (interval(0, 2),), "held-level")[0]
    assert (result.value, result.overlap, result.coverage) == ("3", "1", "1/2")


def test_large_ticks_and_rational_times_are_exact():
    tick = 2**60
    bins = (interval(tick, tick + 1), interval(tick + 1, tick + 3))
    result = allocate("1/3", interval(tick, tick + 3), bins, "uniform-amount")
    assert [c.value for c in result] == ["1/9", "2/9"]
    assert result[0].interval.start == str(tick)


@given(st.integers(min_value=1, max_value=1000), st.integers(min_value=-1000, max_value=1000))
def test_uniform_amount_is_conserved_on_a_complete_partition(width, value):
    result = allocate(
        str(value),
        interval(0, width * 2),
        (interval(0, width), interval(width, 2 * width)),
        "uniform-amount",
    )
    assert sum(Fraction(c.value) for c in result) == value


@pytest.mark.parametrize("bad", ["NaN", "Infinity", "1.5", "1/0", "", "1e3"])
def test_ambiguous_or_nonfinite_quantity_refuses(bad):
    with pytest.raises(ValueError):
        allocate(bad, interval(0, 1), (interval(0, 1),), "held-level")


def test_foreign_clock_and_overlapping_bins_refuse():
    with pytest.raises(ValueError, match="foreign clock"):
        allocate("1", interval(0, 1), (interval(0, 1, "film"),), "held-level")
    with pytest.raises(ValueError, match="nonoverlapping"):
        allocate("1", interval(0, 4), (interval(0, 3), interval(2, 4)), "held-level")
    with pytest.raises(ValueError, match="positive duration"):
        interval(1, 1)
