"""Discriminating guard mutations run in isolated copies; production is never edited."""

import json
import os
import shutil
import subprocess
from pathlib import Path

base = Path(__file__).resolve().parents[1]
root = base / "data/hierarchy-mutations"
root.mkdir(exist_ok=True)
cases = [
    (
        "account-binding",
        "if selected.account_sha256 != account_sha:",
        "if False and selected.account_sha256 != account_sha:",
        "test_stale_account_binding_is_rejected_before_hierarchy_consumption",
    ),
    (
        "exact-containment",
        "if not all(contained_in(e, supports[key]) for e in supports[child]):",
        "if False and not all(contained_in(e, supports[key]) for e in supports[child]):",
        "test_membership_refuses_a_same_document_gap_outside_its_parent_support",
    ),
    (
        "complete-root",
        'if selected.completeness == "complete" and keys and (root is None or empty):',
        'if False and selected.completeness == "complete" and keys and (root is None or empty):',
        "test_partial_is_explicit_but_complete_requires_one_nonempty_segment_root",
    ),
    (
        "parent-independent-identity",
        "members=tuple(sorted(resolved[c] for c in child_keys)),",
        "members=tuple(sorted(resolved[c] for c in child_keys)) "
        "+ ((parents[key][1],) if key in parents else ()),",
        "test_group_identity_is_bottom_up_and_legacy_parents_remain_unchanged",
    ),
]
controls = [
    "test_complete_tree_has_expected_supports_levels_and_stable_query",
    "test_hierarchy_transition_weight_oracle_is_independent_of_generated_edge_order",
]
records = []
for name, old, new, test in cases:
    directory = root / name
    directory.mkdir(exist_ok=True)
    shutil.copytree(
        base / "src/storymodel",
        directory / "storymodel",
        dirs_exist_ok=True,
        ignore=shutil.ignore_patterns("__pycache__"),
    )
    shutil.copy2(base / "tests/test_hierarchy.py", directory / "test_hierarchy.py")
    command = [
        str(base / ".venv/bin/python"),
        "-m",
        "pytest",
        "-q",
        *[f"test_hierarchy.py::{t}" for t in (test, *controls)],
    ]
    environment = dict(os.environ, PYTHONPATH=str(directory), PYTHONDONTWRITEBYTECODE="1")
    record = dict(
        mutation=name, file="hierarchy.py", old=old, new=new, test=test, controls=controls
    )
    for mode in ("baseline", "mutant"):
        if mode == "mutant":
            path = directory / "storymodel/hierarchy.py"
            content = path.read_text()
            assert content.count(old) == 1, (name, content.count(old))
            path.write_text(content.replace(old, new))
            subprocess.run(
                [str(base / ".venv/bin/python"), "-m", "py_compile", str(path)], check=True
            )
            shutil.rmtree(directory / "storymodel/__pycache__", ignore_errors=True)
        process = subprocess.run(
            command, cwd=directory, env=environment, capture_output=True, text=True
        )
        (directory / f"{mode}.log").write_text(process.stdout + process.stderr)
        expected = 0 if mode == "baseline" else 1
        totals = "3 passed" if mode == "baseline" else "1 failed, 2 passed"
        assert process.returncode == expected and totals in process.stdout, (
            name,
            mode,
            process.stdout,
            process.stderr,
        )
        record[mode] = process.stdout.splitlines()[-1]
    record["compiled"] = True
    records.append(record)
    print(name, record["mutant"], flush=True)
(root / "summary.json").write_text(json.dumps(records, indent=2) + "\n")
