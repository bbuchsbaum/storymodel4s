"""Iteration 1: content-local temporal specificity with critic fixes.

Changes from iteration 0 (critique 2026-11-16, linguistics / ML / visualization):
- peak scene is the argmax of the scene distribution (was: peak second; biased to short rows);
- explicit off-film state (pseudo-row with fitted score c), fitted jointly with tau by
  leave-one-participant-out likelihood of the human coding, where uncoded recall time is
  "off-film" (an approximation: the coders' gaps are not certified as non-episodic);
- hubness correction: each row's mean similarity to a FOREIGN reference corpus (FilmFestival
  recall, reference half) is subtracted, so content-independent "hub" rows lose their pull;
- row-text variants (with or without the People/Location template) and row vs duration prior;
- causal (backward) window option;
- measures: H_scene (bits, conditional on on-film mass), contiguous shortest window holding
  50% / 80% of on-film mass (seconds; the film axis concatenates part a and b), p_off. The
  within-scene term is kept but is NOT a reported measure until it passes the dialogue control;
- nulls: different-film recall (FilmFestival, null half) and circular shift of film rows;
- positive control for within-scene localization: recall windows that quote film dialogue
  (shared 4-gram with a row's Whisper text) have a known row.
"""

from __future__ import annotations

import argparse
import csv
import glob
import json
import re
from dataclasses import dataclass

import numpy as np

import data as D
from encoder import Encoder

TAUS = np.geomspace(0.01, 0.12, 14)
CS = np.linspace(-0.05, 0.35, 17)  # off-film pseudo-score (after hub correction)
FF = D.DATA / "study" / "filmfestival" / "jl-onnx-dev"


# ---------------------------------------------------------------- film


@dataclass
class Film:
    rows: list
    emb: np.ndarray
    hub: np.ndarray
    alloc: np.ndarray
    logprior: np.ndarray
    scene_of_sec: np.ndarray
    scenes: np.ndarray
    uniq: np.ndarray
    seconds: int
    speech_tokens: list  # per row, lowercased dialogue tokens


def row_text(r, mode):
    if mode == "full":
        return r.text
    # description + dialogue only
    t = re.sub(r"People: .*?\.\s*", "", r.text)
    t = re.sub(r"Location: .*?\.\s*", "", t)
    return t.strip() or r.text


def build_film(enc, text_mode, prior, hub_windows):
    rows = D.film_rows(with_speech=True)
    raw = D.common.attach_speech(D.common.film_rows())
    speech = {r.id: " ".join(r.speech).lower() for r in raw}
    T = int(np.ceil(max(r.t1 for r in rows)))
    alloc = np.zeros((len(rows), T), dtype=np.float32)
    sos = np.zeros(T, dtype=np.int32)
    dur = np.zeros(len(rows))
    for j, r in enumerate(rows):
        a = int(np.floor(r.t0))
        b = max(int(np.ceil(r.t1)), a + 1)
        alloc[j, a:b] = 1.0 / (b - a)
        sos[a:b] = r.scene
        dur[j] = b - a
    emb = enc.encode([row_text(r, text_mode) for r in rows])
    hub = np.zeros(len(rows))
    if hub_windows is not None:
        hub = (enc.encode(hub_windows) @ emb.T).mean(0)
    lp = (
        np.log(dur / dur.sum())
        if prior == "duration"
        else np.full(len(rows), -np.log(len(rows)))
    )
    scenes = np.array([r.scene for r in rows])
    toks = [re.findall(r"[a-z']+", speech.get(r.id, "")) for r in rows]
    return Film(rows, emb, hub, alloc, lp, sos, scenes, np.unique(sos), T, toks)


# ---------------------------------------------------------------- recall


