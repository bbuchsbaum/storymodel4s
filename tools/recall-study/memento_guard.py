#!/usr/bin/env python3
"""Fixed-path Memento admission and sealed recall access; raw rows never leave accounting APIs.

Only read_units returns text, after participant authorization. Survey and sealing forms close
once a split exists. Post-seal accounting records an attempt before reading and returns aggregates.
"""

from collections import Counter
import hashlib
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parent / "corpus")]
import sealed_split as ss  # noqa: E402
from sealed_split import FinalOpening, GuardRefusal  # noqa: E402,F401
import memento_task as task  # noqa: E402
from xlsx_rows import sheet_names, sheet_rows  # noqa: E402

MEMENTO = ss.Corpus(
    "memento",
    "docs/data/memento/test-split.json",
    "docs/data/memento/test-split-reads.json",
)
SOURCE = "docs/data/memento/source-manifest.json"
TASK = "docs/data/memento/task-definition.json"
POPULATION = "docs/data/memento/recall-population.json"
WORKBOOK = "Subjects.xlsx"


def _record(rel):
    return json.loads(ss._committed(rel).read_text())


def _pins():
    return {
        name: hashlib.sha256(ss._committed(rel).read_bytes()).hexdigest()
        for name, rel in (
            ("sourceManifestSha256", SOURCE),
            ("taskDefinitionSha256", TASK),
            ("populationSha256", POPULATION),
            ("taskImplementationSha256", "tools/recall-study/memento_task.py"),
            ("xlsxReaderSha256", "tools/corpus/xlsx_rows.py"),
        )
    }


def _require_unsealed():
    path = ss.REPO / MEMENTO.split
    if (
        path.exists()
        or path.is_symlink()
        or ss._git("cat-file", "-e", f"HEAD:{MEMENTO.split}").returncode == 0
    ):
        raise GuardRefusal("the Memento split is sealed: sealing-time reads are closed")


def _book(data_root):
    source, definition = _record(SOURCE), _record(TASK)
    expected = next(a["sha256"] for a in source["artifacts"] if a["id"] == WORKBOOK)
    path = Path(data_root) / "memento" / WORKBOOK
    if (
        hashlib.sha256(path.read_bytes()).hexdigest() != expected
        or definition["workbookSha256"] != expected
    ):
        raise GuardRefusal("Memento workbook does not match the admitted task digest")
    return path, definition


def plan():
    """Draw from committed ID/condition metadata only, independently of recall or gold counts."""
    population, definition = _record(POPULATION), _record(TASK)
    groups = population["participantsByCondition"]
    expected_counts = {
        str(r["condition"]): r["remaining"]
        for r in population["reconciliation"]["byCondition"]
    }
    if set(groups) != set(expected_counts) or any(
        len(groups[c]) != n for c, n in expected_counts.items()
    ):
        raise GuardRefusal("population condition counts do not reconcile")
    dev, test = task.draw(
        groups, definition["split"]["seed"], definition["split"]["testByCondition"]
    )
    return {
        "schema": ss.SPLIT_SCHEMA,
        "corpus": MEMENTO.name,
        "seed": definition["split"]["seed"],
        "draw": definition["split"]["algorithm"],
        **_pins(),
        "pool": {"participants": task.sort_ids(dev + test)},
        "development": {"participants": dev},
        "test": {"participants": test},
        "testByCondition": definition["split"]["testByCondition"],
    }


def _validate(record):
    ss.validate_split(MEMENTO, record)
    expected = plan()
    for key in (
        "seed",
        "draw",
        "sourceManifestSha256",
        "taskDefinitionSha256",
        "populationSha256",
        "taskImplementationSha256",
        "xlsxReaderSha256",
        "testByCondition",
    ):
        if record.get(key) != expected[key]:
            raise GuardRefusal(f"Memento seal does not match committed {key}")
    for side in ("pool", "development", "test"):
        if record[side]["participants"] != expected[side]["participants"]:
            raise GuardRefusal(
                "Memento membership or condition-stratified draw changed"
            )
    return record


