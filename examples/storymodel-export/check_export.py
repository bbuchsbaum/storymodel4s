#!/usr/bin/env python3
"""Acceptance checks for the War of the Ghosts export (ADR 0020, AC2). Standard library only.

1. The independent reader recovers the node, evidence and span counts.
2. One hand-checked row: entity `wog:ent:egulac`. Its two support spans were checked by hand
   against the fixture text (fixtures/.../wog/WarOfTheGhostsText.scala, canonical form):
   [0, 28) = "There were people at Egulac." and [1511, 1534) = "They arrived at Egulac."
3. Absent and zero stay distinguishable.
4. Rehashed corruptions are refused: every file hash and byte count is recomputed after the edit,
   so only a semantic check can catch them.

Usage: check_export.py DIRECTORY
"""
import hashlib
import json
import os
import shutil
import sys
import tempfile

import read_storymodel_export as reader

EXPECTED = {
    "nodes": 110,
    "node_kinds": {
        "context": 12,
        "entity": 14,
        "event": 57,
        "segment": 13,
        "state": 14,
    },
    "relations": 178,
    "hierarchy": 84,
    "evidence": 372,
    "spans": 863,
    "exported_claims": 372,
    "model_claims": 475,
    "not_supplied": ["build-receipt"],
    "upstream_refs": {"in_tables": 0, "outside_tables": 0},
}


def check_upstream_outside_tables(directory):
    """Lawful variant: an evidence item citing one exported and one omitted claim is accepted,
    and each reference is classified. WoG itself cites no upstream claims, so this is synthetic."""
    with tempfile.TemporaryDirectory() as scratch:
        copy = os.path.join(scratch, "export")
        shutil.copytree(directory, copy)
        cited = '"{""status"":""present"",""value"":[""wog:claim:ent:arrows"",""wog:claim:omitted-descriptor""]}"'
        edit_table(copy, "evidence.tsv", replace_first('"{""status"":""present"",""value"":[]}"', cited))
        rehash(copy)
        summary, _ = reader.read(copy)
        assert summary["upstream_refs"] == {"in_tables": 1, "outside_tables": 1}, summary



def check_hand_row(tables):
    [node] = [n for n in tables["nodes"] if n["node_id"] == "wog:ent:egulac"]
    assert (
        node["node_kind"] == "entity" and node["claim_id"] == "wog:claim:ent:egulac"
    ), node
    assert node["claim_status"] == "SurfaceExplicit", node
    assert node["context"] == {"status": "not-applicable"}, node
    [entity] = [e for e in tables["entities"] if e["node_id"] == "wog:ent:egulac"]
    assert entity["label"] == "Egulac", entity
    assert entity["entity_type"] == {"status": "standard", "value": "Location"}, entity
    support = sorted(
        (s["utf16_start"], s["utf16_end_exclusive"])
        for s in tables["spans"]
        if s["claim_id"] == "wog:claim:ent:egulac" and s["origin"] == "node-support"
    )
    assert support == [(0, 28), (1511, 1534)], support


def check_absent_is_not_zero(tables):
    first = [
        s
        for s in tables["situations"]
        if s["discourse_position"] == {"status": "present", "value": 0}
    ]
    assert len(first) == 1, first
    frames = [s["frame"] for s in tables["situations"]]
    assert all(f["status"] in ("present", "absent") for f in frames), frames
    absent = [f for f in frames if f["status"] == "absent"]
    assert absent and all(
        f == {"status": "absent", "reason": "not-supplied"} for f in absent
    )


def rehash(directory):
    path = os.path.join(directory, "manifest.json")
    with open(path, encoding="utf-8") as handle:
        manifest = json.load(handle)
    for f in manifest["files"]:
        with open(os.path.join(directory, f["name"]), "rb") as handle:
            data = handle.read()
        f["sha256"] = hashlib.sha256(data).hexdigest()
        f["bytes"] = str(len(data))
        rows = data.decode("utf-8").count("\n") - 1
        f["table"]["rows"] = str(rows)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(json.dumps(manifest, sort_keys=True, separators=(",", ":")))


