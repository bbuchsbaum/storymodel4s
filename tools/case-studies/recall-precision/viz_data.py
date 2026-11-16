"""Export compact JSON for the interactive progress page (numbers only; no recall or film text).

Shape (one object, embedded inline in the page):
  film: {seconds, partA, scenes: [[scene, t0, t1], ...]}
  participants: {NNxx: {
      coded: bool, dur: recall seconds,
      gold: [[r0, r1, scene], ...],                         human scene coding (recall s)
      units: [[r0, r1, talk, hedge, [[form, U, B, [[w, [[f0, f1], ...]], ...]], ...]], ...]
            per recall unit: recall span (s), talk code, hedge flag, references; each reference has
            form, between-hypothesis entropy U (bits), breadth B (s) and its hypotheses
            (tempered weight, film intervals in film seconds),
      tr: {t, conf, U, B, talk, words, analyst_conf, gold, peak}   1.5 s bins on the recall clock
  }}
  metrics: per iteration/stream summary rows (participant means, bootstrap CIs)
  calibration: per stream equal-count bins [conf, hit, n]
  compare: reliability, paired gains, collinearity (compare.json)
"""

from __future__ import annotations

import argparse
import json

import numpy as np

import data as D
from stream3 import ref_quantities  # noqa: F401  (documents the shared model)
from stream3 import load
from stream_eval import FilmGrid

TALK = {
    "episodic": 0,
    "episodic-unlocalized": 1,
    "fragment": 2,
    "commentary": 3,
    "metamemory": 4,
    "missing": 5,
}


def intervals(mask):
    idx = np.where(mask)[0]
    if len(idx) == 0:
        return []
    cuts = np.where(np.diff(idx) > 1)[0]
    starts = np.r_[idx[0], idx[cuts + 1]]
    ends = np.r_[idx[cuts], idx[-1]] + 1
    return [[int(a), int(b)] for a, b in zip(starts, ends)]


def r2(x):
    return (
        None
        if x is None or (isinstance(x, float) and np.isnan(x))
        else round(float(x), 3)
    )


def participant(name, local_run, local_label, analyst_label, g, params):
    units, parsed = load("v2", local_run, name, g)
    al, ga, ep = params
    words = D.recall_words(name)
    t = np.array([np.nan if w.princeton is None else w.princeton for w in words])
    on = np.array([u.onset for u in units])
    ends = np.r_[on[1:], np.nanmax(t) + 0.5]
    U = []
    for u, (talk, hedge, refs), r0, r1 in zip(units, parsed, on, ends):
        rr = []
        for ref in refs:
            pi = ref.w**al
            pi = pi / pi.sum()
            sizes = np.array([m.sum() for m in ref.masks], float)
            Uh = float(-(pi * np.log2(np.where(pi > 0, pi, 1))).sum())
            rr.append(
                [
                    ref.form,
                    round(Uh, 3),
                    round(float((pi * sizes).sum()), 1),
                    [[round(float(p), 3), intervals(m)] for p, m in zip(pi, ref.masks)],
                ]
            )
        U.append(
            [
                round(float(r0), 2),
                round(float(r1), 2),
                TALK.get(talk, 5),
                int(hedge),
                rr,
            ]
        )
    zl = np.load(D.OUT / "runs" / local_label / f"{name}.npz", allow_pickle=True)
    za = np.load(D.OUT / "runs" / analyst_label / f"{name}.npz", allow_pickle=True)
    tmax = np.nanmax(t)
    nb = int(np.ceil(tmax / D.TR)) + 1
    ok = ~np.isnan(t)
    k = np.floor(t[ok] / D.TR).astype(int)

    def lvl(v, fn=np.nanmean):
        out = []
        vv = v[ok]
        for b in range(nb):
            s = vv[k == b]
            s = s[~np.isnan(s)] if s.dtype.kind == "f" else s
            out.append(r2(fn(s)) if len(s) else None)
        return out

    talk_codes = np.array([TALK.get(x, 5) for x in zl["talk"]], float)
    talk_codes[np.array([x == "" for x in zl["talk"]])] = np.nan
    gold = zl["gold"].astype(float)
    gold[gold <= 0] = np.nan
    peak = zl["peak"].astype(float)
    peak[peak <= 0] = np.nan
    tr = dict(
        conf=lvl(zl["conf"]),
        U=lvl(zl["U"]),
        Uflag=lvl(np.where(np.isnan(zl["U"]), np.nan, (zl["U"] > 0).astype(float))),
        B=lvl(zl["B"]),
        talk=lvl(talk_codes, lambda s: float(np.max(s)) if len(s) else None),
        words=[int((k == b).sum()) for b in range(nb)],
        analyst_conf=lvl(za["conf"]),
        gold=lvl(
            gold,
            lambda s: float(np.bincount(s.astype(int)).argmax()) if len(s) else None,
        ),
        peak=lvl(
            peak,
            lambda s: float(np.bincount(s.astype(int)).argmax()) if len(s) else None,
        ),
    )
    iv = D.gold_intervals(name)
    tsv = D.OUT / "export" / f"{name[:4]}_desc-recallspecificity_timeseries.tsv"
    return dict(
        tsv=tsv.read_text() if tsv.exists() else None,
        coded=iv is not None,
        dur=round(float(tmax), 1),
        gold=[[lo, hi + D.TR, s] for lo, hi, s in (iv or [])],
        units=U,
        tr=tr,
    )


