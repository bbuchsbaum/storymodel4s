import os
from fractions import Fraction
from itertools import combinations
from pathlib import Path

import pytest

import storymodel.composition as implementation
from storymodel.composition import (
    CompositionPart,
    compose_support,
    concatenate_playback,
    inverse_support,
    project_composition,
    verify_composition_readout,
)
from storymodel.playback import MediaSource, PlaybackSupport, TickInterval


def model():
    # Independent physical-time oracle: scales 8 and 9 on a 1/12-second quantum.
    a = MediaSource(
        key="a",
        side="source",
        sha256="a" * 64,
        edition="e",
        stream="a",
        clock="a",
        stream_kind="picture",
        start_tick="-5",
        end_tick="5",
        seconds_per_tick="2/3",
    )
    b = MediaSource(
        key="b",
        side="source",
        sha256="b" * 64,
        edition="e",
        stream="b",
        clock="b",
        stream_kind="audio",
        start_tick="10",
        end_tick="20",
        seconds_per_tick="3/4",
    )
    composition = concatenate_playback(
        "independent",
        (b, a),
        (
            CompositionPart(source=a.id, native=TickInterval(start="-3", end="1")),
            CompositionPart(source=b.id, native=TickInterval(start="12", end="16")),
            CompositionPart(source=a.id, native=TickInterval(start="-1", end="2")),
        ),
    )
    return a, b, composition


def test_source_path_and_exact_control():
    assert Path(implementation.__file__).is_relative_to(Path(os.environ["REVIEW_SOURCE"]))
    a, b, c = model()
    assert c.seconds_per_tick == "1/12" and c.end_tick == "92"
    assert [(s.target.start, s.target.end) for s in c.segments] == [
        ("0", "32"),
        ("32", "68"),
        ("68", "92"),
    ]
    assert len(set(s.occurrence for s in c.segments)) == 3
    v = PlaybackSupport(
        source=c.id, intervals=(TickInterval(start="24", end="76"),), points=("32", "68")
    )
    inverse = inverse_support(c, v)
    assert [
        (x.native.source, [(i.start, i.end) for i in x.native.intervals], x.native.points)
        for x in inverse
    ] == [(a.id, [("0", "1")], ()), (b.id, [("12", "16")], ("12",)), (a.id, [("-1", "0")], ("-1",))]


def test_clipped_occurrence_rejects_legal_physical_points():
    a, b, c = model()
    for p in ("-4", "1"):
        with pytest.raises(ValueError, match="occurrence interval"):
            compose_support(c, c.segments[0].occurrence, PlaybackSupport(source=a.id, points=(p,)))


def test_inverse_refuses_subnative_point_without_rounding():
    a, b, c = model()
    with pytest.raises(ValueError, match="integral native tick"):
        inverse_support(c, PlaybackSupport(source=c.id, points=("1",)))


def test_exact_all_point_oracle_and_interval_grid():
    a, b, c = model()
    # These tuples are input-side expectations, not production-derived coordinates.
    expected = ((0, 32, -3, 8, a.id), (32, 68, 12, 9, b.id), (68, 92, -1, 8, a.id))
    for point in range(92):
        lo, hi, start, scale, source = next(s for s in expected if s[0] <= point < s[1])
        native = Fraction(point - lo, scale) + start
        support = PlaybackSupport(source=c.id, points=(str(point),))
        if native.denominator != 1:
            with pytest.raises(ValueError):
                inverse_support(c, support)
        else:
            result = inverse_support(c, support)
            assert len(result) == 1
            assert result[0].native.source == source
            assert result[0].native.points == (str(native.numerator),)
            assert result[0].composed == support
    for x, y in combinations(
        (0, 1, 8, 16, 24, 31, 32, 33, 41, 50, 59, 67, 68, 69, 76, 84, 91, 92), 2
    ):
        pieces = []
        for lo, hi, start, scale, source in expected:
            left, right = max(x, lo), min(y, hi)
            if left < right:
                pieces.append(
                    (
                        source,
                        Fraction(left - lo, scale) + start,
                        Fraction(right - lo, scale) + start,
                    )
                )
        support = PlaybackSupport(source=c.id, intervals=(TickInterval(start=str(x), end=str(y)),))
        if any(left.denominator != 1 or right.denominator != 1 for _, left, right in pieces):
            with pytest.raises(ValueError):
                inverse_support(c, support)
        else:
            result = inverse_support(c, support)
            assert [
                (
                    s.native.source,
                    Fraction(s.native.intervals[0].start),
                    Fraction(s.native.intervals[0].end),
                )
                for s in result
            ] == pieces
            assert Fraction(project_composition(c, support).duration_seconds) == Fraction(y - x, 12)


def test_saved_readout_recomputes_measurements_and_query():
    a, b, c = model()
    support = PlaybackSupport(
        source=c.id, intervals=(TickInterval(start="8", end="16"),), points=("32",)
    )
    bins = (TickInterval(start="0", end="32"), TickInterval(start="32", end="68"))
    saved = project_composition(c, support, bins)
    assert saved.duration_seconds == "2/3"
    assert [(x.coverage, x.points) for x in saved.cells] == [("1/4", ()), ("0/1", ("32",))]
    for field, value in [
        ("duration_seconds", "3/4"),
        ("seconds_per_tick", "1/13"),
        ("end_tick", "93"),
        ("cells", ()),
    ]:
        with pytest.raises(ValueError):
            verify_composition_readout(c, support, bins, saved.model_copy(update={field: value}))
    different = PlaybackSupport(source=c.id, points=("32",))
    with pytest.raises(ValueError):
        verify_composition_readout(c, different, bins, saved)
