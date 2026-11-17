"""Discriminating guard mutations run in isolated copies; production is never edited."""

import json
import os
import shutil
import subprocess
from pathlib import Path

base = Path(__file__).resolve().parents[1]
root = base / "data/temporal-mutations"
root.mkdir(exist_ok=True)
cases = [
    (
        "account-binding",
        "chronology_workflow.py",
        "if proposals.account_sha256 != account_sha:",
        "if False and proposals.account_sha256 != account_sha:",
        "test_proposals_pin_raw_account_and_adjudication_and_replay_atomically",
    ),
    (
        "endpoint-equality",
        "chronology.py",
        '"meets": ((), ((1, 2),)),',
        '"meets": (((1, 2),), ()),',
        "test_meets_endpoint_equality_is_not_before_and_strict_constraints_remain_sparse",
    ),
    (
        "premise-scope",
        "chronology.py",
        "or not _within(contexts, claim.context, known[1])",
        "or False",
        "test_scope_premise_cannot_leak_from_a_descendant_context_to_root",
    ),
    (
        "structural-entailment",
        "chronology.py",
        "if possible != (claim.relation,):",
        "if False and possible != (claim.relation,):",
        "test_structural_entailment_requires_cited_exact_context_constraints_and_one_allen_atom",
    ),
    (
        "premise-filter",
        "chronology_workflow.py",
        "excluded = [row.id for row in rows if row.id not in eligible]",
        "excluded = []",
        "test_policy_excludes_status_and_legacy_dependent_conclusions_transitively",
    ),
]
controls = [
    "test_canonical_creation_rejects_raw_aliases_and_semantic_duplicates_including_during_contains",
    "test_legacy_before_rows_are_retained_and_new_duplicates_are_refused",
]
records = []
for name, filename, old, new, test in cases:
    directory = root / name
    directory.mkdir(exist_ok=True)
    shutil.copytree(
        base / "src/storymodel",
        directory / "storymodel",
        dirs_exist_ok=True,
        ignore=shutil.ignore_patterns("__pycache__"),
    )
    shutil.copytree(
        base / "tests",
        directory / "tests",
        dirs_exist_ok=True,
        ignore=shutil.ignore_patterns("__pycache__"),
    )
    command = [
        str(base / ".venv/bin/python"),
        "-m",
        "pytest",
        "-q",
        *[f"tests/test_chronology.py::{t}" for t in (test, *controls)],
    ]
    environment = dict(os.environ, PYTHONPATH=str(directory), PYTHONDONTWRITEBYTECODE="1")
    record = dict(mutation=name, file=filename, old=old, new=new, test=test, controls=controls)
    for mode in ("baseline", "mutant"):
        if mode == "mutant":
            path = directory / "storymodel" / filename
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
