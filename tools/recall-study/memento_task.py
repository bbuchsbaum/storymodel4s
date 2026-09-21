#!/usr/bin/env python3
"""Pure Memento row identity, eligibility and any-annotated-scene scoring contract.

One physical worksheet row is one observation. Time is optional, never inferred from row order;
repeated text stays repeated. Workbook readers belong to memento_guard, not this module.
"""

# Exact ints deliberately exclude bool for row IDs, labels and split sizes.
# ruff: noqa: E721
from dataclasses import asdict, dataclass
from enum import Enum
import hashlib
import json
import math
import re

SCHEMA = "storymodel4s.memento.task/v1"
FIELDS = (
    "SecondsInMinuteTime",
    "SecondsOfRecall",
    "RecallType",
    "BroadSceneNum",
    "SubsceneNum",
    "BroadSceneNum2",
    "SubsceneNum2",
    "Detail",
    "FalseMemory",
    "FalseMemExp",
    "Transcript",
)


class Reason(str, Enum):
    MISSING_TEXT = "missing-transcript"
    MISSING_CODE = "missing-recall-code"
    INVALID_CODE = "invalid-recall-code"
    NONACCURATE = "not-accurate-recall"
    MISSING_SCENE = "missing-scene-gold"
    INVALID_SCENE = "invalid-scene-gold"


class Clock(str, Enum):
    OBSERVED = "observed-seconds"
    MISSING = "missing"
    INVALID = "invalid"


def digest(value):
    return hashlib.sha256(
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode()
    ).hexdigest()


def blank(value):
    return value is None or isinstance(value, str) and not value.strip()


def integer(value):
    if isinstance(value, bool) or blank(value):
        return None
    if isinstance(value, str) and not re.fullmatch(r"[+-]?\d+(?:\.0+)?", value.strip()):
        return None
    try:
        number = float(value)
        return int(number) if math.isfinite(number) and number == int(number) else None
    except (TypeError, ValueError, OverflowError):
        return None


def clock_value(value):
    if blank(value):
        return None, Clock.MISSING
    number = integer(value)
    if number is not None and number >= 0:
        return number, Clock.OBSERVED
    if isinstance(value, str) and re.fullmatch(r"\d+:\d{2}:\d{2}", value.strip()):
        hours, minutes, seconds = map(int, value.strip().split(":"))
        if minutes < 60 and seconds < 60:
            return hours * 3600 + minutes * 60 + seconds, Clock.OBSERVED
    return None, Clock.INVALID


@dataclass(frozen=True)
class Unit:
    unit_id: str
    participant: str
    excel_row: int
    code: int | None
    gold_scenes: tuple[int, ...]
    text: str | None
    text_sha256: str | None
    observed_seconds: int | None
    clock_status: Clock
    reasons: tuple[Reason, ...]
    header_overlays: tuple[str, ...]

    @property
    def eligible(self):
        return not self.reasons

    def metadata(self):
        """Content-free unit record; text-bearing units themselves stay local."""
        result = asdict(self)
        del result["text"]
        return result


def normalize(indexed_rows, participant, workbook_sha256, contract, *, overlays=True):
    """Normalize explicit rows, refusing unknown header shapes rather than guessing columns."""
    if (
        contract.get("schema") != SCHEMA
        or contract.get("workbookSha256") != workbook_sha256
    ):
        raise ValueError("task contract does not match the workbook")
    if not indexed_rows or indexed_rows[0][0] != 1:
        raise ValueError("expected a header in Excel row 1")
    header = list(indexed_rows[0][1])
    applied = []
    for rule in contract["headerOverlays"]:
        if rule["participant"] != participant:
            continue
        if digest(header) != rule["headerSha256"]:
            raise ValueError("pinned header overlay signature changed")
        if overlays:
            applied = [change["name"] for change in rule["columns"]]
            for change in rule["columns"]:
                if digest(header[change["index"]]) != change["originalCellSha256"]:
                    raise ValueError("pinned header cell state changed")
                header[change["index"]] = change["name"]
    for name in FIELDS:
        if header.count(name) > 1:
            raise ValueError("duplicate named recall column")
    columns = {name: header.index(name) for name in FIELDS if name in header}
    if "BroadSceneNum" not in columns:
        raise ValueError("missing BroadSceneNum column")
    if overlays and "RecallType" not in columns:
        raise ValueError("missing RecallType without a pinned overlay")
    units = []
    previous_row = 1
    for row_number, row in indexed_rows[1:]:
        if type(row_number) is not int or row_number <= previous_row:
            raise ValueError("physical Excel row numbers must increase")
        previous_row = row_number

        def get(name):
            return (
                row[columns[name]]
                if name in columns and columns[name] < len(row)
                else None
            )

        # A clock or annotation-only row is real; formatting and legend-only rows are not.
        if all(blank(get(name)) for name in FIELDS):
            continue
        reasons = []
        raw_text = get("Transcript")
        text = raw_text if isinstance(raw_text, str) and raw_text.strip() else None
        if text is None:
            reasons.append(Reason.MISSING_TEXT)
        raw_code = get("RecallType")
        code = integer(raw_code)
        if blank(raw_code):
            reasons.append(Reason.MISSING_CODE)
        elif code not in (1, 2, 3, 4, 5):
            reasons.append(Reason.INVALID_CODE)
        elif code not in (1, 2):
            reasons.append(Reason.NONACCURATE)
        scene_values = [
            get(name)
            for name in ("BroadSceneNum", "BroadSceneNum2")
            if not blank(get(name))
        ]
        scenes = [integer(value) for value in scene_values]
        if not scenes:
            reasons.append(Reason.MISSING_SCENE)
        elif any(scene is None or not 1 <= scene <= 44 for scene in scenes):
            reasons.append(Reason.INVALID_SCENE)
            scenes = []  # do not accept the valid half of a malformed multi-scene annotation
        seconds, clock = clock_value(get("SecondsOfRecall"))
        identity = digest(
            ["memento.source-row/v1", workbook_sha256, participant, row_number]
        )
        text_digest = (
            hashlib.sha256(text.encode()).hexdigest() if text is not None else None
        )
        units.append(
            Unit(
                identity,
                participant,
                row_number,
                code,
                tuple(sorted(set(scenes))),
                text,
                text_digest,
                seconds,
                clock,
                tuple(reasons),
                tuple(applied),
            )
        )
    return units


