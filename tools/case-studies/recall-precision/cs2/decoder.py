"""Sequence decoding over a forecast's own row distributions (hill-climbing round 4).

The content-local forecast and the order-informed forecast are different estimands (ADR 0031
section 4). This module turns the first into the second and keeps them apart: its output is a
DecodedForecast, a separate type that says for every row how much of it came from the row's own
evidence and whether its top cell was observed or inferred. It never writes to an account.

Model, one session of gold rows in order:
- A state is a position and a mode. A position is a valid scene of a work, or START before any
  film is recalled. A mode is film, search (the between-works memory search) or other (gave up,
  off task, external). A non-film row does not move the position.
- Cells are the scored simplex: one per valid (work, scene), plus search and other.
- Evidence of a row for a cell is (q / d) ** alpha: q the forecast's row distribution on the
  cells, d the participant's duration forecast on the same cells. A non-film state at a position
  inside a work also takes that work's ratio q(work | film) / d(work | film): a non-film row
  keeps its frame, so the frame is evidence about the position, not about the mode.
- Transitions are fitted leave-one-participant-out on other participants' gold sequences.
- The output is the smoothed posterior on the cells (forward-backward).
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from . import baselines as B
from . import scorer as SC

MODES = ("film", "search", "other")
SEARCH_LABEL = "memory_search"
EM_ITERATIONS = 100


class DecodeError(ValueError):
    pass


# ---------------------------------------------------------------- cells


@dataclass(frozen=True)
class Space:
    """Valid scenes grouped by work, works in presentation order."""

    works: tuple
    scenes: tuple  # every valid scene, work by work
    work_of: tuple  # scene index -> work index
    ordinal: tuple  # scene index -> ordinal within its work
    start: tuple  # work index -> index of its first scene
    size: tuple  # work index -> number of valid scenes

    @property
    def n(self):
        return len(self.scenes)

    def index(self):
        return {s: i for i, s in enumerate(self.scenes)}


def space(comp, cfg) -> Space:
    valid = B.valid_scenes(comp, cfg)
    works = tuple(w for w in B.works_in_order(comp) if valid.get(w))
    scenes, work_of, ordinal, start, size = [], [], [], [], []
    for wi, w in enumerate(works):
        start.append(len(scenes))
        size.append(len(valid[w]))
        for j, s in enumerate(valid[w]):
            scenes.append(s)
            work_of.append(wi)
            ordinal.append(j)
    return Space(
        works, tuple(scenes), tuple(work_of), tuple(ordinal), tuple(start), tuple(size)
    )


def nonfilm_split(f, nu):
    """(search, other) parts of the forecast's P(non-film). The claimed part is split by the
    producer's own label; the fallback part by nu, the share of memory search among non-film
    gold rows."""
    if isinstance(f, SC.ProducerForecast):
        s = (1 - f.eps) * f.nonfilm.get(SEARCH_LABEL, 0.0) + (
            f.u * f.fallback.p_nonfilm()
        ) * nu
        return s, f.p_nonfilm() - s
    if isinstance(f, SC.EnsembleForecast):
        parts = [nonfilm_split(m, nu) for m in f.members]
        return tuple(math.fsum(p[i] for p in parts) / len(parts) for i in (0, 1))
    pnf = f.p_nonfilm()
    s = pnf * nu
    return s, pnf - s


def cells(f, sp: Space, dur, nu) -> tuple:
    """(q, residual): the forecast on the cells (n scene cells, then search and other), and the
    in-work mass that sat on no valid scene (a title-only scene, or in-work outside the
    stimulus). That mass is work-level evidence: it is spread within its work by the duration
    prior, as the scorer spreads work-level mass."""
    pw = [f.p_work(w) for w in sp.works]
    tot = math.fsum(pw)
    pnf = f.p_nonfilm()
    q = np.empty(sp.n + 2)
    residual = 0.0
    for wi, w in enumerate(sp.works):
        a, m = sp.start[wi], sp.size[wi]
        ps = [f.p_set_and_work({s}, w) for s in sp.scenes[a : a + m]]
        r = pw[wi] - math.fsum(ps)
        if r > 0:
            ds = dur.scene[w]
            ps = [p + r * ds[s] for p, s in zip(ps, sp.scenes[a : a + m])]
            residual += r / tot
        for j, p in enumerate(ps):
            q[a + j] = (1 - pnf) * p / tot
    q[sp.n], q[sp.n + 1] = nonfilm_split(f, nu)
    return q, residual


# ---------------------------------------------------------------- fitting


def mode_of(r) -> int:
    if r.work is not None:
        return 0
    return 1 if r.nonfilm == SEARCH_LABEL else 2


def _top(r, valid):
    inside = [s for s in valid.get(r.work, []) if s in r.scenes]
    return inside[-1] if inside else None


def _em_scene(data, a):
    """Stay, geometric forward step, uniform jump: (k, i, n) = step, ordinal, scenes in work."""
    ps = pf = pj = 1 / 3
    rho = 0.5
    data = [d for d in data if d[2] > 1]
    for _ in range(EM_ITERATIONS):
        n_s = n_f = n_j = cont = stop = 0.0
        for k, i, n in data:
            ahead = n - 1 - i
            if k == 0:
                if ahead > 0:
                    n_s += 1
                else:  # at the last scene the forward mass stays: not told apart
                    n_s += ps / (ps + pf)
                    n_f += pf / (ps + pf)
            elif k < 0:
                n_j += 1
            else:
                g = pf * (1 - rho) * rho ** (k - 1) / (1 - rho**ahead)
                u = pj / (n - 1)
                r = g / (g + u)
                n_f += r
                n_j += 1 - r
                cont += r * (k - 1)
                stop += r
        tot = n_s + n_f + n_j + 3 * a
        ps, pf, pj = (n_s + a) / tot, (n_f + a) / tot, (n_j + a) / tot
        rho = (cont + a) / (cont + stop + 2 * a)
    return dict(stay=ps, forward=pf, jump=pj, rho=rho, n=len(data))


def _em_entry(data, a):
    """Entry ordinal j in a work of n scenes: geometric from the first scene, or uniform."""
    kappa, rho = 0.5, 0.5
    data = [d for d in data if d[1] > 1]
    for _ in range(EM_ITERATIONS):
        n_u = cont = stop = 0.0
        for j, n in data:
            g = (1 - kappa) * (1 - rho) * rho**j / (1 - rho**n)
            u = kappa / n
            r = g / (g + u)
            n_u += 1 - r
            cont += r * j
            stop += r
        kappa = (n_u + a) / (len(data) + 2 * a)
        rho = (cont + a) / (cont + stop + 2 * a)
    return dict(uniform=kappa, rho=rho, n=len(data))


def fit(comp, gold_by_participant: dict, participant, cfg) -> dict:
    """Transition rates from the gold sequences of every participant but `participant` (the
    scored participant never enters its own rates; None fits on all, for the record only)."""
    a = cfg["lopo_alpha"]
    valid = B.valid_scenes(comp, cfg)
    order = [w for w in B.works_in_order(comp) if valid.get(w)]
    init = [0, 0, 0]
    mode = [[0, 0, 0] for _ in MODES]
    work = {m: dict(same=0, next=0, other=0) for m in MODES}
    start = dict(next=0, other=0)
    steps, entries = [], []
    n_search = n_nonfilm = 0
    for p, rows in gold_by_participant.items():
        if p == participant:
            continue  # the scored participant never enters its own rates
        by = {}
        for r in rows:
            by.setdefault(r.session, []).append(r)
        for rs in by.values():
            prev_mode = pos_work = top = None
            for r in rs:
                m = mode_of(r)
                n_nonfilm += m != 0
                n_search += m == 1
                if prev_mode is None:
                    init[m] += 1
                else:
                    mode[prev_mode][m] += 1
                if m == 0:
                    if pos_work is None:
                        start["next" if r.work == order[0] else "other"] += 1
                    else:
                        i = order.index(pos_work)
                        nxt = order[i + 1] if i + 1 < len(order) else None
                        out = (
                            "same"
                            if r.work == pos_work
                            else "next"
                            if r.work == nxt
                            else "other"
                        )
                        work[MODES[prev_mode]][out] += 1
                    if r.work != pos_work:
                        top = None
                    pos_work = r.work
                    t = _top(r, valid) if r.specific else None
                    if t is not None:
                        j, n = valid[r.work].index(t), len(valid[r.work])
                        if top is None:
                            entries.append((j, n))
                        else:
                            steps.append((j - top, top, n))
                        top = j
                prev_mode = m

    def rates(c):
        tot = sum(c.values()) + len(c) * a
        return {k: (v + a) / tot for k, v in c.items()}

    return dict(
        init=[(c + a) / (sum(init) + 3 * a) for c in init],
        mode=[[(c + a) / (sum(row) + 3 * a) for c in row] for row in mode],
        work={m: rates(c) for m, c in work.items()},
        start=rates(start),
        scene=_em_scene(steps, a),
        entry=_em_entry(entries, a),
        nu=(n_search + a) / (n_nonfilm + 2 * a),
        counts=dict(
            init=init,
            mode=mode,
            work=work,
            start=start,
            steps=len(steps),
            entries=len(entries),
            nonfilm=n_nonfilm,
            search=n_search,
        ),
    )


# ---------------------------------------------------------------- transitions


def _scene_kernel(n, r):
    """n x n: stay, geometric forward step, uniform jump over the work's other scenes."""
    k = np.zeros((n, n))
    if n == 1:
        k[0, 0] = 1.0
        return k
    for i in range(n):
        ahead = n - 1 - i
        k[i] = r["jump"] / (n - 1)
        k[i, i] = r["stay"]
        if ahead == 0:
            k[i, i] += r[
                "forward"
            ]  # the last scene has no step ahead: its forward mass stays
            continue
        g = r["rho"] ** np.arange(ahead)
        k[i, i + 1 :] += r["forward"] * g / g.sum()
    return k


