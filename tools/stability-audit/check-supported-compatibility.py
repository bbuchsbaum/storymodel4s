#!/usr/bin/env python3
"""Refuse removal or signature changes of exact supported declarations and members.

This source-signature check complements whole-artifact MiMa. It does not filter binary errors,
interpret JVM encodings, or automatically classify newly added members as supported.
"""
import json
import subprocess
import sys
from pathlib import Path


def main(baseline_path: str, manifest_path: str, candidate_path: str) -> int:
    validator = Path(__file__).with_name("validate-supported-closure.py")
    checked = subprocess.run([sys.executable, str(validator), baseline_path, manifest_path], capture_output=True, text=True)
    if checked.returncode:
        print(checked.stdout + checked.stderr, file=sys.stderr)
        return 1
    manifest = json.loads(Path(manifest_path).read_text())
    candidate = json.loads(Path(candidate_path).read_text())
    if candidate.get("unresolved") != []:
        raise SystemExit("supported-compatibility: candidate contains unresolved symbols")
    current = {
        "nominals": set(candidate["nominals"]),
        "members": {member["id"] for member in candidate["members"]},
    }
    missing = {kind: sorted(identity for identity, status in manifest[kind].items() if status == "stable" and identity not in current[kind]) for kind in current}
    if any(missing.values()):
        print(json.dumps({"status": "incompatible", "missingSupported": missing}, indent=2))
        return 1
    print("supported-compatibility: exact supported signatures retained")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("usage: check-supported-compatibility.py BASELINE.json DISPOSITIONS.json CANDIDATE.json")
    sys.exit(main(*sys.argv[1:]))
