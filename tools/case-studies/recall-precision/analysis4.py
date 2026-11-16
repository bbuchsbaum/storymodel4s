"""Iteration 4 analyses that need no new model calls (critique round 3).

1. Breadth validity: how much of log breadth is explained by the film moment (human-coded
   scene, and the referenced row grain) versus the speaker? One row per reference (not per word).
2. Uncertainty as a flag: agreement when competing hypotheses are present vs absent; flag
   reliability (kappa) and magnitude reliability among units where either sample is positive.
3. Paired context control on the same participants.
4. TR-level regressors with explicit level-holding (empty TR = missing; non-episodic TR keeps
   its indicator), canonical double-gamma HRF convolution as a preview projection, correlation
   and VIF per participant. HRF is a downstream recipe, recorded here only to check collinearity.
"""

from __future__ import annotations

import json

import numpy as np
from scipy.stats import gamma as gamma_dist

import data as D
from stream3 import load
from stream_eval import FilmGrid

LOCAL, NOCTX, A, B = (
    "it3-v2-llm-local-v2-a",
    "it3-v2-llm-local-v2-noctx",
    "llm-local-v2-a",
    "llm-local-v2-b",
)


def npz(label, name):
    return np.load(D.OUT / "runs" / label / f"{name}.npz", allow_pickle=True)


def eta2(y, groups):
    """Share of variance explained by group means (one-way), and adjusted for group count."""
    y = np.asarray(y, float)
    gm = y.mean()
    ss_t = ((y - gm) ** 2).sum()
    ss_b = 0.0
    labs = np.unique(groups)
    for g in labs:
        s = y[groups == g]
        ss_b += len(s) * (s.mean() - gm) ** 2
    k, n = len(labs), len(y)
    adj = 1 - (1 - ss_b / ss_t) * (n - 1) / max(n - k, 1)
    return float(ss_b / ss_t), float(adj), int(k)


def breadth_validity(g):
    rows = []
    row_dur = {r.id: r.t1 - r.t0 for r in g.rows}
    for name in D.development():
        units, parsed = load("v2", A, name, g)
        z = npz(LOCAL, name)
        for i in range(len(z["t"])):
            pass
        seen = set()
        for i in range(len(z["t"])):
            key = (int(z["k"][i]), int(z["wr"][i]))
            if key in seen or np.isnan(z["B"][i]) or z["gold"][i] <= 0:
                continue
            seen.add(key)
            ref = parsed[key[0]][2][key[1]]
            top = int(np.argmax(ref.w))
            sec = np.where(ref.masks[top])[0]
            mid_row = next((r.id for r in g.rows if r.t0 <= sec.mean() < r.t1), None)
            rows.append(
                dict(
                    p=name[:4],
                    scene=int(z["gold"][i]),
                    logB=np.log10(max(z["B"][i], 1)),
                    form=ref.form,
                    grain=np.log10(max(row_dur.get(mid_row, 1), 1)),
                    n_iv=int(
                        np.diff(np.r_[0, ref.masks[top].astype(int)]).clip(0).sum()
                    ),
                )
            )
    y = np.array([r["logB"] for r in rows])
    out = dict(references=len(rows))
    for key in ["scene", "p", "form"]:
        e, adj, k = eta2(y, np.array([r[key] for r in rows]))
        out[f"eta2_{key}"] = [round(e, 3), round(adj, 3), k]
    grain = np.array([r["grain"] for r in rows])
    out["rho_logB_rowgrain"] = round(float(np.corrcoef(y, grain)[0, 1]), 3)
    spec = np.array([r["form"] == "specific" for r in rows])
    out["rho_logB_rowgrain_specific"] = round(
        float(np.corrcoef(y[spec], grain[spec])[0, 1]), 3
    )
    out["share_B_at_or_below_row_grain_specific"] = round(
        float(np.mean((y <= grain + 0.05)[spec])), 3
    )
    out["median_B_s_by_form"] = {
        f: round(float(10 ** np.median(y[[r["form"] == f for r in rows]])), 1)
        for f in sorted({r["form"] for r in rows})
    }
    return out


def u_flag():
    res = dict()
    hit_pos, hit_zero = [], []
    for name in D.development():
        z = npz(LOCAL, name)
        on = (z["gold"] > 0) & ~np.isnan(z["U"])
        if not on.any():
            continue
        hit = z["peak"] == z["gold"]
        hit_pos.append(hit[on & (z["U"] > 0)].mean())
        hit_zero.append(hit[on & (z["U"] == 0)].mean())
    res["agreement_when_competing_hypotheses"] = round(float(np.mean(hit_pos)), 3)
    res["agreement_when_single_hypothesis"] = round(float(np.mean(hit_zero)), 3)
    return res


