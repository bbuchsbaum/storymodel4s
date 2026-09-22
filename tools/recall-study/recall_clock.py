#!/usr/bin/env python3
"""Content-free Sherlock recall-clock intake oracle; no scanner transform is inferred."""
import argparse
import csv
from decimal import Decimal, DecimalException
from fractions import Fraction
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import tempfile

REPO = Path(__file__).resolve().parents[2]
SCHEMA = "storymodel4s.sherlock.recall-clock-intake/v1"
COLUMNS = (
    ("word", "text"),
    ("princeton-word-onset-seconds", "decimal-seconds"),
    ("princeton-tr-number", "integer-tr"),
    ("princeton-tr-onset-seconds", "decimal-seconds"),
    ("openneuro-word-onset-seconds", "decimal-seconds"),
    ("openneuro-tr-number", "integer-tr"),
    ("openneuro-tr-onset-seconds", "decimal-seconds"),
)
CLOCKS = {"princeton": 1, "openneuro": 4}
DECIMAL = re.compile(r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
MAX_COEFFICIENT_DIGITS = 128
MAX_ABSOLUTE_EXPONENT = 128
MAX_TOKEN_CHARACTERS = 512


class IntakeError(ValueError):
    """Refusal details exclude source words, filenames and offending cell contents."""
    def __init__(self, code, row=None, column=None):
        super().__init__(code)
        self.detail = {"code": code}
        if row is not None:
            self.detail["record"] = row
        if column is not None:
            self.detail["column"] = column


def require(condition, code, **location):
    if not condition:
        raise IntakeError(code, **location)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def decode_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "duplicate-json-key")
            result[key] = value
        return result
    def constant(_):
        raise IntakeError("nonfinite-json-number")
    try:
        return json.loads(data, object_pairs_hook=pairs, parse_constant=constant)
    except (UnicodeError, json.JSONDecodeError):
        raise IntakeError("invalid-json") from None


def natural(value):
    return type(value) is int and value >= 0


def lineage_sources(lineage):
    require(isinstance(lineage, dict), "invalid-lineage")
    require(lineage.get("schema") == "storymodel4s.sherlock.recall-lineage"
            and type(lineage.get("schemaVersion")) is int
            and lineage["schemaVersion"] == 1, "unsupported-lineage")
    require(lineage.get("columns") == [{"id": key, "kind": kind} for key, kind in COLUMNS],
            "unsupported-column-contract")
    require(isinstance(lineage.get("artifactSetId"), str) and lineage["artifactSetId"],
            "missing-artifact-set")
    sources = lineage.get("sources")
    require(isinstance(sources, list) and sources, "missing-sources")
    ids, hashes = set(), set()
    for source in sources:
        require(isinstance(source, dict), "invalid-source")
        ident, checksum = source.get("id"), source.get("localCsvSha256")
        require(isinstance(ident, str) and re.fullmatch(r"recall-source-[0-9]{2}", ident),
                "invalid-source-id")
        require(isinstance(checksum, str) and SHA256.fullmatch(checksum), "invalid-source-digest")
        require(ident not in ids and checksum not in hashes, "ambiguous-source-identity")
        require(all(natural(source.get(k)) for k in
                    ("localCsvByteLength", "recordCount", "numericMissingCellCount")),
                "invalid-source-count")
        ids.add(ident)
        hashes.add(checksum)
    return sources


def check_alias(alias_map, lineage, source, alias_id):
    require(isinstance(alias_map, dict), "invalid-alias-map")
    require(alias_map.get("schema") == "storymodel4s.sherlock.recall-alias-map"
            and type(alias_map.get("schemaVersion")) is int
            and alias_map["schemaVersion"] == 1, "unsupported-alias-map")
    require(alias_map.get("sourceSetId") == lineage["artifactSetId"], "foreign-alias-set")
    rows = alias_map.get("mappings")
    require(isinstance(rows, list), "invalid-alias-map")
    known = {s["id"]: s["localCsvSha256"] for s in lineage["sources"]}
    aliases, targets = set(), set()
    for row in rows:
        require(isinstance(row, dict), "invalid-alias-map")
        alias, target = row.get("aliasId"), row.get("sourceId")
        require(isinstance(alias, str) and re.fullmatch(r"recall-alias-[0-9]{2}", alias),
                "invalid-alias-id")
        require(isinstance(target, str) and target in known, "unknown-alias-source")
        require(alias not in aliases and target not in targets, "duplicate-alias")
        require(row.get("sha256") == known[target], "alias-digest-mismatch")
        aliases.add(alias)
        targets.add(target)
    require(natural(alias_map.get("sourceCount")) and natural(alias_map.get("aliasCount"))
            and alias_map.get("sourceCount") == len(known)
            and alias_map.get("aliasCount") == len(rows), "alias-count-mismatch")
    omitted = alias_map.get("omittedSources")
    require(isinstance(omitted, list) and all(isinstance(x, str) for x in omitted)
            and len(omitted) == len(set(omitted)) and set(omitted) == set(known) - targets,
            "alias-omission-mismatch")
    selected = [row for row in rows if row["aliasId"] == alias_id]
    require(len(selected) == 1, "unknown-alias")
    require(selected[0]["sourceId"] == source["id"], "alias-source-mismatch")
    return {"status": "verified-byte-identity", "id": alias_id, "sourceId": source["id"]}


