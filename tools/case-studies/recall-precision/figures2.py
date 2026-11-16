"""Compare estimators (estimands) on one participant at fixed scales, plus TR-level agreement.

compare.png: one row per estimator; each row is W50 (log s) over recall time, binned at 1.5 s,
with that estimator's own different-film null band; the human-coded scene changes are ticks.
agreement.png: TR-level which-scene entropy of the content-local LLM vs the analyst stream,
pooled across participants (equal weight shown by per-participant Spearman rho in the label).
"""

from __future__ import annotations

import argparse
import json

import numpy as np
from scipy.stats import spearmanr

import data as D
from figures1 import ACCENT, BIN, FAINT, INK, MUTED, binned, plt


def compare(runs, labels, name, out):
    fig, axs = plt.subplots(
        len(runs), 1, figsize=(7.2, 1.25 * len(runs) + 0.5), sharex=True, sharey=True
    )
    tmax = None
    for ax, run, lab in zip(axs, runs, labels):
        z = np.load(D.OUT / "runs" / run / f"{name}.npz")
        tmax = tmax or np.nanmax(z["t"])
        x, m, n = binned(z["t"], np.log10(z["w50"]), tmax)
        nf = D.OUT / "runs" / run / "foreign_null.npz"
        if nf.exists() and len(np.load(nf)["w50"]) >= 30:
            lo, hi = np.quantile(np.load(nf)["w50"], [0.05, 0.95])
            ax.axhspan(lo, hi, color=FAINT, lw=0)
        elif nf.exists():
            ax.text(0.99, 0.08, "different-film null: almost nothing localized (no width band)",
                    transform=ax.transAxes, ha="right", fontsize=6, color=MUTED)
        ax.plot(x, 10**m, color=INK, lw=0.5)
        for s0, _, _ in (D.gold_intervals(name) or [])[1:]:
            ax.plot([s0, s0], [1800, 3000], color=ACCENT, lw=0.4)
        ax.set_yscale("log")
        ax.set_ylim(2, 3000)
        ax.set_yticks([3, 10, 100, 1000])
        ax.set_yticklabels(["3", "10", "100", "1000"])
        s = json.loads((D.OUT / "runs" / run / "summary.json").read_text())[
            "participants"
        ][name]
        ax.set_title(
            f"{lab}   agree {s['scene_acc']:.0%}", loc="left", fontsize=7.5, color=INK
        )
    axs[-1].set_xlim(0, tmax)
    axs[-1].set_xlabel(
        "recall time (s, Princeton clock; 1.5 s bins). Grey band: different-film null "
        "5–95% for that estimator. Red ticks: human-coded scene changes."
    )
    fig.text(
        0.04,
        0.5,
        "W50: film span holding half the mass (s, log)",
        rotation=90,
        va="center",
        fontsize=7,
    )
    fig.savefig(out, bbox_inches="tight")
    plt.close(fig)


def agreement(run_a, run_b, lab_a, lab_b, out):
    xs, ys, rhos = [], [], []
    for name in D.development():
        pa = D.OUT / "runs" / run_a / f"{name}.npz"
        pb = D.OUT / "runs" / run_b / f"{name}.npz"
        if not (pa.exists() and pb.exists()):
            continue
        za, zb = np.load(pa), np.load(pb)
        tmax = max(np.nanmax(za["t"]), np.nanmax(zb["t"]))
        _, a, _ = binned(za["t"], za["H_scene"], tmax)
        _, b, _ = binned(zb["t"], zb["H_scene"], tmax)
        ok = ~np.isnan(a) & ~np.isnan(b)
        xs.append(a[ok])
        ys.append(b[ok])
        if ok.sum() > 10:
            rhos.append(spearmanr(a[ok], b[ok]).statistic)
    x, y = np.concatenate(xs), np.concatenate(ys)
    fig, ax = plt.subplots(figsize=(3.6, 3.4))
    jx = x + np.random.default_rng(0).normal(0, 0.03, len(x))
    jy = y + np.random.default_rng(1).normal(0, 0.03, len(y))
    ax.scatter(jx, jy, s=1.5, color=INK, alpha=0.15, lw=0)
    ax.set_xlabel(f"{lab_a}: which-scene entropy (bits)")
    ax.set_ylabel(f"{lab_b}: which-scene entropy (bits)")
    ax.set_title(
        f"TR-level agreement; Spearman ρ per participant\nmedian {np.median(rhos):.2f} "
        f"(range {min(rhos):.2f}–{max(rhos):.2f}), {len(rhos)} participants",
        loc="left",
        fontsize=7,
    )
    fig.savefig(out, bbox_inches="tight")
    plt.close(fig)
    return dict(median_rho=float(np.median(rhos)), rhos=[float(r) for r in rhos])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", nargs="+", required=True)
    ap.add_argument("--labels", nargs="+", required=True)
    ap.add_argument("--focus", default="NN03_ANT_202231_final")
    ap.add_argument(
        "--out", required=True, help="run label whose fig/ dir receives the figures"
    )
    a = ap.parse_args()
    f = D.OUT / "runs" / a.out / "fig"
    f.mkdir(parents=True, exist_ok=True)
    compare(a.runs, a.labels, a.focus, f / "compare.png")
    r = agreement(
        a.runs[-2], a.runs[-1], a.labels[-2], a.labels[-1], f / "agreement.png"
    )
    print(json.dumps(r))


