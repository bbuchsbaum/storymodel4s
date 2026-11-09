#!/usr/bin/env python3
"""Read an agent mapping bundle (storymodel4s.agentmapping.bundle/v1) without Scala.

A bundle holds, for one participant, the model's two scene judgements for every
recall unit (narrative: the scene being narrated; referent: the scene the
mentioned content belongs to), its self-reported alternatives and confidence,
the rows it cited, and a source dictionary that resolves a row or scene to its
place in the film.

What the values mean, and what they do not:
- A choice is categorical. There are no scores; alternatives are the model's own
  report for the narrative scene only, and are not a probability distribution.
- Confidence is self-reported. "unavailable" is not 0.
- The reply does not say which judgement a cited row supports. A row that
  resolves is a valid reference, not evidence that it supports the claim.
- A unit can fail processing (omitted by the model, or its reply never parsed),
  or a field can be invalid. Neither is a no-assignment.

Usage:
    read_bundle.py BUNDLE              summary of the bundle
    read_bundle.py BUNDLE --unit 12    one unit, by index or by unit id
"""

import argparse
import json
import sys

SCHEMA = "storymodel4s.agentmapping.bundle/v1"


class BundleError(ValueError):
    pass


def load(path):
    with open(path, encoding="utf-8") as handle:
        bundle = json.load(handle)
    if not isinstance(bundle, dict) or bundle.get("schema") != SCHEMA:
        raise BundleError(f"not a {SCHEMA} document")
    for key in ("provenance", "interpretations", "units", "source"):
        if key not in bundle:
            raise BundleError(f"bundle has no {key}")
    return bundle


def find_unit(bundle, selector):
    """A unit row by integer index or by unit id."""
    for unit in bundle["units"]:
        if str(unit["index"]) == str(selector) or unit["unit"] == selector:
            return unit
    raise BundleError(f"no unit {selector}")


def scenes_by_number(bundle):
    return {s["scene"]: s for s in bundle["source"]["scenes"]}


def rows_by_number(bundle):
    return {r["row"]: r for r in bundle["source"]["rows"]}


def record_outcome(bundle, interpretation, unit_id):
    """The checked record's outcome for a unit, as (kind, detail).

    kind is "choice" (detail: the scene number, or the target key if the source
    dictionary does not list it), "no-assignment" (detail: the stated reason), or
    the processing status of a unit with no decision (detail: the failure).
    """
    record = bundle["interpretations"][interpretation]["record"]
    scene_of = {s["target"]: s["scene"] for s in bundle["source"]["scenes"]}
    for outcome in record["outcomes"]:
        if outcome["unit"] != unit_id:
            continue
        processing = outcome["processing_status"]
        if processing["status"] != "complete":
            failure = processing.get("failure", {})
            return processing["status"], f'{failure.get("status")}: {failure.get("detail")}'
        decision = outcome["decision"]["value"]
        if decision["support"]["status"] != "categorical":
            raise BundleError("bundle record holds a non-categorical decision")
        target = decision["decoded_target_id"]
        if target["status"] != "present":
            return "no-assignment", decision["decision_origin"].get("reason")
        return "choice", scene_of.get(target["value"], target["value"])
    raise BundleError(f"record {interpretation} has no unit {unit_id}")


def check_unit(bundle, unit):
    """Refuse a bundle whose unit row and checked records disagree."""
    for name in ("narrative", "referent"):
        kind, detail = record_outcome(bundle, name, unit["unit"])
        field = unit[name]
        answered = unit["processing"]["status"] == "answered"
        expected = (
            ("choice", field["value"])
            if answered and field["status"] == "value"
            else ("no-assignment", None)
            if answered and field["status"] == "null"
            else ("failed", None)
        )
        if kind != expected[0] or (kind == "choice" and detail != expected[1]):
            raise BundleError(
                f'unit {unit["index"]}: {name} row says {field["status"]}, record says {kind}'
            )


