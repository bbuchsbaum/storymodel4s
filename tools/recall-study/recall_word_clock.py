#!/usr/bin/env python3
"""Join an external recall clock to a pinned G1 word inventory by exact replay."""
import argparse
import csv
from decimal import Decimal
import io
import json
from pathlib import Path
import re
import sys

import recall_clock as clock

SCHEMA = "storymodel4s.recall-word-clock-join/v1"
RECIPE = "external-clock-exact-replay/v1"
WORD_POLICY = "input-artifact-sha256+zero-based-parsed-word-index/v1"
ID_WHITESPACE = {0x20, 0x1680, *range(0x2000, 0x2007), *range(0x2008, 0x200B), 0x2028, 0x2029, 0x205F, 0x3000}
require = clock.require


def utf16(text):
    return text.encode("utf-16-be", errors="surrogatepass")


def units(text):
    return len(utf16(text)) // 2


def sequence(values):
    return str(len(values)) + ":" + "".join(str(units(v)) + ":" + v for v in values)


def inventory_hash(values):
    # G1 hashes ASCII hex of UTF-16 code units, including lengths and optional IDs.
    return clock.digest(utf16(sequence(values)).hex().encode("ascii"))


def fields(value, names, code):
    require(isinstance(value, dict) and set(value) == set(names.split()), code)


def identifier(value):
    # IdRules checks UTF-16 length and Char.isWhitespace/isControl. NBSP, figure
    # space and narrow NBSP are not Java whitespace; Python isspace is broader.
    require(isinstance(value, str) and 0 < units(value) <= 256 and not any(
        ord(c) <= 0x1F or 0x7F <= ord(c) <= 0x9F or ord(c) in ID_WHITESPACE for c in value),
        "invalid-identifier")


def checksum(value):
    require(isinstance(value, str) and clock.SHA256.fullmatch(value), "invalid-checksum")


def java_trim(value):
    start, end = 0, len(value)
    while start < end and ord(value[start]) <= 32:
        start += 1
    while end > start and ord(value[end - 1]) <= 32:
        end -= 1
    return value[start:end]


def canonicalize(value):
    lines = value.replace("\r\n", "\n").replace("\r", "\n")
    stripped = "\n".join(line.rstrip(" \t") for line in lines.split("\n"))
    return re.sub(r"\n{3,}", "\n\n", stripped).strip("\n")


def replay(csv_bytes):
    # Intake has already checked exact bytes, CSV syntax and all row widths.
    rows = list(csv.reader(io.StringIO(csv_bytes.decode("utf-8-sig"), newline=""), strict=True))[1:]
    selected = [(record, java_trim(row[0])) for record, row in enumerate(rows, 1)]
    nonempty = [(record, word) for record, word in selected if word]
    text = " ".join(word for _, word in nonempty)
    require(bool(text), "empty-replayed-transcript")
    require(canonicalize(text) == text, "replay-needs-character-map")
    spans, cursor = [], 0
    for record, word in nonempty:
        end = cursor + units(word)
        spans.append({"record": record, "start": cursor, "end_exclusive": end})
        cursor = end + 1
    return text, spans, {record for record, word in selected if not word}


def span_render(spans):
    def surface(value):
        return sequence(["none"]) if value["status"] == "absent" else sequence(["some", value["value"]])
    return sequence([sequence([str(s["start"]), str(s["end_exclusive"]), surface(s["unit"])])
                     for s in spans])


