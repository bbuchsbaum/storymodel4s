"""Evaluate a unit-level stream on the iteration-1 yardstick.

Streams:
- llm-local: content-local LLM ranges (llm_local.py output);
- v3: the cached LLM mapper (reads the whole recall: an analyst-knowledge stream). Its
  self-reported scene alternatives are spread over each scene's seconds, or over the rows its
  claims cite inside that scene when there are any.

Self-reported weights are not probabilities. A tempering exponent alpha and a uniform floor eps
are fitted by leave-one-participant-out likelihood of the human scene coding:
q(film second) = (1 - eps) * mix_alpha(ranges, residual) + eps * uniform, where the residual
("cannot localize") is spread uniformly ONLY inside the likelihood; reported measures are
conditional on localized mass, and the residual share is reported as p_unloc.

Word projection (declared): each word takes the distribution of the unit whose onset interval
[unit onset, next unit onset) contains the word's onset (level-holding). Words of non-episodic
units are missing ("not-applicable"), never zero. Words before the first unit are missing.
"""

from __future__ import annotations

import argparse
import json

import numpy as np

import data as D
from precision1 import auroc, ece, entropy_bits, shortest_window

ALPHAS = np.array([0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0])
EPS = np.array([0.001, 0.003, 0.01, 0.03, 0.1, 0.2])


class FilmGrid:
    def __init__(self):
        rows = D.film_rows(with_speech=False)
        self.rows = rows
        self.T = int(np.ceil(max(r.t1 for r in rows)))
        self.sos = np.zeros(self.T, dtype=int)
        self.secs = {}
        for r in rows:
            a = int(np.floor(r.t0))
            b = max(int(np.ceil(r.t1)), a + 1)
            self.sos[a:b] = r.scene
            self.secs[r.id] = (a, b)
        self.uniq = np.unique(self.sos)
        self.scene_secs = {s: np.where(self.sos == s)[0] for s in self.uniq}

    def range_mask(self, r1, r2):
        r1, r2 = sorted((int(r1), int(r2)))
        r1, r2 = max(r1, 1), min(r2, len(self.rows))
        m = np.zeros(self.T, bool)
        for rid in range(r1, r2 + 1):
            if rid in self.secs:
                a, b = self.secs[rid]
                m[a:b] = True
        return m


def hypotheses_llm(rec, g: FilmGrid):
    """List of (mask, weight, span_seconds) and kind for one llm-local unit."""
    if "ranges" not in rec:
        return None, "missing"
    if rec.get("kind") == "non_episodic" or not rec["ranges"]:
        return [], "non_episodic"
    hs = []
    for x in rec["ranges"]:
        try:
            r1, r2, w = x
            m = g.range_mask(r1, r2)
        except (TypeError, ValueError):
            continue
        if m.any() and float(w) > 0:
            hs.append((m, float(w)))
    return hs, rec.get("reference", "specific")


def hypotheses_v3(rec, g: FilmGrid):
    if rec is not None and rec.get("function") not in (None, "narration") and not rec.get("claims"):
        return [], "non_episodic"
    if rec is None or not rec.get("alts"):
        if rec is not None and rec.get("narrative"):
            alts = [[rec["narrative"], 1.0]]
        else:
            return [], "non_episodic"
    else:
        alts = rec["alts"]
    cited = [r for c in rec.get("claims", []) or [] for r in (c.get("rows") or [])]
    hs = []
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
        hs.append((m, w))
    return hs, "specific"


def unit_dist(hs, alpha, g: FilmGrid):
    """Tempered mixture over film seconds; returns (localized density, residual share)."""
    w = np.array([h[1] for h in hs], float)
    tot = w.sum()
    resid = max(0.0, 1.0 - tot) if tot <= 1 else 0.0
    if tot > 1:
        w = w / tot
    ws = np.append(w, resid)
    ws = np.where(ws > 0, ws, 0.0) ** alpha
    ws = ws / ws.sum()
    d = np.zeros(g.T)
    for (m, _), wk in zip(hs, ws[:-1]):
        d[m] += wk / m.sum()
    return d, float(ws[-1])


def load_stream(stream, run, name, g):
    units = D.common.units(name)
    if stream == "v3":
        p = D.common.OUT / run / f"{name}.json"
        recs = (
            {u["id"]: u for u in json.loads(p.read_text())["units"]}
            if p.exists()
            else {}
        )
        hyp = [hypotheses_v3(recs.get(u.id), g) for u in units]
    else:
        p = D.OUT / "llm" / run / f"{name}.json"
        if not p.exists():
            return None
        recs = {u["id"]: u for u in json.loads(p.read_text())["units"]}
        hyp = [hypotheses_llm(recs.get(u.id, {}), g) for u in units]
    return units, hyp


