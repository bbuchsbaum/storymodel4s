#!/usr/bin/env python3
"""Read study mapping bundles (storymodel4s.agentmapping.bundle/v2) without Scala.

A v2 bundle is one participant-session under one condition and model. For every recall unit it
holds two judgements (narrative: the scene being narrated; referent: the scene the mentioned
content belongs to), each as the reply `stated` it and as it was decided against the source
(`judgement`), beside the two checked records and a source dictionary of films, scenes and rows.

What the values mean:
- `stated` is the answer in the form a scorer reads. `judgement.kind` is one of scene, film (a
  film with no particular scene), time-outside, none, invalid-scene (a real scene that is not a target; it
  still names its film), unknown-scene, unknown-film, invalid, omitted, unparsable-chunk.
- Only scene, film, time-outside and none are answers a record holds as decisions. Every other kind is a
  failed outcome in the record and is never a no-assignment.
- A condition that answers with a time keeps the raw film and time in `stated`, beside the scene
  that contains the time and the scenes near it. `source.clock` holds each film's scene
  intervals as exact fractions. `time-outside` is a time outside the film's clip: it names the
  film and selects no scene, and the record holds it as a choice of the film.
- `extras` are self-reported fields kept verbatim. They are not scores.
- A unit is identified by participant, session and its number `u` (or the study's `row`). The
  `unit` id is derived from the text and can repeat across sessions.

Usage:
    study_bundle.py summary BUNDLE
    study_bundle.py unit BUNDLE U
    study_bundle.py replies BUNDLE_DIR OUT_DIR    reply files, one per bundle, for a scorer
"""

import json
import os
import re
import sys
from fractions import Fraction

SCHEMA = "storymodel4s.agentmapping.bundle/v2"
FIELDS = ("narrative", "referent")


class BundleError(ValueError):
    pass


def is_int(x):
    return isinstance(x, int) and not isinstance(x, bool)


RESERVED = ("u", "row", "unit", "processing") + FIELDS


def record_outcomes(bundle, field):
    """Each unit's outcome in the checked record: (unit id, kind, target key or None)."""
    out = []
    for outcome in bundle["interpretations"][field]["record"]["outcomes"]:
        if outcome["processing_status"]["status"] != "complete":
            out.append((outcome["unit"], "failed", None))
            continue
        decision = outcome["decision"]["value"]
        if decision["support"]["status"] != "categorical":
            raise BundleError("bundle record holds a non-categorical decision")
        target = decision["decoded_target_id"]
        if target["status"] == "present":
            out.append((outcome["unit"], "choice", target["value"]))
        else:
            out.append((outcome["unit"], "none", None))
    return out


TIME = re.compile(r"([0-9]{1,2}):([0-5][0-9](?:\.[0-9]{1,3})?)")
TIME_PROTOCOLS = ("strict-chunked-time/v1",)


def clock_table(source):
    """{film: (duration, [(scene, start, end)])} from a bundle's `source.clock`, exact."""
    table = {}
    for film in source["clock"]["films"]:
        table[film["collection"]] = (
            Fraction(film["durationSeconds"]),
            [(s["scene"], Fraction(s["start"]), Fraction(s["end"])) for s in film["scenes"]],
        )
    return Fraction(source["clock"]["toleranceSeconds"]), table


def time_prediction(clock, film, time):
    """A raw film and time as the prediction a time condition stores (the rule of the
    library's `StudyReplay.timeStated` and `SceneIntervals.locate`)."""
    tolerance, table = clock
    if time is None:
        if is_int(film):
            return {"status": "answered", "kind": "film", "film": film}
        if film is None:
            return {"status": "answered", "kind": "none"}
        return {"status": "failed", "reason": "invalid-film"}
    raw = {"film_stated": film, "time": time}
    match = TIME.fullmatch(time) if isinstance(time, str) else None
    if not match:
        return {"status": "failed", "reason": "invalid-time", **raw}
    if film is None:
        return {"status": "failed", "reason": "time-without-film", **raw}
    if not is_int(film):
        return {"status": "failed", "reason": "invalid-film", **raw}
    if film not in table:
        return {"status": "failed", "reason": "unknown-film", **raw}
    seconds = int(match.group(1)) * 60 + Fraction(match.group(2))
    duration, scenes = table[film]
    near = [s for s, start, end in scenes if max(start - seconds, seconds - end, 0) <= tolerance]
    rest = {"near": near, **raw, "seconds": float(seconds)}
    if seconds < 0 or seconds > duration:
        return {"status": "answered", "kind": "time_outside", "film": film, **rest}
    inside = [s for s, start, end in scenes if start <= seconds < end]
    scene = inside[0] if inside else scenes[-1][0]
    return {"status": "answered", "kind": "scene", "scene": scene, **rest}


