"""Case study 2, milestone 0: compare the library parity outputs with the case-study-1 harness.

Library side: `ReferenceParity` (pipeline) wrote, per development participant,
  <NNxx>_desc-libraryparity_timeseries.tsv   bins on the OpenNeuro clock (1.5 s)
  <NNxx>_desc-libraryparity_references.tsv   one row per reference the reply made
  <NNxx>_desc-libraryparity_words.tsv        one row per word cell: unit, held sample, onset
Harness side: export-raw/<NNxx>_desc-recallspecificity_timeseries.tsv (stream3 --raw-weights,
analysis4) and runs/it3-v2-a-raw/<participant>.npz (per-word k, wr, B, U).

The report names unit ids and counts only; it never prints recall or film text (rule 14d).
Usage: python3 -I compare.py <dataRoot> <libraryDir> [--json out.json]
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import re
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

PART_B_FIRST_ROW = 483
EXACT = 5e-5  # the harness writes four decimals
NEAR = 0.05


def read_tsv(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def num(x):
    return None if x in ("n/a", "", None) else float(x)


def film_rows(root):
    """Annotation row id -> (start, end, part) in raw run-local seconds; the harness's grid."""
    out = {}
    with open(
        root / "sherlock/Sherlock_Segments_1000_NN_2017.tsv", encoding="utf-8-sig"
    ) as f:
        r = csv.reader(f, delimiter="\t")
        next(r)
        for x in r:
            if x and x[0].strip():
                out[int(x[0])] = (float(x[1]), float(x[2]))
    part_a_len = max(e for i, (s, e) in out.items() if i < PART_B_FIRST_ROW)
    grid = {}
    for i, (s, e) in out.items():
        off = part_a_len if i >= PART_B_FIRST_ROW else 0.0
        a = int(math.floor(s + off))
        grid[i] = (a, max(int(math.ceil(e + off)), a + 1))
    return out, grid


def harness_B(alts, grid):
    """The harness's raw-weight breadth: sum_k (w_k / sum w) |union of hypothesis k's rows| on a
    1-s grid of the global film axis (stream3.ref_quantities with alpha 1)."""
    sizes, ws = [], []
    for a in alts:
        secs = set()
        for r1, r2 in a["iv"]:
            r1, r2 = sorted((int(r1), int(r2)))
            for rid in range(max(r1, 1), min(r2, 1000) + 1):
                if rid in grid:
                    secs.update(range(*grid[rid]))
        if secs and float(a["w"]) > 0:
            sizes.append(len(secs))
            ws.append(float(a["w"]))
    ws = np.array(ws)
    if ws.sum() > 1:
        ws = ws / ws.sum()
    pi = ws / ws.sum()
    return float((pi * np.array(sizes)).sum())


def raw_extent(alts, form, rows):
    """The library reducer on raw annotation seconds: per constituent the measure of the union of
    its rows' intervals (zero-duration rows are points, measured 0), summed over constituents;
    then sum w E / sum w. Constituents follow the conversion table."""

    def union(rids):
        iv = sorted((rows[r][0], rows[r][1]) for r in rids if rows[r][1] > rows[r][0])
        tot, cur = 0.0, None
        for s, e in iv:
            if cur and s <= cur[1]:
                cur = (cur[0], max(cur[1], e))
            else:
                if cur:
                    tot += cur[1] - cur[0]
                cur = (s, e)
        return tot + (cur[1] - cur[0] if cur else 0.0)

    E, W = [], []
    for a in alts:
        ivs = [list(range(r1, r2 + 1)) for r1, r2 in a["iv"]]
        if form in ("repeated", "joint"):
            E.append(sum(union(c) for c in ivs))
        else:
            E.append(union(sorted({r for c in ivs for r in c})))
        W.append(float(a["w"]))
    return sum(w * e for w, e in zip(W, E)) / sum(W)


def overlapping_constituents(alts, form):
    if form not in ("repeated", "joint"):
        return False
    for a in alts:
        sets = [set(range(r1, r2 + 1)) for r1, r2 in a["iv"]]
        for i in range(len(sets)):
            for j in range(i + 1, len(sets)):
                if sets[i] & sets[j]:
                    return True
    return False


