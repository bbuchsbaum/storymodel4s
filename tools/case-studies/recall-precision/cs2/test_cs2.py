"""Named invariant and known-answer tests for case study 2 (synthetic fixtures only).

Run from tools/case-studies/recall-precision:  python -m cs2.test_cs2 [--only NAME] [--json]
No recall or story text is used: units, words and references are synthetic.
Each design invariant is one named test; cs2/mutations.py removes each guard and records which
named test fails.
"""

from __future__ import annotations

import json
import math
import sys
import traceback
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from cs2 import baselines as B  # noqa: E402
from cs2 import scorer as SC  # noqa: E402
from cs2.account import (  # noqa: E402
    Account,
    AccountError,
    Attribution,
    Comparison,
    Destination,
    Hypothesis,
    Member,
    NonFilm,
    PositionReference,
    Reference,
    RefSample,
    Shape,
    StateSample,
    Uncovered,
    WordCell,
    WorkReference,
)
from cs2.projections import (  # noqa: E402
    Bin,
    CoverageMeasure,
    bin_ledger,
    LocationDistribution,
    ProjectionError,
    correspondence,
    is_film,
    location_contribution,
    part_mass,
    word_ledger,
)
from cs2.source import (  # noqa: E402
    Composition,
    GoldRow,
    Occurrence,
    Part,
    Segment,
    SourceError,
    SourceUnit,
    Work,
)

CFG = SC.load_config()


# ---------------------------------------------------------------- synthetic fixture
#
# Parts p1, p2. Work A has two occurrences (one per part, like the lobby cartoon); work B lies on
# p1; work C is one occurrence spanning p1 and p2 (like Sherlock's two runs).


def fixture(unit_prefix=""):
    U = lambda x: unit_prefix + x  # noqa: E731
    works = (
        Work("A", "Cartoon", speech=None),
        Work("B", "Film B", speech=True),
        Work("C", "Film C", speech=False, excerpt=True),
    )
    parts = (Part("p1", 0, 100), Part("p2", 0, 100))
    occ = (
        Occurrence("A@p1", "A", (Segment("p1", 0, 10),), 1),
        Occurrence("B@p1", "B", (Segment("p1", 10, 60),), 2),
        Occurrence("C@p1p2", "C", (Segment("p1", 60, 100), Segment("p2", 10, 100)), 3),
        Occurrence("A@p2", "A", (Segment("p2", 0, 10),), 4),
    )
    units = (
        SourceUnit(U("a1"), "A@p1", "p1", 0, 5, 1),
        SourceUnit(U("a2"), "A@p1", "p1", 5, 10, 2),
        SourceUnit(U("b1"), "B@p1", "p1", 10, 20, 5, title_card=True),
        SourceUnit(U("b2"), "B@p1", "p1", 20, 40, 6),
        SourceUnit(U("b3"), "B@p1", "p1", 40, 60, 7),
        SourceUnit(U("c1"), "C@p1p2", "p1", 60, 80, 8),
        SourceUnit(U("c2"), "C@p1p2", "p1", 80, 100, 8),
        SourceUnit(U("c3"), "C@p1p2", "p2", 10, 50, 9),
        SourceUnit(U("c4"), "C@p1p2", "p2", 50, 100, 10),
        SourceUnit(U("a3"), "A@p2", "p2", 0, 5, 3),
        SourceUnit(U("a4"), "A@p2", "p2", 5, 10, 4),
    )
    return Composition("synthetic", works, parts, occ, units)


def H(shape, *constituents):
    return Hypothesis(shape, tuple(frozenset(c) for c in constituents))


def single(*units):
    return H(Shape.SINGLE, units)


def ref(rid, *members):
    return Reference(rid, tuple(Member(t, w) for t, w in members))


def cells(spec):
    """spec: list of (onset, unit, sample) -> WordCells."""
    return [WordCell(i, t, u, s) for i, (t, u, s) in enumerate(spec)]


def rich_account(prefix="", rename=lambda r: r):
    comp = fixture(prefix)
    P = lambda x: prefix + x  # noqa: E731
    refs = [
        ref(rename("r1"), (single(P("b2")), 0.8)),
        ref(rename("r2"), (single(P("b2")), 0.5), (single(P("c1"), P("c3")), 0.3)),
        ref(rename("r3"), (H(Shape.BROAD, [P("b2"), P("b3")]), 0.9)),
        ref(rename("r4"), (H(Shape.JOINT, [P("a1")], [P("a3")]), 1.0)),
        ref(rename("r5"), (Destination("external"), 0.6), (single(P("c4")), 0.4)),
        ref(rename("r6"), (Destination("in_work_outside", "C"), 0.7)),
        WorkReference(rename("r7"), "B", 0.9),
        PositionReference(rename("r8"), "last", "C", 0.5),
        PositionReference(rename("r9"), "first", None, 1.0),
    ]
    refs = {r.id: r for r in refs}
    spec = [
        (1.0, "u1", RefSample(rename("r1"))),
        (1.5, "u1", RefSample(rename("r1"))),
        (2.0, "u1", RefSample(rename("r2"))),
        (2.5, "u2", StateSample("u2", NonFilm.MEMORY_SEARCH.value)),
        (3.0, "u2", Uncovered("u2")),
        (5.0, "u3", RefSample(rename("r3"))),
        (5.5, "u3", RefSample(rename("r4"))),
        (6.0, "u4", RefSample(rename("r5"))),
        (6.5, "u4", RefSample(rename("r6"))),
        (7.0, "u5", RefSample(rename("r7"))),
        (7.5, "u5", RefSample(rename("r8"))),
        (8.0, "u5", RefSample(rename("r9"))),
        (8.5, "", Uncovered("")),
    ]
    acc = Account(comp, refs, {("P1", "s1"): cells(spec)})
    bins = [Bin(0, 2.4), Bin(2.4, 4), Bin(4, 5), Bin(5, 6), Bin(6, 7), Bin(7, 9)]
    return acc, bins


def ld_of(acc, bins, allocation="units"):
    return LocationDistribution.from_words(
        acc, acc.words[("P1", "s1")], bins, allocation
    )


def close(a, b, tol=1e-12):
    assert abs(a - b) <= tol, (a, b)


# ---------------------------------------------------------------- the design's invariants


def test_row_sums_to_one():
    acc, bins = rich_account()
    for allocation in ("units", "seconds"):
        ld = ld_of(acc, bins, allocation)
        sampled = [r for r in ld.rows if r.state == "sampled"]
        assert len(sampled) == 5
        for r in sampled:
            assert all(v >= 0 for v in r.mass.values())
            assert abs(math.fsum(r.mass.values()) - 1) <= 1e-12, r.mass
        for g in ("scene", "occurrence", "work"):
            for r in ld.coarsen(g).rows:
                if r.state == "sampled":
                    assert abs(math.fsum(r.mass.values()) - 1) <= 1e-12


def test_joint_refused_from_distribution():
    comp = fixture()
    joint = H(Shape.JOINT, ["a1"], ["a3"])
    try:
        location_contribution(joint, comp)
    except ProjectionError as e:
        assert e.reason == "joint-refused"
    else:
        raise AssertionError("a joint hypothesis entered a location")
    acc, bins = rich_account()
    row = ld_of(acc, bins).rows[3]  # the bin holding r3 (broad) and r4 (joint)
    assert not any(is_film(k) for k in row.mass), row.mass
    close(row.mass[("held_out", "joint", "A")], 0.5)
    # the broad member (b2, b3) is located by its own rule (tested separately)


