"""Frozen, corpus-specific reference forecasts for the case-study-2 yardstick.

A forecast gives P(work) over the corpus's works and P(scene | work) over each work's valid
scenes (title-only scenes excluded when the config says so: the coding scheme codes a title
mention as film-only). Every baseline is strictly positive on valid scenes.

- duration: P(work) and P(scene | work) proportional to seconds of valid scenes.
- lopo: leave-one-participant-out gold marginal, additive smoothing alpha; the scored
  participant's rows never enter its own forecast.
- order: an ORACLE that reads the previous gold row (the previous row's work, and its highest
  scene or the one after), mixed with the duration prior by lambda. No producer sees this.
- tfidf: cosine between the row's recall words and each valid scene's annotation text, mixed
  with the duration prior by lambda.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass

import numpy as np

from metrics import tfidf_cosine


@dataclass(frozen=True)
class Forecast:
    work: dict  # work -> p (sums to 1 over works: P(work | film))
    scene: dict  # work -> {scene: p}
    nonfilm: float = 0.0  # P(non-film), the film-vs-non-film level

    def p_nonfilm(self):
        return self.nonfilm

    def p_work(self, w):
        return self.work.get(w, 0.0)

    def p_set_and_work(self, scenes, w):
        sw = self.scene.get(w, {})
        return self.p_work(w) * math.fsum(sw.get(s, 0.0) for s in scenes)

    def p_scene_given_work(self, w):
        return self.scene.get(w, {})


def valid_scenes(comp, cfg) -> dict:
    drop = comp.title_only_scenes() if cfg["exclude_title_only_scenes"] else set()
    return {
        w.id: [s for s in comp.scenes_of_work(w.id) if s not in drop]
        for w in comp.works
    }


def _norm(d):
    t = math.fsum(d.values())
    return {k: v / t for k, v in d.items()}


def mix(a: Forecast, b: Forecast, lam: float) -> Forecast:
    """The joint mixture (1 - lam) a(s, w) + lam b(s, w), returned as P(w) and P(s | w);
    the non-film level is mixed the same way.

    Where `a` gives a work no scene distribution, its scene distribution is taken to be `b`'s."""
    work, scene = {}, {}
    for w, bw in b.work.items():
        aw = a.work.get(w, 0.0)
        pw = (1 - lam) * aw + lam * bw
        work[w] = pw
        bs = b.scene[w]
        as_ = a.scene.get(w, bs)
        scene[w] = {
            s: ((1 - lam) * aw * as_.get(s, 0.0) + lam * bw * p) / pw for s, p in bs.items()
        }
    return Forecast(work, scene, (1 - lam) * a.nonfilm + lam * b.nonfilm)


def duration_prior(comp, cfg) -> Forecast:
    secs = comp.scene_seconds()
    valid = valid_scenes(comp, cfg)
    work = _norm({w: math.fsum(secs[s] for s in ss) for w, ss in valid.items() if ss})
    scene = {w: _norm({s: secs[s] for s in ss}) for w, ss in valid.items() if ss}
    return Forecast(work, scene, cfg["nonfilm_fallback_rate"])


def lopo(comp, gold_by_participant: dict, participant: str, cfg) -> Forecast:
    alpha = cfg["lopo_alpha"]
    valid = valid_scenes(comp, cfg)
    works = [w for w, ss in valid.items() if ss]
    wc = {w: 0.0 for w in works}
    sc = {w: {s: 0.0 for s in valid[w]} for w in works}
    for p, rows in gold_by_participant.items():
        if p == participant:
            continue  # the scored participant never enters its own forecast
        for r in rows:
            if r.work is None or r.work not in wc:
                continue
            wc[r.work] += 1
            if r.specific:
                inside = [s for s in r.scenes if s in sc[r.work]]
                for s in inside:
                    sc[r.work][s] += 1 / len(inside)
    work = _norm({w: c + alpha for w, c in wc.items()})
    scene = {w: _norm({s: c + alpha for s, c in d.items()}) for w, d in sc.items()}
    return Forecast(work, scene, cfg["nonfilm_fallback_rate"])


def order_oracle(comp, rows: list, cfg, dur: Forecast) -> list:
    """One forecast per gold row of one session, rows in session order."""
    lam = cfg["order_oracle_lambda"]
    valid = valid_scenes(comp, cfg)
    out, prev_work, prev_top = [], None, None
    for r in rows:
        if prev_work is None:
            out.append(dur)
        else:
            work = {w: 1.0 if w == prev_work else 0.0 for w in dur.work}
            scene = {}
            ss = valid.get(prev_work, [])
            if prev_top is not None and prev_top in ss:
                i = ss.index(prev_top)
                nxt = ss[i + 1] if i + 1 < len(ss) else prev_top
                d = {s: 0.0 for s in ss}
                d[prev_top] += 0.5
                d[nxt] += 0.5
                scene[prev_work] = d
            out.append(mix(Forecast(work, scene, dur.nonfilm), dur, lam))
        if r.work is not None:
            if r.work != prev_work:
                prev_top = None
            prev_work = r.work
            if r.specific:
                inside = [s for s in valid.get(r.work, []) if s in r.scenes]
                if inside:
                    prev_top = inside[-1]
    return out


