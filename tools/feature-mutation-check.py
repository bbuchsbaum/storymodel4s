#!/usr/bin/env python3
"""Run compiling counterexamples to the feature view contract, restoring every source exactly.

Usage: python3 tools/feature-mutation-check.py --grakern /path/to/grakern --out /path/to/evidence
Run only in an isolated checkout with no concurrent sbt process. Logs bind the source hashes,
HEAD, command, real exit and test totals. A compilation error is not a mutation kill.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--grakern", required=True)
parser.add_argument("--out", required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
out = Path(args.out).resolve()
out.mkdir(parents=True, exist_ok=False)
head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
feature = "view/src/main/scala/storymodel4s/view/feature.scala"
mutants = [
    ("ignore-basis", "view/src/main/scala/storymodel4s/view/compiler.scala",
     "basis.map(BasisId.fromChecksum)", "None", "viewJVM/testOnly *FeatureSelectionSuite"),
    ("drop-missing", feature,
     "FeaturePlanner.atScale(model, scene.featureLayer.scale, o.target)",
     "o.estimate.isObserved && FeaturePlanner.atScale(model, scene.featureLayer.scale, o.target)",
     "codecJVM/testOnly *FeatureMaterializerSuite"),
    ("drop-coverage", feature, "o.coverage,", "None,", "codecJVM/testOnly *FeatureMaterializerSuite"),
    ("launder-circularity", feature, "else FeatureCircularity.NotAssessed", "else FeatureCircularity.NotAggregate",
     "codecJVM/testOnly *FeatureMaterializerSuite"),
    ("ignore-source", feature, "track.provenance.storyChecksum.contains(model.source.canonicalChecksum)", "true",
     "codecJVM/testOnly *FeatureMaterializerSuite"),
    ("ignore-support", feature, "o.support.contains(s)", "true", "codecJVM/testOnly *FeatureMaterializerSuite"),
]
receipts = []
for name, filename, original, replacement, task in mutants:
    path = root / filename
    before = path.read_bytes()
    source = before.decode()
    if source.count(original) != 1:
        raise RuntimeError(f"{name}: expected one mutation site, found {source.count(original)}")
    command = ["sbt", "-batch", f"-Dstorymodel4s.grakern.build={args.grakern}", task]
    mutated = source.replace(original, replacement).encode()
    receipt = dict(name=name, head=head, file=filename, command=command,
                   original_sha256=hashlib.sha256(before).hexdigest(),
                   mutant_sha256=hashlib.sha256(mutated).hexdigest())
    try:
        path.write_bytes(mutated)
        result = subprocess.run(command, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        output = result.stdout
        totals = re.findall(r"Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)", output)
        killed = (result.returncode != 0 and "Compilation failed" not in output and
                  any(int(f) > 0 and int(e) == 0 and int(p) > 0 for _, f, e, p in totals))
        receipt.update(exit=result.returncode, totals=totals, killed=killed)
        (out / f"{name}.log").write_text(json.dumps(receipt) + "\n" + output)
    finally:
        path.write_bytes(before)
        if path.read_bytes() != before:
            raise RuntimeError(f"{name}: source restoration failed")
    receipts.append(receipt)
    (out / "receipts.json").write_text(json.dumps(receipts, indent=2) + "\n")
    print(json.dumps(receipt), flush=True)
    if not killed:
        raise SystemExit(f"{name} survived or failed without discriminating test evidence")
