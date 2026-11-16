"""Typed projections of the account: LocationDistribution, CoverageMeasure, marginals and the
correspondence table.

Keys. Film material is ("at", grain, id) with grain unit | scene | occurrence | work. Side
states are their own keys and are never coarsened:
  ("unassigned",)                      the declared remainder, and words no reference covers
  ("external",)                        a typed external destination
  ("in_work_outside", work)            the work's material outside the stimulus
  ("work_ref", work)                   a work-level reference
  ("position", stated, resolved_work)  a position-level reference ('' when unresolved)
  ("nonfilm", state)                   a typed non-film state (memory search, ...)
  ("held_out", "broad"|"joint", work)  a broad or joint hypothesis, held out of the location

LocationDistribution (declared rules, design revision 2):
- a bin is a recall-clock interval; a word belongs to it by its onset;
- a bin without words has the exclusive state 'no_sample' and no simplex;
- reducer: equal weight per distinct sample in the bin (a reference; a not-applicable unit's
  state; a unit's uncovered words), so a not-applicable word stays in the denominator;
- inside a single hypothesis, mass is uniform over its source units (allocation 'units'), or
  proportional to unit seconds (allocation 'seconds', a duration prior, labelled as one);
- weights as written; the remainder goes to 'unassigned'; every sampled row sums to 1 (1e-12);
- a joint hypothesis never enters a location (`location_contribution` refuses it); the account
  routes it, and a broad hypothesis, to a held-out state keyed by work (split by unit share when
  the support spans works).

CoverageMeasure: what the speech covers. Each unit of a hypothesis's support (joint moments each
count; broad covers its span) gets the member's weight; samples in a bin combine by maximum, so a
cell lies in [0, 1] and rows have no sum constraint. Work-level references are held out; spreading
them over seconds is refused.
"""

from __future__ import annotations

import math
from collections import defaultdict
from dataclasses import dataclass

from .account import (
    Destination,
    Hypothesis,
    PositionReference,
    Reference,
    RefSample,
    Shape,
    StateSample,
    Uncovered,
    WorkReference,
)

GRAINS = ("unit", "scene", "occurrence", "work")
ROW_TOL = 1e-12
ALLOCATIONS = {
    "units": "uniform over source units",
    "seconds": "duration prior (seconds)",
}
JOINT_RULE = (
    "held-out"  # the declared route for a joint hypothesis (it never enters a location)
)


class ProjectionError(ValueError):
    def __init__(self, reason: str, detail: str = ""):
        super().__init__(f"{reason}: {detail}" if detail else reason)
        self.reason = reason


@dataclass(frozen=True)
class Bin:
    lo: float
    hi: float
    closed: bool = False

    def contains(self, t) -> bool:
        if t is None:
            return False
        return self.lo <= t <= self.hi if self.closed else self.lo <= t < self.hi


@dataclass(frozen=True)
class Row:
    bin: Bin
    state: str  # 'sampled' | 'no_sample'
    mass: dict
    samples: tuple
    trace: dict  # key -> frozenset of samples that put mass there
    n_words: int


def is_film(key) -> bool:
    return key[0] == "at"


# ---------------------------------------------------------------- contributions


def _work_split(support, comp):
    by = defaultdict(int)
    for u in support:
        by[comp.work_of_unit(u)] += 1
    n = len(support)
    return [(w, c / n) for w, c in sorted(by.items())]


def location_contribution(target, comp, allocation="units"):
    """Mass of one member (weight 1) over location keys. Refuses a joint hypothesis."""
    if allocation not in ALLOCATIONS:
        raise ProjectionError("unknown-allocation", allocation)
    if isinstance(target, Destination):
        if target.kind == "external":
            return [(("external",), 1.0)]
        return [(("in_work_outside", target.work), 1.0)]
    if not isinstance(target, Hypothesis):
        raise ProjectionError("not-a-location-input", type(target).__name__)
    if target.shape is Shape.JOINT:
        raise ProjectionError(
            "joint-refused", "a joint reference cannot enter a distribution"
        )
    if target.shape is Shape.BROAD:
        return [
            (("held_out", "broad", w), f) for w, f in _work_split(target.support, comp)
        ]
    units = sorted(target.support)
    if allocation == "units":
        return [(("at", "unit", u), 1.0 / len(units)) for u in units]
    secs = [comp.unit[u].duration for u in units]
    tot = math.fsum(secs)
    if not tot > 0:
        raise ProjectionError("no-seconds", "a hypothesis of zero-length units has no seconds")
    return [(("at", "unit", u), s / tot) for u, s in zip(units, secs)]


