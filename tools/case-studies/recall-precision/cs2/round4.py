"""Hill-climbing round 4 (cache-only, no model calls): the error budget of the content-local
producer, and sequence decoding over its own row distributions.

Protocol: docs/plans/2026-11-16-cs2-mapping-object.md, "Hill-climbing round 4".
Usage (from tools/case-studies/recall-precision):
  python -m cs2.round4 diag OUT_JSON      # step 0: error budget and miss geometry
  python -m cs2.round4 decode OUT_JSON    # step 1: producer_decoded beside producer
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import numpy as np  # noqa: E402

import data as D  # noqa: E402
from cs2 import baselines as B  # noqa: E402
from cs2 import decoder as DC  # noqa: E402
from cs2 import ff_producer as P  # noqa: E402
from cs2 import scorer as SC  # noqa: E402
from cs2.account import Account, Uncovered, WordCell  # noqa: E402
from cs2.hillclimb import C, SAMPLES, build, forecasts  # noqa: E402
from metrics import sign_flip_p  # noqa: E402

from cs2.run import (  # noqa: E402
    WORK_APPLICABLE,
    baseline_forecasts,
    by_session,
    git_state,
    gold_by_participant,
    resolve_rules,
    row_frame_mixes,
    score_map,
    window_length,
    windowed_gold,
)

SHERLOCK_V1 = dict(
    seed=20261122, salt="cs2-sh-abl-v1", sample=1
)  # ablation V1 (part order)
RUNS = (("filmfestival", 1), ("filmfestival", 2), ("sherlock", 1))
WORK = C / "hillclimb-r4"


def build_sherlock(A, sessions, out, rules):
    """The Sherlock part-order run (ablation V1), rebuilt from the call cache."""
    P.CACHE_ONLY = True
    acc, _, log, _ = P.run(
        A,
        sessions,
        None,
        out,
        SHERLOCK_V1["seed"],
        SHERLOCK_V1["salt"],
        SHERLOCK_V1["sample"],
        arm="coverage",
        rules=rules,
        outline_order="part-order",
    )
    assert all(not c["fresh"] for c in log)
    return acc


class Corpus:
    """One corpus's development gold, baselines and producer accounts (built once)."""

    def __init__(self, name, windows=None):
        self.name = name
        self.cfg = SC.load_config()
        self.rules = resolve_rules(self.cfg)
        self.A = D.adapter(name)
        self.comp = self.A.producer_composition()
        self.dev = self.A.development()
        self.wa = WORK_APPLICABLE[name]
        self.sessions = [(p, s) for p in self.dev for s in self.A.sessions(p)]
        gold = gold_by_participant(self.A, self.dev)
        if windows is not None:  # the gold-blind windows of run.score_corpus
            self.window_seconds = window_length(gold)
            gold = windowed_gold(self.A, gold, self.window_seconds)
        self.gold = gold
        self.participants = sorted(gold)
        self.keys = sorted({(p, g.session) for p, rs in gold.items() for g in rs})
        self.durs, self.fc = baseline_forecasts(
            self.A, self.comp, gold, self.cfg, self.rules
        )
        self.valid = B.valid_scenes(self.comp, self.cfg)
        self._acc = {}

    def account(self, k):
        if k not in self._acc:
            out = WORK / "accounts" / f"{self.name}-s{k}"
            self._acc[k] = (
                build(self.A, self.sessions, k, out, self.rules)
                if self.name == "filmfestival"
                else build_sherlock(self.A, self.sessions, out, self.rules)
            )
        return self._acc[k]

    def producer(self, k):
        return forecasts(
            self.account(k),
            self.comp,
            self.gold,
            self.keys,
            self.durs,
            self.cfg,
            self.rules,
        )

    def score(self, fmap):
        return score_map(self.comp, self.gold, fmap, self.cfg, self.wa)

    def rows(self):
        """Gold rows in the order score_map emits them."""
        return [
            g
            for p, rows in self.gold.items()
            for rs in by_session(rows).values()
            for g in rs
        ]


# ---------------------------------------------------------------- step 0: diagnostics


