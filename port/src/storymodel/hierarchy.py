"""Primary narrative grouping derives containers without rewriting account occurrences."""

from __future__ import annotations

from collections import defaultdict
from typing import Annotated, ClassVar, Literal, Self

from pydantic import Field, model_validator

from .model import (
    AnySupport,
    Digest,
    Evidence,
    Identified,
    Model,
    Name,
    Side,
    Span,
    Support,
    _validate_instance,
    contained_in,
    support_origin,
)
from .playback import PlaybackSupport, union_playback
from .rich_account import SupportedAccount, validate_supported_account
from .serialization import digest


class HierarchyRef(Model):
    """Resolve an actual account anchor or a proposal-local group name, never a text label."""

    kind: Literal["anchor", "group"]
    key: Name


class HierarchyGroup(Model):
    """An explicit grouping proposal; its exact support is derived from its members."""

    key: Name
    kind: Literal["scene", "episode", "story", "arc"]
    members: Annotated[tuple[HierarchyRef, ...], Field(min_length=1)]


class SituationMembership(Model):
    """Place an existing semantic occurrence within an observed unit's exact support."""

    situation: Name
    unit: Name


class HierarchySpec(Model):
    """Account-bound primary grouping; partial construction is distinct from a complete tree."""

    schema_version: Literal["storymodel.hierarchy-spec/v1"] = "storymodel.hierarchy-spec/v1"
    account_sha256: Digest
    groups: tuple[HierarchyGroup, ...] = ()
    memberships: tuple[SituationMembership, ...] = ()
    completeness: Literal["complete", "partial"] = "complete"
    reason: Name | None = None
    expected_root: HierarchyRef | None = None

    @model_validator(mode="after")
    def partial_reason(self) -> Self:
        if self.completeness == "partial" and self.reason is None:
            raise ValueError("partial hierarchy requires a reason")
        if self.completeness == "complete" and self.reason is not None:
            raise ValueError("complete hierarchy cannot carry a partial reason")
        return self


class HierarchySegment(Identified):
    """A derived container identity depends on actual membership, not a label or parent."""

    namespace: ClassVar[str] = "hierarchy-segment"
    kind: Literal["scene", "episode", "story", "arc"]
    members: tuple[Name, ...]
    evidence: Evidence


class HierarchyNode(Model):
    """One checked anchor, its exact coordinates and derived primary-tree position."""

    anchor: Name
    kind: Literal["situation", "segment"]
    level: Annotated[int, Field(ge=0)]
    evidence: Evidence
    parent: Name | None
    children: tuple[Name, ...]


class HierarchyResult(Model):
    """Recomputable hierarchy receipt; the original account remains unchanged."""

    schema_version: Literal["storymodel.hierarchy/v1"] = "storymodel.hierarchy/v1"
    account_sha256: Digest
    side: Side
    spec: HierarchySpec
    groups: dict[Name, Name]
    segments: tuple[HierarchySegment, ...]
    nodes: tuple[HierarchyNode, ...]
    roots: tuple[Name, ...]
    root: Name | None
    # Without a unique root, every anchor is unrooted; no arbitrary first component wins.
    unrooted: tuple[Name, ...]
    empty_segments: tuple[Name, ...]


class HierarchyQuery(Model):
    """Deterministic primary-tree inspection without interpreting nesting as chronology."""

    account_sha256: Digest
    hierarchy_sha256: Digest
    anchor: Name
    parent: Name | None
    children: tuple[Name, ...]
    ancestors: tuple[Name, ...]
    descendants: tuple[Name, ...]
    situations: tuple[Name, ...]
    level: Annotated[int, Field(ge=0)]
    descendant_level: Annotated[int, Field(ge=0)] | None


def _union(values: tuple[AnySupport, ...]) -> tuple[AnySupport, ...]:
    by_origin: dict[str, list[AnySupport]] = defaultdict(list)
    for support in values:
        by_origin[support_origin(support)].append(support)
    result: list[AnySupport] = []
    for origin, supports in sorted(by_origin.items()):
        if isinstance(supports[0], PlaybackSupport):
            native = [s for s in supports if isinstance(s, PlaybackSupport)]
            if len(native) != len(supports):
                raise ValueError("a coordinate identity cannot mix text and playback")
            result.append(union_playback(native))
        else:
            spans: list[Span] = []
            for span in sorted(
                (p for s in supports if isinstance(s, Support) for p in s.spans),
                key=lambda p: (p.start, p.end),
            ):
                if spans and span.start <= spans[-1].end:
                    spans[-1] = Span(start=spans[-1].start, end=max(spans[-1].end, span.end))
                else:
                    spans.append(span)
            result.append(Support(document=origin, spans=tuple(spans)))
    return tuple(result)


