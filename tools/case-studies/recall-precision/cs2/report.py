"""One schema for both corpora: per participant and pooled yardstick numbers from run outputs.

Reads only run outputs (rows.tsv, summary.json, unit_measures.json, manifest.json); no model
calls and no recall text. Pooled across samples = the row-wise mean of the samples' bits.

Usage (from tools/case-studies/recall-precision):
  python -m cs2.report OUT_JSON [old:]CORPUS=PRODUCER=DIR[,DIR] ...
"""

from __future__ import annotations

import csv
import json
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import numpy as np  # noqa: E402

from metrics import cluster_bootstrap, cohen_kappa, icc21, sign_flip_p  # noqa: E402

TERMS = ("film_bits", "work_bits", "scene_bits", "total_bits")
BASES = ("duration", "lopo", "gold_context_ceiling", "gold_context_fitted", "tfidf", "hmm_tfidf",
         "pass1_only")
SCHEMA = "storymodel4s.case-study-2.corpus-report/v1"
EVIDENCE = "development data, engineering numbers; validation = the frozen-six run"


def _num(x):
    if x is None or isinstance(x, float):
        return x
    return None if x in ("n/a", "") else float(x)


def _rows(d):
    out = defaultdict(dict)
    for r in csv.DictReader(open(Path(d) / "rows.tsv"), delimiter="\t"):
        out[r["forecast"]][(r["participant"], r["session"], int(r["row"]))] = r
    return out


def _pmean(rowmap, t):
    by = defaultdict(list)
    for (p, _, _), r in rowmap.items():
        v = _num(r[t])
        if v is not None:
            by[p].append(v)
    return {p: float(np.mean(v)) for p, v in by.items()}


def _ci(pm, stat):
    ci = cluster_bootstrap(pm, stat, n=4000, seed=20261116)
    return None if ci is None else [round(ci[0], 4), round(ci[1], 4)]


def _term(pm):
    if not pm:
        return None
    return dict(
        mean=round(float(np.mean(list(pm.values()))), 4),
        ci95=_ci(pm, lambda xs: float(np.mean(xs))),
        n_participants=len(pm),
    )


def _skill(pa, pb, ra=None, rb=None, t=None):
    """Skill 1 - LL/LL_ref, participant-weighted (ratio of participant means) with a cluster
    bootstrap CI, row-weighted (ratio of row sums on the rows both score), the exact paired
    sign-flip p over participants, and per-participant wins."""
    keys = sorted(set(pa) & set(pb))
    if not keys:
        return None
    cl = {p: (pa[p], pb[p]) for p in keys}
    st = lambda cs: 1 - sum(c[0] for c in cs) / sum(c[1] for c in cs)  # noqa: E731
    d = [pb[p] - pa[p] for p in keys]
    out = dict(skill=round(st(list(cl.values())), 4), ci95=_ci(cl, st),
               sign_flip_p=round(sign_flip_p(d), 4),
               wins=f"{sum(x > 0 for x in d)}/{len(d)}")
    if ra is not None:
        both = [k for k in ra if k in rb and _num(ra[k][t]) is not None
                and _num(rb[k][t]) is not None]
        num = sum(_num(ra[k][t]) for k in both)
        den = sum(_num(rb[k][t]) for k in both)
        out["row_weighted"] = round(1 - num / den, 4) if den else None
        out["rows"] = len(both)
    return out


def _avg(samples, name):
    """Row-wise mean of a forecast over samples (bits), where every sample has it."""
    if not all(name in s for s in samples):
        return None
    if len(samples) == 1:
        return samples[0][name]
    return {k: {t: (None if _num(r[t]) is None else
                    float(np.mean([_num(s[name][k][t]) for s in samples])))
                for t in TERMS} | {"work": r["work"], "top_work": r["top_work"],
                                   "scene_hit": r["scene_hit"]}
            for k, r in samples[0][name].items()}


