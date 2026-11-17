"""Reexecute cold probes and compiling guard removals in an exact-commit temporary archive."""

import json
import os
import py_compile
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

repo = Path(sys.argv[1]).resolve()
revision = sys.argv[2]
root = Path(tempfile.mkdtemp(prefix="storymodel-import-recovery-mutations-"))
archive = root / "source.tar"
with archive.open("wb") as out:
    subprocess.run(["git", "archive", revision, "port/src"], cwd=repo, stdout=out, check=True)
with tarfile.open(archive) as source:
    source.extractall(root, filter="data")
probe = root / "test_cold_probes.py"
shutil.copyfile(repo / "port/evidence/import-recovery-independent.py", probe)
baseline = root / "port/src/storymodel"
changes = {
    "intent": ("if not _same_json(saved_intent, expected):", "if False:"),
    "preflight": (
        "                self._validate_import_state(expected_replies, checked_manifest)\n"
        "            else:",
        "                # mutant: skip recovery preflight\n            else:",
    ),
}
results = []


def check(name, package, expected_exit, summary):
    result = subprocess.run(
        [sys.executable, "-m", "pytest", "-q", "--rootdir=.", str(probe)],
        cwd=root,
        env={**os.environ, "PYTHONPATH": str(package.parent)},
        capture_output=True,
        text=True,
    )
    log = root / f"{name}.log"
    log.write_text(result.stdout + result.stderr)
    assert result.returncode == expected_exit, (name, result.stdout, result.stderr)
    assert summary in result.stdout, (name, result.stdout)
    results.append({"name": name, "exit": result.returncode, "summary": summary, "log": str(log)})


check("baseline", baseline, 0, "10 passed")
for name, (old, new) in changes.items():
    package = root / f"mutant-{name}" / "storymodel"
    shutil.copytree(baseline, package, ignore=shutil.ignore_patterns("__pycache__"))
    path = package / "execution.py"
    text = path.read_text()
    assert text.count(old) == 1
    path.write_text(text.replace(old, new))
    py_compile.compile(str(path), doraise=True)
    check(name, package, 1, "4 failed, 6 passed")
print(json.dumps({"revision": revision, "root": str(root), "results": results}, indent=2))
