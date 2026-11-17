"""Run with wheel Python: SCRIPT PORT NEW_OUTPUT; compare actual installed API and CLI."""

import json
import os
import subprocess
import sys
from hashlib import sha256
from pathlib import Path

import storymodel
from storymodel.composition import CompositionReadout, PlaybackComposition
from storymodel.sherlock_composition import SherlockComposition, SherlockCompositionReadout

port, root = (Path(arg).resolve() for arg in sys.argv[1:3])
assert not Path(storymodel.__file__).resolve().is_relative_to(port / "src")
environment = dict(os.environ)
environment.pop("PYTHONPATH", None)
cli = Path(sys.executable).with_name("storymodel")
calls = []


def run(args, expected=0):
    process = subprocess.run(
        [str(cli), *map(str, args)],
        cwd=root.parent,
        env=environment,
        capture_output=True,
        text=True,
    )
    assert process.returncode == expected, (args, process.stdout, process.stderr)
    calls.append({"command": args[0], "exit": process.returncode})
    return process


example = subprocess.run(
    [sys.executable, str(port / "examples/composed_playback.py"), str(root)],
    cwd=root.parent,
    env=environment,
    capture_output=True,
    text=True,
)
assert example.returncode == 0, example.stdout + example.stderr
prepared = root / "source-prepared.json"
composed = root / "cli-composition.json"
run(
    [
        "sherlock-compose",
        prepared,
        "--expected-input-sha256",
        sha256(prepared.read_bytes()).hexdigest(),
        "--output",
        composed,
    ]
)
assert composed.read_bytes() == (root / "sherlock-composition.json").read_bytes()
for name, units in (("sherlock", []), ("point", ["--unit", "sherlock:row:0003"])):
    output = root / f"cli-{name}.json"
    run(
        [
            "sherlock-composition-readout",
            composed,
            "--bins",
            root / "bins.json",
            *units,
            "--output",
            output,
        ]
    )
    assert output.read_bytes() == (root / f"{name}-readout.json").read_bytes()
    run(
        [
            "verify-sherlock-composition-readout",
            composed,
            output,
            "--bins",
            root / "bins.json",
            *units,
        ]
    )

# Check the behavior independently of agreement between two entry points.
whole = json.loads((root / "cli-sherlock.json").read_bytes())["playback"]
assert whole["end_tick"] == "106000"
assert whole["seconds_per_tick"] == "1/2500"
assert whole["duration_seconds"] == "42/1"
assert [c["duration_seconds"] for c in whole["cells"]] == ["30/1", "0/1", "12/1"]
assert whole["support"]["points"] == ["50000"]
point = json.loads((root / "cli-point.json").read_bytes())["playback"]
assert point["duration_seconds"] == "0/1"
generic = json.loads((root / "generic-readout.json").read_bytes())
assert generic["duration_seconds"] == "2/3"
assert [s["target"] for s in generic["segments"]] == [
    {"start": "0", "end": "24"},
    {"start": "24", "end": "48"},
    {"start": "48", "end": "72"},
]
assert len({s["occurrence"] for s in generic["segments"]}) == 3

forged = json.loads((root / "cli-sherlock.json").read_bytes())
forged["playback"]["duration_seconds"] = "212/5"
bad_result = root / "forged-readout.json"
bad_result.write_text(json.dumps(forged))
failure = run(
    [
        "verify-sherlock-composition-readout",
        composed,
        bad_result,
        "--bins",
        root / "bins.json",
    ],
    expected=2,
)
assert "differs from actual selected evidence" in failure.stderr

denied = json.loads(composed.read_bytes())
denied["export_permission"] = "denied"
denied_input = root / "denied-composition.json"
denied_input.write_text(json.dumps(denied))
denied_output = root / "must-not-exist.json"
failure = run(
    [
        "sherlock-composition-readout",
        denied_input,
        "--bins",
        root / "missing-bins.json",
        "--output",
        denied_output,
    ],
    expected=2,
)
assert "producer denies" in failure.stderr
assert not denied_output.exists()

schemas = json.loads((port / "schemas/composition.json").read_bytes())
for cls in (
    PlaybackComposition,
    CompositionReadout,
    SherlockComposition,
    SherlockCompositionReadout,
):
    assert schemas[cls.__name__] == cls.model_json_schema()

print(
    json.dumps(
        {
            "installed_package": str(storymodel.__file__),
            "cli_checks": len(calls),
            "commands": calls,
            "schemas_checked": len(schemas),
            "api_cli_bytes_equal": True,
            "full_media_seconds": "212/5",
            "annotation_seconds": "42/1",
            "gap_seconds": "2/5",
            "point_duration_seconds": "0/1",
            "repeated_occurrence_duration_seconds": "2/3",
        },
        indent=2,
    )
)
