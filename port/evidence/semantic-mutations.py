"""Independent failing witnesses in isolated copies; production files are never mutated."""

import json
import os
import shutil
import subprocess
from pathlib import Path

base = Path(__file__).resolve().parents[1]
root = base / "data/semantic-mutations"
root.mkdir(exist_ok=True)
cases = [
    (
        "half-cosine",
        "semantic.py",
        "value=measured[target] / 2",
        "value=measured[target] / 4",
        "test_semantic.py::test_distances_reach_local_costs_and_actual_inference_without_account_changes",
    ),
    (
        "explicit-universe-before-budget",
        "semantic.py",
        "if selected.targets is None\n            else [",
        "if True\n            else [",
        "test_semantic.py::test_explicit_universe_is_filtered_before_budget_and_never_widened",
    ),
    (
        "scale-safe-cosine",
        "embedding.py",
        "    query_norm = math.hypot",
        "    query_scale = document_scale = 1.0\n    query_norm = math.hypot",
        "test_embedding.py::test_cosine_distance_preserves_huge_and_subnormal_four_dimensional_orientations",
    ),
]
controls = [
    "test_semantic.py::test_provider_outcomes_remain_distinct_and_only_observed_values_enter_cache[missing]",
    "test_semantic.py::test_reply_binding_refuses_misattributed_batch[foreign-request]",
]
records = []
for name, filename, old, new, test in cases:
    directory = root / name
    directory.mkdir(exist_ok=True)
    for source, destination in (
        (base / "src/storymodel", directory / "storymodel"),
        (base / "tests", directory / "tests"),
    ):
        shutil.copytree(
            source, destination, dirs_exist_ok=True, ignore=shutil.ignore_patterns("__pycache__")
        )
    command = [
        str(base / ".venv/bin/python"),
        "-m",
        "pytest",
        "-q",
        *[f"tests/{item}" for item in (test, *controls)],
    ]
    environment = dict(os.environ, PYTHONPATH=str(directory), PYTHONDONTWRITEBYTECODE="1")
    record = dict(mutation=name, file=filename, old=old, new=new, test=test, controls=controls)
    for mode in ("baseline", "mutant"):
        if mode == "mutant":
            path = directory / "storymodel" / filename
            content = path.read_text()
            assert content.count(old) == 1
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
            process.stdout + process.stderr
        )
        if mode == "mutant":
            assert "AssertionError" in process.stdout
        record[mode] = process.stdout.splitlines()[-1]
    record["compiled"] = True
    records.append(record)
    print(name, record["mutant"], flush=True)
(root / "summary.json").write_text(json.dumps(records, indent=2) + "\n")
