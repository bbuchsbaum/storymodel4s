#!/usr/bin/env python3
"""Film-selection and scene accuracy for Film Festival, against the released gold.

`2026-09-04-filmfestival-first-run.md` found that the corpus's aggregate agreement is dominated by
a near-binary outcome — right film or wrong film — and that the conditional within-film figure is
not a valid accuracy because conditioning on agreement selects easy pairs. This closes that gap with
the gold that already exists: `recall_scenematched` labels every recall utterance with the movie it
refers to, and with the specific scene when the coder could name one.

Each report unit spans a stretch of recall audio. The gold labels utterances on the same clock, so a
unit is scored against the gold utterances its recall span overlaps; a unit overlapping several is
scored against the label holding the most overlap. Units overlapping no labelled utterance, and
units whose label is `memory search`, `gave up` or `off task`, are counted separately and never
scored — an off-task remark has no correct anchor and would otherwise be charged to the model.

The film a unit was placed in comes from its anchor time on the annotation timeline, not from the
report's group string, so this measures the placement rather than a label rendering.

Two baselines are reported, because "how often is the film right" means nothing alone:
* `duration` — a model that guesses films in proportion to their screen time;
* `prior` — a model that always answers with the most-recalled film in the gold.

Usage: filmfest_gold_film.py ANNOTATION_TSV GOLD_TSV LABEL ARM_DIR [LABEL ARM_DIR ...]
"""
import collections
import csv
import glob
import os
import random
import statistics
import sys

PART_OFFSET = {"run-01": 0.0, "run-02": 1490.0}
BOOTSTRAP = 4000
SEED = 20260904


# Annotation film ordinal -> gold movie code. The annotation numbers all twelve blocks including
# both cartoon intros; the gold numbers the ten films 1..10 and calls either cartoon 11.
def gold_code(ordinal):
    if ordinal in (1, 7):
        return 11
    return ordinal - 1 if ordinal < 7 else ordinal - 2


