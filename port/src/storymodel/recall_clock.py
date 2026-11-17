"""Measure mapped recall details on a declared recording clock."""

from bisect import bisect_left, bisect_right
from collections import defaultdict
from pathlib import Path
from typing import Literal, Self

from pydantic import Field, field_validator, model_validator

from ._base import Digest, Model, Name, _validate_instance
from ._coordinates import exact, tick
from .analysis import CorrespondenceRow, RecallRow
from .analysis_workflow import replay_analysis_file
from .model import Support, Unit
from .playback import MediaSource, TickInterval
from .prepared_construction import PreparedConstruction
from .serialization import digest
from .sherlock_recall import SherlockRecall, sherlock_recall_timing
from .temporal import Interval, TemporalCell, allocate


class UnitInterval(Model):
    """A separately supplied extent for one intake unit on the declared recording."""

    unit: int = Field(gt=0)
    native: TickInterval


class RecordingQuery(Model):
    """A producer declares the relation between a specific intake column and audio stream."""

    schema_version: Literal["storymodel.recording-query/v1"] = "storymodel.recording-query/v1"
    intake_sha256: Digest
    clock: Literal["princeton", "openneuro"]
    recording: MediaSource
    zero_tick: str
    provenance: Name
    bins: tuple[TickInterval, ...] = Field(min_length=1)
    intervals: tuple[UnitInterval, ...] = ()

    @field_validator("zero_tick")
    @classmethod
    def exact_zero(cls, value: str) -> str:
        return str(tick(value))

    @model_validator(mode="after")
    def geometry(self) -> Self:
        media = self.recording
        if (media.side, media.stream_kind, media.axis_kind) != (
            "recall",
            "audio",
            "edition-playback",
        ):
            raise ValueError("timing requires recall audio on a playback axis")
        lower, upper = tick(media.start_tick), tick(media.end_tick)
        previous = lower
        for item in self.bins:
            if tick(item.start) < previous or tick(item.end) > upper:
                raise ValueError("bins must be ordered and disjoint within the recording extent")
            previous = tick(item.end)
        if len({item.unit for item in self.intervals}) != len(self.intervals):
            raise ValueError("duplicate measured unit interval")
        for observed in self.intervals:
            if not lower <= tick(observed.native.start) < tick(observed.native.end) <= upper:
                raise ValueError("unit interval outside recording extent")
        return self


class TimedDetail(Model):
    """Timing of the containing recall unit together with every reference interpretation."""

    recall: RecallRow
    correspondence: tuple[CorrespondenceRow, ...]
    onset_tick: str | None
    onset_bin: int | None
    interval: TickInterval | None
    allocations: tuple[TemporalCell, ...]


class TimedRecall(Model):
    """A reproducible query and its observations in recording ticks, with zero-based bin indices."""

    schema_version: Literal["storymodel.timed-recall/v1"] = "storymodel.timed-recall/v1"
    query: RecordingQuery
    analysis_sha256: Digest
    word_onsets: dict[str, str | None]
    unit_onsets: dict[int, str | None]
    bins: tuple[TickInterval, ...]
    total_details: int
    onset_counts: tuple[int, ...]
    missing_onset_details: int
    outside_bins_details: int
    entries: tuple[TimedDetail, ...]


