"""Independent native-support oracles keep physical points, gaps and clocks distinct."""

from fractions import Fraction

import pytest
from hypothesis import given
from hypothesis import strategies as st

from storymodel.playback import (
    MediaSource,
    PlaybackSupport,
    TickInterval,
    contains_playback,
    project_playback,
    union_playback,
    validate_playback,
)


def source(**changes):
    return MediaSource(
        **{
            "key": "control",
            "side": "source",
            "sha256": "a" * 64,
            "edition": "edition-A",
            "stream": "picture-A",
            "clock": "primary-A",
            "stream_kind": "picture",
            "start_tick": "-10",
            "end_tick": "100",
            "seconds_per_tick": "1/3",
            **changes,
        }
    )


def support(media, intervals=(), points=()):
    return PlaybackSupport(
        source=media.id,
        intervals=tuple(TickInterval(start=str(a), end=str(b)) for a, b in intervals),
        points=tuple(str(p) for p in points),
    )


def test_union_retains_gaps_and_explicit_points_inside_intervals():
    media = source()
    a = support(media, [(0, 5), (20, 30)], [2, 10])
    b = support(media, [(5, 10), (22, 25)], [2, 25])
    result = union_playback((b, a))
    assert result.intervals == (
        TickInterval(start="0", end="10"),
        TickInterval(start="20", end="30"),
    )
    assert result.points == ("2", "10", "25")
    assert contains_playback(support(media, [(22, 25)]), result)
    assert not contains_playback(support(media, [(8, 22)]), result)
    assert contains_playback(support(media, points=[10]), result)
    assert not contains_playback(support(media, [(10, 11)]), result)
    assert not contains_playback(support(media, points=[30]), result)


def test_projection_measures_union_not_hull_and_never_gives_points_duration():
    media = source()
    envelope = support(media, [(0, 10), (20, 30)], [2, 10, 25])
    bins = tuple(TickInterval(start=str(i), end=str(i + 10)) for i in (0, 10, 20))
    result = project_playback(media, envelope, bins)
    assert result.duration_seconds == "20/3"
    assert [c.duration_seconds for c in result.cells] == ["10/3", "0/1", "10/3"]
    assert [c.coverage for c in result.cells] == ["1/1", "0/1", "1/1"]
    assert [c.points for c in result.cells] == [("2",), ("10",), ("25",)]
    point_only = project_playback(media, support(media, points=[10]), bins)
    assert point_only.duration_seconds == "0/1"
    assert [c.overlap_ticks for c in point_only.cells] == ["0", "0", "0"]
    assert point_only.cells[1].points == ("10",)


@pytest.mark.parametrize(
    "change",
    [
        {"edition": "other"},
        {"clock": "other"},
        {"stream": "other"},
        {"sha256": "b" * 64},
        {"side": "recall"},
        {"seconds_per_tick": "1/4"},
    ],
)
def test_same_numeric_coordinates_do_not_cross_source_identity(change):
    media = source()
    with pytest.raises(ValueError, match="another edition, stream or clock"):
        validate_playback(source(**change), support(media, [(0, 10)]))
    with pytest.raises(ValueError, match="one nonempty selected source"):
        union_playback((support(media, [(0, 10)]), support(source(**change), [(0, 10)])))


def test_annotation_timeline_and_half_open_extent_are_not_silent_media_evidence():
    annotation = source(axis_kind="annotation-timeline")
    with pytest.raises(ValueError, match="annotation timeline"):
        validate_playback(annotation, support(annotation, [(0, 10)]))
    media = source()
    validate_playback(media, support(media, [(-10, 100)]))
    with pytest.raises(ValueError, match="half-open media extent"):
        validate_playback(media, support(media, points=[100]))
    with pytest.raises(ValueError, match="escapes media extent"):
        validate_playback(media, support(media, [(-11, 0)]))


def test_large_signed_coordinates_and_equivalent_timebases_are_exact():
    start = -(2**70)
    media = source(start_tick=str(start), end_tick=str(start + 100), seconds_per_tick="0.5")
    assert media == source(start_tick=str(start), end_tick=str(start + 100), seconds_per_tick="1/2")
    result = project_playback(media, support(media, [(start + 1, start + 4)], [start + 2]))
    assert result.duration_seconds == "3/2"
    assert result.support.points == (str(start + 2),)


def test_public_projection_and_containment_refuse_typed_forgeries():
    media = source()
    value = support(media, [(0, 10)])
    forged_interval = value.intervals[0].model_copy(update={"start": True})
    forged = value.model_copy(update={"intervals": (forged_interval,)})
    for call in (
        lambda: project_playback(media, forged),
        lambda: contains_playback(forged, value),
        lambda: union_playback((forged,)),
    ):
        with pytest.raises(ValueError):
            call()
    with pytest.raises(ValueError):
        project_playback(media.model_copy(update={"seconds_per_tick": "0"}), value)


_intervals = st.lists(st.tuples(st.integers(-5, 6), st.integers(1, 4)), max_size=5)
_points = st.lists(st.integers(-5, 9), min_size=1, max_size=5)


@given(_intervals, _points, _intervals, _points)
def test_union_and_containment_match_integer_cell_oracle(ai, ap, bi, bp):
    media = source()
    a_pairs, b_pairs = [(x, x + n) for x, n in ai], [(x, x + n) for x, n in bi]
    a, b = support(media, a_pairs, ap), support(media, b_pairs, bp)
    a_cells = {x for lo, hi in a_pairs for x in range(lo, hi)}
    b_cells = {x for lo, hi in b_pairs for x in range(lo, hi)}
    expected = a_cells <= b_cells and set(ap) <= (b_cells | set(bp))
    assert contains_playback(a, b) is expected
    union = union_playback((a, b))
    assert Fraction(project_playback(media, union).duration_seconds) == Fraction(
        len(a_cells | b_cells), 3
    )
    assert set(map(int, union.points)) == set(ap) | set(bp)