def _shares(rowmap):
    by = defaultdict(lambda: dict(w=[], s=[]))
    for (p, _, _), r in rowmap.items():
        if r["work"]:
            by[p]["w"].append(r["top_work"] == r["work"])
        if r["scene_hit"] != "":
            by[p]["s"].append(int(r["scene_hit"]))
    return {
        p: dict(
            top_work=float(np.mean(v["w"])) if v["w"] else None,
            top_scene_in_gold_set=float(np.mean(v["s"])) if v["s"] else None,
            rows=len(v["w"]),
        )
        for p, v in by.items()
    }


def corpus_report(corpus, producer, dirs, work_applicable):
    samples = [_rows(d) for d in dirs]
    terms = TERMS if work_applicable else ("film_bits", "scene_bits", "total_bits")
    prod = (
        samples[0]["producer"]
        if len(samples) == 1
        else {
            k: {
                t: (
                    None
                    if _num(r[t]) is None
                    else float(np.mean([_num(s["producer"][k][t]) for s in samples]))
                )
                for t in TERMS
            }
            | {
                "work": r["work"],
                "top_work": r["top_work"],
                "scene_hit": r["scene_hit"],
            }
            for k, r in samples[0]["producer"].items()
        }
    )
    base = {b: _avg(samples, b) for b in BASES}
    base = {b: v for b, v in base.items() if v is not None}
    parts = sorted({p for p, _, _ in prod})
    per = {p: {} for p in parts}
    for t in terms:
        pm = _pmean(prod, t)
        for p in parts:
            per[p][t] = round(pm.get(p), 4) if p in pm else None
            for b in base:
                bm = _pmean(base[b], t)
                per[p][f"skill_{t}_vs_{b}"] = (
                    round(1 - pm[p] / bm[p], 4) if p in pm and bm.get(p) else None
                )
    sample_shares = [_shares(s["producer"]) for s in samples]
    for p in parts:
        per[p]["top_work"] = (
            None
            if not work_applicable
            else round(float(np.mean([s[p]["top_work"] for s in sample_shares])), 4)
        )
        per[p]["top_scene_in_gold_set"] = round(
            float(np.mean([s[p]["top_scene_in_gold_set"] for s in sample_shares])), 4
        )
    pooled = {t: _term(_pmean(prod, t)) for t in terms}
    pooled["skill"] = {
        b: {t: _skill(_pmean(prod, t), _pmean(base[b], t), prod, base[b], t) for t in terms}
        for b in base
    }
    pooled["baselines"] = {b: {t: _term(_pmean(base[b], t)) for t in terms} for b in base}
    # the cross-corpus headline: skill over the duration prior per term, no total; the film
    # term is left out where the corpus has no non-film gold rows (Sherlock)
    head_terms = [t for t in terms if t != "total_bits"
                  and (work_applicable or t != "film_bits")]
    headline = {t: pooled["skill"]["duration"][t] for t in head_terms}

    def share(key):
        vals = []
        for s in samples:
            rs = [
                r
                for r in s["producer"].values()
                if (r["work"] if key == "w" else r["scene_hit"] != "")
            ]
            vals.append(
                np.mean(
                    [
                        (r["top_work"] == r["work"])
                        if key == "w"
                        else int(r["scene_hit"])
                        for r in rs
                    ]
                )
            )
        return round(float(np.mean(vals)), 4)

    pooled["top_work"] = share("w") if work_applicable else None
    pooled["top_scene_in_gold_set"] = share("s")
    per_sample = []
    for d in dirs:
        sm = json.load(open(Path(d) / "summary.json"))
        mf = json.load(open(Path(d) / "manifest.json"))
        st = sm["producer_strata"]
        words = sm["words"]
        tot = defaultdict(int)
        for v in words.values():
            for k in (
                "words",
                "words_in_bins",
                "reference_words",
                "not_applicable_words",
                "uncovered_words",
                "non_addressed_words",
            ):
                tot[k] += v.get(k, 0)
        tot = dict(tot)
        tot["uncovered_share"] = round(tot["uncovered_words"] / tot["words_in_bins"], 4)
        conv = sm.get("ledger", {}).get("conversion") or sm.get("conversion")
        calls = sm.get("calls") or dict(
            model_calls=0, note="no model calls (recorded answers)"
        )
        per_sample.append(
            dict(
                dir=str(d),
                commit=mf["commit"],
                harness_dirty=mf["harness_dirty"],
                model_calls=mf.get("model_calls", 0),
                calls=calls,
                strata={
                    k: st.get(k)
                    for k in (
                        "work_bits_transition",
                        "work_bits_within",
                        "film_switch_f1",
                        "top_work_agreement",
                        "scene_hit_given_correct_work",
                        "scene_bits_by_speech",
                        "localizability",
                        "nonfilm_rows",
                        "nonwork_mass_by_gold_label",
                        "fallback_mass",
                        "typed_absence",
                        "rows",
                    )
                },
                words=tot,
                conversion=conv,
                rules=sm.get("rules"),
                epsilon=sm.get("epsilon"),
            )
        )
    rel = None
    if len(dirs) >= 2:
        U = [json.load(open(Path(d) / "unit_measures.json")) for d in dirs[:2]]
        keys = sorted(set(U[0]) & set(U[1]))
        both = [
            u
            for u in keys
            if U[0][u]["top_scene"] is not None and U[1][u]["top_scene"] is not None
        ]
        ex = [
            u
            for u in keys
            if U[0][u]["log10_extent"] is not None
            and U[1][u]["log10_extent"] is not None
        ]
        either = [u for u in keys if U[0][u]["top_scene"] is not None
                  or U[1][u]["top_scene"] is not None]
        agree = sum(U[0][u]["top_scene"] == U[1][u]["top_scene"] for u in both)
        rel = dict(
            units=len(keys),
            top_scene_agreement_both_located=dict(
                share=round(agree / len(both), 4), agree=agree, denominator=len(both),
                rule="units with a top scene in both samples"),
            top_scene_agreement_either_located=dict(
                share=round(agree / len(either), 4), agree=agree, denominator=len(either),
                rule="units with a top scene in at least one sample; one-located units count as "
                "disagreements"),
            alternatives_kappa=round(
                cohen_kappa(
                    [int(U[0][u]["alternatives"]) for u in keys],
                    [int(U[1][u]["alternatives"]) for u in keys],
                ),
                4,
            ),
            log10_extent_icc21=round(
                icc21(
                    [U[0][u]["log10_extent"] for u in ex],
                    [U[1][u]["log10_extent"] for u in ex],
                ),
                4,
            ),
            extent_units=len(ex),
        )
    return dict(
        schema=SCHEMA,
        evidence=EVIDENCE,
        corpus=corpus,
        producer=producer,
        samples=len(dirs),
        work_identity="scored" if work_applicable else "not applicable (one work)",
        not_applicable=(
            []
            if work_applicable
            else [
                "work term",
                "top work",
                "film-transition strata and film-switch F1 (one work)",
            ]
        ),
        headline=dict(note="compare corpora only by skill over the duration prior, per term",
                      skill_vs_duration=headline),
        pooled=pooled,
        participants=per,
        per_sample=per_sample,
        reliability=rel
        if rel
        else dict(note="one sample: between-sample reliability not " "computable"),
    )


def main(argv):
    out = Path(argv[0])
    rep = dict(
        schema="storymodel4s.case-study-2.summary-both/v1",
        evidence=EVIDENCE,
        comparability="the corpora use different producers unless both name the same "
        "producer; compare corpora only within one producer",
        corpora=[],
        old_rules=dict(note="the same producers rescored under the previous rules "
                            "(scorer-config old_rules), for comparison only", corpora=[]),
    )
    for spec in argv[1:]:
        old = spec.startswith("old:")
        corpus, producer, dirs = spec.removeprefix("old:").split("=", 2)
        r = corpus_report(corpus, producer, dirs.split(","), corpus != "sherlock")
        (rep["old_rules"]["corpora"] if old else rep["corpora"]).append(r)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(rep, indent=1) + "\n")
    print(out)


if __name__ == "__main__":
    main(sys.argv[1:])
