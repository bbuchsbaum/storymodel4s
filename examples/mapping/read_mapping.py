#!/usr/bin/env python3
"""Inspect one mapping-exchange/v0.1 package using only Python's standard library.

Checks bytes, table joins and projections; does not replace Scala's contextual
derivation admission. Use --fixture-log for the independently authored controls.
"""
import argparse
import csv
import hashlib
import io
import json
import math
from pathlib import Path
import re
import struct


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def parse(text):
    return json.loads(text, object_pairs_hook=unique_object, parse_constant=lambda value: (_ for _ in ()).throw(ValueError(f"nonstandard JSON constant: {value}")))


def number(bits):
    require(re.fullmatch(r"0x[0-9a-f]{16}", bits), "invalid binary64 bits")
    value = struct.unpack(">d", bytes.fromhex(bits[2:]))[0]
    require(math.isfinite(value), "nonfinite mapping measure")
    return value


TABLES = {
    "words": ("inventory.words", ["id", "index", "start", "end_exclusive", "membership"]),
    "units": ("inventory.units", ["id", "ordinal", "spans", "words", "decomposition", "semantics"]),
    "targets": ("source.targets", ["target_id", "level", "parent", "axis_membership", "support_coverage", "propositional_scope"]),
    "target-support": ("source.targets", ["target_id", "support_status"]),
    "alternatives": ("outcomes.mapping_links", ["unit", "row_index", "measure_kind", "channel", "destination", "raw_value", "normalization_scope", "state"]),
    "measure-metadata": ("outcomes", ["unit", "measures"]),
    "decisions": ("outcomes", ["unit", "processing_status", "localization_status", "fidelity_assessment_status", "decision", "stages"]),
    "links": ("outcomes.links", ["unit", "row_index", "destination", "inference_stage_id", "candidate_set_id", "derivation", "gate_outcome", "fidelity_status", "fidelity_facets", "term_support"]),
    "stages": ("stage_assumption_receipts", ["id", "stage", "provenance"]),
}
INTEGER_FIELDS = {"index", "start", "end_exclusive", "ordinal", "level", "row_index"}
TEXT_FIELDS = {"id", "unit", "target_id", "semantics", "measure_kind", "destination", "normalization_scope", "localization_status", "inference_stage_id", "candidate_set_id", "stage"}


def cell_type(name):
    if name in INTEGER_FIELDS:
        return "decimal-integer"
    if name == "raw_value":
        return "ieee754-binary64-hex"
    if name == "mapping_digest" or name in TEXT_FIELDS:
        return "utf8-string"
    return "canonical-json"


def expected_rows(record, path):
    if path.startswith("outcomes."):
        member = path.split(".")[1]
        return [dict(value, unit=o["unit"], row_index=i)
                for o in record["outcomes"] for i, value in enumerate(o[member])]
    value = record
    for key in path.split("."):
        value = value[key]
    return value


def read_tsv(content):
    # The complete table is already in memory; csv's unrelated 128 KiB default
    # must not truncate an admitted unit's word list or a large support union.
    previous = csv.field_size_limit()
    try:
        csv.field_size_limit(max(previous, len(content)))
        return list(csv.reader(io.StringIO(content, newline=""), delimiter="\t", strict=True))
    finally:
        csv.field_size_limit(previous)


