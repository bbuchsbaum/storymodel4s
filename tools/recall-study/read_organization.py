#!/usr/bin/env python3
"""Independent structural/numerical reader; this never restores producer execution authority."""
import argparse
import csv
import hashlib
import io
import itertools
import json
import math
from pathlib import Path
import re
import struct

CATEGORIES = ("Backward", "Forward", "SameTarget", "External", "Incomparable", "Unresolved", "Failed")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load(text):
    return json.loads(text, object_pairs_hook=unique_object)


def number(value):
    if isinstance(value, str) and re.fullmatch(r"0x[0-9a-f]{16}", value):
        result = struct.unpack(">d", bytes.fromhex(value[2:]))[0]
    elif isinstance(value, (float, int)) and not isinstance(value, bool):
        result = float(value)
    else:
        raise ValueError("expected a finite numerical value")
    if not math.isfinite(result):
        raise ValueError("nonfinite value")
    return result


def verified_files(root):
    root = Path(root)
    if root.is_symlink() or not (root / "manifest.json").is_file():
        raise ValueError("missing completion manifest")
    manifest = load((root / "manifest.json").read_text())
    files = {}
    for entry in manifest["files"]:
        name = entry["path"]
        if not re.fullmatch(r"[a-zA-Z0-9._-]+", name) or name in (".", "..", "manifest.json") or name in files:
            raise ValueError("unsafe or duplicate file")
        path = root / name
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"missing file: {name}")
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise ValueError(f"digest mismatch: {name}")
        files[name] = data.decode("utf-8", errors="strict")
    # Recovery inputs are directories; an exchange itself must contain exactly its named files.
    return manifest, files


def classify(a, b, positions):
    if a == "ext:Unranked" or b == "ext:Unranked":
        return "Unresolved"
    if a.startswith("ext:") or b.startswith("ext:"):
        return "External"
    if a == b:
        return "SameTarget"
    if a not in positions or b not in positions or positions[a] == positions[b]:
        return "Incomparable"
    return "Forward" if positions[b] > positions[a] else "Backward"


