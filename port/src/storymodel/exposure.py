"""Exact recording-window exposure for a replayed recall mapping."""

from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass, field
from fractions import Fraction
from pathlib import Path
from typing import Literal, Self

from pydantic import Field, model_validator

from ._base import Digest, Model, Name, _validate_instance
from ._coordinates import exact, tick
from ._exposure import integrate_exposure
from .analysis_workflow import AnalysisReplay, replay_analysis_file
from .direct_mapping import Interpretation, MappingDecision, MappingInput, MappingOutput
from .model import Inventory
from .playback import TickInterval
from .recall_clock import _bind_onsets, _recall_population, _RecordingBinding
from .serialization import digest


class WordIntervals(Model):
    """Externally measured word extents bound to their original recording."""

    recording_id: Name
    provenance: Name
    by_word: dict[Name, TickInterval] = Field(min_length=1)


class ExposureQuery(_RecordingBinding):
    """One half-open window and measured word extents on the same declared recording."""

    schema_version: Literal["storymodel.exposure-query/v1"] = "storymodel.exposure-query/v1"
    mapping_stage: Name
    window: TickInterval
    overlap: Literal["refuse", "equal-share"] = "refuse"
    word_intervals: WordIntervals | None = None

    @model_validator(mode="after")
    def within_recording(self) -> Self:
        lower, upper = tick(self.recording.start_tick), tick(self.recording.end_tick)
        if not lower <= tick(self.window.start) < tick(self.window.end) <= upper:
            raise ValueError("exposure window outside recording extent")
        if self.word_intervals is None:
            return self
        if self.word_intervals.recording_id != self.recording.id:
            raise ValueError("word intervals belong to another recording")
        for interval in self.word_intervals.by_word.values():
            if not lower <= tick(interval.start) < tick(interval.end) <= upper:
                raise ValueError("word interval outside recording extent")
        return self


class UnitExposure(Model):
    """Time attributed once to a recall unit, with its original processing evidence."""

    inventory: Inventory
    duration_seconds: str
    onset_only_ticks: tuple[str, ...]
    missing_words: int = Field(ge=0)
    decisions: tuple[MappingDecision, ...]
    unrequested_details: tuple[Name, ...]
    mapping_coverage: Literal["complete", "incomplete", "no-details"]


class ExposureMappingStage(Model):
    """Identify the replayed mapping decision batch that contributed to this interpretation."""

    name: Name
    request_sha256: Digest
    response_sha256: Digest


class RecallExposure(Model):
    """A measured duration partition bound to its source files, clock and mapping stage."""

    schema_version: Literal["storymodel.recall-exposure/v1"] = "storymodel.recall-exposure/v1"
    query: ExposureQuery
    analysis_sha256: Digest
    interpretation: Interpretation
    mapping_stages: tuple[ExposureMappingStage, ...]
    units: tuple[UnitExposure, ...]
    unassigned_words: tuple[Name, ...]
    uncovered_seconds: str
    seconds_without_complete_mapping: str


@dataclass
class _UnitTiming:
    ticks: Fraction = Fraction()
    onsets: dict[str, None] = field(default_factory=dict)
    missing: int = 0


def _measure_word_timing(
    membership: Iterable[tuple[str, str | None]],
    word_onsets: dict[str, str | None],
    extents: dict[str, TickInterval],
    window: TickInterval,
    overlap: Literal["refuse", "equal-share"],
) -> tuple[dict[str, _UnitTiming], Fraction, tuple[str, ...]]:
    """Measure original word occurrences on native ticks before joining mapping decisions."""
    if extents.keys() - word_onsets.keys():
        raise ValueError("exposure interval refers to a foreign word")

    start, end = tick(window.start), tick(window.end)
    intervals: list[tuple[str, int, int]] = []
    rows: dict[str, _UnitTiming] = defaultdict(_UnitTiming)
    unassigned = []
    for word, unit in membership:
        point, extent = word_onsets[word], extents.get(word)
        if extent is not None and point is not None and extent.start != point:
            raise ValueError("word interval start differs from its recorded onset")
        if unit is None:
            unassigned.append(word)
            continue
        row = rows[unit]
        if extent is not None:
            intervals.append((unit, tick(extent.start), tick(extent.end)))
        elif point is None:
            row.missing += 1
        elif start <= tick(point) < end:
            row.onsets[point] = None

    durations, uncovered = integrate_exposure(intervals, start, end, overlap)
    for unit, duration in durations.items():
        rows[unit].ticks = duration
    return rows, uncovered, tuple(unassigned)


