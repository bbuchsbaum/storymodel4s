#!/usr/bin/env python3
"""Check legacy byte parity and the separate ClockRepair provenance, without recall gold."""
import argparse
import json
from pathlib import Path
import sys

import freeze_baseline as baseline


def provenance(value, manifest, repair):
    require = baseline.require
    inventory = manifest["inventory"]
    require(value["schema"] == "storymodel4s.bench.clock-repair" and
            value["schemaVersion"] == 1, "unknown clock sidecar schema")
    require(value["recordSchema"] == repair["schema"] and
            value["recordSchemaVersion"] == repair["schemaVersion"] and
            value["recordSha256"] == manifest["inputs"]["repairRecord"]["sha256"],
            "foreign repair record")
    require(value["annotationSha256"] == inventory["annotationSha256"] and
            value["sourceFingerprint"] == inventory["sourceFingerprint"] and
            value["reportSha256"] == manifest["runs"][0]["artifacts"]["report"]["sha256"],
            "foreign report or source")
    require(value["recordIdentityBasis"] == "observed-local-root-bytes" and
            value["notebookProvenanceStatus"] == "declared-unverified",
            "overstated root or notebook authority")
    crosswalk = repair["annotationToPlaybackCrosswalk"]
    for key in ("certifies", "doesNotCertify", "whyNotRepaired", "explicitlyNotUsed"):
        require(value[key] == crosswalk[key], "lost crosswalk restriction: " + key)
    for key in ("scientificRestrictions", "nonEquivalences"):
        require(value[key] == repair[key], "lost record restriction: " + key)
    parts = {p["partId"]: p for p in repair["presentationEditionIdentity"]["parts"]}
    axes = {a["part"]: a for a in inventory["axes"]}
    require(len(value["repairs"]) == len(crosswalk["runs"]), "repair population differs")
    row_receipts = {}
    for actual, declared in zip(value["repairs"], crosswalk["runs"]):
        part = parts[declared["partId"]]
        require(actual["run"] == declared["runId"] and actual["part"] == declared["partId"] and
                actual["sourceAxis"] == "annotation-run-local-seconds:" + declared["runId"] and
                actual["declaredTargetAxis"] == declared["axisId"] and
                actual["targetAxis"] == axes[declared["partId"]]["axis"], "crossed repair axes")
        require(actual["scale"] == {"numerator": str(part["video"]["ticksPerSecond"]),
                                    "denominator": "1"} and
                actual["offset"] == {"numerator": "0", "denominator": "1"},
                "changed exact arithmetic")
        require(actual["inputChecksums"] == [value["recordSha256"], value["annotationSha256"],
                                             part["sha256"]], "unbound receipt inputs")
        require(actual["algorithm"] == crosswalk["id"], "foreign repair algorithm")
        # Independently reproduce core ContentAddress's documented NUL-separated receipt digest.
        receipt = baseline.digest("\0".join(["derivation", actual["algorithm"],
                                           actual["parameters"], *actual["inputChecksums"]]).encode())
        require(actual["receiptId"] == receipt, "receipt identity differs")
        first, last = map(int, declared["annotationRows"].split("-"))
        for row in range(first, last + 1):
            require(row not in row_receipts, "overlapping repair row ranges")
            row_receipts[row] = receipt
    expected = [{k: r[k] for k in ("row", "part", "startTick", "endTick")} |
                {"receiptId": row_receipts[r["row"]]} for r in inventory["rowLoci"]]
    require(value["rows"] == expected, "missing changed or wrongly receipted row")
    return {"rows": len(expected), "repairs": len(value["repairs"]),
            "recordSha256": value["recordSha256"], "receiptIds": sorted(set(row_receipts.values()))}


def verify(before_path, after_path, data_root):
    before, after = baseline.load(before_path), baseline.load(after_path)
    roots = {"data": data_root, "repo": baseline.REPO}
    baseline.require(baseline.verify(before, data_root) == baseline.verify(after, data_root),
                     "changed baseline summary")
    for key in ("participant", "configuration", "inputs", "inventory", "summary"):
        baseline.require(before[key] == after[key], "changed " + key)
    old_hashes = {k: v["sha256"] for k, v in before["runs"][0]["artifacts"].items()}
    repair = baseline.load(baseline.resolve(after["inputs"]["repairRecord"], roots))
    sidecars = []
    for run in after["runs"]:
        baseline.require({k: v["sha256"] for k, v in run["artifacts"].items()} == old_hashes,
                         "legacy artifact bytes changed")
        path = Path(str(baseline.resolve(run["artifacts"]["report"], roots)) + ".clock-repair.json")
        checked = provenance(baseline.load(path), after, repair)
        sidecars.append(baseline.file_record(path, "data", data_root))
    baseline.require(sidecars[0]["sha256"] == sidecars[1]["sha256"], "unstable clock provenance")
    return {"beforeRevision": before["codeRevision"], "afterRevision": after["codeRevision"],
            "legacyArtifactHashes": old_hashes, "summary": after["summary"],
            "provenance": checked, "sidecars": sidecars,
            "qualification": "engineering byte parity; no gold, media reachability or behavioral-validity claim"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--data-root", type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.before, args.after, args.data_root), indent=2))
    except (ValueError, KeyError, OSError) as error:
        print("REFUSED: " + str(error), file=sys.stderr)
        sys.exit(1)