def check_result(result, tolerance=1e-10, enumeration_limit=250000):
    if result["schemaVersion"] != "organization-readout/v1" or result["dependence"] != "DeclaredIndependentRows":
        raise ValueError("unsupported conditional input")
    if result["orderKind"] == "MediaPartDiscourse":
        raise ValueError("media part/composition order needs the Scala contextual reader")
    positions = {row["target"]: number(row["position"]) for row in result["order"]}
    if len(positions) != len(result["order"]):
        raise ValueError("duplicate order target")
    rows = result["rows"]
    if len({r["unit"] for r in rows}) != len(rows) or result["units"] != len(rows):
        raise ValueError("unit inventory mismatch")
    masses = []
    for row in rows:
        mass = {v["destination"]: number(v["mass"]) for v in row["mass"]}
        if len(mass) != len(row["mass"]) or any(v < 0 for v in mass.values()):
            raise ValueError("invalid mass")
        if row["status"] == "Available":
            if not mass or abs(sum(mass.values()) - 1) > 1e-9:
                raise ValueError("invalid row denominator")
            admitted = row["admissible"]
            if not admitted or len(set(admitted)) != len(admitted) or not set(admitted) <= mass.keys():
                raise ValueError("invalid admissible set")
        elif row["status"] not in ("Failed", "Unresolved") or mass or row["admissible"]:
            raise ValueError("invalid unavailable row")
        masses.append(mass)
    expected = []
    for i, (left, right) in enumerate(zip(rows, rows[1:])):
        counts = dict.fromkeys(CATEGORIES, 0.0)
        if "Failed" in (left["status"], right["status"]):
            counts["Failed"] = 1.0
        elif left["status"] != "Available" or right["status"] != "Available":
            counts["Unresolved"] = 1.0
        else:
            for a, p in masses[i].items():
                for b, q in masses[i + 1].items():
                    counts[classify(a, b, positions)] += p * q
        expected.append(counts)
    transitions = result["transitions"]
    if len(transitions) != len(expected) or result["adjacencies"] != len(expected):
        raise ValueError("adjacency inventory mismatch")
    for i, transition in enumerate(transitions):
        if transition["left"] != rows[i]["unit"] or transition["right"] != rows[i + 1]["unit"]:
            raise ValueError("gap bridging or reordered adjacency")
        for category in CATEGORIES:
            if abs(number(transition["counts"][category]) - expected[i][category]) > tolerance:
                raise ValueError(f"incorrect {category} count")
    totals = {c: sum(row[c] for row in expected) for c in CATEGORIES}
    for category in CATEGORIES:
        if abs(number(result["counts"][category]) - totals[category]) > tolerance:
            raise ValueError("incorrect total count")
    comparable = sum(totals[c] for c in CATEGORIES[:3])
    if abs(number(result["comparable"]) - comparable) > tolerance:
        raise ValueError("wrong comparable denominator")
    if comparable > 0:
        if abs(number(result["backwardRate"]) - totals["Backward"] / comparable) > tolerance:
            raise ValueError("wrong ratio of expected counts")
    elif "backwardRate" in result:
        raise ValueError("zero denominator must be unavailable")
    measured_units = sum(r["status"] == "Available" for r in rows)
    measured_transitions = sum(r["Failed"] == 0 and r["Unresolved"] == 0 for r in expected)
    if (result["measuredUnits"], result["measuredTransitions"]) != (measured_units, measured_transitions):
        raise ValueError("wrong coverage accounting")
    bounds_check = "unavailable"
    if measured_units != len(rows):
        if "bounds" in result:
            raise ValueError("missing universe cannot establish product bounds")
    else:
        bounds = result["bounds"]
        size = math.prod(len(r["admissible"]) for r in rows)
        bounds_check = "not-enumerated-large-product"
        if size <= enumeration_limit:
            low = dict.fromkeys(CATEGORIES, math.inf)
            high = dict.fromkeys(CATEGORIES, -math.inf)
            for path in itertools.product(*(r["admissible"] for r in rows)):
                counts = dict.fromkeys(CATEGORIES, 0)
                for a, b in zip(path, path[1:]):
                    counts[classify(a, b, positions)] += 1
                for category in CATEGORIES:
                    low[category] = min(low[category], counts[category])
                    high[category] = max(high[category], counts[category])
            for category in CATEGORIES:
                if bounds[category] != {"minimum": low[category], "maximum": high[category]}:
                    raise ValueError("incoherent product bound")
            bounds_check = "exhaustive-enumeration"
    return {"counts": totals, "units": len(rows), "adjacencies": len(expected), "bounds_check": bounds_check}


