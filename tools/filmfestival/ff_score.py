#!/usr/bin/env python3
"""Score FilmFestival predictions under the transfer protocol.

Protocol: docs/plans/2026-11-09-filmfestival-transfer-protocol.md.

A prediction for one utterance is one of:
    {"status": "answered", "kind": "scene", "scene": 57}
    {"status": "answered", "kind": "film", "film": 3}     the film, no particular scene
    {"status": "answered", "kind": "none"}                explicitly no source assignment
    {"status": "failed", "reason": "..."}                 no valid answer was obtained
A failure is never a correct non-assignment. An utterance with no prediction is a failure.

A scene answer that names a real scene which is not a valid target (a title card, the cartoon)
is an answer, not a processing failure. It is its own kind, `invalid_scene`: never a scene hit,
and it keeps its dictionary film for film identity.

Usage:
    ff_score.py population GOLD CODES ANNOTATION
    ff_score.py score GOLD CODES ANNOTATION PREDICTIONS_DIR FIELD OUTPUT
FIELD is `referent` or `narrative`. PREDICTIONS_DIR must hold one <participant>_<session>.json
for every participant-session of the reference, each
{"participant", "session", "units": [{"row": <gold_row>, "<FIELD>": <prediction>}, ...]}.
"""

import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_gold  # noqa: E402

SCHEMA = "storymodel4s.filmfestival.transfer-score/v2"
FIELDS = ("referent", "narrative")
KINDS = ("scene", "invalid_scene", "film", "none", "failed")
BOOTSTRAP_DRAWS = 5000
BOOTSTRAP_SEED = 20261109


class ScoreError(ValueError):
    pass


def is_int(x):
    return isinstance(x, int) and not isinstance(x, bool)


def normalise(prediction, film_of, valid):
    """A checked prediction: a dict with `kind`, and `film`, `scene`, `reason` where they apply.

    A scene answer's film comes from the dictionary, never from the reply.
    """

    def failed(reason):
        return {"kind": "failed", "film": None, "scene": None, "reason": reason}

    if prediction is None:
        return failed("no-prediction")
    if not isinstance(prediction, dict) or prediction.get("status") != "answered":
        reason = prediction.get("reason") if isinstance(prediction, dict) else None
        return failed(reason if isinstance(reason, str) and reason else "unspecified")
    kind = prediction.get("kind")
    if kind == "scene":
        scene = prediction.get("scene")
        if not is_int(scene) or scene not in film_of:
            return failed("unknown-scene")
        if scene in valid:
            return {"kind": "scene", "film": film_of[scene], "scene": scene}
        film = film_of[scene] if film_of[scene] in ff_gold.FILMS else None
        return {"kind": "invalid_scene", "film": film, "scene": scene}
    if kind == "film":
        film = prediction.get("film")
        if is_int(film) and film in ff_gold.FILMS:
            return {"kind": "film", "film": film, "scene": None}
        return failed("unknown-film")
    if kind == "none":
        return {"kind": "none", "film": None, "scene": None}
    return failed("unknown-kind")


def judge(utterance, prediction):
    """What one checked prediction is, against one reference."""
    cls = utterance["cls"]
    out = {"cls": cls, "kind": prediction["kind"], "reason": prediction.get("reason")}
    if cls in ff_gold.SCENE_CLASSES:
        out["scene_hit"] = (
            prediction["kind"] == "scene" and prediction["scene"] in utterance["scenes"]
        )
    if cls in ff_gold.FILM_CLASSES:
        out["film_hit"] = (
            prediction["film"] is not None and prediction["film"] == utterance["film"]
        )
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
        lo, hi = int(0.025 * BOOTSTRAP_DRAWS), int(0.975 * BOOTSTRAP_DRAWS) - 1
        out["interval95"] = [draws[lo], draws[hi]]
    return out


def both_ways(records, key):
    """A rate pooled, per participant, and as the participant average."""
    groups = {}
    for j in records:
        groups.setdefault(j["utterance"]["participant"], []).append(j["flags"][key])
    by_participant = {p: rate(flags) for p, flags in sorted(groups.items())}
    return {
        "pooled": rate([j["flags"][key] for j in records]),
        "participant_average": participant_average(by_participant),
        "by_participant": by_participant,
    }


def kinds(records):
    out = dict.fromkeys(KINDS, 0)
    for j in records:
        out[j["flags"]["kind"]] += 1
    return out


def film_breakdown(records):
    """For utterances with a film but no scene: where the answers went."""

    def count(pred):
        return sum(1 for j in records if pred(j["flags"]))

    return {
        "n": len(records),
        "film_only_correct_film": count(
            lambda f: f["kind"] == "film" and f["film_hit"]
        ),
        "scene_in_correct_film": count(
            lambda f: f["kind"] == "scene" and f["film_hit"]
        ),
        "title_or_invalid_scene_in_correct_film": count(
            lambda f: f["kind"] == "invalid_scene" and f["film_hit"]
        ),
        "wrong_film": count(
            lambda f: f["kind"] in ("scene", "invalid_scene", "film")
            and not f["film_hit"]
        ),
        "none": count(lambda f: f["kind"] == "none"),
        "failed": count(lambda f: f["kind"] == "failed"),
    }


