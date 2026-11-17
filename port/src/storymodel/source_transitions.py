"""Checked source layers and graph-derived transitions for the existing sequence kernel.

Local state admission and costs are supplied, not inferred by this bridge. Text-derived
discourse order is a named projection policy, distinct from story-world precedence.
"""

from __future__ import annotations

import math
from collections import defaultdict
from typing import Annotated, Any, Literal, Self

from pydantic import Field, SerializerFunctionWrapHandler, model_serializer, model_validator

from .hierarchy import HierarchySpec, build_hierarchy
from .model import Digest, Model, Name, Support, _validate_instance, contained_in, text_evidence
from .rich_account import RichAccount, SupportedAccount, require_text_account
from .sequence import (
    Finite,
    Probability,
    SequenceRequest,
    SequenceRow,
    SequenceTransition,
    TransitionEdge,
)
from .serialization import digest

Feature = Literal[
    "stay",
    "discourse-successor",
    "world-time-successor",
    "causal-neighbor",
    "hierarchy-up",
    "hierarchy-down",
    "same-entity-thread",
    "semantic-neighbor",
    "backward",
    "long-jump",
    "external-in",
    "external-stay",
    "cause-to-effect",
    "effect-to-cause",
]
_FEATURES: tuple[Feature, ...] = (
    "stay",
    "discourse-successor",
    "world-time-successor",
    "causal-neighbor",
    "hierarchy-up",
    "hierarchy-down",
    "same-entity-thread",
    "semantic-neighbor",
    "backward",
    "long-jump",
    "external-in",
    "external-stay",
    "cause-to-effect",
    "effect-to-cause",
)
Layer = Literal["discourse", "world-time", "causal", "entity", "hierarchy", "semantic", "reference"]


class SemanticLink(Model):
    """An explicitly supplied similarity sidecar, not identity or calibrated probability."""

    source: Name
    target: Name
    weight: Probability


class SourceProjectionPolicy(Model):
    """Declare scoring coordinates and primary containment instead of inferring either silently."""

    recipe: Literal[
        "account-source-layers/v1", "account-source-layers/v2", "account-source-layers/v3"
    ] = "account-source-layers/v1"
    hierarchy: HierarchySpec | None = None
    scoring_documents: tuple[Name, ...] = ()
    # Each situation has at most one declared primary row parent. Row/scene parents
    # already live in the account. Generic situation part_of is deliberately not read.
    memberships: dict[Name, Name] = Field(default_factory=dict)
    entity_fanout: Annotated[int, Field(ge=0)] = 8
    semantic_links: tuple[SemanticLink, ...] = ()

    @model_validator(mode="after")
    def hierarchy_contract(self) -> Self:
        if self.recipe == "account-source-layers/v1":
            if "hierarchy" in self.model_fields_set or self.hierarchy is not None:
                raise ValueError("hierarchy field requires source projection v2")
        elif self.recipe == "account-source-layers/v2" and (
            self.hierarchy is None or self.memberships
        ):
            raise ValueError("projection v2 requires hierarchy and forbids legacy memberships")
        elif self.hierarchy is not None and self.memberships:
            raise ValueError("hierarchy cannot be combined with legacy memberships")
        return self

    @model_serializer(mode="wrap")
    def historical_wire(self, handler: SerializerFunctionWrapHandler) -> dict[str, Any]:
        data: dict[str, Any] = handler(self)
        if self.recipe == "account-source-layers/v1":
            data.pop("hierarchy", None)
        return data


class SourceNode(Model):
    """A source anchor retains actual parentage and optional document-local text coordinates."""

    anchor: Name
    kind: Literal["situation", "segment"]
    level: Annotated[int, Field(ge=0)]
    parent: Name | None
    document: Name | None
    onset: Annotated[int, Field(ge=0)] | None
    position: Probability | None


class SourceLayerEdge(SemanticLink):
    """A sparse directed edge keeps its scientific relation layer explicit."""

    layer: Layer


class SourceProjection(Model):
    """An account-bound derived snapshot; consumers recompute it before using saved values."""

    schema_version: Literal[
        "storymodel.source-projection/v1",
        "storymodel.source-projection/v2",
        "storymodel.source-projection/v3",
    ] = "storymodel.source-projection/v1"
    account_sha256: Digest
    policy: SourceProjectionPolicy
    nodes: tuple[SourceNode, ...]
    edges: tuple[SourceLayerEdge, ...]


