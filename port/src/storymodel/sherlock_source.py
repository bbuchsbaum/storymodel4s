"""Retained Sherlock TSV intake under the checked v2 clock-repair declaration.

The notebook repair is validated as provenance, but playback is always projected
from raw, run-local annotation seconds onto the declared part-local clock.
"""

from __future__ import annotations

import json
from decimal import Decimal, InvalidOperation
from fractions import Fraction
from hashlib import sha256
from typing import Any, Literal, Self, cast

from pydantic import Field, model_validator

from .model import Digest, Model, Name, _validate_instance
from .serialization import canonical, strict_json
from .source_preparation import (
    AnnotatedSourceRow,
    DeclaredPart,
    PreparedSource,
    SourceAnnotations,
    prepare_source_utf8,
)

_SCHEMA = "storymodel4s.sherlock.timebase-repair"
_VERSION = 2
_RAW_AXIS = "annotation-run-local-seconds (raw)"
_FORMULA = "playbackTicks = rawAnnotationSeconds * ticksPerSecond"
_HEADER = (
    "Segment Number\tStart Time (s) \tEnd Time (s) \tStart TR\tEnd TR\tScene Segments\t"
    "Scene Details - A Level \tSpace-In/Outdoor\tName - All\tName - Focus\tName - Speaking\t"
    "Location\tCamera Angle\tMusic Presence \tWords on Screen "
)
_HEADER_COLUMNS = tuple(column.strip() for column in _HEADER.split("\t")[:12])
_RESTRICTIONS = {
    "clockRepairMayEstablish": [
        "deterministic-published-analysis-order",
    ],
    "clockRepairDoesNotEstablish": [
        "exact-media-edition",
        "frame-exact-evidence",
        "missing-tr-imputation",
        "accepted-narrative-semantics",
    ],
    "editionIdentityEstablishes": [
        "declared-presentation-edition",
        "per-part-playback-axis",
        "exact-annotation-to-playback-crosswalk",
    ],
    "editionIdentityDoesNotEstablish": [
        "accepted-narrative-semantics",
        "recall-to-playback-alignment",
        "media-reachability-at-any-later-time",
    ],
}


def _fail(message: str) -> None:
    raise ValueError(f"sherlock repair: {message}")


