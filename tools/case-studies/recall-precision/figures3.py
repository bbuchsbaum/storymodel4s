"""Iteration-3 figures (numbers only; no recall or film text).

decomposition.png  three channels per participant, stacked by quantity, shared scene rules
breadth_vs_U.png    joint distribution of breadth and between-hypothesis uncertainty, by form
streams.png         content-local vs analyst confidence on one participant + per-participant slope
calibration.png     equal-mass calibration bins with counts, for both streams
"""

from __future__ import annotations

import argparse
import json

import numpy as np

import data as D
from figures1 import ACCENT, BIN, FAINT, INK, MUTED, binned, plt

TALK_SHADE = {
    "episodic": 0.0,
    "episodic-unlocalized": 0.35,
    "fragment": 0.5,
    "commentary": 0.75,
    "metamemory": 1.0,
    "missing": np.nan,
    "": np.nan,
}


def z(label, name):
    return np.load(D.OUT / "runs" / label / f"{name}.npz", allow_pickle=True)


def rules(ax, name, top=True):
    for s0, _, _ in (D.gold_intervals(name) or [])[1:]:
        ax.axvline(s0, color=FAINT, lw=0.5, zorder=0)


def decomposition(label, names, out):
    fig, axs = plt.subplots(
        3,
        len(names),
        figsize=(7.4, 4.6),
        sharey="row",
        gridspec_kw={"hspace": 0.12, "wspace": 0.06, "height_ratios": [0.45, 1.3, 1.0]},
    )
    axs = np.atleast_2d(axs).reshape(3, len(names))
    for j, name in enumerate(names):
        d = z(label, name)
        t = d["t"]
        tmax = np.nanmax(t)
        a, b, c = axs[:, j]
        for ax in (a, b, c):
            rules(ax, name)
            ax.set_xlim(0, tmax)
        shade = np.array([TALK_SHADE.get(x, np.nan) for x in d["talk"]], float)
        x, m, _ = binned(t, shade, tmax)
        a.imshow(
            m[None, :],
            aspect="auto",
            cmap="Greys",
            vmin=0,
            vmax=1,
            extent=[0, x[-1] + BIN / 2, 0, 1],
            interpolation="nearest",
        )
        a.set_yticks([])
        a.set_title(f"{name.split('_')[0]}", loc="left", fontsize=8, color=INK)
        x, m, _ = binned(t, np.log10(np.maximum(d["B"], 1)), tmax)
        b.plot(x, 10**m, color=INK, lw=0.6, drawstyle="steps-mid")
        b.set_yscale("log")
        b.set_ylim(1, 3000)
        x, m, _ = binned(t, d["U"], tmax)
        nz = np.nan_to_num(m) > 0
        c.vlines(x[nz], 0, m[nz], color=ACCENT, lw=0.8)
        c.set_ylim(0, 2.2)
        for ax in (a, b):
            ax.tick_params(labelbottom=False)
        c.set_xlabel("recall time (s)", fontsize=7)
    axs[0, 0].set_ylabel("talk", rotation=0, ha="right", va="center", fontsize=7)
    axs[1, 0].set_ylabel("breadth (s)", fontsize=7)
    axs[1, 0].set_yticks([1, 10, 100, 1000])
    axs[1, 0].set_yticklabels(["1", "10", "100", "1000"])
    axs[2, 0].set_ylabel("uncertainty\nbetween\nhypotheses\n(bits)", fontsize=7)
    fig.text(
        0.1,
        -0.04,
        "Talk strip: white = episodic, light grey = episodic but unlocalized, mid grey = fragment, "
        "dark = commentary, black = metamemory.\nBreadth: film extent of the referenced "
        "hypothesis (weighted). Uncertainty: entropy over competing hypotheses, drawn only "
        "where nonzero. Faint rules: human-coded scene changes.",
        fontsize=6.5,
        color=MUTED,
    )
    fig.savefig(out, bbox_inches="tight")
    plt.close(fig)


def breadth_vs_u(label, out):
    rows = []
    for name in D.development():
        d = z(label, name)
        seen = set()
        for i in range(len(d["t"])):
            key = (int(d["k"][i]), int(d["wr"][i]))
            if key in seen or np.isnan(d["U"][i]):
                continue
            seen.add(key)
            rows.append((d["B"][i], d["U"][i], str(d["form"][i])))
    forms = ["specific", "extended", "repeated", "summary", "joint"]
    fig, axs = plt.subplots(1, len(forms), figsize=(7.4, 1.9), sharey=True, sharex=True)
    for ax, f in zip(axs, forms):
        sel = [(b, u) for b, u, ff in rows if ff == f]
        if sel:
            b, u = np.array(sel).T
            jit = np.random.default_rng(0).uniform(-0.04, 0.04, len(u))
            ax.scatter(
                np.log10(np.maximum(b, 1)),
                u + jit * (u > 0),
                s=2,
                color=INK,
                alpha=0.35,
                lw=0,
            )
            ax.text(
                0.02,
                0.95,
                f"{f}\nn={len(sel)}  U>0: {np.mean(u > 0):.0%}",
                transform=ax.transAxes,
                fontsize=6.5,
                va="top",
                color=INK,
            )
        ax.set_xticks([0, 1, 2, 3])
        ax.set_xticklabels(["1", "10", "100", "1000"])
    axs[0].set_ylabel("uncertainty (bits)", fontsize=7)
    fig.text(
        0.5,
        -0.06,
        "breadth (s, log) — one dot per reference, development participants",
        ha="center",
        fontsize=7,
        color=MUTED,
    )
    fig.savefig(out, bbox_inches="tight")
    plt.close(fig)
    return len(rows)


