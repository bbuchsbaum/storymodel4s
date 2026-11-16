"""Known-answer tests for the case-study metrics (run: python test_metrics.py)."""

import numpy as np

from precision1 import auroc, shortest_window


def test_auroc_ties():
    # 6/10 positives score 1, 4/10 negatives score 1: P(pos>neg)+.5P(tie) = .6*.6 + .5*(.6*.4+.4*.6)... computed exactly:
    pos, neg = [1] * 6 + [0] * 4, [1] * 4 + [0] * 6
    exact = np.mean([(p > n) + 0.5 * (p == n) for p in pos for n in neg])
    assert abs(auroc(pos, neg) - exact) < 1e-12, (auroc(pos, neg), exact)
    assert auroc([1] * 50, [1] * 50) == 0.5
    assert auroc([3, 4], [1, 2]) == 1.0


def test_shortest_window():
    p = np.zeros(10)
    p[[2, 3]] = 0.3
    p[8] = 0.4
    C = np.concatenate([[0.0], np.cumsum(p)])[None, :]
    assert shortest_window(C, 0.5)[0] == 2  # seconds 2-3 hold 0.6
    assert shortest_window(C, 0.8)[0] == 7  # seconds 2..8


if __name__ == "__main__":
    test_auroc_ties()
    test_shortest_window()
    print("ok")
