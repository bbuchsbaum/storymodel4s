"""Known-answer tests for the shared case-study kernels (run: python test_metrics.py).

Each test states its expected value from first principles, not from the implementation.
"""

import numpy as np

from metrics import (
    auroc,
    binary_f1,
    boot_mean_ci,
    cluster_bootstrap,
    cohen_kappa,
    ece,
    icc21,
    log_loss_bits,
    midrank_spearman_matrix,
    paired_difference,
    sign_flip_p,
    tfidf_cosine,
    within_unit_ranks,
)
from precision1 import shortest_window


def close(a, b, tol=1e-12):
    assert abs(a - b) < tol, (a, b)


def test_auroc():
    pos, neg = [1] * 6 + [0] * 4, [1] * 4 + [0] * 6
    exact = np.mean([(p > n) + 0.5 * (p == n) for p in pos for n in neg])  # = 0.6
    close(auroc(pos, neg), exact)
    close(exact, 0.6)
    close(auroc([1] * 50, [1] * 50), 0.5)  # all tied
    close(auroc([3, 4], [1, 2]), 1.0)
    close(auroc([1, 2], [3, 4]), 0.0)
    assert np.isnan(auroc([], [1]))


def test_ece():
    close(ece([0.8] * 10, [1] * 8 + [0] * 2), 0.0)  # stated 80%, right 80%
    close(ece([0.9] * 4, [1, 0, 0, 0]), 0.65)  # |0.9 - 0.25|
    # two bins with equal shares: |0.15-0| and |0.95-1| -> 0.5*0.15 + 0.5*0.05
    close(ece([0.15, 0.15, 0.95, 0.95], [0, 0, 1, 1]), 0.1)


def test_icc21():
    x = np.array([1.0, 2.0, 3.0, 4.0, 5.0])
    close(icc21(x, x), 1.0)  # perfect agreement
    # Constant offset: consistency is perfect but absolute agreement is not.
    # Hand computation for x vs x+1 (n=5, k=2): MSR = 5, MSC = 2.5, MSE = 0
    # ICC(2,1) = (5 - 0) / (5 + 0 + 2*(2.5 - 0)/5) = 5 / 6
    close(icc21(x, x + 1), 5 / 6)
    # Nonzero residual: x = [1,2,3], y = [2,2,4]. By hand: MSR = 13/6, MSC = 2/3, MSE = 1/6;
    # ICC = (13/6 - 1/6) / (13/6 + 1/6 + 2*(2/3 - 1/6)/3) = 2 / (8/3) = 0.75
    close(icc21([1, 2, 3], [2, 2, 4]), 0.75)


def test_kappa():
    close(cohen_kappa([1, 1, 0, 0], [1, 1, 0, 0]), 1.0)
    # po = 0.5, pe = 0.5 (both raters 50/50) -> kappa 0
    close(cohen_kappa([1, 1, 0, 0], [1, 0, 1, 0]), 0.0)
    # Skewed marginals: po = 3/4, pe = .75*.5 + .25*.5 = 1/2 -> kappa = 1/2
    close(cohen_kappa([1, 1, 1, 0], [1, 1, 0, 0]), 0.5)


def test_bootstrap_and_paired():
    assert boot_mean_ci([1.0, 2.0]) is None  # fewer than 3 units: no interval
    ci = boot_mean_ci([5.0, 5.0, 5.0, 5.0])
    close(ci[0], 5.0)
    close(ci[1], 5.0)
    d = paired_difference(
        {"a": 3.0, "b": 1.0, "c": 2.0}, {"a": 1.0, "b": 1.0, "c": 0.0}
    )
    close(d["mean"], 4 / 3)
    assert d["positive"] == 2 and d["n"] == 3


def test_midranks():
    # Mostly-tied columns must not acquire a spurious correlation from row order.
    a = np.r_[np.zeros(98), 1, 1]
    b = np.r_[1, 1, np.zeros(98)]
    names, M = midrank_spearman_matrix({"a": a, "b": b})
    assert M[0, 1] < 0  # the non-zero rows never coincide
    r = within_unit_ranks([0, 0, 0, 1])
    close(r[0], r[1])  # ties share a rank


def test_shortest_window():
    p = np.zeros(10)
    p[[2, 3]] = 0.3
    p[8] = 0.4
    C = np.concatenate([[0.0], np.cumsum(p)])[None, :]
    assert shortest_window(C, 0.5)[0] == 2
    assert shortest_window(C, 0.8)[0] == 7


def test_log_loss_bits():
    close(log_loss_bits(0.5), 1.0)
    close(log_loss_bits(0.25), 2.0)
    close(log_loss_bits(1.0), 0.0)
    for bad in (0.0, -0.1, float("nan"), 1.5):
        try:
            log_loss_bits(bad)
        except ValueError:
            continue
        raise AssertionError(f"log_loss_bits accepted {bad}")


def test_cluster_bootstrap_resamples_clusters():
    # Cluster A: 100 rows of 0; cluster B: 1 row of 1; cluster C: 1 row of 1. The statistic is
    # the mean of cluster means, so every resample mean is k/3 for k in 0..3. A row bootstrap
    # would give values near 2/102 instead.
    clusters = {"A": [0.0] * 100, "B": [1.0], "C": [1.0]}
    ci = cluster_bootstrap(clusters, lambda cs: float(np.mean([np.mean(c) for c in cs])), n=2000)
    for x in ci:
        assert min(abs(x - k / 3) for k in range(4)) < 1e-12, ci
    assert cluster_bootstrap({"A": [1.0], "B": [2.0]}, lambda cs: 0.0) is None


def test_binary_f1():
    # tp = 1, fp = 1, fn = 1 -> 2 / 4
    close(binary_f1([1, 1, 0, 0], [1, 0, 1, 0]), 0.5)
    close(binary_f1([1, 0], [1, 0]), 1.0)
    assert np.isnan(binary_f1([0, 0], [0, 0]))


def test_tfidf_cosine():
    # Two documents {a, b} and {c}; N = 2, df = 1 for every word, so idf = ln(3/2) + 1 for all and
    # tf weights are 1. Query [a] is a unit vector on a; doc 1 is (a + b)/sqrt(2): cosine 1/sqrt 2.
    cos = tfidf_cosine(["a"], {1: ["a", "b"], 2: ["c"]})
    close(cos[1], 1 / np.sqrt(2))
    close(cos[2], 0.0)
    close(tfidf_cosine(["zzz"], {1: ["a"]})[1], 0.0)  # unknown words carry no weight


def test_sign_flip_p():
    # n = 3, all differences +1: only the all-plus and all-minus flips reach |mean| = 1 -> 2/8
    close(sign_flip_p([1.0, 1.0, 1.0]), 0.25)
    # d = [1, -1]: every flip has |mean| 0 or 1; observed 0 -> all 4 are as extreme -> 1
    close(sign_flip_p([1.0, -1.0]), 1.0)
    # ten positive differences: 2 / 1024
    close(sign_flip_p([1.0] * 10), 2 / 1024)
    # one-sided: five positive differences -> only the all-plus assignment reaches the mean: 1/32
    close(sign_flip_p([1.0, 2.0, 0.5, 3.0, 1.5], "greater"), 1 / 32)
    # one-sided: all negative -> every assignment is >= the observed mean: 1
    close(sign_flip_p([-1.0, -1.0, -1.0], "greater"), 1.0)


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
