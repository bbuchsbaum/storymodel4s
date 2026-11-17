"""Run with the installed wheel's Python; compare every CLI with the API example."""

import json
import os
import subprocess
import sys
from hashlib import sha256
from pathlib import Path

import storymodel
from storymodel.prepared_construction import (
    prepared_construction_prompt,
    read_prepared_construction,
)

port = Path(sys.argv[1]).resolve()
root = Path(sys.argv[2]).resolve()
cli = Path(sys.executable).with_name("storymodel")
assert not Path(storymodel.__file__).resolve().is_relative_to(port / "src")
environment = dict(os.environ)
environment.pop("PYTHONPATH", None)
calls = []


def run(arguments):
    process = subprocess.run(
        [str(cli), *map(str, arguments)],
        cwd=root.parent,
        env=environment,
        capture_output=True,
        text=True,
    )
    assert process.returncode == 0, (arguments, process.stdout, process.stderr)
    calls.append(arguments[0])


example = subprocess.run(
    [sys.executable, str(port / "examples/prepared_workflow.py"), str(root)],
    cwd=root.parent,
    env=environment,
    capture_output=True,
    text=True,
)
assert example.returncode == 0, example.stdout + example.stderr
for name, side, key in (
    ("source", "source", "source"),
    ("recall-1", "recall", "recall"),
    ("recall-2", "recall", "recall"),
):
    directory = root / name
    request = directory / "cli-prepared.json"
    run(
        [
            "prepare-construction",
            directory / "raw-input",
            "--key",
            key,
            "--side",
            side,
            "--allow-export",
            "--expected-input-sha256",
            sha256((directory / "raw-input").read_bytes()).hexdigest(),
            "--output",
            request,
        ]
    )
    assert json.loads(request.read_bytes()) == json.loads(
        (directory / "prepared.json").read_bytes()
    )
    prompt = directory / "cli-prompt.json"
    run(["prepared-construction-prompt", request, "--output", prompt])
    assert json.loads(prompt.read_bytes()) == json.loads(
        prepared_construction_prompt(read_prepared_construction(request))
    )
    recording = directory / "cli-recording"
    run(["import-prepared-construction", request, directory / "reply.json", recording])
    result = directory / "cli-result.json"
    run(
        [
            "replay-prepared-construction",
            request,
            recording,
            "--expected-import-sha256",
            sha256((recording / "import.json").read_bytes()).hexdigest(),
            "--output",
            result,
        ]
    )
    assert json.loads(result.read_bytes()) == json.loads(
        (directory / "construction.json").read_bytes()
    )
    raw_reply = directory / "raw-reply.json"
    raw_reply.write_bytes(json.loads((directory / "reply.json").read_bytes())["reply"].encode())
    run(["verify-prepared-construction", request, raw_reply, result])

hashes = {}
for number in (1, 2):
    plan = root / f"analysis-{number}.json"
    output = root / f"cli-analysis-{number}.json"
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
