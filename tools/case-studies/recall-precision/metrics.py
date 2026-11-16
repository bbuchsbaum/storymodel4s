"""Shared scoring kernels for the case studies (bead bd-01M7JRE34VJAGXHD7QBTFEYTE6).

Every kernel here has a known-answer test in test_metrics.py. Case-study scripts import these
instead of keeping their own copies: two tie-handling bugs in this study came from copies.

Ties: every rank-based kernel uses midranks (ties share their average rank).
"""

from __future__ import annotations

import numpy as np
from scipy.stats import rankdata, spearmanr


def auroc(pos, neg) -> float:
    """P(score_pos > score_neg) + 0.5 P(tie), via midranks. NaN when a class is empty."""
    a, b = np.asarray(pos, float), np.asarray(neg, float)
    if len(a) == 0 or len(b) == 0:
        return float("nan")
    r = rankdata(np.concatenate([a, b]))
    return float((r[: len(a)].sum() - len(a) * (len(a) + 1) / 2) / (len(a) * len(b)))


def ece(conf, hit, bins: int = 10) -> float:
    """Expected calibration error over equal-width confidence bins, weighted by bin share."""
    conf, hit = np.asarray(conf, float), np.asarray(hit, float)
    if len(conf) == 0:
        return float("nan")
    q = np.clip((conf * bins).astype(int), 0, bins - 1)
    e = 0.0
    for b in range(bins):
        s = q == b
        if s.any():
            e += s.mean() * abs(conf[s].mean() - hit[s].mean())
    return float(e)


def icc21(x, y) -> float:
    """Shrout & Fleiss ICC(2,1): two-way random effects, absolute agreement, single measure."""
    X = np.column_stack([np.asarray(x, float), np.asarray(y, float)])
    n, k = X.shape
    if n < 2:
        return float("nan")
    gm = X.mean()
    msr = k * ((X.mean(1) - gm) ** 2).sum() / (n - 1)
    msc = n * ((X.mean(0) - gm) ** 2).sum() / (k - 1)
    sse = ((X - X.mean(1, keepdims=True) - X.mean(0, keepdims=True) + gm) ** 2).sum()
    mse = sse / ((n - 1) * (k - 1))
    den = msr + (k - 1) * mse + k * (msc - mse) / n
    return float((msr - mse) / den) if den > 0 else float("nan")


def cohen_kappa(a, b) -> float:
    """Cohen's kappa for two binary (or categorical) raters."""
    a, b = np.asarray(a), np.asarray(b)
    if len(a) == 0:
        return float("nan")
    cats = np.union1d(a, b)
    po = float(np.mean(a == b))
    pe = float(sum(np.mean(a == c) * np.mean(b == c) for c in cats))
    return float((po - pe) / (1 - pe)) if pe < 1 else float("nan")


def boot_mean_ci(values, n: int = 4000, seed: int = 11, level: float = 0.95):
    """Percentile bootstrap CI of the mean over units (e.g. participants). With n ~ 10 units the
    interval is approximate and narrow; report the units' values alongside it."""
    v = np.asarray(values, float)
    v = v[~np.isnan(v)]
    if len(v) < 3:
        return None
    rng = np.random.default_rng(seed)
    bs = v[rng.integers(0, len(v), (n, len(v)))].mean(1)
    lo = (1 - level) / 2
    return [float(np.quantile(bs, lo)), float(np.quantile(bs, 1 - lo))]


def paired_difference(a: dict, b: dict, n: int = 4000, seed: int = 3):
    """Per-unit paired a - b over shared keys; mean, bootstrap CI (None below 3 units), the
    per-unit values, and how many are positive."""
    keys = sorted(set(a) & set(b))
    d = np.array([a[k] - b[k] for k in keys], float)
    return dict(
        per_unit={k: float(x) for k, x in zip(keys, d)},
        n=len(d),
        mean=float(d.mean()) if len(d) else None,
        ci95=boot_mean_ci(d, n=n, seed=seed),
        positive=int((d > 0).sum()),
    )


def midrank_spearman_matrix(columns: dict):
    """Spearman correlation matrix of equally long columns; ties handled by midranks."""
    names = list(columns)
    X = {c: rankdata(np.asarray(columns[c], float)) for c in names}
    M = np.eye(len(names))
    for i, a in enumerate(names):
        for j, b in enumerate(names):
            if j < i:
                M[i, j] = M[j, i] = spearmanr(X[a], X[b]).statistic
    return names, M


def within_unit_ranks(v) -> np.ndarray:
    """Normalized midranks within one unit (participant), for pooling across units."""
    v = np.asarray(v, float)
    return rankdata(v) / len(v)


# ---------------------------------------------------------------- case study 2 kernels


def log_loss_bits(p) -> float:
    """-log2 p for a probability in (0, 1]; refuses 0, negatives, NaN and values above 1."""
    p = float(p)
    if not (0 < p <= 1 + 1e-12):
        raise ValueError(f"log loss needs a probability in (0, 1], got {p!r}")
    return float(-np.log2(min(p, 1.0)))


def cluster_bootstrap(clusters: dict, stat, n: int = 4000, seed: int = 11, level: float = 0.95):
    """Percentile bootstrap that resamples CLUSTERS (e.g. participants), never rows.

    `clusters` maps a cluster id to its payload; `stat(list_of_payloads)` returns a float (NaN
    allowed and dropped). Returns [lo, hi], or None below 3 clusters."""
    keys = sorted(clusters)
    if len(keys) < 3:
        return None
    rng = np.random.default_rng(seed)
    vals = []
    for _ in range(n):
        pick = rng.integers(0, len(keys), len(keys))
        vals.append(stat([clusters[keys[i]] for i in pick]))
    v = np.asarray(vals, float)
    v = v[~np.isnan(v)]
    lo = (1 - level) / 2
    return [float(np.quantile(v, lo)), float(np.quantile(v, 1 - lo))]


def binary_f1(pred, gold) -> float:
    """F1 of a binary prediction against binary gold; NaN when neither has a positive."""
    p, g = np.asarray(pred, bool), np.asarray(gold, bool)
    tp = int((p & g).sum())
    fp, fn = int((p & ~g).sum()), int((~p & g).sum())
    if tp + fp + fn == 0:
        return float("nan")
    return 2 * tp / (2 * tp + fp + fn)


def tfidf_cosine(query: list, documents: dict) -> dict:
    """Cosine between a token list and each document's token list, smoothed IDF
    idf(w) = ln((1 + N) / (1 + df(w))) + 1 and sublinear term frequency 1 + ln(tf), both sides
    weighted. Documents are {key: [tokens]}; returns {key: cosine in [0, 1]}."""
    from collections import Counter

    docs = {k: Counter(v) for k, v in documents.items()}
    df = Counter(w for d in docs.values() for w in d)
    n = len(docs)
    idf = {w: float(np.log((1 + n) / (1 + c)) + 1) for w, c in df.items()}

    def vec(counter):
        v = {w: (1 + np.log(c)) * idf[w] for w, c in counter.items() if w in idf}
        norm = float(np.sqrt(sum(x * x for x in v.values())))
        return {w: x / norm for w, x in v.items()} if norm > 0 else {}

    q = vec(Counter(query))
    out = {}
    for k, d in docs.items():
        dv = vec(d)
        out[k] = float(sum(q[w] * dv.get(w, 0.0) for w in q))
    return out
