#!/usr/bin/env python3
"""Bind retained gate logs and fresh JUnit reports to an exact clean checkout."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import xml.etree.ElementTree as ET

root, log, output = (Path(p).resolve() for p in sys.argv[1:4])
metadata = json.loads(Path(str(log) + ".meta.json").read_text())
assert metadata["exit_code"] == 0 and metadata["reason"] == "exited", metadata
revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
assert subprocess.run(["git", "diff", "--quiet", "HEAD", "--"], cwd=root).returncode == 0
text = log.read_text()
totals = [line for line in text.splitlines() if re.search(r"(?:Passed|Failed): Total \d+", line)]
assert totals, "gate ran no tests"
counts = {key: 0 for key in ("Total", "Passed", "Failed", "Errors", "Skipped", "Ignored")}
for line in totals:
    for key in counts:
        hit = re.search(rf"\b{key} (\d+)", line)
        if hit:
            counts[key] += int(hit[1])
assert counts["Failed"] == counts["Errors"] == 0
reports = []
skips = []
for p in sorted(root.glob("**/test-reports/*.xml")):
    tree = ET.parse(p).getroot()
    reports.append(dict(path=str(p.relative_to(root)), sha256=hashlib.sha256(p.read_bytes()).hexdigest()))
    for case in tree.iter("testcase"):
        for skipped in case.findall("skipped"):
            skips.append(dict(suite=case.get("classname"), test=case.get("name"), reason=skipped.get("message", "")))
output.parent.mkdir(parents=True, exist_ok=True)
archive = output.with_suffix(".tar.gz")
with tarfile.open(archive, "w:gz") as tar:
    tar.add(log, arcname="gate.log")
    tar.add(Path(str(log) + ".meta.json"), arcname="command.json")
    for report in reports:
        tar.add(root / report["path"], arcname=report["path"])
result = dict(codeRevision=revision, command=metadata["command"], cwd=str(root),
    cleanTrackedTreeAfter=True, exitCode=metadata["exit_code"], commandReceipt=metadata,
    logSha256=hashlib.sha256(log.read_bytes()).hexdigest(), testTotals=totals,
    aggregateTestCounts=counts, skips=skips,
    warnings=[line for line in text.splitlines() if "[warn]" in line],
    junit=reports, archive=archive.name, archiveSha256=hashlib.sha256(archive.read_bytes()).hexdigest())
output.write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(dict(revision=revision, counts=counts, skips=skips, warnings=result["warnings"]), indent=2))
