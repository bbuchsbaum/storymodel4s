#!/usr/bin/env python3
"""Shared committed-split checks and durable read accounting for cooperative recall readers.

Corpus wrappers fix the repository paths. Splits, release manifests and ledgers must match
HEAD; a ledger must be committed after each read before another can start. Count-only attempts
are recorded before reading, including failures. This is not a sandbox: a caller that ignores
the wrapper, edits globals or obtains a path dynamically can bypass it.
"""

from contextlib import contextmanager
from dataclasses import dataclass
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

REPO = Path(__file__).resolve().parents[2]
SPLIT_SCHEMA = "storymodel4s.sealed-split/v1"
LEDGER_SCHEMA = "storymodel4s.sealed-split-reads/v1"


class GuardRefusal(Exception):
    """A request the guard will not serve; the message identifies the refused rule."""


@dataclass(frozen=True)
class Corpus:
    """Repository-owned paths and schema identifying one sealed corpus."""

    name: str
    split: str
    ledger: str
    split_schema: str = SPLIT_SCHEMA


@dataclass(frozen=True)
class FinalOpening:
    """Explicit purpose and digest of a committed release-candidate manifest."""

    manifest_path: str
    manifest_sha256: str
    purpose: str

    def __post_init__(self):
        _purpose(self.purpose)


def _purpose(value):
    if not isinstance(value, str) or not value.strip():
        raise GuardRefusal("a read must state its purpose")


def _git(*args):
    return subprocess.run(["git", *args], cwd=REPO, capture_output=True)


def _committed(rel):
    """A regular repository file whose index and actual bytes match HEAD (including skip-worktree)."""
    rel = Path(rel)
    if rel.is_absolute() or ".." in rel.parts:
        raise GuardRefusal(f"{rel}: must be a repository-relative path")
    path = REPO / rel
    if path.resolve() != REPO.resolve() / rel or not path.is_file():
        raise GuardRefusal(f"{rel}: must be a regular repository file, not a symlink")
    head = _git("cat-file", "blob", f"HEAD:{rel.as_posix()}")
    if head.returncode != 0:
        raise GuardRefusal(f"{rel}: not committed in HEAD")
    if (
        _git("diff", "--quiet", "HEAD", "--", rel.as_posix()).returncode != 0
        or _git("diff", "--cached", "--quiet", "HEAD", "--", rel.as_posix()).returncode
        != 0
        or path.read_bytes() != head.stdout
    ):
        raise GuardRefusal(f"{rel}: differs from HEAD, so not committed")
    return path


def validate_split(corpus, split):
    """Require a named schema and two nonempty, disjoint sides partitioning unique IDs."""
    if split.get("schema") != corpus.split_schema or split.get("corpus") != corpus.name:
        raise GuardRefusal("unknown split schema or wrong corpus")
    try:
        groups = [split[key]["participants"] for key in ("development", "test", "pool")]
    except (KeyError, TypeError) as exc:
        raise GuardRefusal("split is missing participant lists") from exc
    if any(
        not isinstance(ids, list)
        or not ids
        or any(not isinstance(s, str) or not s.strip() for s in ids)
        or len(set(ids)) != len(ids)
        for ids in groups
    ):
        raise GuardRefusal("split participant lists must contain unique nonempty IDs")
    dev, test, pool = groups
    if set(dev) & set(test):
        raise GuardRefusal("split sides overlap")
    if sorted(dev + test) != sorted(pool):
        raise GuardRefusal("split sides do not partition the pool")
    return split


def load_split(corpus):
    return validate_split(corpus, json.loads(_committed(corpus.split).read_text()))


def _save(path, ledger):
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".ledger-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            json.dump(ledger, out, indent=2, sort_keys=True)
            out.write("\n")
            out.flush()
            os.fsync(out.fileno())
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


