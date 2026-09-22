#!/usr/bin/env python3
"""Compile targeted M1 counterexamples in an isolated checkout; restore every source.

One sbt process at a time. A kill requires the named test failure, successful compilation,
actual test totals and a nonzero exit. Control runs before and after must pass.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--grakern", required=True)
parser.add_argument("--staging", required=True)
parser.add_argument("--out", required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
out = Path(args.out).resolve()
out.mkdir(parents=True, exist_ok=False)
head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
if subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=root):
    raise SystemExit("Run on a clean, isolated committed tree")
matrix = "view/src/main/scala/storymodel4s/view/mappingmatrix.scala"
manifest = "view/src/main/scala/storymodel4s/view/workspace.scala"
join = "codec/src/main/scala/storymodel4s/codec/workspacejoin.scala"
manifest_task = "viewJVM/testOnly *WorkspaceManifestSuite"
matrix_task = "codecJVM/testOnly *WorkspaceMatrixSuite"
join_task = "fixturesJVM/testOnly *WorkspaceJoinSuite *WorkspaceFixtureSuite"
mutants = [
    ("skip-byte-digest", manifest,
     "ref.checksum != Checksum.ofBytes(files(path).toArray)",
     "ref.checksum != Checksum.ofBytes(files(path).toArray) && false",
     manifest_task, "changed bytes with unchanged paths, local IDs and lengths refuse"),
    ("filter-failed-rows", matrix,
     "record.inventory.units.zip(record.outcomes).map { (unit, outcome) =>",
     "record.inventory.units.zip(record.outcomes).filter(_._2.processing == ProcessingStatus.Complete).map { (unit, outcome) =>",
     matrix_task, "fixed cut includes every declared target and ordinal row"),
    ("missing-as-zero", matrix,
     "outcome.measures.normalized.flatMap(_.mass.get(destination)),",
     "outcome.measures.normalized.flatMap(_.mass.get(destination)).orElse(Some(0.0)),",
     matrix_task, "normalized and transport quantities keep missing, zero, budget and external mass distinct"),
    ("source-renormalize", matrix,
     "outcome.measures.normalized.flatMap(_.mass.get(destination)),",
     "outcome.measures.normalized.flatMap(r => r.mass.get(destination).map(v => v / r.mass.iterator.collect { case (Destination.Target(_), x) => x }.sum)),",
     matrix_task, "normalized and transport quantities keep missing, zero, budget and external mass distinct"),
    ("hull-evidence", join,
     "spans.refs.toVector.map(ref => ref -> ref.span.slice(content).toOption.get)",
     "Vector(SpanRef(None, spans.minSpan)).map(ref => ref -> ref.span.slice(content).toOption.get)",
     join_task, "exact discontiguous recall evidence omits hull-only text"),
    ("ignore-export-grant", manifest,
     ") && exportPermission == WorkspaceContentGrant.Granted",
     ")",
     join_task, "inspection-only imports cannot produce any export"),
    ("local-only-addresses", join,
     "        modelArtifact.hex,\n        recallArtifact.hex,\n",
     "",
     join_task, "two generated checked fixtures differ in hierarchy and retain partial authority"),
]
receipts = []


def run(name, tasks):
    command = ["sbt", "-batch", f"-Dsbt.global.staging={args.staging}",
               f"-Dstorymodel4s.grakern.build={args.grakern}", *tasks]
    start = time.monotonic()
    with (out / f"{name}.log").open("w") as log:
        result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=420)
    output = (out / f"{name}.log").read_text()
    totals = [list(map(int, row)) for row in re.findall(r"Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)", output)]
    failures = [line for line in output.splitlines() if "==> X" in line]
    return dict(name=name, head=head, command=command, exit=result.returncode,
                seconds=time.monotonic() - start, totals=totals, failures=failures,
                compilation_failed="Compilation failed" in output,
                log_sha256=hashlib.sha256((out / f"{name}.log").read_bytes()).hexdigest())


def save(receipt):
    receipts.append(receipt)
    (out / "receipts.json").write_text(json.dumps(receipts, indent=2) + "\n")
    print(json.dumps(receipt), flush=True)


def control(name):
    receipt = run(name, [manifest_task, matrix_task, join_task])
    receipt["passed"] = receipt["exit"] == 0 and len(receipt["totals"]) == 3 and all(
        total == passed and total > 0 and failed == errors == 0
        for total, failed, errors, passed in receipt["totals"])
    save(receipt)
    if not receipt["passed"]:
        raise SystemExit("Control failed")


control("before")
for name, filename, original, replacement, task, witness in mutants:
    path = root / filename
    before = path.read_bytes()
    text = before.decode()
    if text.count(original) != 1:
        raise SystemExit(f"{name}: expected one mutation site")
    mutated = text.replace(original, replacement).encode()
    try:
        path.write_bytes(mutated)
        receipt = run(name, [task])
        receipt.update(file=filename, original_sha256=hashlib.sha256(before).hexdigest(),
                       mutant_sha256=hashlib.sha256(mutated).hexdigest(), witness=witness)
        receipt["killed"] = receipt["exit"] != 0 and not receipt["compilation_failed"] and any(
            failed > 0 and errors == 0 and passed > 0 for _, failed, errors, passed in receipt["totals"]
        ) and any(witness in line for line in receipt["failures"])
    finally:
        path.write_bytes(before)
        if path.read_bytes() != before:
            raise RuntimeError("Source restoration failed")
    save(receipt)
    if not receipt["killed"]:
        raise SystemExit(f"{name}: survived or failed without named test evidence")
control("after")
print(f"Killed {len(mutants)} compiled mutants; both controls passed", flush=True)