def film_ranges(annotation_tsv):
    """(gold movie code, film name) -> [start, end] on the concatenated timeline."""
    spans, order = {}, {}
    with open(annotation_tsv, newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            film = r["film"]
            t0 = PART_OFFSET[r["part_id"]] + float(r["start_s"])
            t1 = PART_OFFSET[r["part_id"]] + float(r["end_s"] or r["start_s"])
            lo, hi = spans.get(film, (t0, t1))
            spans[film] = (min(lo, t0), max(hi, t1))
            order.setdefault(film, int(film.split(".")[0]))
    return [
        (gold_code(order[f]), f, lo, hi)
        for f, (lo, hi) in sorted(spans.items(), key=lambda kv: kv[1][0])
    ]


def load_gold(path):
    by = collections.defaultdict(list)
    with open(path, newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            if r["run"] != "run-01":
                continue
            by[r["participant"]].append(
                {
                    "movie": int(r["movie"]),
                    "scenes": [int(s) for s in r["scenes"].split()]
                    if r["scenes"]
                    else [],
                    "start": float(r["start_s"]),
                    "end": float(r["end_s"]) if r["end_s"] else float(r["start_s"]),
                }
            )
    return by


def best_gold(span, gold_rows):
    """The gold utterance sharing the most recall time with this unit."""
    a0, a1 = span
    best, best_ov = None, 0.0
    for g in gold_rows:
        ov = min(a1, g["end"]) - max(a0, g["start"])
        if ov > best_ov:
            best, best_ov = g, ov
    return best


def score(arm_dir, gold_by_sub, ranges):
    def film_at(t):
        for code, name, lo, hi in ranges:
            if lo <= t <= hi:
                return code, name
        return None, None

    per_unit, skipped, unlabelled, no_gold_sub = [], 0, 0, 0
    for path in sorted(glob.glob(os.path.join(arm_dir, "recall-map-*.tsv"))):
        sub = os.path.basename(path).replace("recall-map-", "").replace(".tsv", "")
        rows = gold_by_sub.get(sub)
        if not rows:
            no_gold_sub += 1
            continue
        with open(path, newline="", encoding="utf-8") as fh:
            for r in csv.DictReader(fh, delimiter="\t"):
                if not r.get("startSeconds") or r.get("mediaPart") not in PART_OFFSET:
                    continue
                a0 = float(r["recallOnsetSeconds"])
                a1 = float(r["recallLastWordOnsetSeconds"] or a0)
                g = best_gold((a0, a1), rows)
                if g is None:
                    unlabelled += 1
                    continue
                if g["movie"] <= 0:
                    skipped += 1
                    continue
                anchor = PART_OFFSET[r["mediaPart"]] + float(r["startSeconds"])
                code, name = film_at(anchor)
                per_unit.append(
                    {
                        "sub": sub,
                        "goldMovie": g["movie"],
                        "modelMovie": code,
                        "modelFilm": name,
                        "goldScenes": g["scenes"],
                    }
                )
    return per_unit, {
        "unitsScored": len(per_unit),
        "unitsOffTaskOrSearch": skipped,
        "unitsWithNoOverlappingGold": unlabelled,
        "participantsWithoutGold": no_gold_sub,
    }


def ci(flags, rng):
    out = []
    n = len(flags)
    for _ in range(BOOTSTRAP):
        idx = [rng.randrange(n) for _ in range(n)]
        out.append(100.0 * sum(flags[i] for i in idx) / n)
    out.sort()
    return out[int(0.025 * BOOTSTRAP)], out[int(0.975 * BOOTSTRAP) - 1]


def main(argv):
    if len(argv) < 5 or len(argv) % 2 == 0:
        sys.exit(__doc__)
    ranges = film_ranges(argv[1])
    gold_by_sub = load_gold(argv[2])
    rng = random.Random(SEED)

    durations = {c: hi - lo for c, _, lo, hi in ranges}
    total_dur = sum(durations.values())

    for k in range(3, len(argv), 2):
        label, arm = argv[k], argv[k + 1]
        units, counts = score(arm, gold_by_sub, ranges)
        if not units:
            print(f"{label}: nothing scored")
            continue
        flags = [1 if u["modelMovie"] == u["goldMovie"] else 0 for u in units]
        acc = 100.0 * sum(flags) / len(flags)
        lo, hi = ci(flags, rng)

        gold_counts = collections.Counter(u["goldMovie"] for u in units)
        prior_best = max(gold_counts.values()) / len(units) * 100.0
        dur_base = (
            100.0
            * sum(
                gold_counts[c] * durations.get(c, 0.0) / total_dur for c in gold_counts
            )
            / len(units)
        )

        by_sub = collections.defaultdict(list)
        for u, f in zip(units, flags):
            by_sub[u["sub"]].append(f)
        per_sub = [100.0 * sum(v) / len(v) for v in by_sub.values()]

        print(f"== {label}")
        print(
            f"   units scored: {counts['unitsScored']}   "
            f"off-task/search skipped: {counts['unitsOffTaskOrSearch']}   "
            f"no overlapping gold: {counts['unitsWithNoOverlappingGold']}   "
            f"participants without gold: {counts['participantsWithoutGold']}"
        )
        print(
            f"   film correct: {acc:5.1f}%  95% CI [{lo:.1f}, {hi:.1f}]   "
            f"per-participant median {statistics.median(per_sub):.1f}%"
        )
        print(
            f"   baselines: duration-weighted guess {dur_base:.1f}%   "
            f"always-most-recalled-film {prior_best:.1f}%"
        )

        conf = collections.Counter(
            (u["goldMovie"], u["modelMovie"]) for u, f in zip(units, flags) if not f
        )
        names = {c: n for c, n, _, _ in ranges}
        print("   most frequent confusions (gold -> model):")
        for (g, m), n in conf.most_common(5):
            print(f"      {names.get(g, g)!r:34s} -> {names.get(m, m)!r:34s} {n}")

        with_scene = [u for u in units if u["goldScenes"]]
        if with_scene:
            exact = sum(1 for u in with_scene if u["modelMovie"] == u["goldMovie"])
            print(
                f"   units whose gold names a specific scene: {len(with_scene)}"
                f"   film correct on those: {100.0 * exact / len(with_scene):.1f}%"
            )
        print()


if __name__ == "__main__":
    main(sys.argv)
