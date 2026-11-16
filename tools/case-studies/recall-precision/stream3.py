"""Iteration 3 evaluator for prompt-v2 streams (and the v3 analyst stream on the same yardstick).

Fixes from critique round 2 (ML): midrank AUROC; confidence is the FITTED probability
(residual and floor included); alternatives and residual are tempered separately on wide grids;
participant means use only participants with human coding; per-fold parameters are not averaged;
stream differences are paired by participant. (Linguistics/visualization): uncertainty is entropy
over competing hypotheses only; breadth is the weighted film extent within a hypothesis; talk
category and hedge are separate channels; words map to the reference whose anchor covers them.

Model for one reference (declared):
  pi_k  = w_k^alpha / sum_j w_j^alpha                 (competing hypotheses, tempered)
  u     = min(1, eps + gamma * residual)               (mass that may be anywhere)
  q(t)  = (1 - u) * sum_k pi_k * Uniform(union of hypothesis k's intervals)(t) + u / T
Measures: U = H(pi) bits; B = sum_k pi_k |hypothesis k| seconds; conf = max_scene q(scene).
Word projection: a word takes the reference whose anchor words cover it; uncovered words take
the nearest anchored reference in the same unit (level-holding); words in non-episodic units are
not applicable (NaN), never zero.
"""

from __future__ import annotations

import argparse
import json
import re

import numpy as np

import data as D
from precision1 import auroc, ece, entropy_bits
from stream_eval import FilmGrid, gold_labels, word_index

ALPHA = np.array([0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0])
GAMMA = np.array([0.0, 0.25, 0.5, 1.0, 1.5, 2.0])
EPS = np.geomspace(1e-5, 0.3, 10)
NORM = re.compile(r"[^a-z0-9']+")


def toks(s):
    return [t for t in NORM.sub(" ", s.lower()).split() if t]


class Ref:
    __slots__ = ("form", "masks", "w", "resid", "span")

    def __init__(self, form, masks, w, resid, span):
        self.form, self.masks, self.w, self.resid, self.span = (
            form,
            masks,
            w,
            resid,
            span,
        )


def refs_v2(rec, unit_text, g):
    """Returns (talk, hedge, [Ref]) for one unit; span = token index range in the unit text."""
    if not rec or "talk" not in rec:
        return "missing", False, []
    talk = rec.get("talk", "episodic")
    hedge = bool(rec.get("hedge"))
    ut = toks(unit_text)
    out = []
    for r in rec.get("refs") or []:
        masks, ws = [], []
        for alt in r.get("alts") or []:
            try:
                w = float(alt.get("w", 0))
                m = np.zeros(g.T, bool)
                for r1, r2 in alt.get("iv") or []:
                    m |= g.range_mask(r1, r2)
            except (TypeError, ValueError, AttributeError):
                continue
            if m.any() and w > 0:
                masks.append(m)
                ws.append(w)
        if not masks:
            continue
        ws = np.array(ws)
        if ws.sum() > 1:
            ws = ws / ws.sum()
        at = toks(r.get("words") or "")
        span = None
        for i in range(len(ut) - len(at) + 1) if at else []:
            if ut[i : i + len(at)] == at:
                span = (i, i + len(at))
                break
        out.append(
            Ref(r.get("form", "specific"), masks, ws, max(0.0, 1 - ws.sum()), span)
        )
    if talk == "episodic" and not out:
        talk = "episodic-unlocalized"
    return talk, hedge, out


