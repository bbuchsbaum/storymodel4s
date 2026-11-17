"""Hill-climbing round 1 (cache-only, no model calls): calibration, two-sample ensembling and
switch repair, tuned on the develop five and confirmed on the report five.

Protocol: docs/plans/2026-11-16-cs2-mapping-object.md, "Hill-climbing round 1".
Usage (from tools/case-studies/recall-precision): python -m cs2.hillclimb OUT_JSON
"""

from __future__ import annotations

import json
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
from cs2.projections import LocationDistribution  # noqa: E402
from cs2.run import (  # noqa: E402
    baseline_forecasts,
    bins_of,
    git_state,
    gold_by_participant,
    resolve_rules,
    row_frame_mixes,
    score_map,
)
from metrics import sign_flip_p  # noqa: E402

TUNE = ["sub-07", "sub-08", "sub-14", "sub-17", "sub-18"]
CONFIRM = ["sub-01", "sub-02", "sub-10", "sub-16", "sub-19"]
C = Path("/Users/bbuchsbaum/code/scala/storymodel4s/data/study/recall-precision/cs2")
SAMPLES = {1: (20261119, "cs2-ff-full-s1"), 2: (20261120, "cs2-ff-full-s2")}
LAMBDAS = [0.0, 0.01, 0.02, 0.05, 0.1, 0.2]
TAUS = [0.5, 0.7, 0.85, 1.0, 1.2, 1.5]
TERMS = ("scene_bits", "work_bits", "film_bits")


def build(A, sessions, k, out, rules):
    P.CACHE_ONLY = True
    seed, salt = SAMPLES[k]
    acc, _, log, _ = P.run(
        A,
        sessions,
        C / "filmfestival/seg/export",
        out,
        seed,
        salt,
        k,
        arm="coverage",
        rules=rules,
    )
    assert all(not c["fresh"] for c in log)
    return acc


def forecasts(acc, comp, gold, keys, durs, cfg, rules):
    out = {}
    for p, s in keys:
        rows = [g for g in gold[p] if g.session == s]
        ld = LocationDistribution.from_words(
            acc,
            acc.words[(p, s)],
            bins_of(rows),
            cfg["allocation"],
            rules["cross_scene_broad"],
        )
        fbs = [
            B.frame_fallback(durs[p], m, cfg["frame_floor"])
            for m in row_frame_mixes(acc, (p, s), rows)
        ]
        out[(p, s)] = SC.producer_forecasts(ld, durs[p], cfg, fbs)
    return out


def summarize(scores, parts, comp):
    sc = [s for s in scores if s.participant in parts]
    pm = {
        t: {
            p: float(
                np.mean(
                    [
                        getattr(s, t)
                        for s in sc
                        if s.participant == p and getattr(s, t) is not None
                    ]
                )
            )
            for p in parts
        }
        for t in TERMS
    }
    hits = [s.scene_hit for s in sc if s.scene_hit is not None]
    st = SC.strata(sc, comp, True)["film_switch_f1"]
    return dict(
        terms=pm,
        top_scene=float(np.mean(hits)),
        switch=dict(
            f1=st["f1"], f1_tol1=st["f1_tolerance_1_row"], timing=st["matched_timing"]
        ),
    )


def mean_bits(a, b):
    """The current two-sample rule: average bits per row."""
    out = []
    for x, y in zip(a, b):
        z = SC.RowScore(x.participant, x.session, x.row, x.cls, x.work, None, None)
        for t in TERMS:
            u, v = getattr(x, t), getattr(y, t)
            setattr(z, t, None if u is None else (u + v) / 2)
        z.scene_hit, z.top_work = x.scene_hit, x.top_work
        out.append(z)
    return out


def delta(v, base, bands, parts):
    out = {}
    for t in TERMS:
        d = [v["terms"][t][p] - base["terms"][t][p] for p in parts]
        out[t] = dict(
            delta=round(float(np.mean(d)), 4),
            band=round(bands[t], 4),
            beyond_band=abs(np.mean(d)) > bands[t],
            better=float(np.mean(d)) < 0,
            p=round(sign_flip_p(d), 4),
            wins=f"{sum(x < 0 for x in d)}/{len(d)}",
        )
    return out


def verdict(dt, dc, term):
    """Kept only if the target term beats its band in the good direction on both halves and no
    other term worsens beyond its band on either half."""
    good = all(d[term]["better"] and d[term]["beyond_band"] for d in (dt, dc))
    harm = any(
        d[t]["delta"] > d[t]["band"] for d in (dt, dc) for t in TERMS if t != term
    )
    return "KEEP" if good and not harm else "DROP"


