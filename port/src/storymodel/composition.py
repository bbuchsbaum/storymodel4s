"""Exact concatenation of already fingerprinted playback sources without synthetic media."""

from __future__ import annotations

from fractions import Fraction
from math import gcd, lcm
from typing import ClassVar, Literal, Self

from pydantic import field_validator, model_validator

from ._base import Identified, Model, Name, _validate_instance
from ._coordinates import exact, fraction_text, tick
from .playback import (
    MediaSource,
    PlaybackCell,
    PlaybackSupport,
    TickInterval,
    _project_checked_axis,
    validate_playback,
)
from .serialization import digest


class CompositionPart(Model):
    """One occurrence selects a positive native interval from a registered physical source."""

    source: Name
    native: TickInterval


class CompositionSegment(Model):
    """A derived occurrence maps one selected native interval onto the composed clock."""

    occurrence: Name
    ordinal: int
    source: Name
    native: TickInterval
    target: TickInterval


class PlaybackComposition(Identified):
    """A content-addressed virtual playback axis retains every physical source identity."""

    namespace: ClassVar[str] = "playback-composition"
    schema_version: Literal["storymodel.playback-composition/v1"] = (
        "storymodel.playback-composition/v1"
    )
    key: Name
    sources: tuple[MediaSource, ...]
    parts: tuple[CompositionPart, ...]

    @field_validator("sources")
    @classmethod
    def canonical_sources(cls, values: tuple[MediaSource, ...]) -> tuple[MediaSource, ...]:
        """Sort the registry before content identity is derived."""
        checked = (_validate_instance(MediaSource, value) for value in values)
        return tuple(sorted(checked, key=lambda source: source.id))

    @model_validator(mode="after")
    def checked_parts(self) -> Self:
        sources = self.sources
        if not sources:
            raise ValueError("composition needs registered sources")
        if len({source.id for source in sources}) != len(sources):
            raise ValueError("composition sources must have unique identities")
        if any(source.axis_kind != "edition-playback" for source in sources):
            raise ValueError("composition requires edition playback sources")
        if len({source.side for source in sources}) != 1:
            raise ValueError("composition sources must have one side")
        parts = tuple(_validate_instance(CompositionPart, part) for part in self.parts)
        if not parts:
            raise ValueError("composition needs ordered parts")
        by_id = {source.id: source for source in sources}
        if {part.source for part in parts} != set(by_id):
            raise ValueError("composition source registry must exactly match used parts")
        for part in parts:
            source = by_id.get(part.source)
            if source is None:
                raise ValueError("composition part has an unregistered source")
            validate_playback(source, PlaybackSupport(source=source.id, intervals=(part.native,)))
        return self

    @property
    def seconds_per_tick(self) -> str:
        """Return the greatest exact common sub-tick shared by all source clocks."""
        values = [exact(source.seconds_per_tick) for source in self.sources]
        numerator = values[0].numerator
        denominator = values[0].denominator
        for value in values[1:]:
            numerator = gcd(numerator, value.numerator)
            denominator = lcm(denominator, value.denominator)
        return fraction_text(Fraction(numerator, denominator))

    @property
    def segments(self) -> tuple[CompositionSegment, ...]:
        """Derive sequential target intervals without caller-provided offsets in identity."""
        by_id = {source.id: source for source in self.sources}
        q = exact(self.seconds_per_tick)
        cursor = 0
        segments: list[CompositionSegment] = []
        for ordinal, part in enumerate(self.parts, start=1):
            source = by_id[part.source]
            native_duration = tick(part.native.end) - tick(part.native.start)
            target_duration = Fraction(native_duration) * exact(source.seconds_per_tick) / q
            if target_duration.denominator != 1:
                raise ValueError("composition clock cannot exactly represent part duration")
            end = cursor + target_duration.numerator
            segments.append(
                CompositionSegment(
                    occurrence=_occurrence_id(self.id, ordinal),
                    ordinal=ordinal,
                    source=source.id,
                    native=part.native,
                    target=TickInterval(start=str(cursor), end=str(end)),
                )
            )
            cursor = end
        return tuple(segments)

    @property
    def end_tick(self) -> str:
        """Return the exclusive composed endpoint on its derived common clock."""
        return self.segments[-1].target.end