def validate_inventory(inventory, text, expected_spans):
    """Check inventory structure/digests against a transcript, not mapping inference."""
    fields(inventory, "digest transcript_checksum segmentation_id word_id_policy words units", "invalid-inventory")
    for name in ("digest", "transcript_checksum", "segmentation_id"):
        checksum(inventory[name])
    require(inventory["transcript_checksum"] == clock.digest(text.encode("utf-8")), "transcript-mismatch")
    policy = inventory["word_id_policy"]
    fields(policy, "name input_artifact", "invalid-word-policy")
    require(policy["name"] == WORD_POLICY, "unsupported-word-policy")
    checksum(policy["input_artifact"])
    words, entries = inventory["words"], inventory["units"]
    require(isinstance(words, list) and isinstance(entries, list), "invalid-inventory-rows")
    require(len(words) == len(expected_spans), "word-record-bijection-mismatch")
    boundaries, position = {0}, 0
    for char in text:
        position += units(char)
        boundaries.add(position)
    def extent(value):
        start, end = value["start"], value["end_exclusive"]
        require(type(start) is int and type(end) is int and 0 <= start <= end <= position
                and start in boundaries and end in boundaries, "invalid-utf16-span")
    unit_ids = set()
    for ordinal, entry in enumerate(entries):
        fields(entry, "id ordinal spans words decomposition semantics", "invalid-unit")
        identifier(entry["id"])
        require(entry["id"] not in unit_ids, "duplicate-unit")
        unit_ids.add(entry["id"])
        require(type(entry["ordinal"]) is int and entry["ordinal"] == ordinal, "invalid-unit-order")
        require(entry["decomposition"] == {"status": "not-assessed", "reason": "NoDecompositionDetector"}
                and entry["semantics"] == "CategoricalReferent", "unsupported-unit-semantics")
        spans = entry["spans"]
        require(isinstance(spans, list) and spans, "invalid-unit-spans")
        keys = []
        for span in spans:
            fields(span, "start end_exclusive unit", "invalid-unit-span")
            extent(span)
            ref = span["unit"]
            require(isinstance(ref, dict), "invalid-surface-reference")
            if ref.get("status") == "present":
                fields(ref, "status value", "invalid-surface-reference")
                identifier(ref["value"])
                surface_key = (1, utf16(ref["value"]))
            else:
                require(ref == {"status": "absent", "reason": "not-supplied"}, "invalid-surface-reference")
                surface_key = (0, b"")
            keys.append((span["start"], span["end_exclusive"], surface_key))
        require(keys == sorted(set(keys)), "noncanonical-unit-spans")
        require(isinstance(entry["words"], list) and all(isinstance(w, str) for w in entry["words"]),
                "invalid-unit-words")
    derived_members = {ident: [] for ident in unit_ids}
    word_tokens = []
    for index, (word, expected) in enumerate(zip(words, expected_spans)):
        fields(word, "id index start end_exclusive membership", "invalid-word")
        require(type(word["index"]) is int and word["index"] == index, "invalid-word-order")
        require(word["id"] == policy["input_artifact"] + ":word:" + str(index), "word-id-mismatch")
        extent(word)
        require(word["start"] == expected["start"] and word["end_exclusive"] == expected["end_exclusive"],
                "word-span-replay-mismatch")
        owners = [entry["id"] for entry in entries if any(
            s["start"] < s["end_exclusive"] and s["start"] < word["end_exclusive"] and s["end_exclusive"] > word["start"]
            for s in entry["spans"])]
        require(len(owners) <= 1, "ambiguous-word-membership")
        if owners:
            membership = {"status": "member", "unit": owners[0]}
            member_token = sequence(["member", owners[0]])
            derived_members[owners[0]].append(word["id"])
        else:
            membership = {"status": "unassigned", "reason": "NotInAnyUnitSpan"}
            member_token = sequence(["unassigned", "NotInAnyUnitSpan"])
        require(word["membership"] == membership, "word-membership-mismatch")
        word_tokens.append(sequence([word["id"], str(index), str(word["start"]), str(word["end_exclusive"]), member_token]))
    for entry in entries:
        require(entry["words"] == derived_members[entry["id"]], "unit-membership-mismatch")
    segmentation = inventory_hash(["segmentation/v1", inventory["transcript_checksum"], text,
                                   sequence([sequence([u["id"], span_render(u["spans"])]) for u in entries])])
    require(inventory["segmentation_id"] == segmentation, "segmentation-digest-mismatch")
    calculated = inventory_hash([
        "recall-inventory/v1", inventory["transcript_checksum"], segmentation, policy["name"], policy["input_artifact"],
        sequence(word_tokens), sequence([sequence([u["id"], str(u["ordinal"]), span_render(u["spans"]),
                                                    sequence(u["words"]), "NotAssessed(NoDecompositionDetector)",
                                                    "CategoricalReferent"]) for u in entries])])
    require(inventory["digest"] == calculated, "inventory-digest-mismatch")


def measured_ref(word):
    return {"wordId": word["wordId"], "record": word["record"], "onset": word["onset"]}


def unit_summary(entry, words):
    members = [words[w] for w in entry["words"]]
    observed = [w for w in members if w["onset"]["status"] == "observed"]
    adjacent = list(zip(members, members[1:]))
    comparable = [(a, b) for a, b in adjacent if a["onset"]["status"] == b["onset"]["status"] == "observed"]
    backward = [{"fromWord": a["wordId"], "toWord": b["wordId"]} for a, b in comparable
                if Decimal(a["onset"]["decimal"]) > Decimal(b["onset"]["decimal"])]
    equal = sum(Decimal(a["onset"]["decimal"]) == Decimal(b["onset"]["decimal"]) for a, b in comparable)
    unavailable = {"status": "unavailable", "reason": "no-member-words" if not members else "no-measured-member-onsets"}
    return {"unitId": entry["id"], "ordinal": entry["ordinal"], "wordIds": entry["words"],
            "coverage": {"status": "unavailable" if not observed else "complete" if len(observed) == len(members) else "partial",
                         "totalWords": len(members), "observedOnsets": len(observed), "missingOnsets": len(members) - len(observed)},
            "firstMember": measured_ref(members[0]) if members else unavailable,
            "lastMember": measured_ref(members[-1]) if members else unavailable,
            "firstMeasuredOnset": measured_ref(observed[0]) if observed else unavailable,
            "lastMeasuredOnset": measured_ref(observed[-1]) if observed else unavailable,
            "onsetOrder": {"scope": "adjacent-member-words-in-transcript-order", "possiblePairs": len(adjacent),
                           "observedPairs": len(comparable), "unobservedPairs": len(adjacent) - len(comparable),
                           "backwardPairs": backward, "equalPairs": equal},
            "duration": {"status": "unavailable", "reason": "onsets-do-not-establish-duration"}}


