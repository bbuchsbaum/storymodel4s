"""Run with wheel Python: SCRIPT PORT NEW_OUTPUT; verify installed APIs and commands."""

import json
import os
import subprocess
import sys
from hashlib import sha256
from pathlib import Path

import storymodel

port, root = (Path(value).resolve() for value in sys.argv[1:3])
assert not Path(storymodel.__file__).resolve().is_relative_to(port / "src")
cli = Path(sys.executable).with_name("storymodel")
environment = dict(os.environ)
environment.pop("PYTHONPATH", None)
calls = []


def run(args):
    process = subprocess.run(
        [str(cli), *map(str, args)],
        cwd=root.parent,
        env=environment,
        capture_output=True,
        text=True,
    )
    assert process.returncode == 0, (args, process.stdout, process.stderr)
    calls.append(args[0])


example = subprocess.run(
    [sys.executable, str(port / "examples/sherlock_workflow.py"), str(root)],
    cwd=root.parent,
    env=environment,
    capture_output=True,
    text=True,
)
assert example.returncode == 0, example.stdout + example.stderr
for name in ("source", "recall-1", "recall-2"):
    directory = root / name
    source = name == "source"
    record = directory / ("repair.json" if source else "lineage.json")
    output = directory / "cli-prepared.json"
    args = [
        "prepare-sherlock-source" if source else "prepare-sherlock-recall",
        directory / "raw-input",
        record,
        "--key",
        "source" if source else "recall",
        "--allow-export",
        "--output",
        output,
        "--expected-input-sha256",
        sha256((directory / "raw-input").read_bytes()).hexdigest(),
        "--expected-record-sha256",
        sha256(record.read_bytes()).hexdigest(),
    ]
    if not source:
        args.extend(["--source-id", "synthetic-recall"])
    run(args)
    assert json.loads(output.read_bytes()) == json.loads((directory / "prepared.json").read_bytes())
    # Exercise existing recording commands with full study-format wrappers too.
    recording = directory / "cli-recording"
    run(["import-prepared-construction", output, directory / "reply.json", recording])
    result = directory / "cli-construction.json"
    run(["replay-prepared-construction", output, recording, "--output", result])
    assert json.loads(result.read_bytes()) == json.loads(
        (directory / "construction.json").read_bytes()
    )
    if not source:
        for clock in ("princeton", "openneuro"):
            timing = directory / f"cli-{clock}.json"
            run(["sherlock-recall-timing", output, "--clock", clock, "--output", timing])
            assert json.loads(timing.read_bytes()) == json.loads(
                (directory / f"{clock}-timing.json").read_bytes()
            )

hashes = {}
for number in (1, 2):
    output = root / f"cli-analysis-{number}.json"
    plan = root / f"analysis-{number}.json"
    run(["replay-analysis", plan, "--output", output])
    assert json.loads(output.read_bytes()) == json.loads(
        (root / f"analysis-{number}-result.json").read_bytes()
    )
    run(["verify-analysis", plan, output])
    hashes[str(number)] = sha256(output.read_bytes()).hexdigest()
print(
    json.dumps(
        {
            "installed_package": str(storymodel.__file__),
            "cli_checks": len(calls),
            "commands": calls,
            "analysis_sha256": hashes,
        },
        indent=2,
    )
)
