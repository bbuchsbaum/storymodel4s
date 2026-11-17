"""Discriminating guard mutations run in isolated copies; production is never edited."""

import json
import os
import shutil
import subprocess
from pathlib import Path

base = Path(__file__).resolve().parents[1]
root = base / "data/native-assessment-mutations"
root.mkdir(exist_ok=True)
cases = [
    (
        "situation-support",
        "native_assessment.py",
        "if not contained_in(citation.support, situation.evidence):",
        "if False and not contained_in(citation.support, situation.evidence):",
        "test_native_assessment.py::test_native_fidelity_refuses_forged_citations_without_changing_pending_slot[outside-situation]",
    ),
    (
        "hidden-recall-media",
        "native_assessment.py",
        'if not account.media_sources or any(s.side != "source" for s in account.media_sources):',
        "if not account.media_sources:",
        "test_native_assessment.py::test_native_consumer_refuses_hidden_recall_media_even_with_selected_text_unit",
    ),
    (
        "account-version-boundary",
        "model.py",
        'if self.schema_version != "storymodel.account/v8":',
        'if False and self.schema_version != "storymodel.account/v8":',
        "test_native_assessment_version.py::test_v8_native_assessment_evidence_cannot_be_downgraded_by_copy_or_raw_wire",
    ),
]
controls = [
    "test_fidelity.py::test_fidelity_only_changes_pending_grade_and_projected_coverage",
    "test_native_assessment.py::test_native_direct_grade_accepts_exact_intervals_or_explicit_points[True]",
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
