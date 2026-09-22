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
source = root / "recall/src/main/scala/storymodel4s/recall/inventory.scala"
original = source.read_text()
digest = lambda b: hashlib.sha256(b).hexdigest()
wrapper = "/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py"
base = ["sbt", "-batch", f"-Dstorymodel4s.grakern.build={dependency}"]
receipts = []
def once(old,new):
    assert original.count(old) == 1, (old,original.count(old))
    return original.replace(old,new)
start = original.index("    new SegmentationId(")
end = original.index("\ntype RecallWord",start)
mutations = [
 ("drop-unassigned",once("            words,\n            units,", "            words.filter(w => membership(w.id).isInstanceOf[WordMembership.Member]),\n            units,"),"every parsed word is accounted",False),
 ("first-overlap",once("          owners match", "          owners.take(1) match"),"word in two units refuses",False),
 ("transcript-only-segmentation",original[:start]+"    new SegmentationId(graph.transcript.canonicalChecksum)\n"+original[end:],"unitization changes SegmentationId",False),
 ("skip-span-refusal",once("    if malformed then", "    if malformed && false then"),"malformed word spans refuse",False),
 ("ignore-segmentation",once(" && segmentation == SegmentationId.of(graph)",""),"inventory does not describe a resegmented graph",False),
 ("missing-decomposition",once("DecompositionStatus.NotAssessed(DecompositionReason.NoDecompositionDetector)","null"),"every unit NotAssessed",False),
 ("public-word-factory",once("  object Word:\n", "  object Word:\n    def of(id: RecallWordId, index: Int, span: TextSpan): Word = new Word(id, index, span)\n"),"word has no caller construction",True),
 ("package-word-constructor",once("final class Word private (", "final class Word private[recall] ("),"word has no caller construction",True)
]

mutations = [m for m in mutations if not m[3]] + [
 ("lossy-token-hash", once('Checksum.ofText(sequence(values).iterator.map(c => f"${c.toInt}%04x").mkString)', 'Checksum.ofText(sequence(values))'), "distinct UTF-16 unit identities never share", False),
 ("checksum-only-transcript", once('          graph.transcript.canonicalText,\n', ''), "distinct admitted UTF-16 transcripts never share", False)
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
            project = "recall" + platform
            commands = [f"{project}/Compile/clean", f"{project}/Test/clean"] if compile_door else []
            commands.append(f"{project}/testOnly *RecallInventory*")
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
    code,text,control=run("restored-control",["recallJVM/testOnly *RecallInventory*","recallJS/testOnly *RecallInventory*","recallNative/testOnly *RecallInventory*"])
    control.update(sourceSha256=digest(source.read_bytes()),cleanCompileAndTest=False)
    (out/"restored-control.json").write_text(json.dumps(control,indent=2)+"\n")
    assert code == 0 and control["totals"]
finally:
    source.write_text(original)
