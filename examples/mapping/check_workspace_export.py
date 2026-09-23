#!/usr/bin/env python3
"""Check synthetic WorkspaceMappingExportSuite outputs with the independent reader."""
import argparse
import json
from pathlib import Path

from read_mapping import number, parse, read, require


def check(log):
    fixtures = {}
    for line in log.read_text().splitlines():
        if line.startswith("MAPPING_EXCHANGE_FIXTURE\t"):
            _, name, content = line.split("\t", 2)
            require(name not in fixtures, "duplicate fixture")
            fixtures[name] = read(parse(content))
    require(set(fixtures) == {f"workspace-policy-{i}" for i in range(3)}, "missing policy fixture")
    require(len({r["record_digest"] for r, _ in fixtures.values()}) == 3, "policy substitution")
    first, second, external = "sit:wog:sit:hunt-seals", "sit:wog:sit:hear-war-cries", "ext:Intrusion"
    for i in range(2):
        _, tables = fixtures[f"workspace-policy-{i}"]
        decisions = {r["unit"]: r for r in tables["decisions"]}
        require(set(decisions) == {f"m1:u{j}" for j in range(4)}, "incomplete outcomes")
        require(decisions["m1:u3"]["processing_status"]["status"] == "failed", "failed outcome lost")
        require(not any(r["unit"] == "m1:u3" for r in tables["alternatives"]), "failure gained values")
        expected = (
            {"RawScore": {first: 0.9, second: 0.4, external: 0.2},
             "NormalizedScoreMass": {first: 0.25, second: 0.25, external: 0.5}}
            if i == 0 else
            {"RawScore": {first: 0.1, second: 0.8, external: 0.1},
             "NormalizedScoreMass": {first: 0.1, second: 0.6, external: 0.3}}
        )
        for kind, values in expected.items():
            actual = {r["destination"]: number(r["raw_value"]) for r in tables["alternatives"]
                      if r["unit"] == "m1:u0" and r["measure_kind"] == kind}
            require(actual == values, "selected policy changed or was renormalized")
    _, historical = fixtures["workspace-policy-2"]
    require({r["measure_kind"] for r in historical["alternatives"]} == {"RawScore", "ModelPosterior"},
            "historical authority relabeled")
    require(len(historical["decisions"]) == 4, "historical outcomes lost")
    return {"status": "pass", "fixtures": sorted(fixtures),
            "digests": {name: r["record_digest"] for name, (r, _) in fixtures.items()},
            "qualification": "package, projection and authored policy checks; not contextual derivation admission"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    print(json.dumps(check(parser.parse_args().log), sort_keys=True))