TOKEN = re.compile(r"[a-z']+")
STOP = set(
    "a an the and or but of to in on at for with from by as is are was were be been being it its "
    "this that these those he she they them his her their him i you we me my our your so then "
    "there here like just um uh yeah okay oh well really kind sort think know remember guess "
    "don't not no do does did have has had would could can will got get gets going goes go went "
    "one two some what which who when where how up down out into over about also very".split()
)


def tokens(text: str) -> list:
    return [w for w in TOKEN.findall(text.lower()) if w not in STOP and len(w) > 1]


def scene_tokens(comp, unit_texts: dict, cfg) -> dict:
    """Valid scene -> tokens of its units' annotation text (held in memory only)."""
    valid = {s for ss in valid_scenes(comp, cfg).values() for s in ss}
    docs: dict = {}
    for u in comp.units:
        if u.scene in valid:
            docs.setdefault(u.scene, []).extend(tokens(unit_texts.get(u.id, "")))
    return docs


def tfidf(comp, row_words: list, docs: dict, cfg, dur: Forecast) -> list:
    """One forecast per row from its recall words (list of token lists)."""
    lam = cfg["tfidf_lambda"]
    out = []
    for q in row_words:
        cos = tfidf_cosine(q, docs) if q else {}
        tot = math.fsum(cos.values())
        if tot <= 0:
            out.append(dur)
            continue
        work, scene = {}, {}
        for w, sw in dur.scene.items():
            m = math.fsum(cos.get(s, 0.0) for s in sw)
            work[w] = m / tot
            if m > 0:
                scene[w] = {s: cos.get(s, 0.0) / m for s in sw}
        out.append(mix(Forecast(work, scene, dur.nonfilm), dur, lam))
    return out


# ---------------------------------------------------------------- revision 2026-11-17


def with_nonfilm(f: Forecast, rate: float) -> Forecast:
    return Forecast(f.work, f.scene, rate)


def lopo_nonfilm_rate(gold_by_participant: dict, participant: str, cfg) -> float:
    """Leave-one-participant-out non-film rate, additively smoothed (alpha over two outcomes).
    The scored participant's rows never enter it."""
    a = cfg["lopo_nonfilm_alpha"]
    n = k = 0
    for p, rows in gold_by_participant.items():
        if p == participant:
            continue
        for r in rows:
            n += 1
            k += r.cls == "nonfilm"
    return (k + a) / (n + 2 * a)


def frame_fallback(dur: Forecast, mix: dict, floor: float = 0.0) -> Forecast:
    """Fallback that spreads unassigned mass over the producer's own frame film first, then by
    duration within it: P(w) = mix(w) + (1 - sum mix) dur(w); scenes within a work by duration.
    An empty mix is the duration prior itself (the same object, so a null producer is exact)."""
    if not mix:
        return dur
    total = math.fsum(mix.values())
    if total > 1:  # declared: frame weights summing above 1 are normalised to sum to 1
        mix = {w: v / total for w, v in mix.items()}
    rest = max(0.0, 1 - math.fsum(mix.values()))
    # declared floor (config frame_floor): (1 - floor) of the frame fallback plus floor of the
    # duration prior, so every work keeps positive fallback mass when the frame is certain
    work = {w: (1 - floor) * (mix.get(w, 0.0) + rest * p) + floor * p
            for w, p in dur.work.items()}
    return Forecast(work, dur.scene, dur.nonfilm)


def presentation_order(comp, cfg) -> list:
    """Valid scenes in presentation order (occurrence position, then scene id)."""
    valid = valid_scenes(comp, cfg)
    occ = sorted(comp.occurrences, key=lambda o: o.position)
    out, seen = [], set()
    for o in occ:
        for s in sorted({u.scene for u in comp.units if u.occurrence == o.id
                         and u.scene is not None}):
            w = comp.work_of_scene(s)
            if s in valid.get(w, []) and s not in seen:
                seen.add(s)
                out.append(s)
    return out


def works_in_order(comp) -> list:
    out = []
    for o in sorted(comp.occurrences, key=lambda o: o.position):
        if o.work not in out:
            out.append(o.work)
    return out


def _top(r, valid):
    inside = [s for s in valid.get(r.work, []) if s in r.scenes]
    return inside[-1] if inside else None