def judge(stated, scenes, films, clock=None, timed=False):
    """The judgement a stated answer gives against the bundle's source dictionary, and the
    record outcome it requires. The same rule as the library's `StudyRecord.judge`. A time
    answer is first converted again from its raw film and time and must be what is stored.
    """
    failed = ("failed", None)
    if not isinstance(stated, dict):
        raise BundleError("a stated answer is not an object")
    if "time" in stated:
        if clock is None or not timed:
            raise BundleError("a time answer in a bundle with no clip clock")
        if time_prediction(clock, stated.get("film_stated"), stated["time"]) != stated:
            raise BundleError("a time answer is not what its film and time give")
        if stated.get("kind") == "time_outside" and stated.get("status") == "answered":
            film = films.get(stated["film"])
            if film is None or not film["isTarget"]:
                raise BundleError("a time is placed in a film that is not a target")
            return (
                {"kind": "time-outside", "collection": stated["film"]},
                ("choice", film["target"]),
            )
    elif timed and stated.get("status") == "answered" and stated.get("kind") == "scene":
        raise BundleError("a scene without a time in a condition that answers with a time")
    if stated.get("status") == "failed":
        reason = stated.get("reason")
        if not isinstance(reason, str):
            raise BundleError("a failure has no reason")
        if reason in ("omitted", "unparsable-chunk"):
            return {"kind": reason}, failed
        return {"kind": "invalid", "reason": reason}, failed
    if stated.get("status") != "answered":
        raise BundleError("a stated answer is neither answered nor failed")
    kind = stated.get("kind")
    if kind == "scene":
        number = stated.get("scene")
        if not is_int(number):
            raise BundleError("a stated scene is not an integer")
        scene = scenes.get(number)
        if scene is None:
            return {"kind": "unknown-scene", "stated": str(number)}, failed
        judgement = {"scene": number}
        if "collection" in scene:
            judgement["collection"] = scene["collection"]
        if scene["isTarget"]:
            return {"kind": "scene", **judgement}, ("choice", scene["target"])
        return {"kind": "invalid-scene", **judgement}, failed
    if kind == "film":
        number = stated.get("film")
        if not is_int(number):
            raise BundleError("a stated film is not an integer")
        film = films.get(number)
        if film is None or not film["isTarget"]:
            return {"kind": "unknown-film", "stated": str(number)}, failed
        return {"kind": "film", "collection": number}, ("choice", film["target"])
    if kind == "none":
        return {"kind": "none"}, ("none", None)
    raise BundleError("a stated answer has an unknown kind")


def decision_receipt(record):
    for receipt in record["stage_assumption_receipts"]:
        if receipt["stage"] == "Decision":
            return receipt["provenance"]["asserted"]["value"]
    raise BundleError("a record has no decision receipt")


