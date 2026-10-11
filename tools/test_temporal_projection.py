#!/usr/bin/env python3
"""Check actual producer exports, independent known answers, and deliberate corruptions.

Usage: python3 tools/test_temporal_projection.py FIXTURE_LOG FACADE_WITNESS_DIRECTORY
The Scala generator command and exact producer revision belong in the accompanying receipt.
"""
import copy
import hashlib
import json
import sys
from fractions import Fraction
from pathlib import Path
import verify_temporal_projection as reader


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def main(log, folder):
    cases = {}
    for line in Path(log).read_text().splitlines():
        if "TEMPORAL_PROJECTION_FIXTURE\t" in line:
            _, name, encoded = line.split("\t", 2)
            check(name not in cases, "duplicate named fixture")
            cases[name] = reader.load(encoded)
    check({"narrow", "broad", "disjoint", "support-only"} <= cases.keys(), "missing positive fixtures")
    for name, document in cases.items():
        reader.verify(document)
    # The producer fixture declares weight .5 on the uniform interval [10,20).
    for name, amount in (("narrow", .05), ("broad", .5), ("disjoint", .1)):
        rows = [r for r in cases[name]["rows"] if "unavailable" not in r["grid"]]
        check(bool(rows), "no measurable positive rows")
        for row in rows:
            reader.close(sum(reader.binary64(v["mass"], name) for v in row["grid"]["allocated"]), amount, name + " known answer")
    for row in cases["support-only"]["rows"]:
        if "unavailable" not in row["grid"]:
            check(row["grid"]["allocated"] == [], "support acquired allocation")
            reader.close(reader.binary64(row["grid"]["bounds"][0]["upper"], "upper"), .5, "support upper bound")
    root = Path(folder)
    manifest = reader.load((root / "temporal-fixtures.json").read_text())
    check(len(manifest["producerRevision"]) == 40, "producer revision missing")
    paired = []
    for case in manifest["cases"]:
        summary = case["summary"]
        check(summary["attempts"] == 2 and summary["failed"] == 1, "failed cohort member disappeared")
        check(summary["temporalCompleted"] == 1 and summary["temporalUnavailable"] == 1, "temporal denominator changed")
        for entry in case["files"]:
            data = (root / entry["path"]).read_bytes()
            check(hashlib.sha256(data).hexdigest() == entry["sha256"], "facade artifact hash mismatch")
            if entry["path"].endswith("--temporal-projection.json"):
                document = reader.load(data.decode())
                reader.verify(document)
                paired.append(document)
                if case["name"] == "timed":
                    check(reader.fraction(document["exposureReadout"]["uncovered"], "uncovered") == 3, "gap filled")
                    check(sum(reader.fraction(r["exposure"]["duration"], "duration") for r in document["rows"]) == 1, "duration changed")
                    check([reader.fraction(t, "onset") for r in document["rows"] for t in r["exposure"]["onsetEvents"]] == [Fraction(2)], "last onset invented an offset")
                else:
                    check(document["timingState"] == "Missing" and "exposureReadout" not in document, "untimed exposure imputed")
    check(len(paired) == 4, "both members of both facade cases required")
    negatives = []
    def refuses(name, original, alter):
        value = copy.deepcopy(original)
        alter(value)
        try:
            reader.verify(value)
        except (ValueError, KeyError, TypeError, OverflowError):
            negatives.append(name)
        else:
            raise AssertionError("corruption accepted: " + name)
    positive = cases["narrow"]
    def row(doc):
        return next(r for r in doc["rows"] if r["grid"].get("allocated"))
    refuses("allocated quantity", positive, lambda d: row(d)["grid"]["allocated"][0].update(mass="0x0000000000000000"))
    refuses("missing sparse cell", positive, lambda d: row(d)["grid"]["cells"].clear())
    refuses("original component weight", positive, lambda d: row(d)["grid"]["cells"][0]["component"].update(weight="0x3ff0000000000000"))
    refuses("crop outside quantity", positive, lambda d: row(d)["grid"].update(allocatedOutside="0x0000000000000000"))
    refuses("external ledger", positive, lambda d: row(d)["grid"]["ledger"]["external"].clear())
    refuses("support upper bound", cases["support-only"], lambda d: next(r for r in d["rows"] if r["grid"].get("bounds"))["grid"]["bounds"][0].update(upper="0x0000000000000000"))
    refuses("foreign axis", positive, lambda d: d["regions"][0].update(axis="foreign"))
    refuses("numeric tick conversion", positive, lambda d: d["regions"][0]["intervals"][0].update(start=10))
    refuses("numeric axis extent", positive, lambda d: d["coordinate"]["extent"].update(start_tick=0))
    timed = next(p for p in paired if p["timingState"] == "Provided")
    refuses("lost exposure gap", timed, lambda d: d["exposureReadout"]["uncovered"].update(numerator="0"))
    def corrupt_weighted(d):
        r = next(r for r in d["rows"] if r["durationWeighted"].get("values"))
        r["durationWeighted"]["values"][0]["quantity"] = "0x0000000000000000"
    refuses("duration weighted quantity", timed, corrupt_weighted)
    if "large-tick-9007199254740993" in cases:
        large = cases["large-tick-9007199254740993"]
        check(large["regions"][0]["intervals"][0]["start"] == "9007199254740993", "large tick was rounded")
        refuses("lossy 2^53 conversion", large, lambda d: d["regions"][0]["intervals"][0].update(start=str(int(float("9007199254740993")))))
    try:
        reader.load('{"schemaVersion":"x","schemaVersion":"y"}')
    except ValueError:
        negatives.append("duplicate JSON keys")
    else:
        raise AssertionError("duplicate JSON keys accepted")
    print(json.dumps({"producerRevision": manifest["producerRevision"], "syntheticCases": sorted(cases), "facadeProjections": len(paired), "corruptionsRefused": negatives}, indent=2))


if __name__ == "__main__":
    main(*sys.argv[1:])
