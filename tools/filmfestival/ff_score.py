#!/usr/bin/env python3
"""Score FilmFestival predictions under the transfer protocol.

Protocol: docs/plans/2026-11-09-filmfestival-transfer-protocol.md.

A prediction for one utterance is one of:
    {"status": "answered", "kind": "scene", "scene": 57}
    {"status": "answered", "kind": "film", "film": 3}     the film, no particular scene
    {"status": "answered", "kind": "none"}                explicitly no source assignment
    {"status": "failed", "reason": "..."}                 no valid answer was obtained
A failure is never a correct non-assignment. An utterance with no prediction is a failure.

Usage:
    ff_score.py population GOLD ANNOTATION
    ff_score.py score GOLD ANNOTATION PREDICTIONS_DIR FIELD OUTPUT
PREDICTIONS_DIR holds <participant>_<session>.json files, each
{"participant", "session", "units": [{"row": <gold_row>, "<FIELD>": <prediction>}, ...]}.
"""

import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_gold  # noqa: E402

SCHEMA = "storymodel4s.filmfestival.transfer-score/v1"


class ScoreError(ValueError):
    pass


def normalise(prediction, film_of, valid):
    """A checked prediction: (kind, film, scene) with kind in scene|film|none|failed.

    A scene answer must name a valid target; its film comes from the dictionary, never from the
    reply. Anything else that is not a well-formed answer is a failure with its reason.
    """
    if not isinstance(prediction, dict) or prediction.get("status") != "answered":
        return ("failed", None, None)
    kind = prediction.get("kind")
    if kind == "scene":
        scene = prediction.get("scene")
        if isinstance(scene, int) and not isinstance(scene, bool) and scene in valid:
            return ("scene", film_of[scene], scene)
        return ("failed", None, None)
    if kind == "film":
        film = prediction.get("film")
        if (
            isinstance(film, int)
            and not isinstance(film, bool)
            and film in ff_gold.FILMS
        ):
            return ("film", film, None)
        return ("failed", None, None)
    if kind == "none":
        return ("none", None, None)
    return ("failed", None, None)


def judge(utterance, prediction):
    """What one prediction is, against one reference. Returns a dict of flags."""
    kind, film, scene = prediction
    cls = utterance["cls"]
    out = {"cls": cls, "kind": kind}
    if cls in ff_gold.SCENE_CLASSES:
        out["scene_hit"] = kind == "scene" and scene in utterance["scenes"]
    if cls in ff_gold.SCENE_CLASSES or cls == "film_noscene":
        out["film_hit"] = kind in ("scene", "film") and film == utterance["film"]
    return out


def chance(utterance, film_of, valid):
    """Success probability of one uniform scene guess: (given the correct film, end to end)."""
    acceptable = len(utterance["scenes"] & valid)
    in_film = sum(1 for s in valid if film_of[s] == utterance["film"])
    return acceptable / in_film, acceptable / len(valid)


def mean(xs):
    return sum(xs) / len(xs) if xs else None


def rate(flags):
    return {
        "n": len(flags),
        "hits": sum(flags),
        "rate": mean([1.0 if f else 0.0 for f in flags]),
    }


BOOTSTRAP_DRAWS = 5000
BOOTSTRAP_SEED = 20261109


def participant_average(per_participant):
    """Mean of per-participant rates, with a 95% interval from resampling participants.

    Participants with no eligible utterance are left out. Whole participants are resampled, so
    one person's utterances stay together. With fewer than two participants there is no interval.
    """
    rates = [r["rate"] for _, r in sorted(per_participant.items()) if r["n"]]
    out = {"participants": len(rates), "rate": mean(rates), "interval95": None}
    if len(rates) >= 2:
        rng = random.Random(BOOTSTRAP_SEED)
        draws = sorted(
            mean([rates[rng.randrange(len(rates))] for _ in rates])
            for _ in range(BOOTSTRAP_DRAWS)
        )
        out["interval95"] = [
            draws[int(0.025 * BOOTSTRAP_DRAWS)],
            draws[int(0.975 * BOOTSTRAP_DRAWS) - 1],
        ]
    return out


