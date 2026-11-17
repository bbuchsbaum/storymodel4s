"""Hill-climbing round 5 (cache-only, no model calls): descriptive scores in film time and by
gold step, and one lever, `producer_order_fallback` (order places only what the producer left
unplaced).

Protocol: docs/plans/2026-11-16-cs2-mapping-object.md, "Hill-climbing round 5".
Usage (from tools/case-studies/recall-precision):
  python -m cs2.round5 describe OUT_JSON   # part A: descriptive scores, no pass/fail bar
  python -m cs2.round5 order OUT_JSON      # part B: the order fallback against its rule
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

from cs2 import baselines as B  # noqa: E402
from cs2 import decoder as DC  # noqa: E402
from cs2 import descriptive as DS  # noqa: E402
from cs2 import round4 as R  # noqa: E402
from cs2 import scorer as SC  # noqa: E402
from cs2.account import Account, Uncovered, WordCell  # noqa: E402
from cs2.projections import LocationDistribution  # noqa: E402
from cs2.run import bins_of, by_session, git_state, row_frame_mixes  # noqa: E402
from metrics import cluster_bootstrap  # noqa: E402

RUNS = (("filmfestival", 1), ("filmfestival", 2), ("sherlock", 1))
COMPARISON = (
    "duration",
    "lopo",
    "gold_context_ceiling",
    "gold_context_fitted",
    "tfidf",
    "hmm_tfidf",
)
EVIDENCE = "development data, engineering numbers; validation = the frozen-six run"
WITHIN_SECONDS = (5, 10, 20)


def producer_parts(X, acc):
    """Producer forecasts, their unit-grain location rows and the rows' frames, per session
    (the forecasts are those of hillclimb.forecasts)."""
    fmap, lds, mixes = {}, {}, {}
    for p, s in X.keys:
        rows = [g for g in X.gold[p] if g.session == s]
        ld = LocationDistribution.from_words(
            acc,
            acc.words[(p, s)],
            bins_of(rows),
            X.cfg["allocation"],
            X.rules["cross_scene_broad"],
        )
        mx = row_frame_mixes(acc, (p, s), rows)
        fbs = [B.frame_fallback(X.durs[p], m, X.cfg["frame_floor"]) for m in mx]
        fmap[(p, s)] = SC.producer_forecasts(ld, X.durs[p], X.cfg, fbs)
        lds[(p, s)], mixes[(p, s)] = list(ld.rows), mx
    return fmap, lds, mixes


def flat(X, m):
    """Per-session lists in the order score_map emits rows."""
    return [
        x for p, rows in X.gold.items() for s in by_session(rows) for x in m[(p, s)]
    ]


def cluster(X, pairs, median=False):
    """Participant-clustered summary of (participant, value) pairs: the mean over participants
    of each participant's mean (or median), with a cluster bootstrap CI."""
    by = {}
    for p, v in pairs:
        if v is not None:
            by.setdefault(p, []).append(float(v))
    if not by:
        return dict(n_rows=0, n_participants=0, mean=None, ci95=None)
    pm = {p: float(np.median(v) if median else np.mean(v)) for p, v in by.items()}
    b = X.cfg["bootstrap"]
    ci = cluster_bootstrap(
        pm, lambda xs: float(np.mean(xs)), n=b["n"], seed=b["seed"], level=b["level"]
    )
    rows = [v for vs in by.values() for v in vs]
    return dict(
        n_rows=len(rows),
        n_participants=len(pm),
        mean=float(np.mean(list(pm.values()))),
        ci95=ci,
        row_weighted=float(np.median(rows) if median else np.mean(rows)),
        per_participant=pm,
    )


def describe_forecast(X, clock, fl, sc, rows, located=False):
    """Top scene, top scene within one step, scene bits and film-time error of one forecast."""
    spec = [i for i, s in enumerate(sc) if s.scene_hit is not None]
    pid = lambda i: sc[i].participant  # noqa: E731
    te = [(pid(i), DS.time_error(fl[i], rows[i], X.valid, clock)) for i in spec]
    out = dict(
        scene_bits=cluster(X, [(pid(i), sc[i].scene_bits) for i in spec]),
        top_scene=cluster(X, [(pid(i), sc[i].scene_hit) for i in spec]),
        top_scene_within_1=cluster(X, [(pid(i), sc[i].within1) for i in spec]),
        film_time_error_seconds=dict(
            mean=cluster(X, te),
            median=cluster(X, te, median=True),
            rows_left_out_no_single_clock=sum(v is None for _, v in te),
        ),
    )
    if located:
        lo = [
            (pid(i), DS.time_error(fl[i], rows[i], X.valid, clock, located_only=True))
            for i in spec
            if clock.single.get(rows[i].work)
        ]
        out["film_time_error_seconds_located_only"] = dict(
            mean=cluster(X, lo),
            median=cluster(X, lo, median=True),
            rows_with_nothing_located_in_the_gold_work=sum(v is None for _, v in lo),
        )
    return out


