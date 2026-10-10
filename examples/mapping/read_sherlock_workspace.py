#!/usr/bin/env python3
"""Independently inspect the local Sherlock workspace packet without deriving mapping authority."""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path

from read_mapping import parse, require
from read_workspace import inspect


RECALL_HEADER = [
    "Words",
    "Princeton Dataspace Word Onset Time (sec)",
    "Princeton Dataspace TR Number",
    "Princeton Dataspace TR Onset (sec)",
    "OpenNeuro Word Onset Time (sec)",
    "OpenNeuro TR Number",
    "OpenNeuro TR Onset (sec)",
]


def sha_bytes(value):
    return hashlib.sha256(value).hexdigest()


def sha_text(value):
    return sha_bytes(value.encode("utf-8", errors="strict"))


def read_json(path):
    return parse(path.read_text(encoding="utf-8"))


def replay_recall(path, record):
    raw = path.read_bytes()
    require(len(raw) == record["localCsvByteLength"] and sha_bytes(raw) == record["localCsvSha256"],
            "foreign recall CSV")
    text = raw.decode("utf-8", errors="strict")
    require(text.encode("utf-8", errors="strict") == raw, "non-roundtrip recall UTF-8")
    rows = list(csv.reader(io.StringIO(text, newline="")))
    require(rows and rows[0] == RECALL_HEADER and len(rows) - 1 == record["recordCount"],
            "invalid recall CSV")
    require(all(len(row) == len(RECALL_HEADER) for row in rows[1:]), "invalid recall row")
    return sha_text(" ".join(row[0].strip() for row in rows[1:] if row[0].strip()))


def annotations(input_dir, receipt):
    path = input_dir / "annotations.csv"
    raw = path.read_bytes()
    require(sha_bytes(raw) == receipt["preparedCsvSha256"], "foreign prepared annotations")
    text = raw.decode("utf-8", errors="strict")
    require(text.encode("utf-8", errors="strict") == raw, "non-roundtrip annotation UTF-8")
    rows = list(csv.DictReader(io.StringIO(text, newline="")))
    require(len(rows) == receipt["rows"] == 1000, "annotation row denominator")
    require(list(rows[0]) == ["ordinal", "text", "part", "onset", "end", "group", "group_label", "locations", "characters"],
            "annotation header")
    require([int(row["ordinal"]) for row in rows] == list(range(1, 1001)), "annotation ordinal")
    require({int(row["group"]) for row in rows} == set(range(1, receipt["scenes"] + 1)), "annotation groups")
    require([row["part"] for row in rows[:482]] == ["annotation-run-1"] * 482 and
            [row["part"] for row in rows[482:]] == ["annotation-run-2"] * 518,
            "annotation part boundary")
    require(rows[12]["onset"] == "45" and rows[12]["end"] == "", "annotation point row 13")
    return rows


def source_support(decoded, annotation_rows):
    source = decoded["source"]
    axes = {axis["id"]: axis for axis in source["axes"]}
    require(len(axes) == 2, "annotation axis denominator")
    axis_part = {}
    for axis in axes.values():
        require(axis["kind"] == "AnnotationTimeline" and axis["source_kind"] == {"status": "AnnotationTable"}
                and axis["edition"]["status"] == "absent", "annotation scope must not claim a movie edition")
        extent = axis["extent"]
        require(extent["status"] == "playback-ticks" and extent["start_tick"] == "0" and
                extent["timebase"] == {"numerator": "1", "denominator": "1"},
                "annotation axis clock")
        end = extent["end_exclusive_tick"]
        require(end in {"1426", "1544"}, "annotation axis extent")
        axis_part[axis["id"]] = "annotation-run-1" if end == "1426" else "annotation-run-2"
    require(set(axis_part.values()) == {"annotation-run-1", "annotation-run-2"}, "annotation axes")

    leaves = []
    for target in source["targets"]:
        if target["level"] != 0:
            continue
        target_id = target["target_id"]
        require(":timed:" in target_id, "foreign leaf target")
        ordinal = int(target_id.rsplit(":", 1)[1])
        status = target["support_status"]
        require(status["status"] == "located" and status["support_relation"] == "evidence-support", "missing leaf support")
        support = status["source_support"]
        require(support["status"] == "anchored" and len(support["anchors"]) == 1, "leaf support shape")
        leaves.append((ordinal, support["anchors"][0]))
    require(sorted(ordinal for ordinal, _ in leaves) == list(range(1, 1001)), "leaf target denominator")
    require(sum(target["level"] == 1 for target in source["targets"]) == 50, "group target denominator")

    for ordinal, anchor in leaves:
        row = annotation_rows[ordinal - 1]
        require(anchor["axis"] in axis_part and axis_part[anchor["axis"]] == row["part"], "foreign support part")
        if row["end"]:
            require(anchor["kind"] == "media-time" and len(anchor["intervals"]) == 1, "extent support")
            interval = anchor["intervals"][0]
            require(interval == {"axis": anchor["axis"], "start_tick": row["onset"], "end_exclusive_tick": row["end"]},
                    "foreign extent support")
        else:
            require(anchor["kind"] == "media-point" and anchor["tick"] == row["onset"], "foreign point support")


