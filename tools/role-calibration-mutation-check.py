#!/usr/bin/env python3
"""Run compiling counterexamples to the participant calibration contract, restoring every source exactly.

Usage: python3 tools/role-calibration-mutation-check.py --grakern /path/to/grakern --out /path/to/evidence
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
filename = "document/src/main/scala/storymodel4s/document/calibration.scala"
task = "documentJVM/testOnly *ParticipantCalibrationSuite"
mutants = [
    ("source-alias-leak", [("groups(item.storyGroup) || sources(item.source)", "groups(item.storyGroup)")]),
    ("story-group-leak", [("groups(item.storyGroup) || sources(item.source)", "sources(item.source)")]),
    ("pool-roles", [("Cell(item.role, item.score)", "Cell(ParticipantRole.Agent, item.score)"),
                    ("Cell(i.role, i.score)", "Cell(ParticipantRole.Agent, i.score)")]),
    ("pool-scorers", [("Cell(item.role, item.score)", "Cell(item.role, RawScore.unsafe(item.score.value, ScorerId.unsafe(\"pooled\")))"),
                      ("Cell(i.role, i.score)", "Cell(i.role, RawScore.unsafe(i.score.value, ScorerId.unsafe(\"pooled\")))")]),
    ("unresolved-as-negative", [("case Verdict.Unresolved(_) => false", "case Verdict.Unresolved(_) => true")]),
    ("held-out-training-leak", [("filter(_._1.storyGroup != group)", "filter(_._1.storyGroup == group)")]),
    ("remove-declared-prior", [("(correct.toDouble + 1.0) / (rows.size.toDouble + 2.0)", "correct.toDouble / rows.size.toDouble")]),
    ("omit-adjudication-protocol", [("j.protocol.hex", "\"omitted\"")]),
    ("structural-chart-identity", [("chartKey(e.chart)", "storymodel4s.proposition.Canonical.checksum(e.chart).hex")]),
    ("omit-proposer-version", [("call.version,", "\"omitted\",")]),
]
receipts = []
for name, changes in mutants:
    path = root / filename
    before = path.read_bytes()
    source = before.decode()
    for original, replacement in changes:
        if source.count(original) != 1:
            raise RuntimeError(f"{name}: expected one mutation site, found {source.count(original)}")
        source = source.replace(original, replacement)
    command = ["sbt", "-batch", f"-Dstorymodel4s.grakern.build={args.grakern}", task]
    mutated = source.encode()
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