def prepare_hierarchy(
    account: SupportedAccount,
    groups: tuple[HierarchyGroup, ...] = (),
    *,
    memberships: tuple[SituationMembership, ...] = (),
    completeness: Literal["complete", "partial"] = "complete",
    reason: str | None = None,
    expected_root: HierarchyRef | None = None,
) -> HierarchySpec:
    """Bind ordinary grouping declarations to the actual independent account snapshot."""
    checked = validate_supported_account(account)
    spec = HierarchySpec(
        account_sha256=digest(checked.model_dump(mode="json")),
        groups=groups,
        memberships=memberships,
        completeness=completeness,
        reason=reason,
        expected_root=expected_root,
    )
    # Admission here makes prepare useful on its own; consumption revalidates later.
    return build_hierarchy(checked, spec).spec


def build_hierarchy(account: SupportedAccount, spec: HierarchySpec) -> HierarchyResult:
    """Resolve primary containment in linear graph space, retaining every account anchor."""
    checked = validate_supported_account(account)
    selected = _validate_instance(HierarchySpec, spec)
    account_sha = digest(checked.model_dump(mode="json"))
    if selected.account_sha256 != account_sha:
        raise ValueError("hierarchy belongs to another account snapshot")
    sides = {d.side for d in checked.documents} | {m.side for m in checked._media_sources()}
    if len(sides) != 1:
        raise ValueError("hierarchy requires an independent source-only or recall-only account")
    side = sides.pop()
    situations = {s.id: s for s in checked._situations()}
    units = {u.id: u for u in checked.units}
    groups = {g.key: g for g in selected.groups}
    if len(groups) != len(selected.groups):
        raise ValueError("duplicate hierarchy group key")
    # Qualified internal keys keep user group names separate from content-addressed anchors.
    type Key = tuple[str, str]
    keys: set[Key] = {("anchor", a) for a in (*situations, *units)} | {
        ("group", key) for key in groups
    }
    children: dict[Key, list[Key]] = defaultdict(list)
    parents: dict[Key, Key] = {}

    def edge(child: Key, parent: Key) -> None:
        if child not in keys or parent not in keys:
            raise ValueError("hierarchy reference is not an account anchor or declared group")
        if parent[0] == "anchor" and parent[1] not in units:
            raise ValueError("primary parent must be a segment, not a situation")
        if child in parents:
            raise ValueError("a primary member has duplicate or conflicting parents")
        parents[child] = parent
        children[parent].append(child)

    for unit in checked.units:
        if unit.parent is not None:
            edge(("anchor", unit.id), ("anchor", unit.parent))
    for membership in selected.memberships:
        if membership.situation not in situations or membership.unit not in units:
            raise ValueError("situation membership requires an actual situation and unit")
        edge(("anchor", membership.situation), ("anchor", membership.unit))
    for group in selected.groups:
        for member in group.members:
            edge((member.kind, member.key), ("group", group.key))

    remaining = {key: len(children[key]) for key in keys}
    pending = sorted(key for key, count in remaining.items() if count == 0)
    resolved: dict[Key, str] = {("anchor", a): a for a in (*situations, *units)}
    supports: dict[Key, tuple[AnySupport, ...]] = {
        ("anchor", s.id): tuple(s.evidence) for s in situations.values()
    }
    supports.update({("anchor", u.id): (u.support,) for u in units.values()})
    levels = {("anchor", s): 0 for s in situations}
    levels.update({("anchor", u.id): 2 if u.kind == "scene" else 1 for u in units.values()})
    segments: dict[str, HierarchySegment] = {}
    processed = 0
    while pending:
        key = pending.pop()
        processed += 1
        child_keys = children[key]
        if key[0] == "group":
            group = groups[key[1]]
            evidence = _union(tuple(e for c in child_keys for e in supports[c]))
            segment = HierarchySegment(
                kind=group.kind,
                members=tuple(sorted(resolved[c] for c in child_keys)),
                evidence=evidence,
            )
            if segment.id in segments or segment.id in units or segment.id in situations:
                raise ValueError("duplicate resolved hierarchy segment identity")
            segments[segment.id] = segment
            resolved[key] = segment.id
            supports[key] = evidence
            levels[key] = 1
        if child_keys:
            levels[key] = max(levels[key], max(levels[c] + 1 for c in child_keys))
        for child in child_keys:
            if not all(contained_in(e, supports[key]) for e in supports[child]):
                raise ValueError("primary parent must contain all exact child support")
        parent = parents.get(key)
        if parent is not None:
            remaining[parent] -= 1
            if remaining[parent] == 0:
                pending.append(parent)
    if processed != len(keys):
        raise ValueError("primary hierarchy contains a cycle")
    roots = tuple(sorted(resolved[k] for k in keys if k not in parents))
    root = roots[0] if len(roots) == 1 and roots[0] not in situations else None
    if selected.expected_root is not None:
        ref = selected.expected_root
        expected = resolved.get((ref.kind, ref.key))
        if expected is None or expected != root:
            raise ValueError("expected root differs from the unique primary root")
    # Existing scene containers with no children cannot establish a complete hierarchy.
    empty = tuple(
        sorted(u.id for u in units.values() if u.kind == "scene" and not children[("anchor", u.id)])
    )
    unrooted = () if root is not None else tuple(sorted(resolved.values()))
    if selected.completeness == "complete" and keys and (root is None or empty):
        raise ValueError("complete hierarchy requires one segment root and no empty scenes")
    return HierarchyResult(
        account_sha256=account_sha,
        side=side,
        spec=selected,
        groups={key: resolved[("group", key)] for key in sorted(groups)},
        segments=tuple(segments[key] for key in sorted(segments)),
        nodes=tuple(
            sorted(
                (
                    HierarchyNode(
                        anchor=resolved[k],
                        kind="situation" if k[0] == "anchor" and k[1] in situations else "segment",
                        level=levels[k],
                        evidence=supports[k],
                        parent=resolved[parents[k]] if k in parents else None,
                        children=tuple(sorted(resolved[c] for c in children[k])),
                    )
                    for k in keys
                ),
                key=lambda n: n.anchor,
            )
        ),
        roots=roots,
        root=root,
        unrooted=unrooted,
        empty_segments=empty,
    )


