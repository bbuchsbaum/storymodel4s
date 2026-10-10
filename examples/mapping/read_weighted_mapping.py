#!/usr/bin/env python3
"""Independent weighted exchange joins; imported claims are not execution authority."""
import argparse
import csv
import hashlib
import io
import math
from pathlib import Path
import re

from read_mapping import digest, envelope, parse, read, read_publication, require

COLUMNS = ["lambda", "profile_id", "record_profile_id", "selection_id", "execution_id",
           "shared_evidence_id", "measure_kind", "record_digest"]


def fraction(text):
    require(isinstance(text, str) and re.fullmatch(r"(0|[1-9][0-9]*)/[1-9][0-9]*", text), "weight syntax")
    n, d = map(int, text.split("/"))
    require(0 <= n <= d <= 9223372036854775807 and math.gcd(n, d) == 1, "weight range or reduction")
    return n, d


def selected(files):
    meta = envelope(files, "weighted-mapping-exchange/v1")
    require(set(meta) == set(COLUMNS), "weighted member metadata")
    n, d = fraction(meta["lambda"])
    for key in COLUMNS:
        if key not in {"lambda", "measure_kind"}:
            digest(meta[key])
    table = list(csv.reader(io.StringIO(files["members.tsv"]), delimiter="\t", strict=True))
    require(table == [COLUMNS, [meta[c] for c in COLUMNS]], "weighted member table")
    expected = io.StringIO(newline="")
    csv.writer(expected, delimiter="\t", quoting=csv.QUOTE_ALL, lineterminator="\n").writerows(table)
    require(expected.getvalue() == files["members.tsv"], "weighted table syntax")
    payloads = {k[len("record--"):]: v for k, v in files.items() if k.startswith("record--")}
    record, tables = read(payloads)
    require(set(files) == {"manifest.json", "members.tsv"} | {"record--" + k for k in payloads}, "weighted payload set")
    require(meta["record_digest"] == record["record_digest"], "weighted record digest")
    require(meta["measure_kind"] == ("NormalizedScoreMass" if n == 0 else "ModelPosterior"), "weighted measure kind")
    require(record["policies"]["decision_policy_id"] == {"status": "declared", "id": "mapping-run:" + meta["record_profile_id"]}, "weighted record profile")
    if 0 < n < d:
        require(meta["record_profile_id"] == meta["profile_id"], "interior profile")
    derived = [s for s in tables["stages"] if s["provenance"]["status"] == "derived"]
    require(len(derived) == 2 and {s["stage"] for s in derived} == {"Inference", "Decision"}, "weighted stages")
    for stage in derived:
        receipt = stage["provenance"]["receipt"]
        member = "Reference" if n == 0 else "Reconstruction" if n == d else f"Weighted/{meta['profile_id']}/{meta['lambda']}"
        require(receipt["policy"] == f"mapping-run/v1/{member}/{stage['stage']}", "weighted stage policy")
        require(receipt["config"] == meta["execution_id" if n in (0, d) else "selection_id"], "weighted stage identity")
        require(receipt["inputs"] == [meta["shared_evidence_id"], record["inventory"]["digest"], record["source"]["digest"]], "weighted evidence join")
    if 0 < n < d:
        for row in record["outcomes"]:
            decision = row["decision"]
            if decision["status"] == "present":
                require(decision["value"]["request"] == {"status": "raw-argmax"}, "interior constrained decision")
    return meta, record, tables, payloads


def read_sweep(files):
    meta = envelope(files, "weighted-mapping-batch/v1")
    require(set(meta) == {"baseManifest", "weights", "members"}, "weighted batch metadata")
    weights = meta["weights"]
    require(weights and len(weights) == len(set(weights)), "duplicate or empty sweep")
    for weight in weights:
        fraction(weight)
    base_files = {k[len("base--"):]: v for k, v in files.items() if k.startswith("base--")}
    require(hashlib.sha256(base_files["manifest.json"].encode()).hexdigest() == meta["baseManifest"], "weighted base digest")
    base = read_publication(base_files)
    require(base["schema"] == "mapping-exchange-batch/v1", "weighted base schema")
    complete = [o for o in base["outcomes"] if o["execution"] == "complete"]
    expected = [(o["run"], weight) for o in complete for weight in weights]
    require([(m["run"], m["lambda"]) for m in meta["members"]] == expected, "weighted attempt denominator")
    outcomes = {o["run"]: o for o in complete}
    names = {"manifest.json"} | {"base--" + k for k in base_files}
    loaded = []
    for entry in meta["members"]:
        require(set(entry) == {"run", "lambda", "prefix", "manifest"}, "weighted member descriptor")
        n, d = fraction(entry["lambda"])
        prefix = entry["run"] + f"--{n}-{d}--"
        require(entry["prefix"] == prefix, "weighted prefix")
        member_files = {k[len(prefix):]: v for k, v in files.items() if k.startswith(prefix)}
        require(hashlib.sha256(member_files["manifest.json"].encode()).hexdigest() == entry["manifest"], "weighted member digest")
        m, record, tables, payloads = selected(member_files)
        require(m["lambda"] == entry["lambda"], "weighted lambda join")
        run = outcomes[entry["run"]]["mapping"]
        require(m["execution_id"] == run["execution"] and m["shared_evidence_id"] == run["sharedEvidence"], "weighted run join")
        parent_prefix = entry["run"] + "--reference--"
        parent = parse(base_files[parent_prefix + "mapping.json"])
        require(record["inventory"] == parent["inventory"] and record["source"] == parent["source"], "weighted source or inventory")
        if n in (0, d):
            endpoint = "reference" if n == 0 else "reconstruction"
            endpoint_prefix = entry["run"] + "--" + endpoint + "--"
            original = {k[len(endpoint_prefix):]: v for k, v in base_files.items() if k.startswith(endpoint_prefix)}
            require(payloads == original, "endpoint bytes differ")
        names |= {prefix + k for k in member_files}
        loaded.append((m, record, tables))
    require(set(files) == names, "unaccounted weighted payload")
    return base, loaded


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    files = {}
    for p in args.directory.iterdir():
        require(p.is_file() and not p.is_symlink(), "unsupported directory entry")
        with p.open(encoding="utf-8", newline="") as stream:
            files[p.name] = stream.read()
    base, members = read_sweep(files)
    print(f"pass: {len(base['outcomes'])} attempts, {len(members)} weighted members; wire checks, not execution admission")