def join(csv_bytes, lineage_bytes, selection, mapping_bytes, expected_mapping_sha256, *, recipe, **intake_options):
    require(recipe == RECIPE, "unsupported-replay-recipe")
    checksum(expected_mapping_sha256)
    require(clock.digest(mapping_bytes) == expected_mapping_sha256, "mapping-file-digest-mismatch")
    record = clock.decode_json(mapping_bytes)
    require(isinstance(record, dict) and record.get("schema") == "storymodel4s.mapping-record"
            and record.get("schemaVersion") == "mapping-record/v0.1", "unsupported-mapping-schema")
    checksum(record.get("record_digest"))
    intake = clock.intake(csv_bytes, lineage_bytes, selection, **intake_options)
    text, spans, omitted = replay(csv_bytes)
    inventory = record.get("inventory")
    validate_inventory(inventory, text, spans)
    words = []
    by_record = {}
    for word, span in zip(inventory["words"], spans):
        row = {"wordId": word["id"], "index": word["index"], "startUtf16": word["start"],
               "endExclusiveUtf16": word["end_exclusive"], "membership": word["membership"],
               "record": span["record"], "onset": intake["records"][span["record"] - 1]["selectedOnset"]}
        words.append(row)
        by_record[span["record"]] = word["id"]
    ledger = [{"record": r["record"], "selectedOnset": r["selectedOnset"],
               "correspondence": {"status": "excluded", "reason": "empty-after-java-trim"} if r["record"] in omitted else
                                 {"status": "matched", "wordId": by_record[r["record"]]}}
              for r in intake["records"]]
    by_id = {w["wordId"]: w for w in words}
    return {"schema": SCHEMA, "recipe": RECIPE,
            "binding": {"mappingFileSha256": expected_mapping_sha256, "declaredMappingRecordDigest": record["record_digest"],
                        "mappingSchemaVersion": record["schemaVersion"], "inventoryDigest": inventory["digest"],
                        "segmentationId": inventory["segmentation_id"], "transcriptChecksum": inventory["transcript_checksum"],
                        "wordIdPolicy": inventory["word_id_policy"], "clockSource": intake["identity"]},
            "validation": {"structuralCorrespondence": "verified-exact-replay",
                           "recordingIdentityBinding": "not-verified-by-this-reader",
                           "fullMappingContextValidation": "not-performed",
                           "sameInputArtifact": inventory["word_id_policy"]["input_artifact"] == intake["identity"]["csvSha256"]},
            "clock": intake["clock"],
            "timingMeaning": "external-csv-record-onsets-transferred-through-exact-word-correspondence",
            "counts": {"records": len(ledger), "words": len(words), "excludedRecords": len(omitted),
                       "units": len(inventory["units"]), "unassignedWords": sum(w["membership"]["status"] == "unassigned" for w in words),
                       "missingWordOnsets": sum(w["onset"]["status"] == "missing" for w in words)},
            "records": ledger, "words": words,
            "units": [unit_summary(u, by_id) for u in inventory["units"]],
            "capabilities": {"scannerAlignment": intake["capabilities"]["scannerAlignment"],
                             "wordOffsets": intake["capabilities"]["wordOffsets"],
                             "temporalInterpolation": {"status": "unavailable", "reason": "no-exposure-policy"}}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, required=True)
    parser.add_argument("--lineage", type=Path, default=clock.REPO / "docs/data/sherlock/recall-lineage.json")
    parser.add_argument("--clock", choices=clock.CLOCKS, required=True)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--mapping-sha256", required=True)
    parser.add_argument("--recipe", choices=[RECIPE], required=True)
    parser.add_argument("--expect-source")
    parser.add_argument("--alias-id")
    parser.add_argument("--alias-map", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        require((args.alias_id is None) == (args.alias_map is None), "alias-requires-map-and-id")
        result = join(args.csv.read_bytes(), args.lineage.read_bytes(), args.clock, args.mapping.read_bytes(),
                      args.mapping_sha256, recipe=args.recipe, expected_source=args.expect_source,
                      alias_id=args.alias_id, alias_bytes=args.alias_map.read_bytes() if args.alias_map else None)
        result["producer"] = {"readerSha256": clock.digest(Path(__file__).read_bytes()),
                              "intakeSha256": clock.digest(Path(clock.__file__).read_bytes())}
        payload = clock.encode(result)
        clock.publish(args.out, payload)
        print(json.dumps({"schema": SCHEMA, "outputSha256": clock.digest(payload), **result["counts"]}, sort_keys=True))
        return 0
    except clock.IntakeError as error:
        print(json.dumps({"error": error.detail}, sort_keys=True), file=sys.stderr)
        return 2
    except OSError:
        print(json.dumps({"error": {"code": "io-error"}}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
