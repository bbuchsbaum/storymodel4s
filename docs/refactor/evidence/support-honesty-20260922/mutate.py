#!/usr/bin/env python3
"""Sequential, restoring support-boundary mutations; raw sbt output is retained."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

root = Path(sys.argv[1]).resolve()
out = Path(sys.argv[2]).resolve()
dependency = Path(sys.argv[3]).resolve()
out.mkdir(parents=True, exist_ok=True)
source = root / "align/src/main/scala/storymodel4s/align/cost.scala"
original = source.read_text()
digest = lambda b: hashlib.sha256(b).hexdigest()
wrapper = "/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py"
base = ["sbt", "-batch", f"-Dstorymodel4s.grakern.build={dependency}"]
suite = "alignJVM/testOnly storymodel4s.align.SupportAssessmentSuite storymodel4s.align.SupportProducerBoundarySuite"
receipts = []

def replace_once(old, new):
    assert original.count(old) == 1, old
    return original.replace(old, new)

def unchecked_method(name):
    start = original.index(f"  private[align] def {name}(")
    end = original.index("\n/**", start) if name == "derived" else original.index("\n  /**", start)
    part = original[start:end]
    search = 0
    while (pos := part.find("checked(", search)) >= 0:
        opening = pos + len("checked")
        depth = 1
        closing = opening + 1
        while depth:
            depth += (part[closing] == "(") - (part[closing] == ")")
            closing += 1
        part = part[:pos] + "Right(new CostBreakdown" + part[opening:closing] + ")" + part[closing:]
        search = closing + len("Right(new CostBreakdown") - len("checked") + 1
    return original[:start] + part + original[end:]

mutations = [
    ("empty-full-support", replace_once(
        "case None => Unestablished.derived(SupportUnestablishedReason.EmptyEligibility, basis)",
        "case None => Assessed.derived(1.0, basis, NonEmptySet.fromSetUnsafe(SortedSet(CostTerm.Semantic)(Ordering.by[CostTerm, Int](_.ordinal))))"),
     "an empty eligible population is Unestablished", False),
    ("zero-full-support", replace_once(
        "Unestablished.derived(SupportUnestablishedReason.ZeroEligibleWeight, basis)",
        "Assessed.derived(1.0, basis, eligible)"),
     "zero eligible weight is Unestablished", False),
    ("external-wrong-reason", replace_once(
        "val externalState: NotApplicable = NotApplicable.of(SupportNotApplicableReason.ExternalState)",
        "val externalState: NotApplicable = NotApplicable.of(SupportNotApplicableReason.Unreachable)"),
     "external cells are NotApplicable", False),
    ("derived-unchecked", unchecked_method("derived"),
     "a producer cannot publish priced terms", False),
    ("external-unchecked", unchecked_method("external"),
     "a producer cannot publish a nonfinite or negative", False),
    ("cost-constructor-package-visible", replace_once(
        "final class CostBreakdown private (", "final class CostBreakdown private[align] ("),
     "SupportAssessmentUnforgeableSuite", True),
]

def run(name, commands):
    log = out / f"{name}.log"
    command = [sys.executable, wrapper, "--log", str(log), "--timeout", "900", "--", *base, *commands]
    completed = subprocess.run(command, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (out / f"{name}.wrapper.txt").write_text(completed.stdout)
    text = log.read_text()
    metadata = json.loads(Path(str(log) + ".meta.json").read_text())
    return completed.returncode, text, dict(metadata=metadata, logSha256=digest(log.read_bytes()),
        totals=re.findall(r"^.*(?:Passed|Failed): Total.*$", text, re.M),
        failures=[line for line in text.splitlines() if "==> X" in line])

try:
    for name, mutant, expected, compile_door in mutations:
        assert mutant != original
        source.write_text(mutant)
        commands = ["alignJVM/Compile/clean", "alignJVM/Test/clean"] if compile_door else []
        commands.append("alignJVM/testOnly storymodel4s.probes.SupportAssessmentUnforgeableSuite storymodel4s.align.attack.SupportPackageAttack" if compile_door else suite)
        code, text, receipt = run(name, commands)
        receipt.update(name=name, sourceSha256=digest(original.encode()), mutantSha256=digest(mutant.encode()),
            command=commands, cleanCompileAndTest=compile_door,
            killed=code != 0 and bool(receipt["totals"]) and expected in "\n".join(receipt["failures"]),
            expectedFailure=expected)
        receipts.append(receipt)
        source.write_text(original)
        (out / "mutations.json").write_text(json.dumps(receipts, indent=2) + "\n")
        print(json.dumps(dict(name=name, killed=receipt["killed"], totals=receipt["totals"])), flush=True)
        if not receipt["killed"]:
            raise RuntimeError(f"inconclusive or surviving mutation: {name}")
    code, text, control = run("restored-control", ["alignJVM/Compile/clean", "alignJVM/Test/clean", suite,
        "alignJVM/testOnly storymodel4s.probes.SupportAssessmentUnforgeableSuite storymodel4s.align.attack.SupportPackageAttack"])
    control.update(sourceSha256=digest(source.read_bytes()), cleanCompileAndTest=True)
    (out / "restored-control.json").write_text(json.dumps(control, indent=2) + "\n")
    assert code == 0 and control["totals"]
finally:
    source.write_text(original)