def load_split():
    return _validate(ss.load_split(MEMENTO))


def require_readable(participants, *, final_opening=None):
    load_split()  # shared validation alone cannot establish condition strata or task identity
    return ss.require_readable(MEMENTO, participants, final_opening=final_opening)


def read_units(data_root, participants, *, final_opening=None):
    """Text-bearing units, available only after the fixed-split participant guard succeeds."""
    ids = require_readable(participants, final_opening=final_opening)
    path, definition = _book(data_root)
    return [
        unit
        for name in ids
        for unit in task.normalize(
            sheet_rows(path, name, indexed=True),
            name,
            definition["workbookSha256"],
            definition,
        )
    ]


def survey_population(data_root):
    """Before sealing only: reproduce the frozen identifiable-event rule, returning IDs only."""
    _require_unsealed()
    path, _ = _book(data_root)
    names = set(sheet_names(path))
    index = sheet_rows(path, "subs")
    if index[0][:2] != ["id", "Condition"]:
        raise GuardRefusal("participant-index schema changed")
    groups = {str(c): [] for c in range(1, 5)}
    missing, excluded, indexed = [], [], set()
    for row in index[1:]:
        number, condition = task.integer(row[0]), task.integer(row[1])
        if number is None or condition not in range(1, 5):
            raise GuardRefusal("invalid participant index")
        name = f"S{number}"
        if name in indexed:
            raise GuardRefusal("duplicate participant in index")
        indexed.add(name)
        if name not in names:
            missing.append(name)
            continue
        rows = sheet_rows(path, name)
        column = rows[0].index("BroadSceneNum")
        events = {
            task.integer(row[column]) for row in rows[1:] if column < len(row)
        } - {None}
        if len(events) < 2:
            excluded.append(name)
        else:
            groups[str(condition)].append(name)
    reference = _record(POPULATION)
    if task.sort_ids(missing) != task.sort_ids(
        reference["workbook"]["inSubsWithoutSheet"]
    ) or task.sort_ids(
        name
        for name in names
        if name.startswith("S") and name[1:].isdigit() and name not in indexed
    ) != task.sort_ids(reference["workbook"]["sheetNotInSubs"]):
        raise GuardRefusal("participant-index reconciliation changed")
    for row in reference["reconciliation"]["byCondition"]:
        if len(groups[str(row["condition"])]) != row["remaining"]:
            raise GuardRefusal("identifiable-event population changed")
    expected_excluded = (
        set(reference["identifiableEventRule"]["sheetsBelowTwo"]) & indexed
    )
    if set(excluded) != expected_excluded:
        raise GuardRefusal("identifiable-event exclusions changed")
    return {
        "participantsByCondition": {c: task.sort_ids(ids) for c, ids in groups.items()},
        "excludedMatchedParticipants": task.sort_ids(excluded),
    }


def _summary(units):
    reasons, codes = Counter(), Counter()
    for unit in units:
        reasons.update(reason.value for reason in unit.reasons)
        codes[str(unit.code) if unit.code in range(1, 6) else "missing-or-invalid"] += 1
    return {
        "units": len(units),
        "goldUnits": sum(u.eligible for u in units),
        "eligibleCode1": sum(u.eligible and u.code == 1 for u in units),
        "eligibleCode2": sum(u.eligible and u.code == 2 for u in units),
        "code1MissingScene": sum(
            u.code == 1 and task.Reason.MISSING_SCENE in u.reasons for u in units
        ),
        "code2MissingScene": sum(
            u.code == 2 and task.Reason.MISSING_SCENE in u.reasons for u in units
        ),
        "missingOrInvalidClocks": sum(
            u.clock_status != task.Clock.OBSERVED for u in units
        ),
        "reasonCounts": dict(sorted(reasons.items())),
        "codeCounts": dict(sorted(codes.items())),
    }


