"""Iteration 5: prompt-v3 evaluation (detail channel, span talk, occurrence, manipulation test).

1. Convert v3 records to the v2 shape (talk = episodic if any span is episodic; form from
   extent/occurrence/mode) and score them with stream3 on the identical yardstick.
2. Detail channel: per reference, number of distinguishing details (and by type). Tests:
   - divergent: details vs breadth (the linguistics threat: breadth is film extent, details are
     specificity) should be weakly related;
   - convergent: more distinguishing details -> more often the right scene;
   - per-occurrence breadth (mean interval length within the top hypothesis) vs occurrence count.
3. Span-level talk: share of units with a metamemory / repair span (v2 saw ~1%).
4. Name manipulation (paired by unit): who-details, competing-hypotheses flag, top-hypothesis
   breadth and top-scene agreement, original vs names replaced by "someone".
"""

from __future__ import annotations

import json
from collections import Counter

import numpy as np
from scipy.stats import spearmanr

import data as D
from stream3 import evaluate, refs_v2
from stream_eval import FilmGrid

V3, NAMES = "llm-local-v3-a", "llm-local-v3-names"


def form_of(r):
    if r.get("occurrence") == "repeated":
        return "repeated"
    if r.get("occurrence") == "generic" or r.get("mode") == "gist":
        return "summary"
    return "extended" if r.get("extent") == "stretch" else "specific"


def to_v2(rec):
    spans = rec.get("spans") or []
    talks = [s.get("talk") for s in spans]
    talk = (
        "episodic"
        if "episodic" in talks
        else (Counter(talks).most_common(1)[0][0] if talks else "fragment")
    )
    if talk == "repair":
        talk = "fragment"
    refs = [
        dict(words=r.get("words", ""), form=form_of(r), alts=r.get("alts") or [])
        for r in rec.get("refs") or []
    ]
    return dict(talk=talk, hedge=rec.get("hedge") is not None, refs=refs)


def convert(run):
    src, dst = D.OUT / "llm" / run, D.OUT / "llm" / f"{run}-asv2"
    dst.mkdir(parents=True, exist_ok=True)
    for p in src.glob("*.json"):
        d = json.loads(p.read_text())
        d["units"] = [
            dict(id=u["id"], **to_v2(u)) if "spans" in u else dict(id=u["id"])
            for u in d["units"]
        ]
        (dst / p.name).write_text(json.dumps(d))
    return f"{run}-asv2"


def ref_rows(run, g):
    """One row per reference: details, types, breadth per occurrence, n occurrences, hit."""
    rows = []
    for name in D.development():
        p = D.OUT / "llm" / run / f"{name}.json"
        if not p.exists():
            continue
        recs = {u["id"]: u for u in json.loads(p.read_text())["units"]}
        gold_iv = D.gold_intervals(name)
        for u in D.units(name):
            rec = recs.get(u.id)
            if not rec or "spans" not in rec:
                continue
            gs = D.gold_scene_at(gold_iv, u.onset) if gold_iv else None
            for r in rec.get("refs") or []:
                alts = [a for a in (r.get("alts") or []) if a.get("iv")]
                if not alts:
                    continue
                top = max(alts, key=lambda a: a.get("w", 0))
                lens, mask = [], np.zeros(g.T, bool)
                for r1, r2 in top["iv"]:
                    try:
                        m = g.range_mask(r1, r2)
                    except (TypeError, ValueError):
                        continue
                    if m.any():
                        lens.append(int(m.sum()))
                        mask |= m
                if not lens:
                    continue
                scenes = Counter(g.sos[mask])
                peak = scenes.most_common(1)[0][0]
                det = r.get("details") or []
                rows.append(
                    dict(
                        p=name[:4],
                        uid=u.id,
                        n_det=len(det),
                        types=Counter(d.get("type") for d in det),
                        who=sum(d.get("type") == "who" for d in det),
                        B_occ=float(np.mean(lens)),
                        n_occ=len(lens),
                        occ=r.get("occurrence"),
                        mode=r.get("mode"),
                        ext=r.get("extent"),
                        flag=len(alts) > 1,
                        w_top=float(top.get("w", 0)),
                        hit=None if gs is None else int(peak == gs),
                    )
                )
    return rows


def spans_summary(run):
    c, units = Counter(), 0
    for name in D.development():
        p = D.OUT / "llm" / run / f"{name}.json"
        if not p.exists():
            continue
        for u in json.loads(p.read_text())["units"]:
            if "spans" not in u:
                continue
            units += 1
            for t in {s.get("talk") for s in u["spans"]}:
                c[t] += 1
            if u.get("hedge"):
                c["hedge:" + str(u["hedge"])] += 1
    return {k: round(v / max(units, 1), 3) for k, v in sorted(c.items())} | {
        "units": units
    }


