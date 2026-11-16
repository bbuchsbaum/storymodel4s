"""Figures for one run directory (numbers only; no recall or film text is drawn)."""

from __future__ import annotations

import argparse
import json

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402

import data as D  # noqa: E402

INK = "#222222"
MUTED = "#8a8a8a"
ACCENT = "#b3001b"
plt.rcParams.update(
    {
        "font.family": "serif",
        "font.size": 8.5,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "axes.edgecolor": MUTED,
        "axes.labelcolor": INK,
        "xtick.color": MUTED,
        "ytick.color": MUTED,
        "axes.linewidth": 0.6,
        "savefig.dpi": 180,
    }
)


def scene_spans():
    rows = D.film_rows(with_speech=False)
    sp = {}
    for r in rows:
        lo, hi = sp.get(r.scene, (r.t0, r.t1))
        sp[r.scene] = (min(lo, r.t0), max(hi, r.t1))
    return sp


def gold_boxes(name):
    iv = D.gold_intervals(name) or []
    return iv


def rolling(x, k):
    k = max(1, k)
    w = np.ones(k) / k
    return np.convolve(np.nan_to_num(x, nan=np.nanmean(x)), w, mode="same")


def map_figure(run, name, out):
    z = np.load(run / f"{name}.npz")
    t, psec, H, Hs, Hw = (
        z["t"],
        z["psec"].astype(np.float32),
        z["H"],
        z["H_scene"],
        z["H_within"],
    )
    ok = ~np.isnan(t)
    t, psec, H, Hs, Hw, H0 = t[ok], psec[ok], H[ok], Hs[ok], Hw[ok], z["H_null"][ok]
    sp = scene_spans()
    fig, (a, b) = plt.subplots(
        2,
        1,
        figsize=(7.2, 6.4),
        sharex=True,
        gridspec_kw={"height_ratios": [3, 1.3], "hspace": 0.06},
    )
    # density image: recall seconds (x) by film seconds (y); one column per word, drawn at its onset
    T = psec.shape[1]
    tb = np.arange(0, np.nanmax(t) + 1.5, 1.5)
    img = np.zeros((T, len(tb)))
    cnt = np.zeros(len(tb))
    k = np.clip(np.searchsorted(tb, t, side="right") - 1, 0, len(tb) - 1)
    for i, kk in enumerate(k):
        img[:, kk] += psec[i]
        cnt[kk] += 1
    img = img / np.maximum(cnt, 1)
    img[:, cnt == 0] = np.nan
    vmax = np.nanpercentile(img, 99.7)
    a.imshow(
        np.sqrt(img / vmax),
        aspect="auto",
        origin="lower",
        extent=[tb[0], tb[-1] + 1.5, 0, T],
        cmap="Greys",
        vmin=0,
        vmax=1,
        interpolation="nearest",
    )
    for lo, hi, s in gold_boxes(name):
        if s in sp:
            f0, f1 = sp[s]
            a.add_patch(
                plt.Rectangle(
                    (lo, f0), hi - lo + 1.5, f1 - f0, fill=False, lw=0.6, ec=ACCENT
                )
            )
    part_a = max(r.t1 for r in D.film_rows(False) if r.part == "a")
    a.axhline(part_a, color=MUTED, lw=0.4, ls=":")
    a.set_ylabel("film time (s)\npart a | part b")
    a.set_title(
        f"{name.split('_')[0]}: where in the film each moment of recall points "
        "(grey = model mass; red outline = human scene coding)",
        loc="left",
        color=INK,
        fontsize=8.5,
    )
    sm = 9
    b.fill_between(
        t, 0, rolling(Hs, sm), color="#555555", lw=0, label="which scene (H scene)"
    )
    b.fill_between(
        t,
        rolling(Hs, sm),
        rolling(Hs + Hw, sm),
        color="#c9c9c9",
        lw=0,
        label="where within scene",
    )
    b.axhline(np.median(H0), color=ACCENT, lw=0.7, ls="--")
    b.text(
        t[-1],
        np.median(H0),
        " shuffled-word null (median)",
        color=ACCENT,
        va="center",
        fontsize=7,
        ha="left",
    )
    b.axhline(np.log2(T), color=MUTED, lw=0.4)
    b.text(
        t[-1], np.log2(T), " uniform over film", color=MUTED, va="center", fontsize=7
    )
    b.set_ylim(0, np.log2(T) + 0.3)
    b.set_ylabel("entropy (bits)\nlower = more specific")
    b.set_xlabel("recall time (s, Princeton word-onset clock)")
    b.legend(frameon=False, loc="lower left", fontsize=7, ncol=2)
    fig.savefig(out, bbox_inches="tight")
    plt.close(fig)


