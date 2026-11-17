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
from cs2 import ff_producer as P  # noqa: E402
from cs2 import scorer as SC  # noqa: E402
from cs2.hillclimb import C, SAMPLES, build, forecasts  # noqa: E402
from cs2.run import (  # noqa: E402
    WORK_APPLICABLE,
    baseline_forecasts,
    by_session,
    git_state,
    gold_by_participant,
    resolve_rules,
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


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "diag":
        diag(Path(sys.argv[2]))
    else:
        raise SystemExit(__doc__)