def test_coarsening_commutes_side_states_unchanged():
    acc, bins = rich_account()
    ld = ld_of(acc, bins)
    direct = ld.coarsen("work")
    chain = ld.coarsen("scene").coarsen("occurrence").coarsen("work")
    via_occ = ld.coarsen("occurrence").coarsen("work")
    for a, b, c, u in zip(direct.rows, chain.rows, via_occ.rows, ld.rows):
        assert a.state == b.state == c.state == u.state
        assert set(a.mass) == set(b.mass) == set(c.mass)
        for k in a.mass:
            close(a.mass[k], b.mass[k])
            close(a.mass[k], c.mass[k])
        side_u = {k: v for k, v in u.mass.items() if not is_film(k)}
        side_w = {k: v for k, v in a.mass.items() if not is_film(k)}
        assert side_u == side_w, (side_u, side_w)
    # occurrence grain keeps the cartoon's two occurrences apart
    occ = ld.coarsen("occurrence")
    keys = {k for r in occ.rows for k in r.mass if is_film(k)}
    assert ("at", "occurrence", "C@p1p2") in keys


def test_work_mass_only_on_its_parts():
    # Known answer on the projection: a reference over c1 (work C on p1) and c3 (work C on p2)
    # puts half of C's mass on each part; the cartoon's two occurrences put A's mass on p1 and
    # p2; work B never reaches p2. Every (work, part) key lies on a part that holds the work.
    comp = fixture()
    refs = {
        "c": ref("c", (single("c1", "c3"), 1.0)),
        "a": ref("a", (single("a2"), 0.5), (single("a4"), 0.5)),
        "b": ref("b", (single("b3"), 1.0)),
    }
    spec = [(1.0, "u1", RefSample("c")), (2.0, "u2", RefSample("a")), (3.0, "u3", RefSample("b"))]
    acc = Account(comp, refs, {("P", "s"): cells(spec)})
    rows = LocationDistribution.from_words(acc, acc.words[("P", "s")], [Bin(0, 9)]).rows
    pm = part_mass(rows[0], comp)
    third = 1 / 3
    expect = {("C", "p1"): third / 2, ("C", "p2"): third / 2, ("A", "p1"): third / 2,
              ("A", "p2"): third / 2, ("B", "p1"): third}
    assert set(pm) == set(expect), pm
    for k, v in expect.items():
        close(pm[k], v)
    for (w, p) in pm:
        assert p in comp.parts_of_work(w), (w, p)


def test_unit_on_foreign_part_refused():
    # a unit that claims a part which does not hold its occurrence is refused
    bad_units = fixture().units + (SourceUnit("bx", "B@p1", "p2", 20, 30, 6),)
    try:
        Composition(
            "bad", fixture().works, fixture().parts, fixture().occurrences, bad_units
        )
    except SourceError:
        pass
    else:
        raise AssertionError("a unit on a foreign part was accepted")


def test_relabel_and_reorder_invariance():
    # Several references put mass on one unit within one bin, with weights whose naive float sum
    # depends on order; relabelling reference and unit ids and reversing word and reference order
    # must leave every mass bit-identical.
    ws = [0.1, 0.7, 0.2, 0.3, 0.6, 0.15, 0.35, 0.45, 0.05, 0.9, 0.33, 0.27]

    def build(prefix, rename, reverse):
        comp = fixture(prefix)
        refs = {}
        spec = []
        for i, w in enumerate(ws):
            rid = rename(f"q{i:02d}")
            refs[rid] = ref(rid, (single(prefix + "b2"), w))
            spec.append((1.0 + i * 0.01, "u1", RefSample(rid)))
        if reverse:
            spec = spec[::-1]
            refs = dict(reversed(list(refs.items())))
        acc = Account(comp, refs, {("P1", "s1"): cells(spec)})
        return LocationDistribution.from_words(
            acc, acc.words[("P1", "s1")], [Bin(0, 9)]
        )

    base = build("", lambda r: r, False)
    other = build("zz-", lambda r: "x" + r[::-1], True)  # reversed ids sort differently
    a, b = base.rows[0].mass, other.rows[0].mass
    unmap = {("at", "unit", "zz-" + k[2]) if is_film(k) else k: k for k in a}
    assert {unmap[k]: v for k, v in b.items()} == a  # exact, not approximate
    acc, bins = rich_account()
    acc2, _ = rich_account(prefix="zz-", rename=lambda r: "id-" + r[::-1])
    for r1, r2 in zip(ld_of(acc, bins).rows, ld_of(acc2, bins).rows):
        m2 = {
            ("at", "unit", k[2][3:]) if is_film(k) else k: v for k, v in r2.mass.items()
        }
        assert m2 == r1.mass


def test_weight_sum_above_one_refused():
    try:
        ref("w", (single("b2"), 0.6), (single("b3"), 0.5))
    except AccountError as e:
        assert e.reason == "weight-sum-above-1"
    else:
        raise AssertionError("a weight sum above 1 was accepted")
    r = ref("w", (single("b2"), 0.6), (single("b3"), 0.4 + 1e-12))
    assert r.unassigned == 0.0  # inside the snap tolerance the remainder is exactly 0
    close(ref("w", (single("b2"), 0.25)).unassigned, 0.75)


def test_every_row_traces_to_a_reference():
    acc, bins = rich_account()
    for r in ld_of(acc, bins).rows:
        if r.state != "sampled":
            continue
        assert r.samples
        for k, v in r.mass.items():
            src = r.trace.get(k)
            assert src, (k, "has no source")
            if is_film(k):
                assert any(
                    isinstance(s, RefSample)
                    and any(
                        isinstance(m.target, Hypothesis) and k[2] in m.target.support
                        for m in acc.references[s.ref].members
                    )
                    for s in src
                ), k


def test_not_applicable_word_stays_in_denominators():
    comp = fixture()
    refs = {"r1": ref("r1", (single("b2"), 1.0))}
    spec = [(1.0, "u1", RefSample("r1"))] * 3 + [
        (1.5, "u2", StateSample("u2", NonFilm.COMMENTARY.value))
    ]
    acc = Account(comp, refs, {("P1", "s1"): cells(spec)})
    bins = [Bin(0, 2)]
    row = LocationDistribution.from_words(acc, acc.words[("P1", "s1")], bins).rows[0]
    close(row.mass[("nonfilm", "commentary")], 0.5)
    close(row.mass[("at", "unit", "b2")], 0.5)
    led = word_ledger(acc.words[("P1", "s1")], bins)
    assert led["words_in_bins"] == 4 and led["not_applicable_words"] == 1
    close(led["not_applicable_share"], 0.25)


