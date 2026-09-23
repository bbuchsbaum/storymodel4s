"""Rehashed malformed packages must fail relational/projection checks, not just hashes."""
import copy
import csv
import io
import json
from pathlib import Path
import sys

from read_mapping import TABLES, cell_type, expected_rows, read
import hashlib


def render(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def repack(files, record):
    result = dict(files)
    result["mapping.json"] = render(record)
    manifest = json.loads(result["manifest.json"])
    for entry in manifest["files"]:
        name = entry["name"]
        if name.endswith(".tsv"):
            path, fields = TABLES[name[:-4]]
            rows = expected_rows(record, path)
            content = io.StringIO(newline="")
            writer = csv.writer(content, delimiter="\t", quoting=csv.QUOTE_ALL, lineterminator="\n")
            writer.writerow(["mapping_digest"] + fields)
            for row in rows:
                writer.writerow([record["record_digest"]] + [render(row[f]) if cell_type(f) == "canonical-json" else str(row[f]) for f in fields])
            result[name] = content.getvalue()
            entry["table"]["rows"] = str(len(rows))
        entry["bytes"] = str(len(result[name].encode("utf-8")))
        entry["sha256"] = hashlib.sha256(result[name].encode("utf-8")).hexdigest()
    result["manifest.json"] = render(manifest)
    return result


def main():
    values = {}
    for line in Path(sys.argv[1]).read_text().splitlines():
        if line.startswith("MAPPING_EXCHANGE_FIXTURE\t"):
            _, name, content = line.split("\t", 2)
            values[name] = json.loads(content)
    files = values["raw"]
    original = json.loads(files["mapping.json"])
    read(files)
    killed = []

    def rejects(name, changed, message):
        try:
            read(changed)
        except ValueError as error:
            if message not in str(error):
                raise AssertionError(f"{name}: wrong refusal: {error}") from error
            killed.append(name)
        else:
            raise AssertionError(f"{name}: malformed package accepted")

    changed = dict(files)
    del changed["decisions.tsv"]
    rejects("missing-outcomes-file", changed, "partial or extra publication")

    record = copy.deepcopy(original)
    record["outcomes"] = [r for r in record["outcomes"] if r["unit"] != "p6"]
    rejects("missing-failed-unit", repack(files, record), "missing outcome")

    record = copy.deepcopy(original)
    record["outcomes"][0]["links"][0]["inference_stage_id"] = "0" * 64
    rejects("dangling-link-stage", repack(files, record), "dangling link stage")

    record = copy.deepcopy(original)
    record["inventory"]["words"][0]["membership"]["unit"] = "foreign-unit"
    rejects("foreign-word-membership", repack(files, record), "word membership join")

    record = copy.deepcopy(original)
    record["outcomes"][0]["mapping_links"].append(copy.deepcopy(record["outcomes"][0]["mapping_links"][0]))
    rejects("duplicate-alternative-key", repack(files, record), "duplicate semantic alternative")

    record = copy.deepcopy(original)
    target = next(t for t in record["source"]["targets"] if t["target_id"] == "sit:e8")
    target["support_status"]["source_support"]["anchors"][0]["tick"] = 30
    rejects("numeric-authoritative-tick", repack(files, record), "inexact tick/rational")

    changed = dict(files)
    manifest = json.loads(changed["manifest.json"])
    changed["alternatives.tsv"] = changed["alternatives.tsv"].replace("0x3feccccccccccccd", "0x3fe0000000000000")
    for entry in manifest["files"]:
        if entry["name"] == "alternatives.tsv":
            entry["sha256"] = hashlib.sha256(changed[entry["name"]].encode()).hexdigest()
    changed["manifest.json"] = render(manifest)
    rejects("rehashed-changed-score", changed, "table differs from record")

    print(json.dumps({"status": "pass", "rejected": killed}))


if __name__ == "__main__":
    main()
