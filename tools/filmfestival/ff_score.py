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

Conditions that answer with a time (docs/plans/2026-11-10-filmfestival-automatic-input-protocol.md)
use the same predictions. `ff_time.prediction` makes them, and adds to a scene answer the scenes
`near` the time and the raw `time`. A time outside the film's clip is
    {"status": "answered", "kind": "time_outside", "film": 3, "near": [...], "time": "9:59", ...}
It is scored as `invalid_scene` (an answer in that film that selects no valid target: never a
scene hit, never a film-only answer) and is also counted alone, as `time_outside_clip`.

Near scene agreement (`scene_near`) is reported beside strict agreement (`scene`) for every
condition: a hit when the selected scene or any near scene is in the reference set. Which scenes
are near depends on the kind of answer, and the report names the rule (`near_rule`):
- `adjacent-scene`, for a scene answer: the valid scenes immediately before and after the
  selected scene in its film, by scene number among valid targets;
- `time-2s`, for a time answer: the prediction's own `near` list, the valid scenes whose interval
  lies within 2 seconds of the time. These may exist when the time selects no scene.
The two are not the same quantity; each asks whether the answer is right to within the
reference's boundary precision.

Usage:
    ff_score.py population GOLD CODES ANNOTATION
    ff_score.py score GOLD CODES ANNOTATION MANIFEST PREDICTIONS_DIR FIELD OUTPUT
