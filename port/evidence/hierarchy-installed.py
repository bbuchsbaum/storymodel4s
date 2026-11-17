"""Exercise primary hierarchy and numerical consumption from a wheel outside the checkout."""

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

base = Path(__file__).resolve().parents[1]
python = base / "data/wheel-env/bin/python"
cli = base / "data/wheel-env/bin/storymodel"
environment = dict(os.environ)
environment.pop("PYTHONPATH", None)
commands = []
with tempfile.TemporaryDirectory(prefix="storymodel-hierarchy-") as temporary:
    root = Path(temporary)
    for name in ("hierarchy_workflow", "connected_construction", "playback_workflow"):
        shutil.copy2(base / f"examples/{name}.py", root / f"{name}.py")
    example_run = subprocess.run(
        [str(python), "hierarchy_workflow.py", "example"],
        cwd=root,
        env=environment,
        capture_output=True,
        text=True,
        check=True,
    )
    example = root / "example"

    def run(arguments, cwd=example):
        process = subprocess.run(
            [str(cli), *arguments],
            cwd=cwd,
            env=environment,
            capture_output=True,
            text=True,
        )
        assert process.returncode == 0, (arguments, process.stdout, process.stderr)
        commands.append(arguments[0])
        return process

    def equal(left, right):
        assert json.loads(left.read_text()) == json.loads(right.read_text()), (left, right)

    for name in ("source", "recall-1", "recall-2", "native"):
        directory = example / name
        account = "source-account.json" if name == "native" else "account.json"
        run(
            ["build-hierarchy", account, "hierarchy-spec.json", "--output", "cli-hierarchy.json"],
            directory,
        )
        equal(directory / "hierarchy.json", directory / "cli-hierarchy.json")
        assert json.loads(
            run(
                ["verify-hierarchy", account, "hierarchy-spec.json", "hierarchy.json"], directory
            ).stdout
        )["valid"]
        hierarchy = json.loads((directory / "hierarchy.json").read_text())
        run(
            [
                "query-hierarchy",
                account,
                "hierarchy.json",
                hierarchy["root"],
                "--output",
                "cli-query.json",
            ],
            directory,
        )
        equal(directory / "hierarchy-query.json", directory / "cli-query.json")
    groups = set(json.loads((example / "source/hierarchy.json").read_text())["groups"].values())
    for number in (1, 2):
        inputs = [
            "source/account.json",
            f"recall-{number}/account.json",
            f"inference-spec-{number}.json",
        ]
        run(["infer-accounts", *inputs, "--output", f"cli-inference-{number}.json"])
        equal(example / f"inference-{number}.json", example / f"cli-inference-{number}.json")
        result = json.loads((example / f"cli-inference-{number}.json").read_text())
        assert groups <= {cell["target"] for cell in result["evidence"]["cells"]}
        assert json.loads(
            run(["verify-account-inference", *inputs, f"inference-{number}.json"]).stdout
        )["valid"]
    denied = json.loads((example / "source/account.json").read_text())
    denied["export_permission"] = "denied"
    (root / "denied.json").write_text(json.dumps(denied))
    for arguments in (
        ["build-hierarchy", "denied.json", "missing-spec"],
        ["query-hierarchy", "denied.json", "missing-result", "missing-anchor"],
    ):
        refusal = subprocess.run(
            [str(cli), *arguments], cwd=root, env=environment, capture_output=True, text=True
        )
        assert refusal.returncode == 2 and "producer denies" in refusal.stderr
    probe = subprocess.run(
        [
            str(python),
            "-c",
            'import storymodel; assert "site-packages" in storymodel.__file__; '
            "print(storymodel.__file__)",
        ],
        cwd=root,
        env=environment,
        capture_output=True,
        text=True,
        check=True,
    )
    artifacts = {
        str(path.relative_to(example)): {
            "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }
        for path in sorted(example.rglob("*.json"))
        if path.name.startswith(("hierarchy", "inference-", "projection"))
    }
    result = dict(
        import_path=probe.stdout.strip(),
        example=[json.loads(line) for line in example_run.stdout.splitlines()],
        commands=sorted(set(commands)),
        cli_invocations=len(commands),
        api_cli_equal=True,
        generated_segments_scored_in_both_recalls=len(groups),
        denied_before_supplementary_reads=True,
        artifacts=artifacts,
    )
    (base / "data/hierarchy-installed.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k != "artifacts"}))
