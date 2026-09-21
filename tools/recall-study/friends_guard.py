#!/usr/bin/env python3
"""The one door to Friends recall: refuses sealed test participants outside final opening.

Why: the Friends test split (docs/data/friends/test-split.json) stays model-untouched only if every
reader asks this module first. Development participants pass. A test participant is refused unless
the caller holds a FinalOpening naming a committed release-candidate manifest whose SHA-256 matches;
each final opening is appended to the read-count ledger, so the count is kept by the mechanism and
not by memory.

Readers of Friends recall must `import friends_guard` and call `require_readable` before opening the
workbook; tests/test_friends_guard.py fails any reader under tools/ or the Scala sources that does
not. The one sanctioned whole-corpus read is `seal_accounting`, which returns per-side integer totals
only and never a test participant's individual count.
"""

import datetime
import hashlib
import json
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[2]
SPLIT = REPO / "docs/data/friends/test-split.json"
LEDGER = REPO / "docs/data/friends/test-split-reads.json"
SOURCE_MANIFEST = REPO / "docs/data/friends/source-manifest.json"
SPLIT_SCHEMA = "storymodel4s.friends.test-split/v1"
LEDGER_SCHEMA = "storymodel4s.friends.test-split-reads/v1"
WORKBOOK = "FriendsRecallScoring.xlsx"


class GuardRefusal(Exception):
    """A request the guard will not serve; the message says which rule refused it."""


def _load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def _sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def load_split(path=SPLIT):
    """The committed split, validated: two disjoint named sides covering exactly the pool."""
    split = _load(path)
    if split.get("schema") != SPLIT_SCHEMA:
        raise GuardRefusal(f"unknown split schema {split.get('schema')!r}")
    dev = split["development"]["participants"]
    test = split["test"]["participants"]
    pool = split["pool"]["participants"]
    if set(dev) & set(test):
        raise GuardRefusal("split sides overlap")
    if sorted(dev + test) != sorted(pool) or len(set(pool)) != len(pool):
        raise GuardRefusal("split sides do not partition the pool")
    return split


class FinalOpening:
    """The explicit capability to read test participants: a committed manifest and its digest."""

    def __init__(self, manifest_path, manifest_sha256, purpose):
        if not purpose or not str(purpose).strip():
            raise GuardRefusal("a final opening must state its purpose")
        self.manifest_path = Path(manifest_path)
        self.manifest_sha256 = str(manifest_sha256)
        self.purpose = str(purpose)


def _verify_opening(opening, repo):
    if not isinstance(opening, FinalOpening):
        raise GuardRefusal("final opening requires a FinalOpening capability")
    path = opening.manifest_path
    path = path if path.is_absolute() else Path(repo) / path
    path = path.resolve()
    if not path.is_relative_to(Path(repo).resolve()):
        raise GuardRefusal("release-candidate manifest must live in the repository")
    if not path.is_file():
        raise GuardRefusal(f"release-candidate manifest {path} does not exist")
    actual = _sha256_file(path)
    if actual != opening.manifest_sha256:
        raise GuardRefusal(
            "release-candidate manifest digest does not match the opening"
        )
    return path, actual


def _append_read(ledger_path, entry):
    ledger = _load(ledger_path)
    if ledger.get("schema") != LEDGER_SCHEMA:
        raise GuardRefusal(f"unknown ledger schema {ledger.get('schema')!r}")
    ledger["reads"].append(entry)
    ledger["readCount"] = len(ledger["reads"])
    with open(ledger_path, "w", encoding="utf-8") as fh:
        json.dump(ledger, fh, indent=2, sort_keys=True)
        fh.write("\n")


def require_readable(
    participants, *, final_opening=None, split_path=SPLIT, ledger_path=LEDGER, repo=REPO
):
    """Return the participants if the caller may read them; refuse otherwise.

    Unknown participants are refused. Test participants are refused unless `final_opening`
    verifies, in which case the read is appended to the ledger before access is granted.
    """
    split = load_split(split_path)
    requested = list(participants)
    if not requested:
        raise GuardRefusal("no participants requested")
    pool = set(split["pool"]["participants"])
    unknown = sorted(set(requested) - pool)
    if unknown:
        raise GuardRefusal(f"participants outside the Friends pool: {unknown}")
    test = sorted(set(requested) & set(split["test"]["participants"]))
    if not test:
        return requested
    if final_opening is None:
        raise GuardRefusal(
            f"sealed Friends test participants refused outside final opening: {test}"
        )
    manifest, digest = _verify_opening(final_opening, repo)
    _append_read(
        ledger_path,
        {
            "utc": datetime.datetime.now(datetime.timezone.utc).isoformat(
                timespec="seconds"
            ),
            "participants": test,
            "releaseCandidateManifest": str(manifest.relative_to(Path(repo).resolve())),
            "releaseCandidateManifestSha256": digest,
            "purpose": final_opening.purpose,
        },
    )
    return requested


def _unit_count(rows):
    """Gold units in one participant sheet: maximal runs of rows with the same veridical event.

    A row contributes its WhichEvent, normalized to a number, only when RecallType is 1; any other
    row, including a blank one, ends the current run. On the pinned workbook this gives the 630
    units recorded in docs/plans/2026-09-17-friends-hill-climb-and-normalization.md:142.
    """
    header = rows[0]
    event, kind = header.index("WhichEvent"), header.index("RecallType")

    def number(v):
        if v in (None, ""):
            return None
        try:
            f = float(v)
        except (TypeError, ValueError):
            raise GuardRefusal(f"non-numeric coded value {v!r}")
        return int(f) if f == int(f) else f

    units, previous = 0, None
    for row in rows[1:]:
        value = number(row[event]) if event < len(row) else None
        if (number(row[kind]) if kind < len(row) else None) != 1:
            value = None
        if value is not None and value != previous:
            units += 1
        previous = value
    return units


def development_unit_counts(data_root, split, split_path=SPLIT):
    """Per-participant gold-unit counts for development participants, through the guard."""
    sys.path.insert(0, str(REPO / "tools/corpus"))
    from xlsx_rows import sheet_rows

    ids = split["development"]["participants"]
    if split_path is not None and Path(split_path).exists():
        require_readable(ids, split_path=split_path)
    elif set(ids) & set(split["test"]["participants"]):
        raise GuardRefusal("development list names a test participant")
    workbook = Path(data_root) / "friends" / WORKBOOK
    return [_unit_count(list(sheet_rows(str(workbook), s))) for s in ids]


def seal_accounting(data_root, split, source_manifest_path=SOURCE_MANIFEST):
    """Per-side participant and gold-unit totals; the only whole-corpus read this module allows.

    Verifies the workbook's bytes against the admitted digest first. Returns integers only: no test
    participant's individual count leaves this function.
    """
    sys.path.insert(0, str(REPO / "tools/corpus"))
    from xlsx_rows import sheet_rows

    record = next(
        a for a in _load(source_manifest_path)["artifacts"] if a["path"] == WORKBOOK
    )
    workbook = Path(data_root) / "friends" / WORKBOOK
    if _sha256_file(workbook) != record["sha256"]:
        raise GuardRefusal("Friends recall workbook does not match its admitted digest")
    totals = {}
    for side in ("development", "test"):
        ids = split[side]["participants"]
        totals[side] = {
            "participants": len(ids),
            "goldUnits": sum(
                _unit_count(list(sheet_rows(str(workbook), s))) for s in ids
            ),
        }
    return {"workbookSha256": record["sha256"], "totals": totals}
