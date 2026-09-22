#!/usr/bin/env python3
"""Falsify independent workspace export readback after re-signing all changed file hashes."""
import csv
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile

checker_path = Path(__file__).with_name("check-workspace-fixtures.py")
spec = importlib.util.spec_from_file_location("workspace_readback", checker_path)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
source = Path(sys.argv[1])
revision = sys.argv[2]
print(json.dumps({"before": checker.verify(source, revision)}), flush=True)
prefix = "bell-authored-a-u0"


def encode(path, value):
    path.write_text(json.dumps(value, sort_keys=True, separators=(",", ":")))


for mutation, expected in (
    ("duplicate-csv", "unique CSV measure coordinates"),
    ("wrong-policy", "selected policy"),
    ("renormalize-selection", "unchanged original outcome"),
    ("hull-evidence", "literal discontiguous recall answer"),
):
    with tempfile.TemporaryDirectory(prefix="workspace-readback-") as temp:
        directory = Path(temp) / "artifacts"
        shutil.copytree(source, directory)
        data_path = directory / f"{prefix}.json"
        table_path = directory / f"{prefix}.csv"
        receipt_path = directory / f"{prefix}.receipt.json"
        data = json.loads(data_path.read_bytes())
        receipt = json.loads(receipt_path.read_bytes())
        if mutation == "duplicate-csv":
            rows = list(csv.reader(io.StringIO(table_path.read_text())))
            rows.append(next(r for r in rows[1:] if "RawScore" in r))
            output = io.StringIO()
            csv.writer(output).writerows(rows)
            table_path.write_text(output.getvalue())
        elif mutation == "wrong-policy":
            data["policy"] = receipt["policy"] = "authored-b"
            encode(data_path, data)
        elif mutation == "renormalize-selection":
            for value in data["outcomes"][0]["mapping_links"]:
                if value["measure_kind"] == "NormalizedScoreMass" and value["destination"].startswith("sit:"):
                    value["raw_value"] = "0x3fe0000000000000"  # invented source-only 0.5
            encode(data_path, data)
        else:
            evidence = data["recall_evidence"][0]["pieces"]
            evidence[0]["span"]["span"]["end"] = evidence[1]["span"]["span"]["end"]
            evidence[0]["text"] = "A bell rang. This note is outside the selected evidence. It rang again."
            data["recall_evidence"][0]["pieces"] = evidence[:1]
            encode(data_path, data)
        receipt["data_sha256"] = hashlib.sha256(data_path.read_bytes()).hexdigest()
        receipt["table_sha256"] = hashlib.sha256(table_path.read_bytes()).hexdigest()
        encode(receipt_path, receipt)
        index_path = directory / "index.json"
        index = json.loads(index_path.read_bytes())
        for item in index["artifacts"]:
            content = (directory / item["path"]).read_bytes()
            item["sha256"] = hashlib.sha256(content).hexdigest()
            item["byteLength"] = len(content)
        encode(index_path, index)
        try:
            checker.verify(directory, revision)
        except AssertionError as error:
            if str(error) != expected:
                raise AssertionError(f"{mutation}: wrong witness: {error}") from error
            print(json.dumps({"mutation": mutation, "killed": True, "witness": str(error)}), flush=True)
        else:
            raise AssertionError(f"{mutation}: survived")
print(json.dumps({"after": checker.verify(source, revision)}), flush=True)
