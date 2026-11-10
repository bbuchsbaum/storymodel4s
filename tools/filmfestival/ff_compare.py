#!/usr/bin/env python3
"""Compare the two arms of the FilmFestival transfer evaluation, within participant.

Protocol: docs/plans/2026-11-09-filmfestival-transfer-protocol.md, section "Arms".

Usage: ff_compare.py PRIMARY_SCORE SECONDARY_SCORE PRIMARY_REPLIES SECONDARY_REPLIES FIELD OUTPUT

The two score files come from `ff_score.py score` on the same reference and FIELD. For each
session it reports each participant's scene-agreement difference (secondary minus primary), the
mean difference with a 95% interval from resampling participants, the same for film identity,
and how often the two arms gave the same answer.
"""

import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_score  # noqa: E402

SCHEMA = "storymodel4s.filmfestival.transfer-arm-comparison/v1"


def paired(primary, secondary):
    """Per-participant differences in a rate, their mean, and an interval over participants.

    Only participants with eligible utterances in both arms are compared; the arms are scored on
    the same reference, so a participant present in one and not the other is an error.
    """
    a = {p: r["rate"] for p, r in primary.items() if r["n"]}
    b = {p: r["rate"] for p, r in secondary.items() if r["n"]}
    if set(a) != set(b):
        raise ff_score.ScoreError("the arms were not scored on the same participants")
    differences = {p: b[p] - a[p] for p in sorted(a)}
    values = list(differences.values())
    out = {
        "participants": len(values),
        "by_participant": differences,
        "mean_difference": ff_score.mean(values),
        "secondary_higher": sum(1 for v in values if v > 0),
        "secondary_lower": sum(1 for v in values if v < 0),
        "interval95": None,
    }
    if len(values) >= 2:
        rng = random.Random(ff_score.BOOTSTRAP_SEED)
        draws = sorted(
            ff_score.mean([values[rng.randrange(len(values))] for _ in values])
            for _ in range(ff_score.BOOTSTRAP_DRAWS)
        )
        lo = int(0.025 * ff_score.BOOTSTRAP_DRAWS)
        hi = int(0.975 * ff_score.BOOTSTRAP_DRAWS) - 1
        out["interval95"] = [draws[lo], draws[hi]]
    return out


def answer(prediction):
    """A prediction reduced to what it says: its kind and its scene or film."""
    if not isinstance(prediction, dict) or prediction.get("status") != "answered":
        return ("failed",)
    kind = prediction.get("kind")
    return (
        kind,
        prediction.get("scene") if kind == "scene" else prediction.get("film"),
    )


def replies(directory, field):
    """{(participant, session, row): answer} for every reply file in a directory."""
    out = {}
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(directory, name), encoding="utf-8") as handle:
            doc = json.load(handle)
        for unit in doc["units"]:
            out[(doc["participant"], doc["session"], unit["row"])] = answer(unit[field])
    return out


def agreement(primary, secondary):
    """Per session: how often the arms gave the same answer, and by which kinds they differed."""
    if set(primary) != set(secondary):
        raise ff_score.ScoreError("the arms did not answer the same utterances")
    sessions = {}
    for key in sorted(primary):
        row = sessions.setdefault(
            key[1], {"n": 0, "same": 0, "both_scene": 0, "same_scene": 0}
        )
        a, b = primary[key], secondary[key]
        row["n"] += 1
        row["same"] += a == b
        if a[0] == "scene" and b[0] == "scene":
            row["both_scene"] += 1
            row["same_scene"] += a == b
    return sessions


def compare(primary_score, secondary_score, primary_replies, secondary_replies):
    if primary_score.get("field") != secondary_score.get("field"):
        raise ff_score.ScoreError("the score files are for different fields")
    if primary_score["population"] != secondary_score["population"]:
        raise ff_score.ScoreError("the score files are for different populations")
    out = {"schema": SCHEMA, "field": primary_score["field"], "sessions": {}}
    for session, a in primary_score["sessions"].items():
        b = secondary_score["sessions"][session]
        out["sessions"][session] = {
            "scene": paired(a["scene"]["by_participant"], b["scene"]["by_participant"]),
            "film_reference": paired(
                a["film"]["film_reference"]["by_participant"],
                b["film"]["film_reference"]["by_participant"],
            ),
        }
    out["agreement"] = agreement(primary_replies, secondary_replies)
    return out


def main(argv):
    if len(argv) != 6 or os.path.exists(argv[5]):
        print(__doc__, file=sys.stderr)
        return 2
    with open(argv[0], encoding="utf-8") as handle:
        primary = json.load(handle)
    with open(argv[1], encoding="utf-8") as handle:
        secondary = json.load(handle)
    try:
        if primary.get("field") != argv[4]:
            raise ff_score.ScoreError(
                "FIELD is not the field the score files were made for"
            )
        report = compare(
            primary, secondary, replies(argv[2], argv[4]), replies(argv[3], argv[4])
        )
    except ff_score.ScoreError as error:
        print(f"refused: {error}", file=sys.stderr)
        return 2
    with open(argv[5], "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=1, sort_keys=True)
        handle.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
