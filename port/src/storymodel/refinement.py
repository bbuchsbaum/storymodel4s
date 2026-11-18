"""Sparse relation-preservation refinement for a checked sequence inference.

The relation inputs are already admitted at the account boundary.  This module only
uses their typed unit endpoints, and leaves source construction and claim exclusion
to its callers.
"""

from __future__ import annotations

import math
from collections import defaultdict, deque
from collections.abc import Iterator
from typing import Annotated, Literal

from pydantic import Field, field_validator, model_validator

from .model import Model, Name, _validate_instance
from .sequence import (
    Finite,
    SequenceRequest,
    SequenceResult,
    StateCost,
    infer_sequence,
    validate_sequence,
)
from .source_transitions import SourceProjection

UnitRelationKind = Literal["before", "after", "simultaneous", "causes"]
PreservationLayer = Literal["world-time", "causal"]


class UnitRelation(Model):
    """One admitted relation claim between two recall units, with its claim provenance."""

    claim: Name
    relation: UnitRelationKind
    from_unit: Name
    to_unit: Name

    @model_validator(mode="after")
    def distinct_endpoints(self) -> UnitRelation:
        if self.from_unit == self.to_unit:
            raise ValueError("unit relation endpoints must be distinct")
        return self


class RefinementPolicy(Model):
    """Bounded corrective strength; each pass returns to the original declared costs."""

    passes: Annotated[int, Field(ge=0)] = 0
    weight: Annotated[Finite, Field(ge=0)] = 0.3

    @field_validator("weight")
    @classmethod
    def canonical_zero(cls, weight: float) -> float:
        return 0.0 if weight == 0.0 else weight


class LayerPreservation(Model):
    """Evaluable relation support for one layer; no evaluable claim has no mean."""

    stated: Annotated[int, Field(ge=0)]
    evaluated: Annotated[int, Field(ge=0)]
    mean: Annotated[float | None, Field(allow_inf_nan=False)]

    @model_validator(mode="after")
    def support_contract(self) -> LayerPreservation:
        if self.evaluated > self.stated:
            raise ValueError("evaluated relations exceed stated relations")
        if (self.mean is None) != (self.evaluated == 0):
            raise ValueError("mean is present exactly for evaluated relations")
        if self.mean is not None and not 0 <= self.mean <= 1:
            raise ValueError("preservation mean must be a fraction")
        return self


class RefinementResult(Model):
    """Final numerical artifact and diagnostics, without retaining a misleading pass history."""

    request: SequenceRequest
    inference: SequenceResult
    preservation: dict[PreservationLayer, LayerPreservation]
    relations: tuple[UnitRelation, ...]
    policy: RefinementPolicy


def _normalized(
    relations: tuple[UnitRelation, ...],
) -> tuple[tuple[PreservationLayer, str, str], ...]:
    """Turn directional claims into source-layer directions; simultaneity is diagnostic-only."""

    normalized: list[tuple[PreservationLayer, str, str]] = []
    for relation in relations:
        if relation.relation == "before":
            normalized.append(("world-time", relation.from_unit, relation.to_unit))
        elif relation.relation == "after":
            normalized.append(("world-time", relation.to_unit, relation.from_unit))
        elif relation.relation == "causes":
            normalized.append(("causal", relation.from_unit, relation.to_unit))
    return tuple(normalized)


def _reachable_targets(outgoing: dict[str, tuple[str, ...]], source: str) -> set[str]:
    """Traverse one source on demand, without retaining an all-pairs closure."""

    pending: deque[str] = deque(outgoing.get(source, ()))
    seen: set[str] = set()
    while pending:
        current = pending.popleft()
        if current not in seen:
            seen.add(current)
            pending.extend(outgoing.get(current, ()))
    return seen


def _state_anchors(request: SequenceRequest) -> dict[str, str | None]:
    return {entry.state.id: entry.state.anchor for row in request.rows for entry in row.costs}


def _posteriors(result: SequenceResult) -> dict[str, dict[str, float]]:
    return {
        row.unit: {mass.state: mass.probability for mass in row.mass} for row in result.posterior
    }


def _layer_graphs(
    projection: SourceProjection,
) -> dict[PreservationLayer, dict[str, tuple[str, ...]]]:
    graphs: dict[PreservationLayer, dict[str, list[str]]] = {
        "world-time": defaultdict(list),
        "causal": defaultdict(list),
    }
    for edge in projection.edges:
        if edge.weight > 0 and edge.layer in graphs:
            graphs[edge.layer][edge.source].append(edge.target)
    return {
        layer: {node: tuple(sorted(set(targets))) for node, targets in graph.items()}
        for layer, graph in graphs.items()
    }


def _validate_boundary(
    request: SequenceRequest,
    projection: SourceProjection,
    relations: tuple[UnitRelation, ...],
) -> None:
    """Refuse stale or forged graph references before numerical consumption."""

    nodes = {node.anchor: node for node in projection.nodes}
    if len(nodes) != len(projection.nodes):
        raise ValueError("source projection has duplicate anchors")
    edge_keys: set[tuple[str, str, str]] = set()
    for edge in projection.edges:
        if edge.source not in nodes or edge.target not in nodes:
            raise ValueError("source projection edge has an unknown anchor")
        key = edge.layer, edge.source, edge.target
        if key in edge_keys:
            raise ValueError("source projection has duplicate layer edge")
        edge_keys.add(key)
    for row in request.rows:
        for entry in row.costs:
            state = entry.state
            if state.anchor is not None:
                node = nodes.get(state.anchor)
                if node is None or node.kind != state.anchor_kind:
                    raise ValueError("sequence anchor differs from source projection")
    claims = [relation.claim for relation in relations]
    if len(claims) != len(set(claims)):
        raise ValueError("relation claim IDs must be unique")