def _entry(n, r):
    g = r["rho"] ** np.arange(n)
    return (1 - r["uniform"]) * g / g.sum() + r["uniform"] / n


def _avail(w, d):
    """Work outcomes that exist, renormalised: {'same' | 'next' | 'other': p}."""
    tot = math.fsum(v for k, v in w.items() if d[k])
    return {k: (v / tot if d[k] else 0.0) for k, v in w.items()}


def transition(sp: Space, r: dict) -> tuple:
    """(T, init) over states ordered film (n), search (n + START), other (n + START)."""
    n, nw = sp.n, len(sp.works)
    entry = [_entry(m, r["entry"]) for m in sp.size]
    scene = [_scene_kernel(m, r["scene"]) for m in sp.size]

    def enter(row, wi, p):
        row[sp.start[wi] : sp.start[wi] + sp.size[wi]] += p * entry[wi]

    st = _avail(r["start"], dict(next=True, other=nw > 1))
    k_start = np.zeros(n)
    enter(k_start, 0, st["next"])
    for wi in range(1, nw):
        enter(k_start, wi, st["other"] / (nw - 1))
    kern = {}
    for m in MODES:
        k = np.zeros((n, n))
        for wi in range(nw):
            others = [x for x in range(nw) if x not in (wi, wi + 1)]
            w = _avail(
                r["work"][m], dict(same=True, next=wi + 1 < nw, other=bool(others))
            )
            a, size = sp.start[wi], sp.size[wi]
            k[a : a + size, a : a + size] = w["same"] * scene[wi]
            for i in range(a, a + size):
                if w["next"]:
                    enter(k[i], wi + 1, w["next"])
                for x in others:
                    enter(k[i], x, w["other"] / len(others))
        kern[m] = k
    size = 3 * n + 2
    T = np.zeros((size, size))
    s0, o0 = n, 2 * n + 1  # search block n..2n (START at 2n); other block 2n+1..3n+1
    eye, eye1 = np.eye(n), np.eye(n + 1)
    M = r["mode"]
    T[:n, :n] = M[0][0] * kern["film"]
    T[:n, s0 : s0 + n] = M[0][1] * eye
    T[:n, o0 : o0 + n] = M[0][2] * eye
    for mi, (m, b) in enumerate((("search", s0), ("other", o0)), start=1):
        T[b : b + n, :n] = M[mi][0] * kern[m]
        T[b + n, :n] = M[mi][0] * k_start
        T[b : b + n + 1, s0 : s0 + n + 1] = M[mi][1] * eye1
        T[b : b + n + 1, o0 : o0 + n + 1] = M[mi][2] * eye1
    init = np.zeros(size)
    init[:n] = r["init"][0] * k_start
    init[s0 + n] = r["init"][1]
    init[o0 + n] = r["init"][2]
    return T, init


