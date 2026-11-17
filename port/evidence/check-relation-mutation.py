"""Delete the exact quote guard in an isolated source copy; require a named failure."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

pilot = Path(__file__).resolve().parents[1]
names = [
    "test_wrong_quote_is_rejected_without_mutating_the_input_account",
    "test_admits_existing_four_kinds_and_flattens_exact_discontinuous_evidence",
    "test_duplicate_edges_and_existing_edge_cycles_are_rejected_atomically",
]
with tempfile.TemporaryDirectory(prefix="relation-mutation-") as directory:
    root = Path(directory)
    shutil.copytree(pilot / "src/storymodel", root / "storymodel")
    env = dict(os.environ, PYTHONPATH=str(root), PYTHONDONTWRITEBYTECODE="1")
    command = [
        sys.executable,
        "-m",
        "pytest",
        "-q",
        *(f"{pilot}/tests/test_relations.py::{name}" for name in names),
    ]
    baseline = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True)
    assert baseline.returncode == 0, baseline.stdout + baseline.stderr
    path = root / "storymodel/relations.py"
    original = path.read_text()
    guard = "quote.span.slice(document.text) != quote.text"
    assert original.count(guard) == 1
    path.write_text(original.replace(guard, "False"))
    imported = subprocess.run(
        [sys.executable, "-c", "import storymodel.relations as m; print(m.__file__)"],
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
                "mutation": "delete exact relation quote check",
                "baseline": "3 passed",
                "mutant": "1 failed, 2 passed",
                "mutant_imported": True,
                "named_failure": names[0],
            },
            indent=2,
        )
    )
