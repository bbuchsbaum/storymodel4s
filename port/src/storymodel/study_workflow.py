"""One offline supplied-recall workflow, from recorded text to checked time mappings.

The pilot uses strict JSON framing and generated prompts. It preserves the Scala
strict-chunked-time/v1 interpretation, without claiming historical prompt/cache identity.
"""

from __future__ import annotations

import hashlib
import json
import math
from collections import Counter
from pathlib import Path
from typing import Annotated, Any, Literal, Self

from pydantic import Field, JsonValue, field_validator, model_validator

from .artifacts import publish_json
from .execution import RecordedRun, RunProfile
from .model import Model, Name, canonical, digest
from .study_time import StudySource, resolve_time_item

RECIPE: Literal["storymodel.supplied-time-study/v1"] = "storymodel.supplied-time-study/v1"


def strict_json(raw: str | bytes) -> Any:
    """Reject duplicate keys and nonfinite constants before schema/algorithm admission."""

    def unique(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON field: {key}")
            result[key] = value
        return result

    def invalid(value: str) -> None:
        raise ValueError(f"nonfinite JSON constant: {value}")

    def finite_float(value: str) -> float:
        parsed = float(value)
        if not math.isfinite(parsed):
            raise ValueError("nonfinite JSON number")
        return parsed

    return json.loads(
        raw, object_pairs_hook=unique, parse_constant=invalid, parse_float=finite_float
    )


class SuppliedUnit(Model):
    """A supplied segmentation keeps its study row key and repeated occurrences."""

    u: Annotated[int, Field(gt=0, le=2**31 - 1)]
    row: JsonValue
    text: Name

    @field_validator("row")
    @classmethod
    def finite_row(cls, value: JsonValue) -> JsonValue:
        canonical(value)
        return value


class SuppliedRecall(Model):
    key: Name
    units: Annotated[tuple[SuppliedUnit, ...], Field(min_length=1)]

    @model_validator(mode="after")
    def ordered(self) -> Self:
        if tuple(u.u for u in self.units) != tuple(range(1, len(self.units) + 1)):
            raise ValueError("supplied unit ordinals must be contiguous from one")
        for unit in self.units:
            unit.text.encode("utf-8")
        return self


class TimeStudy(Model):
    """Independent source and recall intake, plus explicit execution policy."""

    schema_version: Literal["storymodel.time-study/v1"] = "storymodel.time-study/v1"
    admission: dict[str, str] = Field(default_factory=dict)
    source_key: Name
    source: StudySource
    recall: SuppliedRecall
    chunk_size: Annotated[int, Field(gt=0)] = 50
    max_attempts: Annotated[int, Field(ge=1, le=3)] = 1
    export_permission: Literal["allowed", "denied"] = "denied"


class RawReply(Model):
    chunk: Annotated[int, Field(ge=0)]
    attempt: Annotated[int, Field(ge=0)]
    text: str


class ImportedReplies(Model):
    """Raw responses bound to an independently supplied request digest."""

    schema_version: Literal["storymodel.imported-replies/v1"] = "storymodel.imported-replies/v1"
    origin: Name
    request_sha256: str
    replies: tuple[RawReply, ...]


class ChunkReceipt(Model):
    chunk: int
    attempts: int
    accepted: bool
    items_outside_chunk: int
    repeated_units: int
    response_sha256: tuple[str, ...]


class TimeStudyResult(Model):
    schema_version: Literal["storymodel.time-study-result/v1"] = "storymodel.time-study-result/v1"
    recipe: Literal["storymodel.supplied-time-study/v1"] = RECIPE
    interpretation: Literal["strict-chunked-time/v1"] = "strict-chunked-time/v1"
    request_sha256: str
    source_sha256: str
    recall_sha256: str
    profile_sha256: str
    import_sha256: str
    origin: str
    export_permission: Literal["allowed", "denied"]
    rows: tuple[dict[str, Any], ...]
    receipts: tuple[ChunkReceipt, ...]
    counts: dict[str, dict[str, int]]


def checked_request(value: TimeStudy) -> TimeStudy:
    # Pydantic JSON serialization coerces NaN/Infinity to null before validation.
    # Canonicalize Python values first so a forbidden row cannot become another identity.
    return TimeStudy.model_validate_json(canonical(value.model_dump(mode="json")))


def read_request(path: Path, *, expected_sha256: str | None = None) -> TimeStudy:
    raw = path.read_bytes()
    if expected_sha256 is not None and hashlib.sha256(raw).hexdigest() != expected_sha256:
        raise ValueError("request bytes differ from external SHA-256")
    parsed = strict_json(raw)
    if (
        isinstance(parsed, dict)
        and parsed.get("schema_version") == "storymodel.prepared-time-study/v1"
    ):
        from .intake_workflow import PreparedTimeStudy

        return PreparedTimeStudy.model_validate_json(canonical(parsed)).request
    return TimeStudy.model_validate_json(canonical(parsed))


def request_digest(value: TimeStudy) -> str:
    return digest(checked_request(value).model_dump(mode="json"))


def _profile(request: TimeStudy) -> RunProfile:
    return RunProfile(
        provider="imported-text",
        model="supplied-time-study/v1",
        endpoint="offline:import",
        max_tokens=4096,
        recipe=RECIPE,
        input_sha256=(request_digest(request),),
    )


def _chunks(request: TimeStudy) -> tuple[tuple[int, int], ...]:
    return tuple(
        (i + 1, min(i + request.chunk_size, len(request.recall.units)))
        for i in range(0, len(request.recall.units), request.chunk_size)
    )


def _prompt(request: TimeStudy, chunk: int, attempt: int) -> str:
    lo, hi = _chunks(request)[chunk]
    # All supplied units are visible in every chunk, including repeated text.
    return canonical(
        {
            "recipe": RECIPE,
            "request_sha256": request_digest(request),
            "source": request.source.model_dump(mode="json"),
            "recall": request.recall.model_dump(mode="json"),
            "asked_units": {"lo": lo, "hi": hi},
            "attempt": attempt,
            "instruction": (
                "Return a JSON object with units; each item has integer u, referent_film, "
                "referent_time, narrative_film, narrative_time. Times are m:ss or mm:ss "
                "with up to three decimals. Null means no assignment; optional conf is "
                "uninterpreted."
            ),
        }
    )


def import_time_replies(value: TimeStudy, replies: ImportedReplies, root: Path) -> None:
    """Store exported raw replies using the existing journal/cache, with no model calls.

    The destination must be new. Interrupted imports remain inspectable; choose a new
    directory after diagnosis. A supplied reply is never relabelled as a live provider call.
    """
    request = checked_request(value)
    replies = ImportedReplies.model_validate_json(canonical(strict_json(replies.model_dump_json())))
    if replies.request_sha256 != request_digest(request):
        raise ValueError("imported replies belong to another request")
    chunks = _chunks(request)
    slots: set[tuple[int, int]] = set()
    for reply in replies.replies:
        slot = (reply.chunk, reply.attempt)
        if slot in slots or reply.chunk >= len(chunks) or reply.attempt >= request.max_attempts:
            raise ValueError("duplicate or out-of-range recorded attempt")
        slots.add(slot)
    if not slots or any((i, 0) not in slots for i in range(len(chunks))):
        raise ValueError("each chunk requires its initial recorded attempt")
    for chunk, attempt in slots:
        if any((chunk, previous) not in slots for previous in range(attempt)):
            raise ValueError("recorded attempt sequence has a gap")
    root.mkdir(parents=True, exist_ok=False)
    run = RecordedRun(root, _profile(request))
    for reply in replies.replies:

        def imported_text(_: str) -> str:
            return reply.text

        run.run(
            (_prompt(request, reply.chunk, reply.attempt),),
            transport=imported_text,
            max_new_calls=1,
        )
    publish_json(root / "import.json", replies.model_dump(mode="json"))


def replay_time_study(
    value: TimeStudy, root: Path, *, expected_import_sha256: str | None = None
) -> TimeStudyResult:
    """Replay without a transport, then resolve every supplied unit against its source."""
    request = checked_request(value)
    imported = (root / "import.json").read_bytes()
    import_sha = hashlib.sha256(imported).hexdigest()
    if expected_import_sha256 is not None and import_sha != expected_import_sha256:
        raise ValueError("import manifest differs from external SHA-256")
    provenance = ImportedReplies.model_validate_json(canonical(strict_json(imported)))
    if provenance.request_sha256 != request_digest(request):
        raise ValueError("imported replies belong to another request")
    supplied = {(r.chunk, r.attempt): r.text for r in provenance.replies}
    if len(supplied) != len(provenance.replies):
        raise ValueError("duplicate recorded attempt")
    run = RecordedRun(root, _profile(request))
    rows: list[dict[str, Any]] = []
    receipts: list[ChunkReceipt] = []
    recall_sha = digest(request.recall.model_dump(mode="json"))
    for ci, (lo, hi) in enumerate(_chunks(request)):
        items: list[Any] | None = None
        response_hashes: list[str] = []
        for attempt in range(request.max_attempts):
            raw = run.run((_prompt(request, ci, attempt),))[0]
            if supplied.get((ci, attempt)) != raw:
                raise ValueError("cached response differs from imported raw response")
            response_hashes.append(hashlib.sha256(raw.encode()).hexdigest())
            try:
                parsed = strict_json(raw)
            except ValueError:
                parsed = None
            if isinstance(parsed, dict) and isinstance(parsed.get("units"), list):
                items = parsed["units"]
                break
        accepted: dict[int, dict[str, Any]] = {}
        outside = repeated = 0
        for item in items or ():
            key = item.get("u") if isinstance(item, dict) else None
            if type(key) is not int or not lo <= key <= hi:
                outside += 1
                continue
            repeated += int(key in accepted)
            accepted[key] = item
        receipts.append(
            ChunkReceipt(
                chunk=ci,
                attempts=len(response_hashes),
                accepted=items is not None,
                items_outside_chunk=outside,
                repeated_units=repeated,
                response_sha256=tuple(response_hashes),
            )
        )
        for unit in request.recall.units[lo - 1 : hi]:
            reply = accepted.get(unit.u)
            processing = (
                "answered"
                if reply is not None
                else ("omitted" if items is not None else "unparsable-chunk")
            )
            row = resolve_time_item(request.source, reply, failure=processing)
            rows.append(
                {
                    "u": unit.u,
                    "row": unit.row,
                    "unit": "recall:" + digest({"recall": recall_sha, "u": unit.u}),
                    "processing": processing,
                    **row,
                }
            )
    return TimeStudyResult(
        request_sha256=request_digest(request),
        source_sha256=digest(
            {"key": request.source_key, "source": request.source.model_dump(mode="json")}
        ),
        recall_sha256=recall_sha,
        profile_sha256=run.profile.digest,
        import_sha256=import_sha,
        origin=provenance.origin,
        export_permission=request.export_permission,
        rows=tuple(rows),
        receipts=tuple(receipts),
        counts={
            field: dict(sorted(Counter(row[field]["judgement"]["kind"] for row in rows).items()))
            for field in ("narrative", "referent")
        },
    )