def category(s):
    """A scene-scored producer row: 'fallback' (abstains on all of its mass), 'hit' or 'miss'."""
    if s.abstain == 1:
        return "fallback"
    return "hit" if s.scene_hit else "miss"


def top_cell(f, valid):
    """The forecast's top valid (work, scene) cell over every work, or None for a row that
    abstains on all of its mass (its top is the fallback's, not the producer's)."""
    if isinstance(f, SC.ProducerForecast) and f.abstain == 1:
        return None
    best, arg = -1.0, None
    for w, ss in valid.items():
        for s in ss:
            v = f.p_set_and_work({s}, w)
            if v > best:
                best, arg = v, (w, s)
    return arg


def budget(scores, others):
    """Row-weighted scene loss by category, with the reference forecasts on the same rows."""
    sc = [(i, s) for i, s in enumerate(scores) if s.scene_bits is not None]
    total = math.fsum(s.scene_bits for _, s in sc)
    out = dict(n=len(sc), mean_scene_bits=total / len(sc), categories={})
    for cat in ("miss", "hit", "fallback"):
        idx = [i for i, s in sc if category(s) == cat]
        loss = math.fsum(scores[i].scene_bits for i in idx)
        d = dict(
            n=len(idx),
            share_of_rows=len(idx) / len(sc),
            mean_scene_bits=loss / len(idx) if idx else None,
            share_of_scene_loss=loss / total,
        )
        for name, o in others.items():
            d[name + "_mean_scene_bits"] = (
                float(np.mean([o[i].scene_bits for i in idx])) if idx else None
            )
        out["categories"][cat] = d
    return out


def miss_geometry(X, fmap, scores):
    """For rows whose top scene is wrong: how far the top scene is from the gold set, whether
    a gold scene is the second choice, and what the neighbouring rows' top scenes say."""
    rows = X.rows()
    tops, flat, pos = {}, [], 0
    for p, grows in X.gold.items():
        for s, rs in by_session(grows).items():
            fs = fmap[(p, s)]
            for j in range(len(rs)):
                tops[pos + j] = top_cell(fs[j], X.valid)
                flat.append((fs[j], j, len(rs)))
            pos += len(rs)
    total = math.fsum(s.scene_bits for s in scores if s.scene_bits is not None)
    groups = {"1": [], "2-3": [], "4+": []}
    second = prev_eq = next_eq = either = bracket = eq_or_br = isolated = 0
    iso_loss = 0.0
    n = 0
    for i, (g, s) in enumerate(zip(rows, scores)):
        if s.scene_bits is None or category(s) != "miss":
            continue
        n += 1
        f, j, m = flat[i]
        ss = X.valid[g.work]
        ix = {x: k for k, x in enumerate(ss)}
        gs = [x for x in g.scenes if x in ix]
        d = min(abs(ix[s.top_scene] - ix[x]) for x in gs)
        groups["1" if d == 1 else "2-3" if d <= 3 else "4+"].append(s.scene_bits)
        ps = sorted(ss, key=lambda x: -f.p_set_and_work({x}, g.work))
        second += ps[1] in gs if len(ps) > 1 else 0
        pt = tops[i - 1] if j > 0 else None
        nt = tops[i + 1] if j + 1 < m else None
        pe = pt is not None and pt[0] == g.work and pt[1] in gs
        ne = nt is not None and nt[0] == g.work and nt[1] in gs
        br = (
            pt is not None
            and nt is not None
            and pt[0] == g.work == nt[0]
            and any(ix[pt[1]] <= ix[x] <= ix[nt[1]] for x in gs)
        )
        own = ix[s.top_scene]
        prev_eq += pe
        next_eq += ne
        either += pe or ne
        bracket += br
        eq_or_br += pe or ne or br
        # an isolated jump: the neighbours bracket a gold scene and the row's own top scene
        # lies outside the neighbours' range
        iso = br and not ix[pt[1]] <= own <= ix[nt[1]]
        isolated += iso
        iso_loss += s.scene_bits if iso else 0.0
    out = dict(n=n, distance={}, share=lambda k: k / n if n else None)
    share = out.pop("share")
    for k, v in groups.items():
        out["distance"][k] = dict(
            n=len(v),
            share_of_misses=share(len(v)),
            mean_scene_bits=float(np.mean(v)) if v else None,
            share_of_scene_loss=math.fsum(v) / total,
        )
    out.update(
        gold_is_second_choice=share(second),
        previous_top_is_gold=share(prev_eq),
        next_top_is_gold=share(next_eq),
        either_neighbour_top_is_gold=share(either),
        neighbours_bracket_gold=share(bracket),
        neighbours_equal_or_bracket_gold=share(eq_or_br),
        isolated_jump=share(isolated),
        isolated_jump_share_of_scene_loss=iso_loss / total,
        definitions=dict(
            distance="scene steps, within the gold work's valid scenes, from the producer's "
            "top scene to the nearest gold scene",
            neighbours="the previous and next gold rows of the session; a neighbour's top is "
            "its top valid scene over every work; a neighbour that abstains on all of its mass "
            "has none",
            bracket="both neighbours' tops are in the gold work and a gold scene lies between "
            "them, ends included",
            isolated_jump="the neighbours bracket a gold scene and the row's own top scene lies "
            "outside the neighbours' range",
        ),
    )
    return out


