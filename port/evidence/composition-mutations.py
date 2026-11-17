"""Reexecute cold oracles and compiling guard removals in an exact-commit temporary archive."""

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
root = Path(tempfile.mkdtemp(prefix="storymodel-composition-mutations-"))
archive = root / "source.tar"
with archive.open("wb") as out:
    subprocess.run(["git", "archive", revision, "port/src"], cwd=repo, stdout=out, check=True)
with tarfile.open(archive) as source:
    source.extractall(root, filter="data")
probe = root / "test_cold_probes.py"
shutil.copyfile(repo / "port/evidence/composition-independent.py", probe)
baseline = root / "port/src/storymodel"
controls = [
    "test_source_path_and_exact_control",
    "test_saved_readout_recomputes_measurements_and_query",
]
changes = {
    "clip_guard": (
        '    _within(selected, segment.native, "native support escapes occurrence interval")\n',
        "test_clipped_occurrence_rejects_legal_physical_points",
    ),
    "integral_guard": (
        "    if native_delta.denominator != 1:\n"
        '        raise ValueError("composed support endpoint is not an integral native tick")\n',
        "test_inverse_refuses_subnative_point_without_rounding",
    ),
}
results = []


def check(name, package, tests, expected_exit, summary):
    py_compile.compile(str(package / "composition.py"), doraise=True)
    selections = [f"{probe}::{test}" for test in tests] if tests else [str(probe)]
    result = subprocess.run(
        [sys.executable, "-m", "pytest", "-q", "--rootdir=.", *selections],
        cwd=root,
        env={
            **os.environ,
            "PYTHONPATH": str(package.parent),
            "REVIEW_SOURCE": str(package.parent),
        },
        capture_output=True,
        text=True,
    )
    log = root / f"{name}.log"
    log.write_text(result.stdout + result.stderr)
    assert result.returncode == expected_exit, (name, result.stdout, result.stderr)
    assert summary in result.stdout, (name, result.stdout)
    if tests and expected_exit:
        assert any(
            line.startswith("FAILED ") and f"::{tests[-1]}" in line
            for line in result.stdout.splitlines()
        )
    results.append({"name": name, "exit": result.returncode, "summary": summary, "log": str(log)})


check("baseline", baseline, [], 0, "5 passed")
for name, (guard, assertion) in changes.items():
    package = root / f"mutant-{name}" / "storymodel"
    shutil.copytree(baseline, package, ignore=shutil.ignore_patterns("__pycache__"))
    path = package / "composition.py"
    text = path.read_text()
    assert text.count(guard) == 1
    path.write_text(text.replace(guard, ""))
    check(name, package, [*controls, assertion], 1, "1 failed, 2 passed")
print(json.dumps({"revision": revision, "root": str(root), "results": results}, indent=2))