def source_artifact(decoded, input_dir, receipt):
    wrapper = parse(decoded["roles"]["SourceInput"])
    require(wrapper["schemaVersion"] == "workspace-source-input/v1" and
            wrapper["kind"] == "timed-annotations" and wrapper["segmentKind"] is None,
            "workspace source wrapper")
    artifact = parse(wrapper["artifact"])
    require(set(artifact) == {"schemaVersion", "csv", "parts"} and
            artifact["schemaVersion"] == "prepared-timed-source/v1", "prepared timed source")
    csv_bytes = artifact["csv"].encode("utf-8", errors="strict")
    parts_bytes = artifact["parts"].encode("utf-8", errors="strict")
    require(csv_bytes == (input_dir / "annotations.csv").read_bytes() and
            parts_bytes == (input_dir / "parts.json").read_bytes() and
            sha_bytes(csv_bytes) == receipt["preparedCsvSha256"] and
            sha_bytes(parts_bytes) == receipt["descriptorSha256"], "foreign source artifact")


def inspect_workspace(input_dir):
    receipt = read_json(input_dir / "preparation-receipt.json")
    require(receipt["recipe"] == "released-sherlock-annotation-clock-workspace/v1" and
            receipt["rows"] == 1000 and receipt["scenes"] == 50, "preparation receipt")
    require(sha_bytes((input_dir / "parts.json").read_bytes()) == receipt["descriptorSha256"],
            "foreign part descriptor")
    annotation_rows = annotations(input_dir, receipt)
    lineage = read_json(input_dir / "recall-lineage.json")
    require(lineage["schema"] == "storymodel4s.sherlock.recall-lineage", "recall lineage")
    source_records = {row["id"]: row for row in lineage["sources"]}
    admitted = receipt["recalls"]
    require([row["source"] for row in admitted] == ["recall-source-01", "recall-source-02"], "recall selection")
    expected_digests = {}
    for entry in admitted:
        source_id = entry["source"]
        record = source_records[source_id]
        require(entry["csvSha256"] == record["localCsvSha256"] and entry["records"] == record["recordCount"],
                "recall preparation receipt")
        expected_digests[source_id] = replay_recall(input_dir / (source_id + ".csv"), record)

    output = input_dir / "output"
    manifest = read_json(output / "manifest.json")
    require(manifest["schemaVersion"] == "study-workspaces/v1", "workspace root schema")
    metadata = manifest["metadata"]
    outcomes = metadata["outcomes"]
    require(len(outcomes) == len(manifest["files"]) == 2, "workspace cohort denominator")
    require({(row["participant"], row["session"]) for row in outcomes} ==
            {(source_id, "released") for source_id in expected_digests}, "workspace cohort identity")
    files = {row["path"]: row["sha256"] for row in manifest["files"]}
    require(len(files) == 2 and set(output.iterdir()) == {output / "manifest.json"} | {output / name for name in files},
            "workspace root members")

    decoded = {}
    for outcome in outcomes:
        participant = outcome["participant"]
        packet = output / outcome["workspace"]
        require(outcome.get("failure") is None and outcome["readiness"] == "Ready" and
                outcome["workspace"] in files and sha_bytes(packet.read_bytes()) == files[outcome["workspace"]],
                "workspace packet identity")
        value = inspect(packet.read_text(encoding="utf-8"), expected_digests[participant])
        require(value["receipt"]["sourceEdition"] == metadata["sourceEdition"] and
                value["source"]["digest"] == metadata["sourceRepresentation"], "workspace source identity")
        disclosure = value["receipt"]["disclosure"]
        basis = disclosure["basis"]
        require(disclosure["textDigest"] == expected_digests[participant] and
                basis["kind"] == "public-corpus-admitted" and basis["sourceId"] == participant and
                basis["transformVersion"] == "sherlock-word-csv-replay/v1", "recall admission")
        require(outcome["units"] == [unit["id"] for unit in value["inventory"]["units"]], "workspace unit join")
        source_artifact(value, input_dir, receipt)
        source_support(value, annotation_rows)
        decoded[participant] = value
    require(len({json.dumps(value["source"], sort_keys=True) for value in decoded.values()}) == 1,
            "foreign cohort source")
    require(len({tuple(unit["id"] for unit in value["inventory"]["units"]) for value in decoded.values()}) == 2,
            "distinct recalls must retain their own unit identities")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="prepared local Sherlock workspace input directory")
    args = parser.parse_args()
    inspect_workspace(args.input)
    print(json.dumps({"status": "pass", "packets": 2, "annotationRows": 1000}, sort_keys=True))


if __name__ == "__main__":
    main()
