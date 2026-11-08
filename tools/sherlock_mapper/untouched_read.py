#!/usr/bin/env python3
"""Registered single untouched-Sherlock read. Protocol: docs/plans/2026-11-08-untouched-read-protocol.md

Phases (run in order, once):
  freeze  — gold_scene.py freeze-support for the untouchedTest partition; prints counts only.
  score   — after the mapper arms have run with --allow-untouched; scores primary and secondary arms.
"""

import json
import random
import statistics
import subprocess
import sys
from pathlib import Path

import common as c

READ = c.OUT / "untouched-read-20261108"
SUPPORT = READ / "support-untouched.json"
SCORER = Path(__file__).resolve().parents[1] / "recall-study/gold_scene.py"
ARMS = {
    "v2-opus (primary)": ("u-v2-opus", "narrative"),
    "v3-opus": ("u-v3-opus", "narrative"),
    "auto asr+frames sonnet": ("u-auto-sonnet", "scene"),
}


def freeze():
    READ.mkdir(parents=True, exist_ok=False)
    cmd = [
        sys.executable,
        str(SCORER),
        "freeze-support",
        str(c.MANIFEST),
        str(c.DATA / "sherlock/Sherlock_Recall_Scene_n50_Onsets.csv"),
        str(SUPPORT),
        "--partition",
        "untouchedTest",
    ]
    p = subprocess.run(cmd, capture_output=True, text=True)
    (READ / "freeze.log").write_text(p.stdout + p.stderr + f"\nexit={p.returncode}\n")
    s = json.loads(SUPPORT.read_text())
    elig = sum(1 for r in s["rows"] if r["exclusion"] is None)
    print(
        "exit",
        p.returncode,
        "rows",
        len(s["rows"]),
        "eligible",
        elig,
        "gold sha",
        s["goldSha256"][:12],
        "support sha",
        c.sha(SUPPORT.read_text())[:12],
    )


def old_mapper():
    out = {}
    for name in sorted(c.partition()["untouchedTest"]):
        ids = {u.index: u.id for u in c.units_frozen(name)}
        import csv

        with open(c.FROZEN / f"recall-map-{name}.tsv", newline="") as f:
            out[name] = {
                ids[int(x["unit"])]: int(x["group"].split(".")[0])
                for x in csv.DictReader(f, delimiter="\t")
            }
    return out


def arm(run, key):
    return {
        p.stem: {u["id"]: u.get(key) for u in json.loads(p.read_text())["units"]}
        for p in (c.OUT / run).glob("NN*.json")
        if p.stem in c.partition()["untouchedTest"]
    }


def per_participant(pred, gold):
    res = {}
    for name, m in pred.items():
        ks = [k for k in m if k in gold]
        if ks:
            res[name] = dict(
                n=len(ks),
                exact=sum(m[k] == gold[k] for k in ks) / len(ks),
                within1=sum(m[k] is not None and abs(m[k] - gold[k]) <= 1 for k in ks)
                / len(ks),
            )
    return res


def score():
    s = json.loads(SUPPORT.read_text())
    gold = {r["id"]: r["goldScene"] for r in s["rows"] if r["exclusion"] is None}
    old = per_participant(old_mapper(), gold)
    report = {
        "supportSha256": c.sha(SUPPORT.read_text()),
        "goldSha256": s["goldSha256"],
        "oldMapper": summarize(old),
        "arms": {},
    }
    for label, (run, key) in ARMS.items():
        new = per_participant(arm(run, key), gold)
        names = sorted(set(new) & set(old))
        diffs = [new[n]["exact"] - old[n]["exact"] for n in names]
        rng = random.Random(20260902)
        boots = sorted(
            statistics.mean(rng.choice(diffs) for _ in diffs) for _ in range(2000)
        )
        report["arms"][label] = {
            **summarize(new),
            "pairedExactDiffPct": round(100 * statistics.mean(diffs), 2),
            "ci95": [round(100 * boots[49], 2), round(100 * boots[1949], 2)],
            "participants": names,
        }
    (READ / "result.json").write_text(json.dumps(report, indent=1))
    print(json.dumps(report, indent=1))


def summarize(per):
    tot = sum(p["n"] for p in per.values())
    return {
        "participantAverage": {
            k: round(100 * statistics.mean(p[k] for p in per.values()), 2)
            for k in ("exact", "within1")
        },
        "pooledExact": round(
            100 * sum(p["exact"] * p["n"] for p in per.values()) / tot, 2
        ),
        "units": tot,
        "per": {k: {kk: round(vv, 4) for kk, vv in v.items()} for k, v in per.items()},
    }


if __name__ == "__main__":
    {"freeze": freeze, "score": score}[sys.argv[1]]()