def word_index(name, units):
    words = D.recall_words(name)
    t = np.array([np.nan if w.princeton is None else w.princeton for w in words])
    tn = np.array([np.nan if w.openneuro is None else w.openneuro for w in words])
    on = np.array([u.onset for u in units])
    k = np.searchsorted(on, t, side="right") - 1
    k = np.where(np.isnan(t) | (k < 0), -1, k)
    return words, t, tn, k


def gold_labels(name, t):
    iv = D.gold_intervals(name)
    if iv is None:
        return np.zeros(len(t), int)
    g = np.array([0 if np.isnan(x) else (D.gold_scene_at(iv, x) or -1) for x in t], int)
    g[(~np.isnan(t)) & (t > iv[-1][1] + D.TR)] = 0
    return g


def unit_loglik(hyp, k, gold, g, alpha, eps):
    """Sum over words with a scene label of log2 q(gold scene)."""
    s = 0.0
    n = 0
    cache = {}
    for i in np.where((gold > 0) & (k >= 0))[0]:
        u = k[i]
        hs, kind = hyp[u]
        if u not in cache:
            if hs:
                d, r = unit_dist(hs, alpha, g)
                sm = np.array([d[g.scene_secs[sc]].sum() for sc in g.uniq])
                cache[u] = sm + r * np.array(
                    [len(g.scene_secs[sc]) / g.T for sc in g.uniq]
                )
            else:
                cache[u] = None
        sm = cache[u]
        idx = np.searchsorted(g.uniq, gold[i])
        frac = len(g.scene_secs[gold[i]]) / g.T
        q = frac if sm is None else (1 - eps) * sm[idx] + eps * frac
        s += np.log2(max(q, 1e-12))
        n += 1
    return s, n


