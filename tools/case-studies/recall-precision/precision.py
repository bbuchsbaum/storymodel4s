"""RETIRED (iteration 0 record). Its local auroc gives tied scores arbitrary ranks; use metrics.py.

Recall-time temporal specificity over film time (case study 1, iteration loop).

For each recall word w_i (Princeton onset t_i) a centered context window of W words is
compared with every film annotation row. A softmax over rows with temperature tau gives a
distribution over row hypotheses; each row spreads its mass uniformly over its own seconds,
giving p_i(film second). Concentration is reported as:

- H_i: entropy over 1-s film bins (bits); effective width 2**H_i seconds;
- H_i = H(scene) + E_scene[H(t | scene)]: between-scene uncertainty plus within-scene spread
  (exact chain-rule decomposition, so the two add up);
- HPD50_i: seconds in the smallest set of film bins holding half the mass.

tau is fitted by leave-one-participant-out maximum likelihood of the human scene coding at
word onsets, so every participant's series uses a tau fitted without that participant.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass

import numpy as np

import data as D
from encoder import Encoder

TAUS = np.geomspace(0.005, 0.2, 24)


@dataclass
class Film:
    rows: list
    emb: np.ndarray
    alloc: np.ndarray  # rows x seconds, each row sums to 1
    scene_of_sec: np.ndarray
    scenes: np.ndarray
    seconds: int


def build_film(enc: Encoder, with_speech=True) -> Film:
    rows = D.film_rows(with_speech)
    T = int(np.ceil(max(r.t1 for r in rows)))
    alloc = np.zeros((len(rows), T), dtype=np.float32)
    scene_of_sec = np.zeros(T, dtype=np.int32)
    for j, r in enumerate(rows):
        a, b = int(np.floor(r.t0)), max(int(np.ceil(r.t1)), int(np.floor(r.t0)) + 1)
        alloc[j, a:b] = 1.0 / (b - a)
        scene_of_sec[a:b] = r.scene
    emb = enc.encode([r.text for r in rows])
    scenes = np.array([r.scene for r in rows])
    return Film(rows, emb, alloc, scene_of_sec, scenes, T)


def windows(words, W):
    toks = [w.text for w in words]
    h = W // 2
    return [" ".join(toks[max(0, i - h) : i + h + 1]) for i in range(len(toks))]


def entropy_bits(p, axis=-1):
    q = np.where(p > 0, p, 1.0)
    return -(p * np.log2(q)).sum(axis)


def concentration(prow: np.ndarray, film: Film):
    """prow: words x rows. Returns dict of per-word arrays."""
    psec = prow @ film.alloc  # words x seconds
    H = entropy_bits(psec)
    uniq = np.unique(film.scene_of_sec)
    pscene = np.stack([psec[:, film.scene_of_sec == s].sum(1) for s in uniq], 1)
    Hs = entropy_bits(pscene)
    srt = -np.sort(-psec, axis=1)
    hpd50 = (np.cumsum(srt, 1) < 0.5).sum(1) + 1
    peak = psec.argmax(1)
    return dict(
        H=H,
        H_scene=Hs,
        H_within=H - Hs,
        width=2.0**H,
        hpd50=hpd50.astype(float),
        map_t=peak.astype(float),
        map_scene=film.scene_of_sec[peak],
        pscene=pscene,
        scene_ids=uniq,
        psec=psec,
    )


def softmax_rows(sim, tau):
    z = sim / tau
    z -= z.max(1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(1, keepdims=True)


@dataclass
class Part:
    name: str
    words: list
    t: np.ndarray  # princeton onset (nan if missing)
    tn: np.ndarray  # openneuro onset
    sim: np.ndarray
    gold: np.ndarray  # scene or 0


def load_part(name, film, enc, W):
    words = D.recall_words(name)
    t = np.array([np.nan if w.princeton is None else w.princeton for w in words])
    tn = np.array([np.nan if w.openneuro is None else w.openneuro for w in words])
    emb = enc.encode(windows(words, W))
    sim = emb @ film.emb.T
    iv = D.gold_intervals(name)
    gold = np.array([D.gold_scene_at(iv, x) or 0 for x in t])
    return Part(name, words, t, tn, sim, gold)


def scene_loglik(part, film, tau):
    m = part.gold > 0
    if not m.any():
        return 0.0, 0
    p = softmax_rows(part.sim[m], tau)
    g = part.gold[m]
    mass = (p * (film.scenes[None, :] == g[:, None])).sum(1)
    return float(np.log2(np.maximum(mass, 1e-12)).sum()), int(m.sum())


def fit_tau(parts, film, exclude=None):
    best, arg = -np.inf, None
    for tau in TAUS:
        s = n = 0
        for p in parts:
            if p.name == exclude:
                continue
            ll, k = scene_loglik(p, film, tau)
            s += ll
            n += k
        if n and s / n > best:
            best, arg = s / n, tau
    return arg


def auroc(score_correct, score_wrong):
    """P(score of a correct word < score of a wrong word): low entropy should mean correct."""
    a, b = np.asarray(score_correct), np.asarray(score_wrong)
    if len(a) == 0 or len(b) == 0:
        return float("nan")
    allv = np.concatenate([a, b])
    ranks = allv.argsort().argsort().astype(float) + 1
    ra = ranks[: len(a)].sum()
    return float(1 - (ra - len(a) * (len(a) + 1) / 2) / (len(a) * len(b)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--window", type=int, default=15)
    ap.add_argument("--no-speech", action="store_true")
    ap.add_argument("--label", default=None)
    ap.add_argument("--null-seed", type=int, default=20261116)
    args = ap.parse_args()
    label = args.label or f"emb-w{args.window}" + (
        "-nospeech" if args.no_speech else ""
    )
    out = D.OUT / "runs" / label
    out.mkdir(parents=True, exist_ok=True)

    enc = Encoder()
    film = build_film(enc, with_speech=not args.no_speech)
    parts = [load_part(n, film, enc, args.window) for n in D.development()]
    graded = [p for p in parts if (p.gold > 0).any()]

    rng = np.random.default_rng(args.null_seed)
    summary = {
        "label": label,
        "window": args.window,
        "speech": not args.no_speech,
        "film_seconds": film.seconds,
        "rows": len(film.rows),
        "participants": {},
    }
    for p in parts:
        tau = fit_tau(graded, film, exclude=p.name)
        prow = softmax_rows(p.sim, tau)
        c = concentration(prow, film)
        # null: same participant's words in shuffled order, same tau
        perm = rng.permutation(len(p.words))
        shuffled = [p.words[i] for i in perm]
        sim0 = enc.encode(windows(shuffled, args.window)) @ film.emb.T
        c0 = concentration(softmax_rows(sim0, tau), film)

        m = p.gold > 0
        correct = c["map_scene"] == p.gold
        gidx = np.searchsorted(c["scene_ids"], np.where(m, p.gold, c["scene_ids"][0]))
        gold_mass = c["pscene"][np.arange(len(p.gold)), gidx]
        rec = {
            "tau": float(tau),
            "words": len(p.words),
            "graded_words": int(m.sum()),
            "median_H": float(np.median(c["H"])),
            "median_H_null": float(np.median(c0["H"])),
            "median_width_s": float(np.median(c["width"])),
            "median_width_null_s": float(np.median(c0["width"])),
            "median_hpd50_s": float(np.median(c["hpd50"])),
            "median_H_scene": float(np.median(c["H_scene"])),
            "median_H_within": float(np.median(c["H_within"])),
        }
        if m.any():
            rec |= {
                "scene_acc": float(correct[m].mean()),
                "gold_scene_logloss_bits": float(
                    -np.log2(np.maximum(gold_mass[m], 1e-12)).mean()
                ),
                "auroc_lowH_correct": auroc(c["H"][m & correct], c["H"][m & ~correct]),
            }
        summary["participants"][p.name] = rec
        np.savez_compressed(
            out / f"{p.name}.npz",
            t=p.t,
            tn=p.tn,
            gold=p.gold,
            H=c["H"],
            H_scene=c["H_scene"],
            H_within=c["H_within"],
            hpd50=c["hpd50"],
            map_t=c["map_t"],
            map_scene=c["map_scene"],
            gold_mass=gold_mass,
            H_null=c0["H"],
            psec=c["psec"].astype(np.float16),
            tau=tau,
        )
        write_tr(out, p, c)
        print(
            p.name,
            json.dumps(
                {k: round(v, 3) if isinstance(v, float) else v for k, v in rec.items()}
            ),
        )
    agg = {}
    for k in [
        "scene_acc",
        "gold_scene_logloss_bits",
        "auroc_lowH_correct",
        "median_H",
        "median_H_null",
        "median_width_s",
        "median_hpd50_s",
    ]:
        v = [r[k] for r in summary["participants"].values() if k in r]
        agg[k] = float(np.mean(v)) if v else None
    summary["participant_mean"] = agg
    (out / "summary.json").write_text(json.dumps(summary, indent=1))
    print("MEAN", json.dumps({k: round(v, 3) for k, v in agg.items() if v is not None}))


def write_tr(out, p, c):
    """Level-holding projection to the recall scan's TR grid (OpenNeuro clock).

    The value in a TR is the mean of the word-level values whose onsets fall in [k*TR, (k+1)*TR);
    a TR with no word onset is missing ("no-speech"), never zero. Word counts are kept.
    """
    ok = ~np.isnan(p.tn)
    k = np.floor(p.tn[ok] / D.TR).astype(int)
    n = int(k.max()) + 1 if len(k) else 0
    lines = [
        "tr\tonset_s\tn_words\tH_bits\tH_scene_bits\tH_within_bits\thpd50_s\tstatus"
    ]
    for tr in range(n):
        sel = np.where(ok)[0][k == tr]
        if len(sel) == 0:
            lines.append(f"{tr}\t{tr * D.TR:.1f}\t0\tNA\tNA\tNA\tNA\tno-speech")
            continue
        f = lambda a: f"{float(np.mean(a[sel])):.4f}"  # noqa: E731
        lines.append(
            f"{tr}\t{tr * D.TR:.1f}\t{len(sel)}\t{f(c['H'])}\t{f(c['H_scene'])}\t"
            f"{f(c['H_within'])}\t{f(c['hpd50'])}\tobserved"
        )
    (out / f"{p.name}.tr.tsv").write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
