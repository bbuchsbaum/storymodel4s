#!/usr/bin/env python3
"""Synthetic controls for the nonpublishing publication preflight."""

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PREFLIGHT = ROOT / "preflight.py"


def pom(group: str, artifact: str, version: str, dependencies=()):
    deps = "".join(
        f"<dependency><groupId>{g}</groupId><artifactId>{a}</artifactId><version>{v}</version></dependency>"
        for g, a, v in dependencies
    )
    return f"""<project><modelVersion>4.0.0</modelVersion><groupId>{group}</groupId><artifactId>{artifact}</artifactId><version>{version}</version><packaging>jar</packaging><name>{artifact}</name><description>test</description><url>https://example.invalid/{artifact}</url><licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url></license></licenses><developers><developer><id>test</id><name>Test</name></developer></developers><scm><url>https://example.invalid/repo</url><connection>scm:git:https://example.invalid/repo.git</connection><developerConnection>scm:git:ssh://example.invalid/repo.git</developerConnection><tag>v{version}</tag></scm><dependencies>{deps}</dependencies></project>"""


def matrix(path: Path, coordinates):
    path.write_text(json.dumps({"schema": "storymodel4s-release-candidate-matrix/v1", "coordinates": coordinates}))


def coordinate(artifact: str, version="1.0.0"):
    return {"groupId": "example", "artifactId": artifact, "version": version, "packaging": "jar"}


def run(work: Path, expected: Path, candidates: Path, coursier: Path, *extra: str):
    receipt = work / ("receipt-" + str(len(list(work.glob("receipt-*.json")))) + ".json")
    result = subprocess.run(
        [sys.executable, str(PREFLIGHT), "--expected", str(expected), "--coursier", str(coursier), "--output", str(receipt), *extra, str(candidates)],
        text=True,
        capture_output=True,
    )
    return result, json.loads(receipt.read_text())


