"""Run from any cwd: PYTHON run_mutations.py REPO ARTIFACTS NEW_OUTPUT [REVISION]."""

import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

repo, artifacts, output = map(lambda p: Path(p).resolve(), sys.argv[1:4])
revision = sys.argv[4] if len(sys.argv) > 4 else "83a25cda5281a6b38a914c6f730300e8b5fca5ab"
output.mkdir(parents=True, exist_ok=False)
probes = Path(__file__).with_name("analysis-workflow-independent.py")
archive = subprocess.check_output(["git", "-C", str(repo), "archive", revision, "port/src"])
with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
    tar.extractall(output / "baseline", filter="data")
mutations = {
    "snapshot": (
        (
            "    if request.account != account:\n"
            '        raise ValueError(f"analysis stage {value.name!r} '
            'input differs from preceding account")\n'
        ),
        "    # MUTATION: continuity guard removed\n",
        1,
    ),
    "grant": (
        "        _grant(request.export_permission)\n",
        "        # MUTATION: pre-import grant removed\n",
        2,
    ),
}
for name, (old, new, expected_count) in mutations.items():
    shutil.copytree(output / "baseline", output / name)
    source = output / name / "port/src/storymodel/analysis_workflow.py"
    text = source.read_text()
    assert text.count(old) == expected_count
    source.write_text(text.replace(old, new))
    compile(source.read_text(), str(source), "exec")
    (output / f"{name}.diff").write_text(f"OLD:\n{old}\nNEW:\n{new}\n")
summary = {"revision": revision, "runs": {}}
for name in ("baseline", "snapshot", "grant"):
    env = os.environ | {
        "STORYMODEL_ANALYSIS_ARTIFACTS": str(artifacts),
        "PYTHONPATH": str(output / name / "port/src"),
    }
    log = output / f"{name}.log"
    with log.open("w") as stream:
        result = subprocess.run(
            [sys.executable, "-m", "pytest", "-q", str(probes)],
            env=env,
            stdout=stream,
            stderr=subprocess.STDOUT,
        )
    summary["runs"][name] = {
        "exit": result.returncode,
        "last_line": log.read_text().strip().splitlines()[-1],
    }
    assert result.returncode == (0 if name == "baseline" else 1)
(output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, indent=2))
