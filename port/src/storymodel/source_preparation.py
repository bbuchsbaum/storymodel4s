"""Checked source-text intake from exact UTF-8 annotation receipts.

This boundary only establishes document rows and optional native coordinates.  It
does not infer a narrative graph or turn part-local clocks into a global clock.
"""

from __future__ import annotations

from hashlib import sha256
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from ._coordinates import tick
from .model import Digest, Document, Model, Name, Span, _validate_instance
from .serialization import canonical, strict_json
from .source import SourceRow
from .source_intake import NativePart

PositiveInt = Annotated[int, Field(gt=0)]


class DeclaredPart(Model):
    """An ordered source part, optionally with its own native coordinate system."""

    key: Name
    ordinal: PositiveInt
    clock: NativePart | None = None

    @model_validator(mode="after")
    def matching_clock(self) -> Self:
        if self.clock is not None and self.clock.key != self.key:
            raise ValueError("part clock key differs from its declared part")
        return self


class AnnotatedSourceRow(Model):
    """A nonsemantic source row with text and optional part-local annotations."""

    key: Name
    part: Name
    ordinal: PositiveInt
    text: str = Field(min_length=1)
    native_start_tick: str | None = None
    native_end_tick: str | None = None
    scene: Name | None = None
    cast: tuple[Name, ...] | None = None
    location: Name | None = None

    @model_validator(mode="after")
    def row_clock_shape(self) -> Self:
        if not self.text.strip():
            raise ValueError("source row text must be nonblank")
        if self.native_end_tick is not None and self.native_start_tick is None:
            raise ValueError("native row end requires a native row start")
        if self.native_start_tick is not None:
            start = tick(self.native_start_tick)
            if self.native_end_tick is not None and tick(self.native_end_tick) < start:
                raise ValueError("native row end precedes its start")
        return self


class SourceAnnotations(Model):
    """The complete v1 wire input retained by a prepared-source receipt."""

    schema_version: Literal["storymodel.source-annotations/v1"] = "storymodel.source-annotations/v1"
    parts: tuple[DeclaredPart, ...] = Field(min_length=1)
    rows: tuple[AnnotatedSourceRow, ...] = Field(min_length=1)

    @model_validator(mode="after")
    def declared_rows(self) -> Self:
        part_keys = {part.key for part in self.parts}
        part_ordinals = {part.ordinal for part in self.parts}
        if len(part_keys) != len(self.parts) or len(part_ordinals) != len(self.parts):
            raise ValueError("source parts need unique keys and ordinals")
        if len({row.key for row in self.rows}) != len(self.rows):
            raise ValueError("source rows need unique keys")
        row_slots = {(row.part, row.ordinal) for row in self.rows}
        if len(row_slots) != len(self.rows):
            raise ValueError("source row ordinals must be unique within a part")
        parts = {part.key: part for part in self.parts}
        for row in self.rows:
            part = parts.get(row.part)
            if part is None:
                raise ValueError("source row names an undeclared part")
            if row.native_start_tick is None:
                continue
            if part.clock is None:
                raise ValueError("native row timing requires its part clock")
            start = tick(row.native_start_tick)
            end = tick(row.native_end_tick) if row.native_end_tick is not None else start
            if not tick(part.clock.start_tick) <= start <= end <= tick(part.clock.end_tick):
                raise ValueError("native row timing lies outside its declared part")
        return self


def _parse_raw(raw_text: str) -> SourceAnnotations:
    """Parse one exact receipt while refusing duplicate JSON fields."""
    parsed = strict_json(raw_text)
    if not isinstance(parsed, dict):
        raise ValueError("source annotations must be a JSON object")
    return SourceAnnotations.model_validate_json(canonical(parsed))


def _derived(annotations: SourceAnnotations, key: str) -> tuple[Document, tuple[SourceRow, ...]]:
    ordered_parts = sorted(annotations.parts, key=lambda part: part.ordinal)
    part_order = {part.key: index for index, part in enumerate(ordered_parts)}
    ordered_rows = sorted(annotations.rows, key=lambda row: (part_order[row.part], row.ordinal))
    text = "\n".join(row.text for row in ordered_rows)
    document = Document.create(key, "source", text)
    offset = 0
    rows: list[SourceRow] = []
    for index, row in enumerate(ordered_rows):
        # Each row owns its following delimiter.  Consequently the row spans are
        # adjacent and cover the exact synthesized document, without losing the
        # original row text retained in ``annotations``.
        end = offset + len(row.text.encode("utf-16-le")) // 2
        if index + 1 < len(ordered_rows):
            end += 1
        rows.append(SourceRow(key=row.key, span=Span(start=offset, end=end)))
        offset = end
    return document, tuple(rows)


class PreparedSource(Model):
    """An exact raw annotation receipt and its independently derivable text rows."""

    schema_version: Literal["storymodel.prepared-source/v1"] = "storymodel.prepared-source/v1"
    key: Name
    raw_text: str
    raw_sha256: Digest
    annotations: SourceAnnotations
    document: Document
    rows: tuple[SourceRow, ...] = Field(min_length=1)

    @property
    def parsed(self) -> SourceAnnotations:
        """Expose the typed original annotation input without reparsing it ad hoc."""
        return self.annotations

    @model_validator(mode="after")
    def receipt(self) -> Self:
        raw_bytes = self.raw_text.encode("utf-8")
        if sha256(raw_bytes).hexdigest() != self.raw_sha256:
            raise ValueError("prepared source raw SHA-256 differs")
        annotations = _parse_raw(self.raw_text)
        if annotations != self.annotations:
            raise ValueError("prepared source annotations differ from exact raw JSON")
        document, rows = _derived(annotations, self.key)
        if self.document != document or self.rows != rows:
            raise ValueError("prepared source document or rows differ from exact annotations")
        return self


def prepare_source_utf8(key: str, raw: bytes) -> PreparedSource:
    """Prepare exact UTF-8 JSON annotations without normalizing their receipt bytes."""
    if not isinstance(raw, bytes):
        raise ValueError("source annotations must be supplied as UTF-8 bytes")
    try:
        raw_text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValueError("source annotations must be valid UTF-8") from error
    annotations = _parse_raw(raw_text)
    document, rows = _derived(annotations, key)
    return PreparedSource(
        key=key,
        raw_text=raw_text,
        raw_sha256=sha256(raw).hexdigest(),
        annotations=annotations,
        document=document,
        rows=rows,
    )


def validate_prepared_source(value: PreparedSource) -> PreparedSource:
    """Recompute raw parsing and every derived source relation before consumption."""
    return _validate_instance(PreparedSource, value)
