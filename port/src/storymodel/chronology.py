"""Story-world interval claims and sparse consistency, separate from measured clocks."""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Mapping
from typing import ClassVar, Literal, Self

from pydantic import model_validator

from .model import (
    AnyContext,
    AnySupport,
    Identified,
    Model,
    Name,
    _AccountFields,
    acyclic,
)
from .playback import PlaybackSupport, validate_playback

TemporalRelation = Literal[
    "before",
    "meets",
    "overlaps",
    "during",
    "contains",
    "starts",
    "finishes",
    "equal",
    "unclear",
    "after",
    "met-by",
    "overlapped-by",
    "started-by",
    "finished-by",
]
EpistemicStatus = Literal[
    "surface-explicit",
    "linguistically-entailed",
    "world-knowledge-inferred",
    "structurally-derived",
    "hypothesized",
    "human-adjudicated",
]
STATUSES: tuple[EpistemicStatus, ...] = (
    "surface-explicit",
    "linguistically-entailed",
    "world-knowledge-inferred",
    "structurally-derived",
    "hypothesized",
    "human-adjudicated",
)
RELATIONS: tuple[TemporalRelation, ...] = (
    "before",
    "meets",
    "overlaps",
    "during",
    "contains",
    "starts",
    "finishes",
    "equal",
    "after",
    "met-by",
    "overlapped-by",
    "started-by",
    "finished-by",
)
_CONVERSE: dict[TemporalRelation, TemporalRelation] = {
    "before": "after",
    "after": "before",
    "meets": "met-by",
    "met-by": "meets",
    "overlaps": "overlapped-by",
    "overlapped-by": "overlaps",
    "during": "contains",
    "contains": "during",
    "starts": "started-by",
    "started-by": "starts",
    "finishes": "finished-by",
    "finished-by": "finishes",
    "equal": "equal",
    "unclear": "unclear",
}
_REVERSE_ONLY = {"after", "met-by", "overlapped-by", "started-by", "finished-by"}


class _Address(Model):
    source: Name
    target: Name
    relation: TemporalRelation


def canonical_temporal(
    source: str, target: str, relation: TemporalRelation
) -> tuple[str, str, TemporalRelation]:
    """Normalize directional aliases and symmetric endpoints before constructing identity."""
    address = _Address(source=source, target=target, relation=relation)
    source, target, relation = address.source, address.target, address.relation
    if relation in _REVERSE_ONLY:
        source, target, relation = target, source, _CONVERSE[relation]
    if relation in ("equal", "unclear") and target < source:
        source, target = target, source
    return source, target, relation


class TemporalClaim(Identified):
    """An attributed ordering claim retains its evidence and declared epistemic basis."""

    namespace: ClassVar[str] = "temporal-claim"
    source: Name
    target: Name
    relation: TemporalRelation
    context: Name
    status: EpistemicStatus
    evidence: tuple[AnySupport, ...] = ()
    premises: tuple[Name, ...] = ()
    origin: Name
    procedure: Name
    adjudicator: Name | None = None

    @model_validator(mode="after")
    def licensed(self) -> Self:
        if self.source == self.target:
            raise ValueError("temporal claim requires distinct endpoints")
        if (self.source, self.target, self.relation) != canonical_temporal(
            self.source, self.target, self.relation
        ):
            raise ValueError("stored temporal claim must have canonical relation/endpoints")
        if tuple(sorted(set(self.premises))) != self.premises:
            raise ValueError("temporal premises must be unique and sorted")
        if self.status == "surface-explicit" and not self.evidence:
            raise ValueError("surface-explicit temporal claim requires direct evidence")
        if (
            self.status
            in ("linguistically-entailed", "world-knowledge-inferred", "structurally-derived")
            and not self.premises
        ):
            raise ValueError("inferred temporal claim requires upstream claims")
        if not self.evidence and not self.premises:
            raise ValueError("temporal claim requires evidence or upstream claims")
        if (self.status == "human-adjudicated") != (self.adjudicator is not None):
            raise ValueError("only human-adjudicated claims require an explicit adjudicator")
        return self

    @classmethod
    def create(
        cls,
        *,
        source: str,
        target: str,
        relation: TemporalRelation,
        context: str,
        status: EpistemicStatus,
        origin: str,
        procedure: str,
        evidence: tuple[AnySupport, ...] = (),
        premises: tuple[str, ...] = (),
        adjudicator: str | None = None,
    ) -> TemporalClaim:
        """Accept directional proposals while emitting one canonical stored representation."""
        source, target, relation = canonical_temporal(source, target, relation)
        if not isinstance(premises, tuple) or any(
            not isinstance(p, str) or not p for p in premises
        ):
            raise ValueError("temporal premises must be a tuple of claim IDs")
        return cls(
            source=source,
            target=target,
            relation=relation,
            context=context,
            status=status,
            origin=origin,
            procedure=procedure,
            evidence=evidence,
            premises=tuple(sorted(set(premises))),
            adjudicator=adjudicator,
        )