with tempfile.TemporaryDirectory() as temporary:
    work = Path(temporary)
    good_cs = work / "cs-good"
    good_cs.write_text('#!/usr/bin/env python3\nimport json, os, pathlib, sys\ncache = pathlib.Path(os.environ[\'COURSIER_CACHE\'])\np = cache / \'artifact.jar\'\np.parent.mkdir(parents=True, exist_ok=True)\np.write_bytes(b\'jar\')\nreport = pathlib.Path(sys.argv[sys.argv.index(\'--json-output-file\') + 1])\nroot = sys.argv[-1]\ngroup, artifact, version = root.split(\':\')\nrepository = sys.argv[sys.argv.index(\'--repository\') + 1]\nurl = f\'{repository}/{group.replace(".", "/")}/{artifact}/{version}/{artifact}-{version}.jar\'\njson.dump({\'dependencies\': [\n {\'coord\': root, \'file\': str(p), \'url\': url, \'dependencies\': [\'nested:transitive:3.0.0\']},\n {\'coord\': \'nested:transitive:3.0.0\', \'file\': str(p), \'url\': \'https://repo.maven.apache.org/maven2/nested/transitive/3.0.0/transitive-3.0.0.jar\', \'dependencies\': []}\n]}, report.open(\'w\'))\n')
    good_cs.chmod(0o755)
    bad_cs = work / "cs-bad"
    bad_cs.write_text("#!/usr/bin/env python3\nraise SystemExit(17)\n")
    bad_cs.chmod(0o755)
    snapshot_cs = work / "cs-snapshot"
    snapshot_cs.write_text("#!/usr/bin/env python3\nimport json, os, pathlib, sys\ncache=pathlib.Path(os.environ['COURSIER_CACHE']); p=cache/'artifact.jar'; p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(b'jar'); report=pathlib.Path(sys.argv[sys.argv.index('--json-output-file')+1]); json.dump({'dependencies':[{'coord':'nested:transitive:3.0-SNAPSHOT','file':str(p),'url':'https://repo.maven.apache.org/maven2/nested/transitive/3.0-SNAPSHOT/transitive-3.0-SNAPSHOT.jar','dependencies':[]}]}, report.open('w'))\n")
    snapshot_cs.chmod(0o755)
    absent_cs = work / "cs-absent"
    absent_cs.write_text("#!/usr/bin/env python3\nimport json, os, pathlib, sys\nreport=pathlib.Path(sys.argv[sys.argv.index('--json-output-file')+1]); json.dump({'dependencies':[{'coord':'nested:transitive:3.0.0','file':str(pathlib.Path(os.environ['COURSIER_CACHE'])/'missing.jar'),'url':'https://repo.maven.apache.org/maven2/nested/transitive/3.0.0/transitive-3.0.0.jar','dependencies':[]}]}, report.open('w'))\n")
    absent_cs.chmod(0o755)

    candidate = work / "candidate"
    candidate.mkdir()
    (candidate / "a.pom").write_text(pom("example", "a", "1.0.0", [("example", "b", "1.0.0"), ("external", "dep", "2.0.0")]))
    (candidate / "b.pom").write_text(pom("example", "b", "1.0.0"))
    expected = work / "expected.json"
    matrix(expected, [coordinate("a"), coordinate("b")])
    result, receipt = run(work, expected, candidate, good_cs)
    assert result.returncode == 0, result.stderr
    assert receipt["qualified"] == "public-dependency-qualified"
    assert receipt["publicDependencyQualification"] == "passed" and receipt["stagingRepositoryRehearsal"] == "not-requested"
    command = receipt["commands"][0]
    assert command["exit"] == 0 and command["jars"] and Path(command["log"]).is_file()
    assert "--no-default" in command["command"] and "https://repo.maven.apache.org/maven2" in command["command"]
    assert len(command["jars"][0]["sha256"]) == 64
    assert receipt["coursier"]["sha256"] == __import__("hashlib").sha256(good_cs.read_bytes()).hexdigest()
    result, receipt = run(work, expected, candidate, good_cs, "--staging-repository", "https://staging.example.invalid/repository")
    assert result.returncode == 0, result.stderr
    assert {command["mode"] for command in receipt["commands"]} == {"maven-central", "staging-rehearsal"}
    assert receipt["publicDependencyQualification"] == "passed" and receipt["stagingRepositoryRehearsal"] == "passed"

    # A small real Coursier control verifies the v2 JSON-report contract against Maven Central.
    real_cs = ROOT.parent.parent / ".agent-work/release-freeze-20261014/cs"
    if real_cs.is_file() and real_cs.stat().st_mode & 0o111:
        real = work / "real"
        real.mkdir()
        (real / "real.pom").write_text(pom("example", "real", "1.0.0", [("org.scala-lang", "scala3-library_3", "3.3.4")]))
        matrix(work / "real.json", [coordinate("real")])
        result, receipt = run(work, work / "real.json", real, real_cs, "--timeout-seconds", "120")
        assert result.returncode == 0, result.stderr
        assert receipt["commands"][0]["reportSha256"] and len(receipt["commands"][0]["jars"]) >= 2

    # Duplicate coordinates cannot pass merely because their set matches the matrix.
    (candidate / "duplicate.pom").write_text((candidate / "b.pom").read_text())
    result, receipt = run(work, expected, candidate, good_cs)
    assert result.returncode != 0 and "duplicate candidate POM coordinate" in receipt["error"]
    (candidate / "duplicate.pom").unlink()

    wrong_root = work / "cs-wrong-root"
    wrong_root.write_text(good_cs.read_text().replace("root = sys.argv[-1]", "root = 'wrong:coordinate:1.0.0'"))
    wrong_root.chmod(0o755)
    result, receipt = run(work, expected, candidate, wrong_root)
    assert result.returncode != 0 and "omits requested coordinate" in receipt["error"]
    assert receipt["commands"][0]["exit"] == 0

    malformed = work / "cs-malformed"
    malformed.write_text("#!/usr/bin/env python3\nimport pathlib,sys\npathlib.Path(sys.argv[sys.argv.index('--json-output-file')+1]).write_text('[]')\n")
    malformed.chmod(0o755)
    result, receipt = run(work, expected, candidate, malformed)
    assert result.returncode != 0 and "must be an object" in receipt["error"]
    assert receipt["commands"][0]["exit"] == 0

    # Exact coordinate matrix rejects a count-matched candidate with a missing internal target.
    (candidate / "b.pom").unlink()
    (candidate / "c.pom").write_text(pom("example", "c", "1.0.0"))
    result, receipt = run(work, expected, candidate, good_cs)
    assert result.returncode != 0 and "coordinate matrix mismatch" in receipt["error"]
    (candidate / "c.pom").unlink()

    # An internal dependency absent from the candidate set is never sent to Central.
    result, receipt = run(work, work / "only-a.json", candidate, good_cs)
    assert result.returncode != 0
    matrix(work / "only-a.json", [coordinate("a")])
    result, receipt = run(work, work / "only-a.json", candidate, good_cs)
    assert result.returncode != 0 and "missing internal" in receipt["error"]

    # Dynamic and snapshot transitives fail before resolver invocation.
    (candidate / "a.pom").write_text(pom("example", "a", "1.0.0", [("external", "dep", "2.0-SNAPSHOT")]))
    result, receipt = run(work, work / "only-a.json", candidate, good_cs)
    assert result.returncode != 0 and "non-fixed version" in receipt["error"]

    # Fixed external closure preserves the resolver's nonzero exit and fails closed.
    (candidate / "a.pom").write_text(pom("example", "a", "1.0.0", [("external", "dep", "2.0.0")]))
    result, receipt = run(work, work / "only-a.json", candidate, bad_cs)
    assert result.returncode != 0 and receipt["commands"][0]["exit"] == 17

    # A successful process still fails when its report contains a nested snapshot or absent JAR.
    result, receipt = run(work, work / "only-a.json", candidate, snapshot_cs)
    assert result.returncode != 0 and "non-fixed version" in receipt["error"]
    result, receipt = run(work, work / "only-a.json", candidate, absent_cs)
    assert result.returncode != 0 and "absent or outside isolated cache" in receipt["error"]

    # Later 1.x versions require supplied exact 1.0.0 baseline POMs.
    (candidate / "a.pom").write_text(pom("example", "a", "1.0.1", [("external", "dep", "2.0.0")]))
    matrix(work / "later.json", [coordinate("a", "1.0.1")])
    result, receipt = run(work, work / "later.json", candidate, good_cs)
    assert result.returncode != 0 and "requires an exact 1.0.0 baseline" in receipt["error"]

print("publication preflight controls passed (including real Coursier when supplied)")
