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

from metrics import tfidf_cosine


@dataclass(frozen=True)
class Forecast:
    work: dict  # work -> p
    scene: dict  # work -> {scene: p}

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
    """The joint mixture (1 - lam) a(s, w) + lam b(s, w), returned as P(w) and P(s | w).

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
    return Forecast(work, scene)


def duration_prior(comp, cfg) -> Forecast:
    secs = comp.scene_seconds()
    valid = valid_scenes(comp, cfg)
    work = _norm({w: math.fsum(secs[s] for s in ss) for w, ss in valid.items() if ss})
    scene = {w: _norm({s: secs[s] for s in ss}) for w, ss in valid.items() if ss}
    return Forecast(work, scene)


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
    return Forecast(work, scene)


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
            out.append(mix(Forecast(work, scene), dur, lam))
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
        out.append(mix(Forecast(work, scene), dur, lam))
    return out