def small_multiples(run, names, out):
    n = len(names)
    cols = 2
    rws = int(np.ceil(n / cols))
    fig, axs = plt.subplots(rws, cols, figsize=(7.2, 1.0 * rws + 0.4), sharey=True)
    axs = axs.ravel()
    for ax, name in zip(axs, names):
        z = np.load(run / f"{name}.npz")
        ok = ~np.isnan(z["t"])
        t, H = z["t"][ok], z["H"][ok]
        w = 2 ** rolling(H, 9)
        ax.plot(t, w, color=INK, lw=0.5)
        ax.axhline(2 ** np.median(z["H_null"]), color=ACCENT, lw=0.5, ls="--")
        ax.set_yscale("log")
        ax.text(
            0.01, 0.9, name.split("_")[0], transform=ax.transAxes, fontsize=7, color=INK
        )
        ax.set_xlim(0, None)
    for ax in axs[n:]:
        ax.axis("off")
    fig.supylabel("effective film width (s, 2^H; log)", fontsize=8)
    fig.supxlabel("recall time (s)", fontsize=8)
    fig.tight_layout(h_pad=0.2)
    fig.savefig(out, bbox_inches="tight")
    plt.close(fig)


def reliability(run, names, out):
    fig, ax = plt.subplots(figsize=(3.6, 2.8))
    pooled_H, pooled_c = [], []
    for name in names:
        z = np.load(run / f"{name}.npz")
        m = z["gold"] > 0
        if not m.any():
            continue
        H, c = z["H"][m], (z["map_scene"] == z["gold"])[m]
        pooled_H.append(H)
        pooled_c.append(c)
        q = np.quantile(H, np.linspace(0, 1, 6))
        x, y = [], []
        for lo, hi in zip(q[:-1], q[1:]):
            s = (H >= lo) & (H <= hi)
            x.append(H[s].mean())
            y.append(c[s].mean())
        ax.plot(x, y, color=MUTED, lw=0.5, marker="o", ms=1.5)
    H, c = np.concatenate(pooled_H), np.concatenate(pooled_c)
    q = np.quantile(H, np.linspace(0, 1, 11))
    x = [H[(H >= lo) & (H <= hi)].mean() for lo, hi in zip(q[:-1], q[1:])]
    y = [c[(H >= lo) & (H <= hi)].mean() for lo, hi in zip(q[:-1], q[1:])]
    ax.plot(x, y, color=INK, lw=1.2, marker="o", ms=2.5)
    ax.set_xlabel("entropy at word (bits; deciles)")
    ax.set_ylabel("peak scene = human scene")
    ax.set_ylim(0, 1)
    ax.set_title("Is low entropy trustworthy?", loc="left", fontsize=8.5)
    fig.savefig(out, bbox_inches="tight")
    plt.close(fig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("label")
    ap.add_argument("--focus", default="NN03_ANT_202231_final")
    args = ap.parse_args()
    run = D.OUT / "runs" / args.label
    s = json.loads((run / "summary.json").read_text())
    names = list(s["participants"])
    fig = run / "fig"
    fig.mkdir(exist_ok=True)
    map_figure(run, args.focus, fig / "map.png")
    small_multiples(run, names, fig / "multiples.png")
    reliability(run, names, fig / "reliability.png")
    print(fig)


if __name__ == "__main__":
    main()