def check(bundle):
    """Refuse a bundle that is not consistent with itself.

    Every judgement is derived again from its stated answer and the source dictionary and must
    be what the row holds; each record must then hold that outcome for the unit. Each record
    must be its slot's, under the bundle's condition and protocol, and must name the bundle's
    model, provider, instruction and units.

    What this cannot check: that the unit numbers and row keys are the study's, and that the
    records are intact. Both need the study export; the library's `StudyBundle.decode` checks
    them. A scorer's join against its reference catches a row key the study does not have.
    """
    for key in ("provenance", "interpretations", "units", "source"):
        if key not in bundle:
            raise BundleError(f"bundle has no {key}")
    provenance = bundle["provenance"]
    units = bundle["units"]
    if [unit["u"] for unit in units] != list(range(1, len(units) + 1)) or not all(
        is_int(unit["u"]) for unit in units
    ):
        raise BundleError("units are not numbered 1 to n in order")
    scenes = {s["scene"]: s for s in bundle["source"]["scenes"]}
    films = {c["collection"]: c for c in bundle["source"]["collections"]}
    timed = provenance["protocol"] in TIME_PROTOCOLS
    if timed != ("clock" in bundle["source"]):
        raise BundleError("a time condition needs a clip clock, and no other has one")
    clock = clock_table(bundle["source"]) if timed else None
    for unit in units:
        extras = unit["extras"]
        if not isinstance(extras, dict) or any(key in RESERVED for key in extras):
            raise BundleError(f'unit {unit["u"]}: an extra uses a row\'s own key')
    for field in FIELDS:
        slot = bundle["interpretations"][field]
        policy = f'agent-localization:{provenance["condition"]}:{provenance["protocol"]}:{field}'
        receipt = decision_receipt(slot["record"])
        declared = slot["record"]["policies"]["decision_policy_id"]
        if not (slot["policy"] == declared.get("id") == receipt["policy"] == policy):
            raise BundleError(f"the {field} slot does not hold the {field} record")
        producer = receipt["provider"]
        if (
            producer.get("model") != provenance["model"]
            or producer.get("provider") != provenance["provider"]
            or receipt["config"] != provenance["systemSha256"]
            or provenance["segmentation"]["artifactSha256"] not in receipt["inputs"]
            or bundle["source"]["artifact"] not in receipt["inputs"]
        ):
            raise BundleError(f"the {field} record is not from the run the bundle names")
        outcomes = record_outcomes(bundle, field)
        if [o[0] for o in outcomes] != [unit["unit"] for unit in units]:
            raise BundleError(f"unit rows do not match the {field} record's units")
        for unit, (_, kind, target) in zip(units, outcomes):
            where = f'unit {unit["u"]} {field}'
            judgement, expected = judge(unit[field]["stated"], scenes, films, clock, timed)
            if unit[field]["judgement"] != judgement:
                raise BundleError(f"{where}: the judgement does not follow from the stated answer")
            if expected != (kind, target):
                raise BundleError(f'{where}: row says {judgement["kind"]}, record says {kind}')
    for unit in units:
        kinds = {unit[f]["judgement"]["kind"] for f in FIELDS}
        whole = kinds & {"omitted", "unparsable-chunk"}
        processing = "answered" if not whole else (kinds.pop() if len(kinds) == 1 else None)
        if unit["processing"] != processing:
            raise BundleError("a unit's processing does not match its judgements")
    return bundle


def load(path):
    with open(path, encoding="utf-8") as handle:
        bundle = json.load(handle)
    if not isinstance(bundle, dict) or bundle.get("schema") != SCHEMA:
        raise BundleError(f"not a {SCHEMA} document")
    try:
        return check(bundle)
    except (KeyError, TypeError, AttributeError) as error:
        raise BundleError(f"malformed bundle: {error!r}")


def reply_document(bundle):
    """The bundle as the reply file a scorer reads: one row per unit, keyed by the study's row."""
    provenance = bundle["provenance"]
    units = []
    for unit in bundle["units"]:
        row = {"u": unit["u"], "row": unit["row"]}
        for field in ("referent", "narrative"):
            row[field] = unit[field]["stated"]
        row.update(unit["extras"])
        units.append(row)
    return {
        "participant": provenance["participant"],
        "session": provenance["session"],
        "model": provenance["model"],
        "condition": provenance["condition"],
        "manifestSha256": provenance["manifestSha256"],
        "units": units,
    }