def score(units, predictions, participants):
    """Exact unit reconciliation and equal-participant any-annotated-scene accuracy.

    Predictions are a sequence of (unit ID, integer scene or nonlabel) pairs, never a mapping:
    duplicates must remain visible at this boundary. Nonlabels are wrong on eligible rows;
    no-gold rows remain accounted for. Call separately for each experimental condition.
    """
    if not isinstance(predictions, (list, tuple)):
        raise ValueError("prediction outcomes must be pairs, not a collapsed mapping")
    outcomes = {}
    for pair in predictions:
        if not isinstance(pair, (list, tuple)) or len(pair) != 2:
            raise ValueError("prediction outcome must be a unit ID and label pair")
        identity, label = pair
        if not isinstance(identity, str) or identity in outcomes:
            raise ValueError("duplicate or invalid prediction unit IDs")
        outcomes[identity] = label
    ids = [u.unit_id for u in units]
    if len(set(ids)) != len(ids) or set(outcomes) != set(ids):
        raise ValueError(
            "prediction unit IDs must match the complete unique unit manifest"
        )
    if len(set(participants)) != len(participants) or any(
        u.participant not in participants for u in units
    ):
        raise ValueError(
            "units must belong to the declared unique participant population"
        )
    by_participant = {
        name: {"units": 0, "eligible": 0, "correct": 0} for name in participants
    }
    for unit in units:
        counts = by_participant.setdefault(
            unit.participant, {"units": 0, "eligible": 0, "correct": 0}
        )
        counts["units"] += 1
        if unit.eligible:
            counts["eligible"] += 1
            prediction = outcomes[unit.unit_id]
            counts["correct"] += int(
                type(prediction) is int and prediction in unit.gold_scenes
            )
    for counts in by_participant.values():
        counts["accuracy"] = (
            counts["correct"] / counts["eligible"] if counts["eligible"] else None
        )
    defined = [
        counts["accuracy"]
        for counts in by_participant.values()
        if counts["accuracy"] is not None
    ]
    # A retained participant with no eligible rows makes the full-population estimand unavailable.
    mean = (
        sum(defined) / len(defined)
        if defined and len(defined) == len(by_participant)
        else None
    )
    eligible = sum(c["eligible"] for c in by_participant.values())
    return {
        "participants": by_participant,
        "participantMean": mean,
        "pooledAccuracy": sum(c["correct"] for c in by_participant.values()) / eligible
        if eligible
        else None,
    }


def sort_ids(ids):
    return sorted(ids, key=lambda name: int(name.removeprefix("S")))


def draw(groups, seed, test_counts):
    """Condition-stratified SHA-256 ranking: independent of input order and Python RNG version."""
    if (
        type(seed) is not int
        or set(groups) != {"1", "2", "3", "4"}
        or set(test_counts) != set(groups)
    ):
        raise ValueError("invalid stratified draw specification")
    all_ids = [name for ids in groups.values() for name in ids]
    if len(set(all_ids)) != len(all_ids) or any(
        not re.fullmatch(r"S[1-9]\d*", name) for name in all_ids
    ):
        raise ValueError("population IDs must be unique across conditions")
    dev, test = [], []
    for condition in sorted(groups):
        n = test_counts[condition]
        if type(n) is not int or not 0 < n < len(groups[condition]):
            raise ValueError("each condition needs development and test participants")
        ranked = sorted(
            groups[condition],
            key=lambda name: (
                hashlib.sha256(
                    f"memento-split/v1\n{seed}\n{condition}\n{name}".encode()
                ).hexdigest(),
                name,
            ),
        )
        test.extend(ranked[:n])
        dev.extend(ranked[n:])
    return sort_ids(dev), sort_ids(test)