def compare_bins(lib, har):
    out = {"bins_library": len(lib), "bins_harness": len(har)}
    n = min(len(lib), len(har))
    pairs = [(lib[i], har[i]) for i in range(n)]
    assert all(abs(float(a["onset"]) - float(b["onset"])) < 1e-9 for a, b in pairs)
    status_map = {"only_uncovered_or_unassigned": "only_uncovered"}
    out["status"] = Counter(
        f"{a['status']}|{status_map.get(b['status'], b['status'])}" for a, b in pairs
    )
    counts = [
        ("words", "words"),
        ("n_refs", "n_refs"),
        ("n_not_applicable_refs", "n_not_applicable_units"),
        ("n_uncovered_member_words", "n_uncovered_words"),
        ("n_uncovered_unassigned_words", "n_unassigned_words"),
    ]
    out["counts"] = {
        f"{l}~{h}": sum(int(a[l]) == int(b[h]) for a, b in pairs) for l, h in counts
    }
    # Uncovered words in total: the split between "in a unit" and "outside every unit" differs
    # when the harness assigns a word to a unit by onset and the library by text membership.
    out["counts"]["uncovered_total"] = sum(
        int(a["n_uncovered_member_words"]) + int(a["n_uncovered_unassigned_words"])
        == int(b["n_uncovered_words"]) + int(b["n_unassigned_words"])
        for a, b in pairs
    )
    same_counts = [
        all(int(a[l]) == int(b[h]) for l, h in counts[1:3]) for a, b in pairs
    ]
    cols = {}
    for l, h in [
        ("extent_log10_s", "logB"),
        ("alternatives_present_share", "Uflag"),
    ]:
        c = Counter()
        xs, ys = [], []
        far_refs_same, far_refs_diff = 0, 0
        for (a, b), same in zip(pairs, same_counts):
            x, y = num(a[l]), num(b[h])
            if x is None and y is None:
                c["both n/a"] += 1
            elif x is None:
                c["library n/a, harness value"] += 1
            elif y is None:
                c["harness n/a, library value"] += 1
            else:
                xs.append(x)
                ys.append(y)
                d = abs(x - y)
                if d <= EXACT:
                    c["exact"] += 1
                elif d <= NEAR:
                    c["near"] += 1
                else:
                    c["far"] += 1
                if d > EXACT:
                    if same:
                        far_refs_same += 1
                    else:
                        far_refs_diff += 1
        cols[f"{l}~{h}"] = dict(
            c,
            compared=len(xs),
            pearson=float(np.corrcoef(xs, ys)[0, 1]) if len(xs) > 2 else None,
            mean_abs_diff=float(np.mean(np.abs(np.array(xs) - np.array(ys))))
            if xs
            else None,
            differing_with_same_reference_counts=far_refs_same,
            differing_with_different_reference_counts=far_refs_diff,
        )
        one_sided = c["library n/a, harness value"] + c["harness n/a, library value"]
        cols[f"{l}~{h}"]["agreement"] = dict(
            agree=c["exact"], of=len(xs) + one_sided, one_sided=one_sided
        )
    out["columns"] = cols
    # Not a parity column: the library's not-applicable share is a share of sampled REFERENCES,
    # the harness's nonepisodic a share of WORDS with an onset. Counts only, never agreement.
    d = Counter()
    for a, b in pairs:
        x, y = num(a["not_applicable_share"]), num(b["nonepisodic"])
        if x is None and y is None:
            d["both n/a"] += 1
        elif x is None:
            d["harness only (bins with words but no sampled reference)"] += 1
        elif y is None:
            d["library only"] += 1
        else:
            d["both valued"] += 1
            d["both valued, numerically equal"] += abs(x - y) <= EXACT
    out["definitional_differences"] = {
        "not_applicable_share vs nonepisodic": dict(
            d,
            note="library: not-applicable references / sampled references; harness: words in "
            "non-episodic units / words with an onset. Different quantities; not compared.",
        )
    }
    out["status"] = dict(out["status"])
    return out


