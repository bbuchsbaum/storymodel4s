"""Delete the exact-quote guard in an isolated source copy; require a named failure."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

pilot = Path(__file__).resolve().parents[1]
names = [
    "test_invalid_decision_is_atomic_and_does_not_poison_sibling[quote-differs from source]",
    "test_graph_free_mapping_separates_reference_fidelity_and_three_views",
    "test_prompt_is_invariant_to_neighbor_text_global_offsets_and_session_identity",
]
with tempfile.TemporaryDirectory(prefix="mapping-mutation-") as directory:
    root = Path(directory)
    shutil.copytree(pilot / "src/storymodel", root / "storymodel")
    env = dict(os.environ, PYTHONPATH=str(root), PYTHONDONTWRITEBYTECODE="1")
    command = [
        sys.executable,
        "-m",
        "pytest",
        "-q",
        *(f"{pilot}/tests/test_direct_mapping.py::{name}" for name in names),
    ]
    baseline = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True)
    assert baseline.returncode == 0, baseline.stdout + baseline.stderr
    path = root / "storymodel/direct_mapping.py"
    original = path.read_text()
    guard = "if document is None or quote.span.slice(document.text) != quote.text:"
    assert original.count(guard) == 1
    path.write_text(original.replace(guard, "if document is None:"))
    imported = subprocess.run(
        [sys.executable, "-c", "import storymodel.direct_mapping as m; print(m.__file__)"],
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
                "mutation": "delete exact source quote guard",
                "baseline": "3 passed",
                "mutant": "1 failed, 2 passed",
                "mutant_imported": True,
                "named_failure": names[0],
            },
            indent=2,
        )
    )