@contextmanager
def _ledger(corpus):
    lock_ref = _git("rev-parse", "--git-path", "sealed-split-ledger.lock")
    if lock_ref.returncode != 0:
        raise GuardRefusal("cannot locate repository ledger lock")
    lock_path = REPO / os.fsdecode(lock_ref.stdout.strip())
    with open(lock_path, "a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        # This check belongs INSIDE the lock: the preceding reader may have dirtied it.
        path = _committed(corpus.ledger)
        ledger = json.loads(path.read_text())
        if ledger.get("schema") != LEDGER_SCHEMA or ledger.get("corpus") != corpus.name:
            raise GuardRefusal("unknown ledger schema or wrong corpus")
        for kind, counter in (
            ("finalOpenings", "finalOpeningCount"),
            ("countOnlyReads", "countOnlyReadCount"),
        ):
            if (
                not isinstance(ledger.get(kind), list)
                or type(ledger.get(counter)) is not int  # noqa: E721 - bool is not a count
                or ledger[counter] != len(ledger[kind])
            ):
                raise GuardRefusal("ledger count does not match its records")
        yield path, ledger


def _append(path, ledger, kind, entry):
    record = {
        "utc": datetime.datetime.now(datetime.timezone.utc).isoformat(
            timespec="seconds"
        ),
        **entry,
    }
    ledger[kind].append(record)
    ledger["finalOpeningCount"] = len(ledger["finalOpenings"])
    ledger["countOnlyReadCount"] = len(ledger["countOnlyReads"])
    _save(path, ledger)
    return record


def require_readable(corpus, participants, *, final_opening=None):
    """Authorize requested IDs, recording a final opening before granting test access."""
    split = load_split(corpus)
    requested = list(participants)
    if not requested:
        raise GuardRefusal("no participants requested")
    if any(not isinstance(s, str) for s in requested):
        raise GuardRefusal("participant IDs must be strings")
    unknown = sorted(set(requested) - set(split["pool"]["participants"]))
    if unknown:
        raise GuardRefusal(f"participants outside the {corpus.name} pool: {unknown}")
    test = sorted(set(requested) & set(split["test"]["participants"]))
    if not test:
        return requested
    if final_opening is None:
        raise GuardRefusal("sealed test participants refused outside final opening")
    if not isinstance(final_opening, FinalOpening):
        raise GuardRefusal("final opening requires a FinalOpening capability")
    _purpose(final_opening.purpose)
    manifest = _committed(final_opening.manifest_path)
    digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
    if digest != final_opening.manifest_sha256:
        raise GuardRefusal(
            "release-candidate manifest digest does not match the opening"
        )
    with _ledger(corpus) as (path, ledger):
        _append(
            path,
            ledger,
            "finalOpenings",
            {
                "participants": test,
                "releaseCandidateManifest": str(final_opening.manifest_path),
                "releaseCandidateManifestSha256": digest,
                "purpose": final_opening.purpose,
            },
        )
    return requested


@contextmanager
def count_only_read(corpus, purpose):
    """Record an attempt before reading; yield a function recording only per-side totals.

    The lock covers the read and completion. A crash leaves a durable `started` attempt;
    a caught failure marks it `failed`. Both count as reads and require a ledger commit.
    """
    _purpose(purpose)
    split = load_split(corpus)
    with _ledger(corpus) as (path, ledger):
        record = _append(
            path,
            ledger,
            "countOnlyReads",
            {
                "purpose": purpose,
                "status": "started",
            },
        )

        def complete(totals):
            if (
                not isinstance(totals, dict)
                or set(totals) != {"development", "test"}
                or any(
                    not isinstance(side, dict)
                    or set(side) != {"participants", "goldUnits"}
                    or any(type(v) is not int or v < 0 for v in side.values())  # noqa: E721 - reject bool
                    or side["participants"] != len(split[name]["participants"])
                    for name, side in totals.items()
                )
            ):
                raise GuardRefusal(
                    "count-only totals must be nonnegative integers for the sealed sides"
                )
            record.update(status="completed", totals=totals)
            _save(path, ledger)

        try:
            yield complete
            if record["status"] != "completed":
                raise GuardRefusal("count-only read did not record totals")
        except BaseException:
            record["status"] = "failed"
            record.pop("totals", None)
            _save(path, ledger)
            raise


def empty_ledger(corpus):
    return {
        "schema": LEDGER_SCHEMA,
        "corpus": corpus.name,
        "finalOpeningCount": 0,
        "finalOpenings": [],
        "countOnlyReadCount": 0,
        "countOnlyReads": [],
    }
