#!/usr/bin/env python3
"""Demonstrate that the independent check rejects three consequential report faults."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--data-root", type=Path, required=True)
parser.add_argument("--out", type=Path, required=True)
args = parser.parse_args()
out = args.out.resolve()
target = out / "oracle-faults"
target.mkdir(exist_ok=False)
checker = Path(__file__).with_name("check_readout.py")
receipts = []
for fault, error in [("pooled-as-primary", "rate mismatch"),
                     ("missing-gold-as-zero", "missing gold became a score"),
                     ("omitted-participant", "missing reported participant")]:
    case = target / fault
    case.mkdir()
    for name in ["support.json", "A.json", "AV.json", "A-self.json", "A-AV.json", "summary.json"]:
        shutil.copyfile(out / name, case / name)
    (case / "A").symlink_to(out / "A", target_is_directory=True)
    changed = case / ("summary.json" if fault == "pooled-as-primary" else "A-AV.json")
    doc = json.loads(changed.read_text())
    if fault == "pooled-as-primary":
        arm = doc["arms"]["A"]
        assert arm["participantAverageSceneExactPercent"] != arm["pooledSceneExactPercent"]
        arm["participantAverageSceneExactPercent"] = arm["pooledSceneExactPercent"]
    elif fault == "missing-gold-as-zero":
        row = next(p for p in doc["participants"] if p["goldEligibleUnits"] == 0)
        assert row["arms"][0]["sceneExact"] is None
        row["arms"][0]["sceneExact"] = 0.0
    else:
        doc["participants"].pop()
    changed.write_text(json.dumps(doc, indent=2) + "\n")
    command = [sys.executable, str(checker), "--data-root", str(args.data_root.resolve()), "--out", str(case)]
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    (case / "check.log").write_bytes(result.stdout)
    if result.returncode == 0 or error.encode() not in result.stdout or (case / "independent-check.json").exists():
        raise RuntimeError("oracle did not reject " + fault + " for the expected reason")
    receipts.append({"fault": fault, "command": command, "exitCode": result.returncode,
                     "expectedError": error, "logSha256": hashlib.sha256(result.stdout).hexdigest(),
                     "changedInputSha256": hashlib.sha256(changed.read_bytes()).hexdigest()})
(out / "oracle-faults.json").write_text(json.dumps({
    "kind": "data corruption witnesses, not production algorithm mutations",
    "positiveControl": "independent-check.json", "faultsRejected": receipts,
    "scriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
}, indent=2) + "\n")
print("Three injected report faults rejected; original outputs untouched.")
