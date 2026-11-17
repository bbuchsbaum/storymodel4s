"""Run with wheel Python: SCRIPT PORT NEW_OUTPUT; recover actual saved-analysis stages."""

import json
import os
import subprocess
import sys
from hashlib import sha256
from pathlib import Path

import storymodel

port, root = (Path(arg).resolve() for arg in sys.argv[1:3])
assert not Path(storymodel.__file__).resolve().is_relative_to(port / "src")
environment = dict(os.environ)
environment.pop("PYTHONPATH", None)
cli = Path(sys.executable).with_name("storymodel")
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


def snapshot(directory):
    return {
        str(p.relative_to(directory)): sha256(p.read_bytes()).hexdigest()
        for p in directory.rglob("*")
        if p.is_file() and p.name != ".recorded-run.lock"
    }


example = subprocess.run(
    [sys.executable, str(port / "examples/sherlock_workflow.py"), str(root)],
    cwd=root.parent,
    env=environment,
    capture_output=True,
    text=True,
)
assert example.returncode == 0, example.stdout + example.stderr
restored = []
for number, stage in enumerate(("prepared-construction", "details", "mapping", "fidelity")):
    directory = root / "recall-1"
    prepared = stage == "prepared-construction"
    request = directory / ("prepared.json" if prepared else f"{stage}-request.json")
    recording = directory / ("recording" if prepared else f"{stage}-recording")
    manifest = recording / "import.json"
    supplied = root / f"recover-{stage}-reply.json"
    supplied.write_bytes(manifest.read_bytes())
    before = snapshot(recording)
    victim = manifest if number % 2 else next((recording / "replies").glob("*.json"))
    victim.unlink()
    args = [f"import-{stage}", request, supplied, recording, "--recover"]
    run(args)
    assert snapshot(recording) == before
    run(args)
    assert snapshot(recording) == before
    restored.append(stage)

hashes = {}
for number in (1, 2):
    plan = root / f"analysis-{number}.json"
    output = root / f"recovered-analysis-{number}.json"
    run(["replay-analysis", plan, "--output", output])
    assert output.read_bytes() == (root / f"analysis-{number}-result.json").read_bytes()
    run(["verify-analysis", plan, output])
    hashes[str(number)] = sha256(output.read_bytes()).hexdigest()

prior = json.loads((port / "evidence/sherlock-intake.json").read_bytes())
assert hashes == prior["installed"]["analysis_sha256"]
print(
    json.dumps(
        {
            "installed_package": str(storymodel.__file__),
            "cli_checks": len(calls),
            "commands": calls,
            "recovered_stages": restored,
            "analysis_sha256": hashes,
            "prior_analysis_artifacts_unchanged": True,
        },
        indent=2,
    )
)