def edit_table(directory, name, edit):
    path = os.path.join(directory, name)
    with open(path, encoding="utf-8", newline="") as handle:
        lines = handle.read().split("\n")[:-1]
    lines = edit(lines)
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write("\n".join(lines) + "\n")


def edit_manifest(directory, edit):
    path = os.path.join(directory, "manifest.json")
    with open(path, encoding="utf-8") as handle:
        manifest = json.load(handle)
    edit(manifest)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(json.dumps(manifest, sort_keys=True, separators=(",", ":")))


def replace_first(old, new):
    def edit(lines):
        for i, line in enumerate(lines):
            if old in line:
                lines[i] = line.replace(old, new, 1)
                return lines
        raise AssertionError(f"corruption target {old!r} not found")

    return edit


CORRUPTIONS = {
    "situation context names no context": lambda d: edit_table(
        d,
        "nodes.tsv",
        replace_first(
            '""status"":""present"",""value"":""wog:ctx:',
            '""status"":""present"",""value"":""wog:ctx:missing-',
        ),
    ),
    "absent cell emptied to blank": lambda d: edit_table(
        d,
        "situations.tsv",
        replace_first('"{""reason"":""not-supplied"",""status"":""absent""}"', '""'),
    ),
    "duplicated node row": lambda d: edit_table(
        d, "nodes.tsv", lambda ls: ls + [ls[1]]
    ),
    "span_count off by one": lambda d: edit_table(
        d,
        "evidence.tsv",
        lambda ls: ls[:1] + [ls[1].rsplit("\t", 1)[0] + '\t"999"'] + ls[2:],
    ),
    "empty span": lambda d: edit_table(
        d,
        "spans.tsv",
        lambda ls: ls[:1]
        + [ls[1].rsplit("\t", 1)[0] + "\t" + ls[1].rsplit("\t", 2)[1]]
        + ls[2:],
    ),
    "loss record removed": lambda d: edit_manifest(
        d,
        lambda m: m.__setitem__(
            "losses", [l for l in m["losses"] if l["structure"] != "mentions"]
        ),
    ),
    "exported_claims inflated": lambda d: edit_manifest(
        d,
        lambda m: m.__setitem__("exported_claims", str(int(m["exported_claims"]) + 1)),
    ),
    "upstream ids unsorted": lambda d: edit_table(
        d,
        "evidence.tsv",
        replace_first(
            '"{""status"":""present"",""value"":[]}"',
            '"{""status"":""present"",""value"":[""z"",""a""]}"',
        ),
    ),
    "canonical_model claimed present": lambda d: edit_manifest(
        d,
        lambda m: m["capabilities"].__setitem__(
            "canonical_model", {"status": "present"}
        ),
    ),
    "foreign model digest in one row": lambda d: edit_table(
        d, "relations.tsv", lambda ls: ls[:1] + ['"' + "0" * 64 + ls[1][65:]] + ls[2:]
    ),
    "extra file": lambda d: open(os.path.join(d, "notes.txt"), "w").close(),
}


def main(argv):
    directory = argv[1]
    summary, tables = reader.read(directory)
    for key, value in EXPECTED.items():
        assert summary[key] == value, (key, summary[key], value)
    check_hand_row(tables)
    check_absent_is_not_zero(tables)
    check_upstream_outside_tables(directory)
    refused = {}
    for name, corrupt in CORRUPTIONS.items():
        with tempfile.TemporaryDirectory() as scratch:
            copy = os.path.join(scratch, "export")
            shutil.copytree(directory, copy)
            corrupt(copy)
            if name != "extra file":
                rehash(copy)
            try:
                reader.read(copy)
                refused[name] = "ACCEPTED"
            except reader.Refusal as e:
                refused[name] = f"refused: {e}"
    accepted = [n for n, r in refused.items() if r == "ACCEPTED"]
    print(
        json.dumps(
            {"summary": summary, "corruptions": refused}, indent=1, sort_keys=True
        )
    )
    if accepted:
        print(f"FAIL: corruptions accepted: {accepted}", file=sys.stderr)
        return 1
    print(f"PASS: counts, hand row, absent-vs-zero, upstream classification, {len(refused)} corruptions refused")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