def verify_hierarchy(
    account: SupportedAccount, spec: HierarchySpec, result: HierarchyResult
) -> HierarchyResult:
    """Recompute a saved receipt from an independently supplied account and declaration."""
    actual = _validate_instance(HierarchyResult, result)
    expected = build_hierarchy(account, spec)
    if actual != expected:
        raise ValueError("hierarchy differs from account or specification")
    return expected


def query_hierarchy(
    account: SupportedAccount,
    result: HierarchyResult,
    anchor: str,
    *,
    descendant_level: int | None = None,
) -> HierarchyQuery:
    """Inspect nearest-first ancestors and stable-ID depth-first descendants after verification."""
    checked = _validate_instance(HierarchyResult, result)
    hierarchy = verify_hierarchy(account, checked.spec, checked)
    nodes = {node.anchor: node for node in hierarchy.nodes}
    if not isinstance(anchor, str) or anchor not in nodes:
        raise ValueError("query anchor is outside the hierarchy")
    if descendant_level is not None and (type(descendant_level) is not int or descendant_level < 0):
        raise ValueError("descendant level must be a nonnegative integer or None")
    node = nodes[anchor]
    ancestors: list[str] = []
    parent = node.parent
    while parent is not None:
        ancestors.append(parent)
        parent = nodes[parent].parent
    descendants: list[str] = []
    pending = list(reversed(node.children))
    while pending:
        member = pending.pop()
        descendants.append(member)
        pending.extend(reversed(nodes[member].children))
    return HierarchyQuery(
        account_sha256=hierarchy.account_sha256,
        hierarchy_sha256=digest(hierarchy.model_dump(mode="json")),
        anchor=anchor,
        parent=node.parent,
        children=node.children,
        ancestors=tuple(ancestors),
        descendants=tuple(
            a for a in descendants if descendant_level is None or nodes[a].level == descendant_level
        ),
        situations=tuple(a for a in descendants if nodes[a].kind == "situation"),
        level=node.level,
        descendant_level=descendant_level,
    )
