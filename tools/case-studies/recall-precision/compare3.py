"""Iteration 3: reliability, controls, paired stream differences and regressor collinearity.

Inputs are it3 run directories produced by stream3.py. Output: runs/<out>/compare.json.
- reliability: two independent samples (A, B: different shuffles and ids) of the same prompt;
  per-unit ICC(2,1) of U and log B on the first reference of units episodic in both, plus
  top-scene, talk and form agreement (response stability, not calibration);
- context control: target-only arm vs A on the same participants (paired);
- information gain: per coded word, log2 q_analyst(gold) - log2 q_local(gold), paired by
  participant (participant bootstrap);
- regressors: TR-level (OpenNeuro clock, 1.5 s) level-holding means of U, log B, conf,
  analyst conf, hedge and metamemory/commentary share, plus words per TR; Spearman matrix
  pooled over participants after within-participant rank transform.
"""

from __future__ import annotations

import argparse
import json

import numpy as np
from scipy.stats import rankdata, spearmanr

import data as D
from stream3 import load
from stream_eval import FilmGrid


def icc21(x, y):
    """Two-way random, absolute agreement, single measure (Shrout & Fleiss ICC(2,1))."""
    X = np.column_stack([x, y])
    n, k = X.shape
    gm = X.mean()
    msr = k * ((X.mean(1) - gm) ** 2).sum() / (n - 1)
    msc = n * ((X.mean(0) - gm) ** 2).sum() / (k - 1)
    sse = ((X - X.mean(1, keepdims=True) - X.mean(0, keepdims=True) + gm) ** 2).sum()
    mse = sse / ((n - 1) * (k - 1))
    return float((msr - mse) / (msr + (k - 1) * mse + k * (msc - mse) / n))


def unit_first_ref(parsed):
    out = []
    for talk, _, refs in parsed:
        if not refs:
            out.append((talk, None))
            continue
        r = refs[0]
        pi = r.w / r.w.sum()
        U = float(-(pi * np.log2(pi)).sum())
        B = float((pi * np.array([m.sum() for m in r.masks])).sum())
        top = int(np.argmax(pi))
        mid = np.where(r.masks[top])[0]
        out.append(
            (talk, dict(U=U, logB=np.log10(max(B, 1)), form=r.form, centre=mid.mean()))
        )
    return out


def reliability(run_a, run_b, g):
    U, Ub, B, Bb, same_c, talk_same, form_same = [], [], [], [], [], [], []
    for name in D.development():
        la, lb = load("v2", run_a, name, g), load("v2", run_b, name, g)
        if la is None or lb is None:
            continue
        for (ta, ra), (tb, rb) in zip(unit_first_ref(la[1]), unit_first_ref(lb[1])):
            talk_same.append(ta == tb)
            if ra and rb:
                U.append(ra["U"])
                Ub.append(rb["U"])
                B.append(ra["logB"])
                Bb.append(rb["logB"])
                same_c.append(abs(ra["centre"] - rb["centre"]) <= 30)
                form_same.append(ra["form"] == rb["form"])
    return dict(
        units_both_episodic=len(U),
        icc_U=icc21(np.array(U), np.array(Ub)),
        icc_logB=icc21(np.array(B), np.array(Bb)),
        top_hypothesis_within_30s=float(np.mean(same_c)),
        talk_agreement=float(np.mean(talk_same)),
        form_agreement=float(np.mean(form_same)),
    )


def npz(label, name):
    p = D.OUT / "runs" / label / f"{name}.npz"
    return np.load(p, allow_pickle=True) if p.exists() else None


def paired(lab_a, lab_b, key="logq_gold", rng=None):
    """Per participant mean of (a - b) over coded words where both are defined."""
    d = {}
    for name in D.development():
        a, b = npz(lab_a, name), npz(lab_b, name)
        if a is None or b is None:
            continue
        m = (a["gold"] > 0) & ~np.isnan(a[key]) & ~np.isnan(b[key])
        if m.sum() < 20:
            continue
        d[name] = float((a[key][m] - b[key][m]).mean())
    v = np.array(list(d.values()))
    rng = rng or np.random.default_rng(3)
    bs = (
        v[rng.integers(0, len(v), (4000, len(v)))].mean(1)
        if len(v) > 2
        else np.array([np.nan])
    )
    return dict(
        per_participant=d,
        mean=float(v.mean()) if len(v) else None,
        ci95=[float(np.quantile(bs, 0.025)), float(np.quantile(bs, 0.975))],
        positive=int((v > 0).sum()),
        n=len(v),
    )


def tr_table(lab_local, lab_analyst, name):
    a, b = npz(lab_local, name), npz(lab_analyst, name)
    if a is None or b is None:
        return None
    tn = a["tn"]
    ok = ~np.isnan(tn)
    k = np.floor(tn[ok] / D.TR).astype(int)
    n = k.max() + 1
    cols = {}

    def lvl(v):
        s = np.bincount(k, weights=np.nan_to_num(v[ok]), minlength=n)
        c = np.bincount(k, weights=(~np.isnan(v[ok])).astype(float), minlength=n)
        with np.errstate(invalid="ignore"):
            return np.where(c > 0, s / np.maximum(c, 1), np.nan)

    cols["U"] = lvl(a["U"])
    cols["logB"] = lvl(np.log10(np.maximum(a["B"], 1)))
    cols["conf"] = lvl(a["conf"])
    cols["analyst_conf"] = lvl(b["conf"])
    cols["hedge"] = lvl(a["hedge"].astype(float))
    talk = a["talk"]
    cols["nonepisodic"] = lvl(
        np.isin(talk, ["metamemory", "commentary", "fragment"]).astype(float)
    )
    cols["words"] = np.bincount(k, minlength=n).astype(float)
    cols["words"][cols["words"] == 0] = np.nan
    return cols


def collinearity(lab_local, lab_analyst):
    names = ["U", "logB", "conf", "analyst_conf", "hedge", "nonepisodic", "words"]
    pooled = {c: [] for c in names}
    for name in D.development():
        t = tr_table(lab_local, lab_analyst, name)
        if t is None:
            continue
        ok = np.all([~np.isnan(t[c]) for c in names], 0)
        for c in names:
            v = t[c][ok]
            pooled[c].append(
                rankdata(v) / len(v)
            )  # within-person midranks (ties share rank)
    M = np.zeros((len(names), len(names)))
    X = {c: np.concatenate(pooled[c]) for c in names}
    for i, a in enumerate(names):
        for j, b in enumerate(names):
            M[i, j] = spearmanr(X[a], X[b]).statistic
    return dict(names=names, rho=M.round(3).tolist(), n_TR=int(len(X["U"])))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--a", default="llm-local-v2-a")
    ap.add_argument("--b", default="llm-local-v2-b")
    ap.add_argument("--local", default="it3-v2-llm-local-v2-a")
    ap.add_argument("--analyst", default="it3-analyst")
    ap.add_argument("--noctx", default="it3-v2-llm-local-v2-noctx")
    a = ap.parse_args()
    g = FilmGrid()
    res = {}
    if (D.OUT / "llm" / a.b / "NN03_ANT_202231_final.json").exists():
        res["reliability"] = reliability(a.a, a.b, g)
    res["info_gain_analyst_minus_local_bits"] = paired(a.analyst, a.local)
    if (D.OUT / "runs" / a.noctx).exists():
        res["context_gain_local_minus_noctx_bits"] = paired(a.local, a.noctx)
    res["collinearity"] = collinearity(a.local, a.analyst)
    out = D.OUT / "runs" / a.local / "compare.json"
    out.write_text(json.dumps(res, indent=1))
    print(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
