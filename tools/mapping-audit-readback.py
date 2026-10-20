#!/usr/bin/env python3
"""Independent descriptive-summary readback over synthetic WeightedMappingSuite log fixtures.

This checks JSON/TSV consistency; it does not grant live producer or scientific authority.
"""
import copy
import csv
import io
import json
import struct
import sys
from fractions import Fraction
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "examples" / "mapping"))
from read_weighted_mapping import read_sweep


def rows(text):
    return list(csv.DictReader(io.StringIO(text), delimiter="\t"))


def check(files):
    outer = json.loads(files["manifest.json"])
    assert outer["schemaVersion"] == "weighted-mapping-batch/v2"
    base = json.loads(files["base--manifest.json"])["metadata"]
    summary = base["summary"]
    coverage = summary["coverage"]
    observations = coverage["observations"]
    assert summary["attempts"] == len(base["attempts"]) == len(base["outcomes"]) == len(observations)
    assert summary["completed"] == coverage["runProduced"] == sum("run" in o for o in base["outcomes"])
    assert coverage["prepared"] + coverage["unknownUnitObservations"] == summary["attempts"]
    assert coverage["unknownUnitObservations"] == sum("declaredUnits" not in o for o in observations)
    all_units = [u for o in observations for u in o["units"]]
    for original, observation in zip(base["outcomes"], observations):
        assert (original["participant"], original["session"]) == (observation["participant"], observation["session"])
        if "declaredUnits" in observation:
            assert observation["declaredUnits"] == len(observation["units"]) == len(original["units"])
        for member in ("reference", "reconstruction"):
            if "run" not in original:
                continue
            prefix = f'base--{original["run"]}--{member}--'
            inventory = rows(files[prefix + "units.tsv"])
            measures = {r["unit"]: json.loads(r["measures"]) for r in rows(files[prefix + "measure-metadata.tsv"])}
            assert {r["id"] for r in inventory} == {u["unit"] for u in observation["units"]}
            measure = "normalized" if member == "reference" else "posterior"
            for unit in observation["units"]:
                assert unit[member]["measureAvailable"] == (measures[unit["unit"]][measure]["status"] == "present")
    for member in ("reference", "reconstruction"):
        totals = coverage[member]
        measured = sum(u[member]["measureAvailable"] for u in all_units)
        assert totals["declaredUnits"] == len(all_units)
        assert totals["measuredUnits"] == measured
        assert totals["unavailableUnits"] == len(all_units) - measured
        for field, flag in (("overflowUnits", "overflow"), ("uniformSemanticUnits", "uniformSemantic"), ("uniformCostUnits", "uniformCosts")):
            assert totals[field] == sum(u[member].get(flag, False) for u in all_units)
        if all_units:
            assert totals["measuredFractionOfKnownUnits"] == measured / len(all_units)
        else:
            assert "measuredFractionOfKnownUnits" not in totals
    for member in outer["metadata"]["members"]:
        weight = Fraction(member["lambda"])
        expected = "Reference" if weight == 0 else "Reconstruction" if weight == 1 else "Weighted"
        assert member["dispatchedMember"] == expected
        metadata = json.loads(files[member["prefix"] + "manifest.json"])["metadata"]
        tsv = rows(files[member["prefix"] + "members.tsv"])
        assert len(tsv) == 1
        assert member["lambda"] == metadata["lambda"] == tsv[0]["lambda"]
        assert member["kind"] == metadata["measure_kind"] == tsv[0]["measure_kind"]
        if weight == 0:
            for row in rows(files[member["prefix"] + "record--measure-metadata.tsv"]):
                value = json.loads(row["measures"])["normalized"]
                if value["status"] == "present":
                    temperature = struct.unpack(">d", bytes.fromhex(value["value"]["temperature"][2:]))[0]
                    assert temperature == member["temperature"] == member["model"]["reference"]["temperature"]
        else:
            assert member["temperature"] == member["model"]["reconstruction"]["temperature"]
        if weight == 1:
            assert member["decisionPolicy"] == member["model"]["reconstruction"]["decisionPolicy"]
        else:
            assert member["decisionPolicy"] == "RawArgmax"
    # The established reader also verifies byte integrity and all original scientific joins.
    read_sweep(files)


def main(log):
    fixtures = []
    with open(log) as stream:
        for line in stream:
            if line.startswith("WEIGHTED_MAPPING_FIXTURE\t"):
                _, label, payload = line.rstrip("\n").split("\t", 2)
                fixtures.append((label, json.loads(payload)))
    assert fixtures, "no synthetic exported fixtures were run"
    for label, files in fixtures:
        check(files)
        # Deliberately drop a failed/unavailable observation: reconciliation must refuse.
        mutant = copy.deepcopy(files)
        base = json.loads(mutant["base--manifest.json"])
        base["metadata"]["summary"]["coverage"]["observations"].pop()
        mutant["base--manifest.json"] = json.dumps(base)
        try:
            check(mutant)
        except AssertionError:
            pass
        else:
            raise AssertionError("dropped observation survived denominator reconciliation")
        mutant = copy.deepcopy(files)
        manifest = json.loads(mutant["manifest.json"])
        manifest["metadata"]["members"][0]["dispatchedMember"] = "Reconstruction"
        mutant["manifest.json"] = json.dumps(manifest)
        try:
            check(mutant)
        except AssertionError:
            pass
        else:
            raise AssertionError("contradictory endpoint metadata survived")
        print(f"PASS {label}: JSON/TSV readback; dropped-row and contradictory-policy controls refused")


if __name__ == "__main__":
    main(sys.argv[1])
