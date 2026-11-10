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

Three kinds of scene agreement are reported, each laid out the same way (pooled, per participant,
participant average with interval, strata, per film):
- `scene`, strict: the selected scene is in the reference set.
- `scene_adjacent`, in every condition: the selected scene, or the valid scene immediately before
  or after it in the same film (by scene number among valid targets), is in the reference set.
  For a time answer the selected scene is the scene that strictly contains the time; a time
  outside the clip selects no scene and is not a hit.
- `scene_within_2s`, in time conditions only: some scene in the prediction's `near` list (the
  valid scenes whose interval lies within 2 seconds of the time) is in the reference set. A time
  just past the end of the clip can be a hit here. The key is absent, not null, from the report
  of a condition that answers with a scene.

`chance` gives, per session, what one uniform guess would score on each of those, averaged over
the scene-specific utterances and computed exactly. For a scene condition the guess is a uniform
valid scene, of the correct film (`given_film`) or of all ten (`end_to_end`). For a time
condition it is a uniform time, within the correct film's clip or over all film time.

Usage:
    ff_score.py population GOLD CODES ANNOTATION
    ff_score.py score GOLD CODES ANNOTATION MANIFEST PREDICTIONS_DIR FIELD OUTPUT
MANIFEST is the frozen manifest; the three inputs must have the hashes it records. Whether the
answers are scenes or times is the manifest's condition, never a command-line choice. For a time
condition every prediction is first recomputed from its raw time and must come out the same.

A report's identity is checked, not asserted. Every reply file must name the manifest's model.
A reply file that records its `condition` and `manifestSha256` must match the manifest in both,
and a time condition requires both. A reply file without `condition` is `annotation` (the files
landed on 2026-11-09 predate the fields); for those the model check alone applies.
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

SCHEMA = "storymodel4s.filmfestival.transfer-score/v4"
ANNOTATION = "annotation"
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


def neighbours(film_of, valid):
    """{valid scene: the valid scenes immediately before and after it in its film}."""
    out = {}
    for film in ff_gold.FILMS:
        own = sorted(s for s in valid if film_of[s] == film)
        for at, scene in enumerate(own):
            out[scene] = own[max(at - 1, 0) : at] + own[at + 1 : at + 2]
    return out


def within(raw, prediction, film_of, valid, timed):
    """The scenes within 2 seconds of a time answer; None in a condition that answers scenes.

    `raw` is the prediction as given and `prediction` is it checked. A reply file made by one
    kind of condition cannot be scored as the other kind: a time answer among scene answers, or
    a scene answer that carries no time among time answers, is refused.
    """
    carries = isinstance(raw, dict) and ("time" in raw or "near" in raw)
    if not timed:
        if carries or prediction.get("time_outside"):
            raise ScoreError("a time answer cannot be scored as a scene answer")
        return None
    if isinstance(raw, dict) and raw.get("kind") == "scene" and "time" not in raw:
        raise ScoreError("a scene answer without a time cannot be scored as a time")
    if prediction["kind"] != "scene" and not prediction.get("time_outside"):
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


def judge(utterance, prediction, adjacent=None, near=None):
    """What one checked prediction is, against one reference.

    `adjacent` maps a valid scene to its neighbours; `near` are the scenes within 2 seconds of
    a time answer, None when the answers are scenes.
    """
    cls = utterance["cls"]
    out = {"cls": cls, "kind": prediction["kind"], "reason": prediction.get("reason")}
    out["time_outside"] = prediction.get("time_outside", False)
    if cls in ff_gold.SCENE_CLASSES:
        selected = prediction["scene"] if prediction["kind"] == "scene" else None
        out["scene_hit"] = selected is not None and selected in utterance["scenes"]
        beside = (adjacent or {}).get(selected, [])
        out["adjacent_hit"] = out["scene_hit"] or any(
            s in utterance["scenes"] for s in beside
        )
        if near is not None:
            out["within_hit"] = any(s in utterance["scenes"] for s in near)
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


def covered(spans, lo, hi):
    """The total length of the union of `spans`, clipped to [lo, hi]."""
    total, reach = 0, lo
    for start, end in sorted(spans):
        start, end = max(start, reach), min(end, hi)
        if end > start:
            total += end - start
            reach = end
    return total


def uniform_guess(utterance, film_of, valid, adjacent, table):
    """What one uniform guess scores for one scene-specific utterance, exactly.

    Returns {"given_film": {measure: probability}, "end_to_end": {...}}. Without `table` the
    guess is a uniform valid scene; with it, a uniform time in the film's clip (`given_film`) or
    over all ten clips (`end_to_end`).
    """
    film = utterance["film"]
    own = [s for s in valid if film_of[s] == film]
    strict = {s for s in own if s in utterance["scenes"]}
    beside = {s for s in own if s in strict or any(n in strict for n in adjacent[s])}
    if table is None:
        sizes = {"scene": len(strict), "scene_adjacent": len(beside)}
        return {
            "given_film": {k: v / len(own) for k, v in sizes.items()},
            "end_to_end": {k: v / len(valid) for k, v in sizes.items()},
        }
    spans = {scene: (start, end) for scene, start, end in table[film]["scenes"]}
    duration = table[film]["duration"]
    limit = ff_time.Fraction(ff_time.TOLERANCE)
    sizes = {
        "scene": sum(spans[s][1] - spans[s][0] for s in strict),
        "scene_adjacent": sum(spans[s][1] - spans[s][0] for s in beside),
        "scene_within_2s": covered(
            [(spans[s][0] - limit, spans[s][1] + limit) for s in strict], 0, duration
        ),
    }
    everything = sum(entry["duration"] for entry in table.values())
    return {
        "given_film": {k: float(v / duration) for k, v in sizes.items()},
        "end_to_end": {k: float(v / everything) for k, v in sizes.items()},
    }


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