def test_uncovered_words_never_create_unassigned_mass():
    # ADR 0031 section 3: uncovered words are ledger counts, never samples. Unassigned mass is
    # only a reference's own remainder; a bin of uncovered words alone is 'only_uncovered'.
    comp = fixture()
    refs = {"r1": ref("r1", (single("b2"), 1.0)), "r2": ref("r2", (single("c1"), 0.75))}
    spec = [(1.0, "u1", RefSample("r1"))]
    spec += [(1.1 + i / 100, "u1", Uncovered("u1")) for i in range(5)] + [(1.5, "", Uncovered(""))]
    spec += [(3.0, "u2", Uncovered("u2")), (3.5, "", Uncovered(""))]
    spec += [(5.0, "u3", RefSample("r2")), (5.1, "u3", Uncovered("u3"))]
    acc = Account(comp, refs, {("P", "s"): cells(spec)})
    ws = acc.words[("P", "s")]
    bins = [Bin(0, 2), Bin(2, 4), Bin(4, 6), Bin(8, 9)]
    rows = LocationDistribution.from_words(acc, ws, bins).rows
    assert rows[0].state == "sampled" and rows[0].mass == {("at", "unit", "b2"): 1.0}
    assert rows[1].state == "only_uncovered" and rows[1].mass == {} and rows[1].n_words == 2
    assert rows[2].mass[("unassigned",)] == 0.25  # the reference's own remainder only
    assert rows[3].state == "no_sample"
    assert bin_ledger([c for c in ws if bins[0].contains(c.onset)]) == dict(
        words=7, sampled_words=1, uncovered_unit_words=5, uncovered_outside_words=1
    )
    assert word_ledger(ws, bins)["uncovered_words"] == 9
    f = SC.producer_forecasts(
        LocationDistribution.from_words(acc, ws, bins), B.duration_prior(comp, CFG), CFG
    )
    assert f[1].no_sample and f[1].empty == "only_uncovered" and f[0].empty == ""
    assert CoverageMeasure.from_words(acc, ws, bins).rows[1].state == "only_uncovered"


def test_film_term_closes_nonfilm_hedge():
    # One gold row, scene 9 of work C, work identity not applicable (as on Sherlock).
    # Hedger: 0.1 on the gold scene and 0.9 on non-film states. Honest: 0.5 / 0.5 over two
    # scenes of C, one of them gold. The conditional scene term favours the hedger; the film
    # term must make the hedger worse overall.
    comp = fixture()
    dur = B.duration_prior(comp, CFG)
    valid = B.valid_scenes(comp, CFG)
    gold = [_gold("P", "s", 0, "specific", "C", {9})]
    hedge_refs = {"h": ref("h", (single("c3"), 1.0))}
    hedge = [(0.1, "u0", RefSample("h"))]
    hedge += [(0.2 + i / 100, f"n{i}", StateSample(f"n{i}", "memory_search")) for i in range(9)]
    honest_refs = {"o": ref("o", (single("c3"), 0.5), (single("c4"), 0.5))}
    honest = [(0.1, "u0", RefSample("o"))]
    out = {}
    for name, refs, spec in (("hedge", hedge_refs, hedge), ("honest", honest_refs, honest)):
        acc = Account(comp, refs, {("P", "s"): cells(spec)})
        ld = LocationDistribution.from_words(acc, acc.words[("P", "s")], [Bin(0, 1)])
        fc = SC.producer_forecasts(ld, dur, CFG)
        out[name] = SC.score_rows(gold, fc, comp, valid, work_applicable=False)[0]
    e, pi = CFG["epsilon"], CFG["nonfilm_fallback_rate"]
    close(out["hedge"].film_bits, -math.log2(1 - ((1 - e) * 0.9 + e * pi)))
    close(out["honest"].film_bits, -math.log2(1 - e * pi))
    fc_, f9 = dur.p_work("C"), dur.scene["C"][9]
    close(out["hedge"].scene_bits,
          -math.log2(((1 - e) * 0.1 + e * fc_ * f9) / ((1 - e) * 0.1 + e * fc_)))
    assert out["hedge"].scene_bits < out["honest"].scene_bits  # the hedge the term closes
    assert out["hedge"].total_bits > out["honest"].total_bits + 1.0
    assert out["hedge"].work_bits is None


def test_tfidf_forecast_known_answer():
    comp = fixture()
    dur = B.duration_prior(comp, CFG)
    docs = {6: ["dog"], 7: ["cat"], 8: ["sea"], 9: ["sky"], 10: ["sun"], 1: ["pop"],
            2: ["pop"], 3: ["pop"], 4: ["pop"]}
    lam = CFG["tfidf_lambda"]
    f = B.tfidf(comp, [["dog"], []], docs, CFG, dur)
    # only scene 6 matches: cosine 1 there, 0 elsewhere; work B gets all non-prior mass
    close(f[0].p_work("B"), (1 - lam) + lam * dur.p_work("B"))
    pb = f[0].p_work("B")
    close(f[0].scene["B"][6], ((1 - lam) + lam * dur.p_work("B") * dur.scene["B"][6]) / pb)
    assert f[1] is dur  # an empty query falls back to the prior


def test_title_only_gold_scene_dropped():
    comp = fixture()
    dur = B.duration_prior(comp, CFG)
    valid = B.valid_scenes(comp, CFG)
    gold = [_gold("P", "s", 0, "specific", "B", {5, 6}), _gold("P", "s", 1, "specific", "B", {5})]
    sc = SC.score_rows(gold, [dur, dur], comp, valid)
    assert sc[0].dropped_scenes == 1 and sc[1].dropped_scenes == 1
    close(sc[0].scene_bits, -math.log2(dur.scene["B"][6]))
    assert sc[1].scene_bits is None and sc[1].work_bits is not None


def _broad_row(units, broad_rule="scene-set"):
    comp = fixture()
    refs = {"g": ref("g", (H(Shape.BROAD, units), 0.8))}
    acc = Account(comp, refs, {("P", "s"): cells([(0.5, "u0", RefSample("g"))])})
    ld = LocationDistribution.from_words(acc, acc.words[("P", "s")], [Bin(0, 1)],
                                         broad_rule=broad_rule)
    return comp, ld


def test_broad_within_one_scene_is_located_at_the_scene():
    # c1 and c2 are both scene 8 of work C: the broad reference is scene 8's mass
    comp, ld = _broad_row(["c1", "c2"])
    row = ld.rows[0]
    close(row.mass[("at", "unit", "c1")], 0.4)
    close(row.mass[("at", "unit", "c2")], 0.4)
    assert row.allocated == {("at", "unit", "c1"): 0.4, ("at", "unit", "c2"): 0.4}  # labelled
    sc = ld.coarsen("scene").rows[0]
    close(sc.mass[("at", "scene", 8)], 0.8)
    assert not any(k[0] in ("held_out", "work_node") for k in row.mass)
    dur = B.duration_prior(comp, CFG)
    f = SC.producer_forecasts(ld, dur, CFG)[0]
    close(f.loc[8], 0.8)


