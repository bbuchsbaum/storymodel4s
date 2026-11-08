#!/usr/bin/env python3
"""A3: decode LLM narrative-scene distributions with an order prior (Viterbi over 50 scenes).

Usage: decode.py RUN_NAME [--lopo]
Emission: the LLM's "alts" probabilities, with the leftover mass spread over other scenes and a
floor. Transition: stay / step forward (geometric in distance) / jump back. Fixed a-priori
parameters by default; --lopo picks parameters on 9 participants from a small grid and scores the
held-out tenth, so the reported number is not tuned on the scored participant.
"""

import argparse
import itertools
import json
import math
import statistics

import common as c

S = 50


def emissions(u, floor):
    p = [0.0] * (S + 1)
    used = 0.0
    for sc, pr in u.get("alts") or []:
        if (
            isinstance(sc, int)
            and 1 <= sc <= S
            and isinstance(pr, (int, float))
            and pr > 0
        ):
            p[sc] += pr
            used += pr
    if used == 0 and isinstance(u.get("narrative"), int):
        p[u["narrative"]] = 0.6
        used = 0.6
    rest = max(0.0, 1 - used) / S
    return [math.log(max(p[s] + rest, floor)) for s in range(1, S + 1)]


def trans(stay, fwd, back, decay):
    t = [[0.0] * S for _ in range(S)]
    for i in range(S):
        f = [decay ** (k - 1) for k in range(1, S - i)]
        b = list(range(i))
        zf = sum(f) or 1
        for j in range(S):
            if j == i:
                v = stay
            elif j > i:
                v = fwd * f[j - i - 1] / zf
            else:
                v = back / max(1, len(b))
            t[i][j] = math.log(max(v, 1e-12))
    return t


def viterbi(units, params):
    stay, fwd, back, decay, floor, weight = params
    T = trans(stay, fwd, back, decay)
    em = [[weight * x for x in emissions(u, floor)] for u in units]
    score = [em[0][s] for s in range(S)]
    ptr = []
    for e in em[1:]:
        best, arg = [], []
        for j in range(S):
            k = max(range(S), key=lambda i: score[i] + T[i][j])
            best.append(score[k] + T[k][j] + e[j])
            arg.append(k)
        score, ptr = best, ptr + [arg]
    s = max(range(S), key=lambda i: score[i])
    path = [s]
    for arg in reversed(ptr):
        s = arg[s]
        path.append(s)
    return [x + 1 for x in reversed(path)]


def load(run):
    return {
        p.stem: json.loads(p.read_text())["units"]
        for p in (c.OUT / run).glob("NN*.json")
    }


DEFAULT = (0.6, 0.37, 0.03, 0.5, 1e-3, 1.0)
GRID = list(
    itertools.product(
        [0.5, 0.7], [0.27, 0.37], [0.01, 0.03, 0.1], [0.3, 0.6], [1e-3], [1.0, 2.0]
    )
)


def decode_all(data, params):
    return {
        n: dict(zip([u["id"] for u in us], viterbi(us, params)))
        for n, us in data.items()
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--lopo", action="store_true")
    a = ap.parse_args()
    data = load(a.run)
    raw = {
        n: {u["id"]: u.get("narrative", u.get("scene")) for u in us}
        for n, us in data.items()
    }
    print("raw   ", c.score(raw)["participantAverage"])
    print("fixed ", c.score(decode_all(data, DEFAULT))["participantAverage"])
    if a.lopo:
        g = c.gold()
        names = [n for n in data if any(u["id"] in g for u in data[n])]
        cache = {}
        for pr in GRID:
            dec = decode_all(data, pr)
            cache[pr] = {
                n: c.score({n: dec[n]})["participantAverage"]["exact"] for n in names
            }
        held = {}
        for n in names:
            best = max(
                GRID,
                key=lambda pr: statistics.mean(cache[pr][m] for m in names if m != n),
            )
            held[n] = cache[best][n]
        print("lopo   exact", round(statistics.mean(held.values()), 2))


if __name__ == "__main__":
    main()
