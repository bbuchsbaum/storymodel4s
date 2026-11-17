"""Remove predicate support: repeated-actor discrimination must fail independently."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

pilot = Path(__file__).resolve().parents[1]
names = [
    "test_same_actor_different_actions_need_predicate_support",
    "test_competing_occurrences_remain_alternatives_not_tie_broken_by_order",
    "test_no_match_and_no_source_are_unresolved_not_external",
]
with tempfile.TemporaryDirectory(prefix="alignment-mutation-") as directory:
    root = Path(directory)
    shutil.copytree(pilot / "src/storymodel", root / "storymodel")
    env = dict(os.environ, PYTHONPATH=str(root), PYTHONDONTWRITEBYTECODE="1")
    command = [
        sys.executable,
        "-m",
        "pytest",
        "-q",
        *(f"{pilot}/tests/test_alignment.py::{name}" for name in names),
    ]
    baseline = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True)
    assert baseline.returncode == 0, baseline.stdout + baseline.stderr
    path = root / "storymodel/alignment.py"
    source = path.read_text()
    guard = 'if predicate_match == "none":'
    assert source.count(guard) == 1
    path.write_text(source.replace(guard, "if False:"))
    imported = subprocess.run(
        [sys.executable, "-c", "import storymodel.alignment as m; print(m.__file__)"],
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
                "mutation": "remove predicate admission requirement",
                "baseline": "3 passed",
                "mutant": "1 failed, 2 passed",
                "named_failure": names[0],
                "mutant_imported": True,
            },
            indent=2,
        )
    )