def summarise(judged):
    """All reported quantities for one set of (utterance, flags, chance) records."""

    def of(pred):
        return [j for j in judged if pred(j)]

    scene = of(lambda j: j["flags"]["cls"] in ff_gold.SCENE_CLASSES)
    labelled = of(lambda j: "film_hit" in j["flags"])

    def by_participant(records, key):
        groups = {}
        for j in records:
            groups.setdefault(j["utterance"]["participant"], []).append(j["flags"][key])
        return {p: rate(flags) for p, flags in sorted(groups.items())}

    def kinds(records):
        out = {"scene": 0, "film": 0, "none": 0, "failed": 0}
        for j in records:
            out[j["flags"]["kind"]] += 1
        return out

    scene_by_p = by_participant(scene, "scene_hit")
    film_by_p = by_participant(labelled, "film_hit")
    noscene = of(lambda j: j["flags"]["cls"] == "film_noscene")
    report = {
        "utterances": len(judged),
        "scene": {
            "pooled": rate([j["flags"]["scene_hit"] for j in scene]),
            "participant_average": participant_average(scene_by_p),
            "by_participant": scene_by_p,
            "strata": {
                c: rate(
                    [j["flags"]["scene_hit"] for j in scene if j["flags"]["cls"] == c]
                )
                for c in ff_gold.SCENE_CLASSES
            },
            "prediction_kinds": kinds(scene),
            "chance_given_film": mean([j["chance"][0] for j in scene]),
            "chance_end_to_end": mean([j["chance"][1] for j in scene]),
            "by_film": {
                str(f): rate(
                    [
                        j["flags"]["scene_hit"]
                        for j in scene
                        if j["utterance"]["film"] == f
                    ]
                )
                for f in ff_gold.FILMS
            },
        },
        "film": {
            "pooled": rate([j["flags"]["film_hit"] for j in labelled]),
            "participant_average": participant_average(film_by_p),
            "by_participant": film_by_p,
        },
        "film_noscene": {
            "n": len(noscene),
            "film_only_correct_film": sum(
                1
                for j in noscene
                if j["flags"]["kind"] == "film" and j["flags"]["film_hit"]
            ),
            "scene_in_correct_film": sum(
                1
                for j in noscene
                if j["flags"]["kind"] == "scene" and j["flags"]["film_hit"]
            ),
            "wrong_film": sum(
                1
                for j in noscene
                if j["flags"]["kind"] in ("scene", "film")
                and not j["flags"]["film_hit"]
            ),
            "none": sum(1 for j in noscene if j["flags"]["kind"] == "none"),
            "failed": sum(1 for j in noscene if j["flags"]["kind"] == "failed"),
        },
    }
    for cls in ("nonfilm", "cartoon"):
        records = of(lambda j, c=cls: j["flags"]["cls"] == c)
        k = kinds(records)
        report[cls] = {
            "n": len(records),
            "assigned_a_film": k["scene"] + k["film"],
            "no_assignment": k["none"],
            "failed": k["failed"],
        }
    return report


def score(gold, film_of, valid, predictions):
    """`predictions` maps (participant, session, row) to a raw prediction. Returns the report."""
    known = {(u["participant"], u["session"], u["row"]) for u in gold}
    stray = sorted(k for k in predictions if k not in known)
    if stray:
        raise ScoreError(f"prediction for an unknown utterance: {stray[0]}")
    judged = []
    for u in gold:
        key = (u["participant"], u["session"], u["row"])
        prediction = normalise(predictions.get(key), film_of, valid)
        record = {"utterance": u, "flags": judge(u, prediction)}
        if u["cls"] in ff_gold.SCENE_CLASSES:
            record["chance"] = chance(u, film_of, valid)
        judged.append(record)
    sessions = sorted({u["session"] for u in gold})
    report = {
        "schema": SCHEMA,
        "population": ff_gold.population(gold),
        "valid_scene_targets": len(valid),
        "sessions": {
            s: summarise([j for j in judged if j["utterance"]["session"] == s])
            for s in sessions
        },
    }
    # Session comparison is only meaningful within the participants who have both sessions.
    if len(sessions) > 1:
        both = set.intersection(
            *[{u["participant"] for u in gold if u["session"] == s} for s in sessions]
        )
        report["participants_with_every_session"] = {
            "participants": sorted(both),
            "sessions": {
                s: summarise(
                    [
                        j
                        for j in judged
                        if j["utterance"]["session"] == s
                        and j["utterance"]["participant"] in both
                    ]
                )
                for s in sessions
            },
        }
    return report


def load_predictions(directory, field):
    predictions = {}
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(directory, name), encoding="utf-8") as handle:
            doc = json.load(handle)
        for unit in doc["units"]:
            key = (doc["participant"], doc["session"], unit["row"])
            if key in predictions:
                raise ScoreError(f"duplicate prediction for {key}")
            predictions[key] = unit.get(field)
    return predictions


def main(argv):
    if len(argv) == 3 and argv[0] == "population":
        gold = ff_gold.load_gold(argv[1])
        film_of, valid = ff_gold.load_dictionary(argv[2])
        ff_gold.check_join(gold, film_of)
        print(
            json.dumps(
                {"population": ff_gold.population(gold), "targets": len(valid)},
                indent=1,
            )
        )
        return 0
    if len(argv) == 6 and argv[0] == "score":
        if os.path.exists(argv[5]):
            print("refused: output exists", file=sys.stderr)
            return 2
        gold = ff_gold.load_gold(argv[1])
        film_of, valid = ff_gold.load_dictionary(argv[2])
        ff_gold.check_join(gold, film_of)
        report = score(gold, film_of, valid, load_predictions(argv[3], argv[4]))
        report["field"] = argv[4]
        with open(argv[5], "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=1, sort_keys=True)
            handle.write("\n")
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
