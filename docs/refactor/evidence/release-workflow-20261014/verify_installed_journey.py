#!/usr/bin/env python3
"""Replay the documented ordinary-file control through an extracted CLI bundle."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

repo = Path(__file__).resolve().parents[4]
bundle, root = map(lambda x: Path(x).resolve(), sys.argv[1:])
root.mkdir(parents=True, exist_ok=False)
commands = []

def run(args, status=0):
    result = subprocess.run(args, cwd=root, text=True, capture_output=True)
    commands.append({"arguments": [str(x) for x in args], "exit": result.returncode,
                     "stdout": result.stdout, "stderr": result.stderr})
    (root / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
    assert result.returncode == status, (args, result.returncode, result.stderr)
    return result.stdout

cli = ["sh", str(bundle / "bin/storymodel4s")]
(root / "source.txt").write_text("Anna arrived. Bob left.")
(root / "recall.txt").write_text("Anna arrived.")
study = {"schemaVersion": "storymodel-study/v1", "source": {
    "format": "text", "path": "source.txt", "kind": "Sentence"},
    "profile": "recall-baseline-lexical-words/v1", "recalls": [{
    "participant": "synthetic", "session": "one", "path": "recall.txt",
    "timing": None, "admission": {"kind": "project-synthetic-control"}}]}
(root / "study.json").write_text(json.dumps(study, indent=2) + "\n")
(root / "mapping.json").write_text(run(cli + ["mappingRun", "template"]))
for command, destination in [("run", "analysis"), ("exchange", "exchange")]:
    summary = json.loads(run(cli + ["mappingRun", command, "study.json", "mapping.json", destination]))
    assert (summary["attempts"], summary["completed"], summary["failed"]) == (1, 1, 0), summary

def hashes(directory):
    return {str(p.relative_to(directory)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(directory.rglob("*")) if p.is_file()}

before = hashes(root / "exchange")
run(cli + ["mappingRun", "exchange", "study.json", "mapping.json", "exchange", "--resume"])
assert hashes(root / "exchange") == before
run(cli + ["mappingRun", "exchange", "study.json", "mapping.json", "exchange"], 2)
assert hashes(root / "exchange") == before
base_readback = run([sys.executable, str(repo / "examples/mapping/read_mapping.py"),
                     "--directory", "exchange"])
run(cli + ["mappingWeight", "study.json", "mapping.json", "0/1,1/2,1/1", "weighted"])
weighted_readback = run([sys.executable, str(repo / "examples/mapping/read_weighted_mapping.py"), "weighted"])
receipt = {"source": "2c4b74f9ef069e1bc1f6678f8dababe2421ca929",
    "distribution_manifest_sha256": hashlib.sha256((bundle / "manifest.json").read_bytes()).hexdigest(),
    "control": "existing PublicMappingFacade.scala exact text", "attempts": 1, "completed": 1,
    "failed": 0, "resume_identical": True, "overwrite_exit": 2,
    "exchange_readback": base_readback, "weighted_readback": weighted_readback,
    "exchange_files": before, "weighted_files": hashes(root / "weighted"),
    "command_count": len(commands), "command_receipt_sha256": hashlib.sha256((root / "commands.json").read_bytes()).hexdigest()}
(root / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
print(json.dumps({k:v for k,v in receipt.items() if k not in ("exchange_files", "weighted_files")}, indent=2))
