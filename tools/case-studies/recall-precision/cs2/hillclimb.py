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


if __name__ == "__main__" and sys.argv[1:2] not in (["round2"], ["round3"]):
    main(sys.argv[1:])


# ---------------------------------------------------------------- round 2 (pooled, cache-only)

TAUS_R2 = [1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5, 2.75, 3.0]
ALL10 = sorted(TUNE + CONFIRM)


def pooled(v, ref, band):
    d = [v[p] - ref[p] for p in ALL10]
    return dict(delta=round(float(np.mean(d)), 4), band=round(band, 4),
                beyond_band=abs(float(np.mean(d))) > band, better=float(np.mean(d)) < 0,
                p=round(sign_flip_p(d), 4), wins=f"{sum(x < 0 for x in d)}/{len(d)}")


def pass_rule(r):
    return r["better"] and r["beyond_band"] and r["p"] <= 0.01


def round2(out_json):
    state = git_state()
    cfg0 = SC.load_config()
    rules = resolve_rules(cfg0)
    A = D.adapter("filmfestival")
    comp = A.producer_composition()
    dev = A.development()
    sessions = [(p, s) for p in dev for s in A.sessions(p)]
    work = C / "hillclimb-r2"
    accs = {k: build(A, sessions, k, work / f"s{k}", rules) for k in (1, 2)}
    gold = gold_by_participant(A, dev)
    keys = sorted({(p, g.session) for p, rs in gold.items() for g in rs})
    durs, _ = baseline_forecasts(A, comp, gold, cfg0, rules)

    def pm(scores):
        return summarize(scores, ALL10, comp)

    def ens(tau):
        cfg = dict(cfg0, located_temperature=tau)
        f = {k: forecasts(accs[k], comp, gold, keys, durs, cfg, rules) for k in (1, 2)}
        return score_map(comp, gold, {x: [SC.EnsembleForecast([a, b])
                                          for a, b in zip(f[1][x], f[2][x])] for x in keys},
                         cfg0, True)

    base = {k: score_map(comp, gold, forecasts(accs[k], comp, gold, keys, durs, cfg0, rules),
                         cfg0, True) for k in (1, 2)}
    s1, s2 = pm(base[1]), pm(base[2])
    bands = {t: 2 * float(np.std([s2["terms"][t][p] - s1["terms"][t][p] for p in ALL10],
                                 ddof=1)) / np.sqrt(10) for t in TERMS}
    res = dict(schema="storymodel4s.case-study-2.hillclimb-r2/v1", **state, model_calls=0,
               evidence="development data, engineering numbers; validation = the frozen-six run",
               participants=ALL10, pooled_bands=bands, levers={})
    # lever: ensemble of distributions vs mean of bits
    mb = pm(mean_bits(base[1], base[2]))
    e1 = ens(1.0)
    ev = pm(e1)
    lev = dict(result={t: float(np.mean(list(ev["terms"][t].values()))) for t in TERMS},
               mean_of_bits={t: float(np.mean(list(mb["terms"][t].values()))) for t in TERMS},
               top_scene=(ev["top_scene"], mb["top_scene"]),
               test={t: pooled(ev["terms"][t], mb["terms"][t], bands[t]) for t in TERMS})
    lev["verdict"] = "KEEP" if (pass_rule(lev["test"]["scene_bits"]) and not any(
        lev["test"][t]["delta"] > bands[t] for t in TERMS)) else "DROP"
    res["levers"]["ensemble_distributions"] = lev
    # lever: sharpening on the ensemble, tau fitted leave-one-participant-out
    runs = {tau: pm(ens(tau)) for tau in TAUS_R2}
    obj = {tau: {p: float(np.mean([r["terms"][t][p] for t in TERMS])) for p in ALL10}
           for tau, r in runs.items()}
    chosen = {p: min(TAUS_R2, key=lambda tau: np.mean([obj[tau][q] for q in ALL10 if q != p]))
              for p in ALL10}
    sharp = {t: {p: runs[chosen[p]]["terms"][t][p] for p in ALL10} for t in TERMS}
    lev = dict(tau_lopo=chosen,
               grid_pooled_objective={str(t): float(np.mean(list(o.values())))
                                      for t, o in obj.items()},
               result={t: float(np.mean(list(sharp[t].values()))) for t in TERMS},
               reference_tau1={t: float(np.mean(list(ev["terms"][t].values()))) for t in TERMS},
               test={t: pooled(sharp[t], ev["terms"][t], bands[t]) for t in TERMS})
    lev["verdict"] = "KEEP" if (pass_rule(lev["test"]["scene_bits"]) and not any(
        lev["test"][t]["delta"] > bands[t] for t in TERMS)) else "DROP"
    res["levers"]["sharpening_on_ensemble"] = lev
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(res, indent=1, default=str) + "\n")
    print(out_json)


if __name__ == "__main__" and len(sys.argv) > 2 and sys.argv[1] == "round2":
    round2(Path(sys.argv[2]))


# ---------------------------------------------------------------- round 3 (paid; scored cache-only)

FF = C / "filmfestival"
R3_REF = {1: FF / "stage2-20261117/v1-percall-s1", 2: FF / "stage2-20261117/v1-percall-s2"}


def build_r3(A, sessions, k, pass1, out, rules):
    """A per-call run over the coded sessions only, its pass 2 rebuilt per unit from the full
    sample-k run (a cache-only replay with v1 reproduces every reference row)."""
    P.CACHE_ONLY = True
    seed, salt = SAMPLES[k]
    acc, ledger, log, _ = P.run(A, sessions, C / "filmfestival/seg/export", out, seed, salt, k,
                                arm="coverage", rules=rules, layout="per-call", pass1=pass1,
                                reuse_pass2_from=R3_REF[k])
    assert all(not c["fresh"] for c in log)
    return acc, ledger


