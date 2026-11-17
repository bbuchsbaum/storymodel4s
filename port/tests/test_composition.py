"""Exact virtual playback concatenation retains source identity, repeats and half-open joins."""

from fractions import Fraction

import pytest
from hypothesis import given
from hypothesis import strategies as st

from storymodel.composition import (
    CompositionPart,
    compose_support,
    concatenate_playback,
    inverse_support,
    project_composition,
    validate_composed_support,
    validate_occurrence_support,
    verify_composition_readout,
)
from storymodel.playback import MediaSource, PlaybackSupport, TickInterval, project_playback


def media(key: str, start: int, end: int, clock: str, **changes: str) -> MediaSource:
    return MediaSource(
        **{
            "key": key,
            "side": "source",
            "sha256": ("a" if key == "A" else "b") * 64,
            "edition": f"edition-{key}",
            "stream": f"stream-{key}",
            "clock": f"clock-{key}",
            "stream_kind": "picture",
            "start_tick": str(start),
            "end_tick": str(end),
            "seconds_per_tick": clock,
            **changes,
        }
    )


def oracle():
    a, b = media("A", 10, 16, "1/3"), media("B", 0, 8, "1/4")
    composition = concatenate_playback(
        "oracle",
        (b, a),
        (
            CompositionPart(source=a.id, native=TickInterval(start="10", end="16")),
            CompositionPart(source=b.id, native=TickInterval(start="0", end="8")),
            CompositionPart(source=a.id, native=TickInterval(start="10", end="16")),
        ),
    )
    return a, b, composition


def test_concatenation_uses_common_exact_axis_and_keeps_repeated_occurrences():
    a, b, composition = oracle()
    assert composition.sources == tuple(sorted((a, b), key=lambda source: source.id))
    assert composition.seconds_per_tick == "1/12"
    assert composition.end_tick == "72"
    assert [(s.target.start, s.target.end) for s in composition.segments] == [
        ("0", "24"),
        ("24", "48"),
        ("48", "72"),
    ]
    assert composition.segments[0].occurrence != composition.segments[2].occurrence


def test_composition_supports_inverse_points_gaps_and_exact_readout():
    a, _, composition = oracle()
    first, _, third = composition.segments
    native = PlaybackSupport(source=a.id, intervals=(TickInterval(start="11", end="12"),))
    one = compose_support(composition, first.occurrence, native)
    three = compose_support(composition, third.occurrence, native)
    assert one.composed.intervals == (TickInterval(start="4", end="8"),)
    assert three.composed.intervals == (TickInterval(start="52", end="56"),)
    selected = PlaybackSupport(
        source=composition.id,
        intervals=(*one.composed.intervals, *three.composed.intervals),
        points=("24",),
    )
    readout = project_composition(
        composition,
        selected,
        (TickInterval(start="0", end="24"), TickInterval(start="24", end="48")),
    )
    assert readout.duration_seconds == "2/3"
    assert [cell.points for cell in readout.cells] == [(), ("24",)]
    inverse = inverse_support(composition, selected)
    assert [item.occurrence for item in inverse] == [
        first.occurrence,
        composition.segments[1].occurrence,
        third.occurrence,
    ]
    assert inverse[0].native.intervals == native.intervals
    assert inverse[2].native.intervals == native.intervals
    with pytest.raises(ValueError, match="not an integral native tick"):
        inverse_support(composition, PlaybackSupport(source=composition.id, points=("1",)))
    with pytest.raises(ValueError, match="composition extent"):
        validate_composed_support(
            composition, PlaybackSupport(source=composition.id, points=("72",))
        )
    bins = (TickInterval(start="0", end="24"), TickInterval(start="24", end="48"))
    assert verify_composition_readout(composition, selected, bins, readout) == readout


