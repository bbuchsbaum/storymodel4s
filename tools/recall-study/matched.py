#!/usr/bin/env python3
"""Re-score two arms on the units that kept the same anchor granularity in both.

A scene node and its own leaves compete for the same posterior mass, so any change that makes scene
nodes more attractive shifts units onto coarser anchors. A coarser anchor carries a smoother time
and raises Kendall tau for free, which is an artefact rather than better localisation. This restricts
the comparison to units anchored at the leaf level in both arms, where no such shift can flatter
either side, and reports how the anchor mix moved.

Usage: matched.py LABEL_A DIR_A LABEL_B DIR_B
"""
import csv, glob, math, os, random, statistics, sys

PART = {"media-part-a": 0.0, "media-part-b": 100000.0}


def tau_b(xs):
    n = len(xs)
    if n < 2:
        return None
    c = d = t = 0
    for i in range(n):
        for j in range(i + 1, n):
            a, b = xs[i], xs[j]
            if a == b:
                t += 1
            elif a < b:
                c += 1
            else:
                d += 1
    n0 = n * (n - 1) / 2
    den = math.sqrt((n0 - t) * n0)
    return (c - d) / den if den > 0 else None


def boot(v, seed=20260902, n=2000):
    if len(v) < 2:
        return (None, None)
    r = random.Random(seed)
    m = sorted(statistics.mean(r.choices(v, k=len(v))) for _ in range(n))
    return m[int(0.025 * n)], m[int(0.975 * n) - 1]


def load(d):
    out = {}
    for p in sorted(glob.glob(os.path.join(d, "recall-map-*.tsv"))):
        name = os.path.basename(p).replace("recall-map-", "").replace(".tsv", "")
        out[name] = list(csv.DictReader(open(p, newline="", encoding="utf-8"), delimiter="\t"))
    return out


def is_leaf(r):
    return (r.get("mapAnchor") or "").startswith("sit:")


def main(argv):
    la, da, lb, db = argv[1], argv[2], argv[3], argv[4]
    A, B = load(da), load(db)
    shared = sorted(set(A) & set(B))
    dt, dc, dl, kept, tot, sa, sb = [], [], [], 0, 0, 0, 0
    for k in shared:
        ra, rb = A[k], B[k]
        if len(ra) != len(rb):
            print(f"   {k}: length mismatch, skipped")
            continue
        idx = [i for i in range(len(ra)) if is_leaf(ra[i]) and is_leaf(rb[i])]
        tot += len(ra)
        kept += len(idx)
        sa += sum(1 for r in ra if not is_leaf(r))
        sb += sum(1 for r in rb if not is_leaf(r))

        def times(rows):
            v = []
            for i in idx:
                r = rows[i]
                if r.get("mediaPart") in PART and r.get("startSeconds"):
                    v.append(PART[r["mediaPart"]] + float(r["startSeconds"]))
            return v

        ta, tb = tau_b(times(ra)), tau_b(times(rb))
        if ta is None or tb is None:
            continue
        dt.append(tb - ta)

        def med(rows, col):
            return statistics.median([float(rows[i][col]) for i in idx if rows[i].get(col)])

        dc.append(med(rb, "mapAnchorMass") - med(ra, "mapAnchorMass"))
        dl.append(med(rb, "localizability") - med(ra, "localizability"))
    print(f"== {lb} minus {la}, units leaf-anchored in both: {kept}/{tot} ({100*kept/tot:.1f}%)")
    print(f"   scene-anchored units: {la} {sa}, {lb} {sb}")
    for nm, v in (("sequentialCoherence", dt), ("concentration", dc), ("localizability", dl)):
        lo, hi = boot(v)
        excl = "excludes zero" if (lo > 0 or hi < 0) else "includes zero"
        print(f"   {nm}: mean diff {statistics.mean(v):+.4f}  95% CI [{lo:+.4f}, {hi:+.4f}] "
              f"{excl}  improved {sum(1 for x in v if x > 0)}/{len(v)}")


main(sys.argv)
