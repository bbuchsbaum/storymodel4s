"""Isolated rate mutation proves the native clock oracle discriminates an incorrect transform."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

pilot = Path(__file__).resolve().parents[1]
test_file = pilot / "tests/test_source_intake.py"
names = [
    "test_native_clock_construction_matches_existing_scala_parity_source",
    "test_source_boundaries_revalidate_copies_and_derive_target_membership",
    "test_scene_minimum_not_input_order_determines_boundary",
]
with tempfile.TemporaryDirectory(prefix="storymodel-intake-mutation-") as directory:
    root = Path(directory)
    shutil.copytree(pilot / "src/storymodel", root / "storymodel")
    env = dict(os.environ, PYTHONPATH=str(root), PYTHONDONTWRITEBYTECODE="1")
    command = [sys.executable, "-m", "pytest", "-q", *(f"{test_file}::{name}" for name in names)]
    baseline = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True)
    assert baseline.returncode == 0, baseline.stdout + baseline.stderr
    path = root / "storymodel/source_intake.py"
    original = path.read_text()
    expression = "exact(clip.rate) * (starts[scene] - origin)"
    assert original.count(expression) == 1
    path.write_text(original.replace(expression, "Fraction(1) * (starts[scene] - origin)"))
    imported = subprocess.run(
        [sys.executable, "-c", "import storymodel.source_intake as m; print(m.__file__)"],
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
    assert names[0] in mutant.stdout
    print(
        json.dumps(
            {
                "mutation": "replace declared clip rate by one",
                "baseline_exit": 0,
                "baseline": "3 passed",
                "mutant_imported": True,
                "mutant_exit": 1,
                "mutant": "1 failed, 2 passed",
                "named_failure": names[0],
            },
            indent=2,
        )
    )