def load_directory(directory):
    """Every bundle in a directory, checked, by file name. Refuses a directory with none."""
    names = sorted(n for n in os.listdir(directory) if n.endswith(".bundle.json"))
    if not names:
        raise BundleError("no bundles in the directory")
    bundles = {name: load(os.path.join(directory, name)) for name in names}
    for name, b in bundles.items():
        if name != f'{b["provenance"]["participant"]}_{b["provenance"]["session"]}.bundle.json':
            raise BundleError(f"{name} is not named for its participant and session")
    keys = {(b["provenance"]["participant"], b["provenance"]["session"]) for b in bundles.values()}
    if len(keys) != len(bundles):
        raise BundleError("two bundles for one participant-session")
    for key in ("condition", "model", "protocol", "manifestSha256"):
        if len({b["provenance"][key] for b in bundles.values()}) != 1:
            raise BundleError(f"the bundles do not share one {key}")
    return bundles


def describe(judgement, bundle):
    kind = judgement["kind"]
    films = {c["collection"]: c["label"] for c in bundle["source"]["collections"]}
    if kind in ("scene", "invalid-scene"):
        film = judgement.get("collection")
        where = f' in film {film} "{films[film]}"' if film in films else ""
        note = " (not a target)" if kind == "invalid-scene" else ""
        return f'scene {judgement["scene"]}{where}{note}'
    if kind == "film":
        film = judgement["collection"]
        return f'film {film} "{films[film]}", no particular scene'
    if kind == "time-outside":
        film = judgement["collection"]
        return f'film {film} "{films[film]}", at a time outside its clip'
    if kind == "none":
        return "no assignment"
    detail = judgement.get("stated") or judgement.get("reason")
    return f"failed: {kind}" + (f" ({detail})" if detail else "")


def summary(bundle):
    provenance = bundle["provenance"]
    lines = [
        f'{provenance["participant"]} {provenance["session"]}: {len(bundle["units"])} units',
        f'condition {provenance["condition"]}; model {provenance["model"]} via '
        f'{provenance["provider"]}; protocol {provenance["protocol"]}; '
        f'model calls {provenance["modelCalls"]}',
        f'units {provenance["segmentation"]["kind"]} ({provenance["segmentation"]["recipe"]})',
    ]
    for field in FIELDS:
        counts = {}
        for unit in bundle["units"]:
            kind = unit[field]["judgement"]["kind"]
            counts[kind] = counts.get(kind, 0) + 1
        lines.append(f"{field}: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    source = bundle["source"]
    lines.append(
        f'source: {len(source["collections"])} films, {len(source["scenes"])} scenes, '
        f'{len(source["rows"])} rows'
    )
    return lines


def describe_unit(bundle, number):
    for unit in bundle["units"]:
        if str(unit["u"]) == str(number):
            lines = [f'unit {unit["u"]} (row {json.dumps(unit["row"])}): {unit["processing"]}']
            for field in FIELDS:
                lines.append(f"{field}: " + describe(unit[field]["judgement"], bundle))
            for key, value in unit["extras"].items():
                lines.append(f"{key} (self-reported): {json.dumps(value)}")
            return lines
    raise BundleError(f"no unit {number}")


def write_replies(directory, output):
    if os.path.exists(output):
        raise BundleError("output exists")
    bundles = load_directory(directory)
    os.makedirs(output)
    for bundle in bundles.values():
        doc = reply_document(bundle)
        name = f'{doc["participant"]}_{doc["session"]}.json'
        with open(os.path.join(output, name), "w", encoding="utf-8") as handle:
            json.dump(doc, handle, indent=1)
            handle.write("\n")
    return len(bundles)


def main(argv):
    try:
        if len(argv) == 2 and argv[0] == "summary":
            print("\n".join(summary(load(argv[1]))))
            return 0
        if len(argv) == 3 and argv[0] == "unit":
            print("\n".join(describe_unit(load(argv[1]), argv[2])))
            return 0
        if len(argv) == 3 and argv[0] == "replies":
            print(f"bundles={write_replies(argv[1], argv[2])}")
            return 0
    except (BundleError, OSError, KeyError, json.JSONDecodeError) as error:
        print(f"refused: {error!r}", file=sys.stderr)
        return 2
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