def describe_field(field, render=str):
    status = field["status"]
    if status == "value":
        return render(field["value"])
    if status == "null":
        return "null (reported by the model)"
    if status == "absent":
        return "unavailable (not in the reply)"
    return f'invalid: {json.dumps(field["raw"])}'


def describe_unit(bundle, unit):
    """Lines describing one unit: both judgements, citations and their source support."""
    scenes = scenes_by_number(bundle)
    rows = rows_by_number(bundle)

    def scene_text(number):
        scene = scenes.get(number)
        return (
            f'scene {number} "{scene["label"]}"'
            if scene
            else f"scene {number} (not in source)"
        )

    check_unit(bundle, unit)
    processing = unit["processing"]
    lines = [
        f'unit {unit["index"]} ({unit["unit"]})',
        f'processing: {processing["status"]} '
        f'(chunk {processing["chunk"]}, {processing["attempts"]} attempt(s))',
    ]
    for name in ("narrative", "referent"):
        kind, detail = record_outcome(bundle, name, unit["unit"])
        lines.append(f"{name}: {describe_field(unit[name], scene_text)}")
        lines.append(f"  record: {kind}" + (f" ({detail})" if kind != "choice" else f" of scene {detail}"))
    if processing["status"] != "answered":
        return lines
    lines.append(
        "alternatives (self-reported, narrative only, not a distribution): "
        + describe_field(
            unit["alternatives"],
            lambda alts: "; ".join(f"scene {s} p={json.dumps(p)}" for s, p in alts),
        )
    )
    lines.append("confidence (self-reported): " + describe_field(unit["confidence"]))
    cited = unit["rows"]
    if cited["status"] != "value":
        lines.append("rows cited: " + describe_field(cited))
        return lines
    narrative = (
        unit["narrative"].get("value")
        if unit["narrative"]["status"] == "value"
        else None
    )
    lines.append("rows cited (the reply does not say which judgement they support):")
    for number in cited["value"]:
        row = rows.get(number)
        if row is None:
            lines.append(f"  r{number}: not in source")
            continue
        note = ""
        if narrative is not None and row["scene"] != narrative:
            note = "  [outside the narrative scene]"
        lines.append(
            f'  r{number}: scene {row["scene"]}, part {row["part"]}, '
            f'{row["start"]}-{row["end"]}: {row["text"]}{note}'
        )
    return lines


def summary(bundle):
    provenance = bundle["provenance"]
    units = bundle["units"]
    for unit in units:
        check_unit(bundle, unit)
    counts = {}
    for unit in units:
        status = unit["processing"]["status"]
        counts[status] = counts.get(status, 0) + 1

    def field_counts(name):
        out = {}
        for unit in units:
            if unit["processing"]["status"] == "answered":
                out[unit[name]["status"]] = out.get(unit[name]["status"], 0) + 1
        return out

    differ = sum(
        1
        for unit in units
        if unit["narrative"]["status"] == "value"
        and unit["referent"]["status"] == "value"
        and unit["narrative"]["value"] != unit["referent"]["value"]
    )
    return [
        f'participant {provenance["participant"]}: {len(units)} units',
        f'model {provenance["model"]} via {provenance["provider"]}; '
        f'parser {provenance["parser"]}; model calls {provenance["modelCalls"]}',
        "processing: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())),
        "narrative: "
        + ", ".join(f"{k}={v}" for k, v in sorted(field_counts("narrative").items())),
        "referent: "
        + ", ".join(f"{k}={v}" for k, v in sorted(field_counts("referent").items())),
        f"narrative and referent differ: {differ}",
        f'source: {len(bundle["source"]["scenes"])} scenes, {len(bundle["source"]["rows"])} rows',
    ]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("bundle")
    parser.add_argument("--unit", help="unit index or unit id")
    args = parser.parse_args(argv)
    try:
        bundle = load(args.bundle)
        lines = (
            summary(bundle)
            if args.unit is None
            else describe_unit(bundle, find_unit(bundle, args.unit))
        )
    except (BundleError, OSError, json.JSONDecodeError) as error:
        print(f"refused: {error}", file=sys.stderr)
        return 2
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
