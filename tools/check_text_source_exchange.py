#!/usr/bin/env python3
"""Independent standard-library reader for text-source-exchange/v1.

Checks bytes, canonicalization, UTF-16 support, atlas joins and target identities.
It does not independently re-run the Scala sentence segmentation algorithm.
Use plain UTF-8: utf-8-sig would silently remove a content-bearing BOM.
"""

import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import re


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load(text):
    return json.loads(text, object_pairs_hook=unique_object)


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def canonical_text(raw):
    lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    return re.sub(r"\n{3,}", "\n\n", "\n".join(re.sub(r"[ \t]+$", "", line) for line in lines)).strip("\n")


def check(directory):
    directory = Path(directory)
    require({p.name for p in directory.iterdir()} == {"source.json", "segments.tsv", "manifest.json"}, "file inventory mismatch")
    manifest = load((directory / "manifest.json").read_bytes().decode("utf-8"))
    require(manifest["schemaVersion"] == "text-source-exchange/v1", "unsupported exchange schema")
    require(manifest["wire"] == "quoted-tsv/v1", "unsupported table wire")
    require([f["name"] for f in manifest["files"]] == ["source.json", "segments.tsv"], "unsafe or duplicate manifest file name")
    payloads = {}
    for record in manifest["files"]:
        data = (directory / record["name"]).read_bytes()
        require(len(data) == record["bytes"] and hashlib.sha256(data).hexdigest() == record["sha256"], "file hash/length mismatch")
        payloads[record["name"]] = data.decode("utf-8")
    package = load(payloads["source.json"])
    require(package["schemaVersion"] == "text-source/v1", "unsupported source schema")
    require(package["profile"] in ("surface-semicolon/v1", "supplied-atlas/v1"), "unknown profile")
    require(package["canonicalization"] == "story-source/v1", "unknown canonicalization")
    require(package["offset_unit"] == "utf-16-code-units" and package["coordinate_text"] == "source.canonicalText", "wrong coordinate declaration")
    source = package["source"]
    raw, text = source["rawText"], source["canonicalText"]
    require(text == canonical_text(raw), "canonical text mismatch")
    require(source["rawChecksum"] == sha(raw) and source["canonicalChecksum"] == sha(text), "source hash mismatch")
    java_trim_chars = "".join(chr(i) for i in range(33))
    require(raw.replace("\ufeff", "").strip(java_trim_chars), "BOM-only or empty source")
    utf16 = text.encode("utf-16-le")
    if raw.startswith("\ufeff"):
        require(text.startswith("\ufeff") and utf16[:2] == b"\xff\xfe", "content BOM was stripped")
    expected_caps = {
        "character_offsets": {"status": "available"},
        "discourse_ordinals": {"status": "available"},
        "narrative_model": {"status": "unavailable", "reason": "not-supplied"},
        "encoding_seconds": {"status": "unavailable", "reason": "no-presentation-schedule"},
    }
    require(package["capabilities"] == expected_caps, "fabricated source capability")
    atlas = package["atlas"]
    require(atlas["schemaVersion"] == "surface-atlas/v1" and atlas["storyId"] == source["id"] and atlas["canonicalSourceChecksum"] == sha(text), "atlas/source join mismatch")
    units = {u["id"]: u for u in atlas["units"]}
    require(len(units) == len(atlas["units"]), "duplicate atlas ID")
    coarseness = {"Paragraph": 0, "Sentence": 1, "Clause": 2, "Token": 3}
    previous = {}
    for u in atlas["units"]:
        a, b, kind = u["span"]["start"], u["span"]["end"], u["kind"]
        require(type(a) is int and type(b) is int and 0 <= a < b <= len(utf16) // 2, "invalid atlas span")
        utf16[2*a:2*b].decode("utf-16-le")  # Strictly rejects a bisected surrogate pair.
        require(kind in coarseness and type(u["ordinal"]) is int and u["ordinal"] >= 0, "invalid atlas kind/ordinal")
        if kind in previous:
            p = previous[kind]
            require(p["ordinal"] < u["ordinal"] and p["span"]["end"] <= a, "overlapping or unordered atlas units")
        previous[kind] = u
        if "parent" in u:
            require(u["parent"] in units, "unknown atlas parent")
            p = units[u["parent"]]
            require(coarseness[p["kind"]] < coarseness[kind] and p["span"]["start"] <= a and b <= p["span"]["end"], "invalid atlas parent")
    targets = sorted((u for u in units.values() if u["kind"] != "Token"), key=lambda u: (coarseness[u["kind"]], u["ordinal"]))
    require(targets and len(targets) == len(package["segments"]), "missing/extra segments")
    expected_rows = []
    for unit, s in zip(targets, package["segments"]):
        require(all(type(s[k]) is int for k in ("start", "end_exclusive", "ordinal")), "noninteger segment coordinate")
        start, end = unit["span"]["start"], unit["span"]["end"]
        kind, profile = unit["kind"], package["profile"]
        parent = {"status": "present", "id": unit["parent"]} if "parent" in unit else {"status": "absent"}
        identity = "text-segment:" + sha("\0".join(("text-source-segment/v1", profile, sha(text), kind, str(start), str(end))))
        expected = dict(target_id=identity, surface_unit_id=unit["id"], kind=f"{profile}:{kind}", surface_kind=kind, ordinal=unit["ordinal"], start=start, end_exclusive=end, parent=parent, text=utf16[2*start:2*end].decode("utf-16-le"))
        require(s == expected, "segment support/identity mismatch")
        expected_rows.append([sha(text), "utf-16-code-units", profile, identity, unit["id"], f"{profile}:{kind}", kind, str(unit["ordinal"]), str(start), str(end), canonical(parent), expected["text"]])
    names = ["canonical_source_sha256", "offset_unit", "profile", "target_id", "surface_unit_id", "kind", "surface_kind", "ordinal", "start", "end_exclusive", "parent", "text"]
    types = ["utf8-string"] * 7 + ["decimal-integer"] * 3 + ["canonical-json", "utf8-string"]
    require(manifest["columns"] == [dict(name=n, type=t) for n, t in zip(names, types)], "column schema mismatch")
    rows = list(csv.reader(io.StringIO(payloads["segments.tsv"], newline=""), delimiter="\t", strict=True))
    require(rows == [names] + expected_rows, "TSV projection mismatch")
    stream = io.StringIO(newline="")
    csv.writer(stream, delimiter="\t", quoting=csv.QUOTE_ALL, lineterminator="\n").writerows(rows)
    require(stream.getvalue() == payloads["segments.tsv"], "noncanonical quoted TSV")
    return dict(status="checked", segments=len(targets), profile=package["profile"], canonical_utf16_units=len(utf16)//2, bom_retained=text.startswith("\ufeff"), segmentation_recomputed=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    try:
        result = check(args.directory)
    except (ValueError, KeyError, TypeError, OSError, UnicodeError, csv.Error) as error:
        parser.exit(2, canonical({"status": "refused", "reason": str(error)}) + "\n")
    print(canonical(result))


if __name__ == "__main__":
    main()