def main(argv):
    out_json = Path(argv[0])
    state = git_state()
    cfg0 = SC.load_config()
    rules = resolve_rules(cfg0)
    A = D.adapter("filmfestival")
    comp = A.producer_composition()
    dev = A.development()
    sessions = [(p, s) for p in dev for s in A.sessions(p)]
    work = C / "hillclimb-r1"
    accs = {k: build(A, sessions, k, work / f"s{k}", rules) for k in (1, 2)}
    acc_sw = build(
        A, sessions, 1, work / "s1-switch", dict(rules, mismatch_rule="incoming-frame")
    )
    all_gold = gold_by_participant(A, dev)
    keys = sorted({(p, g.session) for p, rs in all_gold.items() for g in rs})
    durs, fc = baseline_forecasts(A, comp, all_gold, cfg0, rules)
    gold = all_gold

    def score(fmap):
        return score_map(comp, gold, fmap, cfg0, True)

    def prod(acc, cfg):
        return score(forecasts(acc, comp, gold, keys, durs, cfg, rules))

    base_f = {
        k: forecasts(accs[k], comp, gold, keys, durs, cfg0, rules) for k in (1, 2)
    }
    base_s = {k: score(base_f[k]) for k in (1, 2)}
    res = dict(
        schema="storymodel4s.case-study-2.hillclimb-r1/v1",
        **state,
        model_calls=0,
        evidence="development data, engineering numbers; validation = the frozen-six run",
        halves=dict(tune=TUNE, confirm=CONFIRM),
        levers={},
    )
    bands, base = {}, {}
    for half, parts in (("tune", TUNE), ("confirm", CONFIRM)):
        s1, s2 = summarize(base_s[1], parts, comp), summarize(base_s[2], parts, comp)
        bands[half] = {
            t: 2
            * float(
                np.std([s2["terms"][t][p] - s1["terms"][t][p] for p in parts], ddof=1)
            )
            / np.sqrt(len(parts))
            for t in TERMS
        }
        base[half] = s1
    res["bands"] = bands
    res["base"] = base

    # lever 1: calibration, fitted on the tune half
    def objective(scores):
        v = summarize(scores, TUNE, comp)["terms"]
        return float(np.mean([np.mean(list(v[t].values())) for t in TERMS]))

    grid = {}
    for lam in LAMBDAS:
        grid[f"lambda={lam}"] = objective(
            prod(accs[1], dict(cfg0, calibration_lambda=lam))
        )
    lam_fit = min(LAMBDAS, key=lambda x: grid[f"lambda={x}"])
    for tau in TAUS:
        grid[f"tau={tau}"] = objective(
            prod(accs[1], dict(cfg0, located_temperature=tau))
        )
    tau_fit = min(TAUS, key=lambda x: grid[f"tau={x}"])
    cal = prod(
        accs[1], dict(cfg0, calibration_lambda=lam_fit, located_temperature=tau_fit)
    )
    lever = dict(fit=dict(lambda_=lam_fit, tau=tau_fit, grid_tune_objective=grid))
    for half, parts in (("tune", TUNE), ("confirm", CONFIRM)):
        v = summarize(cal, parts, comp)
        lever[half] = dict(result=v, delta=delta(v, base[half], bands[half], parts))
    lever["verdict"] = verdict(
        lever["tune"]["delta"], lever["confirm"]["delta"], "scene_bits"
    )
    res["levers"]["calibration"] = lever

    # lever 2: two-sample ensembling of distributions vs the mean of bits
    ens_f = {
        k: [SC.EnsembleForecast([a, b]) for a, b in zip(base_f[1][k], base_f[2][k])]
        for k in keys
    }
    ens = score(ens_f)
    mb = mean_bits(base_s[1], base_s[2])
    lever = {}
    for half, parts in (("tune", TUNE), ("confirm", CONFIRM)):
        v, r = summarize(ens, parts, comp), summarize(mb, parts, comp)
        lever[half] = dict(
            result=v, mean_of_bits=r, delta=delta(v, r, bands[half], parts)
        )
    lever["verdict"] = verdict(
        lever["tune"]["delta"], lever["confirm"]["delta"], "scene_bits"
    )
    lever[
        "note"
    ] = "compared against the current two-sample rule (mean of bits); doubles calls"
    res["levers"]["ensemble"] = lever

    # lever 3: switch repair from pass-2 evidence (frame_mismatch, incoming-frame rule)
    sw = prod(acc_sw, dict(cfg0))
    lever = {}
    for half, parts in (("tune", TUNE), ("confirm", CONFIRM)):
        v = summarize(sw, parts, comp)
        lever[half] = dict(result=v, delta=delta(v, base[half], bands[half], parts))
    lever["verdict"] = verdict(
        lever["tune"]["delta"], lever["confirm"]["delta"], "work_bits"
    )
    res["levers"]["switch_repair"] = lever

    # combination of the independent levers: calibration (fitted) on the ensemble
    if lam_fit or tau_fit != 1:
        cf = {
            k: forecasts(
                accs[k],
                comp,
                gold,
                keys,
                durs,
                dict(cfg0, calibration_lambda=lam_fit, located_temperature=tau_fit),
                rules,
            )
            for k in (1, 2)
        }
        comb = score(
            {
                k: [SC.EnsembleForecast([a, b]) for a, b in zip(cf[1][k], cf[2][k])]
                for k in keys
            }
        )
        lever = {}
        for half, parts in (("tune", TUNE), ("confirm", CONFIRM)):
            v, r = summarize(comb, parts, comp), summarize(mb, parts, comp)
            lever[half] = dict(result=v, delta=delta(v, r, bands[half], parts))
        lever["verdict"] = verdict(
            lever["tune"]["delta"], lever["confirm"]["delta"], "scene_bits"
        )
        res["levers"]["calibration+ensemble_vs_mean_of_bits"] = lever
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(res, indent=1, default=str) + "\n")
    print(out_json)


if __name__ == "__main__":
    main(sys.argv[1:])
