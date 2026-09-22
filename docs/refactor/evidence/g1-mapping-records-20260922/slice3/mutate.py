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
source = root / "align/src/main/scala/storymodel4s/align/mappingprovenance.scala"
original = source.read_text()
digest = lambda b: hashlib.sha256(b).hexdigest()
wrapper = "/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py"
base = ["sbt", "-batch", f"-Dstorymodel4s.grakern.build={dependency}"]
receipts = []
def once(old,new):
    assert original.count(old) == 1, (old,original.count(old))
    return original.replace(old,new)
mutations = [
 ("duplicate-entries-accepted",once("if duplicates.nonEmpty then", "if duplicates.nonEmpty && false then"),"duplicate stage entries refuse",False),
 ("missing-stage-accepted",once("else if missing.nonEmpty then", "else if missing.nonEmpty && false then"),"ledger total over stages",False),
 ("unordered-ledger-accepted",once("else if entries != entries.sortBy(e => (e.stage.ordinal, e.id.digest.hex)) then", "else if entries != entries.sortBy(e => (e.stage.ordinal, e.id.digest.hex)) && false then"),"ledger order canonical",False),
 ("stage-id-string-door",once("object StageEntryId:\n", "object StageEntryId:\n  def from(value: String): Either[storymodel4s.core.DomainError, StageEntryId] = Checksum.from(value).map(new StageEntryId(_))\n"),"identifiers have no string door",True),
 ("derived-factory-door",once("  final class Derived private (val receipt: StageReceipt) extends StageProvenance", "  final class Derived private (val receipt: StageReceipt) extends StageProvenance\n  object Derived:\n    def of(receipt: StageReceipt): Derived = new Derived(receipt)"),"no Derived door",True)
]

def run(name, commands):
    log = out / f"{name}.log"
    command = [sys.executable, wrapper, "--log", str(log), "--timeout", "1800", "--", *base, *commands]
    completed = subprocess.run(command, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (out / f"{name}.wrapper.txt").write_text(completed.stdout)
    text = log.read_text()
    metadata = json.loads(Path(str(log) + ".meta.json").read_text())
    return completed.returncode, text, dict(metadata=metadata, logSha256=digest(log.read_bytes()),
        totals=re.findall(r"^.*(?:Passed|Failed): Total.*$", text, re.M),
        failures=[line for line in text.splitlines() if "==> X" in line])

try:
    for name, mutant, expected, compile_door in mutations:
        platforms = ["JVM", "JS", "Native"] if compile_door else ["JVM"]
        for platform in platforms:
            source.write_text(mutant)
            project = "align" + platform
            commands = [f"{project}/Compile/clean", f"{project}/Test/clean"] if compile_door else []
            commands.append(f"{project}/testOnly *MappingVocabulary*")
            code, text, receipt = run(name+"-"+platform,commands)
            receipt.update(name=name,platform=platform,sourceSha256=digest(original.encode()),mutantSha256=digest(mutant.encode()),command=commands,cleanCompileAndTest=compile_door,expectedFailure=expected)
            receipt["killed"]=code != 0 and bool(receipt["totals"]) and expected in "\n".join(receipt["failures"])
            receipts.append(receipt)
            source.write_text(original)
            (out/"mutations.json").write_text(json.dumps(receipts,indent=2)+"\n")
            print(json.dumps(dict(name=name,platform=platform,killed=receipt["killed"],totals=receipt["totals"])),flush=True)
            if not receipt["killed"]: raise RuntimeError("inconclusive or surviving mutation: "+name+platform)
            if compile_door:
                code,text,control=run(name+"-restored-"+platform,commands)
                control.update(sourceSha256=digest(source.read_bytes()),cleanCompileAndTest=True)
                (out/(name+"-restored-"+platform+".json")).write_text(json.dumps(control,indent=2)+"\n")
                assert code == 0 and control["totals"]
    code,text,control=run("restored-control",["alignJVM/testOnly *MappingVocabulary*","alignJS/testOnly *MappingVocabulary*","alignNative/testOnly *MappingVocabulary*"])
    control.update(sourceSha256=digest(source.read_bytes()),cleanCompileAndTest=False)
    (out/"restored-control.json").write_text(json.dumps(control,indent=2)+"\n")
    assert code == 0 and control["totals"]
finally:
    source.write_text(original)
