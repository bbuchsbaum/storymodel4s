#!/usr/bin/env python3
"""Read a v0.2 imported inspection workspace without deriving mapping authority."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re

from read_mapping import number, parse, require

HEX = re.compile(r"[0-9a-f]{64}\Z")


def digest(value):
    require(isinstance(value, str) and HEX.fullmatch(value), "invalid digest")
    return value


def sha(text):
    return hashlib.sha256(text.encode("utf-8", errors="strict")).hexdigest()


def measure_number(value):
    if isinstance(value, str) and re.fullmatch(r"0x[0-9a-f]{16}", value):
        return number(value)
    require(isinstance(value, str) and re.fullmatch(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?", value), "invalid decimal measure")
    result = float(value)
    require(math.isfinite(result), "nonfinite measure")
    return result


def role_key(role):
    require(isinstance(role, dict) and "kind" in role, "invalid archive role")
    if role["kind"] == "Mapping":
        require(set(role) == {"kind", "id"}, "mapping role fields")
        require(role["id"] in {"reference", "reconstruction"}, "unknown mapping role")
        return "Mapping:" + role["id"]
    require(set(role) == {"kind"}, "archive role fields")
    return role["kind"]


def archive(text):
    value = parse(text)
    require(set(value) == {"schemaVersion", "manifestVersion", "entries", "files"}, "archive fields")
    require(value["schemaVersion"] == "workspace-archive/v0.2" and value["manifestVersion"] == "workspace-manifest/v0.2", "workspace schema")
    paths = {}
    for item in value["files"]:
        require(set(item) == {"path", "utf8"} and isinstance(item["utf8"], str), "archive file")
        require(item["path"] not in paths, "duplicate archive path")
        paths[item["path"]] = item["utf8"]
    roles = {}
    for entry in value["entries"]:
        require(set(entry) == {"role", "path", "disposition"}, "archive entry")
        key = role_key(entry["role"])
        require(key not in roles and entry["path"] in paths, "duplicate or missing archive member")
        disposition = entry["disposition"]
        require(disposition.get("status") == "Supplied" and set(disposition) == {"status", "artifact"}, "unsupplied archive member")
        artifact = disposition["artifact"]
        require(set(artifact) == {"id", "role", "mediaType", "byteLength", "checksum"}, "artifact fields")
        require(artifact["id"] == entry["path"] and artifact["mediaType"] == "application/json", "artifact identity")
        body = paths[entry["path"]]
        require(artifact["byteLength"] == len(body.encode("utf-8")) and artifact["checksum"] == sha(body), "archive member hash")
        roles[key] = body
    required = {"SourceInput", "Recall", "Inventory", "Derivation", "Receipt", "Capabilities", "Mapping:reference", "Mapping:reconstruction"}
    require(set(roles) == required, "archive roles")
    require(len(value["entries"]) == len(paths) and
            {e["path"] for e in value["entries"]} == set(paths), "archive file set")
    capabilities = parse(roles["Capabilities"])
    require(capabilities["schemaVersion"] == "workspace-capabilities/v0.1" and capabilities["inspection"] == "Granted" and capabilities["export"] == "Granted", "workspace grants")
    entry_by_key = {role_key(e["role"]): e for e in value["entries"]}
    require(capabilities["model"] == entry_by_key["SourceInput"]["disposition"]["artifact"]["checksum"], "foreign capability source")
    require(capabilities["recall"] == entry_by_key["Recall"]["disposition"]["artifact"]["checksum"], "foreign capability recall")
    require(capabilities["declaration"] == entry_by_key["Receipt"]["disposition"]["artifact"]["checksum"], "foreign capability receipt")
    return roles


def stage_join(record, member):
    stages = {row["id"]: row for row in record["stage_assumption_receipts"]}
    require(len(stages) == len(record["stage_assumption_receipts"]), "duplicate stage")
    derived = [row for row in stages.values() if row["provenance"]["status"] == "derived"]
    require({row["stage"] for row in derived} == {"Inference", "Decision"}, "paired derived stages")
    bindings = []
    for row in derived:
        claim = row["provenance"]["receipt"]
        require(claim["stage"] == row["stage"], "foreign execution receipt")
        require(len(claim["inputs"]) == 3 and claim["inputs"][1:] ==
                [record["inventory"]["digest"], record["source"]["digest"]], "foreign stage inputs")
        bindings.append((digest(claim["config"]), digest(claim["inputs"][0])))
        require(claim["policy"] == f"mapping-run/v1/{member.capitalize()}/{row['stage']}", "foreign stage policy")
    for outcome in record["outcomes"]:
        if outcome["processing_status"]["status"] == "complete":
            refs = outcome["stages"]
            require(refs["status"] == "present", "complete outcome missing stages")
            required = {"inference": "Inference", "candidates": "Candidates", "decision": "Decision"}
            require(set(refs["value"]) == set(required) and all(
                stages.get(refs["value"][key], {}).get("stage") == expected
                for key, expected in required.items()), "foreign outcome stages")
            for link in outcome["links"]:
                require(stages[link["inference_stage_id"]]["stage"] == "Inference", "foreign link stage")
    require(len(bindings) == 2 and len(set(bindings)) == 1, "foreign execution receipt")
    return bindings[0]


def inspect(text, expected_recall_text_digest=None):
    roles = archive(text)
    source_input = parse(roles["SourceInput"])
    recall = parse(roles["Recall"])
    workspace_inventory = parse(roles["Inventory"])
    receipt = parse(roles["Receipt"])
    require(parse(roles["Derivation"]) == {"schemaVersion": "imported-mapping-claims/v1", "authority": "inspection-only"}, "imported authority description")
    require(receipt["producerRevision"] and re.fullmatch(r"[0-9a-f]{40}", receipt["producerRevision"]), "producer revision")
    require(receipt["sourceEdition"] == sha(source_input["artifact"]), "source edition")
    raw_texts = []
    def collect(value):
        if isinstance(value, dict):
            raw_texts.extend(v for k, v in value.items() if k in {"rawText", "raw_text"} and isinstance(v, str))
            for child in value.values(): collect(child)
        elif isinstance(value, list):
            for child in value: collect(child)
    collect(recall)
    expected_recall_text_digest = (
        sha("Anna arrived.") if expected_recall_text_digest is None
        else digest(expected_recall_text_digest)
    )
    require(len(raw_texts) == 1 and sha(raw_texts[0]) == expected_recall_text_digest,
            "unexpected recall text")
    disclosure = receipt["disclosure"]
    require(disclosure["schemaVersion"] == "recall-disclosure-receipt/v1" and disclosure["scope"] == "local-workspace", "disclosure scope")
    require(disclosure["textDigest"] == expected_recall_text_digest and
            disclosure["basis"]["kind"] in {"public-corpus-admitted", "certified-pseudonymized"},
            "disclosure text binding")
    documents = {name: parse(roles["Mapping:" + name]) for name in ("reference", "reconstruction")}
    inventory = documents["reference"]["inventory"]
    require(documents["reference"]["inventory"] == documents["reconstruction"]["inventory"] == inventory, "foreign mapping inventory")
    require(workspace_inventory["schemaVersion"] == "workspace-inventory/v0.1" and
            workspace_inventory["digest"] == inventory["digest"] and
            workspace_inventory["inputArtifact"] == inventory["word_id_policy"]["input_artifact"], "foreign inventory identity")
    require(workspace_inventory["words"] == [{"start": w["start"], "end": w["end_exclusive"]} for w in inventory["words"]], "foreign inventory words")
    unit_ids = [u["id"] for u in inventory["units"]]
    require(unit_ids == [u["id"] for u in recall["units"]] == [t["unit"] for t in workspace_inventory["timing"]], "foreign inventory units")
    require(all(t["clock"] == {"status": "untimed", "semantics": "declared-unit-presentation/v1"} for t in workspace_inventory["timing"]), "unexpected recall timing")
    require(inventory["transcript_checksum"] == recall["transcript"]["canonicalChecksum"] == sha(recall["transcript"]["canonicalText"]), "foreign canonical recall")
    for unit, recalled in zip(inventory["units"], recall["units"]):
        require(unit["ordinal"] == recalled["ordinal"] and unit["spans"] == [
            {"start": s["span"]["start"], "end_exclusive": s["span"]["end"],
             "unit": {"status": "present", "value": s["unit"]}} for s in recalled["span"]], "foreign unit spans")
    require(documents["reference"]["source"] == documents["reconstruction"]["source"], "foreign mapping source")
    members = receipt["members"]
    require([m["id"] for m in members] == ["reconstruction", "reference"], "mapping members")
    bindings = []
    for member in members:
        name = member["id"]
        body = roles["Mapping:" + name]
        record = documents[name]
        require(member["bytes"] == sha(body) and member["recordClaim"] == record["record_digest"], "mapping byte identity")
        require(record["inventory"] == inventory, "foreign inventory")
        expected = "NormalizedScoreMass" if name == "reference" else "ModelPosterior"
        for outcome in record["outcomes"]:
            allowed = {expected} if name == "reference" else {expected, "RawScore"}
            require(all(row["measure_kind"] in allowed for row in outcome["mapping_links"]), "conflated workspace measure kind")
            values = [measure_number(row["raw_value"]) for row in outcome["mapping_links"] if row["measure_kind"] == expected]
            family = "normalized" if name == "reference" else "posterior"
            measure = outcome["measures"][family]
            require(measure["status"] in {"present", "absent"}, "workspace measure presence")
            present = measure["status"] == "present"
            require(present == bool(values), "workspace measure alternatives presence")
            require(all(outcome["measures"][other]["status"] == "absent"
                        for other in {"normalized", "posterior", "transport"} - {family}), "workspace measure family")
            if present:
                require(all(v >= 0 for v in values) and math.isclose(math.fsum(values), 1.0, rel_tol=0, abs_tol=1e-12), "invalid normalized measure")
            decision = outcome["decision"]
            if decision["status"] == "present":
                require(present and decision["value"]["basis"]["measure_kind"] == expected, "workspace decision basis")
        bindings.append(stage_join(record, name))
    require(len(set(bindings)) == 1, "foreign paired execution")
    require([u["id"] for u in inventory["units"]] == [o["unit"] for o in documents["reference"]["outcomes"]], "missing reference outcome")
    require([u["id"] for u in inventory["units"]] == [o["unit"] for o in documents["reconstruction"]["outcomes"]], "missing reconstruction outcome")
    return {"roles": roles, "source": documents["reference"]["source"], "inventory": inventory, "receipt": receipt, "records": documents}


def fixture(path):
    value = parse(Path(path).read_text())
    require(set(value) == {"schemaVersion", "text", "timed", "textCohort", "timedCohort", "producerRevision"}
            and value["schemaVersion"] == "study-workspace-witness/v1", "witness metadata")
    packets = {}
    for kind in ("text", "timed"):
        cohort = [Path(p) for p in value[kind + "Cohort"]]
        require(len(cohort) == len(set(cohort)) == 2 and str(cohort[0]) == value[kind], "two-subject cohort")
        manifest = parse((cohort[0].parent / "manifest.json").read_text())
        require(manifest["schemaVersion"] == "study-workspaces/v1", "workspace root manifest")
        metadata = manifest["metadata"]
        outcomes = metadata["outcomes"]
        require({(o["participant"], o["session"]) for o in outcomes} ==
                {("fixture-a", "s1"), ("fixture-b", "s1")} and len(outcomes) == 2, "cohort denominator")
        require({o["workspace"] for o in outcomes} == {p.name for p in cohort}, "cohort packet join")
        require(len({o["observation"] for o in outcomes}) == 2, "participant observation identity")
        require(len(manifest["files"]) == 2, "cohort payload denominator")
        for index, packet in enumerate(cohort):
            entry = next((e for e in manifest["files"] if e["path"] == packet.name), None)
            require(entry is not None and entry["sha256"] == hashlib.sha256(packet.read_bytes()).hexdigest(), "workspace root hash")
            content = packet.read_text()
            decoded = inspect(content)
            require(decoded["receipt"]["sourceEdition"] == metadata["sourceEdition"], "cohort source reuse")
            outcome = next(o for o in outcomes if o["workspace"] == packet.name)
            require(outcome.get("failure") is None and outcome["readiness"] == "Ready", "cohort completion")
            require(outcome["units"] == [u["id"] for u in decoded["inventory"]["units"]], "cohort unit denominator")
            packets[kind if index == 0 else kind + "-second"] = content
    return packets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", type=Path, help="StudyWorkspaceWitness OUTPUT_DIR")
    args = parser.parse_args()
    packets = fixture(args.fixture / "workspace-fixtures.json")
    text, timed = inspect(packets["text"]), inspect(packets["timed"])
    require("Anna arrived. Bob left." in text["roles"]["SourceInput"], "text source control")
    support = json.dumps(timed["source"], sort_keys=True, separators=(",", ":"))
    require(any(target["level"] == 1 for target in timed["source"]["targets"]), "timed group target")
    require(all(v in support for v in ('"start_tick":"10"', '"end_exclusive_tick":"20"', '"start_tick":"30"', '"end_exclusive_tick":"40"')), "timed support")
    print(json.dumps({"status": "pass", "qualification": "archive and inspection-claim joins only; no mapping authority derived"}, sort_keys=True))

if __name__ == "__main__":
    main()