def by_step(X, sc):
    """Scene-scored rows by the gold step from the previous scene-coded row of the work."""
    out = {}
    groups = list(SC.GOLD_STEPS) + ["out of order"]
    for k in groups:
        v = [
            s
            for s in sc
            if s.gold_step is not None
            and (
                s.gold_step in SC.OUT_OF_ORDER
                if k == "out of order"
                else s.gold_step == k
            )
        ]
        if v:
            out[k] = dict(
                n=len(v),
                top_scene=cluster(X, [(s.participant, s.scene_hit) for s in v]),
                top_scene_within_1=cluster(X, [(s.participant, s.within1) for s in v]),
                scene_bits=cluster(X, [(s.participant, s.scene_bits) for s in v]),
            )
    return out


def distance_1_misses(X, clock, ldrows, sc, rows):
    """The producer's misses one scene step from the gold: how far, in seconds, the source
    units it named in its top scene lie from the gold scene set."""
    vals, no_span, no_clock, n = [], 0, 0, 0
    for i, (g, s) in enumerate(zip(rows, sc)):
        if s.scene_bits is None or R.category(s) != "miss":
            continue
        ix = {x: j for j, x in enumerate(X.valid[g.work])}
        if min(abs(ix[s.top_scene] - ix[x]) for x in g.scenes if x in ix) != 1:
            continue
        n += 1
        gold = DS.gold_stretches(g, X.valid, clock)
        if gold is None:
            no_clock += 1
            continue
        r = DS.located_span(ldrows[i], X.comp, s.top_scene, gold, clock)
        if r is None:
            no_span += 1
        else:
            vals.append((g.participant, r))
    out = dict(
        n=n,
        with_a_located_span=len(vals),
        top_scene_has_no_unit_located_mass=no_span,
        left_out_no_single_clock=no_clock,
    )
    for j, name in enumerate(("expected_distance", "nearest_edge_gap")):
        d = dict(median_seconds=cluster(X, [(p, r[j]) for p, r in vals], median=True))
        for t in WITHIN_SECONDS:
            d[f"within_{t}_s"] = cluster(X, [(p, r[j] <= t) for p, r in vals])
        out[name] = d
    return out


def describe(out_json):
    """Part A: descriptive scores for the producer and every comparison forecast."""
    state = git_state()
    res = dict(
        schema="storymodel4s.case-study-2.hillclimb-r5-descriptive/v1",
        **state,
        model_calls=0,
        evidence=EVIDENCE,
        note="descriptive scores; no pass/fail bar and no decision rests on them",
        runs={},
    )
    X = {}
    for name, k in RUNS:
        x = X.setdefault(name, R.Corpus(name))
        clock = DS.film_clock(x.comp)
        rows = x.rows()
        fmap, lds, _ = producer_parts(x, x.account(k))
        named = dict(producer=fmap, **{b: x.fc[b] for b in COMPARISON})
        if x.account(k).framing_work:
            named["pass1_only"] = R.pass1_only(x, k)
        scores = {n_: x.score(m) for n_, m in named.items()}
        res["runs"][f"{name}-s{k}"] = dict(
            forecasts={
                n_: describe_forecast(
                    x, clock, flat(x, m), scores[n_], rows, located=n_ == "producer"
                )
                for n_, m in named.items()
            },
            producer_distance_1_misses=distance_1_misses(
                x, clock, flat(x, lds), scores["producer"], rows
            ),
            by_gold_step={
                n_: by_step(x, scores[n_]) for n_ in ("producer", "hmm_tfidf")
            },
            works_without_a_single_clock=sorted(
                w for w, ok in clock.single.items() if not ok
            ),
        )
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(res, indent=1, default=str) + "\n")
    print(out_json)


# ---------------------------------------------------------------- part B: the order fallback

LEVER = "producer_order_fallback"