def compare_words(lib_words, z, cells, refs):
    """Per word: unit assignment and held sample on each side, with the difference classes.
    `cells` are the CSV word cells (used only to count normalized tokens; never printed)."""
    k, wr = z["k"], z["wr"]
    talk = z["talk"]
    refused = {
        (int(r["unit_index"]), int(r["ref_index"])): r["reason"]
        for r in refs
        if r["status"] == "refused"
    }
    unit_id = {int(r["unit_index"]): r["unit_id"] for r in refs}
    c = Counter()
    listed = defaultdict(list)
    for i, w in enumerate(lib_words):
        lu = int(w["unit_index"])
        hu = int(k[i])
        if lu != hu:
            if lu < 0:
                key = "POLICY: word outside every unit; harness holds the preceding unit (onset)"
            elif hu < 0:
                key = "library unit, harness unassigned"
            else:
                key = "HARNESS BUG: word assigned to another unit by onset (tied/non-monotone)"
                listed[key].append(dict(word=i, library_unit=lu, harness_unit=hu))
            c[key] += 1
            continue
        s = w["sample"]
        if lu < 0:
            c["both unassigned"] += 1
            continue
        nonep = talk[i] in ("metamemory", "commentary", "fragment")
        h_ref = int(wr[i])
        if s.startswith("na:"):
            c["not applicable (both)" if nonep else "library not applicable, harness episodic"] += 1
        elif s == "-1":
            if nonep:
                c["library uncovered, harness not applicable"] += 1
            elif h_ref < 0 or np.isnan(z["B"][i]):
                c["uncovered (both)"] += 1
            else:
                why = refused.get((lu, h_ref))
                key = (
                    f"LIBRARY STRICTER: word of a refused reference ({why})"
                    if why
                    else "library uncovered, harness covered (other)"
                )
                c[key] += 1
                listed[key].append(dict(word=i, unit=unit_id.get(lu, lu), ref=h_ref))
        else:
            ref = int(s.split(":")[1])
            if nonep:
                c["library reference, harness not applicable"] += 1
            elif h_ref < 0:
                several = len(toks(cells[i])) != 1
                key = (
                    "HARNESS BUG: anchor alignment misses a cell with several normalized tokens"
                    if several
                    else "library covered, harness uncovered (other)"
                )
                c[key] += 1
                listed[key].append(dict(word=i, unit=unit_id.get(lu, lu), ref=ref))
            elif h_ref == ref:
                c["same reference"] += 1
            else:
                c["different reference"] += 1
    return dict(c), dict(listed)


NORM = re.compile(r"[^a-z0-9']+")


def toks(s):
    """The harness's normalization (stream3.toks)."""
    return [t for t in NORM.sub(" ", s.lower()).split() if t]


def word_cells(root, name):
    with open(root / f"sherlock/recall/{name}.csv", encoding="utf-8-sig", newline="") as f:
        r = csv.reader(f)
        next(r)
        return [x[0].strip() for x in r if x and x[0].strip()]


def compare_refs(lib_refs, z, answers, rows, grid):
    """Per reference: library extent vs harness B (from the npz) and the attribution."""
    hB, hU = {}, {}
    for i in range(len(z["k"])):
        u, r = int(z["k"][i]), int(z["wr"][i])
        if u >= 0 and r >= 0 and not np.isnan(z["B"][i]):
            hB[(u, r)] = float(z["B"][i])
            hU[(u, r)] = bool(z["U"][i] > 0)
    classes = defaultdict(list)
    recomputed_ok = 0
    recomputed = 0
    altflag = Counter()
    for r in lib_refs:
        key = (int(r["unit_index"]), int(r["ref_index"]))
        uid = r["unit_id"]
        ref = answers[uid]["refs"][key[1]]
        hb = harness_B(ref["alts"], grid)
        if key in hB:
            recomputed += 1
            recomputed_ok += abs(hb - hB[key]) < 1e-9
            if r["status"] == "converted":
                altflag[
                    "same"
                    if (r["alternatives_present"] == "true") == hU[key]
                    else "differs"
                ] += 1
        if r["status"] != "converted":
            classes[f"refused: {r['reason']}"].append(uid)
            continue
        lib = num(r["extent_s"])
        if lib is None:
            classes[f"library extent unavailable: {r['reason']}"].append(uid)
            continue
        raw = raw_extent(ref["alts"], ref["form"], rows)
        if abs(lib - hb) <= 1e-6:
            classes["exact"].append(uid)
        elif abs(lib - raw) > 1e-6:
            classes[
                "library ticks differ from raw annotation seconds (clock repair)"
            ].append(uid)
        elif overlapping_constituents(ref["alts"], ref["form"]):
            classes[
                "overlapping occurrences: library sums constituents, harness takes the union"
            ].append(uid)
        elif any(
            rows[x][1] <= rows[x][0]
            for a in ref["alts"]
            for r1, r2 in a["iv"]
            for x in range(r1, r2 + 1)
        ):
            classes[
                "zero-duration row: library point (0 s), harness 1-s grid cell"
            ].append(uid)
        else:
            classes["other"].append(uid)
    return (
        {
            k: {"count": len(v), "units": sorted(set(v))[:50]}
            for k, v in sorted(classes.items())
        },
        dict(recomputed=recomputed, harness_B_reproduced=recomputed_ok),
        dict(altflag),
    )


