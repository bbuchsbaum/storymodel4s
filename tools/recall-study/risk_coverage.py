#!/usr/bin/env python3
"""Risk-coverage: what the model is worth when it is allowed to decline.

Accuracy at full coverage is one number and it hides the thing this system is built to do. A model
that abstains well is more useful at 60% coverage than a model that cannot abstain is at 100%, and
no headline accuracy can express that. The abstention *rate* cannot either: higher is neither better
nor worse, so quoting it says nothing. What says something is the curve — accuracy as a function of
how much of the recall you are willing to answer for — and the area under its risk.

The confidence used for ranking is a column the arm already wrote, not a new estimate; `mapAnchorMass`
is the MAP anchor's posterior mass, the same quantity the study log quotes in quartiles. Nothing here
fits anything.

The reference is the same units in random order. That is the honest floor: a model whose confidence
carries no information has a flat risk curve at its own error rate, so any area below that line is
the part of the model's confidence that is real. Reporting AURC without it invites reading a low
number as skill when it may only be a low error rate.

Gold, participant mapping and the two exclusions come from `gold_scene.py`, unchanged: this file adds
an ordering, never a labelling rule.

Usage: risk_coverage.py GOLD_CSV LABEL DIR [LABEL DIR ...] [--confidence COLUMN] [--tolerance N]
"""
import csv
import glob
import os
import random
import statistics
import sys

from gold_scene import cluster_boot, gold_subject_of, load_gold, scene_at

DEFAULT_CONFIDENCE = "mapAnchorMass"
COVERAGE_GRID = [i / 100.0 for i in range(5, 101, 5)]


def load_scored(directory, confidence_column):
    """(gold_scene, predicted_scene, confidence) per unit that has gold, keyed by participant.

    A unit missing the confidence column, or carrying a non-numeric one, is dropped and counted
    rather than defaulted: a unit whose confidence nobody wrote must not be ranked as if it had the
    lowest, which would flatter the curve at exactly the coverage levels the claim rests on.
    """
    out, dropped = {}, 0
    for path in sorted(glob.glob(os.path.join(directory, "recall-map-*.tsv"))):
        name = os.path.basename(path).replace("recall-map-", "").replace(".tsv", "")
        rows = []
        with open(path, newline="", encoding="utf-8") as fh:
            for r in csv.DictReader(fh, delimiter="\t"):
                onset = r.get("recallOnsetSeconds") or ""
                group = (r.get("group") or "").split(".")[0].strip()
                raw = (r.get(confidence_column) or "").strip()
                if not (onset and group.isdigit()):
                    continue
                try:
                    conf = float(raw)
                except ValueError:
                    dropped += 1
                    continue
                rows.append((float(onset), int(group), conf))
        if rows:
            out[name] = rows
    return out, dropped


def with_gold(arm_rows, gold):
    out = {}
    for name, rows in arm_rows.items():
        subject = gold_subject_of(int(name[2:4]))
        if subject is None or subject not in gold:
            continue
        intervals = gold[subject]
        scored = []
        for onset, predicted, conf in rows:
            g = scene_at(intervals, onset)
            if g is not None:
                scored.append((g, predicted, conf))
        if scored:
            out[name] = scored
    return out


def curve(units, tolerance):
    """Accuracy at each coverage level, taking the most confident units first.

    Ties are broken by taking whole tie-groups, never a slice of one: cutting inside a group of
    equal confidence would let the reported accuracy depend on file order, which is not a property
    of the model.
    """
    ordered = sorted(units, key=lambda u: -u[2])
    points = []
    for coverage in COVERAGE_GRID:
        want = max(1, int(round(coverage * len(ordered))))
        while want < len(ordered) and ordered[want - 1][2] == ordered[want][2]:
            want += 1
        taken = ordered[:want]
        correct = sum(1 for g, p, _ in taken if abs(g - p) <= tolerance)
        points.append((want / len(ordered), 100.0 * correct / len(taken)))
    return points


def aurc(units, tolerance):
    """Mean risk (error rate) over the coverage grid. Lower is better."""
    return statistics.fmean(100.0 - acc for _, acc in curve(units, tolerance))


def shuffled_aurc(units, tolerance, seed=20260904, trials=200):
    """The same units ranked at random: the area a confidence carrying no information would give."""
    rng = random.Random(seed)
    vals = []
    for _ in range(trials):
        shuffled = [(g, p, rng.random()) for g, p, _ in units]
        vals.append(aurc(shuffled, tolerance))
    return statistics.fmean(vals)


def main(argv):
    args = argv[1:]
    tolerance, confidence_column = 0, DEFAULT_CONFIDENCE
    positional = []
    i = 0
    while i < len(args):
        if args[i] == "--tolerance":
            tolerance = int(args[i + 1])
            i += 2
        elif args[i] == "--confidence":
            confidence_column = args[i + 1]
            i += 2
        else:
            positional.append(args[i])
            i += 1
    if len(positional) < 3 or len(positional) % 2 == 0:
        print(__doc__)
        return 2

    gold = load_gold(positional[0])
    arms = [
        (positional[i], positional[i + 1]) for i in range(1, len(positional) - 1, 2)
    ]

    print(f"confidence: {confidence_column}   scene tolerance: {tolerance}")
    for name, directory in arms:
        rows, dropped = load_scored(directory, confidence_column)
        per_part = with_gold(rows, gold)
        if not per_part:
            print(f"   {name:22s} no participant with gold")
            continue
        pooled = [u for k in sorted(per_part) for u in per_part[k]]
        points = curve(pooled, tolerance)
        area = aurc(pooled, tolerance)
        floor = shuffled_aurc(pooled, tolerance)
        lo, hi = cluster_boot(per_part, lambda u: aurc(u, tolerance))
        at = {c: a for c, a in points}

        def nearest(target):
            key = min(at, key=lambda c: abs(c - target))
            return at[key]

        note = (
            f"  ({dropped} units dropped for an unwritten confidence)"
            if dropped
            else ""
        )
        print(
            f"   {name:22s} AURC {area:5.2f}  95% CI "
            f"[{'  n/a' if lo is None else f'{lo:5.2f}'}, "
            f"{'  n/a' if hi is None else f'{hi:5.2f}'}]"
            f"  random-order AURC {floor:5.2f}"
        )
        print(
            f"   {'':22s} accuracy at coverage: "
            f"25% {nearest(0.25):5.1f}   50% {nearest(0.50):5.1f}   "
            f"75% {nearest(0.75):5.1f}   100% {nearest(1.00):5.1f}{note}"
        )
        if lo is not None and lo <= floor <= hi:
            print(
                f"   {'':22s} random ordering falls inside this arm's CI: on these units the "
                f"confidence does not order them better than chance"
            )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