if __name__ == "__main__":
    main()


def decomposition(run, names, out):
    """Three quantities kept apart (measurement contract): is the speech about film content at
    all (localizable share), how long a stretch it refers to (breadth: weighted span of the
    referenced ranges, s), and how much it hesitates between competing hypotheses (which-scene
    entropy over alternatives, bits). Non-episodic stretches are gaps in breadth and uncertainty.
    """
    fig, axs = plt.subplots(3 * len(names), 1, figsize=(7.2, 1.55 * len(names) + 0.6),
                            sharex=True, gridspec_kw={"hspace": 0.12})
    tmax = max(np.nanmax(np.load(D.OUT / "runs" / run / f"{n}.npz")["t"]) for n in names)
    for k, name in enumerate(names):
        z = np.load(D.OUT / "runs" / run / f"{name}.npz")
        a, b, c = axs[3 * k : 3 * k + 3]
        loc = np.where(np.isnan(z["applicable"]), np.nan,
                       np.where(z["applicable"] == 1, 1 - np.nan_to_num(z["p_unloc"]), 0.0))
        x, m, _ = binned(z["t"], loc, tmax)
        a.fill_between(x, 0, np.nan_to_num(m, nan=0), step="mid", color=INK, alpha=0.55, lw=0)
        a.set_ylim(0, 1)
        a.set_yticks([0, 1])
        a.set_ylabel("about\nfilm", rotation=0, ha="right", va="center", fontsize=6.5)
        a.set_title(name.split("_")[0], loc="left", fontsize=7.5, color=INK)
        x, m, _ = binned(z["t"], np.log10(z["breadth_s"]), tmax)
        b.plot(x, 10**m, color=INK, lw=0.6, drawstyle="steps-mid")
        b.set_yscale("log")
        b.set_ylim(1, 2000)
        b.set_yticks([1, 10, 100, 1000])
        b.set_yticklabels(["1", "10", "100", "1000"])
        b.set_ylabel("breadth\n(s)", rotation=0, ha="right", va="center", fontsize=6.5)
        x, m, _ = binned(z["t"], z["H_scene"], tmax)
        c.plot(x, m, color=ACCENT, lw=0.6, drawstyle="steps-mid")
        c.set_ylim(0, 2.5)
        c.set_yticks([0, 1, 2])
        c.set_ylabel("which-scene\nuncertainty\n(bits)", rotation=0, ha="right", va="center",
                     fontsize=6.5)
        for s0, _, _ in (D.gold_intervals(name) or [])[1:]:
            c.plot([s0, s0], [2.1, 2.5], color=MUTED, lw=0.4)
    axs[-1].set_xlim(0, tmax)
    axs[-1].set_xlabel("recall time (s; 1.5 s bins; gaps = no speech or non-episodic). "
                       "Grey ticks: human-coded scene changes.")
    fig.savefig(out, bbox_inches="tight")
    plt.close(fig)


def main_decomp():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--names", nargs="+", default=["NN03", "NN13"])
    a = ap.parse_args()
    names = [n for n in D.development() if n.split("_")[0] in a.names]
    f = D.OUT / "runs" / a.run / "fig"
    decomposition(a.run, names, f / "decomposition.png")
    print(f / "decomposition.png")