# ---------------------------------------------------------------- decoding


@dataclass
class DecodedForecast:
    """A row's smoothed posterior on the cells. An order-informed forecast, never observed
    evidence: `top_from` says whether its top cell is the top of the row's own evidence
    ('evidence') or was supplied by its neighbours ('neighbours')."""

    sp: Space
    post: np.ndarray
    index: dict
    kl_own_bits: float | None = None  # KL(posterior || the row's own distribution)
    kl_neighbours_bits: float | None = (
        None  # KL(posterior || posterior without own evidence)
    )
    own_share: float | None = (
        None  # kl_neighbours / (kl_own + kl_neighbours); None if both 0
    )
    top_from: str = "neighbours"

    def p_nonfilm(self):
        return float(self.post[self.sp.n] + self.post[self.sp.n + 1])

    def p_work(self, w):
        wi = self.sp.works.index(w)
        a = self.sp.start[wi]
        return float(self.post[a : a + self.sp.size[wi]].sum())

    def p_set_and_work(self, scenes, w):
        wi = self.sp.works.index(w)
        return float(
            math.fsum(
                self.post[self.index[s]]
                for s in scenes
                if s in self.index and self.sp.work_of[self.index[s]] == wi
            )
        )


def likelihood(sp: Space, q, d, alpha) -> np.ndarray:
    """Rows x states. Scene cells take (q / d) ** alpha; a non-film state takes its mode's ratio
    and, at a position inside a work, that work's ratio q(work | film) / d(work | film)."""
    n = sp.n
    q, d2 = np.atleast_2d(q), np.atleast_2d(d)
    ratio = q / d
    wo = np.array(sp.work_of)
    work_ratio = np.empty((len(q), n))
    for wi in range(len(sp.works)):
        a, m = sp.start[wi], sp.size[wi]
        # q and d go through the same reductions, so equal rows give a ratio of exactly 1
        qw = q[:, a : a + m].sum(1) / q[:, :n].sum(1)
        dw = d2[:, a : a + m].sum(1) / d2[:, :n].sum(1)
        work_ratio[:, wo == wi] = (qw / dw)[:, None]
    L = np.empty((len(q), 3 * n + 2))
    L[:, :n] = ratio[:, :n]
    for b, c in ((n, n), (2 * n + 1, n + 1)):
        L[:, b : b + n] = ratio[:, [c]] * work_ratio
        L[:, b + n] = ratio[:, c]
    return L**alpha