def _default_weights() -> dict[Feature, float]:
    return {
        "stay": 0.5,
        "discourse-successor": 1.5,
        "world-time-successor": 1.0,
        "causal-neighbor": 0.8,
        "hierarchy-up": 0.6,
        "hierarchy-down": 0.6,
        "same-entity-thread": 0.2,
        "semantic-neighbor": 0.3,
        "backward": -0.5,
        "long-jump": -1.0,
        "external-in": -1.5,
        "external-stay": -0.85,
    }


class TransitionWeights(Model):
    """Finite model parameters; absent feature weights are zero, including external logits."""

    theta: dict[Feature, Finite] = Field(default_factory=_default_weights)


class SourceSequenceSpec(Model):
    """Declared local state populations/costs and a versioned source-transition policy."""

    schema_version: Literal["storymodel.source-sequence-spec/v1"] = (
        "storymodel.source-sequence-spec/v1"
    )
    rows: Annotated[tuple[SequenceRow, ...], Field(min_length=1)]
    projection: SourceProjectionPolicy = Field(default_factory=SourceProjectionPolicy)
    weights: TransitionWeights = Field(default_factory=TransitionWeights)
    temperature: Annotated[float, Field(gt=0, allow_inf_nan=False)] = 1.0


class TransitionDiagnostic(Model):
    """One anchor pair's features and score, shared by faithful and distorted modes."""

    source: Name
    target: Name
    features: dict[Feature, Finite]
    score: Finite


class SourceSequenceResult(Model):
    """Receipt for a producer of numerical inputs, without claiming local admissibility."""

    schema_version: Literal["storymodel.source-sequence/v1"] = "storymodel.source-sequence/v1"
    source_sha256: Digest
    recall_sha256: Digest
    spec_sha256: Digest
    projection: SourceProjection
    diagnostics: tuple[TransitionDiagnostic, ...]
    request: SequenceRequest


def _one_side(account: SupportedAccount, side: Literal["source", "recall"]) -> SupportedAccount:
    checked = require_text_account(account)
    if not checked.documents or any(d.side != side for d in checked.documents):
        raise ValueError(f"source transitions require an independent {side}-only account")
    return checked


def _cover(edges: set[tuple[str, str]]) -> set[tuple[str, str]]:
    """Reduce a checked DAG using bounded-memory reachability, never an all-pairs closure."""
    outgoing: dict[str, set[str]] = defaultdict(set)
    for a, b in edges:
        outgoing[a].add(b)
    kept = set()
    for a, b in edges:
        pending = list(outgoing[a] - {b})
        seen = set()
        while pending:
            item = pending.pop()
            if item == b:
                break
            if item not in seen:
                seen.add(item)
                pending.extend(outgoing.get(item, ()))
        else:
            kept.add((a, b))
    return kept