def detail_tests(rows):
    nd = np.array([r["n_det"] for r in rows], float)
    lb = np.log10(np.maximum([r["B_occ"] for r in rows], 1))
    coded = [r for r in rows if r["hit"] is not None]
    out = dict(
        references=len(rows),
        median_details=float(np.median(nd)),
        rho_details_breadth=round(float(spearmanr(nd, lb).statistic), 3),
        type_share={
            t: round(sum(r["types"][t] for r in rows) / max(nd.sum(), 1), 3)
            for t in [
                "who",
                "action",
                "place",
                "time",
                "object",
                "dialogue",
                "perceptual",
            ]
        },
    )
    by = {}
    for k in [0, 1, 2, 3]:
        sel = [
            r["hit"] for r in coded if (r["n_det"] == k if k < 3 else r["n_det"] >= 3)
        ]
        by[f"{k}{'+' if k == 3 else ''}"] = [
            round(float(np.mean(sel)), 3) if sel else None,
            len(sel),
        ]
    out["agreement_by_details"] = by
    # breadth per occurrence vs number of occurrences: repetition kept apart from breadth?
    no = np.array([r["n_occ"] for r in rows])
    out["rho_breadth_per_occurrence_vs_occurrences"] = round(
        float(spearmanr(lb, no).statistic), 3
    )
    out["occurrence_share"] = dict(Counter(r["occ"] for r in rows))
    # within-participant partial: details vs hit controlling breadth (logistic would be nicer;
    # report stratified by breadth tercile)
    q = np.quantile([np.log10(max(r["B_occ"], 1)) for r in coded], [1 / 3, 2 / 3])
    strata = {}
    for name, lo, hi in [("narrow", -1, q[0]), ("mid", q[0], q[1]), ("broad", q[1], 9)]:
        s = [r for r in coded if lo < np.log10(max(r["B_occ"], 1)) <= hi]
        few = [r["hit"] for r in s if r["n_det"] <= 1]
        many = [r["hit"] for r in s if r["n_det"] >= 2]
        strata[name] = dict(
            few_details=round(float(np.mean(few)), 3) if few else None,
            many_details=round(float(np.mean(many)), 3) if many else None,
            n=(len(few), len(many)),
        )
    out["agreement_by_details_within_breadth_tercile"] = strata
    return out


def manipulation(rows_orig, rows_manip):
    """Paired by unit: aggregate each unit's references."""

    def per_unit(rows):
        d = {}
        for r in rows:
            x = d.setdefault(r["uid"], dict(who=0, n_det=0, flag=0, B=[], hit=[]))
            x["who"] += r["who"]
            x["n_det"] += r["n_det"]
            x["flag"] = max(x["flag"], int(r["flag"]))
            x["B"].append(np.log10(max(r["B_occ"], 1)))
            if r["hit"] is not None:
                x["hit"].append(r["hit"])
        return d

    a, b = per_unit(rows_orig), per_unit(rows_manip)
    keys = [k for k in b if k in a]

    def mean(f, src):
        v = [f(src[k]) for k in keys]
        v = [x for x in v if x is not None]
        return round(float(np.mean(v)), 3) if v else None

    out = dict(units=len(keys))
    for lab, f in [
        ("who_details", lambda x: x["who"]),
        ("details", lambda x: x["n_det"]),
        ("competing_flag", lambda x: x["flag"]),
        (
            "log_breadth_per_occurrence",
            lambda x: float(np.mean(x["B"])) if x["B"] else None,
        ),
        ("agreement", lambda x: float(np.mean(x["hit"])) if x["hit"] else None),
    ]:
        out[lab] = [mean(f, a), mean(f, b)]
    return out


def main():
    g = FilmGrid()
    res = {}
    v2like = convert(V3)
    s = evaluate("v2", v2like, "it5-v3-a")
    res["yardstick"] = {k: round(v, 3) for k, v in s["participant_mean"].items()}
    res["yardstick_ci"] = s["participant_boot95"]
    res["spans"] = spans_summary(V3)
    rows = ref_rows(V3, g)
    res["details"] = detail_tests(rows)
    if (D.OUT / "llm" / NAMES).exists():
        res["names_manipulation"] = manipulation(rows, ref_rows(NAMES, g))
    out = D.OUT / "runs" / "it5-v3-a" / "analysis5.json"
    out.write_text(json.dumps(res, indent=1, default=str))
    print(json.dumps(res, indent=1, default=str))


if __name__ == "__main__":
    main()