def _anchor_masses(posterior: dict[str, float], anchors: dict[str, str | None]) -> dict[str, float]:
    by_anchor: dict[str, list[float]] = defaultdict(list)
    for state, mass in posterior.items():
        if (anchor := anchors[state]) is not None:
            by_anchor[anchor].append(mass)
    return {anchor: math.fsum(masses) for anchor, masses in by_anchor.items()}


def _compatible_targets(
    graph: dict[str, tuple[str, ...]], left: dict[str, float], right: dict[str, float]
) -> Iterator[tuple[str, set[str]]]:
    """Keep at most one graph traversal and its admitted targets in memory."""
    for source in left:
        yield source, _reachable_targets(graph, source).intersection(right)


def _induced(
    left: dict[str, float],
    right: dict[str, float],
    anchors: dict[str, str | None],
    graph: dict[str, tuple[str, ...]],
) -> float | None:
    """Evaluate the product of two marginals conditioned separately on an anchor."""
    a, b = _anchor_masses(left, anchors), _anchor_masses(right, anchors)
    total_a, total_b = math.fsum(a.values()), math.fsum(b.values())
    if total_a == 0 or total_b == 0:
        return None
    agreement = math.fsum(
        (a[source] / total_a) * math.fsum(b[target] / total_b for target in targets)
        for source, targets in _compatible_targets(graph, a, b)
    )
    return min(1.0, agreement)  # Normalization may round one ulp above one.


def _diagnostic(
    result: SequenceResult,
    request: SequenceRequest,
    relations: tuple[UnitRelation, ...],
    graphs: dict[PreservationLayer, dict[str, tuple[str, ...]]],
) -> dict[PreservationLayer, LayerPreservation]:
    rows, anchors = _posteriors(result), _state_anchors(request)
    stated: dict[PreservationLayer, list[float | None]] = {"world-time": [], "causal": []}
    for layer, left, right in _normalized(relations):
        stated[layer].append(_induced(rows[left], rows[right], anchors, graphs[layer]))
    # Simultaneity is explicitly stated but has no direction in a strict precedence layer.
    stated["world-time"].extend(
        None for relation in relations if relation.relation == "simultaneous"
    )
    scores: dict[PreservationLayer, LayerPreservation] = {}
    for layer, values in stated.items():
        evaluated = [value for value in values if value is not None]
        scores[layer] = LayerPreservation(
            stated=len(values),
            evaluated=len(evaluated),
            mean=math.fsum(evaluated) / len(evaluated) if evaluated else None,
        )
    return scores


def _reweight(
    base: SequenceRequest,
    result: SequenceResult,
    relations: tuple[UnitRelation, ...],
    graphs: dict[PreservationLayer, dict[str, tuple[str, ...]]],
    weight: float,
) -> SequenceRequest:
    """Reweight only existing anchored states; every pass begins from ``base`` costs."""

    anchors, posterior = _state_anchors(base), _posteriors(result)
    masses = {unit: _anchor_masses(row, anchors) for unit, row in posterior.items()}
    bonus: dict[tuple[str, str], float] = defaultdict(float)
    for layer, left_unit, right_unit in _normalized(relations):
        left, right = masses[left_unit], masses[right_unit]
        for source, targets in _compatible_targets(graphs[layer], left, right):
            bonus[left_unit, source] += math.fsum(right[target] for target in targets)
            for target in targets:
                bonus[right_unit, target] += left[source]

    def reweighted(entry: StateCost, unit: str) -> StateCost:
        anchor = entry.state.anchor
        if anchor is None:
            return entry
        cost = entry.cost - weight * bonus.get((unit, anchor), 0.0)
        if not math.isfinite(cost):
            raise ValueError("relation refinement cost is outside binary64 range")
        return StateCost(state=entry.state, cost=cost)

    return base.model_copy(
        update={
            "rows": tuple(
                row.model_copy(
                    update={"costs": tuple(reweighted(entry, row.unit) for entry in row.costs)}
                )
                for row in base.rows
            )
        }
    )


def refine_sequence(
    request: SequenceRequest,
    projection: SourceProjection,
    relations: tuple[UnitRelation, ...],
    policy: RefinementPolicy,
) -> RefinementResult:
    """Run bounded preservation while preserving the base state space and costs contract."""

    base = validate_sequence(request)
    projection = _validate_instance(SourceProjection, projection)
    policy = _validate_instance(RefinementPolicy, policy)
    checked_relations = tuple(_validate_instance(UnitRelation, relation) for relation in relations)
    units = {row.unit for row in base.rows}
    if any(
        relation.from_unit not in units or relation.to_unit not in units
        for relation in checked_relations
    ):
        raise ValueError("relation endpoint is outside the sequence request")
    _validate_boundary(base, projection, checked_relations)
    graphs = _layer_graphs(projection)
    current = base
    inference = infer_sequence(current)
    if policy.weight and _normalized(checked_relations):
        for _ in range(policy.passes):
            current = _reweight(base, inference, checked_relations, graphs, policy.weight)
            inference = infer_sequence(current)
    return RefinementResult(
        request=current,
        inference=inference,
        preservation=_diagnostic(inference, current, checked_relations, graphs),
        relations=checked_relations,
        policy=policy,
    )
