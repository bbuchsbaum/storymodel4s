"""Descriptive scores beside the log loss (hill-climbing round 5, part A): how far a forecast is
from the gold in film time. They describe; no decision rests on them.

Film clock (declared): seconds from the start of an occurrence, its segments joined in their
order. A work with several occurrences has no single clock, and its rows are left out.
A scene's stretch runs from its first unit's start to its last unit's end.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from . import scorer as SC


@dataclass(frozen=True)
class Clock:
    unit: dict  # unit id -> (occurrence, start, end) on the occurrence's clock
    scene: dict  # scene -> (occurrence, start, end): the hull of its units
    single: dict  # work -> True when it has exactly one occurrence


def film_clock(comp) -> Clock:
    off = {}
    for o in comp.occurrences:
        t = 0.0
        for seg in o.segments:  # joined in their declared order
            off[(o.id, seg.part)] = t - seg.start_s
            t += seg.end_s - seg.start_s
    unit, scene = {}, {}
    for u in comp.units:
        d = off[(u.occurrence, u.part)]
        unit[u.id] = (u.occurrence, u.start_s + d, u.end_s + d)
        if u.scene is not None:
            a = scene.get(u.scene)
            lo, hi = u.start_s + d, u.end_s + d
            scene[u.scene] = (
                (u.occurrence, lo, hi)
                if a is None
                else (a[0], min(a[1], lo), max(a[2], hi))
            )
    single = {
        w.id: sum(1 for o in comp.occurrences if o.work == w.id) == 1
        for w in comp.works
    }
    return Clock(unit, scene, single)


def merge(intervals) -> list:
    """Sorted, disjoint intervals (touching ones are joined)."""
    out = []
    for lo, hi in sorted(intervals):
        if out and lo <= out[-1][1]:
            out[-1][1] = max(out[-1][1], hi)
        else:
            out.append([lo, hi])
    return out


def distance(t, gold) -> float:
    """0 inside a gold interval, otherwise the distance to the nearest gold boundary."""
    best = math.inf
    for lo, hi in gold:
        if lo <= t <= hi:
            return 0.0
        best = min(best, lo - t if t < lo else t - hi)
    return best


def mean_distance(a, b, gold) -> float:
    """The mean of distance(t, gold) for t spread evenly over [a, b]; exact, because the
    distance is linear between the gold boundaries and the midpoints of the gaps between them."""
    if b <= a:
        return distance(a, gold)
    cuts = {a, b}
    for i, (lo, hi) in enumerate(gold):
        cuts.update((lo, hi))
        if i:
            cuts.add((gold[i - 1][1] + lo) / 2)
    xs = sorted(x for x in cuts if a <= x <= b)
    area = math.fsum(
        (distance(x, gold) + distance(y, gold)) / 2 * (y - x)
        for x, y in zip(xs, xs[1:])
    )
    return area / (b - a)


def gap(a, b, gold) -> float:
    """The gap between [a, b] and the nearest gold interval (0 when they touch or overlap)."""
    return min(max(0.0, lo - b, a - hi) for lo, hi in gold)


def gold_stretches(g, valid, clock: Clock):
    """The gold row's valid scenes as merged stretches on its work's clock, or None when the
    row has no scene term or its work has no single clock."""
    if not g.specific or not clock.single.get(g.work):
        return None
    gs = [s for s in valid[g.work] if s in g.scenes]
    return merge([clock.scene[s][1:] for s in gs]) if gs else None


def located_scene_mass(f, w, valid) -> dict:
    """A producer forecast's own scene mass in work w: scene-located mass, and scene-set mass
    spread inside its set by the fallback (as the scorer spreads it)."""
    if not isinstance(f, SC.ProducerForecast):
        raise ValueError("located mass is defined for a producer forecast")
    ss = set(valid[w])
    out = {s: m for s, m in f.loc.items() if s in ss}
    fs = f.fallback.p_scene_given_work(w)
    for (sw, q), m in f.sets.items():
        inside = [x for x in q if x in ss]
        tot = math.fsum(fs.get(x, 0.0) for x in inside)
        if sw != w or tot <= 0:
            continue
        for x in inside:
            out[x] = out.get(x, 0.0) + m * fs.get(x, 0.0) / tot
    return out


def time_error(f, g, valid, clock: Clock, located_only=False):
    """The expected distance in seconds from the forecast to the gold scene set, inside the gold
    work: the forecast's mass on the work's valid scenes, renormalised, each scene's mass spread
    evenly over its stretch. All mass: unlocated mass stays where the scorer's fallback puts it.
    located_only: only the producer's own scene mass. None when the row has no scene term, its
    work has no single clock, or (located_only) nothing is located in the gold work."""
    gold = gold_stretches(g, valid, clock)
    if gold is None:
        return None
    mass = (
        located_scene_mass(f, g.work, valid)
        if located_only
        else {s: f.p_set_and_work({s}, g.work) for s in valid[g.work]}
    )
    tot = math.fsum(mass.values())
    if tot <= 0:
        return None
    return (
        math.fsum(
            m * mean_distance(*clock.scene[s][1:], gold)
            for s, m in mass.items()
            if m > 0
        )
        / tot
    )


def located_span(row, comp, scene, gold, clock: Clock):
    """For one unit-grain LocationDistribution row: the source units of `scene` that carry the
    row's located mass, as (expected distance to the gold, gap from the span's nearest edge),
    in seconds; None when the scene holds no unit-located mass."""
    units = {
        k[2]: m
        for k, m in row.mass.items()
        if k[0] == "at" and k[1] == "unit" and comp.unit[k[2]].scene == scene and m > 0
    }
    if not units:
        return None
    tot = math.fsum(units.values())
    expected = (
        math.fsum(m * mean_distance(*clock.unit[u][1:], gold) for u, m in units.items())
        / tot
    )
    return expected, min(gap(*clock.unit[u][1:], gold) for u in units)