def _project_source_and_supports(
    account: SupportedAccount, policy: SourceProjectionPolicy | None = None
) -> tuple[SourceProjection, dict[str, tuple[Support, ...]]]:
    """Project source structure without accepting recall-dependent source construction.

    Multi-document situations retain explicit relations but have no single text position
    or discourse rank. Their entity neighbors use bounded stable-ID order. Within each
    unambiguous document, entity neighbors use first-occurrence rank; labels never join IDs.
    """
    checked = _one_side(account, "source")
    selected = _validate_instance(
        SourceProjectionPolicy, SourceProjectionPolicy() if policy is None else policy
    )
    temporal_claims = checked.temporal_relations if isinstance(checked, RichAccount) else ()
    if temporal_claims and selected.recipe != "account-source-layers/v3":
        raise ValueError("temporal claims require source projection v3")
    docs = {d.id: d for d in checked.documents}
    if len(set(selected.scoring_documents)) != len(selected.scoring_documents) or any(
        d not in docs for d in selected.scoring_documents
    ):
        raise ValueError("scoring documents must be unique source document IDs")
    scoring_lengths = {
        document: len(docs[document].text.encode("utf-16-le")) // 2
        for document in selected.scoring_documents
    }
    situations = {s.id: s for s in checked._situations()}
    units = {u.id: u for u in checked.units}
    parents = {u.id: u.parent for u in checked.units}
    parents.update({s: None for s in situations})
    for child, row_parent in selected.memberships.items():
        if child not in situations or row_parent not in units or units[row_parent].kind != "row":
            raise ValueError("primary membership requires an existing situation and source row")
        if not all(
            contained_in(s, (units[row_parent].support,)) for s in situations[child].evidence
        ):
            raise ValueError("primary row must contain all situation evidence")
        parents[child] = row_parent
    if any(parent is not None and parent not in parents for parent in parents.values()):
        raise ValueError("primary parent must be an existing source anchor or None")
    # Height, not a hard-coded scene level: nested scenes retain their actual hierarchy.
    levels = {s: 0 for s in situations}
    levels.update({u.id: 1 if u.kind == "row" else 2 for u in checked.units})
    children: dict[str, list[str]] = defaultdict(list)
    for child, parent in parents.items():
        if parent is not None:
            children[parent].append(child)
    remaining = {node: len(children.get(node, ())) for node in parents}
    leaves = [node for node, count in remaining.items() if count == 0]
    while leaves:
        leaf = leaves.pop()
        parent = parents[leaf]
        if parent is not None:
            levels[parent] = max(levels[parent], levels[leaf] + 1)
            remaining[parent] -= 1
            if remaining[parent] == 0:
                leaves.append(parent)
    supports = {
        anchor: text_evidence(
            situations[anchor].evidence if anchor in situations else (units[anchor].support,)
        )
        for anchor in parents
    }
    if selected.hierarchy is not None:
        hierarchy = build_hierarchy(checked, selected.hierarchy)
        parents = {n.anchor: n.parent for n in hierarchy.nodes}
        levels = {n.anchor: n.level for n in hierarchy.nodes}
        supports = {n.anchor: text_evidence(n.evidence) for n in hierarchy.nodes}
    nodes = []
    for anchor in sorted(parents):
        support = supports[anchor]
        documents = {s.document for s in support}
        document = next(iter(documents)) if len(documents) == 1 else None
        onset = min(s.start for item in support for s in item.spans) if document else None
        position = None
        if document in selected.scoring_documents:
            assert document is not None and onset is not None
            length = scoring_lengths[document]
            end = max(s.end for item in support for s in item.spans)
            position = (onset + end) / (2 * length)
        nodes.append(
            SourceNode(
                anchor=anchor,
                kind="situation" if anchor in situations else "segment",
                level=levels[anchor],
                parent=parents[anchor],
                document=document,
                onset=onset,
                position=position,
            )
        )
    index = {n.anchor: n for n in nodes}
    edges: dict[tuple[Layer, str, str], float] = {}

    def add(layer: Layer, a: str, b: str, weight: float = 1.0) -> None:
        if weight > 0:
            edges[layer, a, b] = weight

    by_level: dict[tuple[str, int], list[SourceNode]] = defaultdict(list)
    for node in nodes:
        if node.document is not None:
            by_level[node.document, node.level].append(node)
        if node.parent is not None:
            add("hierarchy", node.parent, node.anchor)
    discourse_rank = {}
    for level_members in by_level.values():
        level_order = sorted(level_members, key=lambda n: (n.onset, n.anchor))
        discourse_rank.update({n.anchor: i for i, n in enumerate(level_order)})
        for left, right in zip(level_order, level_order[1:]):
            add("discourse", left.anchor, right.anchor)
    contexts = {c.id: c for c in checked.contexts}
    before = set()
    for relation in checked.relations:
        if relation.kind == "before" and contexts[relation.context].kind == "narrated":
            before.add((relation.source, relation.target))
        elif relation.kind == "causes":
            add("causal", relation.source, relation.target)
        elif relation.kind == "references":
            add("reference", relation.source, relation.target)
    if selected.recipe == "account-source-layers/v3":
        # Structural precedence includes all admitted epistemic statuses equally, as in
        # Scala's WorldTime view. These unit weights are not confidence/probabilities.
        before.update(
            (r.source, r.target)
            for r in temporal_claims
            if r.relation in ("before", "meets") and contexts[r.context].kind == "narrated"
        )
    for a, b in _cover(before):
        add("world-time", a, b)
    entities = {s.id: {p.entity for p in s.participants} for s in situations.values()}
    by_entity: dict[str, list[str]] = defaultdict(list)
    for anchor, entity_ids in entities.items():
        for entity in entity_ids:
            by_entity[entity].append(anchor)
    fanout = selected.entity_fanout
    for members in by_entity.values():
        # There is no measured distance between different document coordinate systems.
        # Keep explicit identity continuity there, using a declared stable ordering only.
        ordered = sorted(
            members, key=lambda a: (index[a].document or "", discourse_rank.get(a, 0), a)
        )
        for i, a in enumerate(ordered):
            candidates = ordered[i + 1 : i + 1 + fanout] + ordered[max(0, i - fanout) : i]

            def distance(b: str) -> tuple[int, int]:
                same = index[a].document is not None and index[a].document == index[b].document
                return (0, abs(discourse_rank[a] - discourse_rank[b])) if same else (1, 0)

            for b in sorted(candidates, key=distance)[:fanout]:
                weight = len(entities[a] & entities[b]) / len(entities[a] | entities[b])
                add("entity", a, b, weight)
                add("entity", b, a, weight)
    seen = set()
    for link in selected.semantic_links:
        key = link.source, link.target
        if link.source not in index or link.target not in index or key in seen:
            raise ValueError("semantic links require known unique source pairs")
        seen.add(key)
        add("semantic", link.source, link.target, link.weight)
    result = SourceProjection(
        schema_version=(
            "storymodel.source-projection/v3"
            if selected.recipe == "account-source-layers/v3"
            else "storymodel.source-projection/v2"
            if selected.hierarchy is not None
            else "storymodel.source-projection/v1"
        ),
        account_sha256=digest(checked.model_dump(mode="json")),
        policy=selected,
        nodes=tuple(nodes),
        edges=tuple(
            SourceLayerEdge(layer=layer, source=a, target=b, weight=w)
            for (layer, a, b), w in sorted(edges.items())
        ),
    )

    return result, supports