MANIFEST is the frozen manifest; the three inputs must have the hashes it records. The near rule
is the manifest's (`adjacent-scene` when it names none), never a command-line choice. For a time
condition every prediction is first recomputed from its raw time and must come out the same.
FIELD is `referent` or `narrative`. PREDICTIONS_DIR must hold one <participant>_<session>.json
for every participant-session of the reference, each
{"participant", "session", "units": [{"row": <gold_row>, "<FIELD>": <prediction>}, ...]}.
"""

import hashlib
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_gold  # noqa: E402
import ff_time  # noqa: E402

SCHEMA = "storymodel4s.filmfestival.transfer-score/v3"
ADJACENT, TIME_NEAR = "adjacent-scene", "time-2s"
NEAR_RULES = (ADJACENT, TIME_NEAR)
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
    if kind == "time_outside":
        film = prediction.get("film")
        if is_int(film) and film in ff_gold.FILMS:
            return {
                "kind": "invalid_scene",
                "film": film,
                "scene": None,
                "time_outside": True,
            }
        return failed("unknown-film")
    if kind == "none":
        return {"kind": "none", "film": None, "scene": None}
    return failed("unknown-kind")


def near_scenes(raw, prediction, film_of, valid, rule):
    """The scenes near one answer under `rule`, besides the selected scene.

    `raw` is the prediction as given and `prediction` is it checked. A reply file made by one
    kind of condition cannot be scored under the other kind's rule: a time answer under
    `adjacent-scene`, or a scene answer that carries no time under `time-2s`, is refused.
    """
    if rule not in NEAR_RULES:
        raise ScoreError(f"near rule must be one of {NEAR_RULES}")
    timed = isinstance(raw, dict) and ("time" in raw or "near" in raw)
    located = prediction["kind"] == "scene" or prediction.get("time_outside", False)
    if rule == ADJACENT:
        if timed or prediction.get("time_outside"):
            raise ScoreError("a time answer cannot be scored under adjacent-scene")
        if prediction["kind"] != "scene":
            return []
        own = sorted(s for s in valid if film_of[s] == prediction["film"])
        at = own.index(prediction["scene"])
        return own[max(at - 1, 0) : at] + own[at + 1 : at + 2]
    if isinstance(raw, dict) and raw.get("kind") == "scene" and "time" not in raw:
        raise ScoreError("a scene answer without a time cannot be scored under time-2s")
    if not located:
        return []
    near = raw.get("near")
    if (
        not isinstance(near, list)
        or not all(is_int(s) and s in valid for s in near)
        or any(film_of[s] != prediction["film"] for s in near)
        or (prediction["kind"] == "scene" and prediction["scene"] not in near)
    ):
        raise ScoreError("a time answer's near scenes are not valid scenes of its film")
    return near


def judge(utterance, prediction, near=()):
    """What one checked prediction is, against one reference. `near` are its near scenes."""
    cls = utterance["cls"]
    out = {"cls": cls, "kind": prediction["kind"], "reason": prediction.get("reason")}
    out["time_outside"] = prediction.get("time_outside", False)
    if cls in ff_gold.SCENE_CLASSES:
        out["scene_hit"] = (
            prediction["kind"] == "scene" and prediction["scene"] in utterance["scenes"]
        )
        out["near_hit"] = out["scene_hit"] or any(
            s in utterance["scenes"] for s in near
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

    def agreement(key):
        return {
            **both_ways(scene, key),
            "strata": {
                c: both_ways([j for j in scene if j["flags"]["cls"] == c], key)
                for c in ff_gold.SCENE_CLASSES
            },
            "by_film": {
                str(f): both_ways(
                    [j for j in scene if j["utterance"]["film"] == f], key
                )
                for f in ff_gold.FILMS
            },
        }

    report = {
        "utterances": len(judged),
        "prediction_kinds": kinds(judged),
        "time_outside_clip": sum(1 for j in judged if j["flags"]["time_outside"]),
        "failure_reasons": dict(sorted(reasons.items())),
        "scene": {
            **agreement("scene_hit"),
            "prediction_kinds": kinds(scene),
            "chance_given_film": mean([j["chance"][0] for j in scene]),
            "chance_end_to_end": mean([j["chance"][1] for j in scene]),
        },
        "scene_near": agreement("near_hit"),
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


def score(gold, film_of, valid, predictions, near_rule=ADJACENT):
    """`predictions` maps (participant, session, row) to a raw prediction. Returns the report.

    `near_rule` says which scenes count as near an answer (see the module text).
    """
    known = {(u["participant"], u["session"], u["row"]) for u in gold}
    stray = sorted(k for k in predictions if k not in known)
    if stray:
        raise ScoreError(f"prediction for an unknown utterance: {stray[0]}")
    judged = []
    for u in gold:
        key = (u["participant"], u["session"], u["row"])
        raw = predictions.get(key)
        checked = normalise(raw, film_of, valid)
        near = near_scenes(raw, checked, film_of, valid, near_rule)
        record = {"utterance": u, "flags": judge(u, checked, near)}
        if u["cls"] in ff_gold.SCENE_CLASSES:
            record["chance"] = chance(u, film_of, valid)
        judged.append(record)
    sessions = sorted({u["session"] for u in gold})
    report = {
        "schema": SCHEMA,
        "near_rule": near_rule,
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


def file_sha(path):
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def check_times(manifest, annotation_path, replies):
    """For a condition that answers with a time: the frozen conversion, applied again.

    The scene intervals are rebuilt from the annotation, the clock and the manifest's clip
    lengths, and must be the ones the manifest froze. Every time prediction in `replies` is then
    recomputed from its raw answer and must equal what the reply file holds.
    """
    seconds = {int(film): s for film, s in manifest["clipSeconds"].items()}
    table = ff_time.load(annotation_path, seconds)
    frozen = hashlib.sha256(ff_time.canonical(table).encode("utf-8")).hexdigest()
    if manifest["sceneIntervalsSha256"] != frozen:
        raise ScoreError("the scene intervals are not the ones the manifest froze")
    _, _, differences = ff_time.rederive_directory(table, replies)
    if differences:
        name, row, field = differences[0][:3]
        raise ScoreError(
            f"{len(differences)} predictions differ from their recomputation, "
            f"first {name} row {row} {field}"
        )


def check_inputs(manifest_path, gold_path, codes_path, annotation_path):
    """The scored reference must be the frozen one. Returns the manifest."""
    with open(manifest_path, encoding="utf-8") as handle:
        manifest = json.load(handle)
    for name, path in (
        ("goldSha256", gold_path),
        ("codesSha256", codes_path),
        ("annotationSha256", annotation_path),
    ):
        if manifest.get(name) != file_sha(path):
            raise ScoreError(
                f"{os.path.basename(path)} is not the file the manifest froze ({name})"
            )
    return manifest


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
    if len(argv) == 8 and argv[0] == "score":
        (
            gold_path,
            codes_path,
            annotation_path,
            manifest_path,
            replies,
            field,
            output,
        ) = argv[1:]
        if os.path.exists(output):
            print("refused: output exists", file=sys.stderr)
            return 2
        try:
            manifest = check_inputs(
                manifest_path, gold_path, codes_path, annotation_path
            )
            near_rule = manifest.get("nearRule", ADJACENT)
            if "sceneIntervalsSha256" in manifest:
                check_times(manifest, annotation_path, replies)
            gold = ff_gold.load_gold(gold_path, codes_path)
            film_of, valid = ff_gold.load_dictionary(annotation_path)
            ff_gold.check_join(gold, film_of)
            predictions = load_predictions(replies, field, gold)
            report = score(gold, film_of, valid, predictions, near_rule)
        except (ScoreError, ff_gold.GoldError) as error:
            print(f"refused: {error}", file=sys.stderr)
            return 2
        report["field"] = field
        report["condition"] = manifest.get("condition", "annotation")
        report["model"] = manifest.get("model")
        with open(output, "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=1, sort_keys=True)
            handle.write("\n")
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
