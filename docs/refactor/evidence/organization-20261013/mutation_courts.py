#!/usr/bin/env python3
"""Serial, compiling mutation courts in an owned standalone clone; always restore bytes."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("clone", type=Path)
p.add_argument("output", type=Path)
a = p.parse_args()
a.output.mkdir(exist_ok=False)
head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=a.clone, text=True).strip()
align = "align/src/main/scala/storymodel4s/align/organizationreadout.scala"
pipeline = "pipeline/src/main/scala/storymodel4s/pipeline/MappingPipeline.scala"
coherent = "coherent middle assignment forbids the unattainable pairwise maximum of two"
enumeration = "sparse DP and expected counts agree with complete independent enumeration"
media = "media order uses native parts preserves checked composition and refuses unlocated targets"
new_extrema = """  private def extrema(rows: Vector[Row], order: Order, wanted: Category): Bounds =
    val ranges = rows.zip(rows.drop(1)).map { (a, b) =>
      val possible = for x <- a.admissible; y <- b.admissible yield
        if category(x, y, order) == wanted then 1 else 0
      (possible.min, possible.max)
    }
    new Bounds(ranges.map(_._1).sum, ranges.map(_._2).sum)
"""
mutations = [
    ("weighted-license", pipeline, "if !referenceMember then", "if !referenceMember && member == MappingRun.Member.Reference then",
     "pipeline/testOnly *OrganizationPipelineSuite", "exact zero succeeds every positive structural weight and reconstruction refuse", []),
    ("bridge-missing", align, "val pairs = rows.zip(rows.drop(1))", "val retained = rows.filter(_.status != RowStatus.Unresolved)\n    val pairs = retained.zip(retained.drop(1))",
     "alignJVM/testOnly *OrganizationReadoutSuite", "missing middle retains two unresolved adjacencies and no direct observation", []),
    ("independent-pair-bounds", align, None, new_extrema,
     "alignJVM/testOnly *OrganizationReadoutSuite", coherent, [enumeration]),
    ("invent-media-order", align, "if left.isEmpty || right.isEmpty then None", "if left.isEmpty || right.isEmpty then ranked",
     "alignJVM/testOnly *OrganizationReadoutSuite", media, []),
    ("forgeable-result", align, "final class Result private[OrganizationReadout]", "final case class Result private[OrganizationReadout]",
     "pipeline/Test/clean; pipeline/testOnly *OrganizationBoundarySuite", "Result closes Mirror with an accepting control",
     [f"Result closes {door} with an accepting control" for door in ["apply", "copy", "fromProduct", "product construction"]]),
]
receipts = []
for name, filename, before_text, after_text, task, witness, related in mutations:
    path = a.clone / filename
    original = path.read_bytes()
    source = original.decode()
    if before_text is None:
        site = source.index("  private def extrema(")
        mutated = (source[:site] + after_text).encode()
    else:
        assert source.count(before_text) == 1, name
        mutated = source.replace(before_text, after_text).encode()
    command = ["sbt", "-batch", "set ThisBuild / tlFatalWarnings := true", task]
    try:
        path.write_bytes(mutated)
        with (a.output / (name + ".log")).open("w") as log:
            run = subprocess.run(command, cwd=a.clone, stdout=log, stderr=subprocess.STDOUT)
        plain = re.sub(r"\x1b\[[0-9;]*m", "", (a.output / (name + ".log")).read_text())
        totals = re.findall(r"Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)", plain)
        failures = re.findall(r"^==> X \S+Suite\.(.*?) \d+(?:\.\d+)?s\b", plain, re.MULTILINE)
        compilation = "Compilation failed" in plain or bool(re.search(r"\[error\] --", plain))
        killed = (run.returncode != 0 and not compilation and witness in failures
                  and not set(failures) - {witness, *related} and len(totals) == 1
                  and int(totals[0][1]) == len(failures) and int(totals[0][2]) == 0 and int(totals[0][3]) > 0)
        receipt = dict(name=name, head=head, source=filename, command=command,
                       original_sha256=hashlib.sha256(original).hexdigest(), mutant_sha256=hashlib.sha256(mutated).hexdigest(),
                       exit=run.returncode, totals=totals, failures=failures, witness=witness, compilation_error=compilation, killed=killed)
    finally:
        path.write_bytes(original)
        assert path.read_bytes() == original
    receipts.append(receipt)
    (a.output / "receipts.json").write_text(json.dumps(receipts, indent=2) + "\n")
    print(json.dumps(receipt), flush=True)
    if not killed:
        raise SystemExit("inconclusive mutation: " + name)