def test_broad_across_scenes_goes_to_its_scene_set():
    # rule 'scene-set': c2 (scene 8) and c3 (scene 9) of work C -> the set {8, 9}, spread within
    # the set by the fallback restricted to it; P({9} | C) = F(9) / (F(8) + F(9)) when nothing
    # else is claimed
    comp, ld = _broad_row(["c2", "c3"])
    row = ld.rows[0]
    assert row.mass == {("unassigned",): row.mass[("unassigned",)],
                        ("scene_set", "C", (8, 9)): 0.8}
    dur = B.duration_prior(comp, CFG)
    f = SC.producer_forecasts(ld, dur, CFG)[0]
    e, fs = CFG["epsilon"], dur.scene["C"]
    close(f.p_work("C"), (1 - e) * 0.8 + f.u * dur.p_work("C"))
    exp = (1 - e) * 0.8 * fs[9] / (fs[8] + fs[9]) + f.u * dur.p_work("C") * fs[9]
    close(f.p_set_and_work({9}, "C"), exp)
    close(f.p_set_and_work({10}, "C"), f.u * dur.p_work("C") * fs[10])  # outside the set: 0


def test_broad_across_scenes_old_rule_is_the_work_node():
    # rule 'work-node' (previous rule, kept for comparison)
    comp, ld = _broad_row(["c2", "c3"], broad_rule="work-node")
    row = ld.rows[0]
    assert row.mass == {("unassigned",): row.mass[("unassigned",)], ("work_node", "broad", "C"): 0.8}
    assert not row.allocated
    dur = B.duration_prior(comp, CFG)
    f = SC.producer_forecasts(ld, dur, CFG)[0]
    e = CFG["epsilon"]
    close(f.p_work("C"), (1 - e) * 0.8 + f.u * dur.p_work("C"))  # counts toward P(work)
    close(f.p_set_and_work({9}, "C") / f.p_work("C"), dur.scene["C"][9])  # spread by fallback


def test_broad_crossing_works_is_held_out():
    comp, ld = _broad_row(["b3", "c1"])
    row = ld.rows[0]
    close(row.mass[("held_out", "broad", "")], 0.8)
    f = SC.producer_forecasts(ld, B.duration_prior(comp, CFG), CFG)[0]
    close(f.abstain, 1.0)  # held out with no single work: an abstention for scoring


# ---------------------------------------------------------------- further known answers


def test_no_sample_is_exclusive():
    acc, _ = rich_account()
    ld = ld_of(acc, [Bin(50, 60)])
    assert ld.rows[0].state == "no_sample" and ld.rows[0].mass == {}
    dur = B.duration_prior(acc.composition, CFG)
    f = SC.producer_forecasts(ld, dur, CFG)[0]
    assert f.no_sample and f.abstain == 1.0


def test_alternative_and_unit_allocation():
    acc, bins = rich_account()
    row = ld_of(acc, bins).rows[
        0
    ]  # words of r1 (twice) and r2: two distinct references
    # r1: 0.8 on b2, 0.2 unassigned; r2: 0.5 b2, 0.3 over {c1, c3}, 0.2 unassigned
    close(row.mass[("at", "unit", "b2")], 0.5 * 0.8 + 0.5 * 0.5)
    close(row.mass[("at", "unit", "c1")], 0.5 * 0.15)
    close(row.mass[("at", "unit", "c3")], 0.5 * 0.15)
    close(row.mass[("unassigned",)], 0.5 * 0.2 + 0.5 * 0.2)
    # seconds allocation: c1 is 20 s, c3 is 40 s
    rs = ld_of(acc, bins, "seconds").rows[0]
    close(rs.mass[("at", "unit", "c1")], 0.5 * 0.3 * 20 / 60)
    assert ld_of(acc, bins, "seconds").allocation == "seconds"
    try:
        ld_of(acc, bins, "frames")
    except ProjectionError:
        pass
    else:
        raise AssertionError("an undeclared allocation was accepted")
    try:
        ref("d", (single("b2"), 0.5), (single("b2"), 0.4))
    except AccountError as e:
        assert e.reason == "duplicate-members"
    else:
        raise AssertionError("duplicate members were accepted")


def test_coverage_is_not_a_distribution():
    acc, bins = rich_account()
    cm = CoverageMeasure.from_words(acc, acc.words[("P1", "s1")], bins)
    row = cm.rows[3]  # broad r3 (0.9 over b2, b3) and joint r4 (1.0 over a1 and a3)
    assert (
        row.cover[("at", "unit", "a1")] == 1.0
        and row.cover[("at", "unit", "a3")] == 1.0
    )
    assert (
        row.cover[("at", "unit", "b2")] == 0.9
        and row.cover[("at", "unit", "b3")] == 0.9
    )
    assert sum(row.cover.values()) > 1  # no sum constraint
    held = cm.rows[5].held
    assert (
        held[("work_ref", "B")] == 0.9
    )  # a work-level reference is held out, not spread
    try:
        CoverageMeasure.from_words(acc, acc.words[("P1", "s1")], bins, spread_work=True)
    except ProjectionError as e:
        assert e.reason == "work-spread-refused"
    else:
        raise AssertionError("spreading a work mention over seconds was accepted")
    try:
        SC.producer_forecasts(cm, B.duration_prior(acc.composition, CFG), CFG)
    except ProjectionError:
        pass
    else:
        raise AssertionError("a coverage measure entered the scorer as a distribution")


def test_correspondence_table():
    acc, _ = rich_account()
    acc.relations.extend([Comparison("r7", "r8", "contrast"), Attribution("B", "r1")])
    t = correspondence(acc)
    joint = [x for x in t if x.get("ref") == "r4"]
    assert {(x["constituent"], x["unit"], x["part"]) for x in joint} == {
        (0, "a1", "p1"),
        (1, "a3", "p2"),
    }
    assert sum(1 for x in t if "relation" in x) == 2


def _gold(p, s, i, cls, work, scenes=(), nonfilm=None, lo=None):
    lo = float(i) if lo is None else lo
    return GoldRow(p, s, i, lo, lo + 1, False, cls, work, frozenset(scenes), nonfilm)


def test_hierarchical_log_loss_known_answer():
    comp = fixture()
    dur = B.duration_prior(comp, CFG)
    # duration prior over valid scenes (b1 is title-only, so scene 5 is not a target):
    # A: 1,2,3,4 at 5 s each (20 s); B: 6 (20 s), 7 (20 s); C: 8 (40 s), 9 (40), 10 (50) = 130
    close(dur.p_work("B"), 40 / 190)
    close(dur.scene["C"][10], 50 / 130)
    assert 5 not in dur.scene["B"]
    refs = {"r": ref("r", (single("b2"), 1.0)), "w": WorkReference("w", "C", 1.0)}
    spec = [
        (0.5, "u1", RefSample("r")),
        (1.5, "u2", RefSample("w")),
        (2.5, "u3", Uncovered("u3")),
    ]
    acc = Account(comp, refs, {("P", "s"): cells(spec)})
    gold = [
        _gold("P", "s", 0, "specific", "B", {6}),
        _gold("P", "s", 1, "specific", "C", {9, 10}),
        _gold("P", "s", 2, "film_only", "C"),
        _gold("P", "s", 3, "nonfilm", None, nonfilm="gave_up"),
    ]
    ld = LocationDistribution.from_words(
        acc, acc.words[("P", "s")], [Bin(g.lo, g.hi) for g in gold]
    )
    fc = SC.producer_forecasts(ld, dur, CFG)
    valid = B.valid_scenes(comp, CFG)
    sc = SC.score_rows(gold, fc, comp, valid)
    e = CFG["epsilon"]
    # row 0: all mass on scene 6 of B; u = e
    pw = (1 - e) + e * dur.p_work("B")
    pset = (1 - e) + e * dur.p_work("B") * dur.scene["B"][6]
    close(sc[0].work_bits, -math.log2(pw))
    close(sc[0].scene_bits, -math.log2(pset / pw))
    # row 1: a work-level reference to C is spread within C by the fallback: P(S|C) = F(S|C)
    close(sc[1].scene_bits, -math.log2(dur.scene["C"][9] + dur.scene["C"][10]))
    # row 2: film-only scores the work term only; abstain (uncovered) gives the fallback
    assert sc[2].scene_bits is None
    close(sc[2].work_bits, -math.log2(dur.p_work("C")))
    # row 3: non-film is a stratum, not scored
    assert sc[3].work_bits is None and sc[3].scene_bits is None
    # work identity not applicable: the work term is n/a, never 0
    sc_na = SC.score_rows(gold, fc, comp, valid, work_applicable=False)
    assert all(s.work_bits is None for s in sc_na)
    close(sc_na[0].scene_bits, sc[0].scene_bits)