def streams(local, analyst, focus, out):
    fig = plt.figure(figsize=(7.4, 2.9))
    gs = fig.add_gridspec(1, 2, width_ratios=[4.2, 1], wspace=0.25)
    ax = fig.add_subplot(gs[0])
    a, b = z(local, focus), z(analyst, focus)
    tmax = np.nanmax(a["t"])
    xa, ma, _ = binned(a["t"], a["conf"], tmax)
    xb, mb, _ = binned(b["t"], b["conf"], tmax)
    rules(ax, focus)
    ax.fill_between(
        xa, ma, mb, where=mb > ma, color=ACCENT, alpha=0.25, lw=0, step="mid"
    )
    ax.plot(xa, ma, color=INK, lw=0.6, drawstyle="steps-mid")
    ax.plot(xb, mb, color=ACCENT, lw=0.5, drawstyle="steps-mid")
    ax.text(
        tmax, np.nanmean(ma), " content-local", color=INK, fontsize=6.5, va="center"
    )
    ax.set_ylim(0, 1)
    ax.set_xlim(0, tmax)
    ax.set_ylabel("probability of the top scene\n(fitted)", fontsize=7)
    ax.set_xlabel(
        f"{focus.split('_')[0]}, recall time (s). Red line: analyst stream (whole recall). "
        "Red fill: where order and context add confidence.",
        fontsize=6.5,
    )
    ax2 = fig.add_subplot(gs[1])
    sl = json.loads((D.OUT / "runs" / local / "summary.json").read_text())[
        "participants"
    ]
    sa = json.loads((D.OUT / "runs" / analyst / "summary.json").read_text())[
        "participants"
    ]
    for n in sl:
        if "scene_acc" in sl[n] and "scene_acc" in sa.get(n, {}):
            ax2.plot(
                [0, 1],
                [sl[n]["scene_acc"], sa[n]["scene_acc"]],
                color=MUTED,
                lw=0.6,
                marker="o",
                ms=2,
            )
    ax2.set_xticks([0, 1])
    ax2.set_xticklabels(["local", "analyst"], fontsize=7)
    ax2.set_ylim(0.4, 1)
    ax2.set_title(
        "agreement with\nhuman scene, by\nparticipant", fontsize=7, loc="left"
    )
    fig.savefig(out, bbox_inches="tight")
    plt.close(fig)


def calibration(labels, names_, out):
    fig, axs = plt.subplots(1, len(labels), figsize=(7.4, 2.8), sharey=True)
    for ax, lab, nm in zip(np.atleast_1d(axs), labels, names_):
        conf, hit, who = [], [], []
        for name in D.development():
            d = z(lab, name)
            m = (d["gold"] > 0) & ~np.isnan(d["conf"])
            conf.append(d["conf"][m])
            hit.append((d["peak"] == d["gold"])[m])
        c, h = np.concatenate(conf), np.concatenate(hit)
        q = np.unique(np.quantile(c, np.linspace(0, 1, 9)))
        xs, ys, ns = [], [], []
        for lo, hi in zip(q[:-1], q[1:]):
            s = (c >= lo) & (c <= hi) if hi == q[-1] else (c >= lo) & (c < hi)
            if s.sum():
                xs.append(c[s].mean())
                ys.append(h[s].mean())
                ns.append(int(s.sum()))
        ax.plot([0, 1], [0, 1], color=MUTED, lw=0.5, ls=":")
        ax.plot(xs, ys, color=INK, lw=1, marker="o", ms=2.5)
        for x, y, n in zip(xs, ys, ns):
            ax.text(x, y - 0.06, f"{n}", fontsize=5.5, color=MUTED, ha="center")
        ax.set_xlim(0, 1)
        ax.set_ylim(0, 1)
        ax.set_title(nm, loc="left", fontsize=7.5)
        ax.set_xlabel(
            "fitted probability of the top scene (equal-count bins; n = words)",
            fontsize=6.5,
        )
    np.atleast_1d(axs)[0].set_ylabel("top scene = human scene")
    fig.savefig(out, bbox_inches="tight")
    plt.close(fig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--local", default="it3-v2-llm-local-v2-a")
    ap.add_argument("--analyst", default="it3-analyst")
    ap.add_argument("--focus", default="NN03_ANT_202231_final")
    a = ap.parse_args()
    f = D.OUT / "runs" / a.local / "fig"
    f.mkdir(parents=True, exist_ok=True)
    names = [n for n in D.development() if n.split("_")[0] in ("NN03", "NN15")]
    decomposition(a.local, names, f / "decomposition.png")
    print("refs:", breadth_vs_u(a.local, f / "breadth_vs_U.png"))
    streams(a.local, a.analyst, a.focus, f / "streams.png")
    calibration(
        [a.local, a.analyst],
        ["content-local LLM (prompt v2)", "analyst (whole recall)"],
        f / "calibration.png",
    )
    print(f)


if __name__ == "__main__":
    main()