def search_mass(scores, parts, label):
    """Participant mean of non-film mass on non-film gold rows with this label (None if none)."""
    out = {}
    for p in parts:
        v = [s.nonwork for s in scores if s.participant == p and s.cls == "nonfilm"
             and s.nonfilm_label == label and s.nonwork is not None]
        out[p] = float(np.mean(v)) if v else None
    return out


def round3(out_json):
    state = git_state()
    cfg = SC.load_config()
    rules = resolve_rules(cfg)
    A = D.adapter("filmfestival")
    comp = A.producer_composition()
    dev = A.development()
    sessions = [(p, s) for p in ALL10 for s in A.sessions(p)]
    work = C / "hillclimb-r3"
    runs = {"s1": build_r3(A, sessions, 1, "v1", work / "v1-s1", rules),
            "s2": build_r3(A, sessions, 2, "v1", work / "v1-s2", rules),
            "v1b": build_r3(A, sessions, 1, "v1b", work / "v1b-s1", rules)}
    gold = gold_by_participant(A, dev)
    keys = sorted({(p, g.session) for p, rs in gold.items() for g in rs if p in ALL10})
    durs, _ = baseline_forecasts(A, comp, gold, cfg, rules)
    sc = {k: score_map(comp, gold, forecasts(a, comp, gold, keys, durs, cfg, rules), cfg, True)
          for k, (a, _) in runs.items()}
    metric = {}
    for k, s in sc.items():
        m = summarize(s, ALL10, comp)["terms"]
        m["memory_search_nonfilm_mass"] = search_mass(s, ALL10, "memory_search")
        metric[k] = m
    names = list(TERMS) + ["memory_search_nonfilm_mass"]
    res = dict(schema="storymodel4s.case-study-2.hillclimb-r3/v1", **state,
               evidence="development data, engineering numbers; validation = the frozen-six run",
               lever="pass-1 v1b (search-for-next sentence), per-call sample 1, pass 2 reused "
               "per unit where the frame film is unchanged",
               producer_run=str(FF / "round3-20261117/v1b"),
               reuse=runs["v1b"][1].get("pass2_reuse"), halves={},
               producer_calls=json.loads((FF / "round3-20261117/v1b/summary.json").read_text())
               ["calls"],
               budget=dict(fresh_calls_allowed=100,
                           note="cost_usd is the CLI's list-price estimate (notional); calls run "
                           "on the owner's subscription through claude -p"),
               deferred="(a) context window 3: projected about 144 calls with a prefix "
               "reference on the report five; over the budget left after (b), not run")
    for half, parts in (("tune", TUNE), ("confirm", CONFIRM)):
        h = {}
        for t in names:
            ps = [p for p in parts if all(metric[k][t][p] is not None for k in metric)]
            d = [metric["v1b"][t][p] - metric["s1"][t][p] for p in ps]
            n = [metric["s2"][t][p] - metric["s1"][t][p] for p in ps]
            band = 2 * float(np.std(n, ddof=1)) / np.sqrt(len(n)) if len(n) > 1 else None
            h[t] = dict(participants=ps, v1=float(np.mean([metric["s1"][t][p] for p in ps])),
                        v1b=float(np.mean([metric["v1b"][t][p] for p in ps])),
                        delta=round(float(np.mean(d)), 4),
                        band=None if band is None else round(band, 4),
                        p=round(sign_flip_p(d), 4), per_participant=dict(zip(ps, d)))
        res["halves"][half] = h

    def ok(h):
        ms, fb, sb = (h["memory_search_nonfilm_mass"], h["film_bits"], h["scene_bits"])
        target = ((ms["delta"] > 0 and ms["band"] is not None and ms["delta"] > ms["band"])
                  or (fb["delta"] < 0 and -fb["delta"] > fb["band"]))
        return target and not sb["delta"] > sb["band"]

    res["verdict"] = "KEEP" if all(ok(res["halves"][x]) for x in ("tune", "confirm")) else "DROP"
    # frames that changed: kinds and empty frames on the v1b run against sample 1
    p1a = json.loads((R3_REF[1] / "pass1.json").read_text())
    p1b = json.loads((FF / "round3-20261117/v1b/pass1.json").read_text())
    ch = dict(units=0, frame_emptied=0, frame_film_changed=0, kind_changed=0,
              memory_search_units_v1=0, memory_search_units_v1b=0,
              memory_search_empty_v1=0, memory_search_empty_v1b=0)
    for k, v in p1b.items():
        for fa, fb_ in zip(p1a[k]["frames"], v["frames"]):
            ch["units"] += 1
            top = lambda f: f["frame"][0][0] if f["frame"] else None  # noqa: E731
            ch["frame_emptied"] += bool(fa["frame"]) and not fb_["frame"]
            ch["frame_film_changed"] += top(fa) != top(fb_)
            ch["kind_changed"] += fa["kind"] != fb_["kind"]
            for tag, f in (("v1", fa), ("v1b", fb_)):
                if f["kind"] == "memory_search":
                    ch[f"memory_search_units_{tag}"] += 1
                    ch[f"memory_search_empty_{tag}"] += not f["frame"]
    res["frame_changes"] = ch
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(res, indent=1, default=str) + "\n")
    print(out_json)


if __name__ == "__main__" and len(sys.argv) > 2 and sys.argv[1] == "round3":
    round3(Path(sys.argv[2]))
