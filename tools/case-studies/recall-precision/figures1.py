"""Iteration-1 figures (numbers only; no recall or film text is drawn).

Display rules adopted from the visualization critique:
- series are binned on the recall clock in 1.5 s bins (the TR), as level-holding means of word
  values; a bin with no word onset is a gap, never interpolated or zero;
- uncertainty is shown as the foreign-narrative null band (5-95%), not a median line;
- the within-scene entropy term is not drawn as signal (it is set by annotation granularity
  until the dialogue control says otherwise);
- width in seconds is the contiguous shortest window holding 50% of mass (W50), log axis.
"""

from __future__ import annotations

import argparse
import json

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402

import data as D  # noqa: E402

INK, MUTED, FAINT, ACCENT, HIT, MISS = (
    "#1f2328",
    "#7a7f87",
    "#d9dce1",
    "#b3001b",
    "#1d6f42",
    "#b3001b",
)
plt.rcParams.update(
    {
        "font.family": "serif",
        "font.size": 8,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "axes.edgecolor": MUTED,
        "axes.labelcolor": INK,
        "xtick.color": MUTED,
        "ytick.color": MUTED,
        "axes.linewidth": 0.5,
        "xtick.major.width": 0.5,
        "ytick.major.width": 0.5,
        "savefig.dpi": 200,
    }
)
BIN = D.TR


def binned(t, v, tmax=None):
    ok = ~np.isnan(t) & ~np.isnan(v)
    tmax = tmax or np.nanmax(t)
    edges = np.arange(0, tmax + BIN, BIN)
    k = np.clip(np.floor(t[ok] / BIN).astype(int), 0, len(edges) - 1)
    s = np.bincount(k, weights=v[ok], minlength=len(edges))
    n = np.bincount(k, minlength=len(edges))
    with np.errstate(invalid="ignore"):
        m = np.where(n > 0, s / np.maximum(n, 1), np.nan)
    return edges + BIN / 2, m, n


def scene_spans():
    sp = {}
    for r in D.film_rows(with_speech=False):
        lo, hi = sp.get(r.scene, (r.t0, r.t1))
        sp[r.scene] = (min(lo, r.t0), max(hi, r.t1))
    return sp


def null_band(run, key, min_n=30):
    """Different-film null 5/50/95%; None when too few null units were localized at all."""
    f = run / "foreign_null.npz"
    if not f.exists():
        return None
    z = np.load(f)
    if len(z[key]) < min_n:
        return None
    return np.quantile(z[key], [0.05, 0.5, 0.95])


