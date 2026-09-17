#!/usr/bin/env python3
"""Score a set of recall-to-video reports on the gold-free outcomes of the study plan.

Reads the 20-column TSVs written by `sherlockRecallMap` and reports, per participant and in
aggregate, the quantities the plan predeclares as gold-free primary outcomes:

  sequentialCoherence  Kendall tau-b between MAP-anchored source time and recall order. Free recall
                       of a narrative film is strongly sequential, so this is a proxy for correct
                       localisation. It is a consistency measure, never an accuracy.
  concentration        Median MAP-anchor mass: how much of a unit's posterior sits on its top
                       segment. Rises when the aligner can tell shortlisted segments apart.
  sourceMass           Median share of a unit's mass attributed to the film rather than external
                       states. Reported because the plan requires it, and read with care: it rises
                       when the source text merely absorbs more, which is the verbosity confound.
  localizability       As emitted by the pipeline: 1 - H_source / log K.

Aggregation is the participant-macro mean with a seeded percentile bootstrap over participants,
matching the story-macro convention of `embed-bench`'s Metrics.aggregate. A participant missing any
required column makes that participant missing rather than silently dropped, so an arm cannot look
better by failing on the hard cases.

Usage: score.py LABEL REPORT_DIR [REPORT_DIR ...]
       score.py --compare LABEL_A DIR_A LABEL_B DIR_B   (paired per-participant differences)
"""

import csv
import glob
import json
import math
import os
import random
import statistics
import sys

BOOTSTRAP = 2000
SEED = 20260902
# Sherlock fallback. The 100000.0 is an ORDERING SENTINEL, not a time: this file feeds it only to
# kendall_tau_b, a rank statistic where any monotone offset works. Declaring which kind it is, is
# the whole point -- agreement.py needs the REAL 1426.0 for the same parts because it measures gaps.
PART_OFFSET = {"media-part-a": 0.0, "media-part-b": 100000.0}
OFFSET_KIND = "ordering-only"


def configure(descriptor_path):
    """Replaces PART_OFFSET from a declared descriptor of the ORDERING-ONLY kind."""
    global PART_OFFSET
    import corpus_descriptor as cd
    PART_OFFSET = cd.part_offsets(cd.load(descriptor_path), cd.ORDERING_ONLY)
    return PART_OFFSET


def kendall_tau_b(xs):
    """Tau-b of the sequence against its own index, with tie correction."""
    n = len(xs)
    if n < 2:
        return None
    conc = disc = tx = 0
    for i in range(n):
        for j in range(i + 1, n):
            a, b = xs[i], xs[j]
            if a == b:
                tx += 1
            elif a < b:
                conc += 1
            else:
                disc += 1
    n0 = n * (n - 1) / 2
    denom = math.sqrt((n0 - tx) * n0)
    return (conc - disc) / denom if denom > 0 else None


def read_report(path):
    with open(path, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh, delimiter="\t"))


def participant_of(path):
    base = os.path.basename(path)
    return base.replace("recall-map-", "").replace(".tsv", "")


def score_participant(rows):
    """All four outcomes for one participant, or None if a required column is unusable."""
    if not rows:
        return None
    times = []
    for r in rows:
        part, start = r.get("mediaPart", ""), r.get("startSeconds", "")
        if part in PART_OFFSET and start:
            times.append(PART_OFFSET[part] + float(start))
    if len(times) < 10:
        return None
    tau = kendall_tau_b(times)
    if tau is None:
        return None

    def med(col):
        vals = [float(r[col]) for r in rows if r.get(col)]
        return statistics.median(vals) if vals else None

    out = {
        "units": len(rows),
        "anchored": len(times),
        "sequentialCoherence": tau,
        "concentration": med("mapAnchorMass"),
        "sourceMass": med("sourceMass"),
        "localizability": med("localizability"),
    }
    return None if any(v is None for v in out.values()) else out


def bootstrap_ci(values, seed=SEED, resamples=BOOTSTRAP):
    if len(values) < 2:
        return (None, None)
    rng = random.Random(seed)
    means = []
    for _ in range(resamples):
        means.append(statistics.mean(rng.choices(values, k=len(values))))
    means.sort()
    lo = means[int(0.025 * resamples)]
    hi = means[int(0.975 * resamples) - 1]
    return (lo, hi)


def score_dir(dirs):
    per = {}
    for d in dirs:
        for path in sorted(glob.glob(os.path.join(d, "recall-map-*.tsv"))):
            s = score_participant(read_report(path))
            if s is not None:
                per[participant_of(path)] = s
    return per


def aggregate(
    per,
    metrics=("sequentialCoherence", "concentration", "sourceMass", "localizability"),
):
    agg = {}
    for m in metrics:
        vals = [p[m] for p in per.values()]
        if not vals:
            agg[m] = None
            continue
        lo, hi = bootstrap_ci(vals)
        agg[m] = {
            "mean": statistics.mean(vals),
            "median": statistics.median(vals),
            "ci95": [lo, hi],
            "n": len(vals),
        }
    return agg


def render(label, per, agg):
    print(
        f"== {label}: {len(per)} participants, {sum(p['units'] for p in per.values())} units"
    )
    for m, a in agg.items():
        if a is None:
            print(f"   {m}: missing")
        else:
            print(
                f"   {m}: mean {a['mean']:.4f}  median {a['median']:.4f}  "
                f"95% CI [{a['ci95'][0]:.4f}, {a['ci95'][1]:.4f}]  n={a['n']}"
            )


def compare(label_a, dir_a, label_b, dir_b):
    """Paired per-participant differences, B minus A, over participants present in both."""
    pa, pb = score_dir([dir_a]), score_dir([dir_b])
    shared = sorted(set(pa) & set(pb))
    print(
        f"== paired comparison, {label_b} minus {label_a}: {len(shared)} shared participants"
    )
    if not shared:
        return
    for m in ("sequentialCoherence", "concentration", "sourceMass", "localizability"):
        diffs = [pb[s][m] - pa[s][m] for s in shared]
        lo, hi = bootstrap_ci(diffs)
        better = sum(1 for d in diffs if d > 0)
        excl = (
            "excludes zero"
            if (lo is not None and (lo > 0 or hi < 0))
            else "includes zero"
        )
        print(
            f"   {m}: mean diff {statistics.mean(diffs):+.4f}  "
            f"95% CI [{lo:+.4f}, {hi:+.4f}] {excl}  improved {better}/{len(diffs)}"
        )


def main(argv):
    if len(argv) >= 6 and argv[1] == "--compare":
        compare(argv[2], argv[3], argv[4], argv[5])
        return
    if len(argv) < 3:
        print(__doc__)
        sys.exit(2)
    label, dirs = argv[1], argv[2:]
    per = score_dir(dirs)
    agg = aggregate(per)
    render(label, per, agg)
    out = {
        "label": label,
        "seed": SEED,
        "resamples": BOOTSTRAP,
        "aggregate": agg,
        "perParticipant": per,
    }
    print(json.dumps(out, sort_keys=True))


if __name__ == "__main__":
    main(sys.argv)
