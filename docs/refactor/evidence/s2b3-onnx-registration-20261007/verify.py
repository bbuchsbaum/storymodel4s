"""Verify retained synthetic S2b-3 receipts against their exact Git objects."""
import gzip
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path

EVIDENCE = Path(__file__).resolve().parent
ROOT = EVIDENCE.parents[3]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def git_bytes(sha, path):
    return subprocess.check_output(["git", "show", f"{sha}:{path}"], cwd=ROOT)


qualification = json.loads((EVIDENCE / "qualification.json").read_text())
assert qualification["status"] == "qualified"
export = json.loads((EVIDENCE / "export.json").read_text())
assert export["candidate_sha"] == qualification["code_sha"]
for path, expected in export["changed_files_sha256"].items():
    assert digest(git_bytes(export["candidate_sha"], path)) == expected, path

archive = json.loads((EVIDENCE / "archive.json").read_text())
for entry in archive:
    path = EVIDENCE / entry["path"]
    content = gzip.decompress(path.read_bytes()) if entry["gzip"] else path.read_bytes()
    assert digest(content) == entry["raw_sha256"], path
    assert len(content) == entry["raw_bytes"], path

mutations = json.loads((EVIDENCE / "mutations.json").read_text())
for item in mutations:
    assert item["compiled"] and item["qualified_single_failure"]
    assert item["exit"] == 1 and len(item["failures"]) == 1
    assert item["named_failure"] in item["failures"][0]
    assert item["totals"][-1][1] == 1 and item["totals"][-1][2] == 0
    assert item["totals"][-1][3] > 0
    patch = gzip.decompress((EVIDENCE / item["patch_archive"]).read_bytes())
    log = gzip.decompress((EVIDENCE / item["log_archive"]).read_bytes())
    assert digest(patch) == item["patch_sha256"]
    assert digest(log) == item["log_sha256"]
    original = git_bytes(item["candidate_sha"], item["path"])
    assert original == git_bytes(qualification["code_sha"], item["path"])
    with tempfile.TemporaryDirectory(prefix="s2b3-verify-") as tmp:
        target = Path(tmp) / item["path"]
        target.parent.mkdir(parents=True)
        target.write_bytes(original)
        applied = subprocess.run(["patch", "-p1", "--batch"], input=patch, cwd=tmp,
                                 capture_output=True)
        assert applied.returncode == 0, applied.stderr
        assert digest(target.read_bytes()) == item["source_sha256"]

compatibility = qualification["mutation_input_compatibility"]
for path in compatibility["unchanged_paths"]:
    assert git_bytes(compatibility["mutation_sha"], path) == git_bytes(qualification["code_sha"], path)
for path in compatibility["extended_test_paths"]:
    original = git_bytes(compatibility["mutation_sha"], path).rstrip()
    assert git_bytes(qualification["code_sha"], path).startswith(original), path

for lane in ["source", "consumer"]:
    result = qualification[lane]
    log = gzip.decompress((EVIDENCE / result["log_archive"]).read_bytes())
    metadata = json.loads((EVIDENCE / result["metadata_archive"]).read_text())
    assert digest(log) == result["log_sha256"]
    assert metadata["exit_code"] == result["exit"] == 0
    assert metadata["reason"] == "exited"
    assert metadata["command"] == result["command"]
    rows = re.findall(r"(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)(?:, (?:Ignored|Skipped) (\d+))?", log.decode())
    totals = [[int(x) if x else 0 for x in row] for row in rows]
    assert totals == result["totals"]
    sums = {"passed": sum(x[3] for x in totals), "failed": sum(x[1] for x in totals),
            "errors": sum(x[2] for x in totals), "skipped": sum(x[4] for x in totals)}
    assert sums == result["sum"]
    assert sums["passed"] > 0 and sums["failed"] == sums["errors"] == 0
    assert "set ThisBuild / tlFatalWarnings := true" in metadata["command"]
    assert metadata["command"][-2:] == ["scalafmtCheckAll", "scalafmtSbtCheck"]
assert qualification["independent_review"]["sha"] == qualification["code_sha"]
assert qualification["independent_review"]["verdict"] == "PASS"
print("Verified exact source inputs, archives, compiled mutation kills, gate receipts and review SHA.")