def paired_noctx():
    out = {}
    for name in D.development():
        p = D.OUT / "runs" / NOCTX / f"{name}.npz"
        if not p.exists():
            continue
        a, b = npz(LOCAL, name), np.load(p, allow_pickle=True)
        on = a["gold"] > 0
        out[name[:4]] = dict(
            with_context=round(float((a["peak"] == a["gold"])[on].mean()), 3),
            target_only=round(float((b["peak"] == b["gold"])[on].mean()), 3),
            U_pos_with=round(float(np.nanmean(a["U"][on] > 0)), 3),
            U_pos_without=round(float(np.nanmean(b["U"][on] > 0)), 3),
        )
    return out


def hrf(tr=1.5, length=32.0):
    t = np.arange(0, length, tr)
    h = gamma_dist.pdf(t, 6) - gamma_dist.pdf(t, 16) / 6.0
    return h / h.sum()


def regressors(name):
    """Level-holding TR table on the OpenNeuro clock: value = mean over word onsets in the TR;
    empty TR = missing; non-episodic words count toward the talk indicator only."""
    z = npz(LOCAL, name)
    tn = z["tn"]
    ok = ~np.isnan(tn)
    k = np.floor(tn[ok] / D.TR).astype(int)
    n = int(k.max()) + 1
    words = np.bincount(k, minlength=n).astype(float)

    unit, ref = z["k"][ok], z["wr"][ok]

    def lvl(v):
        """Onset-point projection (ADR 0031 draft §3): mean over the DISTINCT references sampled
        in the bin, each counted once however many of its words fall there."""
        v = v[ok]
        out = np.full(n, np.nan)
        seen = {}
        for b, u, r, x in zip(k, unit, ref, v):
            if np.isnan(x):
                continue
            seen.setdefault(b, {})[(u, r)] = x
        for b, d in seen.items():
            out[b] = float(np.mean(list(d.values())))
        return out

    talk = z["talk"][ok]
    nonep = np.bincount(
        k,
        weights=np.isin(talk, ["metamemory", "commentary", "fragment"]).astype(float),
        minlength=n,
    ) / np.maximum(words, 1)
    nonep[words == 0] = np.nan
    # per-bin ledger (ADR 0031 draft section 3): counts, never one exclusive state
    nonep_set = ["metamemory", "commentary", "fragment"]
    n_refs = np.zeros(n)
    n_na_units = np.zeros(n)
    n_uncovered = np.zeros(n)
    n_unassigned = np.zeros(n)
    refs_seen, na_seen = {}, {}
    Bv = z["B"][ok]
    for b_, u, r, tk, bv in zip(k, unit, ref, talk, Bv):
        if u < 0:
            n_unassigned[b_] += 1
        elif tk in nonep_set:
            na_seen.setdefault(b_, set()).add(u)
        elif r < 0 or np.isnan(bv):
            n_uncovered[b_] += 1
        else:
            refs_seen.setdefault(b_, set()).add((u, r))
    for b_, v in refs_seen.items():
        n_refs[b_] = len(v)
    for b_, v in na_seen.items():
        n_na_units[b_] = len(v)
    status = np.where(words == 0, "no_sample", np.where(n_refs > 0, "observed",
                      np.where(n_na_units > 0, "only_not_applicable", "only_uncovered_or_unassigned")))
    return dict(
        n_refs=n_refs,
        n_not_applicable_units=n_na_units,
        n_uncovered_words=n_uncovered,
        n_unassigned_words=n_unassigned,
        status=status,
        missing_timing_words=int((~ok).sum()),
        words=words,
        logB=lvl(np.log10(np.maximum(z["B"], 1))),
        Uflag=lvl((z["U"] > 0).astype(float) * np.where(np.isnan(z["U"]), np.nan, 1)),
        conf=lvl(z["conf"]),
        hedge=lvl(z["hedge"].astype(float)),
        nonepisodic=nonep,
    )