def diag(out_json):
    state = git_state()
    res = dict(
        schema="storymodel4s.case-study-2.hillclimb-r4-diagnostics/v1",
        **state,
        model_calls=0,
        evidence="development data, engineering numbers; validation = the frozen-six run",
        runs={},
    )
    X = {}
    for name, k in RUNS:
        X.setdefault(name, Corpus(name))
        x = X[name]
        fmap = x.producer(k)
        sc = x.score(fmap)
        others = {
            b: x.score(x.fc[b]) for b in ("hmm_tfidf", "gold_context_fitted", "tfidf")
        }
        res["runs"][f"{name}-s{k}"] = dict(
            budget=budget(sc, others), misses=miss_geometry(x, fmap, sc)
        )
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(res, indent=1, default=str) + "\n")
    print(out_json)


# ---------------------------------------------------------------- step 1: the decoder

ALPHAS = (0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.8, 1.0, 1.25, 1.5)
TERMS = {True: ("scene_bits", "work_bits", "film_bits"), False: ("scene_bits", "film_bits")}
REFERENCES = ("hmm_tfidf", "gold_context_fitted", "order_prior_only")


def pmean(scores, term):
    return SC._pmeans(scores, term)


def tmean(scores, term):
    v = pmean(scores, term)
    return float(np.mean(list(v.values()))) if v else None


def paired(a, b, term, band=None, alternative="two-sided"):
    """Participant-paired difference a - b on a term, with the exact sign-flip test."""
    pa, pb = pmean(a, term), pmean(b, term)
    ps = sorted(pa)
    d = [pa[p] - pb[p] for p in ps]
    out = dict(delta=float(np.mean(d)), wins=f"{sum(x < 0 for x in d)}/{len(d)}",
               p=sign_flip_p(d), per_participant=dict(zip(ps, d)))
    if alternative == "less":  # one-sided: a lower than b
        out["p_one_sided"] = sign_flip_p([-x for x in d], "greater")
    if band is not None:
        out.update(band=band, beyond_band=abs(out["delta"]) > band)
    return out