def calibration(label):
    conf, hit = [], []
    for name in D.development():
        z = np.load(D.OUT / "runs" / label / f"{name}.npz", allow_pickle=True)
        m = (z["gold"] > 0) & ~np.isnan(z["conf"])
        conf.append(z["conf"][m])
        hit.append((z["peak"] == z["gold"])[m])
    c, h = np.concatenate(conf), np.concatenate(hit)
    q = np.unique(np.quantile(c, np.linspace(0, 1, 9)))
    out = []
    for i, (lo, hi) in enumerate(zip(q[:-1], q[1:])):
        s = (c >= lo) & ((c <= hi) if i == len(q) - 2 else (c < hi))
        if s.sum():
            out.append([r2(c[s].mean()), r2(h[s].mean()), int(s.sum())])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--local-run", default="llm-local-v2-a")
    ap.add_argument("--local", default="it3-v2-llm-local-v2-a")
    ap.add_argument("--analyst", default="it3-analyst")
    a = ap.parse_args()
    g = FilmGrid()
    part_a = max(r.t1 for r in g.rows if r.part == "a")
    scenes = [
        [int(s), int(g.scene_secs[s][0]), int(g.scene_secs[s][-1]) + 1] for s in g.uniq
    ]
    summ = json.loads((D.OUT / "runs" / a.local / "summary.json").read_text())
    P = {}
    for name in D.development():
        pr = summ["participants"][name]
        P[name.split("_")[0]] = participant(
            name,
            a.local_run,
            a.local,
            a.analyst,
            g,
            (pr["alpha"], pr["gamma"], pr["eps"]),
        )
    metrics = {}
    for lab in [
        "emb-w15",
        "it1-centered15-desc-duration-hubforeign",
        "it2-llm-local-llm-local-v1",
        "it2-v3-v3-opus",
        a.local,
        a.analyst,
        "it5-v3-a",
    ]:
        p = D.OUT / "runs" / lab / "summary.json"
        if p.exists():
            s = json.loads(p.read_text())
            metrics[lab] = dict(
                mean=s.get("participant_mean"),
                ci=s.get("participant_boot95"),
                per={
                    k.split("_")[0]: v.get("scene_acc_covered", v.get("scene_acc"))
                    for k, v in s["participants"].items()
                },
            )
    a4 = D.OUT / "runs" / a.local / "analysis4.json"
    cmp = D.OUT / "runs" / a.local / "compare.json"
    doc = dict(
        film=dict(seconds=g.T, partA=part_a, scenes=scenes),
        participants=P,
        metrics=metrics,
        calibration={"local": calibration(a.local), "analyst": calibration(a.analyst)},
        compare=json.loads(cmp.read_text()) if cmp.exists() else None,
               analysis4=json.loads(a4.read_text()) if a4.exists() else None,
               sidecar=json.loads((D.OUT / "export" / "desc-recallspecificity_timeseries.json").read_text()),
    )
    s = json.dumps(doc, separators=(",", ":"))
    open(a.out, "w").write(s)
    print(a.out, len(s))


if __name__ == "__main__":
    main()
