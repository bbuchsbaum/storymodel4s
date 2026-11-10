#!/usr/bin/env python3
"""Read study mapping bundles (storymodel4s.agentmapping.bundle/v2) without Scala.

A v2 bundle is one participant-session under one condition and model. For every recall unit it
holds two judgements (narrative: the scene being narrated; referent: the scene the mentioned
content belongs to), each as the reply `stated` it and as it was decided against the source
(`judgement`), beside the two checked records and a source dictionary of films, scenes and rows.

What the values mean:
- `stated` is the answer in the form a scorer reads. `judgement.kind` is one of scene, film (a
  film with no particular scene), none, invalid-scene (a real scene that is not a target; it
  still names its film), unknown-scene, unknown-film, invalid, omitted, unparsable-chunk.
- Only scene, film and none are answers a record holds as decisions. Every other kind is a
  failed outcome in the record and is never a no-assignment.
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
import sys

SCHEMA = "storymodel4s.agentmapping.bundle/v2"
FIELDS = ("narrative", "referent")
DECISIONS = ("scene", "film", "none")
FAILURES = (
    "invalid-scene",
    "unknown-scene",
    "unknown-film",
    "invalid",
    "omitted",
    "unparsable-chunk",
)


class BundleError(ValueError):
    pass


def is_int(x):
    return isinstance(x, int) and not isinstance(x, bool)


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


def check(bundle):
    """Refuse a bundle whose rows, stated answers, source dictionary and records disagree."""
    for key in ("provenance", "interpretations", "units", "source"):
        if key not in bundle:
            raise BundleError(f"bundle has no {key}")
    units = bundle["units"]
    numbers = [unit["u"] for unit in units]
    if len(set(numbers)) != len(numbers):
        raise BundleError("a unit number appears twice")
    scenes = {s["scene"]: s for s in bundle["source"]["scenes"]}
    films = {c["collection"]: c for c in bundle["source"]["collections"]}
    for field in FIELDS:
        outcomes = record_outcomes(bundle, field)
        if [o[0] for o in outcomes] != [unit["unit"] for unit in units]:
            raise BundleError(f"unit rows do not match the {field} record's units")
        for unit, (_, kind, target) in zip(units, outcomes):
            where = f'unit {unit["u"]} {field}'
            stated, judgement = unit[field]["stated"], unit[field]["judgement"]
            j = judgement.get("kind")
            answered = stated.get("status") == "answered"
            if j == "scene" or j == "invalid-scene":
                scene = scenes.get(judgement.get("scene"))
                ok = (
                    answered
                    and stated.get("kind") == "scene"
                    and stated.get("scene") == judgement.get("scene")
                    and scene is not None
                    and scene["isTarget"] == (j == "scene")
                    and scene.get("collection") == judgement.get("collection")
                )
                expected = ("choice", scene["target"]) if ok and j == "scene" else None
            elif j == "film":
                film = films.get(judgement.get("collection"))
                ok = (
                    answered
                    and stated.get("kind") == "film"
                    and stated.get("film") == judgement.get("collection")
                    and film is not None
                    and film["isTarget"]
                )
                expected = ("choice", film["target"]) if ok else None
            elif j == "none":
                ok = answered and stated.get("kind") == "none"
                expected = ("none", None)
            elif j in ("unknown-scene", "unknown-film"):
                ok = answered and stated.get("kind") == ("scene" if j == "unknown-scene" else "film")
                expected = None
            elif j in FAILURES:
                ok = not answered
                expected = None
            else:
                raise BundleError(f"{where}: unknown judgement kind {j}")
            if not ok:
                raise BundleError(f"{where}: the judgement does not follow from the stated answer")
            if (expected or ("failed", None)) != (kind, target):
                raise BundleError(f"{where}: row says {j}, record says {kind}")
        if unit_processing_mismatch(units):
            raise BundleError("a unit's processing does not match its judgements")
    return bundle


def unit_processing_mismatch(units):
    for unit in units:
        kinds = {unit[f]["judgement"]["kind"] for f in FIELDS}
        if unit["processing"] == "answered":
            if kinds & {"omitted", "unparsable-chunk"}:
                return True
        elif kinds != {unit["processing"]}:
            return True
    return False


def load(path):
    with open(path, encoding="utf-8") as handle:
        bundle = json.load(handle)
    if not isinstance(bundle, dict) or bundle.get("schema") != SCHEMA:
        raise BundleError(f"not a {SCHEMA} document")
    return check(bundle)


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
