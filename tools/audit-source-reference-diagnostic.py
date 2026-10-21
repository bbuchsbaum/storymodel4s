#!/usr/bin/env python3
"""Audit the six frozen local A/V/AV reference runs without reading recall text.

This reports source-rank agreement and known development-case diagnostics, not accuracy.
The run directory contains a-historical, a-mapping, v-historical, v-mapping,
av-historical, and av-mapping publications. Producer authority comes from the
separately retained execution receipt, never from a caller-supplied SHA.
"""
import argparse
import hashlib
import json
import math
import struct
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("runs", type=Path)
args = parser.parse_args()
base = args.runs


def number(value):
    return struct.unpack(">d", bytes.fromhex(value[2:]))[0]


def ranks(values):
    return {
        k: 1 + sum(v > value + 1e-12 for v in values.values())
        for k, value in values.items()
    }


def summary(name):
    root = base / name
    manifest = json.loads((root / "manifest.json").read_text())
    for f in manifest["files"]:
        path = (root / f["path"]).resolve()
        if not path.is_relative_to(root.resolve()):
            raise ValueError("manifest path escapes run")
        if hashlib.sha256(path.read_bytes()).hexdigest() != f["sha256"]:
            raise ValueError("manifest checksum mismatch")
    paths = list(root.glob("*reference.json"))
    if len(paths) != 1:
        raise ValueError("expected exactly one reference record")
    path = paths[0]
    if path.name not in {f["path"] for f in manifest["files"]}:
        raise ValueError("reference record is not in the verified manifest")
    record = json.loads(path.read_text())
    ordinals = {u["id"]: u["ordinal"] for u in record["inventory"]["units"]}
    universe = set(record["policies"]["target_universe_id"]["targets"])
    rows = {}
    for out in record["outcomes"]:
        source = {}
        external = {}
        normalized = out["measures"]["normalized"]
        population = normalized.get("value", {}).get(
            "population", "SuppliedDestinations"
        )
        conditional = population == "AdmittedSourceTargets"
        if normalized["status"] == "present" and conditional != name.endswith(
            "-mapping"
        ):
            raise ValueError("unexpected normalization population for policy arm")
        for row in out["mapping_links"]:
            if row["measure_kind"] != "NormalizedScoreMass":
                continue
            target = source if row["destination"] in universe else external
            value = number(row["raw_value"])
            if not math.isfinite(value) or not 0.0 <= value <= 1.0:
                raise ValueError("invalid score mass")
            target[row["destination"]] = target.get(row["destination"], 0) + value
        if source or external:
            if (
                abs(math.fsum(source.values()) + math.fsum(external.values()) - 1.0)
                > 1e-12
            ):
                raise ValueError("mass does not sum to one")
            if conditional and external:
                raise ValueError("conditional run contains external mass")
        order = ranks(source)
        top = [k for k, v in order.items() if v == 1]
        rows[ordinals[out["unit"]]] = {
            "source": source,
            "external": external,
            "ranks": order,
            "top": top,
        }
    cases = []
    for ordinal in (92, 93):
        row = rows[ordinal]
        items = sorted(row["source"], key=lambda k: (row["ranks"][k], k))
        cases.append(
            {
                "unit": ordinal,
                "nominated_source_targets": len(items),
                "top_target_rows": [int(t.rsplit(":", 1)[1]) for t in row["top"]],
                "known_target_ranks": {
                    str(i): next(
                        (row["ranks"][t] for t in items if t.endswith(":" + str(i))),
                        None,
                    )
                    for i in (503, 507, 508, 509, 575, 591, 592, 596)
                },
                "all_nominated": [
                    {
                        "target": t,
                        "row": int(t.rsplit(":", 1)[1]),
                        "rank": row["ranks"][t],
                        "mass": row["source"][t],
                    }
                    for t in items
                ],
                "external_mass": sum(row["external"].values())
                if row["external"]
                else None,
            }
        )
    return {
        "record_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "manifest_sha256": hashlib.sha256(
            (root / "manifest.json").read_bytes()
        ).hexdigest(),
        "units": len(rows),
        "inventory_digest": record["inventory"]["digest"],
        "source_universe": len(universe),
        "normalized_units": sum(bool(r["source"]) for r in rows.values()),
        "cases": cases,
    }, rows


result = {
    "kind": "fresh-producer-development-diagnostic/v1",
    "limitations": [
        "No independent accuracy judgments",
        "Fresh lexical channels, not a replay of historical ONNX/BM25 baseline",
        "Different representation arms may nominate different targets",
        "Ranks use 1e-12 tie tolerance; null target rank means absent from nominations",
        "Conditional producer does not assess intrusion",
    ],
    "arms": {},
    "matched_policy_comparison": {},
}
for arm in ["a", "v", "av"]:
    old, oldrows = summary(arm + "-historical")
    new, newrows = summary(arm + "-mapping")
    result["arms"][arm] = {"historical": old, "corrected": new}
    if (
        oldrows.keys() != newrows.keys()
        or old["inventory_digest"] != new["inventory_digest"]
    ):
        raise ValueError("policy arms have different units")
    result["matched_policy_comparison"][arm] = {
        "units": len(oldrows),
        "same_source_nominations": sum(
            o["source"].keys() == newrows[k]["source"].keys()
            for k, o in oldrows.items()
        ),
        "same_top_source_set": sum(
            set(o["top"]) == set(newrows[k]["top"]) for k, o in oldrows.items()
        ),
        "same_all_source_ranks": sum(
            o["ranks"] == newrows[k]["ranks"] for k, o in oldrows.items()
        ),
    }
print(json.dumps(result, indent=2, sort_keys=True))
