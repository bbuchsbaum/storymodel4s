#!/usr/bin/env python3
"""Exercise the real JVM command and independently read its synthetic exports."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

from read_mapping import read


def sha(data):
    return hashlib.sha256(data).hexdigest()


def check(classpath, workspace_path, evidence):
    evidence.mkdir(parents=True, exist_ok=False)
    workspace = json.loads(workspace_path.read_text(encoding="utf-8"))
    source_files = {f["path"]: f["utf8"] for f in workspace["files"]}
    mappings = {
        e["role"]["id"]: source_files[e["path"]]
        for e in workspace["entries"] if e["role"]["kind"] == "Mapping"
    }
    assert {"authored-a", "authored-b"} <= mappings.keys()
    command = ["java", "-cp", classpath.read_text().strip(),
               "storymodel4s.pipeline.mappingExchangeExport"]
    receipts = []

    def invoke(name, args, expected_exit, error=None, reason=None):
        result = subprocess.run(command + list(map(str, args)), capture_output=True, timeout=90)
        (evidence / (name + ".stdout")).write_bytes(result.stdout)
        (evidence / (name + ".stderr")).write_bytes(result.stderr)
        receipts.append({"case": name, "args": list(map(str, args)),
                         "exit_code": result.returncode,
                         "stdout_sha256": sha(result.stdout), "stderr_sha256": sha(result.stderr)})
        assert result.returncode == expected_exit, (name, result.returncode, result.stderr)
        if expected_exit:
            assert result.stdout == b"", (name, "failure printed stdout")
            # JVM warnings may precede the command's sole JSON diagnostic.
            lines = [line for line in result.stderr.decode().splitlines() if line.startswith("{")]
            assert len(lines) == 1, (name, lines)
            diagnostic = json.loads(lines[0])
            assert diagnostic["schemaVersion"] == "workspace-mapping-export-receipt/v0.1"
            assert diagnostic["status"] == "refused" and diagnostic["error"] == error
            if reason:
                assert diagnostic["reason"] == reason
            for arg in args:
                if len(str(arg)) > 20:
                    assert str(arg) not in lines[0], (name, "argument echoed")
            return None
        return result.stdout

    for policy in ["authored-a", "authored-b"]:
        output = evidence / policy
        raw = invoke(policy, [workspace_path, policy, output], 0)
        receipt = json.loads(raw)
        assert receipt["schemaVersion"] == "workspace-mapping-export-receipt/v0.1"
        assert receipt["status"] == "complete" and receipt["policy_id"] == policy
        assert receipt["input_sha256"] == sha(workspace_path.read_bytes())
        assert receipt["manifest_sha256"] == sha((output / "manifest.json").read_bytes())
        assert (output / "mapping.json").read_bytes() == mappings[policy].encode("utf-8")
        files = {p.name: p.read_text(encoding="utf-8") for p in output.iterdir()}
        record, tables = read(files)
        assert receipt["mapping_digest"] == record["record_digest"]
        receipts[-1].update(mapping=record["record_digest"], units=len(tables["units"]))

    existing = evidence / "authored-b"
    old = {p.name: p.read_bytes() for p in existing.iterdir()}
    invoke("existing", [workspace_path, "authored-b", existing], 2, "OutputExists")
    assert old == {p.name: p.read_bytes() for p in existing.iterdir()}
    absent = evidence / "unknown-output"
    invoke("unknown", [workspace_path, "unknown-policy", absent], 2,
           "WorkspaceRefused", "IncompatiblePolicy")
    assert not absent.exists()

    # This is a newly checksummed, inspectable archive with an actual denied grant.
    denied = json.loads(json.dumps(workspace))
    entry = next(e for e in denied["entries"] if e["role"]["kind"] == "Capabilities")
    item = next(f for f in denied["files"] if f["path"] == entry["path"])
    declaration = json.loads(item["utf8"])
    declaration["export"] = "Denied"
    text = json.dumps(declaration, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    item["utf8"] = text
    entry["disposition"]["artifact"].update(checksum=sha(text.encode()), byteLength=len(text.encode()))
    denied_path = evidence / "inspection-only.workspace.json"
    denied_path.write_text(json.dumps(denied, separators=(",", ":")), encoding="utf-8")
    denied_output = evidence / "denied-output"
    invoke("denied", [denied_path, "authored-b", denied_output], 2,
           "WorkspaceRefused", "PermissionDenied")
    assert not denied_output.exists()

    malformed = evidence / "invalid-utf8.json"
    malformed.write_bytes(b"\xc3\x28")
    malformed_output = evidence / "malformed-output"
    invoke("malformed", [malformed, "authored-b", malformed_output], 2, "InputRead")
    assert not malformed_output.exists()
    invoke("arguments", ["sensitive argument"], 2, "Arguments")
    invoke("invalid-policy", [workspace_path, "invalid policy", evidence / "invalid"],
           2, "InvalidPolicy")
    assert not (evidence / "invalid").exists()
    assert invoke("help", ["--help"], 0).decode().strip() == "mappingExchangeExport WORKSPACE POLICY OUTPUT"
    result = {"status": "pass", "command": command,
              "classpath_sha256": sha(classpath.read_bytes()),
              "workspace_sha256": sha(workspace_path.read_bytes()), "cases": receipts}
    (evidence / "process-receipt.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"status": "pass", "process_cases": len(receipts),
                      "independent_packages": 2, "receipt": str(evidence / "process-receipt.json")}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath", type=Path, required=True)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    check(args.classpath, args.workspace, args.evidence)