def order_forecasts(X, fmap, mixes, pick):
    """The order fallback per row, with round 4's transitions and the given alpha per
    participant; also the decoder (for order_prior_only)."""
    dcd = R.Decoder(X, fmap)
    index = dcd.sp.index()
    out = {}
    for (p, s), q in dcd.q.items():
        nbr = DC.leave_row_out(dcd.sp, *dcd.TI[p], q, dcd.d[p], pick(dcd)[p])
        out[(p, s)] = [
            DC.order_fallback(f, m, X.cfg["frame_floor"], nbr[t], dcd.sp, index)
            for t, (f, m) in enumerate(zip(fmap[(p, s)], mixes[(p, s)]))
        ]
    return out, dcd


def lopo_alpha(dcd):
    """Round 4's pick, unchanged: each participant's alpha minimises the DECODER's objective
    over the other participants. Nothing is fitted to the order fallback."""
    if not hasattr(dcd, "_pick"):
        sc, obj = dcd.grid()
        dcd._pick, dcd._all = dcd.lopo(sc, obj)[0], dcd.all_alpha(obj)
    return dcd._pick


def null_check(X, acc, alphas):
    """A producer with no references and no frames, through the same path: its order fallback
    must equal the order prior alone on every cell and on every scored term, exactly."""
    null = Account(
        X.comp,
        {},
        {
            key: [
                WordCell(c.index, c.onset, c.unit, Uncovered(c.unit))
                for c in acc.words[key]
            ]
            for key in X.keys
        },
    )
    fmap, _, mixes = producer_parts(X, null)
    bmap, dcd = order_forecasts(X, fmap, mixes, lambda _: alphas)
    prior = dcd.forecasts(None)
    a, b = flat(X, bmap), flat(X, prior)
    cells = float(max(np.abs(x.post - y.post).max() for x, y in zip(a, b)))
    bits = 0.0
    for x, y in zip(X.score(bmap), X.score(prior)):
        for t in ("film_bits", "work_bits", "scene_bits"):
            u, v = getattr(x, t), getattr(y, t)
            if (u is None) != (v is None):
                raise SystemExit("null check: scored terms differ")
            if u is not None:
                bits = max(bits, abs(u - v))
    return dict(
        rows=len(a),
        max_abs_cell_difference=cells,
        max_abs_bits_difference=bits,
        order_mass_min=float(min(x.order_mass for x in a)),
    )


def paired_rows(a, b, term, keep, band=None):
    """Participant-paired difference a - b on a term, over the rows `keep` selects."""
    sa, sb = [s for s in a if keep(s)], [s for s in b if keep(s)]
    out = R.paired(sa, sb, term, band, "less")
    out["n_rows"] = sum(getattr(s, term) is not None for s in sa)
    return out


def top_share(sc, key="scene_hit"):
    v = [getattr(s, key) for s in sc if s.scene_hit is not None]
    return float(np.mean(v)) if v else None


def write_order_rows(path, prod, lev, fl):
    """Per row, no text: what the producer and the lever scored, and the mass order placed."""
    import csv

    path.parent.mkdir(parents=True, exist_ok=True)
    num = lambda x: "" if x is None else f"{x:.6f}"  # noqa: E731
    with open(path, "w", newline="") as f:
        w = csv.writer(f, delimiter="\t")
        w.writerow(["participant", "session", "row", "cls", "gold_step", "producer_scene_bits",
                    "lever_scene_bits", "producer_top_scene", "lever_top_scene",
                    "producer_scene_hit", "lever_scene_hit", "order_mass",
                    "order_mass_unassigned", "order_mass_within", "top_source"])
        for a, b, x in zip(prod, lev, fl):
            w.writerow([a.participant, a.session, a.row, a.cls, a.gold_step or "",
                        num(a.scene_bits), num(b.scene_bits),
                        "" if a.top_scene is None else a.top_scene,
                        "" if b.top_scene is None else b.top_scene,
                        "" if a.scene_hit is None else int(a.scene_hit),
                        "" if b.scene_hit is None else int(b.scene_hit),
                        num(x.order_mass), num(x.order_mass_unassigned),
                        num(x.order_mass_within), x.top_source])