def read(files):
    manifest = parse(files["manifest.json"])
    require(set(manifest) == {"schemaVersion", "mapping_digest", "inventory_digest", "source_digest", "transcript_checksum", "segmentation_id", "policies", "roles", "capabilities", "files"}, "manifest fields")
    require(manifest["schemaVersion"] == "mapping-exchange/v0.1", "unsupported exchange schema")
    record = parse(files["mapping.json"])
    require(record["schemaVersion"] == "mapping-record/v0.1" and record["schema"] == "storymodel4s.mapping-record", "unsupported mapping schema")
    inventory, source = record["inventory"], record["source"]
    for key, value in {"mapping_digest": record["record_digest"], "inventory_digest": inventory["digest"], "source_digest": source["digest"], "transcript_checksum": inventory["transcript_checksum"], "segmentation_id": inventory["segmentation_id"], "policies": record["policies"], "roles": record["roles"]}.items():
        require(manifest[key] == value, f"foreign identity: {key}")
    expected_names = {"mapping.json"} | {name + ".tsv" for name in TABLES}
    entries = manifest["files"]
    require(len(entries) == len(expected_names), "file count")
    require({e["name"] for e in entries} == expected_names, "duplicate or missing manifest files")
    require(set(files) == expected_names | {"manifest.json"}, "partial or extra publication")
    loaded = {}
    for entry in entries:
        require(set(entry) == {"name", "sha256", "bytes", "format", "table"}, "file descriptor fields")
        name = entry["name"]
        content = files[name]
        raw = content.encode("utf-8", errors="strict")
        require(str(len(raw)) == entry["bytes"], f"size mismatch: {name}")
        require(hashlib.sha256(raw).hexdigest() == entry["sha256"], f"hash mismatch: {name}")
        if name == "mapping.json":
            require(entry["format"] == "mapping-record/v0.1" and entry["table"] == {"status": "not-applicable"}, "record descriptor")
            continue
        require(entry["format"] == "quoted-tsv/v1", "table format")
        path, columns = TABLES[name[:-4]]
        names = ["mapping_digest"] + columns
        descriptor = entry["table"]
        require(set(descriptor) == {"status", "columns", "rows"} and descriptor["status"] == "present", "table descriptor")
        require(descriptor["columns"] == [{"name": n, "type": cell_type(n)} for n in names], "column schema")
        parsed = read_tsv(content)
        require(parsed and parsed[0] == names, "TSV header")
        # Fix quoting and terminators independently from the producer implementation.
        canonical = io.StringIO(newline="")
        csv.writer(canonical, delimiter="\t", quoting=csv.QUOTE_ALL, lineterminator="\n").writerows(parsed)
        require(canonical.getvalue() == content, "noncanonical TSV quoting")
        projected = expected_rows(record, path)
        require(descriptor["rows"] == str(len(parsed) - 1) == str(len(projected)), "row count")
        typed = []
        for cells, expected in zip(parsed[1:], projected):
            require(len(cells) == len(names), "ragged TSV")
            row = dict(zip(names, cells))
            require(row.pop("mapping_digest") == record["record_digest"], "foreign row identity")
            for column in columns:
                kind = cell_type(column)
                if kind == "canonical-json":
                    row[column] = parse(row[column])
                elif kind == "decimal-integer":
                    require(re.fullmatch(r"0|-?[1-9][0-9]*", row[column]), "noncanonical integer")
                    row[column] = int(row[column])
                elif kind == "ieee754-binary64-hex":
                    number(row[column])
                require(row[column] == expected[column], f"table differs from record: {name}/{column}")
            typed.append(row)
        loaded[name[:-4]] = typed
    units = [u["id"] for u in loaded["units"]]
    require(len(set(units)) == len(units), "duplicate unit")
    require([r["unit"] for r in loaded["decisions"]] == units, "missing outcome")
    targets = [t["target_id"] for t in loaded["targets"]]
    require(len(set(targets)) == len(targets), "duplicate target")
    require([r["target_id"] for r in loaded["target-support"]] == targets, "missing support status")
    require(set(record["policies"]["target_universe_id"]["targets"]) <= set(targets), "foreign declared target")
    for row in loaded["alternatives"] + loaded["links"]:
        require(row["unit"] in units, "foreign unit")
        require(row["destination"] in targets or row["destination"] in {"ext:" + s for s in ("Association", "Commentary", "SourceConsistentInference", "Intrusion", "Uninterpretable", "Unranked")}, "foreign destination")
    words = {w["id"]: w for w in loaded["words"]}
    require(len(words) == len(loaded["words"]), "duplicate word")
    assigned = {}
    for unit in loaded["units"]:
        for word in unit["words"]:
            require(word in words and word not in assigned, "foreign or multiply assigned word")
            assigned[word] = unit["id"]
    for word, value in words.items():
        membership = value["membership"]
        if membership["status"] == "member":
            require(membership["unit"] in units and assigned.get(word) == membership["unit"], "word membership join")
        else:
            require(membership["status"] == "unassigned" and word not in assigned, "unassigned word join")
    stages = {s["id"] for s in loaded["stages"]}
    require(len(stages) == len(loaded["stages"]), "duplicate stage ID")
    for row in loaded["decisions"]:
        if row["stages"]["status"] == "present":
            require(set(row["stages"]["value"].values()) <= stages, "dangling outcome stage")
    for row in loaded["links"]:
        require(row["inference_stage_id"] in stages, "dangling link stage")
    for row in loaded["measure-metadata"]:
        metadata = row["measures"]
        stage_ids = [r["stage"] for r in metadata["raw"]]
        stage_ids += [metadata[k]["value"]["stage"] for k in ("normalized", "transport", "posterior") if metadata[k]["status"] == "present"]
        require(set(stage_ids) <= stages, "dangling measure stage")
    alternative_keys = [(r["unit"], r["measure_kind"], json.dumps(r["channel"], sort_keys=True), r["destination"], json.dumps(r["state"], sort_keys=True)) for r in loaded["alternatives"]]
    require(len(set(alternative_keys)) == len(alternative_keys), "duplicate semantic alternative")
    def exact_ticks(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key in {"tick", "start_tick", "end_exclusive_tick", "numerator", "denominator"}:
                    require(isinstance(item, str) and re.fullmatch(r"0|-?[1-9][0-9]*", item), "inexact tick/rational")
                    require(-(2**63) <= int(item) < 2**63, "tick outside Int64")
                exact_ticks(item)
        elif isinstance(value, list):
            for item in value:
                exact_ticks(item)
    exact_ticks(source)
    capabilities = {"mapping": {"status": "present"}, **{k: {"status": "unavailable", "reason": "not-supplied-to-base-exchange"} for k in ("recall_timing", "temporal_projection", "scanner_alignment", "organization")}, "calibration": {"status": "unavailable", "reason": "not-admitted-by-mapping-record/v0.1"}, "paired_profiles": {"status": "unavailable", "reason": "single-record-component"}}
    require(manifest["capabilities"] == capabilities, "unsupported capability claim")
    return record, loaded


def fixture_answers(name, record, tables):
    if name in {"raw", "normalized"}:
        decisions = {r["unit"]: r for r in tables["decisions"]}
        require(len(decisions) == 8 and decisions["p6"]["processing_status"]["status"] == "failed", "failed-unit accounting")
        require(sum(w["membership"]["status"] == "unassigned" for w in tables["words"]) == 7, "unassigned words")
        support = {r["target_id"]: r["support_status"] for r in tables["target-support"]}
        e2 = support["sit:e2"]["source_support"]["anchors"][0]
        interval = e2["intervals"][0]
        start, end = int(interval["start_tick"]), int(interval["end_exclusive_tick"])
        require((start, end) == (10, 20) and start <= 10 < end and not start <= 20 < end, "half-open support")
        e8 = support["sit:e8"]["source_support"]["anchors"][0]
        require(e8["kind"] == "media-point" and int(e8["tick"]) == 30, "point atom")
        require(support["sit:e4"]["status"] == "unlocated", "unlocated support")
        if name == "raw":
            scores = {r["destination"]: number(r["raw_value"]) for r in tables["alternatives"] if r["unit"] == "p3"}
            require(scores == {"sit:e3": 0.9, "sit:e7": 0.4}, "authored raw scores")
            require(decisions["p3"]["decision"]["value"]["decoded_target_id"]["value"] == "sit:e7", "decoded choice differs from argmax")
        else:
            for unit in decisions:
                rows = [r for r in tables["alternatives"] if r["unit"] == unit]
                if unit == "p6":
                    require(not rows, "failed unit gained mass")
                    continue
                require(all(r["measure_kind"] == "NormalizedScoreMass" for r in rows), "wrong measure kind")
                mass = {r["destination"]: number(r["raw_value"]) for r in rows}
                require(mass == {"sit:e2": 0.5, "sit:e4": 0.25, "ext:Intrusion": 0.25}, "authored normalized mass")
                require(sum(mass.values()) == 1.0, "supplied total")
    elif name == "large-ticks":
        encoded = json.dumps(tables["target-support"])
        require("9007199254740993" in encoded, "lost exact large tick")


def envelope(files, schema):
    """Check a flat completion-marker envelope before interpreting its owned members."""
    require("manifest.json" in files, "incomplete publication")
    manifest = parse(files["manifest.json"])
    require(set(manifest) == {"schemaVersion", "metadata", "files"}, "envelope fields")
    require(manifest["schemaVersion"] == schema, "unsupported envelope schema")
    names = []
    for entry in manifest["files"]:
        require(set(entry) in ({"path", "sha256"}, {"path", "sha256", "bytes"}), "envelope descriptor")
        name = entry["path"]
        require(isinstance(name, str) and re.fullmatch(r"[a-zA-Z0-9._-]+", name)
                and name not in {".", "..", "manifest.json"}, "unsafe envelope path")
        require(name in files and name not in names, "duplicate or missing payload")
        names.append(name)
        raw = files[name].encode("utf-8", errors="strict")
        require(hashlib.sha256(raw).hexdigest() == entry["sha256"], "envelope hash mismatch")
        if "bytes" in entry:
            require(entry["bytes"] == str(len(raw)), "envelope byte count")
    require(set(files) == set(names) | {"manifest.json"}, "partial or extra publication")
    return manifest["metadata"]


def digest(value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value), "invalid digest")
    return value