def summarise(judged):
    """All reported quantities for one set of judged utterances."""

    def of(*classes):
        return [j for j in judged if j["flags"]["cls"] in classes]

    scene = of(*ff_gold.SCENE_CLASSES)
    labelled = of(*ff_gold.FILM_CLASSES)
    reference = [j for j in labelled if j["flags"]["cls"] != "film_search"]
    reasons = {}
    for j in judged:
        if j["flags"]["kind"] == "failed":
            reasons[j["flags"]["reason"]] = reasons.get(j["flags"]["reason"], 0) + 1
    report = {
        "utterances": len(judged),
        "prediction_kinds": kinds(judged),
        "failure_reasons": dict(sorted(reasons.items())),
        "scene": {
            **both_ways(scene, "scene_hit"),
            "strata": {
                c: both_ways([j for j in scene if j["flags"]["cls"] == c], "scene_hit")
                for c in ff_gold.SCENE_CLASSES
            },
            "prediction_kinds": kinds(scene),
            "chance_given_film": mean([j["chance"][0] for j in scene]),
            "chance_end_to_end": mean([j["chance"][1] for j in scene]),
            "by_film": {
                str(f): both_ways(
                    [j for j in scene if j["utterance"]["film"] == f], "scene_hit"
                )
                for f in ff_gold.FILMS
            },
        },
        "film": {
            "film_labelled": both_ways(labelled, "film_hit"),
            "film_reference": both_ways(reference, "film_hit"),
        },
        "film_only": film_breakdown(of("film_only")),
        "film_search": film_breakdown(of("film_search")),
    }
    for cls in ("nonfilm", "cartoon"):
        k = kinds(of(cls))
        report[cls] = {
            "n": len(of(cls)),
            "assigned_a_film": k["scene"] + k["invalid_scene"] + k["film"],
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
        record = {
            "utterance": u,
            "flags": judge(u, normalise(predictions.get(key), film_of, valid)),
        }
        if u["cls"] in ff_gold.SCENE_CLASSES:
            record["chance"] = chance(u, film_of, valid)
        judged.append(record)
    sessions = sorted({u["session"] for u in gold})
    report = {
        "schema": SCHEMA,
        "population": ff_gold.population(gold),
        "classes": ff_gold.classes(gold),
        "valid_scene_targets": len(valid),
        "sessions": {
            s: summarise([j for j in judged if j["utterance"]["session"] == s])
            for s in sessions
        },
    }
    # A session comparison is only meaningful within the participants who have every session.
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


def load_predictions(directory, field, gold):
    """Predictions for `field`; refuses unless every participant-session has exactly one file."""
    if field not in FIELDS:
        raise ScoreError(f"field must be one of {FIELDS}")
    wanted = {(u["participant"], u["session"]) for u in gold}
    predictions, seen = {}, set()
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(directory, name), encoding="utf-8") as handle:
            doc = json.load(handle)
        session = (doc["participant"], doc["session"])
        if session in seen:
            raise ScoreError(f"two files for {session}")
        seen.add(session)
        for unit in doc["units"]:
            key = (doc["participant"], doc["session"], unit["row"])
            if key in predictions:
                raise ScoreError(f"duplicate prediction for {key}")
            if field not in unit:
                raise ScoreError(f"no {field} prediction for {key}")
            predictions[key] = unit[field]
    if seen != wanted:
        missing = sorted(wanted - seen) or sorted(seen - wanted)
        raise ScoreError(
            f"participant-sessions do not match the reference: {missing[0]}"
        )
    return predictions


def main(argv):
    if len(argv) == 4 and argv[0] == "population":
        gold = ff_gold.load_gold(argv[1], argv[2])
        film_of, valid = ff_gold.load_dictionary(argv[3])
        ff_gold.check_join(gold, film_of)
        out = {
            "population": ff_gold.population(gold),
            "classes": ff_gold.classes(gold),
            "targets": len(valid),
        }
        print(json.dumps(out, indent=1, sort_keys=True))
        return 0
    if len(argv) == 7 and argv[0] == "score":
        if os.path.exists(argv[6]):
            print("refused: output exists", file=sys.stderr)
            return 2
        gold = ff_gold.load_gold(argv[1], argv[2])
        film_of, valid = ff_gold.load_dictionary(argv[3])
        ff_gold.check_join(gold, film_of)
        try:
            report = score(
                gold, film_of, valid, load_predictions(argv[4], argv[5], gold)
            )
        except ScoreError as error:
            print(f"refused: {error}", file=sys.stderr)
            return 2
        report["field"] = argv[5]
        with open(argv[6], "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=1, sort_keys=True)
            handle.write("\n")
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
