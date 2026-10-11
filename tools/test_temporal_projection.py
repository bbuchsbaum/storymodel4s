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
    required = {"point-only-support", "large-tick-9007199254740993", "multimodal-disjoint-support",
                "mixture-half", "repeated-media-occurrence-0", "repeated-media-occurrence-1", "failed-unit-rows"}
    check(required <= cases.keys(), "missing temporal acceptance cases")
    for name in ("point-only-support", "large-tick-9007199254740993"):
        check(any(r["grid"].get("allocated") for r in cases[name]["rows"]), name + " lacks positive atomic mass")
    for row in cases["multimodal-disjoint-support"]["rows"]:
        if "unavailable" not in row["grid"]:
            values = {reader.integer(v["bin"],"bin"):reader.binary64(v["mass"],"mass") for v in row["grid"]["allocated"]}
            check(values == {0:.25,2:.25}, "disconnected peaks or gap changed")
    for row in cases["mixture-half"]["rows"]:
        if "unavailable" not in row["grid"]:
            values = {reader.integer(v["bin"],"bin"):reader.binary64(v["mass"],"mass") for v in row["grid"]["allocated"]}
            check(set(values) == {0,1,2}, "mixture lost a mode")
            for index,value in {0:.025,1:.45,2:.025}.items():reader.close(values[index],value,"mixture known answer")
    for name in ("repeated-media-occurrence-0", "repeated-media-occurrence-1"):
        check(cases[name]["request"]["selection"]["kind"] == "occurrence", "not an occurrence fixture")
        for row in cases[name]["rows"]:
            if "unavailable" not in row["grid"]:
                check(not row["grid"]["allocated"], "clipped occurrence was redistributed")
                reader.close(reader.binary64(row["grid"]["ledger"]["unavailableLocation"],"missing"),.75,"clipped occurrence ledger")
    check(any(r["processing"].startswith("Failed") and "unavailable" in r["grid"] for r in cases["failed-unit-rows"]["rows"]), "failed unit disappeared")
    root = Path(folder)
    manifest = reader.load((root / "temporal-fixtures.json").read_text())
    check(len(manifest["producerRevision"]) == 40, "producer revision missing")
    paired = []
    bundles = []
    for case in manifest["cases"]:
        summary = case["summary"]
        check(summary["attempts"] == 2 and summary["failed"] == 1, "failed cohort member disappeared")
        check(summary["temporalCompleted"] == 1 and summary["temporalUnavailable"] == 1, "temporal denominator changed")
        case_files = {Path(e["path"]).name:(root / e["path"]).read_text() for e in case["files"]}
        pair_prefixes = [name[:-len("manifest.json")] for name in case_files if name.endswith("--manifest.json") and len(name) == 64 + len("--manifest.json")]
        check(len(pair_prefixes) == 1, "one successful pair expected")
        prefix = pair_prefixes[0]
        bundle = {name[len(prefix):]:text for name,text in case_files.items() if name.startswith(prefix)}
        reader.verify_pair(bundle)
        bundles.append(bundle)
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
    # Rehash the changed projection descriptor, so contextual failures cannot be explained by bytes alone.
    def pair_refuses(name, member, mutate):
        files = dict(next(b for b in bundles if "reference--recall-timing.json" in b))
        value = reader.load(files[member]);mutate(value)
        files[member] = json.dumps(value, sort_keys=True, separators=(",", ":"))
        pair_manifest = reader.load(files["manifest.json"])
        for entry in pair_manifest["files"]:
            if entry["path"] == member:
                raw = files[member].encode()
                entry.update(bytes=str(len(raw)), sha256=hashlib.sha256(raw).hexdigest())
        files["manifest.json"] = json.dumps(pair_manifest, sort_keys=True, separators=(",", ":"))
        try:reader.verify_pair(files)
        except (ValueError, KeyError, TypeError, OverflowError):negatives.append(name)
        else:raise AssertionError("contextual corruption accepted: " + name)
    projection_name = "reference--temporal-projection.json"
    pair_refuses("foreign selected bundle with rehashed bytes", projection_name, lambda d: d["request"]["selection"].update(bundle="0"*64))
    def suppress_available(d):
        row = d["rows"][0]
        for field in ["grid", "durationWeighted", "durationWeightedBounds"]:row[field] = {"unavailable":"Region(MissingMeasure)"}
    pair_refuses("suppressed available measure with rehashed bytes", projection_name, suppress_available)
    pair_refuses("foreign mapping with rehashed bytes", projection_name, lambda d: d.update(mapping="0"*64))
    pair_refuses("foreign measure with rehashed bytes", projection_name, lambda d: d.update(measure="TransportMass"))
    pair_refuses("foreign projection with rehashed bytes", projection_name, lambda d: d.update(digest="0"*64))
    pair_refuses("foreign inventory unit with rehashed bytes", projection_name, lambda d: d["rows"][0].update(unit="foreign-unit"))
    pair_refuses("foreign timing with rehashed bytes", projection_name, lambda d: d.update(timing="0"*64))
    pair_refuses("foreign recall clock with rehashed bytes", projection_name, lambda d: d["request"]["recallWindow"].update(clock="0"*64))
    pair_refuses("foreign timing inventory with rehashed bytes", "reference--recall-timing.json", lambda d: d.update(inventory_digest="0"*64))
    pair_refuses("foreign word clock with rehashed bytes", "reference--recall-timing.json", lambda d: d["entries"][0].update(clock="0"*64))
    print(json.dumps({"producerRevision": manifest["producerRevision"], "syntheticCases": sorted(cases), "facadeProjections": len(paired), "corruptionsRefused": negatives}, indent=2))


if __name__ == "__main__":
    main(*sys.argv[1:])