def number(cell, kind, row, column):
    text = cell.strip()
    if not text:
        return {"status": "missing", "reason": "blank-cell"}
    require(len(text) <= MAX_TOKEN_CHARACTERS, "numeric-representation-limit", row=row, column=column)
    require(DECIMAL.fullmatch(text), "invalid-numeric-cell", row=row, column=column)
    coefficient, separator, exponent = text.lower().partition("e")
    digits = sum(c in "0123456789" for c in coefficient)
    exponent_digits = exponent.lstrip("+-").lstrip("0") or "0"
    require(digits <= MAX_COEFFICIENT_DIGITS and len(exponent_digits) <= 3,
            "numeric-representation-limit", row=row, column=column)
    exponent_value = int(exponent) if separator else 0
    require(abs(exponent_value) <= MAX_ABSOLUTE_EXPONENT,
            "numeric-representation-limit", row=row, column=column)
    try:
        value = Decimal(text)
    except (DecimalException, ValueError):
        raise IntakeError("invalid-numeric-cell", row=row, column=column) from None
    require(value.is_finite(), "nonfinite-numeric-cell", row=row, column=column)
    require(kind != "integer-tr" or value == value.to_integral_value(),
            "fractional-tr-number", row=row, column=column)
    return {"status": "observed", "decimal": text}


def rational(value):
    return {"numerator": str(value.numerator), "denominator": str(value.denominator)}


def differences(records, left, right):
    pairs = [(r["values"][left], r["values"][right]) for r in records]
    values = [Fraction(Decimal(b["decimal"])) - Fraction(Decimal(a["decimal"]))
              for a, b in pairs if a["status"] == b["status"] == "observed"]
    return {"pairedRecords": len(values), "unpairedRecords": len(records) - len(values),
            "range": {"status": "observed", "minimum": rational(min(values)),
                      "maximum": rational(max(values))} if values else
                     {"status": "unavailable", "reason": "no-paired-values"}}


