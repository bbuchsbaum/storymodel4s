"""Exclusive atomic publication and optionally externally pinned account reads."""

import hashlib
import os
import tempfile
from pathlib import Path
from typing import Any

from .model import Account, canonical, validate_account


def read_account(path: Path, *, expected_sha256: str | None = None) -> Account:
    """Check an optional caller-owned pin before structural validation.

    Without an independent pin this checks consistency, not provenance authentication.
    """
    raw = path.read_bytes()
    if expected_sha256 is not None and hashlib.sha256(raw).hexdigest() != expected_sha256:
        raise ValueError("account bytes differ from the expected external SHA-256")
    return validate_account(raw)


def publish_json(path: Path, value: Any) -> None:
    """Publish complete JSON atomically, refusing an existing destination (including symlinks)."""
    raw = (canonical(value) + "\n").encode("utf-8")
    descriptor, name = tempfile.mkstemp(prefix=".storymodel-", suffix=".tmp", dir=path.parent)
    stage = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(stage, path)
    finally:
        stage.unlink(missing_ok=True)


def export_account(path: Path, value: Account) -> None:
    """Apply producer export permission before constructing the destination."""
    account = validate_account(value)
    if account.export_permission != "allowed":
        raise PermissionError("producer denies evidence export")
    publish_json(path, account.model_dump(mode="json"))