def recall_exposure(plan_path: Path, query: ExposureQuery) -> RecallExposure:
    """Replay both accounts and the named mapping stage before measuring word intervals."""
    query = _validate_instance(ExposureQuery, query)
    return _exposure_from_replay(replay_analysis_file(plan_path), query)


def _exposure_from_replay(replay: AnalysisReplay, query: ExposureQuery) -> RecallExposure:
    """Measure a checked query using the same verified replay as its downstream consumer."""
    intake, ordinals = _recall_population(replay)
    terminal = next((s for s in replay.stages if s.name == query.mapping_stage), None)
    if (
        terminal is None
        or not isinstance(terminal.request, MappingInput)
        or not isinstance(terminal.result, MappingOutput)
    ):
        raise ValueError("exposure requires a replayed mapping stage with the given name")
    mapping = terminal.result
    interpretation = terminal.request.interpretation
    decisions: dict[str, MappingDecision] = {}
    stages = []
    for stage in replay.stages:
        if (
            isinstance(stage.request, MappingInput)
            and isinstance(stage.result, MappingOutput)
            and stage.request.interpretation == interpretation
        ):
            decisions.update((d.detail, d) for d in stage.result.decisions)
            stages.append(
                ExposureMappingStage(
                    name=stage.name,
                    request_sha256=stage.result.request_sha256,
                    response_sha256=stage.result.response_sha256,
                )
            )
        if stage.name == terminal.name:
            break
    word_onsets, _ = _bind_onsets(intake, query, {})
    extents = query.word_intervals.by_word if query.word_intervals else {}
    by_ordinal = {ordinal: unit for unit, ordinal in ordinals.items()}
    membership = ((w.id, by_ordinal[w.unit] if w.unit is not None else None) for w in intake.words)
    timing, uncovered, unassigned = _measure_word_timing(
        membership, word_onsets, extents, query.window, query.overlap
    )
    seconds = exact(query.recording.seconds_per_tick)
    inventories = {i.unit: i for i in mapping.account.inventories}
    by_unit: dict[str, list[str]] = defaultdict(list)
    for detail in mapping.account.details:
        by_unit[detail.unit].append(detail.id)
    rows = []
    incomplete = Fraction()
    for unit in sorted(ordinals, key=ordinals.__getitem__):
        inventory = inventories.get(unit)
        if inventory is None:
            inventory = Inventory(unit=unit, status="not-attempted", reason="no extraction record")
        detail_ids = sorted(by_unit[unit])
        available = tuple(decisions[d] for d in detail_ids if d in decisions)
        unrequested = tuple(d for d in detail_ids if d not in decisions)
        coverage: Literal["complete", "incomplete", "no-details"]
        if (
            inventory.status != "completed"
            or unrequested
            or any(d.status != "admitted" for d in available)
        ):
            coverage = "incomplete"
        elif not detail_ids:
            coverage = "no-details"
        else:
            coverage = "complete"
        observed = timing.get(unit) or _UnitTiming()
        duration = observed.ticks * seconds
        if coverage != "complete":
            incomplete += duration
        rows.append(
            UnitExposure(
                inventory=inventory,
                duration_seconds=str(duration),
                onset_only_ticks=tuple(observed.onsets),
                missing_words=observed.missing,
                decisions=available,
                unrequested_details=unrequested,
                mapping_coverage=coverage,
            )
        )
    return RecallExposure(
        query=query,
        analysis_sha256=digest(replay.model_dump(mode="json")),
        interpretation=interpretation,
        mapping_stages=tuple(stages),
        units=tuple(rows),
        unassigned_words=unassigned,
        uncovered_seconds=str(uncovered * seconds),
        seconds_without_complete_mapping=str(incomplete),
    )