class TemporalRow(Model):
    """Inspect a stored claim; legacy before edges retain unspecified epistemic metadata."""

    id: Name
    source: Name
    target: Name
    relation: TemporalRelation
    context: Name
    status: EpistemicStatus | None
    evidence: tuple[AnySupport, ...]
    premises: tuple[Name, ...] = ()
    origin: Name | None = None
    procedure: Name | None = None
    adjudicator: Name | None = None


class TemporalPolicy(Model):
    """Select statuses and retain claims only when their temporal premises remain eligible."""

    statuses: tuple[EpistemicStatus, ...] = STATUSES
    include_legacy: bool = True

    @model_validator(mode="after")
    def unique_statuses(self) -> Self:
        if len(set(self.statuses)) != len(self.statuses):
            raise ValueError("duplicate temporal status selection")
        return self


def temporal_rows(
    account: _AccountFields, claims: tuple[TemporalClaim, ...]
) -> tuple[TemporalRow, ...]:
    """Internal catalog for already validated accounts, preserving legacy assertion identity."""
    rows = [TemporalRow.model_validate(c.model_dump()) for c in claims]
    rows.extend(
        TemporalRow(
            id=r.id,
            source=r.source,
            target=r.target,
            relation="before",
            context=r.context,
            status=None,
            evidence=r.evidence,
        )
        for r in account.relations
        if r.kind == "before"
    )
    return tuple(sorted(rows, key=lambda r: r.id))


def _within(contexts: Mapping[str, AnyContext], context: str, ancestor: str) -> bool:
    current: str | None = context
    while current is not None:
        if current == ancestor:
            return True
        current = contexts[current].parent
    return False


# Each interval has start/end points. Atomic Allen relations need only < and =.
# Values index (source.start, source.end, target.start, target.end).
_RULES: dict[TemporalRelation, tuple[tuple[tuple[int, int], ...], tuple[tuple[int, int], ...]]] = {
    "before": (((1, 2),), ()),
    "meets": ((), ((1, 2),)),
    "overlaps": (((0, 2), (2, 1), (1, 3)), ()),
    "during": (((2, 0), (1, 3)), ()),
    "contains": (((0, 2), (3, 1)), ()),
    "starts": (((1, 3),), ((0, 2),)),
    "finishes": (((2, 0),), ((1, 3),)),
    "equal": ((), ((0, 2), (1, 3))),
    "unclear": ((), ()),
}


def interval_consistent(edges: tuple[tuple[str, str, TemporalRelation], ...]) -> bool:
    """Internal feasibility of asserted interval constraints; emits no invented physical times."""
    type Point = tuple[str, int]
    strict: set[tuple[Point, Point]] = set()
    equal: list[tuple[Point, Point]] = []
    points: set[Point] = set()
    for source, target, relation in edges:
        source, target, relation = canonical_temporal(source, target, relation)
        four = ((source, 0), (source, 1), (target, 0), (target, 1))
        points.update(four)
        strict.update(((four[0], four[1]), (four[2], four[3])))
        less, same = _RULES[relation]
        strict.update((four[a], four[b]) for a, b in less)
        equal.extend((four[a], four[b]) for a, b in same)
    representatives = {point: point for point in points}

    def find(point: Point) -> Point:
        while representatives[point] != point:
            representatives[point] = representatives[representatives[point]]
            point = representatives[point]
        return point

    for a, b in equal:
        ra, rb = find(a), find(b)
        if ra != rb:
            low, high = sorted((ra, rb))
            representatives[high] = low
    arcs = {(find(a), find(b)) for a, b in strict}
    if any(a == b for a, b in arcs):
        return False
    indegree = dict.fromkeys((find(p) for p in points), 0)
    outgoing: dict[Point, list[Point]] = defaultdict(list)
    for a, b in arcs:
        outgoing[a].append(b)
        indegree[b] += 1
    pending = [p for p, degree in indegree.items() if degree == 0]
    visited = 0
    while pending:
        point = pending.pop()
        visited += 1
        for following in outgoing[point]:
            indegree[following] -= 1
            if indegree[following] == 0:
                pending.append(following)
    return visited == len(indegree)


