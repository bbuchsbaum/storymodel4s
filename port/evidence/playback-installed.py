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
env = dict(os.environ)
env.pop("PYTHONPATH", None)
commands = []
with tempfile.TemporaryDirectory(prefix="storymodel-playback-") as tmp:
    root = Path(tmp)
    shutil.copy2(base / "examples/playback_workflow.py", root / "example.py")
    p = subprocess.run(
        [str(python), "example.py", "example"],
        cwd=root,
        env=env,
        capture_output=True,
        text=True,
        check=True,
    )
    example = root / "example"
    summary = json.loads(p.stdout)

    def run(args, cwd=example):
        p = subprocess.run([str(cli), *args], cwd=cwd, env=env, capture_output=True, text=True)
        assert p.returncode == 0, (args, p.stdout, p.stderr)
        commands.append(args[0])
        return p

    def equal(a, b):
        assert json.loads(a.read_text()) == json.loads(b.read_text()), (a, b)

    run(
        [
            "prepare-playback",
            "media-source.json",
            "observations.json",
            "--allow-export",
            "--output",
            "cli-request.json",
        ]
    )
    equal(example / "request.json", example / "cli-request.json")
    run(["playback-prompt", "request.json", "--output", "cli-prompt.json"])
    prompt = json.loads((example / "cli-prompt.json").read_text())
    assert prompt["request"] == json.loads((example / "request.json").read_text())
    run(["import-playback", "request.json", "recording/import.json", "cli-recording"])
    run(["replay-playback", "request.json", "cli-recording", "--output", "cli-construction.json"])
    equal(example / "construction.json", example / "cli-construction.json")
    run(
        [
            "replay-playback",
            "request.json",
            "recording",
            "--account-only",
            "--output",
            "cli-account.json",
        ]
    )
    equal(example / "source-account.json", example / "cli-account.json")
    account = json.loads((example / "source-account.json").read_text())
    source = account["media_sources"][0]["id"]
    for collection, saved in [("events", "event"), ("states", "state")]:
        situation = account[collection][0]["id"]
        run(
            [
                "situation-playback",
                "source-account.json",
                situation,
                source,
                "--bins",
                "bins.json",
                "--output",
                f"cli-{saved}-playback.json",
            ]
        )
        equal(example / f"{saved}-playback.json", example / f"cli-{saved}-playback.json")
        assert json.loads(
            run(
                [
                    "verify-situation-playback",
                    "source-account.json",
                    situation,
                    source,
                    f"{saved}-playback.json",
                    "--bins",
                    "bins.json",
                ]
            ).stdout
        )["verified"]
    run(["situation-table", "source-account.json", "--output", "cli-situations.json"])
    equal(example / "source-situations.json", example / "cli-situations.json")
    for n in [1, 2]:
        run(
            [
                "situation-playback",
                f"paired-{n}.json",
                account["events"][0]["id"],
                source,
                "--bins",
                "bins.json",
                "--output",
                f"cli-paired-playback-{n}.json",
            ]
        )
        equal(example / f"paired-playback-{n}.json", example / f"cli-paired-playback-{n}.json")
    shutil.copytree(base / "tests/fixtures/context-v1", root / "legacy")
    run(
        ["replay-rich", "request.json", "recording", "--output", "cli-result.json"], root / "legacy"
    )
    equal(root / "legacy/result.json", root / "legacy/cli-result.json")
    request = json.loads((example / "request.json").read_text())
    request["export_permission"] = "denied"
    (example / "denied.json").write_text(json.dumps(request))
    refused = subprocess.run(
        [str(cli), "replay-playback", "denied.json", "nonexistent"],
        cwd=example,
        env=env,
        capture_output=True,
        text=True,
    )
    assert refused.returncode == 2 and "producer denies" in refused.stderr
    probe = subprocess.run(
        [
            str(python),
            "-c",
            "import storymodel; from storymodel.model import Account; "
            "from storymodel.rich_account import account_schema; "
            'assert "site-packages" in storymodel.__file__; '
            'assert "media_sources" not in account_schema("v5")["properties"]; '
            'assert Account.model_json_schema()["title"]=="Account"; print(storymodel.__file__)',
        ],
        cwd=root,
        env=env,
        capture_output=True,
        text=True,
        check=True,
    )
    artifacts = {
        str(p.relative_to(example)): {
            "bytes": p.stat().st_size,
            "sha256": hashlib.sha256(p.read_bytes()).hexdigest(),
        }
        for p in sorted(example.rglob("*.json"))
    }
    result = dict(
        summary=summary,
        import_path=probe.stdout.strip(),
        cli_invocations=len(commands),
        commands=sorted(set(commands)),
        legacy_replay_equal=True,
        denied_before_recording_read=True,
        api_cli_equal=True,
        artifacts=artifacts,
    )
    (base / "data/playback-installed.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k != "artifacts"}, indent=2))
    print("Artifacts:", len(artifacts))
