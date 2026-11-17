"""Installed native workflow and recorded text compatibility, outside the checkout."""

import io
import json
import os
import shutil
import subprocess
import tarfile
import tempfile
from pathlib import Path

base = Path(__file__).resolve().parents[1]
python = base / "data/wheel-env/bin/python"
cli = base / "data/wheel-env/bin/storymodel"
env = dict(os.environ)
env.pop("PYTHONPATH", None)
count = 0
with tempfile.TemporaryDirectory(prefix="native-assessment-installed-") as temporary:
    root = Path(temporary)
    for name in (
        "native_assessment_workflow",
        "playback_workflow",
        "fidelity_workflow",
        "rich_alignment_workflow",
        "context_fidelity_workflow",
    ):
        shutil.copy2(base / f"examples/{name}.py", root / f"{name}.py")
    process = subprocess.run(
        [str(python), "native_assessment_workflow.py", "example"],
        cwd=root,
        env=env,
        capture_output=True,
        text=True,
    )
    assert process.returncode == 0, process.stdout + process.stderr
    summary = json.loads(process.stdout.splitlines()[-1])
    import_path = subprocess.check_output(
        [str(python), "-c", "import storymodel; print(storymodel.__file__)"],
        cwd=root,
        env=env,
        text=True,
    ).strip()
    assert "site-packages/storymodel" in import_path

    def run(*args, succeeds=True):
        global count
        count += 1
        result = subprocess.run(
            [str(cli), *map(str, args)], cwd=root, env=env, capture_output=True, text=True
        )
        assert (result.returncode == 0) is succeeds, result.stdout + result.stderr
        return (
            (json.loads(result.stdout) if result.stdout.strip() else None)
            if succeeds
            else result.stderr
        )

    for number in (1, 2):
        case = root / f"example/recall-{number}"
        account = json.loads((case / "account-v8.json").read_text())
        unit = account["details"][0]["unit"]
        assert run("prepare-mapping", case / "paired.json", unit, "--native-source") == json.loads(
            (case / "mapping-request.json").read_text()
        )
        for kind in ("mapping", "fidelity"):
            request = case / f"{kind}-request.json"
            assert run(f"{kind}-prompt", request) == json.loads(
                (case / f"{kind}-prompt.json").read_text()
            )
            recording = case / f"cli-{kind}"
            run(f"import-{kind}", request, case / f"{kind}-recording/import.json", recording)
            result = run(f"replay-{kind}", request, recording)
            assert result == json.loads((case / f"{kind}-result.json").read_text())
            if kind == "mapping":
                mapped_path = case / "mapped-account.json"
                mapped_path.write_text(json.dumps(result["account"]))
                assert run("prepare-fidelity", mapped_path, unit) == json.loads(
                    (case / "fidelity-request.json").read_text()
                )
            else:
                final = case / "final-account.json"
                final.write_text(json.dumps(result["account"]))
                assert run("analyze", final) == json.loads((case / "views.json").read_text())
                if number == 2:
                    assert "discard" in run(
                        "replay-fidelity", request, recording, "--account-only", succeeds=False
                    )
        denied = case / "denied.json"
        account["export_permission"] = "denied"
        denied.write_text(json.dumps(account))
        assert "denies" in run("prepare-mapping", denied, unit, "--native-source", succeeds=False)

    baseline = "3e06be43e"
    archived = subprocess.check_output(["git", "archive", f"{baseline}:port/src"], cwd=base.parent)
    old = root / "old-src"
    old.mkdir()
    with tarfile.open(fileobj=io.BytesIO(archived)) as archive:
        archive.extractall(old, filter="data")
    comparisons = []
    for example in ("fidelity_workflow", "context_fidelity_workflow"):
        outputs = []
        for label, environment in (("old", dict(env, PYTHONPATH=str(old))), ("new", env)):
            dest = root / f"{label}-{example}"
            result = subprocess.run(
                [str(python), f"{example}.py", str(dest)],
                cwd=root,
                env=environment,
                capture_output=True,
                text=True,
            )
            assert result.returncode == 0, result.stdout + result.stderr
            outputs.append(
                {
                    str(p.relative_to(dest)): p.read_bytes()
                    for p in dest.rglob("*.json")
                    if not any("recording" in part for part in p.relative_to(dest).parts)
                }
            )
        assert outputs[0] == outputs[1], [
            p for p in outputs[0] if outputs[0][p] != outputs[1].get(p)
        ]
        comparisons.append(
            {
                "example": example,
                "artifacts": len(outputs[0]),
                "bytes": sum(map(len, outputs[0].values())),
                "identical": True,
            }
        )
    evidence = {
        "installed_path": import_path,
        "cli_invocations": count,
        "api_cli_equal": True,
        "example": summary,
        "legacy_baseline": baseline,
        "legacy_workflows": comparisons,
        "limits": (
            "Combined rich schema declarations broaden; comparison covers saved "
            "workflow data/prompts/results, excluding recording execution internals."
        ),
    }
    (base / "data/native-assessment-installed.json").write_text(
        json.dumps(evidence, indent=2) + "\n"
    )
    print(json.dumps(evidence, indent=2))
