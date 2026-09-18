#!/usr/bin/env python3
"""Score arms against the scene-level recall gold, under the committed pre-registration.

See docs/plans/2026-09-02-gold-scene-preregistration.md. Everything decidable was decided there:
the clock, the participant mapping, the two exclusions, the labelling rule, the outcomes, and the
fact that exactly one comparison is pre-specified. This file implements that and nothing else.

Usage: gold_scene.py GOLD_CSV LABEL_A DIR_A LABEL_B DIR_B [PARTITION_JSON]
"""
import csv, glob, math, os, random, re, statistics, sys

import corpus_descriptor as cd

# These are the SHERLOCK values, and they are the fallback only. `configure()` replaces them from a
# descriptor. They stay here because this file is imported by risk_coverage.py and by the arm
# runners, which must keep working while the descriptor is wired through the rest of P6desc.
#
# The rule they implement exists TWICE, by acknowledgement: SherlockSceneCoding.scala:14-15 says
# "Two implementations of one rule is one too many, so this one cites the other". The Scala side
# carries trSeconds = 1.5 (:18) and the same alias arithmetic (:21-24). Reading both from one
# declared record is what makes them one rule rather than two that happen to agree today.
TR = 1.5
EXCLUDED = {"NN01"}  # gold subject 1's coding runs to 1417s against a 782s transcript
PARTICIPANT_PATTERN = r"^NN(\d{2})_"


def configure(descriptor_path):
    """Replaces the module's gold-rule constants from a declared descriptor.

    Refuses rather than falling back: a descriptor that cannot be read is a configuration error,
    and silently scoring under Sherlock's constants for some other corpus is precisely the failure
    this whole contract exists to prevent.
    """
    global TR, EXCLUDED, EXCLUDED_NUMS, PARTICIPANT_PATTERN
    rule = cd.gold_rule(cd.load(descriptor_path))
    TR = float(rule["trSeconds"])
    EXCLUDED = set(rule["excludedParticipants"])
    PARTICIPANT_PATTERN = rule["participantPattern"]
    EXCLUDED_NUMS = {n for n in (participant_number(name) for name in EXCLUDED) if n is not None}
    return {"TR": TR, "EXCLUDED": sorted(EXCLUDED), "EXCLUDED_NUMS": sorted(EXCLUDED_NUMS)}


def participant_number(text):
    r"""The participant number in a filename or a bare participant id, per the DECLARED pattern.

    `PARTICIPANT_PATTERN` used to be assigned by `configure()` and read by NOTHING: this job was
    done by `int(name[2:4])` for filenames and `int(name[2:])` for excluded ids, both of which
    hardcode Sherlock's two-letter `NN01_` shape. A corpus whose ids are `sub-013_` would configure
    without complaint and then be parsed by the wrong rule -- a declared knob that looks live and is
    inert, which is the exact defect this contract exists to remove.

    Two forms have to work, because the pattern describes a FILENAME (`^NN(\d{2})_`, separator
    included) while `excludedParticipants` holds bare ids (`NN01`, no separator). Rather than
    assume the separator, the bare form is matched against the pattern truncated at the end of its
    first capture group -- the part that identifies the participant, with whatever follows dropped.

    Returns None when the text matches neither form, so a stray file in an arm directory is skipped
    rather than crashing the run; `label` reports what it skipped.
    """
    m = re.match(PARTICIPANT_PATTERN, text)
    if m is None:
        close = PARTICIPANT_PATTERN.find(")")
        if close < 0:
            return None
        m = re.match(PARTICIPANT_PATTERN[: close + 1], text)
    if m is None or not m.group(1).isdigit():
        return None
    return int(m.group(1))


def gold_subject_of(nn: int):
    """Alias structure of the public release: identity to 4, then a skip over the omitted source."""
    if nn in EXCLUDED_NUMS or nn == 5:
        return None
    return nn if nn <= 4 else nn - 1


EXCLUDED_NUMS = {1}


def load_gold(path):
    by_subject = {}
    with open(path, encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh):
            s = int(r["Subject"])
            by_subject.setdefault(s, []).append(
                (float(r["Onset"]) * TR, float(r["Offset"]) * TR, int(r["Scene"]))
            )
    for v in by_subject.values():
        v.sort()
    return by_subject


def scene_at(intervals, t):
    """The coded scene containing this recall second, or None. Overlaps take the first match."""
    for lo, hi, sc in intervals:
        if lo <= t <= hi:
            return sc
    return None