def refs_v3(rec, g):
    """Analyst stream: one reference whose hypotheses are the v3 scene alternatives."""
    if rec is None:
        return "missing", False, []
    if rec.get("function") not in (None, "narration") and not rec.get("claims"):
        return "commentary", False, []
    alts = rec.get("alts") or (
        [[rec["narrative"], 1.0]] if rec.get("narrative") else []
    )
    cited = [r for c in rec.get("claims") or [] for r in (c.get("rows") or [])]
    masks, ws = [], []
    for s, w in alts:
        try:
            s, w = int(s), float(w)
        except (TypeError, ValueError):
            continue
        if s not in g.scene_secs or w <= 0:
            continue
        m = np.zeros(g.T, bool)
        for rid in cited:
            if isinstance(rid, int) and rid in g.secs and g.rows[rid - 1].scene == s:
                a, b = g.secs[rid]
                m[a:b] = True
        if not m.any():
            m[g.scene_secs[s]] = True
        masks.append(m)
        ws.append(w)
    if not masks:
        return "commentary", False, []
    ws = np.array(ws)
    ws = ws / ws.sum() if ws.sum() > 1 else ws
    return "episodic", False, [Ref("specific", masks, ws, max(0.0, 1 - ws.sum()), None)]


def ref_quantities(ref, g, alpha, gamma, eps):
    pi = ref.w**alpha
    pi = pi / pi.sum()
    u = min(1.0, eps + gamma * ref.resid)
    sizes = np.array([m.sum() for m in ref.masks], float)
    sm = np.zeros(len(g.uniq))
    for m, p, n in zip(ref.masks, pi, sizes):
        sm += p * np.array([(m & (g.sos == s)).sum() for s in g.uniq]) / n
    frac = np.array([len(g.scene_secs[s]) for s in g.uniq]) / g.T
    qs = (1 - u) * sm + u * frac
    return dict(
        U=entropy_bits(pi),
        B=float((pi * sizes).sum()),
        qs=qs,
        conf=qs.max(),
        peak=g.uniq[qs.argmax()],
        form=ref.form,
        n_alt=len(pi),
    )


def word_refs(unit_text, refs, nwords):
    """Map each of the unit's CSV words to a reference index (declared rule; see module doc)."""
    nt = max(len(toks(unit_text)), 1)
    tok_ref = np.full(nt, -1)
    for k, r in enumerate(refs):
        if r.span:
            tok_ref[r.span[0] : r.span[1]] = k
    if (tok_ref < 0).all():
        tok_ref[:] = 0
    anchored = np.where(tok_ref >= 0)[0]
    for i in np.where(tok_ref < 0)[0]:
        tok_ref[i] = tok_ref[anchored[np.argmin(np.abs(anchored - i))]]
    if nwords == 0:
        return np.array([], int)
    idx = np.minimum((np.arange(nwords) * nt / nwords).astype(int), nt - 1)
    return tok_ref[idx]


def load(stream, run, name, g):
    units = D.common.units(name)
    if stream == "v3":
        p = D.common.OUT / run / f"{name}.json"
        recs = {u["id"]: u for u in json.loads(p.read_text())["units"]}
        return units, [refs_v3(recs.get(u.id), g) for u in units]
    p = D.OUT / "llm" / run / f"{name}.json"
    if not p.exists():
        return None
    recs = {u["id"]: u for u in json.loads(p.read_text())["units"]}
    return units, [refs_v2(recs.get(u.id), u.text, g) for u in units]


def per_word(units, parsed, k, nwords):
    """For each word: (unit index, ref index or -1)."""
    wr = np.full(nwords, -1)
    for u in range(len(units)):
        sel = np.where(k == u)[0]
        talk, _, refs = parsed[u]
        if not refs or len(sel) == 0:
            continue
        wr[sel] = word_refs(units[u].text, refs, len(sel))
    return wr


