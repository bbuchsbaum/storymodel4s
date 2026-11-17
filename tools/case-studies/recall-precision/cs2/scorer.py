"""The case-study-2 yardstick: hierarchical log loss on gold rows, skill over frozen reference
forecasts, participant-cluster bootstrap, strata, and refusal of metrics the gold cannot validate.

Loss of one gold row, in bits, three declared conditional terms:
  -log2 P(film level) - log2 P(work | film) - log2 P(scene set | work).
- The film term is scored on every corpus: -log2 P(on film) for film-coded rows (specific,
  film-only, film-search) and -log2 P(non-film) for non-film rows. It closes the hedge a
  conditional scene term leaves open: P(S | w) renormalizes non-film and external mass away.
- P(work | film) is P(w) / sum of P(w') over works.
- P(scene set | work) is the forecast's mass on the gold set within the work (declared set rule).
- Specific rows score all terms; film-only and film-search rows score film and work terms;
  non-film rows score the film term only.
- When work identity is not applicable (one-work corpora such as Sherlock) the work term is
  reported as n/a, never as 0.

Producer forecast from one scene-grain LocationDistribution row (declared, config-frozen):
- abstain mass a = unassigned + position-unresolved + held-out-without-work; claimed c = 1 - a;
- with epsilon e, claimed mass is scaled by (1 - e) and u = 1 - (1 - e) c is redistributed by
  the fallback forecast F (config 'fallback', default the duration prior);
- P(w)        = (1 - e)[loc(w) + spread(w) + outside(w)] + u F(w)
- P(S and w)  = (1 - e)[loc(S, w) + spread(w) F(S | w)] + u F(w) F(S | w)
  where loc is scene mass, spread is work-level, position-resolved, work-node (broad over
  several scenes of w) and held-out mass of w (spread
  within w by F), and outside is in-work-outside-stimulus mass (no scene of the stimulus).
- Non-film and external mass are claims that the row is not this work; at the film level
  P(non-film) = (1 - e) nonwork + u F(non-film), with F(non-film) the declared fallback rate.
So a producer that abstains on every word scores the fallback exactly (to float precision).
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from metrics import auroc, binary_f1, cluster_bootstrap, log_loss_bits

from .projections import LocationDistribution, ProjectionError

CONFIG = Path(__file__).resolve().parent / "scorer-config.json"

REFUSED = {
    "within_scene_seconds": "the gold codes scenes, not seconds within a scene",
    "breadth": "the gold does not code how much film a reference spans",
    "joint_vs_alternative": "the gold set does not say whether scenes are meant jointly",
    "anchors": "the gold does not code which words carry a reference",
    "sub_top_weights": "the gold validates at most the top hypothesis, no weight below it",
}


class UnvalidatableMetric(ValueError):
    pass


class ScoreError(ValueError):
    pass


def load_config(path=CONFIG) -> dict:
    return json.loads(Path(path).read_text())


def request_metric(name: str):
    """Refuse a metric the gold coding cannot validate; return the name otherwise."""
    if name in REFUSED:
        raise UnvalidatableMetric(f"{name}: {REFUSED[name]}")
    return name


# ---------------------------------------------------------------- producer forecast


@dataclass
class ProducerForecast:
    loc: dict  # scene -> mass
    spread: dict  # work -> mass spread within the work by the fallback
    outside: dict  # work -> in-work-outside-stimulus mass
    nonwork: float  # non-film + external
    abstain: float
    fallback: object
    eps: float
    comp: object
    nonfilm: dict = field(default_factory=dict)
    no_sample: bool = False  # True for the typed absences (no sample, only uncovered, ...)
    empty: str = ""  # which typed absence, '' when the row was sampled
    sets: dict = field(default_factory=dict)  # (work, scenes) -> mass on a scene set

    @property
    def u(self):
        return 1 - (1 - self.eps) * (1 - self.abstain)

    def p_nonfilm(self):
        return (1 - self.eps) * self.nonwork + self.u * self.fallback.p_nonfilm()

    def p_work(self, w):
        own = math.fsum(
            m for s, m in self.loc.items() if self.comp.work_of_scene(s) == w
        )
        sets = math.fsum(m for (sw, _), m in self.sets.items() if sw == w)
        claimed = own + self.spread.get(w, 0.0) + self.outside.get(w, 0.0) + sets
        return (1 - self.eps) * claimed + self.u * self.fallback.p_work(w)

    def p_set_and_work(self, scenes, w):
        fs = self.fallback.p_scene_given_work(w)
        f_set = math.fsum(fs.get(s, 0.0) for s in scenes)
        own = math.fsum(
            self.loc.get(s, 0.0) for s in scenes if self.comp.work_of_scene(s) == w
        )
        # declared: scene-set mass is spread within its set by the fallback, restricted to it
        set_part = 0.0
        for (sw, q), m in self.sets.items():
            if sw != w:
                continue
            fq = math.fsum(fs.get(x, 0.0) for x in q)
            set_part += m * (math.fsum(fs.get(x, 0.0) for x in q if x in scenes) / fq
                             if fq > 0 else f_set)
        return (1 - self.eps) * (own + self.spread.get(w, 0.0) * f_set + set_part) + self.u * (
            self.fallback.p_work(w) * f_set
        )

    def located_share(self):
        """Scene-located share of the claimed mass (NaN when nothing is claimed)."""
        c = 1 - self.abstain
        return math.fsum(self.loc.values()) / c if c > 0 else float("nan")


def producer_forecast(row, comp, fallback, eps) -> ProducerForecast:
    """From one scene-grain LocationDistribution row."""
    if row.state in ("no_sample", "only_uncovered", "only_non_addressed"):
        return ProducerForecast(
            {}, {}, {}, 0.0, 1.0, fallback, eps, comp, no_sample=True, empty=row.state
        )
    loc, spread, outside, nonfilm, sets = {}, {}, {}, {}, {}
    nonwork = abstain = 0.0
    for key, m in row.mass.items():
        tag = key[0]
        if tag == "scene_set":
            sets[(key[1], key[2])] = sets.get((key[1], key[2]), 0.0) + m
        elif tag == "at":
            if key[1] != "scene":
                raise ScoreError("producer rows must be at scene grain")
            loc[key[2]] = loc.get(key[2], 0.0) + m
        elif tag == "unassigned":
            abstain += m
        elif tag in ("work_ref", "position", "held_out", "work_node"):
            w = key[-1]
            if w:
                spread[w] = spread.get(w, 0.0) + m
            else:
                abstain += m
        elif tag == "in_work_outside":
            outside[key[1]] = outside.get(key[1], 0.0) + m
        elif tag == "nonfilm":
            nonwork += m
            nonfilm[key[1]] = nonfilm.get(key[1], 0.0) + m
        elif tag == "external":
            nonwork += m
        else:
            raise ScoreError(f"unknown state {key!r}")
    return ProducerForecast(
        loc, spread, outside, nonwork, abstain, fallback, eps, comp, nonfilm, sets=sets
    )


def producer_forecasts(ld: LocationDistribution, fallback, cfg, row_fallbacks=None) -> list:
    """One producer forecast per row. `row_fallbacks` (one Forecast per row) replaces the
    corpus fallback where given: the frame-film rule (rules.fallback = 'frame-film')."""
    if not isinstance(ld, LocationDistribution):
        raise ProjectionError("not-a-distribution", type(ld).__name__)
    scene = ld.marginal("scene")
    fbs = row_fallbacks or [fallback] * len(scene.rows)
    if len(fbs) != len(scene.rows):
        raise ScoreError("one fallback per row is required")
    return [producer_forecast(r, ld.composition, f, cfg["epsilon"])
            for r, f in zip(scene.rows, fbs)]


# ---------------------------------------------------------------- row scores


@dataclass
class RowScore:
    participant: str
    session: str
    row: int
    cls: str
    work: str | None
    work_bits: float | None
    scene_bits: float | None
    film_bits: float | None = None
    total_bits: float | None = None  # sum of the row's scored terms
    top_work: str | None = None
    top_scene: object | None = None
    scene_hit: bool | None = None
    located_share: float | None = None
    nonwork: float | None = None
    abstain: float | None = None
    nonfilm_label: str | None = None
    transition: bool | None = None
    dropped_scenes: int = 0
    empty: str | None = None  # producer rows: typed absence ('no_sample', 'only_uncovered')


def score_rows(gold_rows, forecasts, comp, valid, work_applicable=True) -> list:
    """Score gold rows of one session (in order) against one forecast per row."""
    if len(gold_rows) != len(forecasts):
        raise ScoreError("one forecast per gold row is required")
    works = [w for w, ss in valid.items() if ss]
    out, prev = [], None
    for g, f in zip(gold_rows, forecasts):
        rs = RowScore(g.participant, g.session, g.row, g.cls, g.work, None, None)
        rs.nonfilm_label = g.nonfilm
        pw = {w: f.p_work(w) for w in works}
        pfilm_works = math.fsum(pw.values())
        pnf = f.p_nonfilm()
        if g.work is not None:
            rs.film_bits = log_loss_bits(1 - pnf)
        elif g.cls == "nonfilm":
            rs.film_bits = log_loss_bits(pnf)
        rs.top_work = max(sorted(pw), key=lambda w: pw[w])
        if isinstance(f, ProducerForecast):
            rs.located_share = f.located_share()
            rs.nonwork = f.nonwork
            rs.abstain = f.abstain
            rs.empty = f.empty
        if g.work is not None:
            rs.transition = prev is not None and g.work != prev
            prev = g.work
            p = pw.get(g.work)
            if p is None:
                raise ScoreError(f"gold work {g.work} has no valid scene")
            if work_applicable:
                rs.work_bits = log_loss_bits(p / pfilm_works)
            if g.specific:
                # declared rule: a gold scene that is not a valid target (a title-only scene) is
                # dropped from the set; a set left empty is not scored on the scene term
                gs = frozenset(s for s in g.scenes if s in set(valid[g.work]))
                rs.dropped_scenes = len(g.scenes) - len(gs)
                if gs:
                    rs.scene_bits = log_loss_bits(f.p_set_and_work(gs, g.work) / p)
                    ps = {s: f.p_set_and_work({s}, g.work) for s in valid[g.work]}
                    rs.top_scene = max(valid[g.work], key=lambda s: ps[s])
                    rs.scene_hit = rs.top_scene in gs
        terms = [t for t in (rs.film_bits, rs.work_bits, rs.scene_bits) if t is not None]
        rs.total_bits = math.fsum(terms) if terms else None
        out.append(rs)
    return out


# ---------------------------------------------------------------- aggregation


def _pmeans(scores, term):
    by = {}
    for s in scores:
        v = getattr(s, term)
        if v is not None:
            by.setdefault(s.participant, []).append(v)
    return {p: float(np.mean(v)) for p, v in by.items()}


def summarize(scores, cfg, term):
    """Participant-average of a term (clusters = participants, sessions pooled)."""
    pm = _pmeans(scores, term)
    if not pm:
        return dict(
            n_participants=0, n_rows=0, mean=None, ci95=None, per_participant={}
        )
    b = cfg["bootstrap"]
    ci = cluster_bootstrap(
        pm, lambda xs: float(np.mean(xs)), n=b["n"], seed=b["seed"], level=b["level"]
    )
    return dict(
        n_participants=len(pm),
        n_rows=sum(1 for s in scores if getattr(s, term) is not None),
        mean=float(np.mean(list(pm.values()))),
        ci95=ci,
        per_participant=pm,
    )


def skill(prod, base, cfg, term):
    """1 - LL(producer) / LL(reference), participant-averaged, on the rows both score."""
    key = lambda s: (s.participant, s.session, s.row)  # noqa: E731
    bmap = {key(s): getattr(s, term) for s in base}
    per = {}
    for s in prod:
        v, r = getattr(s, term), bmap.get(key(s))
        if v is None or r is None:
            continue
        a = per.setdefault(s.participant, [[], []])
        a[0].append(v)
        a[1].append(r)
    if not per:
        return None
    clusters = {p: (float(np.mean(a)), float(np.mean(b))) for p, (a, b) in per.items()}

    def stat(cs):
        den = math.fsum(c[1] for c in cs)
        return 1 - math.fsum(c[0] for c in cs) / den if den > 0 else float("nan")

    b = cfg["bootstrap"]
    return dict(
        skill=stat(list(clusters.values())),
        ci95=cluster_bootstrap(
            clusters, stat, n=b["n"], seed=b["seed"], level=b["level"]
        ),
        n_participants=len(clusters),
    )


def strata(scores, comp, work_applicable=True):
    """Strata of the design that the gold coding can validate."""
    out = {}
    film = [s for s in scores if s.work is not None]
    spec = [s for s in film if s.cls == "specific"]
    out["rows"] = {
        c: sum(1 for s in scores if s.cls == c)
        for c in ("specific", "film_only", "film_search", "nonfilm")
    }
    if work_applicable:
        for name, sel in (("transition", True), ("within", False)):
            v = [s.work_bits for s in film if s.transition is sel]
            out[f"work_bits_{name}"] = dict(
                n=len(v), mean=float(np.mean(v)) if v else None
            )
        # F1 on film switches, within each session's sequence of work-coded rows
        gold_sw, pred_sw = [], []
        by = {}
        for s in film:
            by.setdefault((s.participant, s.session), []).append(s)
        tol = dict(tp=0, fp=0, fn=0)
        for seq in by.values():
            g = [a.work != b.work for a, b in zip(seq, seq[1:])]
            q = [a.top_work != b.top_work for a, b in zip(seq, seq[1:])]
            gold_sw += g
            pred_sw += q
            # tolerance +-1 row: a predicted switch counts if a gold switch lies within one row
            gi = [i for i, x in enumerate(g) if x]
            qi = [i for i, x in enumerate(q) if x]
            used = set()
            for i in qi:
                hit = next((j for j in gi if abs(j - i) <= 1 and j not in used), None)
                if hit is None:
                    tol["fp"] += 1
                else:
                    used.add(hit)
                    tol["tp"] += 1
            tol["fn"] += len(gi) - len(used)
        den = 2 * tol["tp"] + tol["fp"] + tol["fn"]
        out["film_switch_f1"] = dict(
            n=len(gold_sw), switches=int(sum(gold_sw)), f1=binary_f1(pred_sw, gold_sw),
            f1_tolerance_1_row=(2 * tol["tp"] / den) if den else float("nan"),
        )
        out["top_work_agreement"] = dict(
            n=len(film),
            share=float(np.mean([s.top_work == s.work for s in film]))
            if film
            else None,
        )
    cond = [s for s in spec if s.top_work == s.work] if work_applicable else spec
    out["scene_hit_given_correct_work"] = dict(
        n=len(cond), share=float(np.mean([s.scene_hit for s in cond])) if cond else None
    )
    sp = {}
    for s in spec:
        k = comp.work[s.work].speech
        sp.setdefault(str(k), []).append(s.scene_bits)
    out["scene_bits_by_speech"] = {
        k: dict(n=len(v), mean=float(np.mean(v))) for k, v in sorted(sp.items())
    }
    # declared: located shares are rounded before ranking, so float noise cannot break ties
    nd = load_config()["auroc_round_decimals"]

    def shares(rows, abstain_as=None):
        out_ = []
        for s in rows:
            v = s.located_share
            if v is None or math.isnan(v):
                if abstain_as is None:
                    continue
                v = abstain_as
            out_.append(round(v, nd))
        return out_

    non = [s for s in film if s.cls != "specific"]
    loc_spec, loc_non = shares(spec), shares(non)
    out["localizability"] = dict(
        n_specific_scored=len(loc_spec), n_specific_total=len(spec),
        n_nonspecific_scored=len(loc_non), n_nonspecific_total=len(non),
        note="a row with no claimed mass (full abstention) has no located share and is left "
        "out of 'auroc'; 'auroc_abstention_as_zero' counts it as located share 0",
        located_share_specific=float(np.mean(loc_spec)) if loc_spec else None,
        located_share_nonspecific=float(np.mean(loc_non)) if loc_non else None,
        auroc=auroc(loc_spec, loc_non),
        auroc_abstention_as_zero=auroc(shares(spec, 0.0), shares(non, 0.0)),
    )
    nf = {}
    for s in scores:
        if s.nonwork is None:
            continue
        lab = s.nonfilm_label if s.cls == "nonfilm" else f"gold:{s.cls}"
        nf.setdefault(lab, []).append(s.nonwork)
    nfr = [s for s in scores if s.cls == "nonfilm"]
    by_lab = {}
    for s in nfr:
        by_lab.setdefault(s.nonfilm_label, []).append(s.film_bits)
    out["nonfilm_rows"] = dict(
        n=len(nfr),
        film_bits=float(np.mean([s.film_bits for s in nfr])) if nfr else None,
        by_label={k: dict(n=len(v), film_bits=float(np.mean(v))) for k, v in sorted(by_lab.items())},
    )
    out["nonwork_mass_by_gold_label"] = {
        k: dict(n=len(v), mean=float(np.mean(v))) for k, v in sorted(nf.items())
    }
    ab = [s.abstain for s in scores if s.abstain is not None]
    out["fallback_mass"] = dict(n=len(ab), mean=float(np.mean(ab)) if ab else None)
    em = [s.empty for s in scores if s.empty is not None]
    if em:
        out["typed_absence"] = {
            k: dict(n=sum(1 for e in em if e == k), share=sum(1 for e in em if e == k) / len(em))
            for k in ("no_sample", "only_uncovered", "only_non_addressed")
        }
        sampled = [s.abstain for s in scores if s.empty == ""]
        out["fallback_mass_sampled_rows"] = dict(
            n=len(sampled), mean=float(np.mean(sampled)) if sampled else None
        )
    return out