def test_abstain_everything_scores_the_baseline():
    acc, _ = rich_account()
    comp = acc.composition
    words = [(0.2 + i, f"u{i}", Uncovered(f"u{i}")) for i in range(6)]
    acc = Account(comp, {}, {("P", "s"): cells(words)})
    gold = [
        _gold("P", "s", 0, "specific", "C", {8}),
        _gold("P", "s", 1, "specific", "A", {1, 3}),
        _gold("P", "s", 2, "nonfilm", None, nonfilm="memory_search"),
        _gold("P", "s", 3, "film_search", "B"),
        _gold("P", "s", 4, "specific", "B", {6, 7}),
        _gold("P", "s", 9, "specific", "C", {10}, lo=40.0),  # no word: no sample
    ]
    dur = B.duration_prior(comp, CFG)
    valid = B.valid_scenes(comp, CFG)
    ld = LocationDistribution.from_words(
        acc, acc.words[("P", "s")], [Bin(g.lo, g.hi) for g in gold]
    )
    prod = SC.score_rows(gold, SC.producer_forecasts(ld, dur, CFG), comp, valid)
    base = SC.score_rows(gold, [dur] * len(gold), comp, valid)
    for p, b in zip(prod, base):
        for term in ("film_bits", "work_bits", "scene_bits", "total_bits"):
            x, y = getattr(p, term), getattr(b, term)
            assert (x is None) == (y is None)
            if x is not None:
                close(x, y)
    s = SC.skill(prod, base, CFG, "scene_bits")
    close(s["skill"], 0.0)


def test_lopo_excludes_scored_participant():
    comp = fixture()
    gold = {
        "P1": [_gold("P1", "s", 0, "specific", "C", {10})] * 5,
        "P2": [_gold("P2", "s", 0, "specific", "C", {8})] * 5,
    }
    f = B.lopo(comp, gold, "P1", CFG)
    a = CFG["lopo_alpha"]
    # only P2's five rows count: scene 10 keeps the smoothing alone
    close(f.scene["C"][10], a / (5 + 3 * a))
    close(f.scene["C"][8], (5 + a) / (5 + 3 * a))
    close(f.work["C"], (5 + a) / (5 + 3 * a))


def test_order_oracle_known_answer():
    comp = fixture()
    dur = B.duration_prior(comp, CFG)
    rows = [
        _gold("P", "s", 0, "specific", "B", {6}),
        _gold("P", "s", 1, "film_only", "B"),
        _gold("P", "s", 2, "specific", "C", {9}),
    ]
    f = B.order_oracle(comp, rows, CFG, dur)
    lam = CFG["order_oracle_lambda"]
    assert f[0] is dur
    close(f[1].p_work("B"), (1 - lam) + lam * dur.p_work("B"))
    # previous top scene 6, next valid scene 7: half each, mixed jointly with the prior
    pb = f[1].p_work("B")
    close(
        f[1].scene["B"][7],
        ((1 - lam) * 0.5 + lam * dur.p_work("B") * dur.scene["B"][7]) / pb,
    )
    close(math.fsum(f[1].scene["B"].values()), 1.0)
    close(math.fsum(f[1].work.values()), 1.0)


def test_unvalidatable_metrics_refused():
    for name in SC.REFUSED:
        try:
            SC.request_metric(name)
        except SC.UnvalidatableMetric:
            continue
        raise AssertionError(f"{name} was not refused")
    assert SC.request_metric("scene_log_loss") == "scene_log_loss"


def test_bootstrap_clusters_are_participants():
    comp = fixture()
    dur = B.duration_prior(comp, CFG)
    valid = B.valid_scenes(comp, CFG)
    gold = [
        _gold("P1", s, i, "specific", "B", {6}) for s in ("s1", "s2") for i in range(3)
    ]
    gold += [_gold(p, "s1", 0, "specific", "C", {8}) for p in ("P2", "P3")]
    sc = SC.score_rows(gold, [dur] * len(gold), comp, valid)
    summ = SC.summarize(sc, CFG, "scene_bits")
    assert summ["n_participants"] == 3 and summ["n_rows"] == 8


def test_frozen_participants_refused():
    import adapters.filmfestival as F

    def no_read(*a, **k):
        raise AssertionError("a frozen participant's file was opened")

    saved = (F._read_csv, F._read_tsv)
    F._read_csv = F._read_tsv = no_read
    try:
        for p in ("sub-06", "sub-09", "sub-11", "sub-12", "sub-13", "sub-20"):
            for call in (
                lambda: F.sessions(p),
                lambda: F.session_words(p, "run-01"),
                lambda: F.gold_rows(p),
            ):
                try:
                    call()
                except F.FrozenParticipant:
                    continue
                raise AssertionError(f"{p} was not refused")
    finally:
        F._read_csv, F._read_tsv = saved
    import adapters.sherlock as S

    for name in ("NN01_JEX_181931_final", "NN14_DSN_242031_final"):
        try:
            S.session_words(name)
        except SystemExit:
            continue
        raise AssertionError(f"{name} was not refused")


# ---------------------------------------------------------------- the FilmFestival producer


def _producer():
    from cs2 import ff_producer as P

    return P


def test_library_units_map_spans_to_words():
    P = _producer()
    from cs2.source import RecallWord

    words = [RecallWord(i, w, float(i)) for i, w in enumerate("he ran and then she naïve left".split())]
    text = P.transcript_of(words).rstrip("\n")
    # units: "he ran" [0, 6) and "then she naïve left" [11, 30); "and" is a consumed separator
    exp = {"transcript": {"canonicalText": text, "units": [
        {"u": 1, "text": "he ran", "spans": [{"start": 0, "endExclusive": 6}]},
        {"u": 2, "text": "then she naïve left", "spans": [{"start": 11, "endExclusive": 30}]},
    ]}}
    assert P.library_units(exp, words) == [(0, 1), (3, 6)]
    bad = {"transcript": {"canonicalText": text, "units": [
        {"u": 1, "text": "e ran", "spans": [{"start": 1, "endExclusive": 6}]}]}}
    for e in (bad, {"transcript": {"canonicalText": text + " x", "units": []}}):
        try:
            P.library_units(e, words)
        except ValueError:
            continue
        raise AssertionError("a span cutting a word or a foreign transcript was accepted")