def intake(csv_bytes, lineage_bytes, clock, *, expected_source=None,
           alias_id=None, alias_bytes=None):
    require(isinstance(clock, str) and clock in CLOCKS, "clock-required")
    lineage = decode_json(lineage_bytes)
    sources = lineage_sources(lineage)
    checksum = digest(csv_bytes)
    matches = [s for s in sources if s["localCsvSha256"] == checksum]
    require(len(matches) == 1, "csv-not-in-declared-lineage")
    source = matches[0]
    require(len(csv_bytes) == source["localCsvByteLength"], "csv-length-mismatch")
    require(expected_source is None or expected_source == source["id"], "source-identity-mismatch")
    require((alias_id is None) == (alias_bytes is None), "alias-requires-map-and-id")
    alias = {"status": "not-requested"}
    if alias_id is not None:
        alias = check_alias(decode_json(alias_bytes), lineage, source, alias_id)
        alias["mapSha256"] = digest(alias_bytes)
    try:
        reader = csv.reader(io.StringIO(csv_bytes.decode("utf-8-sig"), newline=""), strict=True)
        header = next(reader, [])
        require(len(header) == 7 and header[0] == "Words", "invalid-csv-header")
        records, missing = [], 0
        for index, cells in enumerate(reader, 1):
            require(len(cells) == 7, "invalid-column-count", row=index)
            values = {key: number(cells[col], kind, index, key)
                      for col, (key, kind) in enumerate(COLUMNS) if col}
            missing += sum(v["status"] == "missing" for v in values.values())
            # CSV record coordinates, deliberately not canonical RecallWordIds.
            records.append({"record": index, "textPresence": "nonempty" if cells[0] else "empty",
                            "values": values})
    except (UnicodeError, csv.Error):
        raise IntakeError("invalid-csv") from None
    require(len(records) == source["recordCount"], "record-count-mismatch")
    require(missing == source["numericMissingCellCount"], "missing-count-mismatch")
    selected = COLUMNS[CLOCKS[clock]][0]
    for record in records:
        record["selectedOnset"] = record["values"][selected]
    return {
        "schema": SCHEMA,
        "identity": {"artifactSetId": lineage["artifactSetId"], "sourceId": source["id"],
                     "csvSha256": checksum, "csvByteLength": len(csv_bytes),
                     "lineageSha256": digest(lineage_bytes), "alias": alias},
        "clock": {"selection": clock, "column": selected, "unit": "seconds",
                  "basis": "declared-release-export", "appliedOffset": "0"},
        "recordCoordinates": {"base": 1, "headerIncluded": False,
                              "kind": "csv-data-record-not-canonical-word-id"},
        "columns": [{"id": key, "kind": kind} for key, kind in COLUMNS[1:]],
        "numericTextLimits": {"coefficientDigits": MAX_COEFFICIENT_DIGITS,
                              "absoluteExponent": MAX_ABSOLUTE_EXPONENT,
                              "tokenCharacters": MAX_TOKEN_CHARACTERS},
        "counts": {"records": len(records), "emptyTextRecords": sum(r["textPresence"] == "empty" for r in records),
                   "missingNumericCells": missing,
                   "missingSelectedOnsets": sum(r["selectedOnset"]["status"] == "missing" for r in records)},
        "capabilities": {
            "wordOffsets": {"status": "unavailable", "reason": "onsets-only"},
            "scannerAlignment": {"status": "unavailable", "reason": "no-verified-run-crosswalk"},
            "canonicalWordJoin": {"status": "unavailable", "reason": "inventory-join-not-supplied"}},
        "offsetDiagnostics": {
            "meaning": "openneuro-minus-princeton-observed-differences-not-an-applied-transform",
            "wordSeconds": differences(records, COLUMNS[1][0], COLUMNS[4][0]),
            "trNumber": differences(records, COLUMNS[2][0], COLUMNS[5][0]),
            "trSeconds": differences(records, COLUMNS[3][0], COLUMNS[6][0])},
        "records": records,
    }


def encode(value):
    return (json.dumps(value, sort_keys=True, indent=2, allow_nan=False) + "\n").encode("utf-8")


def publish(path, data):
    """Publish one complete file without overwriting an existing path, including races."""
    path = Path(path)
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".recall-clock-", delete=False) as file:
        temporary = Path(file.name)
        try:
            file.write(data)
            file.flush()
            os.fsync(file.fileno())
            try:
                os.link(temporary, path)
            except FileExistsError:
                raise IntakeError("output-exists") from None
        finally:
            temporary.unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, required=True)
    parser.add_argument("--clock", choices=CLOCKS, required=True)
    parser.add_argument("--lineage", type=Path, default=REPO / "docs/data/sherlock/recall-lineage.json")
    parser.add_argument("--expect-source")
    parser.add_argument("--alias-id")
    parser.add_argument("--alias-map", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        require((args.alias_id is None) == (args.alias_map is None), "alias-requires-map-and-id")
        result = intake(args.csv.read_bytes(), args.lineage.read_bytes(), args.clock,
                        expected_source=args.expect_source, alias_id=args.alias_id,
                        alias_bytes=args.alias_map.read_bytes() if args.alias_map else None)
        result["producer"] = {"kind": "independent-intake-oracle", "scriptSha256": digest(Path(__file__).read_bytes())}
        payload = encode(result)
        publish(args.out, payload)
        print(json.dumps({"schema": SCHEMA, "sourceId": result["identity"]["sourceId"],
                          "clock": args.clock, "records": result["counts"]["records"],
                          "outputSha256": digest(payload)}, sort_keys=True))
        return 0
    except IntakeError as error:
        print(json.dumps({"error": error.detail}, sort_keys=True), file=sys.stderr)
        return 2
    except OSError:
        print(json.dumps({"error": {"code": "io-error"}}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