def read_pair(files):
    metadata = envelope(files, "mapping-exchange-pair/v1")
    require(set(metadata) == {"execution", "sharedEvidence", "inventory", "source", "members"}, "pair metadata")
    for key in ("execution", "sharedEvidence", "inventory", "source"):
        digest(metadata[key])
    require([m["member"] for m in metadata["members"]] == ["reference", "reconstruction"], "pair members")
    loaded = {}
    expected_files = {"manifest.json"}
    for member in metadata["members"]:
        require(set(member) == {"member", "profile", "record", "manifest"}, "member descriptor")
        name = member["member"]
        prefix = name + "--"
        supplied = {p[len(prefix):]: text for p, text in files.items() if p.startswith(prefix)}
        record, tables = read(supplied)
        expected_files |= {prefix + p for p in supplied}
        require(hashlib.sha256(supplied["manifest.json"].encode("utf-8")).hexdigest() == member["manifest"], "member manifest digest")
        require(record["record_digest"] == member["record"], "member record digest")
        require(record["inventory"]["digest"] == metadata["inventory"], "foreign pair inventory")
        require(record["source"]["digest"] == metadata["source"], "foreign pair source")
        require(record["policies"]["decision_policy_id"] == {"status": "declared", "id": "mapping-run:" + digest(member["profile"])}, "foreign member profile")
        derived = [s for s in tables["stages"] if s["provenance"]["status"] == "derived"]
        require({s["stage"] for s in derived} == {"Inference", "Decision"} and len(derived) == 2, "missing executed stages")
        for stage in derived:
            receipt = stage["provenance"]["receipt"]
            require(receipt["stage"] == stage["stage"] and receipt["config"] == metadata["execution"], "foreign execution receipt")
            require(receipt["inputs"] == [metadata["sharedEvidence"], metadata["inventory"], metadata["source"]], "foreign originating evidence")
            require(receipt["policy"] == f"mapping-run/v1/{name.capitalize()}/{stage['stage']}", "foreign member authority")
        expected_kind = "NormalizedScoreMass" if name == "reference" else "ModelPosterior"
        allowed_kinds = {expected_kind} if name == "reference" else {expected_kind, "RawScore"}
        require(all(r["measure_kind"] in allowed_kinds for r in tables["alternatives"]), "conflated paired measure kind")
        for unit in tables["units"]:
            values = [number(r["raw_value"]) for r in tables["alternatives"] if r["unit"] == unit["id"] and r["measure_kind"] == expected_kind]
            if values:
                require(all(v >= 0 for v in values) and abs(math.fsum(values) - 1) <= 1e-12, "invalid paired mass")
        loaded[name] = (record, tables)
    require(set(files) == expected_files, "unknown paired payload")
    reference, reconstruction = loaded["reference"][0], loaded["reconstruction"][0]
    require(reference["inventory"] == reconstruction["inventory"], "different paired inventories")
    require(reference["source"] == reconstruction["source"], "different paired sources")
    return metadata, loaded


