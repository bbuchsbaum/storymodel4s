#!/usr/bin/env python3
"""Cross-participant agreement: a gold-free proxy that confidence alone cannot win.

Every outcome the study has used so far can be inflated by making the model more certain. Kendall
tau rises with a sequential prior because tau *is* sequentiality; concentration and localizability
are measures of how peaked the posterior is, so any stronger prior sharpens them whether or not it
is right. That is the standing weakness of gold-free evaluation, and it is why a prior sweep that
improves all three should not be believed on their word alone.

This measures something else. Different participants watched the *same* film, so when two of them
recall the same moment, a correct mapping puts both of their descriptions at the same place in the
film. The pairing of recall units across participants is computed from the recall text alone and is
identical for every arm, so no arm can change which units are compared. A model that became more
confident without becoming more accurate would move both anchors no closer together.

Units are paired by mutual-best inverse-document-frequency overlap above a floor, which keeps only
pairs likely to describe the same event and discards the rest rather than pairing them badly. The
reported statistic is the median absolute difference in film seconds between paired anchors: lower
is better, and it is bounded below by genuine disagreement about what the recall refers to.

Usage: agreement.py LABEL DIR [LABEL DIR ...]
"""
import csv, glob, json, math, os, random, statistics, sys

# A continuous film timeline in real seconds. Part A runs to 1426s in the annotation, so part B is
# offset by that rather than by a large sentinel: with a sentinel offset a pair split across the two
# parts contributes a gap of tens of thousands of seconds and dominates any mean.
PART = {"media-part-a": 0.0, "media-part-b": 1426.0}


def part_offsets(d):
    """Per-corpus part offsets, defaulting to Sherlock's two media parts.

    A corpus whose parts are not Sherlock's writes `parts.json` beside its reports, mapping each
    `mediaPart` id to the seconds at which that part begins on one continuous timeline. Film
    Festival's two scanning runs are such a corpus. Sherlock arms carry no such file and are
    unaffected.
    """
    path = os.path.join(d, "parts.json")
    if not os.path.exists(path):
        return PART
    with open(path, encoding="utf-8") as fh:
        return {k: float(v) for k, v in json.load(fh).items()}
MIN_OVERLAP = 0.22
MIN_TOKENS = 4
STOP = set(
    "the a an and or but if then of to in on at for with from by as is are was were be been being "
    "it its this that these those he she they them his her their there here what which who when "
    "where how all any both each few more most other some such no nor not only own same so than "
    "too very can will just do does did doing i you your we us our me my him himself herself "
    "like really kind sort okay yeah um uh got get going goes went said says say".split()
)


def toks(s):
    return [w for w in "".join(c if c.isalnum() else " " for c in s.lower()).split()
            if w not in STOP and len(w) > 2]


def load(d):
    parts = part_offsets(d)
    out = {}
    for p in sorted(glob.glob(os.path.join(d, "recall-map-*.tsv"))):
        name = os.path.basename(p).replace("recall-map-", "").replace(".tsv", "")
        rows = []
        for r in csv.DictReader(open(p, newline="", encoding="utf-8"), delimiter="\t"):
            if r.get("mediaPart") in parts and r.get("startSeconds"):
                rows.append(
                    (r.get("recallText") or "", parts[r["mediaPart"]] + float(r["startSeconds"]))
                )
        out[name] = rows
    return out


def idf_of(all_docs):
    n = len(all_docs)
    df = {}
    for d in all_docs:
        for w in set(d):
            df[w] = df.get(w, 0) + 1
    return {w: math.log(1 + (n - v + 0.5) / (v + 0.5)) for w, v in df.items()}


def pairs_for(texts_a, texts_b, idf):
    """Mutual-best IDF-overlap pairs above the floor. Independent of any arm's anchors."""
    wa = [(set(t), sum(idf.get(w, 0.0) for w in set(t))) for t in texts_a]
    wb = [(set(t), sum(idf.get(w, 0.0) for w in set(t))) for t in texts_b]
    best_ab, score_ab = {}, {}
    for i, (sa, na) in enumerate(wa):
        if len(sa) < MIN_TOKENS:
            continue
        bi, bs = None, 0.0
        for j, (sb, nb) in enumerate(wb):
            if len(sb) < MIN_TOKENS:
                continue
            inter = sum(idf.get(w, 0.0) for w in (sa & sb))
            den = na + nb - inter
            s = inter / den if den > 0 else 0.0
            if s > bs:
                bi, bs = j, s
        if bi is not None and bs >= MIN_OVERLAP:
            best_ab[i], score_ab[i] = bi, bs
    best_ba = {}
    for j, (sb, nb) in enumerate(wb):
        if len(sb) < MIN_TOKENS:
            continue
        bj, bs = None, 0.0
        for i, (sa, na) in enumerate(wa):
            if len(sa) < MIN_TOKENS:
                continue
            inter = sum(idf.get(w, 0.0) for w in (sa & sb))
            den = na + nb - inter
            s = inter / den if den > 0 else 0.0
            if s > bs:
                bj, bs = i, s
        if bj is not None and bs >= MIN_OVERLAP:
            best_ba[j] = bj
    return [(i, j) for i, j in best_ab.items() if best_ba.get(j) == i]