def test_composition_rejects_registry_side_and_copied_support_forgeries():
    a, b, composition = oracle()
    with pytest.raises(ValueError, match="exactly match"):
        concatenate_playback(
            "bad",
            (a, b),
            (CompositionPart(source=a.id, native=TickInterval(start="10", end="16")),),
        )
    recall = media("C", 0, 8, "1/4", side="recall")
    with pytest.raises(ValueError, match="one side"):
        concatenate_playback(
            "mixed",
            (a, recall),
            (
                CompositionPart(source=a.id, native=TickInterval(start="10", end="16")),
                CompositionPart(source=recall.id, native=TickInterval(start="0", end="8")),
            ),
        )
    segment = composition.segments[0]
    valid = compose_support(
        composition, segment.occurrence, PlaybackSupport(source=a.id, points=("11",))
    )
    forged = valid.model_copy(
        update={"composed": PlaybackSupport(source=composition.id, points=("5",))}
    )
    with pytest.raises(ValueError, match="differs"):
        validate_occurrence_support(composition, forged)


def test_composition_projection_matches_native_projection_for_single_source():
    a = media("A", 10, 16, "1/3")
    composition = concatenate_playback(
        "single",
        (a,),
        (CompositionPart(source=a.id, native=TickInterval(start="10", end="16")),),
    )
    native = PlaybackSupport(
        source=a.id, intervals=(TickInterval(start="11", end="13"),), points=("14",)
    )
    mapped = compose_support(composition, composition.segments[0].occurrence, native)
    assert (
        project_composition(composition, mapped.composed).duration_seconds
        == project_playback(a, native).duration_seconds
    )


def test_registry_order_is_irrelevant_but_occurrence_order_changes_identity():
    a, b, composition = oracle()
    assert concatenate_playback("oracle", (a, b), composition.parts) == composition
    reordered = concatenate_playback(
        "oracle",
        (a, b),
        (
            composition.parts[1],
            composition.parts[0],
            composition.parts[2],
        ),
    )
    assert reordered.id != composition.id
    assert set(s.occurrence for s in reordered.segments).isdisjoint(
        s.occurrence for s in composition.segments
    )
    native = PlaybackSupport(source=a.id, points=("11",))
    old = compose_support(composition, composition.segments[0].occurrence, native)
    with pytest.raises(ValueError, match="another composition"):
        validate_composed_support(reordered, old.composed)


@pytest.mark.parametrize(
    "field,changed",
    [
        ("sha256", "c" * 64),
        ("clock", "other"),
        ("stream", "other"),
        ("edition", "other"),
        ("seconds_per_tick", "1/5"),
    ],
)
def test_physical_source_changes_cannot_reuse_composed_support(field, changed):
    a, _, composition = oracle()
    fields = a.model_dump(exclude={"id"})
    fields[field] = changed
    replacement = MediaSource(**fields)
    parts = tuple(
        CompositionPart(source=replacement.id if p.source == a.id else p.source, native=p.native)
        for p in composition.parts
    )
    sources = tuple(replacement if m.id == a.id else m for m in composition.sources)
    different = concatenate_playback("oracle", sources, parts)
    assert different.id != composition.id
    with pytest.raises(ValueError):
        validate_composed_support(different, PlaybackSupport(source=composition.id, points=("0",)))


def test_selected_clip_bounds_are_stricter_than_physical_source_bounds():
    a = media("A", 10, 16, "1/3")
    composition = concatenate_playback(
        "clip", (a,), (CompositionPart(source=a.id, native=TickInterval(start="11", end="14")),)
    )
    occurrence = composition.segments[0].occurrence
    for support in (
        PlaybackSupport(source=a.id, points=("10",)),
        PlaybackSupport(source=a.id, points=("14",)),
        PlaybackSupport(source=a.id, intervals=(TickInterval(start="13", end="15"),)),
    ):
        with pytest.raises(ValueError, match="occurrence interval"):
            compose_support(composition, occurrence, support)
    assert compose_support(
        composition,
        occurrence,
        PlaybackSupport(
            source=a.id,
            intervals=(TickInterval(start="11", end="14"),),
            points=("11",),
        ),
    ).composed == PlaybackSupport(
        source=composition.id,
        intervals=(TickInterval(start="0", end="3"),),
        points=("0",),
    )


def test_inverse_splits_spanning_interval_and_assigns_join_points_to_next_occurrence():
    a, b, composition = oracle()
    selected = PlaybackSupport(
        source=composition.id, intervals=(TickInterval(start="20", end="52"),), points=("24", "48")
    )
    inverse = inverse_support(composition, selected)
    assert [r.native.source for r in inverse] == [a.id, b.id, a.id]
    assert [[(i.start, i.end) for i in r.native.intervals] for r in inverse] == [
        [("15", "16")],
        [("0", "8")],
        [("10", "11")],
    ]
    assert [r.native.points for r in inverse] == [(), ("0",), ("10",)]
    assert len({r.occurrence for r in inverse}) == 3