def reference_contributions(ref, comp, allocation="units"):
    """[(key, mass)] of one reference; masses sum to 1 (weights plus the remainder)."""
    out = []
    if isinstance(ref, Reference):
        for m in ref.members:
            t = m.target
            if isinstance(t, Hypothesis) and t.shape is Shape.JOINT:
                out += [
                    (("held_out", "joint", w), m.weight * f)
                    for w, f in _work_split(t.support, comp)
                ]
                continue
            out += [
                (k, m.weight * v) for k, v in location_contribution(t, comp, allocation)
            ]
    elif isinstance(ref, WorkReference):
        out.append((("work_ref", ref.work), ref.weight))
    elif isinstance(ref, PositionReference):
        out.append((("position", ref.position, ref.resolved_work or ""), ref.weight))
    else:
        raise ProjectionError("not-a-reference", type(ref).__name__)
    if ref.unassigned > 0:
        out.append((("unassigned",), ref.unassigned))
    return out


def sample_contributions(sample, account, allocation="units"):
    if isinstance(sample, RefSample):
        return reference_contributions(
            account.references[sample.ref], account.composition, allocation
        )
    if isinstance(sample, StateSample):
        return [(("nonfilm", sample.state), 1.0)]
    if isinstance(sample, Uncovered):
        return [(("unassigned",), 1.0)]
    raise ProjectionError("unknown-sample", repr(sample))


def _sample_key(s):
    return (type(s).__name__, tuple(str(x) for x in vars(s).values()))


def bin_samples(cells, b: Bin):
    """Distinct samples of the words in a bin (every word counts, not-applicable included)."""
    inside = [c for c in cells if b.contains(c.onset)]
    return inside, sorted({c.sample for c in inside}, key=_sample_key)


# ---------------------------------------------------------------- distribution


class LocationDistribution:
    """Where the speech points, per recall bin: one simplex per sampled bin."""

    def __init__(self, composition, grain, allocation, rows):
        if grain not in GRAINS:
            raise ProjectionError("unknown-grain", grain)
        self.composition, self.grain, self.allocation, self.rows = (
            composition,
            grain,
            allocation,
            tuple(rows),
        )
        for r in self.rows:
            _check_row(r)

    @classmethod
    def from_words(cls, account, cells, bins, allocation="units"):
        rows = []
        for b in bins:
            inside, samples = bin_samples(cells, b)
            if not samples:
                rows.append(Row(b, "no_sample", {}, (), {}, 0))
                continue
            k = len(samples)
            parts, trace = defaultdict(list), defaultdict(set)
            for s in samples:
                for key, v in sample_contributions(s, account, allocation):
                    parts[key].append(v / k)
                    trace[key].add(s)
            mass = {key: math.fsum(vs) for key, vs in sorted(parts.items(), key=lambda kv: repr(kv[0]))}
            rows.append(
                Row(
                    b,
                    "sampled",
                    mass,
                    tuple(samples),
                    {key: frozenset(v) for key, v in trace.items()},
                    len(inside),
                )
            )
        return cls(account.composition, "unit", allocation, rows)

    def coarsen(self, grain):
        """The marginal at a coarser grain; side states are carried unchanged."""
        if GRAINS.index(grain) < GRAINS.index(self.grain):
            raise ProjectionError("cannot-refine", f"{self.grain} -> {grain}")
        f = _coarsener(self.composition, self.grain, grain)
        rows = []
        for r in self.rows:
            if r.state != "sampled":
                rows.append(r)
                continue
            parts, trace = defaultdict(list), defaultdict(set)
            for key, v in r.mass.items():
                nk = ("at", grain, f(key[2])) if is_film(key) else key
                parts[nk].append(v)
                trace[nk] |= r.trace.get(key, frozenset())
            mass = {
                k: math.fsum(vs)
                for k, vs in sorted(parts.items(), key=lambda kv: repr(kv[0]))
            }
            rows.append(
                Row(
                    r.bin,
                    "sampled",
                    mass,
                    r.samples,
                    {k: frozenset(v) for k, v in trace.items()},
                    r.n_words,
                )
            )
        return LocationDistribution(self.composition, grain, self.allocation, rows)

    def marginal(self, grain):
        return self if grain == self.grain else self.coarsen(grain)


def _coarsener(comp, src, dst):
    if src == dst:
        return lambda x: x
    if src == "unit":
        if dst == "scene":
            return comp.scene_of_unit
        if dst == "occurrence":
            return lambda u: comp.unit[u].occurrence
        return comp.work_of_unit
    if src == "scene":
        if dst == "occurrence":
            return comp.occurrence_of_scene
        return comp.work_of_scene
    return comp.work_of_occurrence  # occurrence -> work


def _check_row(r: Row):
    if r.state == "no_sample":
        if r.mass:
            raise ProjectionError("no-sample-has-mass")
        return
    if r.state != "sampled":
        raise ProjectionError("unknown-row-state", r.state)
    if any(not (v >= 0) for v in r.mass.values()):
        raise ProjectionError("negative-or-nan-mass")
    total = math.fsum(r.mass.values())
    if abs(total - 1) > ROW_TOL:
        raise ProjectionError("row-sum", repr(total))
    if not r.samples:
        raise ProjectionError("untraceable-row")