class OccurrenceSupport(Model):
    """One occurrence support records its native evidence and exact derived composed support."""

    occurrence: Name
    native: PlaybackSupport
    composed: PlaybackSupport


class CompositionReadout(Model):
    """A reproducible virtual-axis measurement keeps its segments and exact physical support."""

    recipe: Literal["composed-playback-support/v1"] = "composed-playback-support/v1"
    composition: PlaybackComposition
    support: PlaybackSupport
    seconds_per_tick: str
    end_tick: str
    segments: tuple[CompositionSegment, ...]
    duration_seconds: str
    cells: tuple[PlaybackCell, ...]


def concatenate_playback(
    key: str, sources: tuple[MediaSource, ...], parts: tuple[CompositionPart, ...]
) -> PlaybackComposition:
    """Create a virtual concatenation while retaining the exact supplied source registry."""
    return PlaybackComposition(key=key, sources=sources, parts=parts)


def compose_support(
    composition: PlaybackComposition, occurrence_id: str, native: PlaybackSupport
) -> OccurrenceSupport:
    """Project native support for one occurrence onto the derived composed clock."""
    checked = _validate_instance(PlaybackComposition, composition)
    segments = checked.segments
    sources = {source.id: source for source in checked.sources}
    segment = _segment(segments, occurrence_id)
    return _compose_checked(
        checked, segment, sources[segment.source], native, exact(checked.seconds_per_tick)
    )


def _compose_checked(
    composition: PlaybackComposition,
    segment: CompositionSegment,
    source: MediaSource,
    native: PlaybackSupport,
    q: Fraction,
) -> OccurrenceSupport:
    """Map one native support when composition, segment and source have already been checked."""
    selected = validate_playback(source, native)
    _within(selected, segment.native, "native support escapes occurrence interval")
    source_tick = exact(source.seconds_per_tick)
    offset = tick(segment.target.start)
    composed = PlaybackSupport(
        source=composition.id,
        intervals=tuple(
            TickInterval(
                start=str(
                    offset
                    + _target_delta(tick(item.start) - tick(segment.native.start), source_tick, q)
                ),
                end=str(
                    offset
                    + _target_delta(tick(item.end) - tick(segment.native.start), source_tick, q)
                ),
            )
            for item in selected.intervals
        ),
        points=tuple(
            str(offset + _target_delta(tick(point) - tick(segment.native.start), source_tick, q))
            for point in selected.points
        ),
    )
    return OccurrenceSupport(occurrence=segment.occurrence, native=selected, composed=composed)


def validate_occurrence_support(
    composition: PlaybackComposition, value: OccurrenceSupport
) -> OccurrenceSupport:
    """Recompute both coordinates to reject copied or mismatched occurrence support."""
    checked = _validate_instance(PlaybackComposition, composition)
    supplied = _validate_instance(OccurrenceSupport, value)
    segments = checked.segments
    sources = {source.id: source for source in checked.sources}
    segment = _segment(segments, supplied.occurrence)
    expected = _compose_checked(
        checked, segment, sources[segment.source], supplied.native, exact(checked.seconds_per_tick)
    )
    if supplied != expected:
        raise ValueError("occurrence support differs from composition mapping")
    return expected


def validate_composed_support(
    composition: PlaybackComposition, support: PlaybackSupport
) -> PlaybackSupport:
    """Check virtual-axis support identity, finite extent and half-open geometry."""
    checked = _validate_instance(PlaybackComposition, composition)
    selected = _validate_instance(PlaybackSupport, support)
    if selected.source != checked.id:
        raise ValueError("composed support belongs to another composition")
    _within(
        selected,
        TickInterval(start="0", end=checked.end_tick),
        "composed support escapes composition extent",
    )
    return selected