def test_large_signed_native_coordinates_and_rational_tick_quantum():
    start = -(2**70)
    a, b = media("A", start, start + 6, "2/3"), media("B", 0, 8, "4/5")
    composition = concatenate_playback(
        "large",
        (a, b),
        tuple(
            CompositionPart(source=m.id, native=TickInterval(start=m.start_tick, end=m.end_tick))
            for m in (a, b)
        ),
    )
    assert composition.seconds_per_tick == "2/15"
    assert composition.end_tick == "78"
    native = PlaybackSupport(
        source=a.id, intervals=(TickInterval(start=str(start + 1), end=str(start + 3)),)
    )
    mapped = compose_support(composition, composition.segments[0].occurrence, native)
    assert mapped.composed.intervals == (TickInterval(start="5", end="15"),)
    assert inverse_support(composition, mapped.composed) == (mapped,)
    assert project_composition(composition, mapped.composed).duration_seconds == "4/3"


def test_readout_bins_can_resolve_sub_native_ticks_without_rounding_inverse_evidence():
    a, _, composition = oracle()
    mapped = compose_support(
        composition,
        composition.segments[0].occurrence,
        PlaybackSupport(source=a.id, intervals=(TickInterval(start="10", end="11"),)),
    )
    result = project_composition(composition, mapped.composed, (TickInterval(start="1", end="2"),))
    assert result.cells[0].duration_seconds == "1/12"
    assert result.cells[0].coverage == "1/1"
    with pytest.raises(ValueError, match="not an integral native tick"):
        inverse_support(
            composition,
            PlaybackSupport(source=composition.id, intervals=(TickInterval(start="1", end="2"),)),
        )


def test_public_consumers_refuse_composition_and_derived_readout_forgeries():
    _, _, composition = oracle()
    selected = PlaybackSupport(source=composition.id, points=("0",))
    forged = composition.model_copy(update={"parts": tuple(reversed(composition.parts[:2]))})
    with pytest.raises(ValueError):
        project_composition(forged, selected)
    result = project_composition(composition, selected)
    segment = result.segments[0].model_copy(update={"target": TickInterval(start="1", end="25")})
    changed = result.model_copy(update={"segments": (segment, *result.segments[1:])})
    with pytest.raises(ValueError, match="differs from original"):
        verify_composition_readout(composition, selected, (), changed)


@given(
    st.integers(1, 5),
    st.integers(1, 9),
    st.integers(1, 5),
    st.integers(1, 9),
    st.integers(1, 8),
    st.integers(1, 8),
)
def test_duration_and_inverse_match_independent_rational_time_oracle(an, ad, bn, bd, al, bl):
    a, b = media("A", -3, al - 3, f"{an}/{ad}"), media("B", 7, bl + 7, f"{bn}/{bd}")
    composition = concatenate_playback(
        "property",
        (a, b),
        tuple(
            CompositionPart(source=m.id, native=TickInterval(start=m.start_tick, end=m.end_tick))
            for m in (a, b, a)
        ),
    )
    elapsed = Fraction(0)
    for segment, source in zip(composition.segments, (a, b, a), strict=True):
        assert Fraction(segment.target.start) * Fraction(composition.seconds_per_tick) == elapsed
        native = PlaybackSupport(source=source.id, intervals=(segment.native,))
        mapped = compose_support(composition, segment.occurrence, native)
        assert inverse_support(composition, mapped.composed) == (mapped,)
        elapsed += (int(source.end_tick) - int(source.start_tick)) * Fraction(
            source.seconds_per_tick
        )
    assert elapsed == 2 * al * Fraction(an, ad) + bl * Fraction(bn, bd)
    whole = PlaybackSupport(
        source=composition.id, intervals=(TickInterval(start="0", end=composition.end_tick),)
    )
    assert Fraction(project_composition(composition, whole).duration_seconds) == elapsed