def _mapping(value: Any, path: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        _fail(f"{path} must be an object")
    return cast(dict[str, Any], value)


def _list(value: Any, path: str) -> list[Any]:
    if not isinstance(value, list):
        _fail(f"{path} must be an array")
    return cast(list[Any], value)


def _field(value: dict[str, Any], name: str, path: str) -> Any:
    if name not in value:
        _fail(f"{path}/{name} is missing")
    return value[name]


def _string(value: Any, path: str) -> str:
    if not isinstance(value, str) or not value.strip():
        _fail(f"{path} must be a nonblank string")
    return cast(str, value)


def _integer(value: Any, path: str) -> int:
    if type(value) is not int:
        _fail(f"{path} must be an integer")
    return cast(int, value)


def _sha(value: Any, path: str) -> str:
    text = _string(value, path)
    if len(text) != 64 or any(char not in "0123456789abcdef" for char in text):
        _fail(f"{path} must be a lowercase SHA-256")
    return text


def _decimal(value: Any, path: str) -> Decimal:
    if type(value) not in (int, float, Decimal) or isinstance(value, bool):
        _fail(f"{path} must be a finite JSON number")
    try:
        parsed = Decimal(str(value))
    except InvalidOperation as error:
        raise ValueError(f"sherlock repair: {path} is not decimal") from error
    if not parsed.is_finite():
        _fail(f"{path} must be finite")
    return parsed


def _scaled_equals(value: Any, factor: int, target: int, path: str) -> bool:
    """Compare a parsed JSON decimal exactly, independent of Decimal context precision."""
    return Fraction(_decimal(value, path)) * factor == target


def _equal(value: Any, expected: Any, path: str) -> None:
    if (type(expected) is bool and type(value) is not bool) or value != expected:
        _fail(f"{path} is unsupported or inconsistent")


def _pair(value: Any, path: str, separator: str, *, ordered: bool) -> tuple[int, int]:
    text = _string(value, path)
    pieces = text.split(separator)
    if len(pieces) != 2 or any(not p.isascii() or not p.isdecimal() for p in pieces):
        _fail(f"{path} must be a positive integer pair")
    first, last = (int(piece) for piece in pieces)
    if first <= 0 or last <= 0 or (ordered and last < first):
        _fail(f"{path} must be a positive integer pair")
    return first, last


def _range(value: Any, path: str) -> tuple[int, int]:
    return _pair(value, path, "-", ordered=True)


def _ratio(value: Any, path: str) -> tuple[int, int]:
    return _pair(value, path, "/", ordered=False)


def _cell(cells: tuple[str, ...], index: int) -> str | None:
    return cells[index].strip() or None if index < len(cells) else None


class SherlockRow(Model):
    """One retained raw annotation row, including prompt-reproducible cast text."""

    row: int = Field(gt=0)
    raw_start_seconds: int = Field(ge=0)
    raw_end_seconds: int = Field(ge=0)
    start_tr: int | None = Field(default=None, gt=0)
    end_tr: int | None = Field(default=None, gt=0)
    scene_label: str | None = None
    description: str = Field(min_length=1)
    space: str | None = None
    names_all: tuple[str, ...] = ()
    names_speaking: tuple[str, ...] = ()
    location: str | None = None
    names_all_cell: str | None = None
    cells: tuple[str, ...] = Field(min_length=7)

    @model_validator(mode="after")
    def bounds(self) -> Self:
        if self.raw_end_seconds < self.raw_start_seconds:
            raise ValueError("row end precedes its start")
        if (self.start_tr is None) != (self.end_tr is None):
            raise ValueError("scanner TR bounds must both be present or both be absent")
        if self.start_tr is not None and self.end_tr is not None and self.end_tr < self.start_tr:
            raise ValueError("scanner TR end precedes its start")
        return self


class SherlockPart(Model):
    """One declared media part with its exact native playback clock."""

    part_id: Name
    ordinal: int = Field(gt=0)
    sha256: Digest
    duration_ticks: int = Field(gt=0)
    ticks_per_second: int = Field(gt=0)
    ticks_per_frame: int = Field(gt=0)


class SherlockLocus(Model):
    """A row's exact native media locus; an instant remains distinct from an extent."""

    part_id: Name
    start_tick: int = Field(ge=0)
    end_tick: int = Field(ge=0)
    kind: Literal["extent", "instant"]

    @model_validator(mode="after")
    def shape(self) -> Self:
        if self.kind == "extent" and self.end_tick <= self.start_tick:
            raise ValueError("extent requires increasing ticks")
        if self.kind == "instant" and self.end_tick != self.start_tick:
            raise ValueError("instant requires equal ticks")
        return self


class SherlockScene(Model):
    """A maximal consecutive row run opened by an admitted scene label."""

    ordinal: int = Field(gt=0)
    label: str = Field(min_length=1)
    first_row: int = Field(gt=0)
    last_row: int = Field(gt=0)

    @model_validator(mode="after")
    def ordered(self) -> Self:
        if self.last_row < self.first_row:
            raise ValueError("scene rows are reversed")
        return self


class SherlockSource(Model):
    """Raw Sherlock TSV plus the exact repair declaration and checked portable rows."""

    schema_version: Literal["storymodel.sherlock-source/v1"] = "storymodel.sherlock-source/v1"
    key: Name
    raw_text: str
    repair_text: str
    raw_sha256: Digest
    repair_sha256: Digest
    header: tuple[str, ...] = Field(min_length=12)
    rows: tuple[SherlockRow, ...] = Field(min_length=1)
    parts: tuple[SherlockPart, ...] = Field(min_length=2, max_length=2)
    loci: tuple[SherlockLocus, ...] = Field(min_length=1)
    scenes: tuple[SherlockScene, ...] = Field(min_length=1)
    repair: dict[str, Any]
    prepared_source: PreparedSource

    @property
    def prepared(self) -> PreparedSource:
        """Return the fully checked generic source view without dropping source receipts."""
        return self.prepared_source

    @model_validator(mode="after")
    def receipt(self) -> Self:
        rebuilt = _prepare(
            self.key, self.raw_text.encode("utf-8"), self.repair_text.encode("utf-8")
        )
        if rebuilt != self:
            raise ValueError("Sherlock source differs from its retained raw receipts")
        return self


def _parse_rows(raw: bytes) -> tuple[str, tuple[str, ...], tuple[SherlockRow, ...]]:
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValueError("Sherlock annotations must be strict UTF-8") from error
    lines = text.splitlines()
    if not lines:
        _fail("annotation TSV is empty")
    header = tuple(lines[0].removeprefix("\ufeff").split("\t"))
    if len(header) < 12 or tuple(cell.strip() for cell in header[:12]) != _HEADER_COLUMNS:
        _fail("annotation TSV header differs from required Microsegment1000 columns")
    if lines[-1] == "":
        lines.pop()
    if any(not line for line in lines[1:]):
        _fail("annotation TSV has an empty data row")
    rows: list[SherlockRow] = []
    for expected, line in enumerate(lines[1:], 1):
        cells = tuple(line.split("\t"))
        if len(cells) < 7:
            _fail(f"row {expected} has fewer than seven TSV cells")

        def integer_cell(index: int, label: str) -> int:
            value = cells[index].strip()
            if not value.isascii() or not value.isdecimal():
                _fail(f"row {expected} {label} is not an integer")
            return int(value)

        row = integer_cell(0, "number")
        if row != expected:
            _fail(f"row {expected} numbering breaks: found {row}")
        start, end = integer_cell(1, "start seconds"), integer_cell(2, "end seconds")
        if end < start:
            _fail(f"row {row} has reversed bounds")

        def optional_int(index: int, label: str) -> int | None:
            value = _cell(cells, index)
            if value is None:
                return None
            if not value.isascii() or not value.isdecimal():
                _fail(f"row {row} {label} is not an integer")
            return int(value)

        start_tr, end_tr = optional_int(3, "start TR"), optional_int(4, "end TR")
        description = _cell(cells, 6)
        if description is None:
            _fail(f"row {row} has an empty scene-detail description")
        assert description is not None
        names_all = _cell(cells, 8)
        speaking = _cell(cells, 10)
        rows.append(
            SherlockRow(
                row=row,
                raw_start_seconds=start,
                raw_end_seconds=end,
                start_tr=start_tr,
                end_tr=end_tr,
                scene_label=_cell(cells, 5),
                description=description,
                space=_cell(cells, 7),
                names_all=tuple(
                    x.strip() for x in (names_all or "").replace("/", ",").split(",") if x.strip()
                ),
                names_speaking=tuple(
                    x.strip() for x in (speaking or "").replace("/", ",").split(",") if x.strip()
                ),
                location=_cell(cells, 11),
                names_all_cell=names_all,
                cells=cells,
            )
        )
    return text, header, tuple(rows)


def _system(repair: dict[str, Any], identifier: str) -> dict[str, Any]:
    systems = _list(_field(repair, "coordinateSystems", "record"), "coordinateSystems")
    found = [item for item in systems if isinstance(item, dict) and item.get("id") == identifier]
    if len(found) != 1:
        _fail(f"coordinate system {identifier} is missing or duplicated")
    return found[0]


def _validate_repair(
    repair: dict[str, Any], raw_sha: str, rows: tuple[SherlockRow, ...]
) -> tuple[tuple[SherlockPart, ...], tuple[SherlockLocus, ...]]:
    _equal(_field(repair, "schema", "record"), _SCHEMA, "schema")
    _equal(_field(repair, "schemaVersion", "record"), _VERSION, "schemaVersion")
    _string(_field(repair, "artifactId", "record"), "artifactId")
    annotation = _mapping(_field(repair, "inputAnnotation", "record"), "inputAnnotation")
    _string(_field(annotation, "artifactId", "inputAnnotation"), "inputAnnotation/artifactId")
    _equal(
        _sha(_field(annotation, "sha256", "inputAnnotation"), "inputAnnotation/sha256"),
        raw_sha,
        "inputAnnotation/sha256",
    )
    repair_block = _mapping(_field(repair, "repair", "record"), "repair")
    input_rows = _integer(_field(repair_block, "inputRows", "repair"), "repair/inputRows")
    if input_rows != len(rows) or input_rows <= 0:
        _fail("repair/inputRows differs from annotation rows")
    edition = _mapping(
        _field(repair, "presentationEditionIdentity", "record"), "presentationEditionIdentity"
    )
    for name, expected in (
        ("authority", "owner-declaration-plus-byte-identity"),
        ("declaredBy", "owner"),
        ("bytesDisposition", "external-to-git-and-not-redistributed"),
        ("filenamesUsedAsIdentity", False),
        ("pathsRecorded", False),
    ):
        _equal(
            _field(edition, name, "presentationEditionIdentity"),
            expected,
            f"presentationEditionIdentity/{name}",
        )
    edition_id = _string(
        _field(edition, "id", "presentationEditionIdentity"), "presentationEditionIdentity/id"
    )
    _string(
        _field(edition, "declaration", "presentationEditionIdentity"),
        "presentationEditionIdentity/declaration",
    )
    raw_parts = _list(
        _field(edition, "parts", "presentationEditionIdentity"), "presentationEditionIdentity/parts"
    )
    if len(raw_parts) != 2:
        _fail("presentation edition needs exactly two parts")
    parts: list[SherlockPart] = []
    for index, candidate in enumerate(raw_parts, 1):
        part_data = _mapping(candidate, f"presentationEditionIdentity/parts/{index - 1}")
        video = _mapping(_field(part_data, "video", "part"), "part/video")
        _equal(
            _integer(_field(part_data, "presentationOrdinal", "part"), "part/presentationOrdinal"),
            index,
            "part/presentationOrdinal",
        )
        _equal(_field(part_data, "primaryStream", "part"), "video", "part/primaryStream")
        if _integer(_field(part_data, "byteLength", "part"), "part/byteLength") <= 0:
            _fail("part byte length must be positive")
        rate = _integer(_field(video, "ticksPerSecond", "video"), "video/ticksPerSecond")
        duration = _integer(_field(video, "durationTicks", "video"), "video/durationTicks")
        per_frame = _integer(_field(video, "ticksPerFrame", "video"), "video/ticksPerFrame")
        frames = _integer(_field(video, "frameCount", "video"), "video/frameCount")
        if min(rate, duration, per_frame, frames) <= 0 or frames * per_frame != duration:
            _fail("video frame extent disagrees")
        _equal(_field(video, "timeBase", "video"), f"1/{rate}", "video/timeBase")
        _equal(_field(video, "constantFrameRate", "video"), True, "video/constantFrameRate")
        _equal(_integer(_field(video, "startPts", "video"), "video/startPts"), 0, "video/startPts")
        numerator, denominator = _ratio(_field(video, "frameRate", "video"), "video/frameRate")
        if numerator * per_frame != rate * denominator:
            _fail("video frame rate disagrees")
        if not _scaled_equals(
            _field(video, "durationSeconds", "video"), rate, duration, "video/durationSeconds"
        ):
            _fail("video seconds and ticks disagree")
        parts.append(
            SherlockPart(
                part_id=_string(_field(part_data, "partId", "part"), "part/partId"),
                ordinal=index,
                sha256=_sha(_field(part_data, "sha256", "part"), "part/sha256"),
                duration_ticks=duration,
                ticks_per_second=rate,
                ticks_per_frame=per_frame,
            )
        )
    if len({part.part_id for part in parts}) != 2 or len({part.sha256 for part in parts}) != 2:
        _fail("duplicate part identity")
    systems = _list(_field(repair, "coordinateSystems", "record"), "coordinateSystems")
    ids = [
        _string(
            _field(_mapping(item, "coordinateSystem"), "id", "coordinateSystem"),
            "coordinateSystem/id",
        )
        for item in systems
    ]
    base_ids = {
        "media-part-local-seconds",
        "annotation-run-local-seconds",
        "scanner-tr",
        "topic-notebook-repaired-seconds",
        "recall-princeton-seconds-and-tr",
        "recall-openneuro-seconds-and-tr",
    }
    if len(ids) != len(set(ids)) or not base_ids.issubset(ids):
        _fail("unsupported coordinate system")
    local = _system(repair, "media-part-local-seconds")
    _equal(
        _field(local, "kind", "media-part-local-seconds"),
        "container-presentation-time",
        "media-part-local-seconds/kind",
    )
    _equal(
        _field(local, "experimentalEditionReceipt", "media-part-local-seconds"),
        edition_id,
        "media-part-local-seconds/experimentalEditionReceipt",
    )
    local_parts = _list(
        _field(local, "parts", "media-part-local-seconds"), "media-part-local-seconds/parts"
    )
    if len(local_parts) != 2:
        _fail("local seconds requires two parts")
    for part, local_part in zip(parts, local_parts, strict=True):
        lp = _mapping(local_part, "media local part")
        _equal(_field(lp, "partId", "media local part"), part.part_id, "media local part/partId")
        if _decimal(
            _field(lp, "startSeconds", "media local part"), "startSeconds"
        ) != 0 or not _scaled_equals(
            _field(lp, "endSeconds", "media local part"),
            part.ticks_per_second,
            part.duration_ticks,
            "endSeconds",
        ):
            _fail("local seconds extent disagrees")
    crosswalk = _mapping(
        _field(repair, "annotationToPlaybackCrosswalk", "record"), "annotationToPlaybackCrosswalk"
    )
    for name, expected in (
        ("status", "established"),
        ("editionReceipt", edition_id),
        ("certifies", "coordinate-mapping-only"),
        ("doesNotCertify", "media-reachability"),
        ("sourceCoordinate", _RAW_AXIS),
        ("explicitlyNotUsed", "topic-notebook-repaired-seconds"),
        ("tailHandling", "observed-not-trimmed-not-imputed"),
    ):
        _equal(_field(crosswalk, name, "crosswalk"), expected, f"crosswalk/{name}")
    _string(_field(crosswalk, "id", "crosswalk"), "crosswalk/id")
    _string(_field(crosswalk, "whyNotRepaired", "crosswalk"), "crosswalk/whyNotRepaired")
    runs = _list(_field(crosswalk, "runs", "crosswalk"), "crosswalk/runs")
    if len(runs) != 2:
        _fail("crosswalk needs two runs")
    parsed_runs: list[tuple[dict[str, Any], tuple[int, int]]] = []
    loci: list[SherlockLocus] = []
    for index, (candidate, part) in enumerate(zip(runs, parts, strict=True), 1):
        run = _mapping(candidate, f"crosswalk/runs/{index - 1}")
        _equal(_field(run, "runId", "run"), f"run-{index}", "run/runId")
        _equal(_field(run, "partId", "run"), part.part_id, "run/partId")
        axis_id = _string(_field(run, "axisId", "run"), "run/axisId")
        for name, expected in (
            ("sourceCoordinate", _RAW_AXIS),
            ("mapping", "identity"),
            ("formula", _FORMULA),
        ):
            _equal(_field(run, name, "run"), expected, f"run/{name}")
        first, last = _range(_field(run, "annotationRows", "run"), "run/annotationRows")
        start, end = (
            _integer(_field(run, "annotationStartSeconds", "run"), "run/start"),
            _integer(_field(run, "annotationEndSeconds", "run"), "run/end"),
        )
        end_ticks, tail = (
            _integer(_field(run, "playbackEndTicks", "run"), "run/end ticks"),
            _integer(_field(run, "uncoveredTailTicks", "run"), "run/tail"),
        )
        if (
            start < 0
            or end < start
            or tail < 0
            or start * part.ticks_per_second
            != _integer(_field(run, "playbackStartTicks", "run"), "run/start ticks")
            or end * part.ticks_per_second != end_ticks
            or end_ticks + tail != part.duration_ticks
        ):
            _fail("run contradicts the identity crosswalk")
        if (
            _integer(_field(run, "playbackEndFrame", "run"), "run/end frame") * part.ticks_per_frame
            != end_ticks
            or _integer(_field(run, "uncoveredTailFrames", "run"), "run/tail frame")
            * part.ticks_per_frame
            != tail
            or not _scaled_equals(
                _field(run, "uncoveredTailSeconds", "run"),
                part.ticks_per_second,
                tail,
                "run/tail seconds",
            )
        ):
            _fail("run frame or tail declaration disagrees")
        axis = _system(repair, axis_id)
        axis_fields: tuple[tuple[str, Any], ...] = (
            ("kind", "edition-playback-time"),
            ("status", "established"),
            ("partId", part.part_id),
            ("editionReceipt", edition_id),
            ("timeBase", f"1/{part.ticks_per_second}"),
            ("ticksPerSecond", part.ticks_per_second),
            ("ticksPerFrame", part.ticks_per_frame),
            ("startPts", 0),
            ("durationTicks", part.duration_ticks),
            ("frameCount", part.duration_ticks // part.ticks_per_frame),
        )
        for name, expected in axis_fields:
            _equal(_field(axis, name, "playback axis"), expected, f"playback axis/{name}")
        if not _scaled_equals(
            _field(axis, "durationSeconds", "playback axis"),
            part.ticks_per_second,
            part.duration_ticks,
            "playback axis/durationSeconds",
        ):
            _fail("playback axis duration seconds disagree")
        parsed_runs.append((run, (first, last)))
    if (
        parsed_runs[0][1][0] != 1
        or parsed_runs[0][1][1] + 1 != parsed_runs[1][1][0]
        or parsed_runs[1][1][1] != len(rows)
    ):
        _fail("run partition has a gap, overlap, or wrong extent")
    axis_ids = [run[0]["axisId"] for run in parsed_runs]
    if len(set(axis_ids)) != 2 or set(ids) != base_ids | set(axis_ids):
        _fail("unsupported coordinate system")
    exactness = _mapping(_field(crosswalk, "exactness", "crosswalk"), "crosswalk/exactness")
    _equal(
        _integer(
            _field(exactness, "annotationTimeValues", "exactness"), "exactness/annotationTimeValues"
        ),
        len(rows) * 2,
        "exactness/annotationTimeValues",
    )
    for name in ("nonIntegerValues", "valuesOffFrameBoundary", "valuesOffPtsTick"):
        _equal(
            _integer(_field(exactness, name, "exactness"), f"exactness/{name}"),
            0,
            f"exactness/{name}",
        )
    for (run, (first, last)), part in zip(parsed_runs, parts, strict=True):
        selected = rows[first - 1 : last]
        if (
            not selected
            or selected[0].raw_start_seconds != run["annotationStartSeconds"]
            or selected[-1].raw_end_seconds != run["annotationEndSeconds"]
        ):
            _fail("run raw boundary differs from annotation input")
        for row in selected:
            if (
                not run["annotationStartSeconds"]
                <= row.raw_start_seconds
                <= row.raw_end_seconds
                <= run["annotationEndSeconds"]
            ):
                _fail("row escapes declared run extent")
            start_tick, end_tick = (
                row.raw_start_seconds * part.ticks_per_second,
                row.raw_end_seconds * part.ticks_per_second,
            )
            if start_tick % part.ticks_per_frame or end_tick % part.ticks_per_frame:
                _fail("row is off the declared exact frame boundary")
            loci.append(
                SherlockLocus(
                    part_id=part.part_id,
                    start_tick=start_tick,
                    end_tick=end_tick,
                    kind="instant" if start_tick == end_tick else "extent",
                )
            )
    _validate_notebook(repair, rows, parsed_runs, parts)
    non_equivalences = _list(_field(repair, "nonEquivalences", "record"), "nonEquivalences")
    if not non_equivalences or any(
        not isinstance(item, str) or not item.strip() for item in non_equivalences
    ):
        _fail("nonEquivalences must contain nonblank explanations")
    derivation = _mapping(_field(repair, "sourceDerivation", "record"), "sourceDerivation")
    if (
        _integer(
            _field(derivation, "byteLength", "sourceDerivation"), "sourceDerivation/byteLength"
        )
        <= 0
    ):
        _fail("sourceDerivation byteLength must be positive")
    _sha(_field(derivation, "sha256", "sourceDerivation"), "sourceDerivation/sha256")
    for name in ("commit", "gitBlob"):
        value = _string(_field(derivation, name, "sourceDerivation"), f"sourceDerivation/{name}")
        if len(value) != 40 or any(char not in "0123456789abcdef" for char in value):
            _fail(f"sourceDerivation/{name} must be a lowercase git identifier")
    return tuple(parts), tuple(loci)


def _validate_notebook(
    repair: dict[str, Any],
    rows: tuple[SherlockRow, ...],
    parsed_runs: list[tuple[dict[str, Any], tuple[int, int]]],
    parts: list[SherlockPart],
) -> None:
    block = _mapping(_field(repair, "repair", "record"), "repair")
    drops = _list(_field(block, "dropSourceRows", "repair"), "repair/dropSourceRows")
    if not drops or any(type(row) is not int for row in drops) or drops != sorted(set(drops)):
        _fail("repair/dropSourceRows must be unique ordered rows")
    run_one, run_two = parsed_runs
    raw_axis = _system(repair, "annotation-run-local-seconds")
    _equal(_field(raw_axis, "kind", "raw axis"), "coder-table-interval", "raw axis/kind")
    raw_runs = _list(_field(raw_axis, "runs", "raw axis"), "raw axis/runs")
    if len(raw_runs) != 2:
        _fail("raw annotation axis needs two runs")
    raw_one = _mapping(raw_runs[0], "raw run one")
    raw_two = _mapping(raw_runs[1], "raw run two")
    _equal(_field(raw_one, "runId", "raw run one"), "run-1", "raw run one/runId")
    _equal(_field(raw_two, "runId", "raw run two"), "run-2", "raw run two/runId")
    ordinary = _range(_field(raw_one, "ordinaryRows", "raw run one"), "raw run one/ordinaryRows")
    if (
        ordinary[0] != 1
        or ordinary[1] + len(drops) != run_one[1][1]
        or drops != list(range(ordinary[1] + 1, run_one[1][1] + 1))
    ):
        _fail("ordinary and scan-break ranges disagree")
    if (
        raw_one.get("scanBreakRows") != drops
        or raw_one.get("ordinaryStartSeconds") != run_one[0]["annotationStartSeconds"]
        or raw_one.get("rawEndIncludingScanBreakSeconds") != run_one[0]["annotationEndSeconds"]
        or _range(_field(raw_two, "rows", "raw run two"), "raw run two/rows") != run_two[1]
        or raw_two.get("startSeconds") != run_two[0]["annotationStartSeconds"]
        or raw_two.get("endSeconds") != run_two[0]["annotationEndSeconds"]
    ):
        _fail("raw run declaration differs from crosswalk")
    offset = _mapping(_field(block, "offsetSeconds", "repair"), "repair/offsetSeconds")
    _equal(_integer(_field(offset, "run-1", "offset"), "offset/run-1"), 0, "offset/run-1")
    second_offset = _integer(_field(offset, "run-2", "offset"), "offset/run-2")
    ordinary_end = _integer(
        _field(raw_one, "ordinaryEndSeconds", "raw run one"), "raw run one/ordinaryEndSeconds"
    )
    if second_offset != ordinary_end or second_offset > run_one[0]["annotationEndSeconds"]:
        _fail("run-2 offset must equal ordinary run-1 end")
    assignments = _list(_field(block, "runAssignment", "repair"), "repair/runAssignment")
    if len(assignments) != 2:
        _fail("repair needs two run assignments")
    for assignment, run_id, expected_rows in zip(
        assignments, ("run-1", "run-2"), (ordinary, run_two[1]), strict=True
    ):
        item = _mapping(assignment, "repair/runAssignment")
        if (
            item.get("runId") != run_id
            or _range(
                _field(item, "sourceRows", "repair/runAssignment"),
                "repair/runAssignment/sourceRows",
            )
            != expected_rows
        ):
            _fail("repair run assignment differs from raw axis")
    _equal(_field(block, "purity", "repair"), "pure-coordinate-transform", "repair/purity")
    _equal(
        _mapping(_field(block, "formula", "repair"), "repair/formula"),
        {
            "repairedStartSeconds": "rawStartSeconds + offsetSeconds(runId)",
            "repairedEndSeconds": "rawEndSeconds + offsetSeconds(runId)",
        },
        "repair/formula",
    )
    retained = len(rows) - len(drops)
    _equal(
        _integer(_field(block, "retainedRows", "repair"), "repair/retainedRows"),
        retained,
        "repair/retainedRows",
    )
    scanner = _system(repair, "scanner-tr")
    _equal(_field(scanner, "kind", "scanner"), "scanner-index", "scanner/kind")
    if _decimal(_field(scanner, "secondsPerTr", "scanner"), "scanner/secondsPerTr") <= 0:
        _fail("scanner secondsPerTr must be positive")
    present = [value for row in rows for value in (row.start_tr, row.end_tr) if value is not None]
    if (
        not present
        or _integer(_field(scanner, "minimumTr", "scanner"), "scanner/minimumTr") != min(present)
        or _integer(_field(scanner, "maximumTr", "scanner"), "scanner/maximumTr") != max(present)
        or _field(scanner, "rowsWithMissingBounds", "scanner") != drops
        or _field(scanner, "missingBoundsImputed", "scanner") is not False
    ):
        _fail("scanner extent or missingness differs from rows")
    if (
        any(
            (row.start_tr is None) != (row.end_tr is None)
            or (row.start_tr is not None and row.end_tr is not None and row.end_tr < row.start_tr)
            for row in rows
        )
        or [row.row for row in rows if row.start_tr is None] != drops
    ):
        _fail("scanner TR intervals differ from declared missingness")
    notebook = _system(repair, "topic-notebook-repaired-seconds")
    if (
        notebook.get("kind") != "derived-analysis-time"
        or notebook.get("rowCount") != retained
        or notebook.get("startSeconds") != 0
        or notebook.get("endSeconds") != second_offset + run_two[0]["annotationEndSeconds"]
        or notebook.get("derivationReceipt") != block.get("id")
    ):
        _fail("notebook derived-axis declaration disagrees")
    boundary_checks = _list(_field(block, "boundaryChecks", "repair"), "repair/boundaryChecks")
    if len(boundary_checks) != 3:
        _fail("repair boundary checks differ")
    for item, row_number, run_id in zip(
        boundary_checks,
        (ordinary[1], run_two[1][0], len(rows)),
        ("run-1", "run-2", "run-2"),
        strict=True,
    ):
        row = rows[row_number - 1]
        declared = _mapping(item, "repair/boundaryChecks")
        offset_seconds = 0 if run_id == "run-1" else second_offset
        if (
            declared.get("sourceRow") != row_number
            or declared.get("runId") != run_id
            or declared.get("rawStartSeconds") != row.raw_start_seconds
            or declared.get("rawEndSeconds") != row.raw_end_seconds
            or declared.get("repairedStartSeconds") != row.raw_start_seconds + offset_seconds
            or declared.get("repairedEndSeconds") != row.raw_end_seconds + offset_seconds
            or declared.get("startTr") != row.start_tr
            or declared.get("endTr") != row.end_tr
        ):
            _fail("repair boundary check differs from raw input")
    receipt = _mapping(
        _field(block, "serializationReceipt", "repair"), "repair/serializationReceipt"
    )
    fields = [
        "source-row-zero-padded-to-four-digits",
        "run-id",
        "raw-start-seconds-base10-integer",
        "raw-end-seconds-base10-integer",
        "repaired-start-seconds-base10-integer",
        "repaired-end-seconds-base10-integer",
        "start-tr-base10-integer-or-empty",
        "end-tr-base10-integer-or-empty",
    ]
    for name, expected in (
        ("recordOrder", "ascending source row"),
        ("fields", fields),
        ("fieldSeparator", "TAB"),
        ("recordSeparator", "LF"),
        ("finalRecordSeparator", True),
        ("recordCount", retained),
    ):
        _equal(
            _field(receipt, name, "serializationReceipt"), expected, f"serializationReceipt/{name}"
        )

    def serialized_row(row: SherlockRow) -> str:
        run_id = "run-1" if row.row <= run_one[1][1] else "run-2"
        offset_seconds = 0 if run_id == "run-1" else second_offset
        return (
            "\t".join(
                (
                    f"{row.row:04d}",
                    run_id,
                    str(row.raw_start_seconds),
                    str(row.raw_end_seconds),
                    str(row.raw_start_seconds + offset_seconds),
                    str(row.raw_end_seconds + offset_seconds),
                    "" if row.start_tr is None else str(row.start_tr),
                    "" if row.end_tr is None else str(row.end_tr),
                )
            )
            + "\n"
        )

    replay = "".join(serialized_row(row) for row in rows if row.row not in drops)
    _equal(
        _sha(_field(receipt, "sha256", "serializationReceipt"), "serializationReceipt/sha256"),
        sha256(replay.encode()).hexdigest(),
        "serializationReceipt/sha256",
    )
    restrictions = _mapping(
        _field(repair, "scientificRestrictions", "record"), "scientificRestrictions"
    )
    if set(restrictions) != set(_RESTRICTIONS):
        _fail("unknown scientific restriction family")
    for name, expected in _RESTRICTIONS.items():
        actual = _list(restrictions[name], f"scientificRestrictions/{name}")
        if name == "clockRepairMayEstablish":
            expected = expected + [f"replayable-{retained}-row-derived-axis"]
        _equal(actual, expected, f"scientificRestrictions/{name}")
    tails = _mapping(_field(repair, "missingnessAndTails", "record"), "missingnessAndTails")
    _equal(
        _field(tails, "tailHandling", "tails"),
        "observed-not-trimmed-not-imputed",
        "tails/tailHandling",
    )
    breaks = _list(_field(tails, "scanBreakRows", "tails"), "tails/scanBreakRows")
    if len(breaks) != len(drops):
        _fail("tail declaration omits scan breaks")
    for dropped, declared in zip(drops, breaks, strict=True):
        row = rows[dropped - 1]
        item = _mapping(declared, "scan break")
        if (
            item.get("sourceRow") != dropped
            or item.get("startSeconds") != row.raw_start_seconds
            or item.get("endSeconds") != row.raw_end_seconds
            or item.get("trBounds") != "missing"
        ):
            _fail("scan break declaration differs from raw row")
    for part, name, run in zip(parts, ("partA", "partB"), parsed_runs, strict=True):
        tail = _mapping(_field(tails, name, "tails"), f"tails/{name}")
        if not _scaled_equals(
            _field(tail, "mediaEndSeconds", "tail"),
            part.ticks_per_second,
            part.duration_ticks,
            "tail/mediaEndSeconds",
        ):
            _fail("media tail extent disagrees")
        end_key = (
            "rawAnnotationEndIncludingBreakSeconds" if name == "partA" else "annotationEndSeconds"
        )
        tail_key = (
            "rawAnnotationToMediaTailSeconds" if name == "partA" else "annotationToMediaTailSeconds"
        )
        if tail.get(end_key) != run[0]["annotationEndSeconds"] or not _scaled_equals(
            _field(tail, tail_key, "tail"),
            part.ticks_per_second,
            run[0]["uncoveredTailTicks"],
            "tail/tail seconds",
        ):
            _fail("annotation tail declaration disagrees")
    part_a = _mapping(_field(tails, "partA", "tails"), "tails/partA")
    if (
        part_a.get("retainedNotebookAnnotationEndSeconds") != second_offset
        or (
            Fraction(
                _decimal(
                    _field(part_a, "retainedNotebookAnnotationToMediaTailSeconds", "tails/partA"),
                    "tails/partA/retained notebook tail",
                )
            )
            + second_offset
        )
        * parts[0].ticks_per_second
        != parts[0].duration_ticks
    ):
        _fail("retained notebook tail declaration disagrees")


def _prepared(
    key: str,
    rows: tuple[SherlockRow, ...],
    parts: tuple[SherlockPart, ...],
    loci: tuple[SherlockLocus, ...],
) -> PreparedSource:
    ordinals: dict[str, int] = {part.part_id: 0 for part in parts}
    prepared_rows: list[dict[str, Any]] = []
    for row, locus in zip(rows, loci, strict=True):
        ordinals[locus.part_id] += 1
        prepared_rows.append(
            {
                "key": f"sherlock:row:{row.row:04d}",
                "part": locus.part_id,
                "ordinal": ordinals[locus.part_id],
                "text": row.description,
                "native_start_tick": str(locus.start_tick),
                "native_end_tick": str(locus.end_tick),
                "scene": f"sherlock:scene:{row.scene_label}" if row.scene_label else None,
                "cast": row.names_all or None,
                "location": row.location,
            }
        )
    annotations = SourceAnnotations(
        parts=tuple(
            DeclaredPart.model_validate(
                {
                    "key": part.part_id,
                    "ordinal": part.ordinal,
                    "clock": {
                        "key": part.part_id,
                        "start_tick": "0",
                        "end_tick": str(part.duration_ticks),
                        "seconds_per_tick": f"1/{part.ticks_per_second}",
                    },
                }
            )
            for part in parts
        ),
        rows=tuple(AnnotatedSourceRow.model_validate(row) for row in prepared_rows),
    )
    return prepare_source_utf8(key, canonical(annotations.model_dump(mode="json")).encode())


def _prepare(key: str, raw: bytes, repair_bytes: bytes) -> SherlockSource:
    if not isinstance(key, str) or not key:
        raise ValueError("Sherlock source key must be nonblank")
    if not isinstance(raw, bytes) or not isinstance(repair_bytes, bytes):
        raise ValueError("Sherlock receipts must be bytes")
    raw_text, header, rows = _parse_rows(raw)
    try:
        repair_text = repair_bytes.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValueError("Sherlock repair declaration must be strict UTF-8") from error
    # ``strict_json`` supplies the duplicate-key/nonfinite guard.  Validation then
    # reparses finite decimal literals exactly: a binary float would quietly erase
    # a tiny declaration disagreement that Scala's BigDecimal refuses.
    repair = _mapping(strict_json(repair_text), "repair record")
    exact_repair = _mapping(json.loads(repair_text, parse_float=Decimal), "repair record")
    raw_sha, repair_sha = sha256(raw).hexdigest(), sha256(repair_bytes).hexdigest()
    parts, loci = _validate_repair(exact_repair, raw_sha, rows)
    prepared = _prepared(key, rows, parts, loci)
    opens = [row for row in rows if row.scene_label is not None]
    if not opens:
        _fail("the first row must open a scene")
    if rows[0].scene_label is None:
        _fail("the first row must open a scene")
    scenes = tuple(
        SherlockScene(
            ordinal=index,
            label=row.scene_label or "",
            first_row=row.row,
            last_row=opens[index].row - 1 if index < len(opens) else rows[-1].row,
        )
        for index, row in enumerate(opens, 1)
    )
    return SherlockSource.model_construct(
        key=key,
        raw_text=raw_text,
        repair_text=repair_text,
        raw_sha256=raw_sha,
        repair_sha256=repair_sha,
        header=header,
        rows=rows,
        parts=parts,
        loci=loci,
        scenes=scenes,
        repair=repair,
        prepared_source=prepared,
    )


def prepare_sherlock_source(key: str, raw: bytes, repair: bytes) -> SherlockSource:
    """Retain, validate, and derive one admitted Sherlock source receipt pair."""
    value = _prepare(key, raw, repair)
    return _validate_instance(SherlockSource, value)


def validate_sherlock_source(value: SherlockSource) -> SherlockSource:
    """Revalidate raw TSV, repair JSON, and every source-derived row before consumption."""
    return _validate_instance(SherlockSource, value)
