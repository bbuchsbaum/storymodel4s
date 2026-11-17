"""Discriminating guard mutations run in isolated copies; production is never edited."""

import json
import os
import shutil
import subprocess
from pathlib import Path

base = Path(__file__).resolve().parents[1]
root = base / "data/construction-links-mutations"
root.mkdir(exist_ok=True)
cases = [
    (
        "qualified-name",
        "row = resolutions.get(ref.row)",
        "row = next(iter(resolutions.values()))",
        "test_row_local_names_select_distinct_source_and_recall_occurrences_and_preserve_graph",
    ),
    (
        "construction-binding",
        "if links.construction_sha256 != construction_sha:",
        "if False and links.construction_sha256 != construction_sha:",
        "test_receipts_bind_exact_construction_and_link_bytes_and_revalidate_typed_values",
    ),
    (
        "exact-link-bytes",
        'sha256(links_raw.encode("utf-8")).hexdigest()',
        "digest(strict_json(links_raw))",
        "test_receipts_bind_exact_construction_and_link_bytes_and_revalidate_typed_values",
    ),
    (
        "row-boundary",
        "not row.span.start",
        "False and not row.span.start",
        "test_row_escaped_and_utf16_split_quotes_are_rejected",
    ),
]
controls = [
    "test_link_processing_outcomes_and_prompt_export_denial_are_explicit",
    "test_non_situation_and_unadmitted_row_references_are_rejected_without_graph_mutation[entity]",
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
    shutil.copy2(
        base / "tests/test_construction_links.py", directory / "test_construction_links.py"
    )
    command = [
        str(base / ".venv/bin/python"),
        "-m",
        "pytest",
        "-q",
        *[f"test_construction_links.py::{t}" for t in (test, *controls)],
    ]
    environment = dict(os.environ, PYTHONPATH=str(directory), PYTHONDONTWRITEBYTECODE="1")
    record = dict(
        mutation=name, file="construction_links.py", old=old, new=new, test=test, controls=controls
    )
    for mode in ("baseline", "mutant"):
        if mode == "mutant":
            path = directory / "storymodel/construction_links.py"
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