def check_run(metadata, files):
    required = {"run", "registration", "execution", "sharedEvidence", "config", "readiness", "members"}
    require(set(metadata) == required, "run metadata")
    prefix = digest(metadata["run"]) + "--"
    pair_files = {p[len(prefix):]: text for p, text in files.items() if p.startswith(prefix)}
    pair, loaded = read_pair(pair_files)
    digest(metadata["registration"])
    require(metadata["execution"] == pair["execution"] and metadata["sharedEvidence"] == pair["sharedEvidence"], "foreign run execution")
    require(metadata["members"] == [
        {"member": name.capitalize(), "kind": "NormalizedScoreMass" if name == "reference" else "ModelPosterior",
         "profile": member["profile"], "record": member["record"]}
        for name, member in zip(("reference", "reconstruction"), pair["members"])], "run member join")
    return loaded, {prefix + p for p in pair_files}


def read_publication(files):
    schema = parse(files.get("manifest.json", "{}" )).get("schemaVersion")
    if schema == "mapping-exchange-pair/v1":
        metadata, loaded = read_pair(files)
        return {"schema": schema, "pairs": [loaded], "outcomes": [], "metadata": metadata}
    require(schema in {"mapping-exchange-run/v1", "mapping-exchange-batch/v1"}, "unsupported publication schema")
    metadata = envelope(files, schema)
    if schema == "mapping-exchange-run/v1":
        loaded, names = check_run(metadata, files)
        require(set(files) == names | {"manifest.json"}, "unexpected run payload")
        return {"schema": schema, "pairs": [loaded], "outcomes": [], "metadata": metadata}
    require(set(metadata) == {"plan", "sourceEdition", "config", "attempts", "outcomes"}, "batch metadata")
    digest(metadata["plan"])
    digest(metadata["sourceEdition"])
    require(len(metadata["attempts"]) == len(metadata["outcomes"]), "attempt denominator")
    for attempt in metadata["attempts"]:
        digest(attempt)
    names, identities, loaded_pairs = {"manifest.json"}, set(), []
    for outcome in metadata["outcomes"]:
        key = (outcome["participant"], outcome["session"])
        require(key not in identities, "duplicate participant/session")
        identities.add(key)
        observation = outcome.get("observation")
        prepared = None
        if observation is not None:
            recall_name = digest(observation) + "-recall.json"
            require(recall_name in files, "missing prepared recall")
            prepared = parse(files[recall_name])
            require(prepared["schemaVersion"] in {"prepared-recall/v1", "prepared-recall/v2"}, "prepared recall schema")
            if prepared["schemaVersion"] == "prepared-recall/v2":
                disclosure = prepared["disclosure"]
                require(disclosure["schemaVersion"] == "recall-disclosure-receipt/v1"
                        and disclosure["scope"] == "local-workspace", "recall disclosure description")
                # This validates a wire claim; it does not admit disclosure authority.
                require(disclosure["basis"]["kind"] in {"local-only", "public-corpus-admitted", "certified-pseudonymized"}, "recall disclosure basis")
                require(disclosure["textDigest"] == hashlib.sha256(
                    prepared["graph"]["transcript"]["rawText"].encode()).hexdigest(), "recall disclosure text")
            require((prepared["participant"], prepared["session"]) == key and prepared["observation"] == observation, "foreign recall observation")
            require(prepared["sourceEdition"] == metadata["sourceEdition"], "foreign recall source edition")
            names.add(recall_name)
        if outcome["execution"] == "failed":
            require(outcome.get("failure") is not None and outcome.get("mapping") is None
                    and outcome.get("run") is None and outcome.get("registration") is None
                    and outcome["readiness"] == "not-executed"
                    and outcome["structuralValidity"] == "not-produced", "failed outcome masquerades as success")
            expected_units = [] if prepared is None else [
                {"unit": u["id"], "reference": "Failed", "reconstruction": "Failed"}
                for u in prepared["graph"]["units"]]
            require(outcome["units"] == expected_units, "failed unit denominator or status")
            continue
        require(outcome["execution"] == "complete" and outcome["structuralValidity"] == "checked"
                and outcome.get("failure") is None and prepared is not None, "invalid complete outcome")
        run = outcome["mapping"]
        require(run["config"] == metadata["config"] and run["run"] == outcome["run"]
                and run["registration"] == outcome["registration"] and run["readiness"] == outcome["readiness"], "foreign batch run")
        pair, payloads = check_run(run, files)
        names |= payloads
        require(pair["reference"][0]["inventory"]["digest"] == prepared["inventory"], "foreign prepared inventory")
        require([u["unit"] for u in outcome["units"]] == [u["id"] for u in pair["reference"][1]["units"]], "unit outcome denominator")
        for name in ("reference", "reconstruction"):
            for summary, decision in zip(outcome["units"], pair[name][1]["decisions"]):
                require(summary[name] == decision["processing_status"]["status"].capitalize(), "unit outcome status")
        loaded_pairs.append(pair)
    require(set(files) == names, "unaccounted batch payload")
    return {"schema": schema, "pairs": loaded_pairs, "outcomes": metadata["outcomes"], "metadata": metadata}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--directory", type=Path)
    group.add_argument("--fixture-log", type=Path)
    args = parser.parse_args()
    if args.directory:
        files = {}
        for path in args.directory.iterdir():
            require(path.is_file() and not path.is_symlink(), "unsupported directory entry")
            with path.open(encoding="utf-8", newline="") as stream:
                files[path.name] = stream.read()
        if parse(files.get("manifest.json", "{}" )).get("schemaVersion") == "mapping-exchange/v0.1":
            record, tables = read(files)
            result = {"mapping": record["record_digest"], "units": len(tables["units"])}
        else:
            publication = read_publication(files)
            result = {"schema": publication["schema"], "pairs": len(publication["pairs"]), "attempts": len(publication["outcomes"])}
        print(json.dumps({"status": "pass", **result, "qualification": "package/schema/projection checks; not contextual derivation admission"}))
    else:
        values = {}
        for line in args.fixture_log.read_text().splitlines():
            if line.startswith("MAPPING_EXCHANGE_FIXTURE\t"):
                _, name, content = line.split("\t", 2)
                require(name not in values, "duplicate fixture")
                values[name] = parse(content)
        require(set(values) == {"raw", "normalized", "large-ticks"}, "missing fixture output")
        for name, files in values.items():
            fixture_answers(name, *read(files))
        print(json.dumps({"status": "pass", "fixtures": sorted(values), "knownAnswers": "raw scores, normalized mass, outcomes, half-open support and exact points/ticks"}))


if __name__ == "__main__":
    main()
