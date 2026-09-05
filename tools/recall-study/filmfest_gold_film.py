#!/usr/bin/env python3
"""Film identity evaluation v2, with fixed gold eligibility and participant bootstrap.

Primary: correct placements / all eligible recall units (unanchored units remain in the denominator).
Also report anchor coverage and accuracy conditional on an anchor. Gold overlap uses interpolated
first/last word onsets, not utterance durations; zero-width units use point containment. A unit is
labelled by the single gold utterance with greatest overlap, not an aggregate vote across labels.
Films restrict GOLD eligibility only. Predictions outside that set remain errors. No scene accuracy
is claimed. The duration comparator sums both cartoon blocks; the most-common-gold comparator is an
in-sample descriptive oracle, not a deployable fitted baseline. Intervals are half-open, with a final
annotation instant admitted at the end of each run. Reports and source text remain outside Git.
"""
import argparse
import collections
import csv
import hashlib
import json
import math
from pathlib import Path
import random
import statistics

PART_OFFSET = {"run-01": 0.0, "run-02": 1490.0}
BOOTSTRAP = 4000
SEED = 20260904
SCHEMA = "storymodel4s.filmfestival.film-score/v2"


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def finite(raw):
    x = float(raw)
    if not math.isfinite(x):
        raise ValueError(f"non-finite coordinate: {raw!r}")
    return x


def gold_code(ordinal):
    if ordinal in (1, 7):
        return 11
    return ordinal - 1 if ordinal < 7 else ordinal - 2