def wilcoxon_p(d):
    """Two-sided Wilcoxon signed-rank, normal approximation with tie correction."""
    d = [x for x in d if x != 0]
    n = len(d)
    if n < 10:
        return 1.0
    order = sorted(range(n), key=lambda i: abs(d[i]))
    ranks = [0.0] * n
    i = 0
    while i < n:
        j = i
        while j + 1 < n and abs(d[order[j + 1]]) == abs(d[order[i]]):
            j += 1
        avg = (i + j) / 2.0 + 1.0
        for k in range(i, j + 1):
            ranks[order[k]] = avg
        i = j + 1
    wplus = sum(ranks[i] for i in range(n) if d[i] > 0)
    mean = n * (n + 1) / 4.0
    sd = math.sqrt(n * (n + 1) * (2 * n + 1) / 24.0)
    if sd == 0:
        return 1.0
    z = (wplus - mean) / sd
    return math.erfc(abs(z) / math.sqrt(2))


def binom_p(k, n, p=0.5):
    """Two-sided exact sign test."""
    if n == 0:
        return 1.0
    def pmf(i):
        return math.comb(n, i) * (p ** i) * ((1 - p) ** (n - i))
    obs = pmf(k)
    return min(1.0, sum(pmf(i) for i in range(n + 1) if pmf(i) <= obs * (1 + 1e-9)))


def boot(v, seed=20260902, n=1000):
    r = random.Random(seed)
    m = sorted(statistics.median(r.choices(v, k=len(v))) for _ in range(n))
    return m[int(0.025 * n)], m[int(0.975 * n) - 1]


def main(argv):
    args = argv[1:]
    arms = [(args[i], args[i + 1]) for i in range(0, len(args) - 1, 2)]
    if not arms:
        print(__doc__)
        sys.exit(2)
    # The pairing is computed once, from the first arm's recall texts, and reused for every arm so
    # that all arms are scored on exactly the same set of cross-participant comparisons.
    ref = load(arms[0][1])
    names = sorted(ref)
    tok = {n: [toks(t) for t, _ in ref[n]] for n in names}
    idf = idf_of([d for n in names for d in tok[n]])
    pairing = {}
    for a in range(len(names)):
        for b in range(a + 1, len(names)):
            pa, pb = names[a], names[b]
            pairing[(pa, pb)] = pairs_for(tok[pa], tok[pb], idf)
    per_arm = {}
    total = sum(len(v) for v in pairing.values())
    print(f"pairing: {total} mutual-best cross-participant unit pairs over "
          f"{len(names)} participants, fixed across arms")
    for label, d in arms:
        rows = load(d)
        gaps = []
        for (pa, pb), prs in pairing.items():
            if pa not in rows or pb not in rows:
                continue
            for i, j in prs:
                if i < len(rows[pa]) and j < len(rows[pb]):
                    gaps.append(abs(rows[pa][i][1] - rows[pb][j][1]))
        if not gaps:
            print(f"   {label:22s} no comparable pairs")
            continue
        lo, hi = boot(gaps)
        within60 = 100.0 * sum(1 for g in gaps if g <= 60) / len(gaps)
        print(f"   {label:22s} median gap {statistics.median(gaps):8.1f}s  "
              f"95% CI [{lo:.1f}, {hi:.1f}]  within 60s: {within60:5.1f}%  n={len(gaps)}")
        per_arm[label] = gaps

    # Paired over the identical pair set. Three statistics, because the first one tried was a bad
    # choice: the median of the per-pair differences is 0 whenever most anchors are unchanged
    # between arms, which is the usual case here, so it reported nulls for effects that are plainly
    # visible in the medians themselves. What matters is whether the *distribution* of gaps improved,
    # so the paired bootstrap resamples pair indices once and scores both arms on that same resample.
    ref_label = arms[0][0]
    if ref_label in per_arm:
        base = per_arm[ref_label]
        n = len(base)
        print(f"\n== paired against {ref_label}, same {n} pairs, resampled together ==")
        print("   negative change means the two participants' anchors agree more closely")
        for label, _ in arms[1:]:
            cur = per_arm.get(label)
            if cur is None or len(cur) != n:
                continue
            r = random.Random(20260902)
            dmed, dw60 = [], []
            for _ in range(4000):
                idx = [r.randrange(n) for _ in range(n)]
                dmed.append(statistics.median([cur[i] for i in idx])
                            - statistics.median([base[i] for i in idx]))
                dw60.append(100.0 * (sum(1 for i in idx if cur[i] <= 60)
                                     - sum(1 for i in idx if base[i] <= 60)) / n)
            dmed.sort()
            dw60.sort()
            mlo, mhi = dmed[100], dmed[3899]
            wlo, whi = dw60[100], dw60[3899]
            mex = "excludes zero" if (mlo > 0 or mhi < 0) else "includes zero"
            wex = "excludes zero" if (wlo > 0 or whi < 0) else "includes zero"
            obs_m = statistics.median(cur) - statistics.median(base)
            obs_w = 100.0 * (sum(1 for x in cur if x <= 60) - sum(1 for x in base if x <= 60)) / n
            # Wilcoxon signed-rank over the pairs that moved, which uses magnitude rather than
            # only the sign, normal approximation with tie-corrected ranks.
            d = [c - b for c, b in zip(cur, base) if c != b]
            p = wilcoxon_p(d)
            print(f"   {label:22s} median gap {obs_m:+7.1f}s [{mlo:+.1f}, {mhi:+.1f}] {mex}   "
                  f"within60 {obs_w:+5.1f}pp [{wlo:+.1f}, {whi:+.1f}] {wex}   signed-rank p={p:.4f}")


main(sys.argv)
