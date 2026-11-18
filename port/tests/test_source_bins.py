from fractions import Fraction

import pytest
from pydantic import ValidationError

from storymodel._source_bins import BinIndex
from storymodel.playback import PlaybackSupport, TickInterval


def _support(
    intervals: tuple[tuple[int, int], ...] = (), points: tuple[int, ...] = ()
) -> PlaybackSupport:
    return PlaybackSupport(
        source="media-source:test",
        intervals=tuple(TickInterval(start=str(lo), end=str(hi)) for lo, hi in intervals),
        points=tuple(str(point) for point in points),
    )


def test_mixture_retains_internal_points_gaps_outside_and_original_bin_indexes() -> None:
    index = BinIndex(
        bins=(
            TickInterval(start="20", end="30"),
            TickInterval(start="0", end="10"),
            TickInterval(start="12", end="18"),
        ),
        start=0,
        end=30,
    )
    result = index.project(
        _support(intervals=((0, 5), (15, 25)), points=(3, 17, 29)),
        "mixture",
        Fraction(2, 3),
    )
    assert result.allocation_status == "allocated"
    assert {i: (cell.contained, cell.fraction) for i, cell in result.cells.items()} == {
        0: (False, Fraction(1, 3)),
        1: (False, Fraction(1, 3)),
        2: (False, Fraction(11, 45)),
    }
    assert result.outside == Fraction(4, 45)


def test_single_bin_containment_is_lower_bound_and_half_open_points_do_not_leak() -> None:
    index = BinIndex(
        bins=(TickInterval(start="0", end="30"), TickInterval(start="30", end="40")),
        start=0,
        end=40,
    )
    result = index.project(_support(intervals=((5, 10),), points=(10,)), "mixture", Fraction(1, 2))
    assert {i: (cell.contained, cell.fraction) for i, cell in result.cells.items()} == {
        0: (True, Fraction(1))
    }
    assert result.outside == 0
    point_at_boundary = index.project(_support(points=(30,)), "uniform-points")
    assert {i: cell.fraction for i, cell in point_at_boundary.cells.items()} == {1: Fraction(1)}


@pytest.mark.parametrize(
    ("allocation", "support", "share", "status"),
    [
        ("support-only", _support(intervals=((1, 2),)), None, "support-only"),
        ("uniform-intervals", _support(points=(1,)), None, "incompatible-geometry"),
        ("uniform-points", _support(intervals=((1, 2),)), None, "incompatible-geometry"),
        ("mixture", _support(intervals=((1, 2),)), Fraction(1, 2), "incompatible-geometry"),
    ],
)
def test_unallocated_statuses_keep_geometry_but_never_invent_fraction(
    allocation: str, support: PlaybackSupport, share: Fraction | None, status: str
) -> None:
    index = BinIndex(bins=(TickInterval(start="0", end="5"),), start=0, end=5)
    result = index.project(support, allocation, share)  # type: ignore[arg-type]
    assert result.allocation_status == status
    assert {i: (cell.contained, cell.fraction) for i, cell in result.cells.items()} == {
        0: (True, None)
    }
    assert result.outside is None


def test_large_ticks_and_sparse_projection_do_not_scan_empty_bins() -> None:
    big = 9_007_199_254_740_993
    index = BinIndex(
        bins=tuple(
            TickInterval(start=str(big + 2 * i), end=str(big + 2 * i + 1)) for i in range(10)
        ),
        start=big,
        end=big + 20,
    )
    result = index.project(_support(intervals=((big + 6, big + 7),)), "uniform-intervals")
    assert {i: (cell.contained, cell.fraction) for i, cell in result.cells.items()} == {
        3: (True, Fraction(1))
    }
    assert result.outside == 0


def test_sparse_interleaved_fragments_and_points_materialize_only_intersections() -> None:
    index = BinIndex(
        bins=tuple(TickInterval(start=str(2 * i), end=str(2 * i + 1)) for i in range(100_000)),
        start=0,
        end=200_000,
    )
    locations = tuple(range(1_000, 1_400, 8))
    result = index.project(
        _support(
            intervals=tuple((at, at + 1) for at in locations),
            points=tuple(at + 4 for at in locations),
        ),
        "mixture",
        Fraction(1, 2),
    )
    assert len(result.cells) == 100
    assert set(result.cells) == {at // 2 for at in locations} | {(at + 4) // 2 for at in locations}
    assert all(cell.fraction == Fraction(1, 100) for cell in result.cells.values())
    assert result.outside == 0


def test_axis_rejects_overlap_and_more_than_maximum_bins() -> None:
    with pytest.raises(ValidationError, match="nonoverlapping"):
        BinIndex(
            bins=(TickInterval(start="0", end="4"), TickInterval(start="3", end="5")),
            start=0,
            end=5,
        )
    repeated = TickInterval(start="0", end="1")
    with pytest.raises(ValidationError, match="too many bins"):
        BinIndex(bins=(repeated,) * 100_001, start=0, end=1)


def test_playback_model_refuses_empty_support() -> None:
    with pytest.raises(ValidationError, match="intervals or points"):
        PlaybackSupport(source="media-source:test")
