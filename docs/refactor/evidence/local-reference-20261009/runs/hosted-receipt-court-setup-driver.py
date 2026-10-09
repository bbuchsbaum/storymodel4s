"""Challenge hosted receipt guards with real logs and repaired artifact hashes."""
import gzip
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

E = Path(__file__).resolve().parent
R = E.parents[3]
O = Path(sys.argv[1])
SHA = "38cf9c7f0f879c883a307cbeb54d51c67f6c1476"
OLD = "eb240ffa7a11576525938b96491a2c94027aeba0"
JOB = 113986813858
SUITE = "storymodel4s.align.ReferenceMeasurementSuite"
CASES = [("wrong-run", "hosted job run"), ("wrong-head", "hosted job head"),
         ("wrong-attempt", "hosted job attempt"), ("wrong-checkout", "checkout SHA"),
         ("wrong-suite-count", "suite completion " + SUITE),
         ("missing-suite-completion", "suite completion " + SUITE)]
report = {"published_sha": SHA, "old_tooling_sha": OLD, "script_sha256": {}, "cases": []}
report["script_sha256"][Path(__file__).name] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
transcript = []

def digest(b):
    return hashlib.sha256(b).hexdigest()

def change_job(jobs, case):
    j = next(j for j in jobs["jobs"] if j["id"] == JOB)
    if case == "wrong-run": j["run_id"] += 1
    if case == "wrong-head": j["head_sha"] = "0" * 40
    if case == "wrong-attempt": j["run_attempt"] += 1

def change_log(body, case):
    s = body.decode()
    if case == "wrong-checkout":
        lines, n = s.splitlines(keepends=True), 0
        for i, line in enumerate(lines[:-1]):
            if 'git log -1 --format=%H' in line and SHA in lines[i + 1]:
                lines[i + 1] = lines[i + 1].replace(SHA, "0" * 40)
                n += 1
        assert n == 1
        s = ''.join(lines)
    if case == "wrong-suite-count":
        s, n = re.subn(r'(Test run ' + re.escape(SUITE) +
                       r' finished: 0 failed, 0 ignored, )14 total', r'\g<1>13 total', s)
        assert n == 1
    if case == "missing-suite-completion":
        s, n = re.subn(r'Test run ' + re.escape(SUITE) + r' finished:',
                       "Test run " + SUITE + " incomplete:", s)
        assert n == 1
    return s.encode()

def invoke(tool, version, case=None):
    with tempfile.TemporaryDirectory(prefix="reference-receipt-court-") as directory:
        root = Path(directory) / "repo"
        target = root / "docs/refactor/evidence" / E.name
        target.parent.mkdir(parents=True)
        shutil.copytree(E, target, ignore=shutil.ignore_patterns("__pycache__"))
        (root / ".git").symlink_to(R / ".git", target_is_directory=True)
        if version == "old":
            source = subprocess.check_output(["git", "show", OLD + ":" +
                str((E / tool).relative_to(R))], cwd=R)
            (target / tool).write_bytes(source)
        output = Path(directory) / "hosted"
        shutil.copytree(O, output)
        if case:
            jobs_path = output / "source-jobs.json" if tool == "collect-hosted.py" else target / "runs/hosted-source-jobs.json"
            jobs = json.loads(jobs_path.read_text())
            change_job(jobs, case)
            jobs_path.write_text(json.dumps(jobs, indent=2) + "\n")
            if tool == "collect-hosted.py":
                path = output / f"job-{JOB}.log"
                path.write_bytes(change_log(path.read_bytes(), case))
            else:
                publication_path = target / "publication.json"
                publication = json.loads(publication_path.read_text())
                job = next(j for j in publication["source"]["jobs"] if j["id"] == JOB)
                path = target / job["log_archive"]
                body = change_log(gzip.decompress(path.read_bytes()), case)
                path.write_bytes(gzip.compress(body, mtime=0))
                job.update(log_bytes=len(body), log_sha256=digest(body))
                publication_path.write_text(json.dumps(publication, indent=2) + "\n")
                index_path = target / "archive.json"
                index = json.loads(index_path.read_text())
                for item in index:
                    path = target / item["path"]
                    body = gzip.decompress(path.read_bytes()) if path.suffix == ".gz" else path.read_bytes()
                    item.update(raw_bytes=len(body), raw_sha256=digest(body))
                index_path.write_text(json.dumps(index, indent=2) + "\n")
        command = [sys.executable, str(target / tool)]
        if tool == "collect-hosted.py": command += [SHA, str(output)]
        result = subprocess.run(command, cwd=root, capture_output=True, text=True)
        transcript.append(json.dumps({"tool": tool, "version": version, "case": case,
            "exit": result.returncode, "stdout": result.stdout, "stderr": result.stderr}))
        return result

for tool in ["collect-hosted.py", "verify.py"]:
    report["script_sha256"][tool] = digest((E / tool).read_bytes())
    assert invoke(tool, "current").returncode == 0
    for case, message in CASES:
        old, current = invoke(tool, "old", case), invoke(tool, "current", case)
        assert old.returncode == 0, old.stderr
        assert current.returncode == 1 and "AssertionError: " + message in current.stderr, current.stderr
        report["cases"].append({"tool": tool, "case": case, "old_exit": old.returncode,
                                "current_exit": current.returncode, "named_assertion": message})
(E / "hosted-receipt-court.json").write_text(json.dumps(report, indent=2) + "\n")
(E / "runs/hosted-receipt-court.jsonl").write_text("\n".join(transcript) + "\n")
print("Verified two clean current controls and twelve named refusals; old tooling accepts all twelve altered receipts.")