def explain_bins(lib, har, words, z, refs):
    """For every bin whose extent or alternatives value differs, the reason in terms of the
    references each side sampled there: (unit, ref) present on one side only, and why."""
    status = {(int(r["unit_index"]), int(r["ref_index"])): r for r in refs}
    lib_by_bin, har_by_bin = defaultdict(set), defaultdict(set)
    for i, w in enumerate(words):
        if w["onset"] == "n/a":
            continue
        b = int(math.floor(float(w["onset"]) / 1.5))
        s = w["sample"]
        if s != "-1" and not s.startswith("na:"):
            u, r = map(int, s.split(":"))
            lib_by_bin[b].add((u, r))
        u, r = int(z["k"][i]), int(z["wr"][i])
        if u >= 0 and r >= 0 and not np.isnan(z["B"][i]):
            har_by_bin[b].add((u, r))
    reasons = Counter()
    detail = []
    offaxis = set()
    unexplained = 0
    for b in range(min(len(lib), len(har))):
        a, h = lib[b], har[b]
        diffs = []
        for l, hh in [("extent_log10_s", "logB"), ("alternatives_present_share", "Uflag")]:
            x, y = num(a[l]), num(h[hh])
            if (x is None) != (y is None) or (x is not None and abs(x - y) > EXACT):
                diffs.append(l)
        if not diffs:
            continue
        why, units = [], set()
        for key in sorted(har_by_bin[b] - lib_by_bin[b]):
            r = status.get(key)
            if r and r["status"] == "refused":
                why.append(f"harness-only reference: library refused ({r['reason']})")
            else:
                why.append("harness-only reference: word-to-unit or coverage differs")
            if r:
                units.add(r["unit_id"])
        for key in sorted(lib_by_bin[b] - har_by_bin[b]):
            why.append("library-only reference: word-to-unit or coverage differs")
            if key in status:
                units.add(status[key]["unit_id"])
        if "extent_log10_s" in diffs:
            for key in sorted(lib_by_bin[b] & har_by_bin[b]):
                r = status.get(key)
                if r and r["status"] == "converted" and r["extent_s"] == "n/a":
                    why.append(f"shared reference, library extent unavailable ({r['reason']})")
                    units.add(r["unit_id"])
        why = sorted(set(why)) or ["unexplained"]
        if any("extent unavailable" in w for w in why):
            cls = "cross-part extent unavailable (library stricter)"
        elif any("library refused" in w for w in why):
            cls = "reference refused by the library"
        elif why == ["unexplained"]:
            cls = "unexplained"
        else:
            cls = "word-to-unit assignment or coverage (harness)"
        for w in why:
            reasons[f"{'+'.join(diffs)}: {w}"] += 1
        detail.append(dict(bin=b, columns=diffs, cls=cls, reasons=why, units=sorted(units)))
    return dict(reasons), detail


