#!/usr/bin/env python3
"""Error anatomy for a mapper run (development gold only).

Usage: errors.py RUN_NAME [--show N]
Classifies each eligible unit: hit, adjacent (|d|=1), far, abstain; and whether the unit sits
at a gold transition (gold scene differs from the previous or next eligible unit).
"""

import argparse
import json
from collections import Counter

import common as c


def load(run):
    d = c.OUT / run
    return {p.stem: json.loads(p.read_text()) for p in d.glob("NN*.json")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--show", type=int, default=0)
    a = ap.parse_args()
    g = c.gold()
    tally, at_edge = Counter(), Counter()
    shown = 0
    for name, r in sorted(load(a.run).items()):
        us = [u for u in r["units"] if u["id"] in g]
        texts = {u.id: u.text for u in c.units(name)}
        for i, u in enumerate(us):
            gs, ps = g[u["id"]], u["scene"]
            edge = (i > 0 and g[us[i - 1]["id"]] != gs) or (
                i + 1 < len(us) and g[us[i + 1]["id"]] != gs
            )
            kind = (
                "abstain"
                if ps is None
                else "hit"
                if ps == gs
                else "adjacent"
                if abs(ps - gs) == 1
                else "far"
            )
            tally[kind] += 1
            at_edge[(kind, edge)] += 1
            if kind == "far" and shown < a.show:
                shown += 1
                print(
                    f'{name[:4]} u{u["index"]} gold={gs} pred={ps} conf={u.get("conf")} '
                    f'rows={u.get("rows")} :: {texts[u["id"]][:140]}'
                )
    n = sum(tally.values())
    print({k: f"{v} ({100 * v / n:.1f}%)" for k, v in tally.items()})
    for k in ("hit", "adjacent", "far", "abstain"):
        e, ne = at_edge[(k, True)], at_edge[(k, False)]
        if e + ne:
            print(f"{k:9s} at gold transition {e:4d}  interior {ne:4d}")


if __name__ == "__main__":
    main()