def order_variant(X, k, pick, bands=None, rows_out=None):
    acc = X.account(k)
    fmap, _, mixes = producer_parts(X, acc)
    bmap, dcd = order_forecasts(X, fmap, mixes, pick)
    named = dict(
        producer=fmap,
        **{LEVER: bmap},
        order_prior_only=dcd.forecasts(None),
        hmm_tfidf=X.fc["hmm_tfidf"],
        gold_context_fitted=X.fc["gold_context_fitted"],
    )
    if acc.framing_work:
        named["pass1_only"] = R.pass1_only(X, k)
    sc = {n: X.score(m) for n, m in named.items()}
    prod, lev = sc["producer"], sc[LEVER]
    clock, rows, terms = DS.film_clock(X.comp), X.rows(), R.TERMS[X.wa]
    out = dict(forecasts={}, tests={}, by_gold_step={}, categories={})
    for n, s in sc.items():
        d = describe_forecast(X, clock, flat(X, named[n]), s, rows)
        f = dict(
            **{t: R.tmean(s, t) for t in terms},
            top_scene=top_share(s),
            top_scene_participant_mean=d["top_scene"]["mean"],
            top_scene_within_1=top_share(s, "within1"),
            film_time_error_seconds=dict(
                mean=d["film_time_error_seconds"]["mean"]["mean"],
                median=d["film_time_error_seconds"]["median"]["mean"],
            ),
            nonfilm_mass_memory_search=R.nonfilm_mass(s, "memory_search"),
            film_term=R.film_split(s),
        )
        if X.wa:
            st = SC.strata(s, X.comp, True)["film_switch_f1"]
            f["switch"] = dict(f1=st["f1"], f1_tolerance_1_row=st["f1_tolerance_1_row"])
        out["forecasts"][n] = f
    for t in terms:
        out["tests"][t] = R.paired(lev, prod, t, (bands or {}).get(t), "less")
    out["tests"]["scene_bits_out_of_order_rows"] = paired_rows(
        lev,
        prod,
        "scene_bits",
        lambda s: s.gold_step in SC.OUT_OF_ORDER,
        (bands or {}).get("scene_bits"),
    )
    out["tests"]["top_scene_points"] = dict(
        producer=top_share(prod),
        lever=top_share(lev),
        difference=top_share(lev) - top_share(prod),
    )
    for step in list(SC.GOLD_STEPS) + ["out of order"]:
        ii = [
            i
            for i, s in enumerate(prod)
            if s.gold_step is not None
            and (
                s.gold_step in SC.OUT_OF_ORDER
                if step == "out of order"
                else s.gold_step == step
            )
        ]
        if ii:
            out["by_gold_step"][step] = {
                "n": len(ii),
                **{
                    f"{n}_{key}": float(np.mean([getattr(s[i], attr) for i in ii]))
                    for n, s in (("producer", prod), ("lever", lev))
                    for key, attr in (
                        ("scene_bits", "scene_bits"),
                        ("top_scene", "scene_hit"),
                        ("top_scene_within_1", "within1"),
                    )
                },
            }
    fl = flat(X, bmap)
    if rows_out is not None:
        write_order_rows(rows_out, prod, lev, fl)
        out["rows_file"] = str(rows_out)
    idx = [i for i, s in enumerate(prod) if s.scene_bits is not None]
    for cat in ("miss", "hit", "fallback"):
        ii = [i for i in idx if R.category(prod[i]) == cat]
        out["categories"][cat] = (
            dict(n=len(ii))
            if not ii
            else dict(
                n=len(ii),
                producer_scene_bits=float(np.mean([prod[i].scene_bits for i in ii])),
                lever_scene_bits=float(np.mean([lev[i].scene_bits for i in ii])),
                lever_top_scene=float(np.mean([lev[i].scene_hit for i in ii])),
                mean_order_mass=float(np.mean([fl[i].order_mass for i in ii])),
            )
        )
    om = np.array([f.order_mass for f in fl])
    src = {}
    for name in ("located", "order, nothing located", "order over located"):
        ii = [i for i in idx if fl[i].top_source == name]
        src[name] = (
            dict(n=len(ii))
            if not ii
            else dict(
                n=len(ii),
                share_of_rows=len(ii) / len(idx),
                producer_top_scene=float(np.mean([prod[i].scene_hit for i in ii])),
                lever_top_scene=float(np.mean([lev[i].scene_hit for i in ii])),
                producer_scene_bits=float(np.mean([prod[i].scene_bits for i in ii])),
                lever_scene_bits=float(np.mean([lev[i].scene_bits for i in ii])),
            )
        )
    out["order_mass"] = dict(
        rows=len(fl),
        mean=float(om.mean()),
        median=float(np.median(om)),
        share_of_rows_above_half=float((om > 0.5).mean()),
        mean_unassigned=float(np.mean([f.order_mass_unassigned for f in fl])),
        mean_within_a_named_work_or_set=float(
            np.mean([f.order_mass_within for f in fl])
        ),
        scene_scored_rows_by_top_source=src,
        note="per row, the mass order placed: the unassigned share, and mass placed inside a "
        "work or scene set the producer named; located mass is the producer's, unchanged",
    )
    out["alpha"] = dict(
        per_participant=dict(pick(dcd)),
        note="round 4's pick, unchanged; nothing is fitted to the lever",
    )
    out["null_producer_equals_order_prior_only"] = null_check(X, acc, dict(pick(dcd)))
    return out, dcd


