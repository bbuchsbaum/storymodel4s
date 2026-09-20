"""Independent reconciliation of the completed local S4b evidence chain."""
from pathlib import Path
import hashlib
import json
import subprocess
import tarfile


root = Path(__file__).resolve().parents[3]
evidence = root / "docs/refactor/evidence/d1a-s4b-envelope-20260919"
study = root / "data/study/d1a-s4b-20260919"
goal = root / "data/study/film-foundation-goal-20260919"
output = goal / "root-s4b-landing-audit.json"
assert not output.exists(), output


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path: Path):
    return json.loads(path.read_text())


mutation = load(evidence / "mutation-terminal-summary.json")
assert mutation["codeRevision"] == "9a7caf45829e737e9638a23cd9176f30002a0187"
assert (mutation["planned"], mutation["completed"], mutation["compiledKilled"], mutation["namedAcceptingControlsPassed"], mutation["cleanProbeRecompiles"]) == (54, 54, 54, 54, 24)
assert mutation["runnerExitCode"] == 0 and mutation["terminalCloneClean"]
restored = mutation["terminalRestoredControl"]
assert restored["exitCode"] == 0 and restored["cleanBefore"] and restored["cleanAfter"]
assert restored["aggregateTestCounts"] == {"Total": 62, "Failed": 0, "Errors": 0, "Passed": 62, "Skipped": 0}
assert all(row["originalSha256"] == row["restoredSha256"] != row["mutantSha256"] for row in mutation["records"])

provider = load(goal / "root-s4b-final-provider-audit.json")
assert provider["codeRevision"] == "9dcaf8e786207d523787d670810c28d104efe117"
assert provider["taskCount"] == 56 and provider["cleanCloneObserved"]
assert provider["aggregateTestCounts"] == {"Total": 6932, "Failed": 0, "Errors": 0, "Passed": 6927, "Skipped": 5}
assert provider["s0GoldenSha256"] == "cc201d9dd3e3576fabcd45677369f00c023ca5a455ea9d9e7ba694700c759fb3"

consumer = load(goal / "root-s4b-repaired-consumer-audit.json")
assert consumer["actualRevisions"]["storyatlas4s"] == "b156029d95a662107c819dd2aefc80abec7d748e"
assert consumer["actualRevisions"]["storymodel4s"] == provider["codeRevision"]
assert len(consumer["tasks"]) == 8 and consumer["allCloneStatesVerifiedClean"]
assert consumer["aggregateTestCounts"] == {"Total": 245, "Failed": 0, "Errors": 0, "Passed": 245, "Skipped": 0}

smoke = load(goal / "root-s4b-final-smoke-audit.json")
assert smoke["revisions"]["storyatlas4s"] == consumer["actualRevisions"]["storyatlas4s"]
assert smoke["revisions"]["storymodel4s"] == provider["codeRevision"]
assert smoke["passedChecks"] == 260 and smoke["lifecycle"]["allOwnedRowsAbsentAfter"]

repair = load(evidence / "consumer-smoke-repair.json")
assert repair["repairConsumer"] == consumer["actualRevisions"]["storyatlas4s"]
assert [row["observedChecks"]["exitCode"] for row in repair["originalFailures"]] == [1, 1]
assert all(row["observedChecks"]["passedChecks"] == 237 for row in repair["originalFailures"])
assert repair["baselineRepaired"]["observedChecks"]["passedChecks"] == 260
assert repair["candidateFinal"]["checks"] == 260 and repair["candidateFinal"]["failedChecks"] == 0

acceptance = load(evidence / "acceptance-map.json")
assert acceptance["sourceTestCandidate"] == mutation["codeRevision"]
assert acceptance["releaseCandidate"] == provider["codeRevision"]
assert len(acceptance["criteria"]) == 7
assert acceptance["status"] == "All required local qualification checks pass; local landing and tracker closure follow."

source_equivalence = load(evidence / "source-equivalence.json")
assert source_equivalence["mutationCandidate"] == mutation["codeRevision"]
assert source_equivalence["releaseCandidate"] == provider["codeRevision"]
assert source_equivalence["allProductionTestBuildAndExecutableDocsIdentical"]
assert source_equivalence["frozenS0Sha256"] == provider["s0GoldenSha256"]

archive_manifest_path = evidence / "release-artifact-manifest.json"
archive_manifest = load(archive_manifest_path)
archive = evidence / archive_manifest["archive"]
assert sha(archive) == archive_manifest["archiveSha256"]
assert archive.stat().st_size == archive_manifest["archiveBytes"]
with tarfile.open(archive, "r:gz") as bundle:
    members = [member for member in bundle.getmembers() if member.isfile()]
    assert len(members) == len(archive_manifest["members"]) == 669
    assert [member.name for member in members] == [row["path"] for row in archive_manifest["members"]]
    for member, row in zip(members, archive_manifest["members"]):
        data = bundle.extractfile(member).read()
        assert len(data) == row["bytes"] and hashlib.sha256(data).hexdigest() == row["sha256"]

changed = subprocess.check_output(["git", "diff", "--name-only", mutation["codeRevision"], "c929c52f"], cwd=root, text=True).splitlines()
assert changed and all(path.startswith("docs/refactor/evidence/d1a-s4b-envelope-20260919/") for path in changed)
landing = load(study / "consumer-local-landing.json")

result = {
    "scope": "Independent local S4b evidence reconciliation; no builds, browser launch, process action, remote publication, or Mote mutation by auditor.",
    "revisions": {
        "mutationSourceAndTests": mutation["codeRevision"],
        "providerRelease": provider["codeRevision"],
        "providerEvidence": "c929c52f",
        "consumer": consumer["actualRevisions"]["storyatlas4s"],
    },
    "mutation": {"plannedKilledControls": 54, "cleanCompileProbes": 24, "restoredControl": restored["aggregateTestCounts"], "terminalCloneClean": True},
    "provider": {"taskCount": provider["taskCount"], "tests": provider["aggregateTestCounts"], "s0Sha256": provider["s0GoldenSha256"], "auditSha256": sha(goal / "root-s4b-final-provider-audit.json")},
    "consumer": {"taskCount": len(consumer["tasks"]), "tests": consumer["aggregateTestCounts"], "auditSha256": sha(goal / "root-s4b-repaired-consumer-audit.json")},
    "smoke": {"checks": smoke["passedChecks"], "auditSha256": sha(goal / "root-s4b-final-smoke-audit.json"), "ownedRowsAbsentAfter": True},
    "failedSmokeRepair": {"baselineAndCandidateOriginalFailuresPreserved": 2, "baselineRepairedChecks": 260, "candidateChecks": 260},
    "evidenceArchive": {"members": len(archive_manifest["members"]), "archiveSha256": archive_manifest["archiveSha256"], "manifestSha256": sha(archive_manifest_path)},
    "acceptanceCriteria": len(acceptance["criteria"]),
    "postMutationSourceChanges": changed,
    "consumerLocalLanding": landing,
    "findings": [],
    "limits": "Local qualification and landing reconciliation only; no CI, remote push, film compiler, caption licensing, S4c, D1B, or scientific qualification.",
}
output.write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