def loglik_table(P, g):
    """participant -> array[alpha, gamma, eps] of summed log2 q(gold scene) over coded words."""
    out = {}
    frac = {s: len(g.scene_secs[s]) / g.T for s in g.uniq}
    for p in P:
        on = np.where((p["gold"] > 0) & (p["k"] >= 0))[0]
        if len(on) == 0:
            continue
        L = np.zeros((len(ALPHA), len(GAMMA), len(EPS)))
        # group words by (unit, ref) to avoid recomputation
        keys = {}
        for i in on:
            keys.setdefault((p["k"][i], p["wr"][i]), []).append(p["gold"][i])
        for (u, r), golds in keys.items():
            refs = p["parsed"][u][2]
            gi = np.searchsorted(g.uniq, golds)
            if r < 0 or not refs:
                L += np.log2([frac[x] for x in golds]).sum()
                continue
            ref = refs[r]
            sizes = np.array([m.sum() for m in ref.masks], float)
            scene_share = np.stack(
                [
                    np.array([(m & (g.sos == s)).sum() for s in g.uniq]) / n
                    for m, n in zip(ref.masks, sizes)
                ]
            )  # hyps x scenes
            fr = np.array([frac[x] for x in golds])
            for a, al in enumerate(ALPHA):
                pi = ref.w**al
                pi = pi / pi.sum()
                sg = (pi @ scene_share)[gi]
                for c, ga in enumerate(GAMMA):
                    for e, ep in enumerate(EPS):
                        uu = min(1.0, ep + ga * ref.resid)
                        L[a, c, e] += np.log2(
                            np.maximum((1 - uu) * sg + uu * fr, 1e-12)
                        ).sum()
        out[p["name"]] = (L, len(on))
    return out


def evaluate(stream, run, label):
    g = FilmGrid()
    P = []
    for name in D.development():
        ld = load(stream, run, name, g)
        if ld is None:
            continue
        units, parsed = ld
        words, t, tn, k = word_index(name, units)
        P.append(
            dict(
                name=name,
                units=units,
                parsed=parsed,
                t=t,
                tn=tn,
                k=k,
                wr=per_word(units, parsed, k, len(words)),
                gold=gold_labels(name, t),
            )
        )
    LL = loglik_table(P, g)

    def fit(ex):
        s = sum(v[0] for n, v in LL.items() if n != ex) if len(LL) > 1 else LL[ex][0]
        i = np.unravel_index(np.argmax(s), s.shape)
        # gamma = 0 is a natural boundary (no extra residual mass), not a grid limit
        edge = i[0] in (0, len(ALPHA) - 1) or i[1] == len(GAMMA) - 1 or i[2] in (0, len(EPS) - 1)
        return ALPHA[i[0]], GAMMA[i[1]], EPS[i[2]], edge

    out = D.OUT / "runs" / label
    out.mkdir(parents=True, exist_ok=True)
    summ = dict(label=label, stream=stream, run=run, participants={})
    for p in P:
        al, ga, ep, edge = fit(p["name"])
        n = len(p["t"])
        W = {x: np.full(n, np.nan) for x in ("U", "B", "conf", "logq_gold")}
        peak = np.zeros(n, int)
        form = np.array([""] * n, dtype=object)
        talk = np.array([""] * n, dtype=object)
        hedge = np.zeros(n, bool)
        cache = {}
        for i in range(n):
            u, r = p["k"][i], p["wr"][i]
            if u < 0:
                continue
            tk, hd, refs = p["parsed"][u]
            talk[i], hedge[i] = tk, hd
            if r < 0 or not refs:
                continue
            if (u, r) not in cache:
                cache[(u, r)] = ref_quantities(refs[r], g, al, ga, ep)
            q = cache[(u, r)]
            W["U"][i], W["B"][i], W["conf"][i] = q["U"], q["B"], q["conf"]
            peak[i], form[i] = q["peak"], q["form"]
            if p["gold"][i] > 0:
                W["logq_gold"][i] = np.log2(
                    max(q["qs"][np.searchsorted(g.uniq, p["gold"][i])], 1e-12)
                )
        gd = p["gold"]
        on = gd > 0
        app = ~np.isnan(W["conf"])
        unit_talk = [x[0] for x in p["parsed"]]
        unit_forms = [r.form for x in p["parsed"] for r in x[2]]
        rec = dict(
            alpha=float(al),
            gamma=float(ga),
            eps=float(ep),
            grid_edge=bool(edge),
            units=len(p["units"]),
            talk={
                c: unit_talk.count(c) / len(unit_talk) for c in sorted(set(unit_talk))
            },
            forms={
                c: unit_forms.count(c) / max(len(unit_forms), 1)
                for c in sorted(set(unit_forms))
            },
            refs_per_episodic_unit=float(
                np.mean([len(x[2]) for x in p["parsed"] if x[2]] or [0])
            ),
            anchored_share=float(
                np.mean([r.span is not None for x in p["parsed"] for r in x[2]] or [0])
            ),
            share_U_positive=float(np.nanmean(W["U"] > 0)),
            median_B=float(np.nanmedian(W["B"])),
        )
        if on.any():
            ev = on & app
            hit = (peak == gd) & app
            fallback = on & ~app
            frac = np.array([len(g.scene_secs[s]) / g.T for s in g.uniq])
            lq = W["logq_gold"].copy()
            lq[fallback] = np.log2(frac[np.searchsorted(g.uniq, gd[fallback])])
            rec |= dict(
                coverage=float(ev.sum() / on.sum()),
                scene_acc=float(hit[on].mean()),
                logloss_on_bits=float(-lq[on].mean()),
                mean_conf=float(W["conf"][ev].mean()),
                ece_top=ece(W["conf"][ev], hit[ev]),
                auroc_conf_hit=auroc(W["conf"][ev & hit], W["conf"][ev & ~hit]),
                auroc_lowU_hit=auroc(-W["U"][ev & hit], -W["U"][ev & ~hit]),
                auroc_lowB_hit=auroc(-W["B"][ev & hit], -W["B"][ev & ~hit]),
            )
        summ["participants"][p["name"]] = rec
        np.savez_compressed(
            out / f"{p['name']}.npz",
            t=p["t"],
            tn=p["tn"],
            gold=gd,
            peak=peak,
            k=p["k"],
            wr=p["wr"],
            form=form.astype(str),
            talk=talk.astype(str),
            hedge=hedge,
            **W,
        )
    coded = {k: v for k, v in summ["participants"].items() if "scene_acc" in v}
    keys = [
        "scene_acc",
        "logloss_on_bits",
        "mean_conf",
        "ece_top",
        "auroc_conf_hit",
        "auroc_lowU_hit",
        "auroc_lowB_hit",
        "coverage",
        "share_U_positive",
        "median_B",
        "anchored_share",
        "refs_per_episodic_unit",
    ]
    summ["participant_mean"] = {
        k: float(np.nanmean([v[k] for v in coded.values()])) for k in keys
    }
    summ["participant_boot95"] = boot({k: [v[k] for v in coded.values()] for k in keys})
    summ["grid_edge_folds"] = int(
        sum(v["grid_edge"] for v in summ["participants"].values())
    )
    (out / "summary.json").write_text(json.dumps(summ, indent=1))
    return summ


