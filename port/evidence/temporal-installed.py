"""Exercise chronology API/CLI and numerical consumption from an installed wheel."""

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
environment = dict(os.environ)
environment.pop("PYTHONPATH", None)
commands = []
with tempfile.TemporaryDirectory(prefix="storymodel-chronology-") as temporary:
    root = Path(temporary)
    for name in ("chronology_workflow", "connected_construction", "playback_workflow"):
        shutil.copy2(base / f"examples/{name}.py", root / f"{name}.py")
    example_run = subprocess.run(
        [str(python), "chronology_workflow.py", "example"],
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
        inputs = ["temporal-input.json", "temporal-proposals.json"]
        run(["admit-temporal", *inputs, "--output", "cli-result.json"], directory)
        equal(directory / "temporal-result.json", directory / "cli-result.json")
        run(
            ["admit-temporal", *inputs, "--account-only", "--output", "cli-account.json"], directory
        )
        equal(directory / "temporal-account.json", directory / "cli-account.json")
        assert json.loads(
            run(["verify-temporal", *inputs, "temporal-result.json"], directory).stdout
        )["valid"]
        run(["temporal-table", "temporal-account.json", "--output", "cli-table.json"], directory)
        equal(directory / "temporal-table.json", directory / "cli-table.json")
        query = json.loads((directory / "temporal-query.json").read_text())
        args = ["temporal-account.json", query["source"], query["target"], query["context"]]
        run(["query-temporal", *args, "--output", "cli-query.json"], directory)
        equal(directory / "temporal-query.json", directory / "cli-query.json")
        assert json.loads(
            run(["verify-temporal-query", *args, "temporal-query.json"], directory).stdout
        )["valid"]
    for number in (1, 2):
        inputs = [
            "source/temporal-account.json",
            f"recall-{number}/temporal-account.json",
            f"inference-spec-{number}.json",
        ]
        run(["infer-accounts", *inputs, "--output", f"cli-inference-{number}.json"])
        equal(example / f"inference-{number}.json", example / f"cli-inference-{number}.json")
        assert json.loads(
            run(["verify-account-inference", *inputs, f"inference-{number}.json"]).stdout
        )["valid"]
    denied = json.loads((example / "source/temporal-input.json").read_text())
    denied["export_permission"] = "denied"
    (root / "denied.json").write_text(json.dumps(denied))
    refusal = subprocess.run(
        [str(cli), "admit-temporal", "denied.json", "missing-proposals"],
        cwd=root,
        env=environment,
        capture_output=True,
        text=True,
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
    result = dict(
        import_path=probe.stdout.strip(),
        example=[json.loads(line) for line in example_run.stdout.splitlines()],
        commands=sorted(set(commands)),
        cli_invocations=len(commands),
        api_cli_equal=True,
        denied_before_supplementary_reads=True,
    )
    (base / "data/temporal-installed.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))

# Run the same legacy workflow with old source and the new installed wheel. This
# compares observable artifacts, including account recipes and v1/v2 projection paths.

with tempfile.TemporaryDirectory(prefix="storymodel-chronology-compat-") as temporary:
    root = Path(temporary)
    reference = "ec1e91f45"
    archive = root / "reference.tar"
    with archive.open("wb") as output:
        subprocess.run(
            ["git", "archive", f"{reference}:port/src"],
            cwd=base.parent,
            stdout=output,
            check=True,
        )
    old = root / "old-source"
    old.mkdir()
    with tarfile.open(archive) as source:
        source.extractall(old, filter="data")
    for name in ("hierarchy_workflow", "connected_construction", "playback_workflow"):
        shutil.copy2(base / f"examples/{name}.py", root / f"{name}.py")
    for label, env in (("old", dict(environment, PYTHONPATH=str(old))), ("new", environment)):
        process = subprocess.run(
            [str(python), "hierarchy_workflow.py", label],
            cwd=root,
            env=env,
            capture_output=True,
            text=True,
        )
        assert process.returncode == 0, (label, process.stdout, process.stderr)
    left = {p.relative_to(root / "old"): p.read_bytes() for p in (root / "old").rglob("*.json")}
    right = {p.relative_to(root / "new"): p.read_bytes() for p in (root / "new").rglob("*.json")}
    assert left.keys() == right.keys()
    assert left == right, [str(p) for p in left if left[p] != right[p]]
    compatibility = dict(
        reference=reference,
        artifacts=len(left),
        bytes=sum(map(len, left.values())),
        byte_identical=True,
    )
    (base / "data/temporal-compatibility.json").write_text(
        json.dumps(compatibility, indent=2) + "\n"
    )
    print(json.dumps(compatibility))
