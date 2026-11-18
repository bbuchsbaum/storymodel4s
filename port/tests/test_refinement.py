"""Independent controls for sparse directed relation-preservation refinement."""

from __future__ import annotations

import json
import math
from pathlib import Path

import pytest

from storymodel import refinement
from storymodel.refinement import (
    RefinementPolicy,
    UnitRelation,
    refine_sequence,
)
from storymodel.sequence import (
    SequenceRequest,
    SequenceRow,
    SequenceState,
    SequenceTransition,
    StateCost,
    TransitionEdge,
    infer_sequence,
)
from storymodel.source_transitions import (
    SourceLayerEdge,
    SourceNode,
    SourceProjection,
    SourceProjectionPolicy,
)


def source(anchor: str) -> SequenceState:
    return SequenceState(kind="source", anchor_kind="situation", anchor=anchor)


def distorted(anchor: str) -> SequenceState:
    return SequenceState(
        kind="distorted", anchor_kind="situation", anchor=anchor, facets=("action",)
    )


def external() -> SequenceState:
    return SequenceState(kind="external", external="association")


def row(unit: str, *states: SequenceState) -> SequenceRow:
    return SequenceRow(unit=unit, costs=tuple(StateCost(state=state, cost=0) for state in states))


def complete_step(left: SequenceRow, right: SequenceRow) -> SequenceTransition:
    probability = 1 / len(right.costs)
    return SequenceTransition(
        from_unit=left.unit,
        to_unit=right.unit,
        edges=tuple(
            TransitionEdge(
                from_state=before.state.id, to_state=after.state.id, probability=probability
            )
            for before in left.costs
            for after in right.costs
        ),
    )


def projection(*edges: tuple[str, str, str], nodes: tuple[str, ...] = ()) -> SourceProjection:
    anchors = sorted(
        {*nodes, *(anchor for _, source, target in edges for anchor in (source, target))}
    )
    return SourceProjection(
        account_sha256="0" * 64,
        policy=SourceProjectionPolicy(),
        nodes=tuple(
            SourceNode(
                anchor=anchor,
                kind="situation",
                level=0,
                parent=None,
                document=None,
                onset=None,
                position=None,
            )
            for anchor in anchors
        ),
        edges=tuple(
            SourceLayerEdge(layer=layer, source=source, target=target, weight=1)
            for layer, source, target in edges
        ),
    )


def test_zero_weight_has_one_serialized_identity():
    assert (
        RefinementPolicy(weight=-0.0).model_dump_json()
        == RefinementPolicy(weight=0.0).model_dump_json()
    )


def test_diagnostic_uses_directed_positive_transitive_paths_and_source_conditional_denominator():
    a, b = source("a"), distorted("b")
    left, right = row("left", a, external()), row("right", b, external())
    request = SequenceRequest(rows=(left, right), transitions=(complete_step(left, right),))

    result = refine_sequence(
        request,
        projection(("world-time", "a", "middle"), ("world-time", "middle", "b")),
        (UnitRelation(claim="c1", relation="before", from_unit="left", to_unit="right"),),
        RefinementPolicy(),
    )

    world = result.preservation["world-time"]
    assert (world.stated, world.evaluated, world.mean) == (1, 1, 1.0)
    # The two external alternatives each have 1/2 posterior mass.  They are excluded from
    # the preservation denominator, rather than making this directed source relation 1/4.
    assert {mass.probability for mass in result.inference.posterior[0].mass} == {0.5}


@pytest.mark.parametrize("only_external", [False, True])
def test_after_direction_and_diagnostic_with_tiny_or_absent_source_mass(only_external):
    a, b = source("a"), source("b")
    left = row("left", external()) if only_external else row("left", b, external())
    right = row("right", a, external())
    rows = tuple(
        r.model_copy(
            update={
                "costs": tuple(
                    StateCost(state=c.state, cost=460.0 if c.state.anchor else 0.0) for c in r.costs
                )
            }
        )
        for r in (left, right)
    )
    request = SequenceRequest(rows=rows, transitions=(complete_step(*rows),))
    result = refine_sequence(
        request,
        projection(("world-time", "a", "b")),
        (UnitRelation(claim="reverse", relation="after", from_unit="left", to_unit="right"),),
        RefinementPolicy(),
    )
    score = result.preservation["world-time"]
    assert (score.stated, score.evaluated, score.mean) == (
        (1, 0, None) if only_external else (1, 1, 1.0)
    )


def test_reweight_is_anchored_to_base_costs_and_never_changes_external_or_state_space():
    a, x, b, y = source("a"), source("x"), source("b"), source("y")
    left, right = row("left", a, x, external()), row("right", b, y, external())
    request = SequenceRequest(rows=(left, right), transitions=(complete_step(left, right),))
    relation = UnitRelation(claim="c1", relation="before", from_unit="left", to_unit="right")
    view = projection(("world-time", "a", "b"), nodes=("x", "y"))

    one = refine_sequence(request, view, (relation,), RefinementPolicy(passes=1, weight=0.6))
    two = refine_sequence(request, view, (relation,), RefinementPolicy(passes=2, weight=0.6))
    initial = infer_sequence(request)

    costs_one = {entry.state.id: entry.cost for line in one.request.rows for entry in line.costs}
    costs_two = {entry.state.id: entry.cost for line in two.request.rows for entry in line.costs}
    assert costs_one[a.id] == pytest.approx(-0.2)
    assert costs_one[b.id] == pytest.approx(-0.2)
    assert costs_one[external().id] == 0
    assert costs_one[x.id] == costs_one[y.id] == 0
    # A second pass recomputes support from its first inference but still subtracts from the
    # original zero costs; it must not accumulate a second -0.2 onto the first request.
    assert costs_two[a.id] < costs_one[a.id]
    assert costs_two[a.id] > -0.6
    assert tuple(line.costs for line in one.request.rows) != tuple(
        line.costs for line in request.rows
    )
    assert tuple(line.unit for line in initial.posterior) == tuple(
        line.unit for line in one.inference.posterior
    )
    assert all(
        {entry.state.id for entry in old.costs} == {entry.state.id for entry in new.costs}
        for old, new in zip(request.rows, one.request.rows)
    )


