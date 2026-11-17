"""Delete the attribution guard in an isolated source copy; require a named failure."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

pilot = Path(__file__).resolve().parents[1]
names = [
    "test_source_attributed_content_cannot_be_promoted_to_root[believed]",
    "test_source_rows_compile_both_events_and_local_roles_from_recorded_proposals",
    "test_row_accounting_distinguishes_unsupported_failed_missing_partial_and_malformed",
]
with tempfile.TemporaryDirectory(prefix="source-mutation-") as directory:
    root = Path(directory)
    shutil.copytree(pilot / "src/storymodel", root / "storymodel")
    env = dict(os.environ, PYTHONPATH=str(root), PYTHONDONTWRITEBYTECODE="1")
    command = [
        sys.executable,
        "-m",
        "pytest",
        "-q",
        *(f"{pilot}/tests/test_source.py::{name}" for name in names),
    ]
    baseline = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True)
    assert baseline.returncode == 0, baseline.stdout + baseline.stderr
    path = root / "storymodel/_admission.py"
    original = path.read_text()
    guard = "e.context not in contexts or context_for(e.evidence.span) != e.context"
    assert original.count(guard) == 1
    path.write_text(original.replace(guard, "e.context not in contexts"))
    imported = subprocess.run(
        [sys.executable, "-c", "import storymodel._admission as m; print(m.__file__)"],
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
                "mutation": "delete declared attribution scope check",
                "baseline": "3 passed",
                "mutant": "1 failed, 2 passed",
                "mutant_imported": True,
                "named_failure": names[0],
            },
            indent=2,
        )
    )
