#!/usr/bin/env python3
"""Independent Python readback of the two M1 fixtures; imports no producer code.

Usage: python3 tools/check-workspace-fixtures.py DIRECTORY PRODUCER_SHA
Checks literal control answers as well as byte and selection receipt bindings.
"""

import csv
import hashlib
import io
import json
import re
import struct
import sys
from pathlib import Path


EXPECTED = {
    "bell": {
        "first": "sit:bell:sit:ring",
        "second": "sit:bell:sit:quiet",
        "recall": ["A bell rang.", "It rang again."],
        "source": {
            "sit:bell:sit:ring": ["A bell rang.", "The bell rang again."],
            "sit:bell:sit:quiet": ["The clock remained silent."],
        },
    },
    "wog": {
        "first": "sit:wog:sit:hunt-seals",
        "second": "sit:wog:sit:hear-war-cries",
        "recall": ["Two men hunted.", "They heard cries."],
        "source": {
            "sit:wog:sit:hunt-seals": ["One night two young men went to hunt seals."],
            "sit:wog:sit:hear-war-cries": ["While they were paddling they heard war-cries."],
        },
    },
}
checks = 0


def check(condition, label):
    global checks
    checks += 1
    if not condition:
        raise AssertionError(label)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def present(value):
    check(value["status"] == "present", "explicit supplied optional value")
    return value["value"]


def number(value):
    check(bool(re.fullmatch(r"0x[0-9a-f]{16}", value)), "canonical IEEE-754 value")
    return struct.unpack(">d", bytes.fromhex(value[2:]))[0]


def pieces(values, text):
    for piece in values:
        span = piece["span"]["span"]
        # Both product courts use ASCII, so Python offsets equal UTF-16 source offsets.
        check(text.isascii(), "fixture ASCII offset precondition")
        check(text[span["start"]:span["end"]] == piece["text"], "exact source slice")
    return [piece["text"] for piece in values]