class Decoder:
    """The decoder over one corpus and one evidence forecast map (LOPO rates, cells, scores)."""

    def __init__(self, X, fmap):
        self.X, self.sp = X, DC.space(X.comp, X.cfg)
        self.rates = {p: DC.fit(X.comp, X.gold, p, X.cfg) for p in X.participants}
        self.TI = {p: DC.transition(self.sp, r) for p, r in self.rates.items()}
        self.d = {p: DC.cells(X.durs[p], self.sp, X.durs[p], self.rates[p]["nu"])[0]
                  for p in X.participants}
        self.q, self.residual_rows = {}, 0
        for (p, s), fs in fmap.items():
            cs = [DC.cells(f, self.sp, X.durs[p], self.rates[p]["nu"]) for f in fs]
            self.q[(p, s)] = np.array([c[0] for c in cs])
            self.residual_rows += sum(c[1] > 1e-9 for c in cs)
        self._cache = {}

    def forecasts(self, alpha):
        """alpha None: flat evidence (the order prior alone)."""
        if alpha not in self._cache:
            self._cache[alpha] = {
                (p, s): DC.decode(self.sp, *self.TI[p], len(q) if alpha is None else q,
                                  self.d[p], alpha)
                for (p, s), q in self.q.items()}
        return self._cache[alpha]

    def flat(self, fmap):
        """Forecasts in the order score_map emits rows."""
        return [f for p, rows in self.X.gold.items() for s in by_session(rows)
                for f in fmap[(p, s)]]

    def grid(self):
        """Scores per alpha, and the objective per participant: the mean of the terms."""
        terms = TERMS[self.X.wa]
        sc = {a: self.X.score(self.forecasts(a)) for a in ALPHAS}
        obj = {a: {p: float(np.mean([pmean(sc[a], t)[p] for t in terms]))
                   for p in self.X.participants} for a in ALPHAS}
        return sc, obj

    def lopo(self, sc, obj):
        """Each participant's alpha minimises the objective over the other participants."""
        ps = self.X.participants
        pick = {p: min(ALPHAS, key=lambda a: np.mean([obj[a][q] for q in ps if q != p]))
                for p in ps}
        scores = [sc[pick[s.participant]][i] for i, s in enumerate(sc[ALPHAS[0]])]
        fl = {a: self.flat(self.forecasts(a)) for a in set(pick.values())}
        fcs = [fl[pick[s.participant]][i] for i, s in enumerate(scores)]
        return pick, scores, fcs

    def fixed(self, alpha):
        return self.X.score(self.forecasts(alpha)), self.flat(self.forecasts(alpha))

    @staticmethod
    def all_alpha(obj):
        return min(ALPHAS, key=lambda a: np.mean(list(obj[a].values())))


def pass1_only(X, k):
    acc = X.account(k)
    return {(p, s): [B.frame_fallback(X.durs[p], m, X.cfg["frame_floor"])
                     for m in row_frame_mixes(acc, (p, s),
                                              [g for g in X.gold[p] if g.session == s])]
            for p, s in X.keys}


def null_check(X, k, alpha):
    """A producer with no references and no frames, through the same path: its decoded forecast
    must equal the order prior alone on every cell of every row, exactly."""
    acc = X.account(k)
    null = Account(X.comp, {}, {key: [WordCell(c.index, c.onset, c.unit, Uncovered(c.unit))
                                      for c in acc.words[key]] for key in X.keys})
    dcd = Decoder(X, forecasts(null, X.comp, X.gold, X.keys, X.durs, X.cfg, X.rules))
    a, b = dcd.flat(dcd.forecasts(alpha)), dcd.flat(dcd.forecasts(None))
    return dict(rows=len(a), alpha=alpha,
                max_abs_difference=float(max(np.abs(x.post - y.post).max()
                                             for x, y in zip(a, b))))


def nonfilm_mass(scores, label=None):
    """Mean scored P(non-film) on non-film gold rows (one label, or all)."""
    v = [2.0 ** -s.film_bits for s in scores if s.cls == "nonfilm"
         and (label is None or s.nonfilm_label == label)]
    return dict(n=len(v), mean=float(np.mean(v)) if v else None)


def film_split(scores):
    out = {}
    for name, sel in (("film_rows", lambda s: s.cls != "nonfilm"),
                      ("nonfilm_rows", lambda s: s.cls == "nonfilm")):
        v = [s.film_bits for s in scores if sel(s) and s.film_bits is not None]
        out[name] = dict(n=len(v), mean_film_bits=float(np.mean(v)) if v else None)
    return out