def summarise(judged, timed=False):
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

    measures = [k for j in scene[:1] for k in j["guess"]["given_film"]]
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
        "scene_adjacent": agreement("adjacent_hit"),
        "chance": {
            scope: {m: mean([j["guess"][scope][m] for j in scene]) for m in measures}
            for scope in ("given_film", "end_to_end")
        },
        "film": {
            "film_labelled": both_ways(labelled, "film_hit"),
            "film_reference": both_ways(reference, "film_hit"),
        },
        "film_only": film_breakdown(of("film_only")),
        "film_search": film_breakdown(of("film_search")),
    }
    if timed:
        report["scene_within_2s"] = agreement("within_hit")
    for cls in ("nonfilm", "cartoon"):
        k = kinds(of(cls))
        report[cls] = {
            "n": len(of(cls)),
            "assigned_a_film": k["scene"] + k["invalid_scene"] + k["film"],
            "no_assignment": k["none"],
            "failed": k["failed"],
        }
    return report


def score(gold, film_of, valid, predictions, table=None):
    """`predictions` maps (participant, session, row) to a raw prediction. Returns the report.

    `table` is the scene-interval table of `ff_time` when the answers are times, and None when
    they are scenes. It decides whether `scene_within_2s` is reported and what a chance guess is.
    """
    known = {(u["participant"], u["session"], u["row"]) for u in gold}
    stray = sorted(k for k in predictions if k not in known)
    if stray:
        raise ScoreError(f"prediction for an unknown utterance: {stray[0]}")
    judged, adjacent = [], neighbours(film_of, valid)
    for u in gold:
        key = (u["participant"], u["session"], u["row"])
        raw = predictions.get(key)
        checked = normalise(raw, film_of, valid)
        near = within(raw, checked, film_of, valid, table is not None)
        record = {"utterance": u, "flags": judge(u, checked, adjacent, near)}
        if u["cls"] in ff_gold.SCENE_CLASSES:
            record["chance"] = chance(u, film_of, valid)
            record["guess"] = uniform_guess(u, film_of, valid, adjacent, table)
        judged.append(record)
    sessions = sorted({u["session"] for u in gold})
    report = {
        "schema": SCHEMA,
        "answers": "scene" if table is None else "time",
        "chance_guess": "uniform valid scene" if table is None else "uniform time",
        "population": ff_gold.population(gold),
        "classes": ff_gold.classes(gold),
        "valid_scene_targets": len(valid),
        "sessions": {
            s: summarise(
                [j for j in judged if j["utterance"]["session"] == s], table is not None
            )
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
                    ],
                    table is not None,
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
    recomputed from its raw answer and must equal what the reply file holds. Returns the table.
    """
    lengths, clock = manifest.get("clipSeconds"), manifest.get("clock")
    if (
        not isinstance(lengths, dict)
        or not isinstance(manifest.get("sceneIntervalsSha256"), str)
        or sorted(lengths) != sorted(str(film) for film in ff_gold.FILMS)
    ):
        raise ScoreError("the manifest of a time condition freezes no scene intervals")
    if clock != {str(film): ff_time.CLOCK[film] for film in ff_time.CLOCK}:
        raise ScoreError("the manifest's clock is not this scorer's clock")
    seconds = {int(film): s for film, s in lengths.items()}
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
    return table


def check_replies(directory, manifest, manifest_sha):
    """Every reply file must come from the run the manifest froze (see the module text)."""
    condition = manifest.get("condition", ANNOTATION)
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(directory, name), encoding="utf-8") as handle:
            reply = json.load(handle)
        if reply.get("model") != manifest.get("model"):
            raise ScoreError(
                f"{name} is from the model {reply.get('model')}, "
                f"not the manifest's {manifest.get('model')}"
            )
        if reply.get("condition", ANNOTATION) != condition:
            raise ScoreError(
                f"{name} is not from the manifest's condition, {condition}"
            )
        recorded = reply.get("manifestSha256")
        if recorded is None and "condition" not in reply:
            # A landed annotation file: any other condition was refused just above.
            continue
        if recorded != manifest_sha or "condition" not in reply:
            raise ScoreError(f"{name} was not run under this manifest")


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
            condition = manifest.get("condition", ANNOTATION)
            check_replies(replies, manifest, file_sha(manifest_path))
            table = None
            if condition != ANNOTATION:
                table = check_times(manifest, annotation_path, replies)
            gold = ff_gold.load_gold(gold_path, codes_path)
            film_of, valid = ff_gold.load_dictionary(annotation_path)
            ff_gold.check_join(gold, film_of)
            predictions = load_predictions(replies, field, gold)
            report = score(gold, film_of, valid, predictions, table)
        except (ScoreError, ff_gold.GoldError) as error:
            print(f"refused: {error}", file=sys.stderr)
            return 2
        report["field"] = field
        report["condition"] = condition
        report["model"] = manifest.get("model")
        report["manifestSha256"] = file_sha(manifest_path)
        with open(output, "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=1, sort_keys=True)
            handle.write("\n")
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
