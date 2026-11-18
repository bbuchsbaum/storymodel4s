"""Independent per-tick checks for sparse exact exposure integration."""

from __future__ import annotations

from fractions import Fraction

import pytest
from hypothesis import given
from hypothesis import strategies as st

from storymodel._exposure import integrate_exposure


def _oracle(
    intervals: list[tuple[str, int, int]], start: int, end: int
) -> tuple[dict[str, Fraction], Fraction]:
    """Direct tick oracle, deliberately unsuitable for large coordinates."""
    result: dict[str, Fraction] = {unit: Fraction() for unit, _, _ in intervals}
    uncovered = Fraction()
    for tick in range(start, end):
        units = {unit for unit, lo, hi in intervals if lo <= tick < hi}
        if not units:
            uncovered += 1
        else:
            share = Fraction(1, len(units))
            for unit in units:
                result[unit] = result.get(unit, Fraction()) + share
    return result, uncovered


def test_equal_share_matches_independent_per_tick_oracle():
    intervals = [("a", 0, 6), ("a", 2, 5), ("b", 1, 4), ("c", 3, 7)]
    assert integrate_exposure(intervals, 0, 8, "equal-share") == _oracle(intervals, 0, 8)


@given(
    st.lists(
        st.tuples(st.sampled_from(("a", "b", "c")), st.integers(-6, 6), st.integers(1, 6)),
        max_size=8,
    ),
    st.integers(-6, 6),
    st.integers(1, 6),
)
def test_equal_share_matches_oracle_for_small_generated_inputs(raw, start, width):
    intervals = [(unit, lo, lo + duration) for unit, lo, duration in raw]
    assert integrate_exposure(intervals, start, start + width, "equal-share") == _oracle(
        intervals, start, start + width
    )


def test_input_order_does_not_change_exact_result():
    intervals = [("a", 0, 5), ("b", 2, 7), ("c", 4, 6), ("a", 1, 3)]
    forward = integrate_exposure(intervals, 0, 8, "equal-share")
    assert integrate_exposure(list(reversed(intervals)), 0, 8, "equal-share") == forward


def test_three_units_split_ticks_into_exact_thirds():
    exposure, uncovered = integrate_exposure(
        [("a", 0, 1), ("b", 0, 1), ("c", 0, 1)], 0, 1, "equal-share"
    )
    assert exposure == {"a": Fraction(1, 3), "b": Fraction(1, 3), "c": Fraction(1, 3)}
    assert uncovered == 0


def test_touching_intervals_do_not_overlap_and_same_unit_unions():
    intervals = [("a", 0, 2), ("b", 3, 4), ("a", 1, 3)]
    assert integrate_exposure(intervals, 0, 4, "refuse") == (
        {"a": Fraction(3), "b": Fraction(1)},
        0,
    )


def test_refusal_checks_cross_unit_overlap_outside_selected_window():
    with pytest.raises(ValueError, match="cross-unit"):
        integrate_exposure([("a", 0, 3), ("b", 1, 2)], 10, 11, "refuse")


def test_big_coordinates_are_not_expanded_to_ticks():
    coordinate = 10**18
    assert integrate_exposure(
        [("a", coordinate, coordinate + 9), ("b", coordinate + 3, coordinate + 6)],
        coordinate,
        coordinate + 9,
        "equal-share",
    ) == ({"a": Fraction(15, 2), "b": Fraction(3, 2)}, 0)


def test_empty_selection_and_invalid_inputs():
    with pytest.raises(ValueError, match="positive"):
        integrate_exposure([("a", 0, 1)], 4, 4, "equal-share")
    with pytest.raises(ValueError, match="positive"):
        integrate_exposure([("a", 1, 1)], 0, 2, "equal-share")
    with pytest.raises(ValueError, match="positive"):
        integrate_exposure([], 2, 1, "equal-share")


def test_window_wholly_outside_intervals_is_uncovered():
    assert integrate_exposure([("a", 10, 11)], 0, 4, "equal-share") == (
        {"a": Fraction()},
        Fraction(4),
    )