def describe(X, named, fcs, bands=None):
    """The comparison of `producer_decoded` with `producer` and the references on one corpus."""
    terms = TERMS[X.wa]
    prod, dec = named["producer"], named["producer_decoded"]
    out = dict(forecasts={}, tests={}, categories={})
    for name, sc in named.items():
        hits = [s.scene_hit for s in sc if s.scene_hit is not None]
        out["forecasts"][name] = dict(
            **{t: tmean(sc, t) for t in terms},
            top_scene=float(np.mean(hits)) if hits else None,
            nonfilm_mass_memory_search=nonfilm_mass(sc, "memory_search"),
            nonfilm_mass_all_nonfilm=nonfilm_mass(sc), film_term=film_split(sc))
        if X.wa:
            st = SC.strata(sc, X.comp, True)["film_switch_f1"]
            out["forecasts"][name]["switch"] = dict(
                f1=st["f1"], f1_tolerance_1_row=st["f1_tolerance_1_row"],
                matched_timing=st["matched_timing"], gold_switches=st["switches"])
    for t in terms:
        out["tests"][t] = paired(dec, prod, t, (bands or {}).get(t), "less")
    for ref in REFERENCES:
        if ref in named:
            out["tests"]["scene_vs_" + ref] = paired(dec, named[ref], "scene_bits")
    idx = [i for i, s in enumerate(prod) if s.scene_bits is not None]
    total = math.fsum(prod[i].scene_bits for i in idx)
    for cat in ("miss", "hit", "fallback"):
        ii = [i for i in idx if category(prod[i]) == cat]
        if not ii:
            out["categories"][cat] = dict(n=0)
            continue
        own = [fcs[i].own_share for i in ii if fcs[i].own_share is not None]
        out["categories"][cat] = dict(
            n=len(ii), share_of_producer_scene_loss=math.fsum(prod[i].scene_bits
                                                              for i in ii) / total,
            producer_scene_bits=float(np.mean([prod[i].scene_bits for i in ii])),
            decoded_scene_bits=float(np.mean([dec[i].scene_bits for i in ii])),
            order_prior_only_scene_bits=float(np.mean(
                [named["order_prior_only"][i].scene_bits for i in ii])),
            decoded_top_scene=float(np.mean([dec[i].scene_hit for i in ii])),
            mean_own_share=float(np.mean(own)) if own else None)
    scored = [(dec[i], fcs[i]) for i in idx]
    share = {}
    for src in ("evidence", "neighbours"):
        v = [s for s, f in scored if f.top_from == src]
        share[src] = dict(n=len(v), share_of_rows=len(v) / len(scored),
                          decoded_top_scene=float(np.mean([s.scene_hit for s in v]))
                          if v else None,
                          decoded_scene_bits=float(np.mean([s.scene_bits for s in v]))
                          if v else None)
    own = [f.own_share for f in fcs if f.own_share is not None]
    out["evidence_share"] = dict(
        rows=len(fcs), rows_without_a_share=len(fcs) - len(own),
        mean_own_share=float(np.mean(own)) if own else None,
        mean_kl_own_bits=float(np.mean([f.kl_own_bits for f in fcs])),
        mean_kl_neighbours_bits=float(np.mean([f.kl_neighbours_bits for f in fcs])),
        scene_scored_rows_by_top_source=share,
        note="own share = KL(posterior || posterior without the row's own evidence) / (that + "
        "KL(posterior || the row's own distribution)); a row whose top cell is not the top of "
        "its own evidence is typed 'neighbours' (inferred)")
    return out


def step_split(X, prod, dec):
    """Post hoc: scene-scored rows by the gold step from the previous scene-coded row of the
    same work (the last valid scene of each set, as the transition fit takes it)."""
    rows = X.rows()
    out, prev = {}, None
    for i, g in enumerate(rows):
        if prev is not None and (prev[0], prev[1]) != (g.participant, g.session):
            prev = None
        if g.work is None:
            continue
        if prev is not None and prev[2] != g.work:
            prev = None
        t = DC._top(g, X.valid) if g.specific else None
        if t is None:
            continue
        j = X.valid[g.work].index(t)
        k = None if prev is None else j - prev[3]
        key = ("first in the work" if k is None else "stay" if k == 0 else "forward 1"
               if k == 1 else "forward 2-3" if 2 <= k <= 3 else "forward 4+" if k > 3
               else "backward")
        if prod[i].scene_bits is not None:
            out.setdefault(key, []).append(i)
        prev = (g.participant, g.session, g.work, j)
    return {k: dict(n=len(ii),
                    producer_scene_bits=float(np.mean([prod[i].scene_bits for i in ii])),
                    decoded_scene_bits=float(np.mean([dec[i].scene_bits for i in ii])),
                    producer_top_scene=float(np.mean([prod[i].scene_hit for i in ii])),
                    decoded_top_scene=float(np.mean([dec[i].scene_hit for i in ii])))
            for k, ii in sorted(out.items())}


