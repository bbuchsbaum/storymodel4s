"""Exact primary-axis playback evidence retains gaps and explicit point observations."""

from collections.abc import Iterable
from fractions import Fraction
from typing import ClassVar, Literal, Self

from pydantic import field_validator, model_validator

from ._base import Digest, Identified, Model, Name, Side, _validate_instance
from ._coordinates import exact, fraction_text, tick


class TickInterval(Model):
    """A positive half-open native interval; integer strings survive JSON without rounding."""

    start: str
    end: str

    @field_validator("start", "end")
    @classmethod
    def canonical_tick(cls, value: str) -> str:
        return str(tick(value))

    @model_validator(mode="after")
    def increasing(self) -> Self:
        if tick(self.end) <= tick(self.start):
            raise ValueError("playback interval must have positive duration")
        return self


class MediaSource(Identified):
    """One fingerprinted edition stream on a declared primary clock, independent of its text."""

    namespace: ClassVar[str] = "media-source"
    key: Name
    side: Side
    sha256: Digest
    edition: Name
    stream: Name
    clock: Name
    axis_kind: Literal["edition-playback", "annotation-timeline"] = "edition-playback"
    stream_kind: Literal["picture", "audio"]
    start_tick: str
    end_tick: str
    seconds_per_tick: str

    @field_validator("start_tick", "end_tick")
    @classmethod
    def canonical_tick(cls, value: str) -> str:
        return str(tick(value))

    @field_validator("seconds_per_tick")
    @classmethod
    def positive_timebase(cls, value: str) -> str:
        number = exact(value)
        if number <= 0:
            raise ValueError("playback timebase must be positive")
        return fraction_text(number)

    @model_validator(mode="after")
    def increasing(self) -> Self:
        if tick(self.end_tick) <= tick(self.start_tick):
            raise ValueError("media extent must have positive duration")
        return self


class PlaybackSupport(Model):
    """Canonical interval union plus explicit points on one complete media-source identity."""

    source: Name
    intervals: tuple[TickInterval, ...] = ()
    points: tuple[str, ...] = ()

    @model_validator(mode="after")
    def canonical_union(self) -> Self:
        if not self.intervals and not self.points:
            raise ValueError("playback support needs intervals or points")
        merged: list[TickInterval] = []
        for interval in sorted(self.intervals, key=lambda i: (tick(i.start), tick(i.end))):
            if merged and tick(interval.start) <= tick(merged[-1].end):
                previous = merged.pop()
                merged.append(
                    TickInterval(
                        start=previous.start, end=str(max(tick(previous.end), tick(interval.end)))
                    )
                )
            else:
                merged.append(interval)
        object.__setattr__(self, "intervals", tuple(merged))
        object.__setattr__(
            self, "points", tuple(str(p) for p in sorted({tick(p) for p in self.points}))
        )
        return self


def validate_playback(source: MediaSource, support: PlaybackSupport) -> PlaybackSupport:
    """Check the actual edition/stream/clock and extent; annotation timing is not media evidence."""
    media = _validate_instance(MediaSource, source)
    checked = _validate_instance(PlaybackSupport, support)
    if media.axis_kind != "edition-playback":
        raise ValueError("annotation timeline is not edition playback evidence")
    if checked.source != media.id:
        raise ValueError("playback support belongs to another edition, stream or clock")
    start, end = tick(media.start_tick), tick(media.end_tick)
    if any(not start <= tick(i.start) < tick(i.end) <= end for i in checked.intervals):
        raise ValueError("playback interval escapes media extent")
    if any(not start <= tick(p) < end for p in checked.points):
        raise ValueError("playback point escapes half-open media extent")
    return checked


def union_playback(values: Iterable[PlaybackSupport]) -> PlaybackSupport:
    """Union one selected source without implicit clock conversion or loss of explicit points."""
    supports = tuple(_validate_instance(PlaybackSupport, v) for v in values)
    if not supports or len({v.source for v in supports}) != 1:
        raise ValueError("playback union requires one nonempty selected source")
    return PlaybackSupport(
        source=supports[0].source,
        intervals=tuple(i for s in supports for i in s.intervals),
        points=tuple(p for s in supports for p in s.points),
    )


def contains_playback(child: PlaybackSupport, parent: PlaybackSupport) -> bool:
    """Containment uses the exact union; a point never supplies an interval's duration."""
    child = _validate_instance(PlaybackSupport, child)
    parent = _validate_instance(PlaybackSupport, parent)
    if child.source != parent.source:
        return False
    return all(
        any(tick(p.start) <= tick(c.start) and tick(c.end) <= tick(p.end) for p in parent.intervals)
        for c in child.intervals
    ) and all(
        c in parent.points or any(tick(p.start) <= tick(c) < tick(p.end) for p in parent.intervals)
        for c in child.points
    )


class PlaybackCell(Model):
    """Observed interval exposure and explicit point observations remain separate quantities."""

    interval: TickInterval
    overlap_ticks: str
    duration_seconds: str
    coverage: str
    points: tuple[str, ...]


class PlaybackReadout(Model):
    """An exact selected-source readout retains its complete physical support and source."""

    recipe: Literal["native-playback-support/v1"] = "native-playback-support/v1"
    source: MediaSource
    support: PlaybackSupport
    duration_seconds: str
    cells: tuple[PlaybackCell, ...]


def project_playback(
    source: MediaSource, support: PlaybackSupport, bins: tuple[TickInterval, ...] = ()
) -> PlaybackReadout:
    """Measure interval-union exposure without filling gaps or assigning duration to points."""
    media = _validate_instance(MediaSource, source)
    checked = validate_playback(media, support)
    timebase = exact(media.seconds_per_tick)
    cells = []
    previous: int | None = None
    for raw in bins:
        interval = _validate_instance(TickInterval, raw)
        lo, hi = tick(interval.start), tick(interval.end)
        if not tick(media.start_tick) <= lo < hi <= tick(media.end_tick):
            raise ValueError("playback bin escapes selected source extent")
        if previous is not None and lo < previous:
            raise ValueError("playback bins must be ordered and nonoverlapping")
        previous = hi
        overlap = sum(
            max(0, min(hi, tick(i.end)) - max(lo, tick(i.start))) for i in checked.intervals
        )
        cells.append(
            PlaybackCell(
                interval=interval,
                overlap_ticks=str(overlap),
                duration_seconds=fraction_text(overlap * timebase),
                coverage=fraction_text(Fraction(overlap, hi - lo)),
                points=tuple(p for p in checked.points if lo <= tick(p) < hi),
            )
        )
    return PlaybackReadout(
        source=media,
        support=checked,
        duration_seconds=fraction_text(
            sum(tick(i.end) - tick(i.start) for i in checked.intervals) * timebase
        ),
        cells=tuple(cells),
    )