def forward_backward(T, init, L) -> tuple:
    """(posterior, neighbour-only posterior) per row over states, scaled at every step. The
    neighbour-only posterior leaves out the row's own evidence."""
    n, size = L.shape
    pred = np.empty((n, size))
    alpha = np.empty((n, size))
    p = init
    for t in range(n):
        pred[t] = p
        a = p * L[t]
        z = a.sum()
        if not z > 0:
            raise DecodeError(f"row {t}: no state has positive mass")
        alpha[t] = a / z
        p = alpha[t] @ T
    beta = np.ones((n, size))
    for t in range(n - 2, -1, -1):
        b = T @ (L[t + 1] * beta[t + 1])
        beta[t] = b / b.sum()
    post = alpha * beta
    nbr = pred * beta
    return post / post.sum(1, keepdims=True), nbr / nbr.sum(1, keepdims=True)


def to_cells(sp: Space, x) -> np.ndarray:
    n = sp.n
    return np.concatenate(
        [
            x[:, :n],
            x[:, n : 2 * n + 1].sum(1, keepdims=True),
            x[:, 2 * n + 1 :].sum(1, keepdims=True),
        ],
        axis=1,
    )


def _kl_bits(p, q):
    m = p > 0
    return float(np.sum(p[m] * np.log2(p[m] / q[m])))


def decode(sp: Space, T, init, q, d, alpha) -> list:
    """One DecodedForecast per row. q: rows x cells, the forecast's own row distributions, or
    an int n for n rows of flat evidence (the order prior alone); d: the duration forecast on
    the cells."""
    flat = isinstance(q, int)
    L = np.ones((q, 3 * sp.n + 2)) if flat else likelihood(sp, q, d, alpha)
    post, nbr = forward_backward(T, init, L)
    post, nbr = to_cells(sp, post), to_cells(sp, nbr)
    index = sp.index()
    out = []
    for t in range(len(post)):
        f = DecodedForecast(sp, post[t], index)
        if not flat:
            f.kl_own_bits = _kl_bits(post[t], q[t])
            f.kl_neighbours_bits = _kl_bits(post[t], nbr[t])
            tot = f.kl_own_bits + f.kl_neighbours_bits
            f.own_share = f.kl_neighbours_bits / tot if tot > 0 else None
            f.top_from = (
                "evidence"
                if int(np.argmax(post[t])) == int(np.argmax(q[t]))
                else "neighbours"
            )
        out.append(f)
    return out