def convolved_collinearity():
    h = hrf()
    names = ["logB", "Uflag", "conf", "hedge", "nonepisodic", "words"]
    per = []
    for name in D.development():
        R = regressors(name)
        X = []
        for c in names:
            v = R[c].copy()
            # missing -> participant mean for the convolution preview only (declared), then z-score
            v[np.isnan(v)] = np.nanmean(v)
            x = np.convolve(v - v.mean(), h)[: len(v)][20:]
            X.append((x - x.mean()) / (x.std() + 1e-12))
        X = np.nan_to_num(np.array(X))
        live = (
            X.std(1) > 1e-9
        )  # a constant series (e.g. no hedges) is excluded for that participant
        C = np.full((len(names), len(names)), np.nan)
        C[np.ix_(live, live)] = np.corrcoef(X[live])
        vif = np.full(len(names), np.nan)
        vif[live] = np.diag(np.linalg.pinv(C[np.ix_(live, live)]))
        per.append((C, vif))
    Cm = np.nanmedian([c for c, _ in per], 0)
    V = np.nanmedian([v for _, v in per], 0)
    Vmax = np.nanmax([v for _, v in per], 0)
    return dict(
        names=names,
        median_rho=Cm.round(3).tolist(),
        median_vif=V.round(2).tolist(),
        max_vif=Vmax.round(2).tolist(),
    )


def export_tsv():
    """Per-participant TR-grid regressor file (recall clock, OpenNeuro, TR 1.5 s) + JSON sidecar."""
    out = D.OUT / "export"
    out.mkdir(exist_ok=True)
    cols = ["words", "n_refs", "n_not_applicable_units", "n_uncovered_words", "n_unassigned_words",
            "logB", "Uflag", "hedge", "nonepisodic"]
    counts = {"words", "n_refs", "n_not_applicable_units", "n_uncovered_words", "n_unassigned_words"}
    missing_timing = {}
    for name in D.development():
        R = regressors(name)
        n = len(R["words"])
        missing_timing[name[:4]] = R["missing_timing_words"]
        lines = ["onset\tduration\tstatus\t" + "\t".join(cols)]
        for i in range(n):
            vals = []
            for c in cols:
                v = R[c][i]
                vals.append(
                    "n/a"
                    if np.isnan(v)
                    else (f"{int(v)}" if c in counts else f"{v:.4f}")
                )
            lines.append(f"{i * D.TR:.1f}\t{D.TR}\t{R['status'][i]}\t" + "\t".join(vals))
        (out / f"{name[:4]}_desc-recallspecificity_timeseries.tsv").write_text(
            "\n".join(lines) + "\n"
        )
    side = {
        "Description": "Recall-time series from the storymodel4s recall-specificity case study, iteration 3 "
        "(content-local LLM, prompt v2, sample A). Development participants only.",
        "Clock": "OpenNeuro word-onset column (scan-relative seconds); no lag or HRF applied.",
        "Binning": "Half-open 1.5 s bins [k*1.5, (k+1)*1.5); value = mean of word-level values whose onset "
        "falls in the bin, each distinct reference counted once (onset-point projection, ADR 0031 "
        "draft section 3); a word maps to the reference whose anchor covers it (explicit token "
        "alignment). Timing is onset-only, so a bin with no onset is 'no_sample', not 'no speech'.",
        "status": "observed (at least one reference sampled) | only_not_applicable (only talk not about "
        "the film) | only_uncovered_or_unassigned | no_sample.",
        "n_refs": "Distinct references sampled in the bin.",
        "n_not_applicable_units": "Distinct units of talk not about the film (metamemory, commentary, "
        "fragment) sampled in the bin.",
        "n_uncovered_words": "Words of film-describing units that no reference anchor covers.",
        "n_unassigned_words": "Words outside every recall unit.",
        "MissingTimingWords": "per participant, words without an onset (not placed in any bin): "
        + json.dumps(missing_timing),
        "words": "Number of word onsets in the bin (speech-rate nuisance).",
        "logB": "log10 breadth (s): film extent of the referenced hypothesis, weighted; n/a where not about "
        "the film. Lower bound set by annotation row grain.",
        "Uflag": "Share of words whose reference carries competing hypotheses (0/1 per word). Magnitude of "
        "uncertainty is not reliable (ICC 0.01 on tempered weights); the flag is (kappa 0.69).",
        "hedge": "Share of words in units the model marked as speaker-hedged.",
        "nonepisodic": "Share of words in units labelled metamemory, commentary or fragment.",
        "NotIncluded": "Top-scene confidence (collinear with Uflag after HRF convolution).",
    }
    (out / "desc-recallspecificity_timeseries.json").write_text(
        json.dumps(side, indent=1)
    )
    return str(out)


def main():
    g = FilmGrid()
    res = dict(
        breadth=breadth_validity(g),
        uncertainty_flag=u_flag(),
        context_paired=paired_noctx(),
        convolved=convolved_collinearity(),
        export=export_tsv(),
    )
    (D.OUT / "runs" / LOCAL / "analysis4.json").write_text(json.dumps(res, indent=1))
    print(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
