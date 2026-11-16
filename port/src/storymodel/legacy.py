"""Read the bounded legacy StudyBundle v2 profile without importing repository tools.

This module reuses the validation semantics of
``tools/agentmapping/study_bundle.py`` (the independent Python reader), copied
here so an installed ``storymodel`` package has no runtime dependency on the
repository.  It validates categorical v2 bundles and the existing timed-v2
protocol only.  It is not a general Scala ``StudyBundle.decode`` replacement:
the Scala decoder additionally binds a bundle to independently supplied source
and recall objects.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import re
from fractions import Fraction
from pathlib import Path
from typing import Any, cast

type LegacyDocument = dict[str, Any]

SCHEMA = "storymodel4s.agentmapping.bundle/v2"
FIELDS = ("narrative", "referent")
_RESERVED = ("u", "row", "unit", "processing") + FIELDS
_TIME = re.compile(r"([0-9]{1,2}):([0-5][0-9](?:\.[0-9]{1,3})?)")
_TIME_PROTOCOLS = ("strict-chunked-time/v1",)
_SHA256 = re.compile(r"[0-9a-fA-F]{64}")


class LegacyBundleError(ValueError):
    """Why an externally supplied legacy bundle cannot be admitted."""


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _record_outcomes(bundle: LegacyDocument, field: str) -> list[tuple[str, str, object | None]]:
    outcomes: list[tuple[str, str, object | None]] = []
    for outcome in bundle["interpretations"][field]["record"]["outcomes"]:
        if outcome["processing_status"]["status"] != "complete":
            outcomes.append((outcome["unit"], "failed", None))
            continue
        decision = outcome["decision"]["value"]
        if decision["support"]["status"] != "categorical":
            raise LegacyBundleError("bundle record holds a non-categorical decision")
        target = decision["decoded_target_id"]
        outcomes.append(
            (outcome["unit"], "choice", target["value"])
            if target["status"] == "present"
            else (outcome["unit"], "none", None)
        )
    return outcomes


def _clock_table(
    source: LegacyDocument,
) -> tuple[Fraction, dict[int, tuple[Fraction, list[tuple[int, Fraction, Fraction]]]]]:
    table: dict[int, tuple[Fraction, list[tuple[int, Fraction, Fraction]]]] = {}
    for film in source["clock"]["films"]:
        table[film["collection"]] = (
            Fraction(film["durationSeconds"]),
            [
                (scene["scene"], Fraction(scene["start"]), Fraction(scene["end"]))
                for scene in film["scenes"]
            ],
        )
    return Fraction(source["clock"]["toleranceSeconds"]), table


def _time_prediction(
    clock: tuple[Fraction, dict[int, tuple[Fraction, list[tuple[int, Fraction, Fraction]]]]],
    film: object,
    time: object,
) -> LegacyDocument:
    tolerance, table = clock
    if time is None:
        if _is_int(film):
            return {"status": "answered", "kind": "film", "film": film}
        if film is None:
            return {"status": "answered", "kind": "none"}
        return {"status": "failed", "reason": "invalid-film"}
    raw = {"film_stated": film, "time": time}
    match = _TIME.fullmatch(time) if isinstance(time, str) else None
    if match is None:
        return {"status": "failed", "reason": "invalid-time", **raw}
    if film is None:
        return {"status": "failed", "reason": "time-without-film", **raw}
    if not _is_int(film):
        return {"status": "failed", "reason": "invalid-film", **raw}
    film_number = cast(int, film)
    if film_number not in table:
        return {"status": "failed", "reason": "unknown-film", **raw}
    seconds = int(match.group(1)) * 60 + Fraction(match.group(2))
    duration, scenes = table[film_number]
    near = [
        scene
        for scene, start, end in scenes
        if max(start - seconds, seconds - end, Fraction(0)) <= tolerance
    ]
    rest = {"near": near, **raw, "seconds": float(seconds)}
    if seconds > duration:
        return {"status": "answered", "kind": "time_outside", "film": film, **rest}
    inside = [scene for scene, start, end in scenes if start <= seconds < end]
    return {
        "status": "answered",
        "kind": "scene",
        "scene": inside[0] if inside else scenes[-1][0],
        **rest,
    }


def _judge(
    stated: object,
    scenes: dict[int, LegacyDocument],
    films: dict[int, LegacyDocument],
    clock: tuple[Fraction, dict[int, tuple[Fraction, list[tuple[int, Fraction, Fraction]]]]] | None,
    timed: bool,
) -> tuple[LegacyDocument, tuple[str, object | None]]:
    if not isinstance(stated, dict):
        raise LegacyBundleError("a stated answer is not an object")
    if "time" in stated:
        if clock is None or not timed:
            raise LegacyBundleError("a time answer in a bundle with no clip clock")
        if _time_prediction(clock, stated.get("film_stated"), stated["time"]) != stated:
            raise LegacyBundleError("a time answer is not what its film and time give")
        if stated.get("kind") == "time_outside" and stated.get("status") == "answered":
            film = films.get(stated["film"])
            if film is None or not film["isTarget"]:
                raise LegacyBundleError("a time is placed in a film that is not a target")
            return {"kind": "time-outside", "collection": stated["film"]}, (
                "choice",
                film["target"],
            )
    elif timed and stated.get("status") == "answered" and stated.get("kind") == "scene":
        raise LegacyBundleError("a scene without a time in a condition that answers with a time")
    if stated.get("status") == "failed":
        reason = stated.get("reason")
        if not isinstance(reason, str):
            raise LegacyBundleError("a failure has no reason")
        return (
            {"kind": reason}
            if reason in ("omitted", "unparsable-chunk")
            else {"kind": "invalid", "reason": reason},
            ("failed", None),
        )
    if stated.get("status") != "answered":
        raise LegacyBundleError("a stated answer is neither answered nor failed")
    kind = stated.get("kind")
    if kind == "scene":
        number = stated.get("scene")
        if not _is_int(number):
            raise LegacyBundleError("a stated scene is not an integer")
        scene_number = cast(int, number)
        scene = scenes.get(scene_number)
        if scene is None:
            return {"kind": "unknown-scene", "stated": str(scene_number)}, ("failed", None)
        judgement: LegacyDocument = {"scene": scene_number}
        if "collection" in scene:
            judgement["collection"] = scene["collection"]
        return (
            ({"kind": "scene", **judgement}, ("choice", scene["target"]))
            if scene["isTarget"]
            else ({"kind": "invalid-scene", **judgement}, ("failed", None))
        )
    if kind == "film":
        number = stated.get("film")
        if not _is_int(number):
            raise LegacyBundleError("a stated film is not an integer")
        film_number = cast(int, number)
        film = films.get(film_number)
        if film is None or not film["isTarget"]:
            return {"kind": "unknown-film", "stated": str(film_number)}, ("failed", None)
        return {"kind": "film", "collection": film_number}, ("choice", film["target"])
    if kind == "none":
        return {"kind": "none"}, ("none", None)
    raise LegacyBundleError("a stated answer has an unknown kind")


def _answers_digest(units: list[LegacyDocument], field: str) -> str:
    def token(stated: LegacyDocument, key: str) -> str:
        if key not in stated:
            return "-"
        value = stated[key]
        if value is None:
            return "null"
        if _is_int(value):
            return str(value)
        if isinstance(value, str):
            return "s:" + hashlib.sha256(value.encode()).hexdigest()
        return "x"

    lines: list[str] = []
    for unit in units:
        stated = unit[field]["stated"]
        kind = (
            stated.get("kind", "?")
            if stated.get("status") == "answered"
            else "failed:" + stated.get("reason", "?")
        )
        if not isinstance(kind, str):
            raise LegacyBundleError("a stated kind or reason is not text")
        lines.append(
            f"{unit['u']}\t{kind}\t"
            + "\t".join(token(stated, key) for key in ("scene", "film", "film_stated", "time"))
            + "\n"
        )
    return hashlib.sha256("".join(lines).encode()).hexdigest()


def _decision_receipt(record: LegacyDocument) -> LegacyDocument:
    for receipt in record["stage_assumption_receipts"]:
        if receipt["stage"] == "Decision":
            return cast(LegacyDocument, receipt["provenance"]["asserted"]["value"])
    raise LegacyBundleError("a record has no decision receipt")


def _check_transcript(bundle: LegacyDocument) -> None:
    segmentation = bundle["provenance"]["segmentation"]
    if segmentation["kind"] == "supplied":
        if segmentation["recipe"] != "supplied-units/v1" or "recall" in bundle:
            raise LegacyBundleError("supplied segmentation has incompatible provenance")
        return
    if segmentation["kind"] != "segmenter" or segmentation["recipe"] != "recall-segmenter/v1":
        raise LegacyBundleError("unknown segmentation recipe")
    recall = bundle["recall"]
    if (
        recall["recipe"] != segmentation["recipe"]
        or recall["rawSha256"] != segmentation["artifactSha256"]
        or recall["canonicalization"] != "story-source/v1"
        or recall["offsetUnit"] != "utf-16-code-units"
    ):
        raise LegacyBundleError("transcript provenance differs")
    text = recall["canonicalText"]
    try:
        canonical_hash = hashlib.sha256(text.encode()).hexdigest()
        encoded = text.encode("utf-16-le")
    except UnicodeError as error:
        raise LegacyBundleError("canonical transcript has malformed Unicode") from error
    if canonical_hash != recall["canonicalSha256"]:
        raise LegacyBundleError("canonical transcript checksum differs")
    described = recall["units"]
    rows = bundle["units"]
    if (
        not all(_is_int(unit["u"]) for unit in described)
        or not all(_is_int(unit["row"]) for unit in rows)
        or [unit["u"] for unit in described] != list(range(1, len(rows) + 1))
        or [unit["u"] for unit in rows] != [unit["u"] for unit in described]
        or [unit["row"] for unit in rows] != [unit["u"] for unit in described]
        or [unit["unit"] for unit in described] != [unit["unit"] for unit in rows]
    ):
        raise LegacyBundleError("transcript unit keys differ")
    previous_end = 0
    for unit in described:
        pieces: list[str] = []
        if not unit["spans"]:
            raise LegacyBundleError("transcript unit has no spans")
        for span in unit["spans"]:
            start, end = span["start"], span["endExclusive"]
            if (
                not _is_int(start)
                or not _is_int(end)
                or not 0 <= previous_end <= start < end <= len(encoded) // 2
            ):
                raise LegacyBundleError("invalid canonical transcript span")
            try:
                pieces.append(encoded[start * 2 : end * 2].decode("utf-16-le"))
            except UnicodeDecodeError as error:
                raise LegacyBundleError("transcript span splits a surrogate pair") from error
            previous_end = end
        if "".join(pieces) != unit["text"]:
            raise LegacyBundleError("transcript unit text differs from its spans")
    for field in FIELDS:
        inventory = bundle["interpretations"][field]["record"]["inventory"]
        if (
            inventory["transcript_checksum"] != recall["canonicalSha256"]
            or inventory["word_id_policy"]["input_artifact"] != recall["rawSha256"]
        ):
            raise LegacyBundleError("transcript differs from record inventory")
        expected = [
            (unit["unit"], [(span["start"], span["endExclusive"]) for span in unit["spans"]])
            for unit in described
        ]
        actual = [
            (unit["id"], [(span["start"], span["end_exclusive"]) for span in unit["spans"]])
            for unit in inventory["units"]
        ]
        if actual != expected:
            raise LegacyBundleError("transcript spans differ from record inventory")


def _check(bundle: LegacyDocument) -> None:
    for key in ("provenance", "interpretations", "units", "source"):
        if key not in bundle:
            raise LegacyBundleError(f"bundle has no {key}")
    _check_transcript(bundle)
    provenance = bundle["provenance"]
    units = bundle["units"]
    if (
        not isinstance(units, list)
        or not units
        or not all(isinstance(unit, dict) for unit in units)
    ):
        raise LegacyBundleError("unit rows are not a non-empty array")
    rows = cast(list[LegacyDocument], units)
    numbers = [unit["u"] for unit in rows]
    if not all(_is_int(number) for number in numbers) or len(set(numbers)) != len(numbers):
        raise LegacyBundleError("unit numbers must be distinct integers")
    scenes = {scene["scene"]: scene for scene in bundle["source"]["scenes"]}
    films = {film["collection"]: film for film in bundle["source"]["collections"]}
    timed = provenance["protocol"] in _TIME_PROTOCOLS
    if timed != ("clock" in bundle["source"]):
        raise LegacyBundleError("a time condition needs a clip clock, and no other has one")
    clock = _clock_table(bundle["source"]) if timed else None
    for unit in rows:
        extras = unit["extras"]
        if not isinstance(extras, dict) or any(key in _RESERVED for key in extras):
            raise LegacyBundleError(f"unit {unit['u']}: an extra uses a row's own key")
    for field in FIELDS:
        slot = bundle["interpretations"][field]
        receipt = _decision_receipt(slot["record"])
        policy = f"agent-localization:{provenance['condition']}:{provenance['protocol']}:{field}"
        if (
            slot["policy"] != slot["record"]["policies"]["decision_policy_id"].get("id")
            or receipt["policy"] != policy
            or slot["policy"] != policy
        ):
            raise LegacyBundleError(f"the {field} slot does not hold the {field} record")
        producer = receipt["provider"]
        if (
            producer.get("model") != provenance["model"]
            or producer.get("provider") != provenance["provider"]
            or receipt["config"] != provenance["systemSha256"]
            or provenance["segmentation"]["artifactSha256"] not in receipt["inputs"]
            or bundle["source"]["artifact"] not in receipt["inputs"]
        ):
            raise LegacyBundleError(f"the {field} record is not from the run the bundle names")
        outcomes = _record_outcomes(bundle, field)
        if [outcome[0] for outcome in outcomes] != [unit["unit"] for unit in rows]:
            raise LegacyBundleError(f"unit rows do not match the {field} record's units")
        for unit, (_, actual_kind, actual_target) in zip(rows, outcomes, strict=True):
            judgement, expected = _judge(unit[field]["stated"], scenes, films, clock, timed)
            if unit[field]["judgement"] != judgement:
                message = (
                    f"unit {unit['u']} {field}: the judgement does not follow from the stated "
                    "answer"
                )
                raise LegacyBundleError(message)
            if expected != (actual_kind, actual_target):
                message = (
                    f"unit {unit['u']} {field}: row says {judgement['kind']}, "
                    f"record says {actual_kind}"
                )
                raise LegacyBundleError(message)
        if _answers_digest(rows, field) not in receipt["inputs"]:
            raise LegacyBundleError(
                f"the {field} answers are not the ones the record was made from"
            )
    for unit in rows:
        kinds = {unit[field]["judgement"]["kind"] for field in FIELDS}
        terminal = kinds & {"omitted", "unparsable-chunk"}
        processing = (
            "answered" if not terminal else (next(iter(kinds)) if len(kinds) == 1 else None)
        )
        if unit["processing"] != processing:
            raise LegacyBundleError("a unit's processing does not match its judgements")


def load_legacy(path: str | Path, *, expected_sha256: str) -> LegacyDocument:
    """Read a caller-pinned, supported legacy v2 bundle.

    ``expected_sha256`` is an integrity pin chosen outside this file.  It is not
    authentication: a hash supplied by the same untrusted source as the bytes
    provides no trust boundary.
    """
    if not isinstance(expected_sha256, str) or _SHA256.fullmatch(expected_sha256) is None:
        raise LegacyBundleError("expected_sha256 must be a 64-character SHA-256 hex digest")
    try:
        raw = Path(path).read_bytes()
    except OSError as error:
        raise LegacyBundleError(f"cannot read legacy bundle: {error}") from error
    actual = hashlib.sha256(raw).hexdigest()
    if not hmac.compare_digest(actual, expected_sha256.lower()):
        raise LegacyBundleError("legacy bundle SHA-256 differs from caller-pinned expected_sha256")
    try:
        document = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise LegacyBundleError(f"malformed legacy JSON bundle: {error}") from error
    if not isinstance(document, dict) or document.get("schema") != SCHEMA:
        raise LegacyBundleError(f"unsupported legacy schema; expected {SCHEMA}")
    bundle = cast(LegacyDocument, document)
    try:
        _check(bundle)
    except (KeyError, TypeError, AttributeError, IndexError, ValueError) as error:
        if isinstance(error, LegacyBundleError):
            raise
        raise LegacyBundleError(f"malformed legacy bundle: {error}") from error
    return bundle


def legacy_rows(bundle: LegacyDocument) -> tuple[LegacyDocument, ...]:
    """Return the original unit rows without flattening or discarding extras."""
    rows = bundle.get("units")
    if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
        raise LegacyBundleError("legacy bundle has no unit-row array")
    return tuple(cast(LegacyDocument, row) for row in rows)


def legacy_summary(bundle: LegacyDocument) -> tuple[str, ...]:
    """Summarise admitted categorical outcomes while retaining raw rows separately."""
    provenance = bundle["provenance"]
    rows = legacy_rows(bundle)
    lines = [
        f"{provenance['participant']} {provenance['session']}: {len(rows)} units",
        f"condition {provenance['condition']}; model {provenance['model']} via "
        f"{provenance['provider']}; protocol {provenance['protocol']}; "
        f"model calls {provenance['modelCalls']}",
        f"units {provenance['segmentation']['kind']} ({provenance['segmentation']['recipe']})",
    ]
    for field in FIELDS:
        counts: dict[str, int] = {}
        for row in rows:
            kind = row[field]["judgement"]["kind"]
            counts[kind] = counts.get(kind, 0) + 1
        lines.append(
            f"{field}: " + ", ".join(f"{kind}={count}" for kind, count in sorted(counts.items()))
        )
    source = bundle["source"]
    lines.append(
        f"source: {len(source['collections'])} films, {len(source['scenes'])} scenes, "
        f"{len(source['rows'])} rows"
    )
    return tuple(lines)
