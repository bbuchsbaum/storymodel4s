"""Four compiling guard removals against the exact corrected ac9926c26 archive."""

import json
import os
import pathlib
import shutil
import subprocess
import sys
import tarfile
import tempfile

repo = pathlib.Path(sys.argv[1]).resolve()
revision = sys.argv[2]
root = pathlib.Path(tempfile.mkdtemp(prefix="storymodel-sherlock-mutations-"))
archive = root / "source.tar"
with archive.open("wb") as output:
    subprocess.run(["git", "archive", revision, "port"], cwd=repo, stdout=output, check=True)
with tarfile.open(archive) as source_archive:
    source_archive.extractall(root, filter="data")
base = root / "port"
python = sys.executable
shutil.copyfile(
    repo / "port/evidence/sherlock-intake-independent.py", base / "tests/test_cold_declarations.py"
)
source = pathlib.Path("src/storymodel/sherlock_source.py")
mutations = {
    "ordinary_end": (
        [("        or second_offset != rows[ordinary[1] - 1].raw_end_seconds\n", "")],
        (
            "tests/test_sherlock_source.py::test_notebook_offset_matches_"
            "actual_ordinary_end_not_only_other_declarations"
        ),
    ),
    "break_continuity": (
        [
            (
                (
                    "        if row.raw_start_seconds != previous_end:\n          "
                    '  _fail("scan breaks must continue from ordinary run-1 end w'
                    'ithout gaps or overlaps")\n'
                ),
                "",
            )
        ],
        "tests/test_cold_declarations.py::test_scan_break_must_start_at_ordinary_end",
    ),
    "edition_binding": (
        [
            (
                (
                    '    _equal(\n        _field(local, "experimentalEditionBindin'
                    'g", "media-part-local-seconds"),\n        "owner-declared-and'
                    '-byte-identified",\n        "media-part-local-seconds/experim'
                    'entalEditionBinding",\n    )\n'
                ),
                "",
            )
        ],
        "tests/test_cold_declarations.py::test_edition_binding_must_be_established",
    ),
    "notebook_provenance": (
        [
            (
                '    repair_id = _string(_field(block, "id", "repair"), "repair/id")\n',
                '    repair_id = block.get("id")\n',
            ),
            (
                (
                    '        or _string(_field(notebook, "derivationReceipt", "no'
                    'tebook"), "notebook/derivationReceipt")\n        != repair_id'
                    "\n"
                ),
                '        or notebook.get("derivationReceipt") != repair_id\n',
            ),
        ],
        "tests/test_cold_declarations.py::test_notebook_provenance_cannot_be_missing_on_both_sides",
    ),
}
controls = [
    "tests/test_cold_declarations.py::test_valid_control",
    "tests/test_cold_declarations.py::test_external_raw_pin_control",
]
results = []
for name, (edits, test) in mutations.items():
    dest = root / ("mutant-" + name)
    shutil.copytree(base, dest, ignore=shutil.ignore_patterns("__pycache__", ".pytest_cache"))
    code = (dest / source).read_text()
    for old, new in edits:
        assert code.count(old) == 1, (name, old)
        code = code.replace(old, new)
    (dest / source).write_text(code)
    compiled = subprocess.run(
        [python, "-m", "py_compile", str(dest / source)], capture_output=True, text=True
    )
    assert compiled.returncode == 0, compiled.stderr
    env = dict(os.environ, PYTHONPATH=str(dest / "src"), PYTHONDONTWRITEBYTECODE="1")
    run = subprocess.run(
        [python, "-m", "pytest", "-q", test, *controls],
        cwd=dest,
        env=env,
        capture_output=True,
        text=True,
    )
    (root / (name + ".log")).write_text(run.stdout + run.stderr)
    assert run.returncode == 1, (name, run.stdout, run.stderr)
    assert "1 failed, 2 passed" in run.stdout, (name, run.stdout)
    assert "DID NOT RAISE" in run.stdout, (name, run.stdout)
    results.append(
        dict(
            mutation=name,
            test=test,
            compile_exit=compiled.returncode,
            pytest_exit=run.returncode,
            failed=1,
            passed=2,
            log=str(root / (name + ".log")),
        )
    )
(root / "mutation-results.json").write_text(
    json.dumps(dict(review_sha=revision, mutants=results), indent=2) + "\n"
)
print(json.dumps(dict(review_sha=revision, root=str(root), mutants=results), indent=2))
