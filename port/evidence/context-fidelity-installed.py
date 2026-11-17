"""Installed contextual fidelity checks and byte-for-byte v1 workflow compatibility."""

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

with tempfile.TemporaryDirectory(prefix="storymodel-context-compat-") as temporary:
    root = Path(temporary)
    reference = "4ce3ca536"
    archive = root / "reference.tar"
    with archive.open("wb") as output:
        subprocess.run(
            ["git", "archive", f"{reference}:port/src"], cwd=base.parent, stdout=output, check=True
        )
    old = root / "old-source"
    old.mkdir()
    with tarfile.open(archive) as source:
        source.extractall(old, filter="data")
    for name in ("fidelity_workflow", "rich_alignment_workflow"):
        shutil.copy2(base / f"examples/{name}.py", root / f"{name}.py")
    for label, env in (("old", dict(environment, PYTHONPATH=str(old))), ("new", environment)):
        process = subprocess.run(
            [str(python), "fidelity_workflow.py", label],
            cwd=root,
            env=env,
            capture_output=True,
            text=True,
        )
        assert process.returncode == 0, (label, process.stdout, process.stderr)

    def stable_artifacts(directory):
        return {
            p.relative_to(directory): p.read_bytes()
            for p in directory.rglob("*.json")
            if "recording" not in p.relative_to(directory).parts
        }

    left, right = stable_artifacts(root / "old"), stable_artifacts(root / "new")
    assert left.keys() == right.keys()
    assert left == right, [str(p) for p in left if left[p] != right[p]]
    old_schema = subprocess.check_output(
        ["git", "show", f"{reference}:port/schemas/fidelity.json"], cwd=base.parent
    )
    assert old_schema == (base / "schemas/fidelity.json").read_bytes()
    compatibility = dict(
        reference=reference,
        artifacts=len(left),
        bytes=sum(map(len, left.values())),
        byte_identical=True,
        v1_schema_byte_identical=True,
        excluded=(
            "recording execution internals; "
            "compared saved request/prompt/reply/result/account/views"
        ),
    )
    (base / "data/context-fidelity-compatibility.json").write_text(
        json.dumps(compatibility, indent=2) + "\n"
    )
    print(json.dumps(compatibility))

with tempfile.TemporaryDirectory(prefix="storymodel-context-installed-") as temporary:
    root = Path(temporary)
    shutil.copy2(base / "examples/context_fidelity_workflow.py", root / "example.py")
    process = subprocess.run(
        [str(python), "example.py", "example"],
        cwd=root,
        env=environment,
        capture_output=True,
        text=True,
    )
    assert process.returncode == 0, (process.stdout, process.stderr)
    example = root / "example"
    commands = []

    def run(arguments, expected=0):
        process = subprocess.run(
            [str(cli), *arguments],
            cwd=example,
            env=environment,
            capture_output=True,
            text=True,
        )
        assert process.returncode == expected, (arguments, process.stdout, process.stderr)
        commands.append(arguments[0])
        return process

    for case, result_name in (
        ("named", "named"),
        ("unresolved-required", "rejected"),
        ("unresolved-independent", "independent"),
    ):
        request_path = f"inputs/{case}-fidelity-request.json"
        request = json.loads((example / request_path).read_text())
        directory = example / f"cli-{case}"
        directory.mkdir()
        (directory / "account.json").write_text(json.dumps(request["account"]))
        for field in ("identity_annotations", "holder_proposals"):
            (directory / f"{field}.json").write_text(request["context_evidence"][field])
        run(
            [
                "prepare-fidelity",
                str(directory / "account.json"),
                request["unit"],
                "--identity-annotations",
                str(directory / "identity_annotations.json"),
                "--holder-proposals",
                str(directory / "holder_proposals.json"),
                "--output",
                str(directory / "request.json"),
            ]
        )
        assert json.loads((directory / "request.json").read_text()) == request
        crlf_request = json.loads(json.dumps(request))
        for field in ("identity_annotations", "holder_proposals"):
            raw = json.dumps(json.loads(request["context_evidence"][field]), indent=2) + "\n"
            raw = raw.replace("\n", "\r\n")
            (directory / f"crlf-{field}.json").write_bytes(raw.encode("utf-8"))
            crlf_request["context_evidence"][field] = raw
        run(
            [
                "prepare-fidelity",
                str(directory / "account.json"),
                request["unit"],
                "--identity-annotations",
                str(directory / "crlf-identity_annotations.json"),
                "--holder-proposals",
                str(directory / "crlf-holder_proposals.json"),
                "--output",
                str(directory / "crlf-request.json"),
            ]
        )
        assert json.loads((directory / "crlf-request.json").read_text()) == crlf_request
        run(["fidelity-prompt", request_path, "--output", str(directory / "prompt.json")])
        assert json.loads((directory / "prompt.json").read_text()) == json.loads(
            (example / f"prompts/{case}-fidelity.json").read_text()
        )
        run(
            [
                "import-fidelity",
                request_path,
                f"recordings/{case}-fidelity/import.json",
                str(directory / "recording"),
            ]
        )
        run(
            [
                "replay-fidelity",
                request_path,
                str(directory / "recording"),
                "--output",
                str(directory / "receipt.json"),
            ]
        )
        assert json.loads((directory / "receipt.json").read_text()) == json.loads(
            (example / f"results/{result_name}-fidelity-receipt.json").read_text()
        )
        account_output = directory / "replayed-account.json"
        run(
            [
                "replay-fidelity",
                request_path,
                str(directory / "recording"),
                "--account-only",
                "--output",
                str(account_output),
            ],
            expected=2 if result_name == "rejected" else 0,
        )
        if result_name == "rejected":
            assert not account_output.exists()
        else:
            assert json.loads(account_output.read_text()) == json.loads(
                (example / f"results/{result_name}-account.json").read_text()
            )
    denied = request["account"] | {"export_permission": "denied"}
    (example / "denied.json").write_text(json.dumps(denied))
    refusal = run(
        [
            "prepare-fidelity",
            "denied.json",
            request["unit"],
            "--identity-annotations",
            "missing",
            "--holder-proposals",
            "missing",
        ],
        expected=2,
    )
    assert "producer denies" in refusal.stderr
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
        example=json.loads(process.stdout.splitlines()[-1]),
        commands=sorted(set(commands)),
        cli_invocations=len(commands),
        api_cli_equal=True,
        rejected_account_only_refused=True,
        crlf_recordings_retained_exactly=True,
        denied_before_extra_reads=True,
    )
    (base / "data/context-fidelity-installed.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))
