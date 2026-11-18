"""Lexical measures over occurrence-preserving transcript, scene and recording windows."""

from bisect import bisect_right
from fractions import Fraction
from pathlib import Path
from typing import Literal

from pydantic import Field, FiniteFloat, field_validator

from ._base import Digest, Identified, Model, Name, _validate_instance
from ._lexical import lower
from .analysis_workflow import replay_analysis_file
from .model import Span
from .playback import TickInterval
from .prepared_construction import PreparedConstruction
from .recall_clock import RecordingQuery, TimedDetail, _timed_replay
from .recall_intake import _token_spans, _word_code_point
from .sherlock_recall import SherlockRecall
from .sherlock_source import SherlockSource


class Lexicon(Identified):
    """A named scalar lexicon with normalization and content-bound provenance."""

    namespace = "lexical-norm/v1"
    name: Name
    units: Name
    entries: dict[str, FiniteFloat] = Field(min_length=1)

    @field_validator("name")
    @classmethod
    def valid_name(cls, value: str) -> str:
        if any(ch.isspace() or ch in ":/" for ch in value):
            raise ValueError("lexicon name cannot contain whitespace, colon or slash")
        return value

    @field_validator("entries")
    @classmethod
    def normalize(cls, entries: dict[str, float]) -> dict[str, float]:
        result: dict[str, float] = {}
        for raw, value in entries.items():
            key = lower(raw.strip())
            if not key or (key in result and result[key] != value):
                raise ValueError("empty or conflicting normalized lexicon key")
            result[key] = 0.0 if value == 0 else value
        return result


class WordSample(Model):
    occurrence: Name
    span: Span
    form: str
    onset_tick: str | None
    length: int
    lexicon_score: float | None


class Mean(Model):
    measured: int
    missing: int
    mean: float | None


class Summary(Model):
    occurrences: tuple[str, ...]
    token_length: Mean
    lexicon: Mean


class Window(Model):
    interval: TickInterval
    words: Summary
    detail_ids: tuple[str, ...]


class FeatureWindows(Model):
    schema_version: Literal["storymodel.feature-windows/v1"] = "storymodel.feature-windows/v1"
    analysis_sha256: Digest
    query: RecordingQuery
    lexicon: Lexicon
    recall_document: Name
    source_document: Name | None
    recall_words: tuple[WordSample, ...]
    source_words: tuple[WordSample, ...]
    details: tuple[TimedDetail, ...]
    windows: tuple[Window, ...]
    missing_onset: tuple[str, ...]
    outside_windows: tuple[str, ...]
    unit_intervals: dict[int, Summary]
    source_scenes: dict[str, Summary]


def _sample(
    occurrence: str, span: Span, form: str, onset: str | None, norms: Lexicon
) -> WordSample:
    return WordSample(
        occurrence=occurrence,
        span=span,
        form=form,
        onset_tick=onset,
        length=len(form),
        lexicon_score=norms.entries.get(lower(form)),
    )


def _mean(values: list[int | float], eligible: int) -> Mean:
    # Exact accumulation also keeps a mean of large, finite values finite.
    return Mean(
        measured=len(values),
        missing=eligible - len(values),
        mean=float(sum(map(Fraction, values)) / len(values)) if values else None,
    )


def _summarize(words: list[WordSample]) -> Summary:
    return Summary(
        occurrences=tuple(w.occurrence for w in words),
        token_length=_mean([w.length for w in words], len(words)),
        lexicon=_mean([w.lexicon_score for w in words if w.lexicon_score is not None], len(words)),
    )


def _scene_features(
    source: SherlockSource, lexicon: Lexicon
) -> tuple[list[WordSample], dict[str, Summary]]:
    """Group source-annotation words by declared scene, retaining document-relative UTF-16 spans."""
    source_words = []
    scenes = {}
    prepared = source.prepared
    source_document = prepared.document.id
    raw = prepared.document.text.encode("utf-16-le")
    for scene in source.scenes:
        scene_words = []
        for row in prepared.rows[scene.first_row - 1 : scene.last_row]:
            text = raw[2 * row.span.start : 2 * row.span.end].decode("utf-16-le")
            offsets = [row.span.start]
            for ch in text:
                offsets.append(offsets[-1] + (2 if ord(ch) > 0xFFFF else 1))
            for begin, end in _token_spans(text, (0, len(text))):
                if _word_code_point(text[begin]):
                    span = Span(start=offsets[begin], end=offsets[end])
                    scene_words.append(
                        _sample(
                            f"{source_document}@{span.start}-{span.end}",
                            span,
                            text[begin:end],
                            None,
                            lexicon,
                        )
                    )
        scenes[f"sherlock:scene:{scene.ordinal:03d}"] = _summarize(scene_words)
        source_words.extend(scene_words)
    return source_words, scenes


def feature_windows(plan: Path, query: RecordingQuery, lexicon: Lexicon) -> FeatureWindows:
    """Compute both measures over the same eligible lexical occurrences in each window."""
    query = _validate_instance(RecordingQuery, query)
    lexicon = _validate_instance(Lexicon, lexicon)
    replay = replay_analysis_file(plan)
    timed = _timed_replay(replay, query)
    request = replay.recall.request
    if not isinstance(request, PreparedConstruction) or not isinstance(
        request.intake, SherlockRecall
    ):
        raise ValueError("expected a prepared Sherlock recall")
    recall = request.intake
    encoded = recall.prepared.canonical_text.encode("utf-16-le")
    starts = [int(b.start) for b in query.bins]
    groups: list[list[WordSample]] = [[] for _ in starts]
    samples = []
    missing, outside = [], []
    intervals: dict[int, list[WordSample]] = {i.unit: [] for i in query.intervals}
    for word in recall.words:
        span = word.span
        form = encoded[2 * span.start : 2 * span.end].decode("utf-16-le")
        onset = timed.word_onsets[word.id]
        sample = _sample(word.id, span, form, onset, lexicon)
        samples.append(sample)
        if onset is None:
            missing.append(word.id)
            continue
        if word.unit in intervals and word.unit is not None:
            intervals[word.unit].append(sample)
        point = int(onset)
        index = bisect_right(starts, point) - 1
        if index >= 0 and point < int(query.bins[index].end):
            groups[index].append(sample)
        else:
            outside.append(word.id)
    details: list[list[str]] = [[] for _ in starts]
    for entry in timed.entries:
        if entry.onset_bin is not None:
            details[entry.onset_bin].append(entry.recall.detail)
    windows = tuple(
        Window(
            interval=b,
            words=_summarize(groups[i]),
            detail_ids=tuple(details[i]),
        )
        for i, b in enumerate(query.bins)
    )
    source_words: list[WordSample] = []
    scenes: dict[str, Summary] = {}
    source_document = None
    source = replay.source.request
    if isinstance(source, PreparedConstruction) and isinstance(source.intake, SherlockSource):
        source_document = source.intake.prepared.document.id
        source_words, scenes = _scene_features(source.intake, lexicon)
    return FeatureWindows(
        analysis_sha256=timed.analysis_sha256,
        query=query,
        lexicon=lexicon,
        recall_document=recall.prepared.document.id,
        source_document=source_document,
        recall_words=tuple(samples),
        source_words=tuple(source_words),
        details=timed.entries,
        windows=windows,
        missing_onset=tuple(missing),
        outside_windows=tuple(outside),
        unit_intervals={u: _summarize(words) for u, words in intervals.items()},
        source_scenes=scenes,
    )