def project_source(
    account: SupportedAccount, policy: SourceProjectionPolicy | None = None
) -> SourceProjection:
    """Project checked source structure; v2 consumes explicit identity-preserving hierarchy."""
    return _project_source_and_supports(account, policy)[0]


def verify_source_projection(
    account: SupportedAccount, policy: SourceProjectionPolicy, result: SourceProjection
) -> SourceProjection:
    """Recompute saved node/layer values against an independently supplied policy and account."""
    checked = _validate_instance(SourceProjection, result)
    expected = project_source(account, policy)
    if checked != expected:
        raise ValueError("source projection differs from account or policy")
    return expected


def _features(
    nodes: dict[str, SourceNode], edges: dict[tuple[Layer, str, str], float], a: str, b: str
) -> dict[Feature, float]:
    def has(layer: Layer, left: str, right: str) -> bool:
        return edges.get((layer, left, right), 0.0) > 0

    def ancestor(parent: str, child: str) -> bool:
        current = nodes[child].parent
        while current is not None:
            if current == parent:
                return True
            current = nodes[current].parent
        return False

    up, down = ancestor(b, a), ancestor(a, b)
    values: dict[Feature, float] = {
        "stay": float(a == b),
        "discourse-successor": float(has("discourse", a, b)),
        "world-time-successor": float(has("world-time", a, b)),
        "causal-neighbor": float(has("causal", a, b) or has("causal", b, a)),
        "hierarchy-up": float(up),
        "hierarchy-down": float(down),
        "same-entity-thread": float(has("entity", a, b) or has("entity", b, a)),
        "semantic-neighbor": max(
            edges.get(("semantic", a, b), 0), edges.get(("semantic", b, a), 0)
        ),
        "cause-to-effect": float(has("causal", a, b)),
        "effect-to-cause": float(has("causal", b, a)),
    }
    left, right = nodes[a], nodes[b]
    if left.position is not None and right.position is not None and left.document == right.document:
        values["backward"] = float(a != b and right.position < left.position and not up)
        values["long-jump"] = abs(right.position - left.position)
    return values


def _bernoulli(logit: float) -> tuple[float, float]:
    """Compute p and its complement separately, retaining small representable exit mass."""
    small = math.exp(-abs(logit))
    low, high = small / (1 + small), 1 / (1 + small)
    return (high, low) if logit >= 0 else (low, high)