def verify(directory, revision):
    index = json.loads((directory / "index.json").read_bytes())
    check(index["producerRevision"] == revision, "exact producer revision")
    check(len(index["artifacts"]) == 18, "18 fixture artifacts")
    names = [entry["path"] for entry in index["artifacts"]]
    check(len(set(names)) == 18, "unique artifact paths")
    for entry in index["artifacts"]:
        check(Path(entry["path"]).name == entry["path"], "local artifact basename")
        content = (directory / entry["path"]).read_bytes()
        check(len(content) == entry["byteLength"], "index byte length")
        check(digest(content) == entry["sha256"], "index byte digest")

    for name, expected in EXPECTED.items():
        archive_bytes = (directory / f"{name}.workspace.json").read_bytes()
        archive = json.loads(archive_bytes)
        check(archive["schemaVersion"] == "workspace-archive/v0.1", "archive schema")
        files = {entry["path"]: entry["utf8"].encode("utf-8") for entry in archive["files"]}
        members = {}
        for entry in archive["entries"]:
            check(entry["disposition"]["status"] == "Supplied", "fixture member supplied")
            metadata = entry["disposition"]["artifact"]
            content = files[entry["path"]]
            check(len(content) == metadata["byteLength"], "member byte length")
            check(digest(content) == metadata["checksum"], "member byte digest")
            role = entry["role"]
            key = role.get("id", role["kind"])
            members[key] = json.loads(content)
        check(members["Receipt"]["producerRevision"] == revision, "member producer revision")
        check(members["Receipt"]["origin"] == "AuthoredFixture", "fixture authority")
        check(members["Capabilities"]["inspection"] == "Granted", "inspection declaration")
        check(members["Capabilities"]["export"] == "Granted", "export declaration")
        clocks = members["Inventory"]["timing"]
        check([r["unit"] for r in clocks] == [f"m1:u{i}" for i in range(4)], "all ordinal units")
        check([r["clock"]["status"] for r in clocks] == ["untimed", "onset", "interval", "untimed"], "clock missingness")
        check(number(clocks[1]["clock"]["at"]) == 2.5, "onset seconds")
        check(number(clocks[2]["clock"]["start"]) == 4.0, "interval start seconds")
        check(number(clocks[2]["clock"]["end"]) == 5.0, "interval end seconds")
        historical = members["historical-lexical"]
        check(historical["policies"]["inference_policy_id"]["status"] == "historical-reconstruction", "historical authority retained")
        check(len(historical["outcomes"]) == 4, "historical complete inventory")
        for outcome in historical["outcomes"]:
            posterior = [number(v["raw_value"]) for v in outcome["mapping_links"] if v["measure_kind"] == "ModelPosterior"]
            check(bool(posterior) and abs(sum(posterior) - 1.0) <= 1e-9, "executed posterior row")
        model = members["SourceModel"]
        recall = members["Recall"]
        if name == "bell":
            check(len(model["graph"]["segments"]) == 1, "small fixture one segment")
            check(len(model["graph"]["situations"]) == 2, "small fixture two situations")
        for policy in ("authored-a", "authored-b"):
            prefix = f"{name}-{policy}-u0"
            data_bytes = (directory / f"{prefix}.json").read_bytes()
            table_bytes = (directory / f"{prefix}.csv").read_bytes()
            text_bytes = (directory / f"{prefix}.txt").read_bytes()
            receipt = json.loads((directory / f"{prefix}.receipt.json").read_bytes())
            data = json.loads(data_bytes)
            for payload, key in ((data_bytes, "data_sha256"), (table_bytes, "table_sha256"), (text_bytes, "text_sha256")):
                check(digest(payload) == receipt[key], "export payload digest")
            check(receipt["workspace_archive"] == data["workspace_archive"] == digest(archive_bytes), "export archive binding")
            check(receipt["policy"] == data["policy"] == policy, "selected policy")
            check(receipt["units"] == ["m1:u0"], "selected unit inventory")
            check(receipt["selection"] == data["selection"], "selection receipt")
            record = members[policy]
            check(data["original_record_digest"] == receipt["original_record_digest"] == record["record_digest"], "original record binding")
            check(data["outcomes"] == record["outcomes"][:1], "unchanged original outcome")
            check(record["outcomes"][-1]["processing_status"]["status"] == "failed", "failed row retained")
            check(record["outcomes"][2]["localization_status"] == "Nonlocalizable", "all-external row retained")
            outcome = data["outcomes"][0]
            values = {(v["destination"], v["measure_kind"]): number(v["raw_value"]) for v in outcome["mapping_links"]}
            raw = (0.9, 0.4, 0.2) if policy == "authored-a" else (0.1, 0.8, 0.1)
            mass = (0.25, 0.25, 0.5) if policy == "authored-a" else (0.1, 0.6, 0.3)
            for destination, r, m in zip((expected["first"], expected["second"], "ext:Intrusion"), raw, mass):
                check(values[(destination, "RawScore")] == r, "literal authored raw answer")
                check(values[(destination, "NormalizedScoreMass")] == m, "literal unrenormalized mass answer")
            check(len(values) == 6, "no fabricated values")
            decision = present(outcome["decision"])
            check(present(decision["decoded_target_id"]) == expected["second"], "decoded target answer")
            check(present(decision["raw_argmax"])["destination"] == expected["first" if policy == "authored-a" else "second"], "argmax independent of decode")
            check(decision["calibration"]["status"] == "unavailable", "no fabricated calibration")
            rows = list(csv.DictReader(io.StringIO(table_bytes.decode("utf-8"))))
            supplied = {(r["destination"], r["measure"]): number(r["value_ieee754"]) for r in rows if r["measure"] != "NotSupplied"}
            check(supplied == values, "CSV independently recovers exact measures")
            check(all(r["unit"] == "m1:u0" and json.loads(r["recall_clock_seconds"]) == {"status": "untimed"} for r in rows), "CSV unit and explicit untimed clock")
            check(any(r["measure"] == "NotSupplied" for r in rows), "missing values retained")
            evidence = data["recall_evidence"]
            check(len(evidence) == 1 and evidence[0]["unit"] == "m1:u0", "recall evidence identity")
            check(evidence[0]["clock"] == {"status": "untimed"}, "JSON explicit untimed clock")
            check(pieces(evidence[0]["pieces"], recall["transcript"]["canonicalText"]) == expected["recall"], "literal discontiguous recall answer")
            source = {r["target"]: pieces(r["support"]["pieces"], model["source"]["canonicalText"]) for r in data["source_evidence"]}
            check(source == expected["source"], "literal exact source answers")
            text = text_bytes.decode("utf-8")
            check(f"Policy: {policy}" in text, "accessible policy label")
            check("This note is outside the selected evidence." not in text, "no recall hull in accessible export")
            check(all(piece in text for piece in expected["recall"]), "accessible exact recall")
    return {"status": "passed", "checks": checks, "producerRevision": revision, "fixtures": ["wog", "bell"], "artifacts": 18}


if __name__ == "__main__":
    if len(sys.argv) != 3 or not re.fullmatch(r"[0-9a-f]{40}", sys.argv[2]):
        raise SystemExit(__doc__)
    print(json.dumps(verify(Path(sys.argv[1]), sys.argv[2]), sort_keys=True))