def read_exchange(root):
    manifest, files = verified_files(root)
    if manifest["schemaVersion"] not in ("organization-exchange/v1", "organization-cohort/v1"):
        raise ValueError("unsupported exchange manifest")
    if {p.name for p in Path(root).iterdir()} != {"manifest.json", *files}:
        raise ValueError("unexpected output files")
    metadata = manifest["metadata"]
    if manifest["schemaVersion"] == "organization-exchange/v1":
        prefixes = {"": metadata}
        outcomes = []
    else:
        outcomes = metadata["outcomes"]
        if (not isinstance(outcomes, list) or type(metadata["attempts"]) is not int
                or metadata["attempts"] != len(outcomes)):
            raise ValueError("cohort attempt accounting mismatch")
        prefixes = {}
        observations = set()
        for outcome in outcomes:
            identity = (outcome["participant"], outcome["session"])
            if identity in observations:
                raise ValueError("duplicate cohort observation")
            observations.add(identity)
            if outcome["status"] == "complete":
                run = outcome["run"]
                if not re.fullmatch(r"[0-9a-f]{64}", run) or run + "--" in prefixes:
                    raise ValueError("invalid or duplicate completed run")
                if (outcome["metadata"]["run"] != run
                        or outcome["metadata"]["organization"] != outcome["organization"]):
                    raise ValueError("cohort result identity mismatch")
                prefixes[run + "--"] = outcome["metadata"]
            elif outcome["status"] != "failed" or not isinstance(outcome.get("reason"), str) or not outcome["reason"]:
                raise ValueError("unaccounted cohort outcome")
    expected_files = set()
    component_names = {"manifest.json", "mapping.json", "words.tsv", "units.tsv", "targets.tsv",
                       "target-support.tsv", "alternatives.tsv", "measure-metadata.tsv", "decisions.tsv", "links.tsv", "stages.tsv"}
    for prefix in prefixes:
        required = {prefix + name for name in ("organization.json", "compatibility.json", "transitions.tsv", "mapping--manifest.json")}
        if not required <= files.keys():
            raise ValueError("missing complete organization payload")
        pair = load(files[prefix + "mapping--manifest.json"])
        members = [m["member"] for m in pair["metadata"]["members"]]
        if (len(set(members)) != len(members) or not {"reference", "reconstruction"} <= set(members)
                or not set(members) <= {"reference", "reconstruction", "weighted"}):
            raise ValueError("invalid mapping pair members")
        pair_names = {f"{member}--{name}" for member in members for name in component_names}
        entries = pair["files"]
        if len(entries) != len(pair_names) or {e["path"] for e in entries} != pair_names:
            raise ValueError("incomplete mapping pair manifest")
        for entry in entries:
            name = prefix + "mapping--" + entry["path"]
            if name not in files or hashlib.sha256(files[name].encode()).hexdigest() != entry["sha256"]:
                raise ValueError("incomplete mapping pair payload")
            required.add(name)
        expected_files.update(required)
    if set(files) != expected_files:
        raise ValueError("payloads differ from accounted outcomes")
    results = []
    for prefix, expected_metadata in prefixes.items():
        result = load(files[prefix + "organization.json"])
        compatibility = load(files[prefix + "compatibility.json"])
        if compatibility != expected_metadata:
            raise ValueError("result metadata differs from manifest outcome")
        if compatibility["member"] != "Reference" or compatibility["measure"] != "NormalizedScoreMass" or compatibility["calibration"] != "not-established":
            raise ValueError("unsupported measurement interpretation")
        if compatibility["verdict"] != "CompatibleWithDeclaredPolicy" or compatibility["request"]["axis"] != result["axis"]:
            raise ValueError("inconsistent declared compatibility")
        pair = load(files[prefix + "mapping--manifest.json"])
        metadata = pair["metadata"]
        for field in ("execution", "inventory", "source"):
            if compatibility[field] != metadata[field]:
                raise ValueError("foreign mapping join")
        reference = next(m for m in metadata["members"] if m["member"] == "reference")
        if reference["record"] != compatibility["reference"]:
            raise ValueError("foreign reference member")
        record = load(files[prefix + "mapping--reference--mapping.json"])
        if record["record_digest"] != reference["record"]:
            raise ValueError("wrong mapping record digest")
        units = record["inventory"]["units"]
        if [r["unit"] for r in result["rows"]] != [u["id"] for u in units]:
            raise ValueError("organization rows differ from original mapping inventory")
        mapping_outcomes = {o["unit"]: o for o in record["outcomes"]}
        for row in result["rows"]:
            original = mapping_outcomes[row["unit"]]
            mass = {v["destination"]: number(v["raw_value"]) for v in original["mapping_links"]
                    if v["measure_kind"] == "NormalizedScoreMass"}
            retained = {v["destination"]: number(v["mass"]) for v in row["mass"]}
            if mass != retained:
                raise ValueError("organization mass differs from reference mapping")
        summary = check_result(result)
        table = list(csv.reader(io.StringIO(files[prefix + "transitions.tsv"]), delimiter="\t", strict=True))
        if table[0] != ["left_unit", "right_unit", *CATEGORIES] or len(table) != len(result["transitions"]) + 1:
            raise ValueError("wrong transition table")
        for table_row, transition in zip(table[1:], result["transitions"]):
            if table_row[:2] != [transition["left"], transition["right"]] or len(table_row) != 9:
                raise ValueError("transition table identity mismatch")
            for c, cell in zip(CATEGORIES, table_row[2:]):
                if number(cell) != number(transition["counts"][c]):
                    raise ValueError("transition table data mismatch")
        results.append(summary)
    return {"validation": "structural and conditional numerical readback; no restored execution authority", "results": results, "outcomes": outcomes}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("exchange", type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(read_exchange(args.exchange), sort_keys=True))
    except (ValueError, KeyError, StopIteration, OSError) as error:
        parser.exit(2, f"organization reader refused: {error}\n")


if __name__ == "__main__":
    main()
