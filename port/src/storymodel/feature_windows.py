"""Lexical measures over occurrence-preserving transcript, scene and recording windows."""

from bisect import bisect_right
from dataclasses import dataclass
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
from .recall_intake import _token_spans
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


@dataclass(frozen=True, slots=True)
class LexicalToken:
    """One classified surface token, preserving Scala's word/number distinction."""

    form: str
    normalized: str | None
    lexical: bool
    codepoint_length: int


class LexicalLedger:
    """Classify occurrences once and retain whole-text lexical type frequencies.

    Repeated forms retain separate entries.  The frequency is a property of each
    occurrence's normalized lexical type, never an identity for that occurrence.
    """

    __slots__ = ("_frequencies", "_tokens")

    def __init__(self, forms: list[str] | tuple[str, ...]) -> None:
        if not isinstance(forms, (tuple, list)) or not all(isinstance(f, str) for f in forms):
            raise TypeError(
                "supply a list or tuple of surface forms, or use from_text for a string"
            )
        self._tokens = tuple(_classify(form) for form in forms)
        self._frequencies: dict[str, int] | None = None

    @classmethod
    def from_text(cls, text: str) -> "LexicalLedger":
        """Classify the port's ordinary transcript tokens in their source order."""
        if not isinstance(text, str):
            raise TypeError("from_text requires a transcript string")
        spans = _token_spans(text, (0, len(text)))
        return cls([text[start:end] for start, end in spans])

    @property
    def tokens(self) -> tuple[LexicalToken, ...]:
        """The immutable, occurrence-preserving classifications."""
        return self._tokens

    def type_frequency(self, index: int) -> int | None:
        """Return the lexical type count, or ``None`` for an ineligible token."""
        token = self.tokens[index]
        if token.normalized is None:
            return None
        if self._frequencies is None:
            frequencies: dict[str, int] = {}
            for entry in self.tokens:
                if entry.normalized is not None:
                    frequencies[entry.normalized] = frequencies.get(entry.normalized, 0) + 1
            self._frequencies = frequencies
        return self._frequencies[token.normalized]


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
    ineligible_recall: dict[str, Span]
    missing_onset: tuple[str, ...]
    outside_windows: tuple[str, ...]
    unit_intervals: dict[int, Summary]
    source_scenes: dict[str, Summary]


def _eligible(form: str) -> bool:
    """Apply the Scala surface word/number test to a complete token."""
    return _classify(form).lexical


def _classify(form: str) -> LexicalToken:
    """Mirror ``SurfaceSequence.classify`` for a complete surface token."""
    if any(ch.isalpha() for ch in form):
        return LexicalToken(form, lower(form), True, len(form))
    if not any(ch.isdecimal() for ch in form):
        return LexicalToken(form, None, False, len(form))
    for i, ch in enumerate(form):
        if ch.isdecimal() or ch == "_":
            continue
        if ch not in ".," or i == 0 or i + 1 == len(form):
            return LexicalToken(form, None, False, len(form))
        if not (form[i - 1].isdecimal() and form[i + 1].isdecimal()):
            return LexicalToken(form, None, False, len(form))
    return LexicalToken(form, form, True, len(form))


def _sample(
    occurrence: str, span: Span, token: LexicalToken, onset: str | None, norms: Lexicon
) -> WordSample:
    return WordSample(
        occurrence=occurrence,
        span=span,
        form=token.form,
        onset_tick=onset,
        length=token.codepoint_length,
        lexicon_score=norms.entries.get(token.normalized) if token.normalized is not None else None,
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
            spans = _token_spans(text, (0, len(text)))
            ledger = LexicalLedger([text[begin:end] for begin, end in spans])
            for (begin, end), token in zip(spans, ledger.tokens, strict=True):
                if token.lexical:
                    span = Span(start=offsets[begin], end=offsets[end])
                    scene_words.append(
                        _sample(
                            f"{source_document}@{span.start}-{span.end}",
                            span,
                            token,
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
    ineligible = {}
    missing, outside = [], []
    intervals: dict[int, list[WordSample]] = {i.unit: [] for i in query.intervals}
    recall_forms = [
        encoded[2 * word.span.start : 2 * word.span.end].decode("utf-16-le")
        for word in recall.words
    ]
    ledger = LexicalLedger(recall_forms)
    for word, token in zip(recall.words, ledger.tokens, strict=True):
        span = word.span
        if not token.lexical:
            ineligible[word.id] = span
            continue
        onset = timed.word_onsets[word.id]
        sample = _sample(word.id, span, token, onset, lexicon)
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
        ineligible_recall=ineligible,
        missing_onset=tuple(missing),
        outside_windows=tuple(outside),
        unit_intervals={u: _summarize(words) for u, words in intervals.items()},
        source_scenes=scenes,
    )