def map_figure(run, name, out, summ):
    z = np.load(run / f"{name}.npz")
    t = z["t"]
    ok = ~np.isnan(t)
    psec = z["psec"].astype(np.float32)[ok]
    tt = t[ok]
    T = psec.shape[1]
    edges = np.arange(0, tt.max() + BIN, BIN)
    k = np.floor(tt / BIN).astype(int)
    img = np.zeros((T, len(edges)))
    cnt = np.bincount(k, minlength=len(edges))
    for i, kk in enumerate(k):
        img[:, kk] += psec[i]
    img = img / np.maximum(cnt, 1)
    sp = scene_spans()
    hub = psec.mean(0)

    fig = plt.figure(figsize=(7.4, 7.6))
    gs = fig.add_gridspec(
        4,
        2,
        height_ratios=[3.2, 0.9, 0.9, 0.35],
        width_ratios=[30, 1.4],
        hspace=0.08,
        wspace=0.03,
    )
    a = fig.add_subplot(gs[0, 0])
    h = fig.add_subplot(gs[0, 1], sharey=a)
    b = fig.add_subplot(gs[1, 0], sharex=a)
    c = fig.add_subplot(gs[2, 0], sharex=a)
    d = fig.add_subplot(gs[3, 0], sharex=a)

    vmax = np.quantile(img[:, cnt > 0], 0.998)
    shown = np.sqrt(np.clip(img / vmax, 0, 1))
    shown[:, cnt == 0] = np.nan
    cmap = plt.get_cmap("Greys").copy()
    cmap.set_bad("#f3f0ea")
    a.imshow(
        shown,
        aspect="auto",
        origin="lower",
        extent=[0, edges[-1] + BIN, 0, T],
        cmap=cmap,
        vmin=0,
        vmax=1,
        interpolation="nearest",
    )
    for lo, hi, s in D.gold_intervals(name) or []:
        if s in sp:
            f0, f1 = sp[s]
            a.add_patch(
                plt.Rectangle(
                    (lo, f0), hi - lo + BIN, f1 - f0, lw=0, fc=ACCENT, alpha=0.18
                )
            )
    part_a = max(r.t1 for r in D.film_rows(False) if r.part == "a")
    a.axhline(part_a, color=MUTED, lw=0.4, ls=":")
    a.text(2, part_a + 15, "part b", color=MUTED, fontsize=6.5)
    a.text(2, part_a - 55, "part a", color=MUTED, fontsize=6.5)
    a.set_ylabel("film time (s)")
    a.tick_params(labelbottom=False)
    r = summ["participants"][name]
    a.set_title(
        f"{name.split('_')[0]} — grey: model mass over film time at each moment of recall; "
        f"red band: human scene coding.  peak-scene agreement {r['scene_acc']:.0%}",
        loc="left",
        fontsize=7.5,
        color=INK,
    )

    h.plot(hub / hub.max(), np.arange(T) + 0.5, color=INK, lw=0.4)
    h.set_xticks([])
    h.tick_params(labelleft=False)
    h.spines["left"].set_visible(False)
    h.set_title("mean\nmass", fontsize=6, color=MUTED)

    x, m, n = binned(t, z["H_scene"])
    nb = null_band(run, "H_scene")
    if nb is not None:
        lo, med, hi = nb
        b.axhspan(lo, hi, color=FAINT, lw=0)
        b.text(x[-1], hi, " different-film null 5–95%", fontsize=6, color=MUTED, va="bottom")
        b.fill_between(x, m, lo, where=m < lo, color=INK, alpha=0.25, lw=0)
    b.plot(x, m, color=INK, lw=0.6)
    b.set_ylabel("which-scene\nentropy (bits)")
    b.set_ylim(0, np.log2(50) + 0.2)
    b.axhline(np.log2(50), color=MUTED, lw=0.4)
    b.tick_params(labelbottom=False)

    x, m, n = binned(t, np.log10(z["w50"]))
    nb = null_band(run, "w50")
    if nb is not None:
        lo, med, hi = nb
        c.axhspan(lo, hi, color=FAINT, lw=0)
        c.fill_between(x, 10**m, lo, where=10**m < lo, color=INK, alpha=0.25, lw=0)
    c.plot(x, 10**m, color=INK, lw=0.6)
    c.set_yscale("log")
    c.set_ylim(2, 3000)
    c.set_ylabel("W50: film span\nholding half (s)")
    c.tick_params(labelbottom=False)

    poff = z["p_off"] if "p_off" in z.files else z["p_unloc"]
    x, m, n = binned(t, np.nan_to_num(poff, nan=1.0))
    d.imshow(
        m[None, :],
        aspect="auto",
        cmap="Greys",
        vmin=0,
        vmax=1,
        extent=[0, x[-1] + BIN / 2, 0, 1],
    )
    d.set_yticks([])
    d.set_ylabel("off-film /\nunlocalized", rotation=0, ha="right", va="center", fontsize=7)
    d.set_xlabel(
        "recall time (s; Princeton word-onset clock; 1.5 s bins, gaps = no speech)"
    )
    fig.savefig(out, bbox_inches="tight")
    plt.close(fig)


def multiples(run, summ, out, key="w50"):
    P = summ["participants"]
    names = sorted(P, key=lambda k: -(P[k].get("scene_acc") or -1))
    tmax = max(np.nanmax(np.load(run / f"{n}.npz")["t"]) for n in names)
    nb = null_band(run, key)
    fig, axs = plt.subplots(
        len(names), 1, figsize=(7.2, 0.55 * len(names) + 0.5), sharex=True, sharey=True
    )
    for ax, name in zip(axs, names):
        z = np.load(run / f"{name}.npz")
        x, m, n = binned(z["t"], np.log10(z[key]))
        if nb is not None:
            ax.axhspan(nb[0], nb[2], color=FAINT, lw=0)
            ax.fill_between(x, 10**m, nb[0], where=10**m < nb[0], color=INK, alpha=0.3, lw=0)
        ax.plot(x, 10**m, color=INK, lw=0.45)
        iv = D.gold_intervals(name) or []
        for s0, _, _ in iv[1:]:
            ax.plot([s0, s0], [2200, 3000], color=ACCENT, lw=0.35)
        ax.set_yscale("log")
        ax.set_ylim(2, 3000)
        ax.set_yticks([3, 10, 100, 1000])
        ax.set_yticklabels(["3", "10", "100", "1000"])
        r = P[name]
        lab = name.split("_")[0]
        if "scene_acc" in r:
            lab += f"  agree {r['scene_acc']:.0%}  AUROC {r['auroc_lowHscene_hit']:.2f}"
        else:
            lab += "  (no human coding)"
        ax.text(
            1.0, 0.5, lab, transform=ax.transAxes, fontsize=6.3, color=INK, va="center"
        )
        ax.spines["bottom"].set_visible(ax is axs[-1])
        ax.tick_params(bottom=ax is axs[-1], labelsize=6)
    axs[-1].set_xlim(0, tmax)
    axs[-1].set_xlabel(
        "recall time (s); red ticks = human-coded scene changes; grey band = "
        "different-film null 5–95%; dark fill = more specific than the null"
    )
    fig.text(
        0.04,
        0.5,
        "W50, film span holding half the mass (s)",
        rotation=90,
        va="center",
        fontsize=7,
    )
    fig.savefig(out, bbox_inches="tight")
    plt.close(fig)


