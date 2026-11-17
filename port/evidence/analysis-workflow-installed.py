"""Qualify saved analysis replay from an installed wheel outside the checkout."""

import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

base = Path(__file__).resolve().parents[1]
python = base / "data/wheel-env/bin/python"
cli = base / "data/wheel-env/bin/storymodel"
env = dict(os.environ)
env.pop("PYTHONPATH", None)
with tempfile.TemporaryDirectory(prefix="analysis-installed-") as temporary:
    root = Path(temporary)
    for name in (
        "analysis_workflow",
        "context_fidelity_workflow",
        "native_assessment_workflow",
        "playback_workflow",
        "correspondence_workflow",
    ):
        shutil.copy2(base / f"examples/{name}.py", root / f"{name}.py")
    process = subprocess.run(
        [str(python), "analysis_workflow.py", "examples"],
        cwd=root,
        env=env,
        capture_output=True,
        text=True,
    )
    assert process.returncode == 0, process.stdout + process.stderr
    summaries = json.loads(process.stdout)
    imported = subprocess.check_output(
        [str(python), "-c", "import storymodel; print(storymodel.__file__)"],
        cwd=root,
        env=env,
        text=True,
    ).strip()
    assert "site-packages/storymodel" in imported
    count = 0
    for branch in ("context", "native", "correspondence"):
        for number in (1, 2):
            plan = root / f"examples/{branch}/analysis-{number}.json"
            receipt = plan.with_name(f"analysis-{number}-result.json")
            for args in (("replay-analysis", plan), ("verify-analysis", plan, receipt)):
                result = subprocess.run(
                    [str(cli), *map(str, args)], cwd=root, env=env, capture_output=True, text=True
                )
                assert result.returncode == 0, result.stdout + result.stderr
                payload = json.loads(result.stdout)
                assert payload == (
                    json.loads(receipt.read_bytes())
                    if args[0] == "replay-analysis"
                    else {"verified": True}
                )
                count += 1
    print(json.dumps(dict(import_path=imported, cli_checks=count, branches=summaries)))