def build_source_sequence(
    source: SupportedAccount, recall: SupportedAccount, spec: SourceSequenceSpec
) -> SourceSequenceResult:
    """Derive graph transitions for checked source anchors and explicitly supplied recall costs.

    This does not establish whether the supplied source/distorted modes are admissible.
    Every destination layer must include external states, as required by the Bernoulli
    mixture. A sole unranked destination is a legitimate external-only population.
    """
    source = _one_side(source, "source")
    recall = _one_side(recall, "recall")
    spec = _validate_instance(SourceSequenceSpec, spec)
    projection = project_source(source, spec.projection)
    nodes = {node.anchor: node for node in projection.nodes}
    layers = {(e.layer, e.source, e.target): e.weight for e in projection.edges}
    recall_units = {u.id for u in recall.units if u.kind == "recall"}
    if len({row.unit for row in spec.rows}) != len(spec.rows):
        raise ValueError("duplicate sequence unit")
    for row in spec.rows:
        if row.unit not in recall_units:
            raise ValueError("sequence unit is outside the independent recall account")
        for entry in row.costs:
            state = entry.state
            if state.anchor is not None:
                node = nodes.get(state.anchor)
                if node is None or node.kind != state.anchor_kind:
                    raise ValueError("sequence anchor or kind differs from checked source")
    diagnostics: dict[tuple[str, str], TransitionDiagnostic] = {}
    steps = []
    for previous, following in zip(spec.rows, spec.rows[1:]):
        anchored = sorted(
            (e.state for e in following.costs if e.state.anchor is not None),
            key=lambda s: s.sort_key,
        )
        external = sorted(
            (e.state for e in following.costs if e.state.anchor is None), key=lambda s: s.sort_key
        )
        if not external:
            raise ValueError(
                "graph transition destination requires an explicit external population"
            )
        transition_edges = []
        for entry in sorted(previous.costs, key=lambda e: e.state.sort_key):
            state = entry.state
            external_mass, source_mass = (
                _bernoulli(
                    spec.weights.theta.get(
                        "external-in" if state.anchor is not None else "external-stay", 0.0
                    )
                )
                if anchored
                else (1.0, 0.0)
            )
            probabilities = []
            if anchored and state.anchor is not None:
                scores = []
                for target in anchored:
                    assert target.anchor is not None
                    pair = state.anchor, target.anchor
                    if pair not in diagnostics:
                        values = _features(nodes, layers, *pair)
                        score = sum(
                            spec.weights.theta.get(k, 0.0) * values.get(k, 0.0) for k in _FEATURES
                        )
                        if not math.isfinite(score):
                            raise ValueError(
                                "transition feature score exceeds finite numeric range"
                            )
                        diagnostics[pair] = TransitionDiagnostic(
                            source=pair[0], target=pair[1], features=values, score=score
                        )
                    scores.append(diagnostics[pair].score)
                peak = max(scores)
                mass = [math.exp(score - peak) for score in scores]
                total = math.fsum(mass)
                probabilities = [source_mass * value / total for value in mass]
            elif anchored:
                probabilities = [source_mass / len(anchored)] * len(anchored)
            for target, probability in zip(anchored, probabilities):
                if probability > 0:
                    transition_edges.append(
                        TransitionEdge(
                            from_state=state.id, to_state=target.id, probability=probability
                        )
                    )
            for target in external:
                probability = external_mass / len(external)
                if probability > 0:
                    transition_edges.append(
                        TransitionEdge(
                            from_state=state.id, to_state=target.id, probability=probability
                        )
                    )
        steps.append(
            SequenceTransition(
                from_unit=previous.unit, to_unit=following.unit, edges=tuple(transition_edges)
            )
        )
    request = SequenceRequest(
        rows=spec.rows,
        transitions=tuple(steps),
        temperature=spec.temperature,
        export_permission="allowed"
        if source.export_permission == recall.export_permission == "allowed"
        else "denied",
    )
    return SourceSequenceResult(
        source_sha256=projection.account_sha256,
        recall_sha256=digest(recall.model_dump(mode="json")),
        spec_sha256=digest(spec.model_dump(mode="json")),
        projection=projection,
        diagnostics=tuple(diagnostics[pair] for pair in sorted(diagnostics)),
        request=request,
    )


def verify_source_sequence(
    source: SupportedAccount,
    recall: SupportedAccount,
    spec: SourceSequenceSpec,
    result: SourceSequenceResult,
) -> SourceSequenceResult:
    """Verify all projection, transition and provenance fields against original inputs."""
    checked = _validate_instance(SourceSequenceResult, result)
    expected = build_source_sequence(source, recall, spec)
    if checked != expected:
        raise ValueError("source sequence differs from source, recall or specification")
    return expected
