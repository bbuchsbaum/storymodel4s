"""Run one isolated boundary mutation; never edit the working package."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

pilot = Path(__file__).resolve().parents[1]
tests = pilot / "tests/test_account.py"
names = (
    "test_exact_union_containment_does_not_fill_gaps",
    "test_non_bmp_support_uses_utf16_and_refuses_surrogate_split",
    "test_full_utf16_span_recovers_exact_text",
)
selection = [f"{tests}::{name}" for name in names]
guard = "    if child.document != parent.document:\n        return False\n"

with tempfile.TemporaryDirectory(prefix="storymodel-mutation-") as directory:
    root = Path(directory)
    shutil.copytree(pilot / "src/storymodel", root / "storymodel")
    env = dict(os.environ, PYTHONPATH=str(root), PYTHONDONTWRITEBYTECODE="1")
    model = root / "storymodel/model.py"
    original = model.read_text()
    assert original.count(guard) == 1
    command = [sys.executable, "-m", "pytest", "-q", *selection]
    baseline = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True)
    assert baseline.returncode == 0, baseline.stdout + baseline.stderr
    model.write_text(original.replace(guard, "", 1))
    # Prove the mutant imports, from the temporary package, before running assertions.
    imported = subprocess.run(
        [sys.executable, "-c", "import storymodel.model as m; print(m.__file__)"],
        cwd=root,
        env=env,
        capture_output=True,
        text=True,
        check=True,
    )
    assert str(model) in imported.stdout
    mutant = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True)
    assert mutant.returncode == 1, mutant.stdout + mutant.stderr
    assert "1 failed, 2 passed" in mutant.stdout, mutant.stdout
    assert f"test_account.py::{names[0]}" in mutant.stdout, mutant.stdout
    print(
        json.dumps(
            {
                "mutation": "delete same-document containment guard",
                "baseline_exit": baseline.returncode,
                "baseline_output": baseline.stdout,
                "mutant_imported": True,
                "mutant_exit": mutant.returncode,
                "mutant_output": mutant.stdout,
            },
            indent=2,
        )
    )