def part_mass(row: Row, comp):
    """(work, part) -> mass for a unit-grain row; used to check that a work's mass lies only on
    parts that hold it."""
    out = defaultdict(float)
    for key, v in row.mass.items():
        if is_film(key) and key[1] == "unit":
            u = comp.unit[key[2]]
            out[(comp.work_of_unit(u.id), u.part)] += v
    return dict(out)


# ---------------------------------------------------------------- coverage


@dataclass(frozen=True)
class CoverageRow:
    bin: Bin
    state: str
    cover: dict  # ("at", "unit", u) -> [0, 1]
    held: dict  # side state -> [0, 1]
    samples: tuple


class CoverageMeasure:
    """What the speech covers, per recall bin. Never a distribution: no sum constraint."""

    def __init__(self, composition, rows):
        self.composition, self.rows = composition, tuple(rows)

    @classmethod
    def from_words(cls, account, cells, bins, spread_work=False):
        if spread_work:
            raise ProjectionError(
                "work-spread-refused",
                "one mention of a film must not become its seconds",
            )
        rows = []
        for b in bins:
            _, samples = bin_samples(cells, b)
            if not samples:
                rows.append(CoverageRow(b, "no_sample", {}, {}, ()))
                continue
            cover, held = defaultdict(float), defaultdict(float)
            for s in samples:
                if isinstance(s, StateSample):
                    held[("nonfilm", s.state)] = 1.0
                    continue
                if isinstance(s, Uncovered):
                    held[("unassigned",)] = 1.0
                    continue
                ref = account.references[s.ref]
                if isinstance(ref, WorkReference):
                    k = ("work_ref", ref.work)
                    held[k] = max(held[k], ref.weight)
                    continue
                if isinstance(ref, PositionReference):
                    k = ("position", ref.position, ref.resolved_work or "")
                    held[k] = max(held[k], ref.weight)
                    continue
                for m in ref.members:
                    t = m.target
                    if isinstance(t, Destination):
                        k = (
                            ("external",)
                            if t.kind == "external"
                            else ("in_work_outside", t.work)
                        )
                        held[k] = max(held[k], m.weight)
                        continue
                    for u in t.support:
                        k = ("at", "unit", u)
                        cover[k] = max(cover[k], m.weight)
            rows.append(
                CoverageRow(b, "sampled", dict(cover), dict(held), tuple(samples))
            )
        return cls(account.composition, rows)


# ---------------------------------------------------------------- correspondence and ledger


def correspondence(account):
    """Sparse reference x hypothesis x interval table (one row per unit of each constituent)."""
    comp = account.composition
    out = []
    for rid in sorted(account.references):
        ref = account.references[rid]
        if isinstance(ref, Reference):
            for mi, m in enumerate(ref.members):
                t = m.target
                if isinstance(t, Destination):
                    out.append(
                        dict(
                            ref=rid,
                            member=mi,
                            shape=t.kind,
                            constituent=None,
                            unit=None,
                            work=t.work,
                            part=None,
                            start_s=None,
                            end_s=None,
                            weight=m.weight,
                        )
                    )
                    continue
                for ci, c in enumerate(t.constituents):
                    for u in sorted(c):
                        su = comp.unit[u]
                        out.append(
                            dict(
                                ref=rid,
                                member=mi,
                                shape=t.shape.value,
                                constituent=ci,
                                unit=u,
                                work=comp.work_of_unit(u),
                                part=su.part,
                                start_s=su.start_s,
                                end_s=su.end_s,
                                weight=m.weight,
                            )
                        )
        else:
            work = ref.work if isinstance(ref, WorkReference) else ref.resolved_work
            out.append(
                dict(
                    ref=rid,
                    member=0,
                    shape=type(ref).__name__,
                    constituent=None,
                    unit=None,
                    work=work,
                    part=None,
                    start_s=None,
                    end_s=None,
                    weight=ref.weight,
                )
            )
    for rel in account.relations:
        out.append(dict(relation=type(rel).__name__, **vars(rel)))
    return out


def word_ledger(cells, bins):
    """Word denominators: every word in a bin counts, not-applicable words included."""
    inb = [c for c in cells if any(b.contains(c.onset) for b in bins)]
    kinds = defaultdict(int)
    for c in inb:
        kinds[type(c.sample).__name__] += 1
    n = len(inb)
    return dict(
        words=len(cells),
        words_in_bins=n,
        reference_words=kinds["RefSample"],
        not_applicable_words=kinds["StateSample"],
        uncovered_words=kinds["Uncovered"],
        uncovered_share=kinds["Uncovered"] / n if n else float("nan"),
        not_applicable_share=kinds["StateSample"] / n if n else float("nan"),
    )