def measures(hyp, k, alpha, g, nwords):
    keys = [
        "H_scene",
        "w50",
        "w80",
        "p_unloc",
        "top_scene_p",
        "breadth_s",
        "applicable",
    ]
    out = {x: np.full(nwords, np.nan) for x in keys}
    out["peak_scene"] = np.zeros(nwords, int)
    out["broad"] = np.zeros(nwords, bool)
    psec = np.zeros((nwords, g.T), np.float16)
    per_unit = {}
    for u in np.unique(k[k >= 0]):
        hs, kind = hyp[u]
        if not hs:
            per_unit[u] = None
            continue
        d, r = unit_dist(hs, alpha, g)
        loc = d / d.sum()
        sm = np.array([loc[g.scene_secs[sc]].sum() for sc in g.uniq])
        C = np.concatenate([[0.0], np.cumsum(loc)])[None, :]
        span = sum(m.sum() * w for m, w in hs) / sum(w for _, w in hs)
        per_unit[u] = dict(
            H_scene=entropy_bits(sm),
            w50=shortest_window(C, 0.5)[0],
            w80=shortest_window(C, 0.8)[0],
            p_unloc=r,
            top_scene_p=sm.max(),
            peak_scene=g.uniq[sm.argmax()],
            breadth_s=span,
            broad=kind == "broad",
            loc=loc,
        )
    for i in range(nwords):
        u = k[i]
        if u < 0 or per_unit.get(u) is None:
            out["applicable"][i] = 0 if u >= 0 else np.nan
            continue
        pu = per_unit[u]
        for x in ["H_scene", "w50", "w80", "p_unloc", "top_scene_p", "breadth_s"]:
            out[x][i] = pu[x]
        out["peak_scene"][i] = pu["peak_scene"]
        out["broad"][i] = pu["broad"]
        out["applicable"][i] = 1
        psec[i] = pu["loc"]
    out["psec"] = psec
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("stream", choices=["llm-local", "v3"])
    ap.add_argument("run")
    ap.add_argument("--label")
    a = ap.parse_args()
    label = a.label or f"it2-{a.stream}-{a.run}"
    out = D.OUT / "runs" / label
    out.mkdir(parents=True, exist_ok=True)
    g = FilmGrid()
    parts = []
    for name in D.development():
        ld = load_stream(a.stream, a.run, name, g)
        if ld is None:
            continue
        units, hyp = ld
        words, t, tn, k = word_index(name, units)
        parts.append(
            dict(
                name=name,
                units=units,
                hyp=hyp,
                words=words,
                t=t,
                tn=tn,
                k=k,
                gold=gold_labels(name, t),
            )
        )
    LL, NN = {}, {}
    for p in parts:
        if not (p["gold"] > 0).any():
            continue
        LL[p["name"]] = np.zeros((len(ALPHAS), len(EPS)))
        for i, al in enumerate(ALPHAS):
            for j, ep in enumerate(EPS):
                LL[p["name"]][i, j], NN[p["name"]] = unit_loglik(
                    p["hyp"], p["k"], p["gold"], g, al, ep
                )

    def fit(ex):
        s = sum(v for n, v in LL.items() if n != ex)
        if not hasattr(s, "shape"):  # single participant: in-sample fit, flagged in summary
            s = LL[ex]
            summary["in_sample_fit"] = True
        i, j = np.unravel_index(np.argmax(s), s.shape)
        return ALPHAS[i], EPS[j]

    summary = dict(label=label, stream=a.stream, run=a.run, participants={})
    rng = np.random.default_rng(7)
    for p in parts:
        al, ep = fit(p["name"])
        m = measures(p["hyp"], p["k"], al, g, len(p["words"]))
        gd = p["gold"]
        app = m["applicable"] == 1
        on = gd > 0
        rec = dict(
            alpha=float(al),
            eps=float(ep),
            words=len(p["words"]),
            units=len(p["units"]),
            unit_non_episodic=float(np.mean([not h for h, _ in p["hyp"]])),
            unit_broad=float(np.mean([kd == "broad" for _, kd in p["hyp"]])),
            median_H_scene=float(np.nanmedian(m["H_scene"])),
            median_w50=float(np.nanmedian(m["w50"])),
            median_w80=float(np.nanmedian(m["w80"])),
            mean_p_unloc=float(np.nanmean(m["p_unloc"])),
        )
        if on.any():
            hit = (m["peak_scene"] == gd) & app
            ll, n = (
                LL[p["name"]][list(ALPHAS).index(al), list(EPS).index(ep)],
                NN[p["name"]],
            )
            ev = on & app
            rec |= dict(
                coverage=float(ev.sum() / on.sum()),
                scene_acc=float(hit[on].mean()),
                scene_acc_applicable=float(hit[ev].mean()),
                logloss_on_bits=float(-ll / n),
                auroc_lowHscene_hit=auroc(
                    -m["H_scene"][ev & hit], -m["H_scene"][ev & ~hit]
                ),
                auroc_topP_hit=auroc(
                    m["top_scene_p"][ev & hit], m["top_scene_p"][ev & ~hit]
                ),
                auroc_lowW50_hit=auroc(-m["w50"][ev & hit], -m["w50"][ev & ~hit]),
                ece_top=ece(m["top_scene_p"][ev], hit[ev]),
                auroc_nonepisodic_offfilm=auroc(
                    (m["applicable"] == 0)[(gd == -1) | on].astype(float)[
                        (gd[(gd == -1) | on] == -1)
                    ],
                    (m["applicable"] == 0)[on].astype(float),
                ),
            )
        summary["participants"][p["name"]] = rec
        np.savez_compressed(
            out / f"{p['name']}.npz",
            t=p["t"],
            tn=p["tn"],
            gold=gd,
            **{x: v for x, v in m.items()},
        )
        print(
            p["name"],
            json.dumps(
                {
                    x: (round(v, 3) if isinstance(v, float) else v)
                    for x, v in rec.items()
                }
            ),
            flush=True,
        )
    from precision1 import boot, means

    fp = D.OUT / "llm" / a.run / "FOREIGN-filmfestival.json"
    if a.stream == "llm-local" and fp.exists():
        al, _ = fit(None)
        recs = json.loads(fp.read_text())["units"]
        rows = []
        for r in recs:
            hs, kind = hypotheses_llm(r, g)
            if hs is None:
                continue
            if not hs:
                rows.append(dict(applicable=0))
                continue
            d, res = unit_dist(hs, al, g)
            loc = d / d.sum()
            sm = np.array([loc[g.scene_secs[sc]].sum() for sc in g.uniq])
            C = np.concatenate([[0.0], np.cumsum(loc)])[None, :]
            rows.append(dict(applicable=1, H_scene=entropy_bits(sm), w50=shortest_window(C, 0.5)[0],
                             p_unloc=res, top_scene_p=sm.max(), broad=kind == "broad"))
        appl = [r for r in rows if r["applicable"]]
        arr = {k: np.array([r[k] for r in appl], float) for k in ("H_scene", "w50", "p_unloc", "top_scene_p")}
        np.savez_compressed(out / "foreign_null.npz", **arr)
        summary["foreign_null"] = dict(
            units=len(rows), non_episodic=float(1 - len(appl) / max(len(rows), 1)),
            broad=float(np.mean([r["broad"] for r in appl])) if appl else None,
            median_H_scene=float(np.median(arr["H_scene"])), median_w50=float(np.median(arr["w50"])),
            mean_p_unloc=float(arr["p_unloc"].mean()), mean_top_scene_p=float(arr["top_scene_p"].mean()))
        print("NULL", json.dumps(summary["foreign_null"]))

    summary["participant_mean"] = means(summary["participants"])
    summary["participant_boot95"] = boot(summary["participants"], rng)
    (out / "summary.json").write_text(json.dumps(summary, indent=1))
    print(
        "MEAN",
        json.dumps({x: round(v, 3) for x, v in summary["participant_mean"].items()}),
    )


if __name__ == "__main__":
    main()