def film_ranges(annotation_tsv):
    spans, order = {}, {}
    with open(annotation_tsv, newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            film = r["film"]
            t0 = PART_OFFSET[r["part_id"]] + finite(r["start_s"])
            t1 = PART_OFFSET[r["part_id"]] + finite(r["end_s"] or r["start_s"])
            if t1 < t0:
                raise ValueError("reversed annotation interval")
            lo, hi = spans.get(film, (t0, t1))
            spans[film] = min(lo, t0), max(hi, t1)
            order.setdefault(film, int(film.split(".")[0]))
    if not spans:
        raise ValueError("empty annotation")
    return [(gold_code(order[f]), f, lo, hi)
            for f, (lo, hi) in sorted(spans.items(), key=lambda kv: kv[1][0])]


def durations_by_code(ranges):
    durations = collections.Counter()
    for code, _, lo, hi in ranges:
        durations[code] += hi - lo
    if sum(durations.values()) <= 0:
        raise ValueError("no positive source duration")
    return dict(durations)


def film_at(t, ranges):
    for code, name, lo, hi in ranges:
        if lo <= t < hi or lo == hi == t:
            return code, name
    # The last row of each run is a declared instant, not a fabricated trailing duration.
    for code, name, _, hi in ranges:
        if t == hi and not any(lo == t for _, _, lo, _ in ranges):
            return code, name
    return None, None


def load_gold(path):
    by = collections.defaultdict(list)
    with open(path, newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            if r["run"] != "run-01":
                continue  # the declared comparison uses the first recall run only
            start, end = finite(r["start_s"]), finite(r["end_s"] or r["start_s"])
            if end < start:
                raise ValueError("reversed gold interval")
            by[r["participant"]].append({"movie": int(r["movie"]), "start": start, "end": end})
    if not by:
        raise ValueError("empty first-run gold")
    return by


def best_gold(span, gold_rows):
    a0, a1 = span
    if a1 < a0:
        raise ValueError("reversed recall interval")
    if a0 == a1:
        return next((g for g in gold_rows if g["start"] <= a0 < g["end"]), None)
    best, best_ov = None, 0.0
    for g in gold_rows:
        ov = min(a1, g["end"]) - max(a0, g["start"])
        if ov > best_ov:
            best, best_ov = g, ov
    return best


def recall_population(arm_dir):
    """Bind every recall unit before any gold, film or anchor exclusion."""
    population = {}
    for path in sorted(Path(arm_dir).glob("recall-map-*.tsv")):
        sub = path.stem.removeprefix("recall-map-")
        with path.open(newline="", encoding="utf-8") as fh:
            for r in csv.DictReader(fh, delimiter="\t"):
                key = (sub, r["unit"])
                if key in population:
                    raise ValueError(f"duplicate recall unit {key}")
                start = finite(r["recallOnsetSeconds"])
                end = finite(r["recallLastWordOnsetSeconds"] or start)
                population[key] = hashlib.sha256(json.dumps([r["recallText"], start, end]).encode()).hexdigest()
    if not population:
        raise ValueError("empty recall population")
    return hashlib.sha256(json.dumps(sorted(population.items())).encode()).hexdigest()


def score(arm_dir, gold_by_sub, ranges, films=None):
    units, counts = [], collections.Counter()
    paths = sorted(Path(arm_dir).glob("recall-map-*.tsv"))
    if not paths:
        raise ValueError(f"no reports in {arm_dir}")
    seen = set()
    for path in paths:
        sub = path.stem.removeprefix("recall-map-")
        rows = gold_by_sub.get(sub)
        if not rows:
            counts["participantsWithoutGold"] += 1
        with path.open(newline="", encoding="utf-8") as fh:
            for r in csv.DictReader(fh, delimiter="\t"):
                counts["unitsRead"] += 1
                if rows is None:
                    counts["unitsWithoutParticipantGold"] += 1
                    continue
                a0 = finite(r["recallOnsetSeconds"])
                a1 = finite(r["recallLastWordOnsetSeconds"] or a0)
                key = (sub, r["unit"])
                if key in seen:
                    raise ValueError(f"duplicate unit identity {key}")
                seen.add(key)
                g = best_gold((a0, a1), rows)
                if g is None:
                    counts["unitsWithNoOverlappingGold"] += 1
                    continue
                if g["movie"] <= 0:
                    counts["unitsOffTaskOrSearch"] += 1
                    continue
                if films is not None and g["movie"] not in films:
                    counts["unitsOutsideDeclaredFilms"] += 1
                    continue
                code, name = None, None
                raw = r.get("startSeconds", "")
                if raw:
                    part = r.get("mediaPart")
                    if part not in PART_OFFSET:
                        raise ValueError(f"unknown media part {part!r}")
                    code, name = film_at(PART_OFFSET[part] + finite(raw), ranges)
                    if code is None:
                        raise ValueError("anchor outside reference annotation film ranges")
                signature = hashlib.sha256(json.dumps([r["recallText"], a0, a1]).encode()).hexdigest()
                units.append({"key": key, "signature": signature, "sub": sub,
                              "goldMovie": g["movie"], "modelMovie": code, "modelFilm": name})
                counts["unitsEligible"] += 1
                counts["unitsAnchored" if code is not None else "unitsUnanchored"] += 1
    if not units:
        raise ValueError("no eligible units")
    return units, dict(sorted(counts.items()))


def percent(a, b):
    return 100.0 * a / b if b else None


def cluster_ci(rows, numerator, denominator, seed=SEED, draws=BOOTSTRAP):
    """Resample participants with replacement, retaining each participant's entire unit cluster."""
    if not rows:
        raise ValueError("bootstrap has no participants")
    rng = random.Random(seed)
    values = []
    for _ in range(draws):
        sample = rng.choices(rows, k=len(rows))
        value = percent(sum(r[numerator] for r in sample), sum(r[denominator] for r in sample))
        if value is not None:
            values.append(value)
    if not values:
        return None
    values.sort()
    return [values[int(.025 * len(values))], values[min(len(values)-1, int(.975 * len(values)))]]


def summarize(units, counts, ranges, films=None):
    grouped = collections.defaultdict(list)
    for u in units:
        grouped[u["sub"]].append(u)
    participants = []
    for sub, us in sorted(grouped.items()):
        participants.append({"participant": sub, "eligible": len(us),
                             "anchored": sum(u["modelMovie"] is not None for u in us),
                             "correct": sum(u["modelMovie"] == u["goldMovie"] for u in us)})
    totals = {k: sum(p[k] for p in participants) for k in ("eligible", "anchored", "correct")}
    durations = durations_by_code(ranges)
    if films is not None:
        durations = {c: d for c, d in durations.items() if c in films}
    duration = sum(durations.values())
    if duration <= 0:
        raise ValueError("declared films have no duration")
    gold_counts = collections.Counter(u["goldMovie"] for u in units)
    confusion = collections.Counter((u["goldMovie"], u["modelMovie"]) for u in units)
    return {
        "counts": counts, "participants": participants,
        "correctPerEligiblePct": percent(totals["correct"], totals["eligible"]),
        "correctPerEligibleParticipantBootstrap95": cluster_ci(participants, "correct", "eligible"),
        "anchorCoveragePct": percent(totals["anchored"], totals["eligible"]),
        "correctPerAnchorPct": percent(totals["correct"], totals["anchored"]),
        "participantMeanCorrectPerEligiblePct": statistics.mean(percent(p["correct"],p["eligible"]) for p in participants),
        "participantMedianCorrectPerEligiblePct": statistics.median(percent(p["correct"],p["eligible"]) for p in participants),
        "durationWeightedGuessPct": 100 * sum(n * durations.get(c, 0) / duration for c,n in gold_counts.items()) / len(units),
        "inSampleMostCommonGoldPct": percent(max(gold_counts.values()), len(units)),
        "confusions": [{"gold":g,"predicted":m,"units":n} for (g,m),n in sorted(confusion.items(),key=lambda kv:(kv[0][0],kv[0][1] or -1))],
    }


def compare(base, other):
    a, b = {u["key"]:u for u in base}, {u["key"]:u for u in other}
    if a.keys() != b.keys() or any((a[k]["signature"],a[k]["goldMovie"]) != (b[k]["signature"],b[k]["goldMovie"]) for k in a):
        raise ValueError("paired comparison requires identical recall units and gold eligibility")
    transitions = collections.Counter()
    by = collections.defaultdict(lambda: {"delta":0,"eligible":0})
    for k in a:
        ac, bc = a[k]["modelMovie"] == a[k]["goldMovie"], b[k]["modelMovie"] == b[k]["goldMovie"]
        transitions[f"{'correct' if ac else 'wrongOrUnanchored'}To{'Correct' if bc else 'WrongOrUnanchored'}"] += 1
        by[k[0]]["delta"] += int(bc)-int(ac)
        by[k[0]]["eligible"] += 1
    rows = [v for _,v in sorted(by.items())]
    return {"deltaCorrectPerEligiblePp":percent(sum(r["delta"] for r in rows),len(a)),
            "pairedParticipantBootstrap95Pp":cluster_ci(rows,"delta","eligible"),
            "transitions":dict(sorted(transitions.items())),
            "participants":[dict(participant=s,**r,deltaPp=percent(r["delta"],r["eligible"])) for s,r in sorted(by.items())]}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("annotation")
    p.add_argument("gold")
    p.add_argument("arms", nargs="+")
    p.add_argument("--films", help="comma-separated gold movie codes; fixed before scoring")
    p.add_argument("--json-out")
    args = p.parse_args()
    if len(args.arms) % 2:
        p.error("arms must be LABEL DIRECTORY pairs")
    films = {int(x) for x in args.films.split(",")} if args.films else None
    ranges, gold = film_ranges(args.annotation), load_gold(args.gold)
    result = {"schema":SCHEMA,"bootstrap":{"unit":"participant","draws":BOOTSTRAP,"seed":SEED},
              "goldFilms":sorted(films) if films else "all", "inputs":{"annotationSha256":digest(args.annotation),"goldSha256":digest(args.gold)},
              "arms":{}, "comparisons":{}}
    base, base_label, base_population = None, None, None
    for label, arm in zip(args.arms[::2],args.arms[1::2]):
        if label in result["arms"]:
            raise ValueError("duplicate arm label")
        population = recall_population(arm)
        if base_population is not None and population != base_population:
            raise ValueError("paired comparison requires the complete unchanged recall population, including excluded units")
        base_population = population
        units, counts = score(arm,gold,ranges,films)
        result["arms"][label] = summarize(units,counts,ranges,films)
        result["arms"][label]["recallPopulationSha256"] = population
        result["arms"][label]["reportSha256"] = {f.name:digest(f) for f in sorted(Path(arm).glob('recall-map-*.tsv'))}
        if base is None:
            base,base_label = units,label
        else:
            result["comparisons"][f"{label}-minus-{base_label}"] = compare(base,units)
    body=json.dumps(result,indent=2,sort_keys=True,allow_nan=False)+"\n"
    if args.json_out:
        Path(args.json_out).write_text(body)
    print(body,end="")


if __name__ == "__main__":
    main()