def _account(path, definition, split):
    groups = _record(POPULATION)["participantsByCondition"]
    pool = {name: c for c, ids in groups.items() for name in ids}
    units = {
        name: task.normalize(
            sheet_rows(path, name, indexed=True),
            name,
            definition["workbookSha256"],
            definition,
        )
        for name in split["pool"]["participants"]
    }
    result = {
        "workbookSha256": definition["workbookSha256"],
        "totals": {},
        "bySideAndCondition": {},
    }
    for side in ("development", "test"):
        ids = split[side]["participants"]
        result["totals"][side] = {
            "participants": len(ids),
            "goldUnits": sum(u.eligible for name in ids for u in units[name]),
        }
        result["bySideAndCondition"][side] = {}
        for condition in groups:
            members = [name for name in ids if pool[name] == condition]
            result["bySideAndCondition"][side][condition] = {
                "participants": len(members),
                "evaluableParticipants": sum(
                    any(u.eligible for u in units[name]) for name in members
                ),
                **_summary([u for name in members for u in units[name]]),
            }
    return result


def seal_accounting(data_root, *, sealing_split=None, purpose=None):
    """Per-side/condition integer accounting only. After sealing, attempts are prelogged."""
    if sealing_split is not None:
        _require_unsealed()
        split = _validate(sealing_split)
        path, definition = _book(data_root)
        return _account(path, definition, split)
    split = load_split()
    with ss.count_only_read(MEMENTO, purpose) as complete:
        path, definition = _book(data_root)
        result = _account(path, definition, split)
        complete(result["totals"])
        return result


def admission_audit(data_root):
    """Before sealing only: anomaly coordinates and condition-level accounting, never recall text."""
    _require_unsealed()
    path, definition = _book(data_root)
    groups = _record(POPULATION)["participantsByCondition"]
    output = {
        "schema": "storymodel4s.memento.task-audit/v1",
        **_pins(),
        "workbookSha256": definition["workbookSha256"],
        "byCondition": {},
        "invalidCodeCells": [],
        "invalidSceneRows": [],
        "overlayEffects": [],
        "zeroEligibleParticipants": [],
    }
    for condition, ids in groups.items():
        all_units, strict_units = [], []
        evaluable, strict_evaluable = 0, 0
        for name in ids:
            rows = sheet_rows(path, name, indexed=True)
            units = task.normalize(rows, name, definition["workbookSha256"], definition)
            strict = task.normalize(
                rows, name, definition["workbookSha256"], definition, overlays=False
            )
            all_units.extend(units)
            strict_units.extend(strict)
            evaluable += any(u.eligible for u in units)
            strict_evaluable += any(u.eligible for u in strict)
            if not any(u.eligible for u in units):
                output["zeroEligibleParticipants"].append(name)
            for unit in units:
                if task.Reason.INVALID_CODE in unit.reasons:
                    output["invalidCodeCells"].append(
                        {
                            "participant": name,
                            "excelRow": unit.excel_row,
                            "reason": "invalid-recall-code",
                            "unitId": unit.unit_id,
                        }
                    )
                if task.Reason.INVALID_SCENE in unit.reasons:
                    output["invalidSceneRows"].append(
                        {
                            "participant": name,
                            "excelRow": unit.excel_row,
                            "reason": "invalid-scene-gold",
                            "unitId": unit.unit_id,
                        }
                    )
            if any(
                rule["participant"] == name for rule in definition["headerOverlays"]
            ):
                output["overlayEffects"].append(
                    {
                        "participant": name,
                        "condition": condition,
                        "eligibleWithOverlay": sum(u.eligible for u in units),
                        "eligibleWithoutOverlay": sum(u.eligible for u in strict),
                        "evaluableWithOverlay": int(any(u.eligible for u in units)),
                        "evaluableWithoutOverlay": int(any(u.eligible for u in strict)),
                    }
                )
        output["byCondition"][condition] = {
            "participants": len(ids),
            "evaluableParticipants": evaluable,
            **_summary(all_units),
            "withoutOverlays": {
                "evaluableParticipants": strict_evaluable,
                **_summary(strict_units),
            },
        }
    return output
