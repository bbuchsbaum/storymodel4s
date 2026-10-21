#!/usr/bin/env python3
"""Explicit, checksum-pinned model downloads; Python standard library only."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tempfile
import urllib.request

MANIFEST = Path(__file__).with_name("model-weights.json")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def verify(path, entry):
    if not path.is_file():
        raise ValueError(f"Missing model file: {path}; mount its external drive or fetch into another --root")
    if path.stat().st_size != entry["bytes"] or sha256(path) != entry["sha256"]:
        raise ValueError(f"Checksum/size mismatch: {path}; existing files are never overwritten")


def target(root, relative):
    p = PurePosixPath(relative)
    if p.is_absolute() or ".." in p.parts or not p.parts:
        raise ValueError(f"Unsafe manifest path: {relative}")
    return root.joinpath(*p.parts)


def fetch(path, entry):
    # A dangling link is an unavailable drive, not permission to replace the link.
    if path.exists() or path.is_symlink():
        verify(path, entry)
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    # Refuse writes through symlinked parents; verified existing files remain readable.
    if path.parent.resolve() != path.parent.absolute():
        raise ValueError(f"Download parent contains a symlink: {path.parent}")
    fd, name = tempfile.mkstemp(prefix="." + path.name + "-", suffix=".part", dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, "wb") as output, urllib.request.urlopen(entry["url"], timeout=60) as response:
            shutil.copyfileobj(response, output, length=1024 * 1024)
            output.flush()
            os.fsync(output.fileno())
        verify(temporary, entry)
        # Atomic no-clobber publication; another writer cannot be overwritten.
        os.link(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["list", "fetch", "verify"])
    parser.add_argument("model", nargs="?", default="all")
    parser.add_argument("--root", type=Path, default=Path(os.environ.get(
        "STORYMODEL4S_WEIGHTS_ROOT", str(Path.home() / ".cache/storymodel4s/weights"))),
                        help="Bundle cache root (default: STORYMODEL4S_WEIGHTS_ROOT or ~/.cache/storymodel4s/weights)")
    args = parser.parse_args()
    models = json.loads(MANIFEST.read_text())["models"]
    if args.model != "all" and args.model not in models:
        parser.error("unknown model; choose " + ", ".join(models))
    selected = models if args.model == "all" else {args.model: models[args.model]}
    root = args.root.expanduser().absolute()
    # Never recreate an unmounted /Volumes mount point on the internal disk.
    if root.parts[:2] == ("/", "Volumes"):
        mount = Path(*root.parts[:3])
        if not os.path.ismount(mount):
            parser.error(f"External volume is not mounted: {mount}")
    for key, model in selected.items():
        folder = target(root, model["cache_path"])
        if args.command == "list":
            print(f'{key}: {sum(f["bytes"] for f in model["files"].values()):,} bytes -> {folder}')
            continue
        for relative, entry in model["files"].items():
            path = target(folder, relative)
            (fetch if args.command == "fetch" else verify)(path, entry)
        print(f"{key}: verified {len(model['files'])} files -> {folder}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as exc:
        raise SystemExit(str(exc))