def order(out_json):
    state = git_state()
    res = dict(
        schema="storymodel4s.case-study-2.hillclimb-r5/v1",
        **state,
        model_calls=0,
        evidence=EVIDENCE,
        lever=LEVER,
        status="a candidate SECONDARY forecast; never the mapping object",
        corpora={},
    )
    ff, sh = R.Corpus("filmfestival"), R.Corpus("sherlock")
    s1, s2 = ff.score(ff.producer(1)), ff.score(ff.producer(2))
    bands = {}
    for t in R.TERMS[True]:
        a, b = R.pmean(s1, t), R.pmean(s2, t)
        d = [b[p] - a[p] for p in ff.participants]
        bands[t] = 2 * float(np.std(d, ddof=1)) / math.sqrt(len(d))
    res["pooled_bands"] = bands
    F = res["corpora"]["filmfestival"] = {}
    rows = out_json.parent / "order-rows"
    F["sample_1"], d1 = order_variant(ff, 1, lopo_alpha, bands, rows / "filmfestival-s1.tsv")
    F["sample_2"], _ = order_variant(ff, 2, lopo_alpha, bands, rows / "filmfestival-s2.tsv")
    a_ff = d1._all  # the FilmFestival value: the minimiser over all ten participants
    S = res["corpora"]["sherlock"] = {}
    S["part_order_run"], _ = order_variant(
        sh,
        1,
        lambda dcd: {p: a_ff for p in sh.participants},
        dict(scene_bits=bands["scene_bits"]),
        rows / "sherlock-part-order.tsv",
    )
    res["sherlock_alpha"] = a_ff
    # sensitivity, not part of the rule: the gold-blind windows, every fitted quantity refitted
    ffw, shw = R.Corpus("filmfestival", "median"), R.Corpus("sherlock", "median")
    ffw._acc, shw._acc = ff._acc, sh._acc
    W = res["sensitivity_gold_blind_windows"] = dict(
        note="not part of the rule: the same lever on fixed windows (median gold-row "
        "duration) labelled by the gold row they overlap most; transitions and alpha "
        "refitted on the windowed rows as in round 4")
    W["filmfestival_sample_1"], dw = order_variant(ffw, 1, lopo_alpha)
    W["filmfestival_sample_1"]["window_seconds"] = ffw.window_seconds
    W["sherlock_part_order_run"], _ = order_variant(
        shw, 1, lambda dcd: {p: dw._all for p in shw.participants})
    W["sherlock_part_order_run"]["window_seconds"] = shw.window_seconds
    p, q = F["sample_1"], S["part_order_run"]
    band = bands["scene_bits"]
    sc = p["tests"]["scene_bits"]
    c1 = sc["delta"] < 0 and sc["p"] <= 0.01 and abs(sc["delta"]) > band
    c2 = all(p["tests"][t]["delta"] <= bands[t] for t in ("work_bits", "film_bits"))
    c3 = all(
        x["tests"]["scene_bits_out_of_order_rows"]["delta"] <= band for x in (p, q)
    )
    c4 = all(x["tests"]["top_scene_points"]["difference"] >= -0.01 for x in (p, q))
    sq = q["tests"]["scene_bits"]
    c5 = sq["delta"] < 0 and sq["p_one_sided"] <= 0.05
    res["conditions"] = {
        "1_filmfestival_scene_pooled": bool(c1),
        "2_work_and_film_not_worse": bool(c2),
        "3_out_of_order_rows_not_worse": bool(c3),
        "4_top_scene_within_1_point": bool(c4),
        "5_sherlock_same_direction": bool(c5),
    }
    res["verdict"] = (
        "KEEP as a secondary forecast" if c1 and c2 and c3 and c4 and c5 else "DROP"
    )
    res["caveat"] = (
        "Sherlock's development rows were used to diagnose round 4, so condition 5 "
        "is a check and not an independent confirmation; confirmation can only "
        "come from sealed data, as a pre-registered secondary forecast"
    )
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(res, indent=1, default=str) + "\n")
    print(out_json)


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "describe":
        describe(Path(sys.argv[2]))
    elif len(sys.argv) > 2 and sys.argv[1] == "order":
        order(Path(sys.argv[2]))
    else:
        raise SystemExit(__doc__)