def validate_temporal_claims(account: _AccountFields, claims: tuple[TemporalClaim, ...]) -> None:
    """Check claim licensing, exact context scopes and the complete legacy/new constraint union."""
    situations = {s.id: s for s in account._situations()}
    contexts = {c.id: c for c in account.contexts}
    docs = {d.id: d for d in account.documents}
    media = {m.id: m for m in account._media_sources()}
    if len({c.id for c in claims}) != len(claims):
        raise ValueError("duplicate temporal claim identity")
    upstream: dict[str, tuple[str, str]] = {s.id: (s.side, s.context) for s in situations.values()}
    upstream.update({r.id: (situations[r.source].side, r.context) for r in account.relations})
    for claim in claims:
        left, right = situations.get(claim.source), situations.get(claim.target)
        context = contexts.get(claim.context)
        if left is None or right is None or context is None:
            raise ValueError("temporal claim has foreign endpoints or context")
        if (
            left.side != right.side
            or context.side != left.side
            or not (
                _within(contexts, context.id, left.context)
                and _within(contexts, context.id, right.context)
            )
        ):
            raise ValueError("temporal context must be within both same-side endpoint contexts")
        upstream[claim.id] = (left.side, context.id)
        for support in claim.evidence:
            if isinstance(support, PlaybackSupport):
                origin = media.get(support.source)
                if origin is None or origin.side != left.side:
                    raise ValueError("temporal evidence has a foreign media source or side")
                validate_playback(origin, support)
            else:
                document = docs.get(support.document)
                if document is None or document.side != left.side:
                    raise ValueError("temporal evidence has a foreign document or side")
                for span in support.spans:
                    span.slice(document.text)
    for claim in claims:
        side = situations[claim.source].side
        for premise in claim.premises:
            known = upstream.get(premise)
            if known is None or known[0] != side or not _within(contexts, claim.context, known[1]):
                raise ValueError("temporal premise must be an existing same-side claim in scope")
    acyclic(((c.id, p) for c in claims for p in c.premises), "temporal premise graph")
    rows = temporal_rows(account, claims)
    by_id = {row.id: row for row in rows}
    for claim in claims:
        if claim.status != "structurally-derived":
            continue
        premises = tuple(by_id[p] for p in claim.premises if p in by_id)
        if any(p.context != claim.context for p in premises):
            raise ValueError("structural temporal premises require the exact conclusion context")
        # Provenance alone is not a proof. Only explicitly cited temporal assertions
        # constrain the endpoints; neither the conclusion nor uncited account facts enter.
        edges = tuple((p.source, p.target, p.relation) for p in premises)
        possible = tuple(
            relation
            for relation in RELATIONS
            if interval_consistent((*edges, (claim.source, claim.target, relation)))
        )
        if possible != (claim.relation,):
            raise ValueError("structural temporal claim is not uniquely entailed by cited premises")
    # Old before records may repeat a fact with different evidence. Preserve them, but
    # forbid any new assertion from duplicating an existing temporal fact.
    seen = {
        (r.context, r.source, r.target, "before") for r in account.relations if r.kind == "before"
    }
    for claim in claims:
        source, target, relation = claim.source, claim.target, claim.relation
        if relation == "contains":
            source, target, relation = target, source, "during"
        key = (claim.context, source, target, relation)
        if key in seen:
            raise ValueError("duplicate temporal fact independent of evidence or status")
        seen.add(key)
    by_context: dict[str, list[TemporalRow]] = defaultdict(list)
    by_pair: dict[tuple[str, tuple[str, ...]], set[TemporalRelation]] = defaultdict(set)
    for row in rows:
        by_context[row.context].append(row)
        by_pair[(row.context, tuple(sorted((row.source, row.target))))].add(row.relation)
    if any("unclear" in kinds and len(kinds) > 1 for kinds in by_pair.values()):
        raise ValueError("unclear and definite temporal assertions conflict for one scoped pair")
    for context_rows in by_context.values():
        if not interval_consistent(tuple((r.source, r.target, r.relation) for r in context_rows)):
            raise ValueError("inconsistent story-world interval constraints")
