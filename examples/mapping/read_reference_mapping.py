#!/usr/bin/env python3
"""Read a reference-only publication. Integrity joins do not confer Scala execution authority."""
import argparse
import json
import hashlib
from pathlib import Path
from read_mapping import read, envelope, parse, require, digest, number


def read_reference(files):
    metadata = envelope(files, "mapping-exchange-batch/reference/v1")
    summary = metadata["summary"]
    require(summary["schema"] == "mapping-summary/reference/v1", "reference summary version")
    outcomes = metadata["outcomes"]
    coverage = summary["coverage"]
    observations = coverage["observations"]
    require(len(outcomes) == summary["attempts"] == len(observations), "attempt denominator")
    require("reconstruction" not in coverage, "fabricated member")
    names, records, unknown, completed, known, measured = {"manifest.json"}, [], 0, 0, 0, 0
    keys = set()
    models = {m["run"]: m for m in summary["models"]}
    for outcome, observation in zip(outcomes, observations):
        key = (outcome["participant"], outcome["session"])
        require(key not in keys, "duplicate observation")
        keys.add(key)
        for field in ("participant", "session", "observation", "failure"):
            require(outcome.get(field) == observation.get(field), "coverage observation join")
        inventory = None
        if outcome.get("observation") is not None:
            name = digest(outcome["observation"]) + "-recall.json"
            names.add(name)
            prepared = parse(files[name])
            require((prepared["participant"], prepared["session"]) == key and prepared["sourceEdition"] == metadata["sourceEdition"], "prepared input join")
            require(prepared["observation"] == outcome["observation"], "prepared observation")
            require([u["id"] for u in prepared["graph"]["units"]] == [u["unit"] for u in observation["units"]], "prepared unit denominator")
            require(observation["declaredUnits"] == len(prepared["graph"]["units"]), "prepared unit count")
            if prepared["schemaVersion"] == "prepared-recall/v2":
                disclosure = prepared["disclosure"]
                require(disclosure["scope"] == "local-workspace" and disclosure["schemaVersion"] == "recall-disclosure-receipt/v1", "disclosure description")
                require(disclosure["textDigest"] == hashlib.sha256(prepared["graph"]["transcript"]["rawText"].encode()).hexdigest(), "disclosure text")
            require(prepared["schemaVersion"] in {"prepared-recall/v1", "prepared-recall/v2"}, "prepared recall schema")
        else:
            unknown += 1
            require(observation.get("declaredUnits") is None and not observation["units"], "unknown unit denominator")
        if outcome.get("run") is None:
            require(outcome.get("failure") is not None, "missing failure")
            known += observation.get("declaredUnits") or 0
            require(all(not row["reference"]["measureAvailable"] for row in observation["units"]), "failed available row")
            continue
        completed += 1
        prefix = digest(outcome["run"]) + "--"
        member = {name[len(prefix):]: content for name, content in files.items() if name.startswith(prefix)}
        record, tables = read(member, reference_only=True)
        names.update(prefix + name for name in member)
        require(record["record_digest"] == outcome["record"], "foreign record")
        inventory = record["inventory"]
        require(record["source"]["digest"] == metadata["sourceRepresentation"], "foreign source")
        require(prepared["inventory"] == inventory["digest"] and prepared["observation"] == outcome["observation"], "foreign inventory")
        execution = digest(outcome["execution"])
        derived = [row for row in tables["stages"] if row["provenance"]["status"] == "reference-derived"]
        require({row["stage"] for row in derived} == {"Inference", "Decision"}, "reference stage authority")
        require(all(row["provenance"]["receipt"]["config"] == execution for row in derived), "foreign execution")
        model = models[outcome["run"]]["reference"]
        universe = record["policies"]["target_universe_id"]
        require(model["kind"] == "NormalizedScoreMass" and model["universe"] == universe["id"], "reference model")
        require(universe["grain"] == {"status": "single-level", "level": model["level"]}, "selected level")
        require(model["targets"] == len(universe["targets"]), "target denominator")
        rows = record["outcomes"]
        require([row["unit"] for row in rows] == [row["unit"] for row in observation["units"]], "dropped unit")
        require(observation["declaredUnits"] == len(rows), "declared units")
        known += len(rows)
        for row, observed in zip(rows, observation["units"]):
            measures = row["measures"]
            require(not measures["raw"] and measures["posterior"]["status"] == "absent", "reconstruction measure")
            available = measures["normalized"]["status"] == "present"
            require(observed["reference"]["measureAvailable"] == available, "availability")
            measured += available
            if available:
                require(number(measures["normalized"]["value"]["temperature"]) == model["temperature"], "temperature")
        records.append(record)
    require(names == set(files), "unaccounted payload")
    require(completed == summary["completed"] == coverage["runProduced"], "completed denominator")
    require(unknown == coverage["unknownUnitObservations"], "unknown denominator")
    totals = coverage["reference"]
    require(totals["declaredUnits"] == known and totals["measuredUnits"] == measured and totals["unavailableUnits"] == known - measured, "coverage totals")
    require(summary["failed"] == len(outcomes) - completed, "failed denominator")
    require(coverage["prepared"] == len(outcomes) - unknown, "prepared denominator")
    fraction = totals.get("measuredFractionOfKnownUnits")
    require(fraction == (measured / known if known else None), "coverage fraction")
    all_units = [row["reference"] for observation in observations for row in observation["units"]]
    for field, total in (("overflow", "overflowUnits"), ("uniformSemantic", "uniformSemanticUnits"), ("uniformCosts", "uniformCostUnits")):
        require(totals[total] == sum(bool(row.get(field)) for row in all_units), "diagnostic totals")
    require(len(models) == completed, "model count")
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    records = read_reference({p.name: p.read_text() for p in args.directory.iterdir() if p.is_file()})
    print(json.dumps({"referenceRecords": len(records), "units": sum(len(r["outcomes"]) for r in records)}))


if __name__ == "__main__":
    main()