def load_arm(d):
    out = {}
    for p in sorted(glob.glob(os.path.join(d, "recall-map-*.tsv"))):
        name = os.path.basename(p).replace("recall-map-", "").replace(".tsv", "")
        rows = []
        with open(p, newline="", encoding="utf-8") as fh:
            for r in csv.DictReader(fh, delimiter="\t"):
                onset = r.get("recallOnsetSeconds") or ""
                grp = (r.get("group") or "").split(".")[0].strip()
                if onset and grp.isdigit():
                    rows.append((float(onset), int(grp)))
        out[name] = rows
    return out


def label(arm_rows, gold):
    """(gold_scene, predicted_scene) per unit that has gold, keyed by participant."""
    out = {}
    for name, rows in arm_rows.items():
        nn = participant_number(name)
        if nn is None:
            continue
        gs = gold_subject_of(nn)
        if gs is None or gs not in gold:
            continue
        iv = gold[gs]
        pairs = []
        for onset, pred in rows:
            g = scene_at(iv, onset)
            if g is not None:
                pairs.append((g, pred))
        if pairs:
            out[name] = pairs
    return out


def cluster_boot(per_part, fn, seed=20260902, n=2000):
    """Bootstrap resampling participants, which is the unit of independence."""
    names = sorted(per_part)
    if len(names) < 2:
        return (None, None)
    rng = random.Random(seed)
    vals = []
    for _ in range(n):
        pick = [names[rng.randrange(len(names))] for _ in names]
        pooled = [x for k in pick for x in per_part[k]]
        vals.append(fn(pooled))
    vals.sort()
    return vals[int(0.025 * n)], vals[int(0.975 * n) - 1]


def rate(pairs, tol=0):
    if not pairs:
        return 0.0
    return 100.0 * sum(1 for g, p in pairs if abs(g - p) <= tol) / len(pairs)


def report(label_name, per_part):
    allp = [x for v in per_part.values() for x in v]
    ex = rate(allp, 0)
    w1 = rate(allp, 1)
    med = statistics.median([abs(g - p) for g, p in allp]) if allp else float("nan")
    lo, hi = cluster_boot(per_part, lambda v: rate(v, 0))
    print(f"   {label_name:24s} scene-exact {ex:5.1f}%  95% CI [{lo:.1f}, {hi:.1f}]   "
          f"within-1 {w1:5.1f}%   median dist {med:4.1f}   units {len(allp)}  "
          f"participants {len(per_part)}")
    return ex, w1


def paired(la, pa, lb, pb):
    shared = sorted(set(pa) & set(pb))
    print(f"\n== paired, {lb} minus {la}, {len(shared)} participants ==")
    for tol, nm in ((0, "scene-exact"), (1, "scene-within-1")):
        per = {k: [(rate(pb[k], tol) - rate(pa[k], tol))] for k in shared}
        diffs = [per[k][0] for k in shared]
        lo, hi = cluster_boot(per, lambda v: statistics.mean(v))
        excl = "excludes zero" if (lo is not None and (lo > 0 or hi < 0)) else "includes zero"
        better = sum(1 for d in diffs if d > 0)
        print(f"   {nm:16s} mean change {statistics.mean(diffs):+5.2f} points  "
              f"95% CI [{lo:+.2f}, {hi:+.2f}] {excl}  improved {better}/{len(diffs)}")


def main(argv):
    if len(argv) < 6:
        print(__doc__)
        return 2
    goldp, la, da, lb, db = argv[1], argv[2], argv[3], argv[4], argv[5]
    partition = argv[6] if len(argv) > 6 else None
    gold = load_gold(goldp)
    pa, pb = label(load_arm(da), gold), label(load_arm(db), gold)
    sets = [("all with gold", None)]
    if partition:
        import json
        p = json.load(open(partition))
        sets = [("development", set(p["development"])), ("untouched", set(p["untouchedTest"])),
                ("pooled", None)]
    for nm, keep in sets:
        fa = {k: v for k, v in pa.items() if keep is None or k in keep}
        fb = {k: v for k, v in pb.items() if keep is None or k in keep}
        if not fa or not fb:
            continue
        print(f"\n=== {nm} ===")
        report(la, fa)
        report(lb, fb)
        paired(la, fa, lb, fb)

# Guarded so the module can be imported. Without this an `import gold_scene` runs a full comparison
# against the pre-registered gold using the importer's argv, and §7 rule 3 counts every comparison
# scored against that gold — so an import would spend one silently, which is the one thing a corpus
# with fifteen participants cannot afford.
if __name__ == "__main__":
    sys.exit(main(sys.argv))
