"""Delete the incomplete-input guard in an isolated source copy; require a named failure."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

pilot = Path(__file__).resolve().parents[1]
names = [
    "test_missing_earlier_form_cannot_manufacture_a_unique_antecedent",
    "test_future_introduction_never_resolves_an_earlier_pronoun",
    "test_non_bmp_offsets_are_utf16_and_annotation_quotes_cannot_reposition_mentions",
]
with tempfile.TemporaryDirectory(prefix="identity-mutation-") as directory:
    root = Path(directory)
    shutil.copytree(pilot / "src/storymodel", root / "storymodel")
    env = dict(os.environ, PYTHONPATH=str(root), PYTHONDONTWRITEBYTECODE="1")
    command = [
        sys.executable,
        "-m",
        "pytest",
        "-q",
        *(f"{pilot}/tests/test_identity.py::{name}" for name in names),
    ]
    baseline = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True)
    assert baseline.returncode == 0, baseline.stdout + baseline.stderr
    path = root / "storymodel/identity.py"
    original = path.read_text()
    guard = "if incomplete:"
    assert original.count(guard) == 1
    path.write_text(original.replace(guard, "if False:"))
    imported = subprocess.run(
        [sys.executable, "-c", "import storymodel.identity as m; print(m.__file__)"],
        cwd=root,
        env=env,
        capture_output=True,
        text=True,
        check=True,
    )
    assert str(path) in imported.stdout
    mutant = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True)
    assert mutant.returncode == 1 and "1 failed, 2 passed" in mutant.stdout, (
        mutant.stdout + mutant.stderr
    )
    assert names[0] in mutant.stdout
    print(
        json.dumps(
            {
                "mutation": "delete incomplete antecedent input guard",
                "baseline": "3 passed",
                "mutant": "1 failed, 2 passed",
                "mutant_imported": True,
                "named_failure": names[0],
            },
            indent=2,
        )
    )