def reliability(run, summ, out, rng):
    P = summ["participants"]
    names = [n for n in P if "scene_acc" in P[n]]
    data = {}
    for n in names:
        z = np.load(run / f"{n}.npz")
        on = (z["gold"] > 0) & ~np.isnan(z["top_scene_p"])
        data[n] = (
            z["top_scene_p"][on],
            (z["peak_scene"] == z["gold"])[on],
            z["H_scene"][on],
        )
    fig, (a, b) = plt.subplots(1, 2, figsize=(7.2, 2.9))
    edges = np.linspace(0, 1, 11)

    def curve(sel, xi, edges):
        ys = []
        for lo, hi in zip(edges[:-1], edges[1:]):
            per = []
            for n in sel:
                x, y = data[n][xi], data[n][1]
                s = (x >= lo) & (x < hi)
                if s.sum() >= 5:
                    per.append(y[s].mean())
            ys.append(np.mean(per) if per else np.nan)
        return np.array(ys)

    mids = (edges[:-1] + edges[1:]) / 2
    base = curve(names, 0, edges)
    bs = np.array(
        [curve(list(rng.choice(names, len(names))), 0, edges) for _ in range(300)]
    )
    a.fill_between(
        mids,
        np.nanquantile(bs, 0.025, 0),
        np.nanquantile(bs, 0.975, 0),
        color=FAINT,
        lw=0,
    )
    a.plot([0, 1], [0, 1], color=MUTED, lw=0.5, ls=":")
    a.plot(mids, base, color=INK, lw=1, marker="o", ms=2)
    allp = np.concatenate([data[n][0] for n in names])
    hist, _ = np.histogram(allp, edges)
    a.bar(mids, hist / hist.max() * 0.15, width=0.09, color=MUTED, alpha=0.5, lw=0)
    a.set_xlabel("model's top-scene probability")
    a.set_ylabel("top scene = human scene")
    a.set_title(
        "Calibration (participants weighted equally; band = participant bootstrap 95%;\n"
        "bars = share of words; dotted = perfect calibration)",
        loc="left",
        fontsize=7,
    )
    a.set_xlim(0, 1)
    a.set_ylim(0, 1)

    hmax = np.log2(50)
    he = np.linspace(0, hmax, 12)
    hm = (he[:-1] + he[1:]) / 2
    base = curve(names, 2, he)
    bs = np.array(
        [curve(list(rng.choice(names, len(names))), 2, he) for _ in range(300)]
    )
    b.fill_between(
        hm,
        np.nanquantile(bs, 0.025, 0),
        np.nanquantile(bs, 0.975, 0),
        color=FAINT,
        lw=0,
    )
    b.plot(hm, base, color=INK, lw=1, marker="o", ms=2)
    b.axhline(1 / 50, color=MUTED, lw=0.5, ls=":")
    b.text(hmax, 1 / 50, " chance", fontsize=6, color=MUTED, va="center")
    allh = np.concatenate([data[n][2] for n in names])
    hist, _ = np.histogram(allh, he)
    b.bar(
        hm,
        hist / hist.max() * 0.15,
        width=(he[1] - he[0]) * 0.9,
        color=MUTED,
        alpha=0.5,
        lw=0,
    )
    b.set_xlabel("which-scene entropy (bits)")
    b.set_title(
        "Discrimination: is a low-entropy moment more often right?",
        loc="left",
        fontsize=7,
    )
    b.set_ylim(0, 1)
    fig.tight_layout()
    fig.savefig(out, bbox_inches="tight")
    plt.close(fig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("label")
    ap.add_argument("--focus", default="NN03_ANT_202231_final")
    a = ap.parse_args()
    run = D.OUT / "runs" / a.label
    summ = json.loads((run / "summary.json").read_text())
    f = run / "fig"
    f.mkdir(exist_ok=True)
    rng = np.random.default_rng(1)
    map_figure(run, a.focus, f / "map.png", summ)
    multiples(run, summ, f / "multiples.png")
    reliability(run, summ, f / "reliability.png", rng)
    print(f)


if __name__ == "__main__":
    main()
