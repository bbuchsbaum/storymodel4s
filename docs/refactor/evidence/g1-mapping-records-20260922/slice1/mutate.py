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
source = root / "laws/src/main/scala/storymodel4s/laws/MappingMiniature.scala"
original = source.read_text()
digest = lambda b: hashlib.sha256(b).hexdigest()
wrapper = "/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py"
base = ["sbt", "-batch", f"-Dstorymodel4s.grakern.build={dependency}"]
suite = "codecJVM/testOnly storymodel4s.codec.MappingMiniatureDigestSuite"
receipts = []
assert 'Locus("e7", Some("part-b"), Some("20"), Some("30"))' in original
start = original.index('    Packet("p6",')
end = original.index('\n', start)
mutations = [
 ("coordinate-altered", original.replace('Locus("e7", Some("part-b"), Some("20"), Some("30"))', 'Locus("e7", Some("part-b"), Some("20"), Some("31"))'), "transcription equals fixture", False),
 ("failure-packet-dropped", original[:start] + original[end+1:], "transcription equals fixture", False)
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
    code, text, control = run("restored-control", [suite])
    control.update(sourceSha256=digest(source.read_bytes()), cleanCompileAndTest=False)
    (out / "restored-control.json").write_text(json.dumps(control, indent=2) + "\n")
    assert code == 0 and control["totals"]
finally:
    source.write_text(original)
