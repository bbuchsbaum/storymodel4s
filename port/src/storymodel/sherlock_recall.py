"""Exact Sherlock word-CSV replay and separate, unaligned external clock readouts."""

from __future__ import annotations

import csv
import io
import re
from fractions import Fraction
from hashlib import sha256
from typing import Literal, Self

from pydantic import Field, model_validator

from ._coordinates import fraction_text
from .model import Digest, Model, Name, Span, _validate_instance
from .recall_intake import (
    PreparedRecall,
    _token_spans,
    _word_code_point,
    prepare_recall,
)
from .serialization import canonical, digest, strict_json

SHERLOCK_RECALL_HEADER = (
    "Words",
    "Princeton Dataspace Word Onset Time (sec)",
    "Princeton Dataspace TR Number",
    "Princeton Dataspace TR Onset (sec)",
    "OpenNeuro Word Onset Time (sec)",
    "OpenNeuro TR Number",
    "OpenNeuro TR Onset (sec)",
)
_ASCII_TRIM = "".join(chr(i) for i in range(33))
_DECIMAL = re.compile(r"([+-]?)([0-9]*)(?:\.([0-9]*))?(?:[eE]([+-]?[0-9]+))?")


def _utf8(raw: bytes) -> str:
    if not isinstance(raw, bytes):
        raise ValueError("Sherlock intake requires exact UTF-8 bytes")
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValueError("invalid Sherlock UTF-8") from error


def _records(text: str) -> tuple[tuple[str, ...], ...]:
    """Use standard CSV parsing, additionally rejecting quotes inside unquoted fields."""
    text = text.removeprefix("\ufeff")
    state = "start"
    for character in text:
        if state == "quoted":
            if character == '"':
                state = "closed"
        elif character in ",\r\n":
            state = "start"
        elif state == "start":
            state = "quoted" if character == '"' else "unquoted"
        elif state == "closed" and character == '"':
            state = "quoted"
        elif state == "closed" or character == '"':
            raise ValueError("invalid Sherlock CSV quoting")
    if state == "quoted":
        raise ValueError("unterminated Sherlock CSV quote")
    try:
        # newline='' preserves line endings inside quoted cells. A blank physical
        # record is a one-cell record, matching the retained strict Scala parser.
        return tuple(
            tuple(row) if row else ("",)
            for row in csv.reader(io.StringIO(text, newline=""), strict=True)
        )
    except csv.Error as error:
        raise ValueError("invalid Sherlock CSV") from error


class SherlockRecallRecord(Model):
    """Every CSV row survives, including blank words and uninterpreted clock columns."""

    number: int = Field(ge=1)
    cells: tuple[str, ...] = Field(min_length=7, max_length=7)
    joined_span: Span | None


class SherlockRecallWord(Model):
    """A lexical occurrence binds the CSV byte identity and zero-based parsed index."""

    id: Name
    index: int = Field(ge=0)
    span: Span
    unit: int | None = Field(default=None, ge=1)


