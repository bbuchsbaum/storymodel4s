#!/usr/bin/env python3
"""Admit and seal Memento without exposing recall: --population, --audit, --write, --check.

Commit each generated metadata stage before the next. --check never opens the workbook.
Membership uses only committed IDs/conditions and the owner-selected allocation, never gold.
"""

import argparse
import datetime
import json
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import memento_guard as guard  # noqa: E402
from friends_split import paired_mde_effect_size  # noqa: E402

AUDIT = "docs/data/memento/task-audit.json"


def power_block(counts):
    effects = {n: paired_mde_effect_size(n)[0] for n in set(counts.values())}
    return {
        "estimand": "Within each condition, equal-participant paired difference in any-annotated-scene accuracy",
        "method": "Two-sided paired-t planning using noncentral t, alpha=.05 and power=.80; standardized by participant-level paired-difference SD",
        "alpha": 0.05,
        "power": 0.8,
        "byCondition": {
            c: {
                "testParticipants": n,
                "standardizedMde": round(effects[n], 6),
                "hypotheticalSdPp": [
                    {"sdPp": sd, "mdePp": round(sd * effects[n], 3)}
                    for sd in (5, 10, 15)
                ],
            }
            for c, n in counts.items()
        },
        "limits": [
            "Planning assumptions, not measured Memento effect sizes or calibrated power.",
            "Rows are correlated; row counts are not independent sample size.",
            "Four marginal calculations, not familywise or between-condition interaction power.",
            "Does not measure generalization across films; shared stimulus dependence remains.",
        ],
    }


def _write_new(rel, value):
    path = guard.ss.REPO / rel
    with path.open("x", encoding="utf-8") as out:
        out.write(json.dumps(value, indent=2, sort_keys=True) + "\n")


def _data_root(value):
    return (
        Path(value)
        if value
        else Path(
            subprocess.check_output(
                ["bash", str(HERE.parent / "data-root.sh")],
                cwd=guard.ss.REPO,
                text=True,
            ).strip()
        )
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    modes = parser.add_mutually_exclusive_group(required=True)
    for name in ("population", "audit", "write", "check"):
        modes.add_argument("--" + name, action="store_true")
    parser.add_argument("--data-root")
    args = parser.parse_args(argv)
    if args.check:
        record = guard.load_split()
        audit = guard._record(AUDIT)
        if record["power"] != power_block(record["testByCondition"]):
            raise guard.GuardRefusal("planning-power calculations changed")
        if record["auditSha256"] != guard.task.digest(audit):
            raise guard.GuardRefusal("admission audit changed")
        print(
            "Memento seal: membership, task identity and planning metadata current; no recall read"
        )
        return 0
    guard._require_unsealed()  # reject all pre-seal forms before resolving or reading data
    if args.population:
        reference = guard._record(guard.POPULATION)
        if "participantsByCondition" in reference:
            raise guard.GuardRefusal(
                "population already materialized; refuse to overwrite"
            )
        reference.update(guard.survey_population(_data_root(args.data_root)))
        reference["membershipMaterializedAt"] = "2026-09-21"
        reference[
            "membershipMethod"
        ] = "Pinned workbook subs index intersected with sheets; >=2 distinct integer BroadSceneNum values, independent of accuracy eligibility"
        (guard.ss.REPO / guard.POPULATION).write_text(
            json.dumps(reference, indent=2) + "\n"
        )
        print("Materialized content-free population IDs; commit before --audit")
        return 0
    if args.audit:
        if (guard.ss.REPO / AUDIT).exists():
            raise guard.GuardRefusal(
                "admission audit already exists; refuse to overwrite"
            )
        record = guard.admission_audit(_data_root(args.data_root))
        _write_new(AUDIT, record)
        print("Wrote content-free task audit; commit before --write")
        return 0
    if (guard.ss.REPO / guard.MEMENTO.ledger).exists():
        raise guard.GuardRefusal("refusing to overwrite an existing read ledger")
    audit = guard._record(AUDIT)
    pins = guard._pins()
    if any(audit.get(key) != value for key, value in pins.items()):
        raise guard.GuardRefusal(
            "admission audit does not match current inputs or implementation"
        )
    record = guard.plan()
    accounting = guard.seal_accounting(_data_root(args.data_root), sealing_split=record)
    for condition in record["testByCondition"]:
        sides = [
            accounting["bySideAndCondition"][side][condition]
            for side in ("development", "test")
        ]
        for key in (
            "participants",
            "evaluableParticipants",
            "units",
            "goldUnits",
            "eligibleCode1",
            "eligibleCode2",
        ):
            if sum(side[key] for side in sides) != audit["byCondition"][condition][key]:
                raise guard.GuardRefusal(
                    "seal accounting disagrees with the admission audit"
                )
    record.update(
        sealedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(
            timespec="seconds"
        ),
        auditSha256=guard.task.digest(audit),
        accounting=accounting,
        power=power_block(record["testByCondition"]),
        exposure={
            "status": "model-untouched, not unseen",
            "beforeSeal": "Source structure, code distributions, population, anomalies, header-overlay effects and condition-level gold availability were inspected; no model outputs were used to define the task or draw membership.",
            "zeroEligibleParticipants": audit["zeroEligibleParticipants"],
            "modelOutputSurvey": "No Memento model output was found in the local named-path survey recorded with this admission; this is not a proof against arbitrarily renamed outputs.",
        },
        guard={
            "module": "tools/recall-study/memento_guard.py",
            "shared": "tools/recall-study/sealed_split.py",
            "ledger": guard.MEMENTO.ledger,
            "finalOpening": "Explicit committed release manifest and digest; every authorization is recorded, not a one-use capability.",
        },
    )
    _write_new(guard.MEMENTO.split, record)
    _write_new(guard.MEMENTO.ledger, guard.ss.empty_ledger(guard.MEMENTO))
    print(
        f"Sealed Memento: {len(record['test']['participants'])} test / {len(record['development']['participants'])} development; commit the split and empty ledger"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
