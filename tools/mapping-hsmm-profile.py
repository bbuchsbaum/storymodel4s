#!/usr/bin/env python3
"""Apply the H HSMM recorder only to a verified baseline export or clone."""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

BASELINE = "90ebaa8f48dd45f70db13bcccc67dda1c0020234"
PATCH_SHA256 = "4b45740293b93519799c7cec730b9d61ea978171d7617a21dd761ffad478dd24"
INPUTS = {
    "align/src/main/scala/storymodel4s/align/hsmm.scala": "a26ae87f9bb3be979049571806c8ce73b7e08a4ad689a57c0118329a1717197d",
    "pipeline/src/test/scala/storymodel4s/pipeline/MappingWorkloadCapture.scala": "8b668965c02f3b774cac598f3bf2d81cc565d02cdef06008e1535b59ca108c8d",
}
MARKER = "private[storymodel4s] object HsmmProfileRecorder:"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fail(message: str) -> None:
    raise SystemExit(f"mapping-hsmm-profile: refused: {message}")


def git(root: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", "-C", str(root), *args], text=True, capture_output=True)


def verify_target(root: Path) -> None:
    primary = Path(__file__).resolve().parents[1]
    if root == primary or primary in root.parents:
        fail("target is this primary repository or one of its descendants")
    inside = git(root, "rev-parse", "--is-inside-work-tree")
    if inside.returncode:
        return  # A hash-verified archive has no git metadata to inspect.
    top = git(root, "rev-parse", "--show-toplevel")
    if top.returncode or Path(top.stdout.strip()).resolve() != root:
        fail("target is not a repository top-level")
    if (root / ".git").is_file():
        fail("linked worktrees are not accepted")
    dirty = git(root, "status", "--porcelain", "--untracked-files=all")
    if dirty.returncode or dirty.stdout:
        fail("git target is dirty (tracked or untracked files)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export", type=Path, help="standalone clean export or clone at the pinned baseline")
    parser.add_argument("--dry-run", action="store_true", help="verify patch applicability without writing")
    args = parser.parse_args()
    root = args.export.resolve()
    patch = Path(__file__).with_suffix(".patch")
    if not root.is_dir():
        fail(f"not a directory: {root}")
    verify_target(root)
    if digest(patch) != PATCH_SHA256:
        fail("patch receipt hash differs")
    for relative, expected in INPUTS.items():
        path = root / relative
        if not path.is_file():
            fail(f"missing {relative}")
        if MARKER in path.read_text() or digest(path) != expected:
            fail(f"{relative} is not the exact uninstrumented {BASELINE} input")
    head = git(root, "rev-parse", "HEAD")
    if head.returncode == 0 and head.stdout.strip() != BASELINE:
        fail(f"clone HEAD {head.stdout.strip()} is not pinned baseline {BASELINE}")
    check = git(root, "apply", "--check", str(patch))
    if check.returncode:
        fail(check.stderr.strip() or "patch does not apply")
    if not args.dry_run:
        applied = git(root, "apply", str(patch))
        if applied.returncode:
            fail(applied.stderr.strip() or "git apply failed")
    receipt = {
        "baseline": BASELINE,
        "dryRun": args.dry_run,
        "patchSha256": digest(patch),
        "inputSha256": INPUTS,
        "outputSha256": {} if args.dry_run else {p: digest(root / p) for p in INPUTS},
    }
    print(json.dumps(receipt, sort_keys=True))


if __name__ == "__main__":
    main()