def _fields(key: str, source_id: str, raw_text: str, lineage_text: str) -> dict[str, object]:
    raw = raw_text.encode("utf-8")
    raw_sha = sha256(raw).hexdigest()
    lineage = strict_json(lineage_text)
    if (
        not isinstance(lineage, dict)
        or lineage.get("schema") != "storymodel4s.sherlock.recall-lineage"
        or type(lineage.get("schemaVersion")) is not int
        or lineage["schemaVersion"] != 1
    ):
        raise ValueError("unsupported Sherlock recall lineage")
    release = lineage.get("publicRelease")
    if (
        not isinstance(release, dict)
        or not isinstance(release.get("tag"), str)
        or not release["tag"].strip()
        or not isinstance(release.get("commit"), str)
        or re.fullmatch(r"[0-9a-f]{40}", release["commit"]) is None
    ):
        raise ValueError("invalid Sherlock recall release identity")
    sources = lineage.get("sources")
    if not isinstance(sources, list) or not all(isinstance(item, dict) for item in sources):
        raise ValueError("invalid Sherlock recall source entries")
    matches = [item for item in sources if item.get("id") == source_id]
    if len(matches) != 1:
        raise ValueError("Sherlock recall source must have exactly one lineage entry")
    entry = matches[0]
    if (
        type(entry.get("localCsvByteLength")) is not int
        or entry["localCsvByteLength"] != len(raw)
        or entry.get("localCsvSha256") != raw_sha
    ):
        raise ValueError("Sherlock recall CSV differs from lineage byte identity")
    count = entry.get("recordCount")
    if type(count) is not int or count <= 0:
        raise ValueError("invalid Sherlock recall record count")
    table = _records(raw_text)
    if not table or table[0] != SHERLOCK_RECALL_HEADER:
        raise ValueError("Sherlock recall header mismatch")
    rows = table[1:]
    if len(rows) != count or any(len(row) != 7 for row in rows):
        raise ValueError("Sherlock recall record or column count mismatch")
    words = [row[0].strip(_ASCII_TRIM) for row in rows]
    joined = " ".join(word for word in words if word)
    prepared = prepare_recall(key, joined)
    offset = 0
    records = []
    for number, (row, word) in enumerate(zip(rows, words, strict=True), 1):
        span = None
        if word:
            end = offset + len(word.encode("utf-16-le")) // 2
            span = Span(start=offset, end=end)
            offset = end + 1
        records.append(SherlockRecallRecord(number=number, cells=row, joined_span=span))
    text = prepared.canonical_text
    offsets = [0]
    for character in text:
        offsets.append(offsets[-1] + (2 if ord(character) > 0xFFFF else 1))
    lexical: list[SherlockRecallWord] = []
    unit_index = 0
    for start, end in _token_spans(text, (0, len(text))):
        if not _word_code_point(text[start]):
            continue
        span = Span(start=offsets[start], end=offsets[end])
        while (
            unit_index < len(prepared.units)
            and prepared.units[unit_index].support.end <= span.start
        ):
            unit_index += 1
        unit = prepared.units[unit_index] if unit_index < len(prepared.units) else None
        member = (
            unit.u
            if unit is not None
            and unit.support.start <= span.start
            and span.end <= unit.support.end
            else None
        )
        index = len(lexical)
        lexical.append(
            SherlockRecallWord(
                id="sherlock-word/v1:" + digest({"csv_sha256": raw_sha, "index": index}),
                index=index,
                span=span,
                unit=member,
            )
        )
    return {
        "schema_version": "storymodel.sherlock-recall/v1",
        "recipe": "sherlock-word-csv-replay/v1",
        "key": key,
        "source_id": source_id,
        "raw_text": raw_text,
        "raw_sha256": raw_sha,
        "lineage_text": lineage_text,
        "lineage_sha256": sha256(lineage_text.encode("utf-8")).hexdigest(),
        "records": [row.model_dump(mode="json") for row in records],
        "words": [word.model_dump(mode="json") for word in lexical],
        "prepared": prepared.model_dump(mode="json"),
    }


class SherlockRecall(Model):
    """Replayed word CSV and actual lineage; lineage does not grant export permission."""

    schema_version: Literal["storymodel.sherlock-recall/v1"] = "storymodel.sherlock-recall/v1"
    recipe: Literal["sherlock-word-csv-replay/v1"] = "sherlock-word-csv-replay/v1"
    key: Name
    source_id: Name
    raw_text: str
    raw_sha256: Digest
    lineage_text: str
    lineage_sha256: Digest
    records: tuple[SherlockRecallRecord, ...]
    words: tuple[SherlockRecallWord, ...]
    prepared: PreparedRecall

    @model_validator(mode="after")
    def replay(self) -> Self:
        if self.model_dump(mode="json") != _fields(
            self.key, self.source_id, self.raw_text, self.lineage_text
        ):
            raise ValueError("Sherlock recall differs from exact CSV and lineage replay")
        return self


def prepare_sherlock_recall(
    key: str, raw: bytes, lineage: bytes, *, source_id: str
) -> SherlockRecall:
    """Replay a declared CSV byte identity; no public-disclosure authority is inferred."""
    return SherlockRecall.model_validate_json(
        canonical(_fields(key, source_id, _utf8(raw), _utf8(lineage)))
    )


def validate_sherlock_recall(value: SherlockRecall) -> SherlockRecall:
    """Reject stale or copied derivatives before using text or timing."""
    return _validate_instance(SherlockRecall, value)