def evidence_bound(dcd, rates, prod, dec):
    """Post hoc: how far a row's content can outweigh the order prior. The floor is the lowest
    q / d over a row's scene cells (mass that abstains or stays at work level sits under every
    scene); the bound is log2(top ratio / floor), before alpha."""
    keys = [(p, s) for p, rows in dcd.X.gold.items() for s in by_session(rows)]
    q = np.concatenate([dcd.q[k] for k in keys])
    d = np.concatenate([np.tile(dcd.d[k[0]], (len(dcd.q[k]), 1)) for k in keys])
    n, ix = dcd.sp.n, dcd.sp.index()
    ratio = q[:, :n] / d[:, :n]
    floor, top = ratio.min(1), ratio.max(1)
    lost = [i for i, (a, b) in enumerate(zip(prod, dec)) if a.scene_hit and b.scene_hit is False]
    won = [i for i, (a, b) in enumerate(zip(prod, dec)) if a.scene_hit is False and b.scene_hit]
    lr = [math.log2(ratio[i, ix[prod[i].top_scene]] / ratio[i, ix[dec[i].top_scene]])
          for i in lost]
    size = int(np.median(dcd.sp.size))
    sc = rates["scene"]
    return dict(
        median_floor_ratio=float(np.median(floor)), median_top_ratio=float(np.median(top)),
        median_bound_bits=float(np.median(np.log2(top / floor))),
        tops_right_to_wrong=len(lost), tops_wrong_to_right=len(won),
        median_evidence_bits_producer_top_over_decoded_top_on_right_to_wrong=float(
            np.median(lr)) if lr else None,
        order_prior_bits_one_step_forward_over_a_jump=math.log2(
            sc["forward"] * (1 - sc["rho"]) / (sc["jump"] / (size - 1))) if size > 1 else None,
        median_scenes_per_work=size)


def run_variant(X, fmap, alpha=None, extra=None, bands=None):
    """Decode one evidence map. alpha None: fitted leave-one-participant-out; else fixed."""
    dcd = Decoder(X, fmap)
    sc, obj = dcd.grid()
    all_alpha = dcd.all_alpha(obj)
    if alpha is None:
        pick, dec, fcs = dcd.lopo(sc, obj)
    else:
        pick, (dec, fcs) = {p: alpha for p in X.participants}, dcd.fixed(alpha)
    named = dict(producer=X.score(fmap), producer_decoded=dec,
                 order_prior_only=X.score(dcd.forecasts(None)),
                 **{b: X.score(X.fc[b]) for b in ("hmm_tfidf", "gold_context_fitted", "tfidf")},
                 **{k: X.score(v) for k, v in (extra or {}).items()})
    out = describe(X, named, fcs, bands)
    out.update(
        alpha=dict(per_participant=pick, fitted="leave-one-participant-out" if alpha is None
                   else "fixed", minimiser_over_all_participants=all_alpha,
                   at_grid_edge=sorted({a for a in list(pick.values()) + [all_alpha]
                                        if a in (ALPHAS[0], ALPHAS[-1])}),
                   grid_objective={str(a): float(np.mean(list(obj[a].values())))
                                   for a in ALPHAS},
                   grid_scene_bits={str(a): tmean(sc[a], "scene_bits") for a in ALPHAS}),
        rows_with_in_work_residual=int(dcd.residual_rows),
        rates_all_participants=(allr := DC.fit(X.comp, X.gold, None, X.cfg)),
        post_hoc_evidence_bound=evidence_bound(dcd, allr, named["producer"], dec),
        states=3 * dcd.sp.n + 2, cells=dcd.sp.n + 2)
    return out, all_alpha, named