def windows(tokens, W, mode):
    out = []
    for i in range(len(tokens)):
        lo, hi = (
            (i - W // 2, i + W // 2 + 1) if mode == "centered" else (i - W + 1, i + 1)
        )
        out.append(" ".join(tokens[max(0, lo) : hi]))
    return out


def foreign_streams():
    """FilmFestival recall as word streams; even-numbered subjects = hub reference, odd = null."""
    ref, null = [], []
    for f in sorted(glob.glob(str(FF / "recall-map-sub-*.tsv"))):
        k = int(re.search(r"sub-(\d+)", f).group(1))
        toks = []
        with open(f, newline="") as fh:
            for x in csv.DictReader(fh, delimiter="\t"):
                toks += x["recallText"].split()
        (ref if k % 2 == 0 else null).append(toks)
    return ref, null


def sample_windows(streams, W, mode, n, rng):
    allw = [w for s in streams for w in windows(s, W, mode)]
    idx = rng.choice(len(allw), size=min(n, len(allw)), replace=False)
    return [allw[i] for i in sorted(idx)]


# ---------------------------------------------------------------- model


def logits(sim, film, tau, c):
    """Returns (words x rows+1) log-probabilities; last column is off-film."""
    z = (sim - film.hub[None, :]) / tau + film.logprior[None, :]
    off = np.full((sim.shape[0], 1), c / tau + np.log(1.0))
    z = np.concatenate([z, off], 1)
    z -= z.max(1, keepdims=True)
    lse = np.log(np.exp(z).sum(1, keepdims=True))
    return z - lse


def label_loglik(sim, gold, film, tau, c):
    """gold: scene id, or -1 for off-film (uncoded), 0 for unusable (no clock)."""
    m = gold != 0
    lp = logits(sim[m], film, tau, c)
    g = gold[m]
    p = np.exp(lp[:, :-1])
    on = g > 0
    mass = np.empty(len(g))
    mass[on] = (p[on] * (film.scenes[None, :] == g[on, None])).sum(1)
    mass[~on] = np.exp(lp[~on, -1])
    return float(np.log2(np.maximum(mass, 1e-12)).sum()), int(m.sum())


def concentration(lp, film, chunk=512):
    p = np.exp(lp)
    p_off = p[:, -1]
    prow = p[:, :-1] / np.maximum(p[:, :-1].sum(1, keepdims=True), 1e-12)
    psec = prow @ film.alloc
    pscene = np.stack([psec[:, film.scene_of_sec == s].sum(1) for s in film.uniq], 1)
    Hs = entropy_bits(pscene)
    H = entropy_bits(psec)
    w50 = np.empty(len(p))
    w80 = np.empty(len(p))
    for a in range(0, len(p), chunk):
        C = np.concatenate(
            [np.zeros((min(chunk, len(p) - a), 1)), np.cumsum(psec[a : a + chunk], 1)],
            1,
        )
        w50[a : a + chunk] = shortest_window(C, 0.5)
        w80[a : a + chunk] = shortest_window(C, 0.8)
    return dict(
        p_off=p_off,
        H=H,
        H_scene=Hs,
        H_within=H - Hs,
        w50=w50,
        w80=w80,
        top_scene_p=pscene.max(1),
        peak_scene=film.uniq[pscene.argmax(1)],
        pscene=pscene,
        psec=psec,
    )


def shortest_window(C, q):
    """C: words x (T+1) cumulative mass. Smallest integer width w with max window mass >= q."""
    n, T1 = C.shape
    lo = np.ones(n, dtype=int)
    hi = np.full(n, T1 - 1, dtype=int)
    while (lo < hi).any():
        mid = (lo + hi) // 2
        best = np.zeros(n)
        for w in np.unique(mid):
            s = mid == w
            best[s] = (C[s][:, w:] - C[s][:, :-w]).max(1)
        ok = best >= q - 1e-9
        hi = np.where(ok, mid, hi)
        lo = np.where(ok, lo, mid + 1)
    return lo.astype(float)


def entropy_bits(p):
    q = np.where(p > 0, p, 1.0)
    return -(p * np.log2(q)).sum(-1)


def auroc(pos, neg):
    """P(score_pos > score_neg) + 0.5 P(tie), via midranks (ties share their average rank)."""
    from scipy.stats import rankdata

    a, b = np.asarray(pos, float), np.asarray(neg, float)
    if len(a) == 0 or len(b) == 0:
        return float("nan")
    r = rankdata(np.concatenate([a, b]))
    return float((r[: len(a)].sum() - len(a) * (len(a) + 1) / 2) / (len(a) * len(b)))


# ---------------------------------------------------------------- dialogue anchors


STOP = set(
    "the a an and to of i you it is that in he she was his her what on for with be".split()
)


def dialogue_anchors(words, film, n=4):
    """Indices of recall words ending an n-gram that occurs in exactly one row's dialogue,
    with at least two non-stopword tokens. Returns {word index: row index}."""
    grams = {}
    for j, toks in enumerate(film.speech_tokens):
        for k in range(len(toks) - n + 1):
            g = tuple(toks[k : k + n])
            grams.setdefault(g, set()).add(j)
    rt = [re.sub(r"[^a-z']", "", w.text.lower()) for w in words]
    out = {}
    for i in range(n - 1, len(rt)):
        g = tuple(rt[i - n + 1 : i + 1])
        if sum(t not in STOP for t in g) < 2:
            continue
        rows = grams.get(g)
        if rows and len(rows) == 1:
            out[i] = next(iter(rows))
    return out


# ---------------------------------------------------------------- main


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--window", type=int, default=15)
    ap.add_argument("--mode", choices=["centered", "causal"], default="centered")
    ap.add_argument("--text", choices=["full", "desc"], default="desc")
    ap.add_argument("--prior", choices=["row", "duration"], default="duration")
    ap.add_argument("--hub", choices=["none", "foreign"], default="foreign")
    ap.add_argument("--seed", type=int, default=20261116)
    ap.add_argument("--label", default=None)
    a = ap.parse_args()
    label = a.label or f"it1-{a.mode}{a.window}-{a.text}-{a.prior}-hub{a.hub}"
    out = D.OUT / "runs" / label
    out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(a.seed)

    enc = Encoder()
    ref, null = foreign_streams()
    hubw = (
        sample_windows(ref, a.window, a.mode, 3000, rng) if a.hub == "foreign" else None
    )
    film = build_film(enc, a.text, a.prior, hubw)
    nullw = sample_windows(null, a.window, a.mode, 3000, rng)
    sim_null = enc.encode(nullw) @ film.emb.T

    parts = []
    for name in D.development():
        words = D.recall_words(name)
        t = np.array([np.nan if w.princeton is None else w.princeton for w in words])
        tn = np.array([np.nan if w.openneuro is None else w.openneuro for w in words])
        remb = enc.encode(windows([w.text for w in words], a.window, a.mode))
        sim = remb @ film.emb.T
        iv = D.gold_intervals(name)
        if iv is None:
            gold = np.zeros(len(words), dtype=int)
        else:
            gold = np.array(
                [0 if np.isnan(x) else (D.gold_scene_at(iv, x) or -1) for x in t],
                dtype=int,
            )
            # words after the last coded interval: unusable, not off-film
            gold[(~np.isnan(t)) & (t > iv[-1][1] + D.TR)] = 0
        parts.append(
            dict(name=name, words=words, t=t, tn=tn, sim=sim, remb=remb, gold=gold)
        )

    # likelihood table: participant x tau x c
    graded = [p for p in parts if (p["gold"] != 0).any()]
    LL = {p["name"]: np.zeros((len(TAUS), len(CS))) for p in graded}
    NN = {}
    for p in graded:
        for i, tau in enumerate(TAUS):
            for j, c in enumerate(CS):
                LL[p["name"]][i, j], NN[p["name"]] = label_loglik(
                    p["sim"], p["gold"], film, tau, c
                )

    def fit(exclude):
        s = sum(LL[k] for k in LL if k != exclude)
        n = sum(NN[k] for k in NN if k != exclude)
        i, j = np.unravel_index(np.argmax(s), s.shape)
        return TAUS[i], CS[j], float(s[i, j] / n)

    summary = dict(label=label, args=vars(a), participants={}, grid_edges={})
    shift_rows = len(film.rows) // 2
    for p in parts:
        tau, c, _ = fit(p["name"])
        lp = logits(p["sim"], film, tau, c)
        cc = concentration(lp, film)
        g = p["gold"]
        on, off = g > 0, g == -1
        rec = dict(
            tau=float(tau),
            c=float(c),
            words=len(p["words"]),
            on_words=int(on.sum()),
            off_words=int(off.sum()),
        )
        rec |= dict(
            median_H_scene=float(np.median(cc["H_scene"])),
            median_w50=float(np.median(cc["w50"])),
            median_w80=float(np.median(cc["w80"])),
            mean_p_off=float(cc["p_off"].mean()),
        )
        if on.any():
            hit = cc["peak_scene"] == g
            gidx = np.searchsorted(film.uniq, np.where(on, g, film.uniq[0]))
            gm = cc["pscene"][np.arange(len(g)), gidx]
            # circular-shift null: rows' embeddings rolled along the film
            film_shift = Film(
                **{
                    **film.__dict__,
                    "emb": np.roll(film.emb, shift_rows, 0),
                    "hub": np.roll(film.hub, shift_rows),
                }
            )
            sims = p["remb"] @ film_shift.emb.T
            cs = concentration(logits(sims, film_shift, tau, c), film)
            rec |= dict(
                scene_acc=float(hit[on].mean()),
                scene_acc_shift_null=float((cs["peak_scene"] == g)[on].mean()),
                gold_scene_mass=float(gm[on].mean()),
                logloss_on_bits=float(-np.log2(np.maximum(gm[on], 1e-12)).mean()),
                auroc_lowHscene_hit=auroc(
                    -cc["H_scene"][on & hit], -cc["H_scene"][on & ~hit]
                ),
                auroc_topP_hit=auroc(
                    cc["top_scene_p"][on & hit], cc["top_scene_p"][on & ~hit]
                ),
                auroc_lowW50_hit=auroc(-cc["w50"][on & hit], -cc["w50"][on & ~hit]),
                auroc_poff_offfilm=auroc(cc["p_off"][off], cc["p_off"][on]),
                ece_top=ece(cc["top_scene_p"][on], hit[on]),
            )
            # dialogue positive control (within-scene): only anchors whose peak scene is right
            anc = dialogue_anchors(p["words"], film)
            rows_t = [(r.t0, r.t1) for r in film.rows]
            dists, base, wins = [], [], []
            for i, j in anc.items():
                t0, t1 = rows_t[j]
                ps = cc["psec"][i]
                sc = film.rows[j].scene
                insc = film.scene_of_sec == sc
                cond = ps * insc
                if cond.sum() <= 0:
                    continue
                cond = cond / cond.sum()
                within = np.arange(film.seconds)
                inrow = (within >= np.floor(t0)) & (
                    within < max(np.ceil(t1), np.floor(t0) + 1)
                )
                dists.append(float(cond[inrow].sum()))
                base.append(float(inrow.sum() / insc.sum()))
                wins.append(cc["peak_scene"][i] == sc)
            rec |= dict(
                dialogue_anchors=len(dists),
                anchor_row_mass_within_scene=float(np.mean(dists)) if dists else None,
                anchor_row_uniform_baseline=float(np.mean(base)) if base else None,
                anchor_peak_scene_hit=float(np.mean(wins)) if wins else None,
            )
        summary["participants"][p["name"]] = rec
        np.savez_compressed(
            out / f"{p['name']}.npz",
            t=p["t"],
            tn=p["tn"],
            gold=g,
            **{k: v for k, v in cc.items() if k not in ("pscene", "psec")},
            psec=cc["psec"].astype(np.float16),
            tau=tau,
            c=c,
        )
        write_tr(out, p, cc)
        print(
            p["name"],
            json.dumps(
                {
                    k: (round(v, 3) if isinstance(v, float) else v)
                    for k, v in rec.items()
                }
            ),
            flush=True,
        )

    # foreign-narrative null, using the pooled fit
    tau, c, _ = fit(None)
    cn = concentration(logits(sim_null, film, tau, c), film)
    summary["foreign_null"] = dict(
        windows=len(nullw),
        tau=float(tau),
        c=float(c),
        median_H_scene=float(np.median(cn["H_scene"])),
        median_w50=float(np.median(cn["w50"])),
        median_w80=float(np.median(cn["w80"])),
        mean_p_off=float(cn["p_off"].mean()),
        q05_H_scene=float(np.quantile(cn["H_scene"], 0.05)),
        q05_w50=float(np.quantile(cn["w50"], 0.05)),
    )
    np.savez_compressed(
        out / "foreign_null.npz", **{k: v for k, v in cn.items() if k not in ("psec",)}
    )
    summary["grid_edges"] = dict(
        tau=[float(TAUS[0]), float(TAUS[-1])], c=[float(CS[0]), float(CS[-1])]
    )
    summary["participant_mean"] = means(summary["participants"])
    summary["participant_boot95"] = boot(summary["participants"], rng)
    (out / "summary.json").write_text(json.dumps(summary, indent=1))
    print("NULL", json.dumps(summary["foreign_null"]))
    print(
        "MEAN",
        json.dumps({k: round(v, 3) for k, v in summary["participant_mean"].items()}),
    )


def ece(conf, hit, bins=10):
    q = np.clip((conf * bins).astype(int), 0, bins - 1)
    e = 0.0
    for b in range(bins):
        s = q == b
        if s.any():
            e += s.mean() * abs(conf[s].mean() - hit[s].mean())
    return float(e)


def means(P):
    keys = sorted({k for r in P.values() for k, v in r.items() if isinstance(v, float)})
    return {
        k: float(np.mean([r[k] for r in P.values() if r.get(k) is not None]))
        for k in keys
    }


def boot(P, rng, n=2000):
    names = [k for k, r in P.items() if "scene_acc" in r]
    out = {}
    for k in [
        "scene_acc",
        "scene_acc_shift_null",
        "auroc_lowHscene_hit",
        "auroc_topP_hit",
        "auroc_lowW50_hit",
        "auroc_poff_offfilm",
        "ece_top",
        "median_H_scene",
        "median_w50",
        "anchor_row_mass_within_scene",
        "anchor_row_uniform_baseline",
    ]:
        v = np.array([P[x][k] for x in names if P[x].get(k) is not None])
        if len(v) == 0:
            continue
        bs = [v[rng.integers(0, len(v), len(v))].mean() for _ in range(n)]
        out[k] = [float(np.quantile(bs, 0.025)), float(np.quantile(bs, 0.975))]
    return out


def write_tr(out, p, cc):
    """Level-holding projection to the recall TR grid (OpenNeuro clock); declared rule: the mean
    of word-level values whose onsets fall in [k*TR, (k+1)*TR). This is a mean of word
    entropies, not the entropy of a pooled distribution. No onset -> missing, never zero."""
    ok = ~np.isnan(p["tn"])
    k = np.floor(p["tn"][ok] / D.TR).astype(int)
    n = int(k.max()) + 1 if len(k) else 0
    idx = np.where(ok)[0]
    cols = ["H_scene", "w50", "w80", "p_off"]
    lines = ["tr\tonset_s\tn_words\t" + "\t".join(cols) + "\tstatus"]
    for tr in range(n):
        sel = idx[k == tr]
        if len(sel) == 0:
            lines.append(
                f"{tr}\t{tr * D.TR:.1f}\t0\t"
                + "\t".join("NA" for _ in cols)
                + "\tno-speech"
            )
        else:
            vals = "\t".join(f"{float(np.mean(cc[c][sel])):.4f}" for c in cols)
            lines.append(f"{tr}\t{tr * D.TR:.1f}\t{len(sel)}\t{vals}\tobserved")
    (out / f"{p['name']}.tr.tsv").write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
