#!/usr/bin/env python3
"""Historical risk-coverage instrument, retained only to audit archived calculations.

The positional gold-reading CLI is retired. It ranked a potentially different anchor's mass,
removed missing decisions/scores before computing coverage, and labelled unattained coverage
points. Use the frozen-unit/support contract in SCORING.md; the replacement emitted-decision
score importer is not yet qualified. No new evaluation should use these historical helpers.

Usage: risk_coverage.py (refuses before opening gold or prediction files)
"""
import csv
import glob
import os
import random
import statistics
import sys

from gold_scene import gold_subject_of, scene_at

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
    print("LEGACY_RISK_COVERAGE_RETIRED: requires frozen unit/support identities and "
          "qualified emitted-decision scores; see SCORING.md. No gold has been opened.",
          file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
