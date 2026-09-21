#!/usr/bin/env python3
"""The door to Friends recall: the Friends sealed-split declaration and its count-only accounting.

Why: docs/data/friends/test-split.json keeps Friends usable as a final test only if every reader
asks first. Readers `import friends_guard` and call `require_readable` before opening the recall
workbook; tests/test_friends_guard.py flags a reader that names the Friends recall path without
doing so. The rules themselves (committed split, final opening, ledger) live in sealed_split.py.

After the seal, the whole-corpus reads here use only the committed split and are logged as
count-only reads; the sealing-time forms refuse once the split exists.
"""

import hashlib
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import sealed_split as ss  # noqa: E402
from sealed_split import FinalOpening, GuardRefusal  # noqa: E402,F401

FRIENDS = ss.Corpus(
    name="friends",
    split="docs/data/friends/test-split.json",
    ledger="docs/data/friends/test-split-reads.json",
    split_schema="storymodel4s.friends.test-split/v1",
)
SOURCE_MANIFEST = "docs/data/friends/source-manifest.json"
WORKBOOK = "FriendsRecallScoring.xlsx"


def load_split():
    return ss.load_split(FRIENDS)


def require_readable(participants, *, final_opening=None):
    return ss.require_readable(FRIENDS, participants, final_opening=final_opening)


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


def _require_unsealed():
    path = ss.REPO / FRIENDS.split
    if (
        path.exists()
        or path.is_symlink()
        or ss._git("cat-file", "-e", f"HEAD:{FRIENDS.split}").returncode == 0
    ):
        raise GuardRefusal("the Friends split is sealed: sealing-time reads are closed")


def _sides(sealing_split):
    """The split to count over: the committed one, or at sealing time the one being sealed."""
    if sealing_split is None:
        return load_split()
    _require_unsealed()
    return ss.validate_split(FRIENDS, sealing_split)


def _workbook(data_root):
    """The recall workbook, refused unless its bytes match the admitted digest."""
    manifest = json.loads(ss._committed(SOURCE_MANIFEST).read_text(encoding="utf-8"))
    record = next(a for a in manifest["artifacts"] if a["path"] == WORKBOOK)
    path = Path(data_root) / "friends" / WORKBOOK
    if hashlib.sha256(path.read_bytes()).hexdigest() != record["sha256"]:
        raise GuardRefusal("Friends recall workbook does not match its admitted digest")
    return path, record["sha256"]


def _counts(workbook, ids):
    sys.path.insert(0, str(HERE.parent / "corpus"))
    from xlsx_rows import sheet_rows

    return [_unit_count(list(sheet_rows(str(workbook), s))) for s in ids]


def development_unit_counts(data_root, *, sealing_split=None):
    """Per-participant gold-unit counts for development participants only."""
    split = _sides(sealing_split)
    ids = split["development"]["participants"]
    if sealing_split is None:
        require_readable(ids)
    workbook, _ = _workbook(data_root)
    return _counts(workbook, ids)


def seal_accounting(data_root, *, sealing_split=None, purpose=None):
    """Per-side participant and gold-unit totals: integers only, never one test participant's.

    After the seal this counts over the committed split and appends a count-only read to the
    ledger; `purpose` is then required.
    """
    split = _sides(sealing_split)
    if sealing_split is None and not (purpose and str(purpose).strip()):
        raise GuardRefusal(
            "a count-only read of the sealed split must state its purpose"
        )

    def count():
        workbook, digest = _workbook(data_root)
        totals = {
            side: {
                "participants": len(split[side]["participants"]),
                "goldUnits": sum(_counts(workbook, split[side]["participants"])),
            }
            for side in ("development", "test")
        }
        return {"workbookSha256": digest, "totals": totals}

    if sealing_split is not None:
        return count()
    with ss.count_only_read(FRIENDS, purpose) as complete:
        result = count()
        complete(result["totals"])
        return result