def test_anchor_matching():
    P = _producer()
    u = P.toks("he ran to the shop and he ran home")
    assert P.match_anchor(u, P.toks("to the Shop")) == (2, None)
    assert P.match_anchor(u, P.toks("he ran")) == (None, "anchor-ambiguous")
    assert P.match_anchor(u, P.toks("he flew")) == (None, "anchor-not-found")
    assert P.match_anchor(u, []) == (None, "anchor-empty")


def test_members_shapes_and_frame_check():
    P = _producer()
    comp = fixture()
    rowmap = {1: "b2", 2: "b3", 3: "c1", 4: "c2"}
    refused, conv = {}, dict(shapes={}, broad_cases={})

    def refuse(why):
        refused[why] = refused.get(why, 0) + 1

    m = P.members(dict(occurrence="joint", alts=[{"w": 0.5, "iv": [[1, 1], [2, 2]]}]), rowmap,
                  "B", 0.8, comp, refuse, conv)
    assert m[0].target.shape is Shape.JOINT and abs(m[0].weight - 0.4) < 1e-12
    m = P.members(dict(mode="gist", extent="stretch", alts=[{"w": 1, "iv": [[1, 2]]}]), rowmap,
                  "B", 1.0, comp, refuse, conv)
    assert m[0].target.shape is Shape.BROAD and conv["broad_cases"] == {"scene_set": 1}
    m = P.members(dict(occurrence="repeated", alts=[{"w": 1, "iv": [[3, 3], [4, 4]]}]), rowmap,
                  "C", 1.0, comp, refuse, conv)
    assert m[0].target.shape is Shape.SINGLE and len(m[0].target.constituents) == 2
    # rows of work C under a frame of work B: refused, typed and counted
    assert P.members(dict(alts=[{"w": 1, "iv": [[3, 3]]}]), rowmap, "B", 1.0, comp, refuse,
                     conv) is None
    assert P.members(dict(alts=[{"w": 1, "iv": [[9, 9]]}]), rowmap, "B", 1.0, comp, refuse,
                     conv) is None
    assert refused == {"rows-outside-frame": 1, "unknown-row": 1}


def test_resolve_position_phrases():
    P = _producer()
    comp = fixture()  # films in presentation order: A, B, C
    r = lambda s, cur=None: P.resolve_position(comp, s, cur)  # noqa: E731
    assert r("the first one") == "A" and r("the last movie") == "C" and r("second") == "B"
    assert r("second to last") == "B" and r("the penultimate one") == "B"
    assert r("the next one", "A") == "B" and r("the previous film", "C") == "B"
    assert r("the next one") is None and r("the next one", "C") is None
    assert r("fifth") is None and r("the blue one") is None


def test_batches_hold_no_window_neighbours():
    P = _producer()
    items = [dict(session_key=("P1", "s"), k=k, oid=f"a{k}") for k in range(10)]
    items += [dict(session_key=("P2", "s"), k=k, oid=f"b{k}") for k in range(0, 30, 2)]
    for sample in (1, 2):
        batches = P.make_batches(items, 7, sample, batch=40, window=2)
        rep = P.batch_report(batches, window=2)
        assert not rep["chaining_possible"] and rep["min_same_session_unit_gap"] > 2, rep
        assert sorted(it["oid"] for b in batches for it in b) == sorted(it["oid"] for it in items)
    a = P.make_batches(items, 7, 1)
    b = P.make_batches(items, 7, 2)
    assert [[i["oid"] for i in x] for x in a] != [[i["oid"] for i in x] for x in b]


def test_gold_bins_are_utterance_spans():
    import adapters.filmfestival as F

    events = [(10.0, 5.0), (16.0, None), (20.0, 3.0)]
    gold = [(10.03, 14.03), (16.03, 18.0), (20.03, 22.03)]
    spans, rec = F.align_gold(gold, events)
    assert spans == [(10.0, 15.0), (16.0, 20.0), (20.0, 23.0)]
    assert abs(rec["start_offset_s"] - 0.03) < 1e-9 and rec["ends_checked"] == 2
    for g, e in (
        ([(10.03, 14.03), (16.2, 18.0), (20.03, 22.03)], events),  # offset not constant
        ([(10.03, 14.53), (16.03, 18.0), (20.03, 22.03)], events),  # end not duration - 1 s
        (gold[:2], events),  # rows and utterances do not correspond
        ([(10.1, 14.1), (16.1, 18.0), (20.1, 22.1)], events),  # offset above 0.05 s
    ):
        try:
            F.align_gold(g, e)
        except SourceError:
            continue
        raise AssertionError(f"misaligned gold accepted: {g}")


def test_small_batches_respect_the_window():
    P = _producer()
    items = [dict(session_key=("P", "s"), k=k, oid=f"a{k}") for k in (0, 2)]  # 2 apart
    rep = P.batch_report(P.make_batches(items, 3, 1, batch=40, window=2), window=2)
    assert not rep["chaining_possible"] and rep["batches"] == 2


def test_condition_ids_follow_prompt_digests():
    P = _producer()
    a = P.conditions(1, "s", 1, "coverage")
    b = P.conditions(1, "s", 1, "pre-coverage")
    assert a["pass1"]["id"] == b["pass1"]["id"]  # pass 1 is shared between the arms
    assert a["pass2"]["id"] != b["pass2"]["id"] and a["pass2"]["system_sha256"] != b["pass2"]["system_sha256"]
    assert a["pass2"]["renderer_sha256"] == P.renderer_sha256()


def test_state_mapping_old_rule():
    # fragment -> ledger count; commentary or metamemory inside the framed film -> work-level;
    # memory search between films, gave up, off task -> non-film; an unframed remark -> non-film
    # commentary.
    P = _producer()
    from cs2.source import RecallWord

    comp = fixture()
    words = [RecallWord(i, f"w{i}", float(i)) for i in range(14)]
    units = [dict(first=2 * i, last=2 * i + 1) for i in range(7)]
    frames = [
        dict(kind="fragment", frame=[("B", 0.9)], position=None),
        dict(kind="content", frame=[("B", 0.8)], position=None),
        dict(kind="content", frame=[("C", 1.0)], position=None),
        dict(kind="content", frame=[("C", 1.0)], position=None),
        dict(kind="memory_search", frame=[], position=None),
        dict(kind="work", frame=[], position=None),
        dict(kind="gave_up", frame=[], position=None),
    ]
    items = {k: dict(oid=f"o{k}") for k in (1, 2, 3)}
    answers = {"o1": (dict(talk="commentary"), {}), "o2": (dict(talk="metamemory"), {}),
               "o3": (dict(talk="fragment"), {})}
    conds = P.conditions(1, "s", 1)
    refs = {}
    conv = P.new_conv()
    cells = P.convert_session(("P", "s"), words, units, frames, answers, items, comp, conds,
                              refs, conv, dict(talk="state-map-v1"))
    kinds = [type(c.sample).__name__ for c in cells[::2]]
    assert kinds == ["Uncovered", "RefSample", "RefSample", "Uncovered", "StateSample",
                     "StateSample", "StateSample"], kinds
    assert conv["fragment_words"] == 4
    w1, w2 = refs[cells[2].sample.ref], refs[cells[4].sample.ref]
    assert isinstance(w1, WorkReference) and w1.work == "B" and abs(w1.weight - 0.8) < 1e-12
    assert isinstance(w2, WorkReference) and w2.work == "C"
    assert [cells[i].sample.state for i in (8, 10, 12)] == ["memory_search", "commentary",
                                                             "gave_up"]
    acc = Account(comp, refs, {("P", "s"): cells})
    row = LocationDistribution.from_words(acc, cells, [Bin(0, 2)]).rows[0]
    assert row.state == "only_uncovered"  # a fragment unit is never non-film mass