def inverse_support(
    composition: PlaybackComposition, support: PlaybackSupport
) -> tuple[OccurrenceSupport, ...]:
    """Split virtual support at occurrence bounds, refusing any non-integral native coordinate."""
    checked = _validate_instance(PlaybackComposition, composition)
    selected = validate_composed_support(checked, support)
    q = exact(checked.seconds_per_tick)
    segments = checked.segments
    sources = {source.id: source for source in checked.sources}
    results: list[OccurrenceSupport] = []
    interval_index = 0
    point_index = 0
    for segment in segments:
        source = sources[segment.source]
        source_tick = exact(source.seconds_per_tick)
        lo, hi = tick(segment.target.start), tick(segment.target.end)
        while (
            interval_index < len(selected.intervals)
            and tick(selected.intervals[interval_index].end) <= lo
        ):
            interval_index += 1
        intervals: list[TickInterval] = []
        scan_interval = interval_index
        while scan_interval < len(selected.intervals):
            item = selected.intervals[scan_interval]
            if tick(item.start) >= hi:
                break
            overlap_lo, overlap_hi = max(lo, tick(item.start)), min(hi, tick(item.end))
            if overlap_lo < overlap_hi:
                intervals.append(
                    TickInterval(
                        start=str(_native_tick(overlap_lo - lo, source_tick, q, segment)),
                        end=str(_native_tick(overlap_hi - lo, source_tick, q, segment)),
                    )
                )
            scan_interval += 1
        while point_index < len(selected.points) and tick(selected.points[point_index]) < lo:
            point_index += 1
        points: list[str] = []
        while point_index < len(selected.points) and tick(selected.points[point_index]) < hi:
            points.append(
                str(_native_tick(tick(selected.points[point_index]) - lo, source_tick, q, segment))
            )
            point_index += 1
        if intervals or points:
            results.append(
                _compose_checked(
                    checked,
                    segment,
                    source,
                    PlaybackSupport(
                        source=source.id, intervals=tuple(intervals), points=tuple(points)
                    ),
                    q,
                )
            )
    return tuple(results)


def project_composition(
    composition: PlaybackComposition, support: PlaybackSupport, bins: tuple[TickInterval, ...] = ()
) -> CompositionReadout:
    """Measure exact virtual support on the derived common axis without filling gaps or points."""
    checked = _validate_instance(PlaybackComposition, composition)
    selected = validate_composed_support(checked, support)
    duration_seconds, cells = _project_checked_axis(
        start_tick="0",
        end_tick=checked.end_tick,
        seconds_per_tick=checked.seconds_per_tick,
        support=selected,
        bins=bins,
        bin_error="composition bin escapes composed extent",
    )
    return CompositionReadout(
        composition=checked,
        support=selected,
        seconds_per_tick=checked.seconds_per_tick,
        end_tick=checked.end_tick,
        segments=checked.segments,
        duration_seconds=duration_seconds,
        cells=cells,
    )


def verify_composition_readout(
    composition: PlaybackComposition,
    support: PlaybackSupport,
    bins: tuple[TickInterval, ...],
    saved: CompositionReadout,
) -> CompositionReadout:
    """Recompute a saved virtual-axis readout against the exact composition and support."""
    supplied = _validate_instance(CompositionReadout, saved)
    expected = project_composition(composition, support, bins)
    if supplied != expected:
        raise ValueError("composition readout differs from original inputs")
    return expected


def _occurrence_id(composition_id: str, ordinal: int) -> str:
    return "composition-occurrence:" + digest({"composition": composition_id, "position": ordinal})


def _segment(segments: tuple[CompositionSegment, ...], occurrence_id: str) -> CompositionSegment:
    for segment in segments:
        if segment.occurrence == occurrence_id:
            return segment
    raise ValueError("unknown composition occurrence")


def _within(support: PlaybackSupport, extent: TickInterval, message: str) -> None:
    lo, hi = tick(extent.start), tick(extent.end)
    if any(not lo <= tick(item.start) < tick(item.end) <= hi for item in support.intervals):
        raise ValueError(message)
    if any(not lo <= tick(point) < hi for point in support.points):
        raise ValueError(message)


def _target_delta(native_delta: int, source_tick: Fraction, q: Fraction) -> int:
    result = Fraction(native_delta) * source_tick / q
    if result.denominator != 1:
        raise ValueError("composition clock cannot exactly represent native support")
    return result.numerator


def _native_tick(
    delta: int, source_tick: Fraction, q: Fraction, segment: CompositionSegment
) -> int:
    native_delta = Fraction(delta) * q / source_tick
    if native_delta.denominator != 1:
        raise ValueError("composed support endpoint is not an integral native tick")
    return tick(segment.native.start) + native_delta.numerator