def boot(cols, n=4000, seed=11):
    """Percentile bootstrap over participants (n ~ 10: intervals are approximate and narrow)."""
    rng = np.random.default_rng(seed)
    out = {}
    for k, v in cols.items():
        v = np.array(v, float)
        v = v[~np.isnan(v)]
        if len(v) < 3:
            continue
        bs = v[rng.integers(0, len(v), (n, len(v)))].mean(1)
        out[k] = [float(np.quantile(bs, 0.025)), float(np.quantile(bs, 0.975))]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("stream", choices=["v2", "v3"])
    ap.add_argument("run")
    ap.add_argument("--label")
    a = ap.parse_args()
    s = evaluate(a.stream, a.run, a.label or f"it3-{a.stream}-{a.run}")
    for n, r in s["participants"].items():
        print(
            n.split("_")[0],
            json.dumps(
                {
                    k: (round(v, 3) if isinstance(v, float) else v)
                    for k, v in r.items()
                    if k not in ("talk", "forms")
                }
            ),
        )
    print(
        "MEAN", json.dumps({k: round(v, 3) for k, v in s["participant_mean"].items()})
    )
    print(
        "CI",
        json.dumps(
            {k: [round(x, 3) for x in v] for k, v in s["participant_boot95"].items()}
        ),
    )
    print("grid-edge folds:", s["grid_edge_folds"])


if __name__ == "__main__":
    main()
