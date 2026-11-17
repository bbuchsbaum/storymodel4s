"""Discriminating guard mutations run in isolated copies; production is never edited."""

import json
import os
import shutil
import subprocess
from pathlib import Path

base = Path(__file__).resolve().parents[1]
root = base / "data/context-fidelity-mutations"
root.mkdir(exist_ok=True)
cases = [
    (
        "required-dependency",
        "context_fidelity.py",
        'if unresolved and status == "completed" and grade != "unverifiable":',
        'if False and unresolved and status == "completed" and grade != "unverifiable":',
        "test_context_fidelity.py::test_unresolved_required_holder_blocks_grade_but_independent_answer_is_admitted",
    ),
    (
        "context-accounting",
        "context_fidelity.py",
        "if set(accounting.considered_contexts) != set(contexts):",
        "if False and set(accounting.considered_contexts) != set(contexts):",
        "test_context_fidelity.py::test_contextual_accounting_rejects_foreign_duplicate_and_missing_aliases",
    ),
    (
        "recall-closure",
        "context_fidelity.py",
        "recall = () if detail.event is None else (situations[detail.event],)",
        'recall = tuple(s for s in situations.values() if s.side == "recall" '
        "and any(e.document == detail.support.document for e in text_evidence(s.evidence)))",
        "test_context_fidelity.py::test_linked_recall_closure_exposes_only_local_excerpt_coordinates_and_no_private_state",
    ),
    (
        "holder-recording-binding",
        "attribution.py",
        "if supplied.account_sha256 != account_sha:",
        "if False and supplied.account_sha256 != account_sha:",
        "test_context_fidelity.py::test_context_recordings_remain_bound_to_the_post_mapping_account",
    ),
]
controls = [
    "test_fidelity.py::test_fidelity_only_changes_pending_grade_and_projected_coverage",
    "test_context_fidelity.py::test_context_background_cannot_promote_root_or_replace_target_owned_quote",
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
        *[f"tests/{t}" for t in (test, *controls)],
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
