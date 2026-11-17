"""Run isolated compiling mutants: PYTHON SCRIPT REPO NEW_OUTPUT [REVISION]."""

import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

repo, output = (Path(value).resolve() for value in sys.argv[1:3])
revision = sys.argv[3] if len(sys.argv) > 3 else "092228d86edc5dc76753bde3ae3946a330c9b362"
output.mkdir(parents=True, exist_ok=False)
probe = Path(__file__).with_name("prepared-construction-independent.py").resolve()
archive = subprocess.check_output(["git", "-C", str(repo), "archive", revision, "port/src"])
with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
    tar.extractall(output / "baseline", filter="data")
cases = {
    "raw-binding": (
        "source_preparation.py",
        "        if annotations != self.annotations:\n"
        '            raise ValueError("prepared source annotations differ from exact raw JSON")\n',
        "",
        "test_changed_raw_metadata_cannot_keep_stale_parsed_receipt",
    ),
    "import-binding": (
        "prepared_construction.py",
        '    if supplied.prepared_sha256 != digest(prepared.model_dump(mode="json")):\n'
        '        raise ValueError("recording belongs to another prepared input")\n',
        "",
        "test_import_refuses_foreign_raw_before_root_created",
    ),
    "native-time-order": (
        "source_preparation.py",
        "ordered_rows = sorted(annotations.rows, "
        "key=lambda row: (part_order[row.part], row.ordinal))",
        "ordered_rows = sorted(annotations.rows, "
        'key=lambda row: (part_order[row.part], int(row.native_start_tick or "0")))',
        "test_declared_order_overrides_both_clock_resets_and_within_part_times",
    ),
}
for label, (filename, old, new, _) in cases.items():
    shutil.copytree(output / "baseline", output / label)
    path = output / label / "port/src/storymodel" / filename
    original = path.read_text()
    assert original.count(old) == 1
    mutant = original.replace(old, new)
    compile(mutant, str(path), "exec")
    path.write_text(mutant)

summary = {"revision": revision, "runs": {}}
for label in ("baseline", *cases):
    environment = os.environ | {
        "PYTHONPATH": str(output / label / "port/src"),
        "PYTHONDONTWRITEBYTECODE": "1",
    }
    process = subprocess.run(
        [sys.executable, "-B", "-m", "pytest", "-q", str(probe)],
        env=environment,
        cwd=output / label,
        capture_output=True,
        text=True,
    )
    (output / f"{label}.log").write_text(process.stdout + process.stderr)
    if label == "baseline":
        assert process.returncode == 0 and "3 passed" in process.stdout
    else:
        assert process.returncode == 1 and "1 failed, 2 passed" in process.stdout
        assert cases[label][3] in process.stdout
    summary["runs"][label] = {
        "exit": process.returncode,
        "last_line": process.stdout.strip().splitlines()[-1],
    }
(output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, indent=2))