def fit_gold_context(comp, gold_by_participant, participant, cfg) -> dict:
    """LOPO rates for the fitted gold-context forecast: stay in the previous row's work, move to
    the next work in presentation order; within the same work, stay on the previous top scene,
    move to the next scene. Counted on the other participants' sessions."""
    valid = valid_scenes(comp, cfg)
    order = works_in_order(comp)
    c = dict(n_w=0, stay=0, nxt=0, n_s=0, s_stay=0, s_next=0)
    for p, rows in gold_by_participant.items():
        if p == participant:
            continue
        by = {}
        for r in rows:
            by.setdefault(r.session, []).append(r)
        for rs in by.values():
            prev = None
            for r in rs:
                if r.work is None:
                    continue
                if prev is not None:
                    c["n_w"] += 1
                    c["stay"] += r.work == prev.work
                    i = order.index(prev.work)
                    c["nxt"] += i + 1 < len(order) and r.work == order[i + 1]
                    if r.work == prev.work and r.specific and prev.specific:
                        m, t = _top(prev, valid), _top(r, valid)
                        ss = valid.get(r.work, [])
                        if m is not None and t is not None:
                            c["n_s"] += 1
                            c["s_stay"] += t == m
                            j = ss.index(m)
                            c["s_next"] += j + 1 < len(ss) and t == ss[j + 1]
                prev = r
    a = cfg["lopo_alpha"]
    f = lambda k, n: (k + a) / (n + 3 * a)  # noqa: E731  (three outcomes: stay, next, other)
    return dict(stay=f(c["stay"], c["n_w"]), next=f(c["nxt"], c["n_w"]),
                scene_stay=f(c["s_stay"], c["n_s"]), scene_next=f(c["s_next"], c["n_s"]),
                counts=c)


def gold_context_fitted(comp, rows, cfg, dur: Forecast, rates: dict) -> list:
    """A ceiling that reads the previous gold row, with LOPO-fitted (then frozen) rates."""
    valid = valid_scenes(comp, cfg)
    order = works_in_order(comp)
    out, prev = [], None
    for r in rows:
        if prev is None:
            out.append(dur)
        else:
            i = order.index(prev.work)
            nxt = order[i + 1] if i + 1 < len(order) else None
            rest = 1 - rates["stay"] - (rates["next"] if nxt else 0.0)
            work = {w: rest * p for w, p in dur.work.items()}
            work[prev.work] += rates["stay"]
            if nxt:
                work[nxt] += rates["next"]
            scene = dict(dur.scene)
            m = _top(prev, valid) if prev.specific else None
            ss = valid.get(prev.work, [])
            if m is not None:
                j = ss.index(m)
                n2 = ss[j + 1] if j + 1 < len(ss) else None
                rs = 1 - rates["scene_stay"] - (rates["scene_next"] if n2 else 0.0)
                d = {s: rs * p for s, p in dur.scene[prev.work].items()}
                d[m] += rates["scene_stay"]
                if n2:
                    d[n2] += rates["scene_next"]
                scene[prev.work] = d
            out.append(Forecast(work, scene, dur.nonfilm))
        if r.work is not None:
            prev = r
    return out


def fit_hmm(comp, gold_by_participant, participant, cfg) -> dict:
    """LOPO transition rates over presentation order: stay on the scene, advance to the next
    scene in presentation order; counted on consecutive specific rows of other participants."""
    valid = valid_scenes(comp, cfg)
    order = presentation_order(comp, cfg)
    pos = {s: i for i, s in enumerate(order)}
    n = st = nx = 0
    for p, rows in gold_by_participant.items():
        if p == participant:
            continue
        by = {}
        for r in rows:
            by.setdefault(r.session, []).append(r)
        for rs in by.values():
            prev = None
            for r in rs:
                if not r.specific:
                    continue
                t = _top(r, valid)
                if t is None:
                    continue
                if prev is not None:
                    n += 1
                    st += t == prev
                    nx += pos[t] == pos[prev] + 1
                prev = t
    a = cfg["lopo_alpha"]
    return dict(stay=(st + a) / (n + 3 * a), next=(nx + a) / (n + 3 * a), n=n)


def hmm_tfidf(comp, row_words: list, docs: dict, cfg, dur: Forecast, rates: dict) -> list:
    """A sequential non-oracle forecast: an HMM over scenes in presentation order with TF-IDF
    emissions exp(beta * cosine), filtered on the session's recall words only (row k uses rows
    1..k). Transitions: stay, next scene, or a uniform jump; computed in O(scenes) per row."""
    order = presentation_order(comp, cfg)
    beta = cfg["hmm_beta"]
    n = len(order)
    prior = np.array([dur.work[comp.work_of_scene(s)] * dur.scene[comp.work_of_scene(s)][s]
                      for s in order])
    alpha = prior / prior.sum()
    ps, pn = rates["stay"], rates["next"]
    pj = 1 - ps - pn
    out = []
    for k, q in enumerate(row_words):
        if k:
            shifted = np.concatenate([[0.0], alpha[:-1]])
            shifted[-1] += alpha[-1]  # the last scene has no next scene: its advance stays
            alpha = ps * alpha + pn * shifted + pj * (1 - alpha) / (n - 1)
            alpha = alpha / alpha.sum()
        if q:
            cos = tfidf_cosine(q, docs)
            e = np.exp(beta * np.array([cos.get(s, 0.0) for s in order]))
            alpha = alpha * e
            alpha = alpha / alpha.sum()
        work, scene = {}, {}
        for w in dur.work:
            idx = [i for i, s in enumerate(order) if comp.work_of_scene(s) == w]
            m = float(alpha[idx].sum())
            work[w] = m
            scene[w] = {order[i]: float(alpha[i]) / m for i in idx}
        out.append(Forecast(work, scene, dur.nonfilm))
    return out