def test_simultaneous_is_stated_but_has_no_directed_evaluation_and_foreign_units_refuse():
    a, b = source("a"), source("b")
    left, right = row("left", a), row("right", b)
    request = SequenceRequest(rows=(left, right), transitions=(complete_step(left, right),))
    view = projection(("world-time", "a", "b"))
    simultaneous = UnitRelation(
        claim="sim", relation="simultaneous", from_unit="left", to_unit="right"
    )
    result = refine_sequence(request, view, (simultaneous,), RefinementPolicy(passes=2, weight=1))
    assert result.request == request
    assert result.preservation["world-time"].model_dump() == {
        "stated": 1,
        "evaluated": 0,
        "mean": None,
    }
    with pytest.raises(ValueError, match="outside the sequence request"):
        refine_sequence(
            request,
            view,
            (UnitRelation(claim="bad", relation="before", from_unit="left", to_unit="missing"),),
            RefinementPolicy(),
        )


def test_scala_relation_preservation_fixture_matches_directed_diagnostic_and_reweight():
    """Consume the independent Scala probe rather than deriving expectations in this module."""

    fixture = json.loads(
        (Path(__file__).parents[1] / "evidence/refinement/relation-preservation.json").read_text()
    )
    e2, e3, e5, e1 = (
        source("e2-hear"),
        distorted("e3-search"),
        source("e5-find"),
        source("e1-arrive"),
    )
    association = external()
    intrusion = SequenceState(kind="external", external="intrusion")
    u3 = SequenceRow(
        unit="u3",
        costs=tuple(
            StateCost(state=state, cost=cost)
            for state, cost in ((e2, 10), (e3, 20), (association, 30))
        ),
    )
    u2 = SequenceRow(
        unit="u2",
        costs=tuple(
            StateCost(state=state, cost=cost)
            for state, cost in ((e5, 40), (e1, 50), (intrusion, 60))
        ),
    )
    # An independent product-state construction gives the Scala probe's explicit posterior
    # marginals exactly before the pure relation-preservation correction is applied.
    posterior_u3 = (0.2, 0.3, 0.5)
    posterior_u2 = (0.4, 0.2, 0.4)
    posterior_request = SequenceRequest(
        rows=(
            SequenceRow(
                unit="u3",
                costs=tuple(
                    StateCost(state=state, cost=-math.log(mass))
                    for state, mass in zip((e2, e3, association), posterior_u3)
                ),
            ),
            SequenceRow(
                unit="u2",
                costs=tuple(
                    StateCost(state=state, cost=-math.log(mass))
                    for state, mass in zip((e5, e1, intrusion), posterior_u2)
                ),
            ),
        ),
        transitions=(
            SequenceTransition(
                from_unit="u3",
                to_unit="u2",
                edges=tuple(
                    TransitionEdge(from_state=before.id, to_state=after.id, probability=1 / 3)
                    for before in (e2, e3, association)
                    for after in (e5, e1, intrusion)
                ),
            ),
        ),
    )
    base = SequenceRequest(rows=(u3, u2), transitions=posterior_request.transitions)
    view = projection(
        ("world-time", "e1-arrive", "e2-hear"),
        ("world-time", "e2-hear", "e3-search"),
        ("world-time", "e3-search", "e4-enter"),
        ("world-time", "e4-enter", "e5-find"),
        ("causal", "e2-hear", "e3-search"),
        ("causal", "e2-hear", "e4-enter"),
        ("causal", "e4-enter", "e5-find"),
    )
    relations = (
        UnitRelation(claim="temporal", relation="before", from_unit="u3", to_unit="u2"),
        UnitRelation(claim="causal", relation="causes", from_unit="u3", to_unit="u2"),
    )
    inferred = infer_sequence(posterior_request)
    graphs = refinement._layer_graphs(view)
    preservation = refinement._diagnostic(inferred, base, relations, graphs)
    for layer, expected in fixture["expected"]["preservation"].items():
        observed = preservation[layer]
        assert (observed.stated, observed.evaluated) == (expected["stated"], expected["evaluated"])
        assert observed.mean == pytest.approx(expected["mean"])
    costs = {
        row.unit: {entry.state.id: entry.cost for entry in row.costs}
        for row in refinement._reweight(base, inferred, relations, graphs, 0.5).rows
    }
    expected = fixture["expected"]["final_costs"]
    assert costs["u3"] == pytest.approx(
        {
            e2.id: expected["u3"]["e2-hear:faithful"],
            e3.id: expected["u3"]["e3-search:distorted:Polarity"],
            association.id: expected["u3"]["external:Association"],
        }
    )
    assert costs["u2"] == pytest.approx(
        {
            e5.id: expected["u2"]["e5-find:faithful"],
            e1.id: expected["u2"]["e1-arrive:faithful"],
            intrusion.id: expected["u2"]["external:Intrusion"],
        }
    )