def decode(out_json):
    state = git_state()
    res = dict(schema="storymodel4s.case-study-2.hillclimb-r4/v1", **state, model_calls=0,
               evidence="development data, engineering numbers; validation = the frozen-six run",
               alphas=list(ALPHAS), corpora={})
    ff, sh = Corpus("filmfestival"), Corpus("sherlock")
    f1, f2 = ff.producer(1), ff.producer(2)
    s1, s2 = ff.score(f1), ff.score(f2)
    bands = {}
    for t in TERMS[True]:
        a, b = pmean(s1, t), pmean(s2, t)
        d = [b[p] - a[p] for p in ff.participants]
        bands[t] = 2 * float(np.std(d, ddof=1)) / math.sqrt(len(d))
    res["pooled_bands"] = bands
    F = res["corpora"]["filmfestival"] = {}
    F["sample_1"], a_ff, n_ff = run_variant(ff, f1, None, dict(pass1_only=pass1_only(ff, 1)),
                                            bands)
    F["sample_2"], _, _ = run_variant(ff, f2, None, dict(pass1_only=pass1_only(ff, 2)), bands)
    ens = {k: [SC.EnsembleForecast([x, y]) for x, y in zip(f1[k], f2[k])] for k in ff.keys}
    F["ensemble"], _, _ = run_variant(ff, ens, None, None, bands)
    S = res["corpora"]["sherlock"] = {}
    S["part_order_run"], _, n_sh = run_variant(sh, sh.producer(1), a_ff)
    res["null_producer_equals_order_prior_only"] = dict(
        filmfestival=null_check(ff, 1, a_ff), sherlock=null_check(sh, 1, a_ff))
    # the gold-blind windows: every fitted quantity is refitted on the windowed rows
    ffw, shw = Corpus("filmfestival", "median"), Corpus("sherlock", "median")
    ffw._acc, shw._acc = ff._acc, sh._acc
    F["windows_sample_1"], a_ffw, _ = run_variant(ffw, ffw.producer(1))
    F["windows_sample_1"]["window_seconds"] = ffw.window_seconds
    S["windows_part_order_run"], _, _ = run_variant(shw, shw.producer(1), a_ffw)
    S["windows_part_order_run"]["window_seconds"] = shw.window_seconds
    # the protocol's four conditions (plan, "Hill-climbing round 4")
    p, q = F["sample_1"], S["part_order_run"]
    sc = p["tests"]["scene_bits"]
    c1 = sc["delta"] < 0 and sc["p"] <= 0.01 and sc["beyond_band"]
    c2 = all(p["tests"][t]["delta"] <= bands[t] for t in ("work_bits", "film_bits"))
    sq = q["tests"]["scene_bits"]
    c3 = sq["delta"] < 0 and sq["p_one_sided"] <= 0.05
    c4 = all(x["tests"]["scene_vs_" + r]["delta"] < 0 for x in (p, q) for r in REFERENCES)
    res["conditions"] = {
        "1_filmfestival_scene_pooled": bool(c1), "2_work_and_film_not_worse": bool(c2),
        "3_sherlock_confirmation": bool(c3), "4_beats_references_on_scene": bool(c4)}
    res["verdict"] = "KEEP" if c1 and c2 and c3 and c4 else "DROP"
    res["sherlock_alpha"] = a_ff
    # post hoc: computed after the verdict to explain it; no decision rests on any of it
    own, _, _ = run_variant(sh, sh.producer(1))
    res["post_hoc"] = dict(
        note="computed after the verdict to explain it; not part of the protocol, and no "
        "decision rests on it",
        by_gold_step=dict(
            filmfestival_sample_1=step_split(ff, n_ff["producer"], n_ff["producer_decoded"]),
            sherlock_part_order_run=step_split(sh, n_sh["producer"], n_sh["producer_decoded"])),
        sherlock_alpha_fitted_within_sherlock=dict(
            alpha=own["alpha"], forecasts={k: own["forecasts"][k] for k in
                                           ("producer", "producer_decoded")},
            tests={k: own["tests"][k] for k in TERMS[False]}))
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(res, indent=1, default=str) + "\n")
    print(out_json)


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "diag":
        diag(Path(sys.argv[2]))
    elif len(sys.argv) > 2 and sys.argv[1] == "decode":
        decode(Path(sys.argv[2]))
    else:
        raise SystemExit(__doc__)
