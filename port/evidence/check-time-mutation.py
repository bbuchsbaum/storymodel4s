"""Prove the Scala parity witness catches a clip-endpoint regression in an isolated copy."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

pilot = Path(__file__).resolve().parents[1]
selection = [
    str(pilot / "tests/test_study_workflow.py")
    + "::test_all_computed_rows_match_pinned_scala_artifact",
    str(pilot / "tests/test_study_time.py")
    + "::test_time_boundary_and_outside_keep_raw_values_and_exact_near_list",
    str(pilot / "tests/test_study_time.py")
    + "::test_missing_clock_and_missing_or_unparsable_chunk_are_distinct",
]
with tempfile.TemporaryDirectory(prefix="storymodel-time-mutation-") as directory:
    root = Path(directory)
    shutil.copytree(pilot / "src/storymodel", root / "storymodel")
    env = dict(os.environ, PYTHONPATH=str(root), PYTHONDONTWRITEBYTECODE="1")
    command = [sys.executable, "-m", "pytest", "-q", *selection]
    baseline = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True)
    assert baseline.returncode == 0, baseline.stdout + baseline.stderr
    path = root / "storymodel/study_time.py"
    original = path.read_text()
    guard = "if seconds > _exact(entry.duration_seconds):"
    assert original.count(guard) == 1
    path.write_text(original.replace(guard, "if seconds >= _exact(entry.duration_seconds):"))
    imported = subprocess.run(
        [sys.executable, "-c", "import storymodel.study_time as m; print(m.__file__)"],
        cwd=root,
        env=env,
        capture_output=True,
        text=True,
        check=True,
    )
    assert str(path) in imported.stdout
    mutant = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True)
    assert mutant.returncode == 1, mutant.stdout + mutant.stderr
    assert "1 failed, 2 passed" in mutant.stdout, mutant.stdout
    assert "test_all_computed_rows_match_pinned_scala_artifact" in mutant.stdout
    print(
        json.dumps(
            {
                "mutation": "treat final clip endpoint as outside by changing > to >=",
                "baseline_exit": baseline.returncode,
                "baseline": "3 passed",
                "mutant_imported": True,
                "mutant_exit": mutant.returncode,
                "mutant": "1 failed, 2 passed",
                "named_failure": "test_all_computed_rows_match_pinned_scala_artifact",
            },
            indent=2,
        )
    )