def _bind_onsets(
    intake: SherlockRecall, query: RecordingQuery, intervals: dict[int, TickInterval]
) -> tuple[dict[str, str | None], dict[int, str | None]]:
    """Convert lexical observations once and check their declared unit extents."""
    timing = sherlock_recall_timing(intake, query.clock)
    if timing.intake_sha256 != query.intake_sha256:
        raise ValueError("recording declaration belongs to another recall intake")
    media = query.recording
    rate = exact(media.seconds_per_tick)
    zero = tick(query.zero_tick)
    lower, upper = tick(media.start_tick), tick(media.end_tick)
    word_onsets: dict[str, str | None] = {}
    for record in timing.records:
        if record.word is None:
            continue
        point = None
        if record.onset_seconds is not None:
            coordinate = zero + exact(record.onset_seconds) / rate
            if coordinate.denominator != 1:
                raise ValueError("word onset does not fall on an integral recording tick")
            if not lower <= coordinate < upper:
                raise ValueError("word onset outside recording extent")
            point = str(coordinate.numerator)
        word_onsets[record.word] = point
    onsets = {
        unit.unit: word_onsets[unit.first_word] if unit.first_word is not None else None
        for unit in timing.unit_onsets
    }
    if intervals.keys() - onsets.keys():
        raise ValueError("unknown unit in recording interval declaration")
    for word in intake.words:
        word_point = word_onsets[word.id]
        extent = intervals.get(word.unit) if word.unit is not None else None
        if (
            extent
            and word_point is not None
            and not (tick(extent.start) <= tick(word_point) < tick(extent.end))
        ):
            raise ValueError("word onset outside its declared unit interval")

    return word_onsets, onsets


def timed_recall(plan_path: Path, query: RecordingQuery) -> TimedRecall:
    """Replay the granted analysis, bind exact recording ticks and locate its mapped details."""
    query = _validate_instance(RecordingQuery, query)
    replay = replay_analysis_file(plan_path)
    request = replay.recall.request
    if not isinstance(request, PreparedConstruction) or not isinstance(
        request.intake, SherlockRecall
    ):
        raise ValueError("timed recall requires prepared Sherlock recall construction")
    intake = request.intake
    media = query.recording
    intervals = {item.unit: item.native for item in query.intervals}
    word_onsets, onsets = _bind_onsets(intake, query, intervals)
    ordinals = {
        Unit(
            kind="recall", support=Support(document=intake.prepared.document.id, spans=(u.support,))
        ).id: u.u
        for u in intake.prepared.units
    }
    if ordinals.keys() != {u.id for u in replay.account.units if u.kind == "recall"}:
        raise ValueError("analysis recall units differ from timed transcript spans")
    references: dict[str, list[CorrespondenceRow]] = defaultdict(list)
    for row in replay.views.correspondence:
        references[row.detail].append(row)
    bins = tuple(Interval(clock=media.id, start=b.start, end=b.end) for b in query.bins)
    starts = [tick(b.start) for b in query.bins]
    ends = [tick(b.end) for b in query.bins]
    counts = [0] * len(bins)
    missing = outside = 0
    entries = []
    for detail in replay.views.recall:
        ordinal = ordinals[detail.unit]
        onset = onsets[ordinal]
        index = None
        if onset is None:
            missing += 1
        else:
            point_tick = tick(onset)
            pos = bisect_right(starts, point_tick) - 1
            if pos >= 0 and point_tick < ends[pos]:
                index = pos
                counts[pos] += 1
            else:
                outside += 1
        extent = intervals.get(ordinal)
        cells: tuple[TemporalCell, ...] = ()
        if extent is not None:
            support = Interval(clock=media.id, start=extent.start, end=extent.end)
            # Only bins meeting this interval can receive any of its one-detail amount.
            selected = bins[
                bisect_right(ends, tick(extent.start)) : bisect_left(starts, tick(extent.end))
            ]
            cells = allocate("1", support, selected, "uniform-amount")
        entries.append(
            TimedDetail(
                recall=detail,
                correspondence=tuple(references[detail.detail]),
                onset_tick=onset,
                onset_bin=index,
                interval=extent,
                allocations=cells,
            )
        )
    return TimedRecall(
        query=query,
        analysis_sha256=digest(replay.model_dump(mode="json")),
        word_onsets=word_onsets,
        unit_onsets=onsets,
        bins=query.bins,
        total_details=len(entries),
        onset_counts=tuple(counts),
        missing_onset_details=missing,
        outside_bins_details=outside,
        entries=tuple(entries),
    )