def test_memory_search_in_a_framed_film_old_rule():
    # pass-1 memory search with a frame, and pass-2 metamemory inside its framed film, are
    # work-level references to that film, never non-film mass
    P = _producer()
    from cs2.source import RecallWord

    comp = fixture()
    words = [RecallWord(i, f"w{i}", float(i)) for i in range(4)]
    units = [dict(first=0, last=1), dict(first=2, last=3)]
    frames = [dict(kind="memory_search", frame=[("C", 0.7)], position=None),
              dict(kind="content", frame=[("B", 1.0)], position=None)]
    conv = P.new_conv()
    refs = {}
    cells = P.convert_session(("P", "s"), words, units, frames,
                              {"o1": (dict(talk="metamemory"), {})}, {1: dict(oid="o1")}, comp,
                              P.conditions(1, "s", 1), refs, conv,
                              dict(talk="state-map-v1"))
    for c, work in ((cells[0], "C"), (cells[2], "B")):
        assert isinstance(c.sample, RefSample), c.sample
        r = refs[c.sample.ref]
        assert isinstance(r, WorkReference) and r.work == work
    acc = Account(comp, refs, {("P", "s"): cells})
    row = LocationDistribution.from_words(acc, cells, [Bin(0, 9)]).rows[0]
    assert not any(k[0] == "nonfilm" for k in row.mass), row.mass


def test_gold_alignment_refuses_non_monotone_onsets():
    import adapters.filmfestival as F

    try:
        F.align_gold([(10.0, 14.0), (9.0, 11.0)], [(10.0, 5.0), (9.0, 3.0)])
    except SourceError:
        pass
    else:
        raise AssertionError("non-monotone utterance onsets were accepted")
    spans, rec = F.align_gold([(10.0, 0), (10.0, 0), (12.0, 13.0)],
                              [(10.0, None), (10.0, None), (12.0, 2.0)])
    assert spans[0] == (10.0, 10.0) and rec["zero_length_bins"] == 1


def _orthogonal_session(frames, answers, items, variant="identifying"):
    P = _producer()
    from cs2.source import RecallWord

    comp = fixture()
    words = [RecallWord(i, f"w{i}", float(i)) for i in range(2 * len(frames))]
    units = [dict(first=2 * i, last=2 * i + 1) for i in range(len(frames))]
    refs, conv, framing, talk = {}, P.new_conv(), {}, {}
    cells = P.convert_session(("P", "s"), words, units, frames, answers, items, comp,
                              P.conditions(1, "s", 1), refs, conv,
                              dict(talk="orthogonal", talk_work_variant=variant), framing, talk)
    return comp, cells, refs, conv, framing, talk


def test_talk_is_orthogonal_and_non_addressed_talk_has_no_mass():
    # memory search (even inside a framed film), giving up, unanchored commentary and fragments
    # are non-addressed talk: ledger counts, never a destination. Off-task is the non-film
    # state. Anchored commentary keeps its references (talk recorded on them). An identifying
    # 'work' unit is a work-level reference.
    frames = [
        dict(kind="memory_search", frame=[("C", 0.7)], position=None),
        dict(kind="work", frame=[], position=None),  # unframed remark: commentary talk
        dict(kind="content", frame=[("B", 1.0)], position=None),  # unanchored commentary
        dict(kind="content", frame=[("B", 1.0)], position=None),  # anchored commentary
        dict(kind="off_task", frame=[], position=None),
        dict(kind="work", frame=[("C", 0.9)], position=None),
        dict(kind="fragment", frame=[("C", 0.9)], position=None),
    ]
    answers = {"o2": (dict(talk="commentary", refs=[]), {}),
               "o3": (dict(talk="commentary", refs=[dict(words="w7", alts=[
                   {"w": 1.0, "iv": [[1, 1]]}])]), {1: "b2"})}
    comp, cells, refs, conv, framing, talk = _orthogonal_session(
        frames, answers, {2: dict(oid="o2"), 3: dict(oid="o3")})
    kinds = [type(c.sample).__name__ for c in cells[::2]]
    assert kinds == ["TalkSample", "TalkSample", "TalkSample", "Uncovered", "StateSample",
                     "RefSample", "TalkSample"], kinds
    assert type(cells[7].sample).__name__ == "RefSample"  # the anchored word
    assert refs[cells[7].sample.ref].talk == "commentary"
    assert isinstance(refs[cells[10].sample.ref], WorkReference)
    assert conv["non_addressed_words"] == {"metamemory": 2, "commentary": 4, "fragment": 2}
    assert talk[("P", "s", "P:s:u0")] == "metamemory" and framing[("P", "s", "P:s:u0")]
    acc = Account(comp, refs, {("P", "s"): cells})
    ld = LocationDistribution.from_words(acc, cells, [Bin(0, 1.5), Bin(4, 5.5)])
    assert [r.state for r in ld.rows] == ["only_non_addressed", "only_non_addressed"]


def test_talk_work_variant_position_only():
    frames = [dict(kind="work", frame=[("C", 0.9)], position=None),
              dict(kind="work", frame=[("C", 0.9)], position="the last one")]
    _, cells, refs, _, _, _ = _orthogonal_session(frames, {}, {}, variant="position-only")
    assert type(cells[0].sample).__name__ == "TalkSample"
    r = refs[cells[2].sample.ref]
    assert isinstance(r, PositionReference) and r.resolved_work == "C"


def test_frame_fallback_spreads_unassigned_over_the_frame_film():
    # rule 'frame-film': a row whose words lie in a unit framed 0.8 on B and 0.2 on C spreads
    # its unassigned mass F(w) = mix(w) + (1 - sum mix) dur(w), scenes by duration within w
    comp = fixture()
    dur = B.duration_prior(comp, CFG)
    fb = B.frame_fallback(dur, {"B": 0.8, "C": 0.2})
    close(fb.p_work("B"), 0.8)
    close(fb.p_work("C"), 0.2)
    close(fb.p_work("A"), 0.0)
    fb2 = B.frame_fallback(dur, {"B": 0.5})
    close(fb2.p_work("B"), 0.5 + 0.5 * dur.p_work("B"))
    close(fb2.p_work("C"), 0.5 * dur.p_work("C"))
    assert fb2.scene == dur.scene
    # an abstaining producer whose row is framed on B scores the frame fallback
    acc = Account(comp, {}, {("P", "s"): cells([(0.5, "u0", Uncovered("u0"))])},
                  framing_work={("P", "s", "u0"): (("B", 0.8), ("C", 0.2))})
    from cs2.run import row_frame_mixes

    g = [_gold("P", "s", 0, "specific", "B", {6}, lo=0.0)]
    mixes = row_frame_mixes(acc, ("P", "s"), g)
    assert mixes == [{"B": 0.8, "C": 0.2}]
    ld = LocationDistribution.from_words(acc, acc.words[("P", "s")], [Bin(0, 1)])
    f = SC.producer_forecasts(ld, dur, CFG, [B.frame_fallback(dur, m) for m in mixes])[0]
    close(f.p_work("B"), 0.8)


