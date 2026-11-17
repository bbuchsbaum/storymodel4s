"""Verify the built wheel and connected workflow outside the source checkout."""

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
with tempfile.TemporaryDirectory(prefix="storymodel-construction-links-") as temporary:
    root = Path(temporary)
    shutil.copy2(base / "examples/connected_construction.py", root / "example.py")
    example_run = subprocess.run(
        [str(python), "example.py", "example"],
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

    for name in ("source", "recall-1", "recall-2"):
        directory = example / name
        inputs = ["request.json", "graph-proposals.json", "links.json"]
        run(["construction-link-prompt", *inputs[:2], "--output", "cli-prompt.json"], directory)
        equal(directory / "link-prompt.json", directory / "cli-prompt.json")
        run(["connect-rich", *inputs, "--output", "cli-connected.json"], directory)
        equal(directory / "connected.json", directory / "cli-connected.json")
        run(["connect-rich", *inputs, "--account-only", "--output", "cli-account.json"], directory)
        equal(directory / "account.json", directory / "cli-account.json")
        assert json.loads(
            run(["verify-connected-rich", *inputs, "connected.json"], directory).stdout
        )["verified"]
        run(["relation-table", "account.json", "--output", "cli-relations.json"], directory)
        equal(directory / "relations.json", directory / "cli-relations.json")
        run(
            ["replay-rich", "request.json", "recording", "--output", "cli-construction.json"],
            directory,
        )
        equal(directory / "construction.json", directory / "cli-construction.json")
    for number in (1, 2):
        inputs = [
            "source/account.json",
            f"recall-{number}/account.json",
            f"inference-spec-{number}.json",
        ]
        run(["infer-accounts", *inputs, "--output", f"cli-inference-{number}.json"])
        equal(example / f"inference-{number}.json", example / f"cli-inference-{number}.json")
        assert json.loads(
            run(["verify-account-inference", *inputs, f"inference-{number}.json"]).stdout
        )["valid"]
    request = json.loads((example / "source/request.json").read_text())
    request["export_permission"] = "denied"
    (root / "denied.json").write_text(json.dumps(request))
    refused = subprocess.run(
        [str(cli), "connect-rich", "denied.json", "missing-construction", "missing-links"],
        cwd=root,
        env=environment,
        capture_output=True,
        text=True,
    )
    assert refused.returncode == 2 and "producer denies" in refused.stderr
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
    }
    result = dict(
        import_path=probe.stdout.strip(),
        example=json.loads(example_run.stdout),
        commands=sorted(set(commands)),
        cli_invocations=len(commands),
        api_cli_equal=True,
        denied_before_supplementary_read=True,
        artifacts=artifacts,
    )
    (base / "data/construction-links-installed.json").write_text(
        json.dumps(result, indent=2) + "\n"
    )
    print(json.dumps({key: value for key, value in result.items() if key != "artifacts"}, indent=2))
    print("Artifacts:", len(artifacts))