def summary(report):
    """The headline: agreement with one-sided bins in the denominator, differing bins by class,
    the references and words behind them. Unit ids and word indices only."""
    out = {}
    for col in ["extent_log10_s~logB", "alternatives_present_share~Uflag"]:
        agree = sum(r["bins"]["columns"][col]["agreement"]["agree"] for r in report.values())
        of = sum(r["bins"]["columns"][col]["agreement"]["of"] for r in report.values())
        lcol = col.split("~")[0]
        by_class = Counter(
            d["cls"] for r in report.values() for d in r["differing_bin_detail"]
            if lcol in d["columns"]
        )
        out[col] = dict(agree=agree, of=of, differing=of - agree, differing_by_class=dict(by_class))
    out["unavailable_extent_references"] = sorted(
        {(p, u) for p, r in report.items() for d in r["differing_bin_detail"]
         if d["cls"].startswith("cross-part") for u in d["units"]}
    )
    out["refused_references"] = sorted(
        (p, u, k.split(": ", 1)[1])
        for p, r in report.items()
        for k, v in r["reference_extent_classes"].items()
        if k.startswith("refused")
        for u in v["units"]
    )
    words = Counter()
    listed = defaultdict(list)
    for p, r in report.items():
        for k, v in r["word_classes"].items():
            words[k] += v
        for k, v in r["listed_words"].items():
            listed[k] += [dict(participant=p, **x) for x in v]
    out["word_classes"] = dict(words)
    out["listed_words"] = dict(listed)
    defs = Counter()
    for r in report.values():
        for k, v in r["bins"]["definitional_differences"]["not_applicable_share vs nonepisodic"].items():
            if isinstance(v, int):
                defs[k] += v
    out["definitional_differences"] = {"not_applicable_share vs nonepisodic": dict(defs)}
    out["unexplained_bins"] = sum(
        d["cls"] == "unexplained" for r in report.values() for d in r["differing_bin_detail"]
    )
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data_root", type=Path)
    ap.add_argument("library_dir", type=Path)
    ap.add_argument("--export", default="study/recall-precision/export-raw")
    ap.add_argument("--run", default="study/recall-precision/runs/it3-v2-a-raw")
    ap.add_argument("--answers", default="study/recall-precision/llm/llm-local-v2-a")
    ap.add_argument("--json", type=Path)
    a = ap.parse_args()
    root = a.data_root
    part = json.loads((root / "study/recall-to-video/partition.json").read_text())
    untouched = set(part["untouchedTest"])
    rows, grid = film_rows(root)
    report = {}
    for name in sorted(part["development"]):
        if name in untouched:
            continue
        p = name[:4]
        lib = read_tsv(a.library_dir / f"{p}_desc-libraryparity_timeseries.tsv")
        har = read_tsv(root / a.export / f"{p}_desc-recallspecificity_timeseries.tsv")
        z = np.load(root / a.run / f"{name}.npz", allow_pickle=True)
        words = read_tsv(a.library_dir / f"{p}_desc-libraryparity_words.tsv")
        refs = read_tsv(a.library_dir / f"{p}_desc-libraryparity_references.tsv")
        answers = {
            u["id"]: u
            for u in json.loads((root / a.answers / f"{name}.json").read_text())[
                "units"
            ]
        }
        assert len(words) == len(z["k"]), f"{p}: word count differs"
        onset_ok = sum(
            (w["onset"] == "n/a" and np.isnan(t))
            or (w["onset"] != "n/a" and abs(float(w["onset"]) - t) < 1e-9)
            for w, t in zip(words, z["tn"])
        )
        wc, listed = compare_words(words, z, word_cells(root, name), refs)
        bin_reasons, bin_detail = explain_bins(lib, har, words, z, refs)
        rc, recomputation, altflag = compare_refs(refs, z, answers, rows, grid)
        report[p] = dict(
            words=len(words),
            onsets_equal=int(onset_ok),
            bins=compare_bins(lib, har),
            word_classes=wc,
            listed_words=listed,
            differing_bins=bin_reasons,
            differing_bin_detail=bin_detail,
            reference_extent_classes=rc,
            harness_recomputation=recomputation,
            reference_alternatives_flag=altflag,
        )
    total = defaultdict(Counter)
    for p, r in report.items():
        for col, v in r["bins"]["columns"].items():
            for k2, x in v.items():
                if isinstance(x, int):
                    total[col][k2] += x
        for k2, x in r["differing_bins"].items():
            total["differing_bins"][k2] += x
        for k2, x in r["word_classes"].items():
            total["words"][k2] += x
        for k2, x in r["reference_extent_classes"].items():
            total["references"][k2] += x["count"]
        for k2, x in r["bins"]["status"].items():
            total["status"][k2] += x
        for k2, x in r["bins"]["counts"].items():
            total["counts_equal"][k2] += x
        total["counts_equal"]["bins"] += min(
            r["bins"]["bins_library"], r["bins"]["bins_harness"]
        )
    participants = dict(report)
    report["TOTAL"] = {k: dict(v) for k, v in total.items()}
    report["SUMMARY"] = summary(participants)
    text = json.dumps(report, indent=1, default=int)
    if a.json:
        a.json.write_text(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