def _seconds(cell: str) -> str | None:
    token = cell.strip()
    if not token:
        return None
    if len(token) > 512:
        raise ValueError("recall onset exceeds decimal resource limit")
    match = _DECIMAL.fullmatch(token)
    if match is None:
        raise ValueError("malformed recall onset")
    sign, whole, fraction, exponent_text = match.groups()
    digits = whole + (fraction or "")
    exponent = int(exponent_text or "0")
    if not digits:
        raise ValueError("malformed recall onset")
    if len(digits) > 128 or abs(exponent) > 128:
        raise ValueError("recall onset exceeds decimal resource limit")
    scale = len(fraction or "") - exponent
    coefficient = int(digits) * (-1 if sign == "-" else 1)
    seconds = Fraction(coefficient * 10 ** max(-scale, 0), 10 ** max(scale, 0))
    # Python's exact integers remove the Scala Long representation limit. The
    # decimal length/exponent limits above still bound parsing work; no rounding.
    return fraction_text(seconds)


class SherlockTimingRecord(Model):
    """A source-reported onset, or explicit blank-cell missingness, on one declared clock."""

    record: int
    word: Name | None
    status: Literal["matched", "excluded-blank-word"]
    onset_seconds: str | None


class SherlockUnitOnset(Model):
    """The first assigned lexical word's onset; no search for a later nonmissing value."""

    unit: int
    first_word: Name | None
    onset_seconds: str | None


class SherlockRecallTiming(Model):
    """Exact lexical timing correspondence does not establish recording or scanner identity."""

    schema_version: Literal["storymodel.sherlock-recall-timing/v1"] = (
        "storymodel.sherlock-recall-timing/v1"
    )
    intake_sha256: Digest
    clock: Literal["princeton", "openneuro"]
    recording_identity: Literal["unestablished"] = "unestablished"
    origin: Literal["unestablished"] = "unestablished"
    recording_link: Literal["not-established"] = "not-established"
    records: tuple[SherlockTimingRecord, ...]
    unit_onsets: tuple[SherlockUnitOnset, ...]


def sherlock_recall_timing(
    value: SherlockRecall, clock: Literal["princeton", "openneuro"]
) -> SherlockRecallTiming:
    """Bind exactly one lexical word per nonblank CSV cell; never split a cell's onset."""
    intake = validate_sherlock_recall(value)
    if clock not in ("princeton", "openneuro"):
        raise ValueError("unknown Sherlock recall clock")
    if intake.prepared.raw_text != intake.prepared.canonical_text:
        raise ValueError("recall timing replay requires a character map after canonicalization")
    column = 1 if clock == "princeton" else 4
    result = []
    word_index = 0
    first_by_unit: dict[int, tuple[str, str | None]] = {}
    for row in intake.records:
        seconds = _seconds(row.cells[column])
        word = None
        if row.joined_span is not None:
            if word_index >= len(intake.words):
                raise ValueError("each nonblank recall CSV cell must contain one lexical word")
            word = intake.words[word_index]
            span = row.joined_span
            if not span.start <= word.span.start < word.span.end <= span.end:
                raise ValueError("each nonblank recall CSV cell must contain one lexical word")
            word_index += 1
            if word_index < len(intake.words) and intake.words[word_index].span.start < span.end:
                raise ValueError("one CSV onset cannot be divided among multiple lexical words")
            if word.unit is not None and word.unit not in first_by_unit:
                first_by_unit[word.unit] = (word.id, seconds)
        result.append(
            SherlockTimingRecord(
                record=row.number,
                word=word.id if word else None,
                status="matched" if word else "excluded-blank-word",
                onset_seconds=seconds,
            )
        )
    if word_index != len(intake.words):
        raise ValueError("CSV timing does not cover the lexical inventory")
    return SherlockRecallTiming(
        intake_sha256=digest(intake.model_dump(mode="json")),
        clock=clock,
        records=tuple(result),
        unit_onsets=tuple(
            SherlockUnitOnset(
                unit=unit.u,
                first_word=first_by_unit.get(unit.u, (None, None))[0],
                onset_seconds=first_by_unit.get(unit.u, (None, None))[1],
            )
            for unit in intake.prepared.units
        ),
    )


def verify_sherlock_recall_timing(
    value: SherlockRecall, receipt: SherlockRecallTiming
) -> SherlockRecallTiming:
    """Recompute timing against actual intake rather than trusting a hash-only readout."""
    checked = _validate_instance(SherlockRecallTiming, receipt)
    expected = sherlock_recall_timing(value, checked.clock)
    if checked != expected:
        raise ValueError("Sherlock timing differs from exact intake")
    return expected