def test_null_producer_scores_the_baseline_under_frame_fallback():
    # no references and no frames: the frame-film fallback is the duration prior itself, so
    # every term equals the baseline exactly
    comp = fixture()
    dur = B.with_nonfilm(B.duration_prior(comp, CFG), 0.07)
    valid = B.valid_scenes(comp, CFG)
    words = [(0.2 + i, f"u{i}", Uncovered(f"u{i}")) for i in range(4)]
    acc = Account(comp, {}, {("P", "s"): cells(words)})
    gold = [_gold("P", "s", 0, "specific", "C", {8}), _gold("P", "s", 1, "film_only", "B"),
            _gold("P", "s", 2, "nonfilm", None, nonfilm="off_task"),
            _gold("P", "s", 3, "specific", "A", {1, 3})]
    from cs2.run import row_frame_mixes

    mixes = row_frame_mixes(acc, ("P", "s"), gold)
    assert all(m == {} for m in mixes)
    fbs = [B.frame_fallback(dur, m) for m in mixes]
    assert all(f is dur for f in fbs)
    ld = LocationDistribution.from_words(acc, acc.words[("P", "s")], [Bin(g.lo, g.hi) for g in gold])
    prod = SC.score_rows(gold, SC.producer_forecasts(ld, dur, CFG, fbs), comp, valid)
    base = SC.score_rows(gold, [dur] * len(gold), comp, valid)
    for p, b in zip(prod, base):
        for t in ("film_bits", "work_bits", "scene_bits", "total_bits"):
            assert getattr(p, t) == getattr(b, t), (t, getattr(p, t), getattr(b, t))


def test_lopo_nonfilm_rate_excludes_scored_participant():
    gold = {"P1": [_gold("P1", "s", i, "nonfilm", None, nonfilm="gave_up") for i in range(4)],
            "P2": [_gold("P2", "s", i, "specific", "B", {6}) for i in range(8)]
            + [_gold("P2", "s", 9, "nonfilm", None, nonfilm="off_task")]}
    a = CFG["lopo_nonfilm_alpha"]
    close(B.lopo_nonfilm_rate(gold, "P1", CFG), (1 + a) / (9 + 2 * a))  # P2 only
    close(B.lopo_nonfilm_rate(gold, "P2", CFG), (4 + a) / (4 + 2 * a))  # P1 only


def test_gold_context_fitted_known_answer():
    comp = fixture()
    dur = B.duration_prior(comp, CFG)
    rates = dict(stay=0.6, next=0.3, scene_stay=0.5, scene_next=0.25)
    rows = [_gold("P", "s", 0, "specific", "B", {6}), _gold("P", "s", 1, "specific", "B", {7})]
    f = B.gold_context_fitted(comp, rows, CFG, dur, rates)
    assert f[0] is dur
    # works in presentation order: A, B, C; previous work B -> stay B 0.6, next C 0.3
    close(f[1].p_work("B"), 0.6 + 0.1 * dur.p_work("B"))
    close(f[1].p_work("C"), 0.3 + 0.1 * dur.p_work("C"))
    # previous top scene 6, next valid scene 7: 0.5 / 0.25, rest by duration
    close(f[1].scene["B"][7], 0.25 + 0.25 * dur.scene["B"][7])
    close(math.fsum(f[1].work.values()), 1.0)
    gold = {"P1": rows, "P2": rows}
    r = B.fit_gold_context(comp, gold, "P1", CFG)
    a = CFG["lopo_alpha"]
    close(r["stay"], (1 + a) / (1 + 3 * a))  # P2's one transition stays in B
    close(r["scene_next"], (1 + a) / (1 + 3 * a))  # and advances 6 -> 7


def test_hmm_tfidf_filters_on_recall_words():
    comp = fixture()
    dur = B.duration_prior(comp, CFG)
    docs = {6: ["dog"], 7: ["cat"], 8: ["sea"], 9: ["sky"], 10: ["sun"], 1: ["pop"],
            2: ["pip"], 3: ["pap"], 4: ["pup"]}
    rates = dict(stay=0.7, next=0.2)
    f = B.hmm_tfidf(comp, [["dog"], [], ["sky"]], docs, CFG, dur, rates)
    top = lambda fc: max(((w, s, fc.work[w] * p) for w, d in fc.scene.items()  # noqa: E731
                          for s, p in d.items()), key=lambda t: t[2])[:2]
    assert top(f[0]) == ("B", 6)  # the emission picks the dog scene
    assert top(f[1]) == ("B", 6)  # no words: the stay transition keeps it
    assert top(f[2]) == ("C", 9)  # new evidence moves it
    for fc in f:
        close(math.fsum(fc.work.values()), 1.0)
    order = B.presentation_order(comp, CFG)
    assert order == [1, 2, 6, 7, 8, 9, 10, 3, 4]  # A@p1, B, C, then A@p2; title scene 5 dropped


def test_non_film_states_are_destinations_not_talk():
    # correction 2026-11-17: memory search between films (no frame), giving up and off task are
    # non-film states with mass; memory search inside a framed film stays non-addressed talk
    frames = [dict(kind="memory_search", frame=[], position=None),
              dict(kind="gave_up", frame=[], position=None),
              dict(kind="off_task", frame=[], position=None)]
    comp, cells, refs, _, _, _ = _orthogonal_session(frames, {}, {})
    states = [c.sample.state if type(c.sample).__name__ == "StateSample" else None
              for c in cells[::2]]
    assert states == ["memory_search", "gave_up", "off_task"], states
    acc = Account(comp, refs, {("P", "s"): cells})
    row = LocationDistribution.from_words(acc, cells, [Bin(0, 1.5)]).rows[0]
    assert row.mass == {("nonfilm", "memory_search"): 1.0}


# ---------------------------------------------------------------- runner


def main(argv):
    only = argv[argv.index("--only") + 1] if "--only" in argv else None
    tests = [
        (n, f) for n, f in globals().items() if n.startswith("test_") and callable(f)
    ]
    failed, errors = [], {}
    for name, fn in tests:
        if only and name != only:
            continue
        try:
            fn()
            if "--json" not in argv:
                print("ok", name)
        except Exception as exc:  # noqa: BLE001
            failed.append(name)
            errors[name] = type(exc).__name__
            if "--json" not in argv:
                print("FAIL", name)
                traceback.print_exc()
    ran = sum(1 for n, _ in tests if not only or n == only)
    if "--json" in argv:
        print(json.dumps(dict(ran=ran, failed=failed, errors=errors)))
    else:
        print(f"passed {ran - len(failed)} failed {len(failed)} of {ran}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
