#!/usr/bin/env python3
"""Scene-error decomposition of frozen stage traces. Protocol: protocol.md (same directory).

Usage: decompose.py DATA_ROOT OUT_JSON [--precheck]
--precheck validates the row-to-scene map against trace assignedScene without reading support.
"""
import csv
import hashlib
import json
import re
import statistics
import sys
from collections import Counter
from pathlib import Path

RUN = "study/recall-to-video/development-20260923"
SEGMENTS = "sherlock/Sherlock_Segments_1000_NN_2017.tsv"
SEGMENTS_SHA = "8c205826dcea8c58db24d7a17c71a3f99a9054e9379435e60b1dd0b987c2b296"
GOLD_SHA = "68cc307c19cfd429a3bd0eda84ee18d0152c87444d33a79246a7cd64d3763753"
CLASSES = (
    "no-trace",
    "retrieval-miss",
    "ranking-miss",
    "decode-rescue",
    "decode-loss",
    "hit",
)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def row_scenes(path):
    if sha(path) != SEGMENTS_SHA:
        sys.exit("segments file changed")
    scene, out = None, {}
    with open(path, encoding="utf-8-sig", newline="") as f:
        reader = csv.reader(f, delimiter="\t")
        header = next(reader)
        col = [h.strip() for h in header].index("Scene Segments")
        for r in reader:
            m = re.match(r"\s*(\d+)\.", r[col]) if len(r) > col else None
            if m:
                scene = int(m.group(1))
            out[int(r[0])] = scene
    return out


def scene_of(ref, rows):
    if ref is None:
        return None
    m = re.fullmatch(r"seg:sherlock:scene:(\d+)", ref)
    if m:
        return int(m.group(1))
    m = re.fullmatch(r"sit:sherlock:row:(\d+)", ref)
    if m:
        return rows[int(m.group(1))]
    raise ValueError("unknown ref " + ref)


def traces(data):
    out = {}
    for p in sorted((data / RUN / "A").glob("recall-map-*.tsv.stages.json")):
        name = p.name[len("recall-map-") : -len(".tsv.stages.json")]
        out[name] = json.loads(p.read_text())["units"]
    if len(out) != 11:
        sys.exit("expected 11 development traces")
    return out


def precheck(all_traces, rows):
    checked = 0
    for units in all_traces.values():
        for u in units:
            a = u.get("finalAnchor") or ""
            if a.startswith(("sit:", "seg:")):
                if scene_of(a, rows) != u["assignedScene"]:
                    sys.exit(
                        f'row map disagrees: {a} -> {scene_of(a, rows)} vs {u["assignedScene"]}'
                    )
                checked += 1
    return checked


def classify(u, gold, rows):
    src = [s for s in u["states"] if s.get("anchor")]
    ext = [s for s in u["states"] if not s.get("anchor")]
    if u.get("abstained") or not src:
        return "no-trace", None
    top = max(src, key=lambda s: s["localMass"])
    local = scene_of(top["anchor"], rows)
    final = u["assignedScene"]
    noms = [(scene_of(n["ref"], rows), n["level"]) for n in u["nominations"]]
    info = {
        "local": local,
        "final": final,
        "localWithin1": local is not None and abs(local - gold) <= 1,
        "finalWithin1": final is not None and abs(final - gold) <= 1,
        "nonSourceDominant": bool(ext)
        and max(s["localMass"] for s in ext) > top["localMass"],
        "goldLevels": sorted({lv for sc, lv in noms if sc == gold}),
    }
    if gold not in {sc for sc, _ in noms}:
        cls = "retrieval-miss"
    elif local != gold and final != gold:
        cls = "ranking-miss"
    elif local != gold:
        cls = "decode-rescue"
    elif final != gold:
        cls = "decode-loss"
    else:
        cls = "hit"
    return cls, info


def main():
    data, out = Path(sys.argv[1]).resolve(), Path(sys.argv[2])
    rows = row_scenes(data / SEGMENTS)
    all_traces = traces(data)
    checked = precheck(all_traces, rows)
    print("precheck: row map reproduces assignedScene for", checked, "units")
    if "--precheck" in sys.argv:
        return
    support = json.loads((data / RUN / "support.json").read_text())
    if support["goldSha256"] != GOLD_SHA or support["partition"] != "development":
        sys.exit("support changed")
    gold = {
        r["id"]: r["goldScene"]
        for r in support["rows"]
        if r["exclusion"] is None and r["goldScene"] is not None
    }
    per, pooled, extra = {}, Counter(), Counter()
    levels = Counter()
    for name, units in all_traces.items():
        c, e, n = Counter(), Counter(), 0
        for u in units:
            g = gold.get(u["unitId"])
            if g is None:
                continue
            n += 1
            cls, info = classify(u, g, rows)
            c[cls] += 1
            if info:
                e["localExact"] += info["local"] == g
                e["finalExact"] += info["final"] == g
                e["localWithin1"] += info["localWithin1"]
                e["finalWithin1"] += info["finalWithin1"]
                e["nonSourceDominant"] += info["nonSourceDominant"]
                lv = info["goldLevels"]
                levels[
                    "none"
                    if not lv
                    else (
                        "row-only"
                        if lv == [0]
                        else "scene-only"
                        if lv == [1]
                        else "both"
                    )
                ] += 1
        per[name] = {"eligible": n, "classes": dict(c), "extra": dict(e)}
        pooled.update(c)
        extra.update(e)
        extra["eligible"] += n

    def avg(key, sub):
        vals = [
            p[sub].get(key, 0) / p["eligible"] for p in per.values() if p["eligible"]
        ]
        return round(100 * statistics.mean(vals), 2)

    total = extra["eligible"]
    result = {
        "schema": "storymodel4s.mapping-decomposition/v1",
        "protocolSha256": sha(Path(__file__).with_name("protocol.md")),
        "scriptSha256": sha(__file__),
        "goldSha256": GOLD_SHA,
        "precheckUnits": checked,
        "eligibleUnits": total,
        "participantsWithGold": sum(1 for p in per.values() if p["eligible"]),
        "participantAveragePercent": {
            **{k: avg(k, "classes") for k in CLASSES},
            **{
                k: avg(k, "extra")
                for k in (
                    "localExact",
                    "finalExact",
                    "localWithin1",
                    "finalWithin1",
                    "nonSourceDominant",
                )
            },
        },
        "pooledPercent": {
            **{k: round(100 * pooled[k] / total, 2) for k in CLASSES},
            **{
                k: round(100 * extra[k] / total, 2)
                for k in (
                    "localExact",
                    "finalExact",
                    "localWithin1",
                    "finalWithin1",
                    "nonSourceDominant",
                )
            },
        },
        "goldNominationLevel": dict(levels),
        "perParticipant": per,
    }
    Path(out).write_text(json.dumps(result, indent=2) + "\n")
    print(
        json.dumps(
            {
                k: result[k]
                for k in (
                    "eligibleUnits",
                    "participantAveragePercent",
                    "pooledPercent",
                    "goldNominationLevel",
                )
            },
            indent=1,
        )
    )


if __name__ == "__main__":
    main()
