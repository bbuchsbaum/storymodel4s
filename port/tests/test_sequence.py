"""Independent exhaustive and high-precision witnesses for sparse sequence inference."""

from __future__ import annotations

import math
import sys
from decimal import Decimal, localcontext
from itertools import product

import pytest

from storymodel.sequence import (
    SequenceRequest,
    SequenceRow,
    SequenceState,
    SequenceTransition,
    StateCost,
    StateMass,
    TransitionEdge,
    infer_sequence,
    validate_sequence,
    verify_sequence,
)
from storymodel.serialization import canonical


def source(anchor: str) -> SequenceState:
    return SequenceState(kind="source", anchor_kind="situation", anchor=anchor)


def distorted(anchor: str, *facets: str) -> SequenceState:
    return SequenceState(kind="distorted", anchor_kind="situation", anchor=anchor, facets=facets)


def external(kind: str) -> SequenceState:
    return SequenceState(kind="external", external=kind)


def row(unit: str, *entries: tuple[SequenceState, float]) -> SequenceRow:
    return SequenceRow(unit=unit, costs=tuple(StateCost(state=s, cost=c) for s, c in entries))


def transition(
    left: str, right: str, *entries: tuple[SequenceState, SequenceState, float]
) -> SequenceTransition:
    return SequenceTransition(
        from_unit=left,
        to_unit=right,
        edges=tuple(
            TransitionEdge(from_state=a.id, to_state=b.id, probability=p) for a, b, p in entries
        ),
    )


def scala_key(state: SequenceState) -> str:
    """Render the documented Scala ordering independently of the implementation sort key."""

    def title(value: str) -> str:
        return "".join(piece.title() for piece in value.split("-"))

    if state.external is not None:
        return f"ext:{title(state.external)}"
    key = f"{state.anchor_kind}/{state.anchor}"
    if state.facets:
        key += "/distorted/" + ",".join(title(value) for value in state.facets)
    return key


def direct_oracle(
    request: SequenceRequest,
) -> tuple[
    float, dict[tuple[int, str], float], dict[tuple[int, str, str], float], tuple[str, ...], float
]:
    """Enumerate paths directly; no forward/backward or production numerical helper is used."""
    states = [[entry.state for entry in line.costs] for line in request.rows]
    costs = [{entry.state.id: entry.cost for entry in line.costs} for line in request.rows]
    transitions = [
        {(edge.from_state, edge.to_state): edge.probability for edge in step.edges}
        for step in request.transitions
    ]
    paths = list(product(*states))
    weights: list[tuple[tuple[SequenceState, ...], float]] = []
    for path in paths:
        weight = math.exp(
            -sum(costs[i][state.id] for i, state in enumerate(path)) / request.temperature
        )
        weight /= len(states[0])
        for index, (before, after) in enumerate(zip(path, path[1:])):
            weight *= transitions[index].get((before.id, after.id), 0.0)
        weights.append((path, weight))
    normalizer = math.fsum(weight for _, weight in weights)
    posterior = {
        (index, state.id): math.fsum(weight for path, weight in weights if path[index] == state)
        / normalizer
        for index, layer in enumerate(states)
        for state in layer
    }
    flow = {
        (index, before, after): math.fsum(
            weight
            for path, weight in weights
            if path[index].id == before and path[index + 1].id == after
        )
        / normalizer
        for index, step in enumerate(transitions)
        for before, after in step
    }
    best_weight = max(weight for _, weight in weights)
    # Scala breaks exact ties from final state back through predecessors, not forward lexically.
    best_path = max(
        (path for path, weight in weights if weight == best_weight),
        key=lambda path: tuple(scala_key(state) for state in reversed(path)),
    )
    return (
        math.log(normalizer),
        posterior,
        flow,
        tuple(state.id for state in best_path),
        math.log(best_weight),
    )


def assert_matches_oracle(request: SequenceRequest) -> None:
    result = infer_sequence(request)
    log_z, posterior, flow, best_path, best_weight = direct_oracle(request)
    assert result.log_normalizer == pytest.approx(log_z, abs=1e-12, rel=0)
    assert result.best_path_log_weight == pytest.approx(best_weight, abs=1e-12, rel=0)
    assert result.best_path == best_path
    assert tuple(line.unit for line in result.posterior) == tuple(row.unit for row in request.rows)
    assert tuple((step.from_unit, step.to_unit) for step in result.flow) == tuple(
        (step.from_unit, step.to_unit) for step in request.transitions
    )
    for index, (line, requested) in enumerate(zip(result.posterior, request.rows)):
        expected_states = {entry.state.id for entry in requested.costs}
        assert {mass.state for mass in line.mass} == expected_states
        for mass in line.mass:
            assert mass.probability == pytest.approx(posterior[index, mass.state], abs=1e-12, rel=0)
    for index, (step, requested) in enumerate(zip(result.flow, request.transitions)):
        expected_edges = {
            (edge.from_state, edge.to_state) for edge in requested.edges if edge.probability > 0
        }
        assert {(mass.from_state, mass.to_state) for mass in step.mass} == expected_edges
        for mass in step.mass:
            assert mass.probability == pytest.approx(
                flow[index, mass.from_state, mass.to_state], abs=1e-12, rel=0
            )
        previous = {mass.state: mass.probability for mass in result.posterior[index].mass}
        following = {mass.state: mass.probability for mass in result.posterior[index + 1].mass}
        for state, probability in previous.items():
            assert math.fsum(
                m.probability for m in step.mass if m.from_state == state
            ) == pytest.approx(probability, abs=1e-12, rel=0)
        for state, probability in following.items():
            assert math.fsum(
                m.probability for m in step.mass if m.to_state == state
            ) == pytest.approx(probability, abs=1e-12, rel=0)


def test_three_unit_seven_state_exhaustive_oracle_checks_marginals_flows_and_viterbi():
    a, b = source("a"), source("b")
    externals = tuple(
        external(value)
        for value in (
            "association",
            "commentary",
            "source-consistent-inference",
            "intrusion",
            "uninterpretable",
        )
    )
    states = (a, b, *externals)
    enter = 1.0 / (1.0 + math.exp(0.75))
    stay_external = 1.0 / (1.0 + math.exp(0.425))
    stay_source = math.exp(0.25)

    def costs(index: int) -> tuple[tuple[SequenceState, float], ...]:
        return tuple(
            (state, 0.6 if state.external else (0.1 if (index == 1) == (state == a) else 0.4))
            for state in states
        )

    def edges(left: str, right: str):
        values = []
        for before in states:
            for after in states:
                if before.external:
                    probability = stay_external / 5 if after.external else (1 - stay_external) / 2
                elif after.external:
                    probability = enter / 5
                else:
                    probability = (
                        (1 - enter) * (stay_source if before == after else 1) / (stay_source + 1)
                    )
                values.append((before, after, probability))
        return transition(left, right, *values)

    request = SequenceRequest(
        rows=(row("u0", *costs(0)), row("u1", *costs(1)), row("u2", *costs(2))),
        transitions=(edges("u0", "u1"), edges("u1", "u2")),
        temperature=0.15,
        export_permission="allowed",
    )
    assert_matches_oracle(request)


def test_sparse_absent_and_explicit_zero_edges_leave_zero_state_and_no_zero_flow():
    a, b, c, dead = source("a"), source("b"), source("c"), source("dead")
    request = SequenceRequest(
        rows=(row("left", (a, 0), (b, 0)), row("right", (c, 0), (dead, 0))),
        transitions=(transition("left", "right", (a, c, 1), (b, c, 1), (b, dead, 0)),),
    )
    result = infer_sequence(request)
    assert_matches_oracle(request)
    assert {(mass.from_state, mass.to_state) for mass in result.flow[0].mass} == {
        (a.id, c.id),
        (b.id, c.id),
    }
    assert all(mass.to_state != dead.id for mass in result.flow[0].mass)
    assert next(mass for mass in result.posterior[1].mass if mass.state == dead.id).probability == 0


def test_single_reachable_path_single_unit_and_special_states_remain_distinct():
    a, unreachable = source("a"), source("unreachable")
    only = source("only")
    request = SequenceRequest(rows=(row("one", (only, 2.0)),), transitions=(), temperature=0.5)
    only_result = infer_sequence(request)
    assert only_result.best_path == (only.id,)
    assert only_result.posterior[0].mass == (StateMass(state=only.id, probability=1),)

    reachable = SequenceRequest(
        rows=(row("one", (only, 0)), row("two", (a, 0), (unreachable, 0))),
        transitions=(transition("one", "two", (only, a, 1)),),
    )
    assert_matches_oracle(reachable)
    assert (
        next(
            x for x in infer_sequence(reachable).posterior[1].mass if x.state == unreachable.id
        ).probability
        == 0
    )

    plain, altered, unranked = source("same"), distorted("same", "polarity"), external("unranked")
    mixed = SequenceRequest(rows=(row("mixed", (plain, 0), (altered, 0)),), transitions=())
    assert_matches_oracle(mixed)
    mixed_mass = {mass.state: mass.probability for mass in infer_sequence(mixed).posterior[0].mass}
    assert mixed_mass == {plain.id: 0.5, altered.id: 0.5}
    sole = SequenceRequest(rows=(row("u", (unranked, 3)),), transitions=())
    assert infer_sequence(sole).best_path == (unranked.id,)
    with pytest.raises(ValueError, match="unranked"):
        row("bad", (plain, 0), (unranked, 0))


def test_state_and_edge_input_order_do_not_change_canonical_numerics_or_tie_choice():
    a, b, c, d = source("a"), source("b"), source("c"), source("d")
    forward = SequenceRequest(
        rows=(row("x", (a, 0), (b, 0)), row("y", (c, 0), (d, 0))),
        transitions=(transition("x", "y", (a, c, 0.5), (a, d, 0.5), (b, c, 0.5), (b, d, 0.5)),),
    )
    reversed_input = SequenceRequest(
        rows=(row("x", (b, 0), (a, 0)), row("y", (d, 0), (c, 0))),
        transitions=(transition("x", "y", (b, d, 0.5), (b, c, 0.5), (a, d, 0.5), (a, c, 0.5)),),
    )
    left, right = infer_sequence(forward), infer_sequence(reversed_input)
    assert left.request_sha256 != right.request_sha256
    assert left.model_dump(exclude={"request_sha256"}) == right.model_dump(
        exclude={"request_sha256"}
    )
    assert left.best_path == (b.id, d.id)


def test_viterbi_ties_prioritize_final_state_over_earlier_state():
    early_low, early_high = source("a"), source("b")
    final_low, final_high = source("a-final"), source("z-final")
    request = SequenceRequest(
        rows=(
            row("early", (early_low, 0), (early_high, 0)),
            row("final", (final_low, 0), (final_high, 0)),
        ),
        transitions=(
            transition("early", "final", (early_low, final_high, 1), (early_high, final_low, 1)),
        ),
    )
    assert_matches_oracle(request)
    assert infer_sequence(request).best_path == (early_low.id, final_high.id)


def test_viterbi_breaks_a_repeated_product_tie_by_greatest_rendered_path():
    start, direct, product_state, filler = (
        source("start"),
        source("direct"),
        source("product"),
        source("filler"),
    )
    z, waste = source("z"), source("waste")
    request = SequenceRequest(
        rows=(
            row("first", (start, 0)),
            row("middle", (direct, 0), (product_state, 0), (filler, 100)),
            row("last", (z, 0), (waste, 100)),
        ),
        transitions=(
            transition(
                "first",
                "middle",
                (start, direct, 0.25),
                (start, product_state, 0.5),
                (start, filler, 0.25),
            ),
            transition(
                "middle",
                "last",
                (direct, z, 1),
                (product_state, z, 0.5),
                (product_state, waste, 0.5),
                (filler, z, 1),
            ),
        ),
    )
    result = infer_sequence(request)
    assert result.best_path == (start.id, product_state.id, z.id)
    assert result.best_path_log_weight == pytest.approx(math.log(0.25), abs=1e-12, rel=0)


def test_largest_cost_and_smallest_temperature_refuse_nonfinite_output_weight():
    state = source("finite-inputs")
    request = SequenceRequest(
        rows=(row("only", (state, sys.float_info.max)),),
        transitions=(),
        temperature=float.fromhex("0x0.0000000000001p-1022"),
    )
    with pytest.raises(ValueError, match="binary64 output range"):
        infer_sequence(request)


def decimal_oracle(
    request: SequenceRequest,
) -> tuple[
    Decimal,
    dict[tuple[int, str], Decimal],
    dict[tuple[int, str, str], Decimal],
    tuple[str, ...],
    Decimal,
]:
    """Direct Decimal enumeration after cost centering, independent of production messages."""
    with localcontext() as context:
        context.prec = 90
        states = [[entry.state for entry in line.costs] for line in request.rows]
        costs = [
            {entry.state.id: Decimal.from_float(entry.cost) for entry in line.costs}
            for line in request.rows
        ]
        edges = [
            {
                (edge.from_state, edge.to_state): Decimal.from_float(edge.probability)
                for edge in step.edges
            }
            for step in request.transitions
        ]
        temperature = Decimal.from_float(request.temperature)
        weighted_paths = []
        for path in product(*states):
            probability = Decimal(1) / Decimal(len(states[0]))
            for index, (before, after) in enumerate(zip(path, path[1:])):
                probability *= edges[index].get((before.id, after.id), Decimal(0))
            if probability:
                weighted_paths.append(
                    (path, sum(costs[i][state.id] for i, state in enumerate(path)), probability)
                )
        minimum_cost = min(cost for _, cost, _ in weighted_paths)
        weights = [
            (path, (-(cost - minimum_cost) / temperature).exp() * probability)
            for path, cost, probability in weighted_paths
        ]
        normalizer = sum(weight for _, weight in weights)
        posterior = {
            (index, state.id): sum(weight for path, weight in weights if path[index] == state)
            / normalizer
            for index, layer in enumerate(states)
            for state in layer
        }
        flow = {
            (index, before, after): sum(
                weight
                for path, weight in weights
                if path[index].id == before and path[index + 1].id == after
            )
            / normalizer
            for index, step in enumerate(edges)
            for before, after in step
            if step[before, after] > 0
        }
        log_weights = [
            -cost / temperature + probability.ln() for _, cost, probability in weighted_paths
        ]
        best_weight = max(log_weights)
        best_path = max(
            (
                path
                for (path, _, _), log_weight in zip(weighted_paths, log_weights)
                if log_weight == best_weight
            ),
            key=lambda path: tuple(scala_key(state) for state in reversed(path)),
        )
        return (
            -minimum_cost / temperature + normalizer.ln(),
            posterior,
            flow,
            tuple(state.id for state in best_path),
            best_weight,
        )


def test_decimal_oracle_retains_huge_traded_costs_with_both_priors():
    s, a, b, c, d = source("s"), source("a"), source("b"), source("c"), source("d")
    height = 1e20

    def request(a_prior: float, b_prior: float) -> SequenceRequest:
        return SequenceRequest(
            rows=(
                row("first", (s, 0)),
                row("middle", (a, height), (b, 0)),
                row("last", (c, 0), (d, height)),
            ),
            transitions=(
                transition("first", "middle", (s, a, a_prior), (s, b, b_prior)),
                transition("middle", "last", (a, c, 1), (b, d, 1)),
            ),
        )

    for candidate, expected in (
        (request(0.75, 0.25), (0.75, 0.25)),
        (request(0.25, 0.75), (0.25, 0.75)),
    ):
        result = infer_sequence(candidate)
        log_z, posterior, flow, best_path, best_weight = decimal_oracle(candidate)
        assert result.log_normalizer == pytest.approx(float(log_z), abs=1e-12, rel=0)
        assert result.best_path_log_weight == pytest.approx(float(best_weight), abs=1e-12, rel=0)
        assert result.best_path == best_path
        for index, line in enumerate(result.posterior):
            assert {mass.state for mass in line.mass} == {
                entry.state.id for entry in candidate.rows[index].costs
            }
            for mass in line.mass:
                assert mass.probability == pytest.approx(
                    float(posterior[index, mass.state]), abs=1e-12, rel=0
                )
        for index, step in enumerate(result.flow):
            assert {(mass.from_state, mass.to_state) for mass in step.mass} == set(
                flow_key[1:] for flow_key in flow if flow_key[0] == index
            )
            for mass in step.mass:
                assert mass.probability == pytest.approx(
                    float(flow[index, mass.from_state, mass.to_state]), abs=1e-12, rel=0
                )
        assert [mass.probability for mass in result.posterior[1].mass] == list(expected)
        assert [mass.probability for mass in result.posterior[2].mass] == list(expected)


def test_common_signed_energy_shifts_preserve_mass_and_change_absolute_weights():
    s, a, b = source("s"), source("a"), source("b")

    def request(shift):
        return SequenceRequest(
            rows=(row("first", (s, shift)), row("last", (a, shift), (b, shift))),
            transitions=(transition("first", "last", (s, a, 0.75), (s, b, 0.25)),),
        )

    baseline = infer_sequence(request(0.0))
    for shift in (1e20, -1e20):
        candidate = request(shift)
        result = infer_sequence(candidate)
        expected = decimal_oracle(candidate)
        assert result.posterior == baseline.posterior
        assert result.flow == baseline.flow
        assert result.best_path == baseline.best_path
        assert result.log_normalizer == float(expected[0]) == -2 * shift
        assert result.best_path_log_weight == float(expected[4])


def test_roundoff_stochastic_rows_are_normalized_but_material_errors_refuse():
    s, a, b = source("s"), source("a"), source("b")
    rows = (row("x", (s, 0)), row("y", (a, 0), (b, 0)))
    p = 0.5 + 2e-13
    result = infer_sequence(
        SequenceRequest(rows=rows, transitions=(transition("x", "y", (s, a, 0.5), (s, b, p)),))
    )
    assert [mass.probability for mass in result.posterior[1].mass] == pytest.approx(
        [0.5 / (0.5 + p), p / (0.5 + p)], rel=0, abs=2e-16
    )
    with pytest.raises(ValueError, match="sum to one"):
        SequenceRequest(rows=rows, transitions=(transition("x", "y", (s, a, 0.5), (s, b, 0.51)),))


def test_viterbi_ties_use_scala_utf16_order_for_valid_unicode_ids():
    bmp, supplementary = source("\ue000"), source("\U00010000")
    # Scala compares UTF-16 units, for which D800 < E000; Python code-point order differs.
    for states in ((bmp, supplementary), (supplementary, bmp)):
        request = SequenceRequest(rows=(row("unicode", *((state, 0) for state in states)),))
        assert infer_sequence(request).best_path == (bmp.id,)


def test_model_copies_cannot_smuggle_boolean_numbers_through_serialization():
    a, b = source("a"), source("b")
    request = SequenceRequest(
        rows=(row("x", (a, 0)), row("y", (b, 0))),
        transitions=(transition("x", "y", (a, b, 1)),),
    )
    result = infer_sequence(request)
    assert verify_sequence(request, result) == result
    assert verify_sequence(request.model_dump_json(), result.model_dump_json()) == result
    invalid_requests = (
        request.model_copy(update={"temperature": True}),
        request.model_copy(
            update={
                "rows": (
                    request.rows[0].model_copy(
                        update={
                            "costs": (request.rows[0].costs[0].model_copy(update={"cost": False}),)
                        }
                    ),
                    request.rows[1],
                )
            }
        ),
        request.model_copy(
            update={
                "transitions": (
                    request.transitions[0].model_copy(
                        update={
                            "edges": (
                                request.transitions[0]
                                .edges[0]
                                .model_copy(update={"probability": True}),
                            )
                        }
                    ),
                )
            }
        ),
    )
    for forged in invalid_requests:
        # Python-mode dumping preserves the forged types, unlike JSON-mode serialization.
        raw = canonical(forged.model_dump(mode="python", warnings=False))
        for value in (forged, raw):
            with pytest.raises(ValueError):
                infer_sequence(value)
    for forged in (
        result.model_copy(update={"log_normalizer": False}),
        result.model_copy(update={"best_path_log_weight": False}),
        result.model_copy(
            update={
                "posterior": (
                    result.posterior[0].model_copy(
                        update={
                            "mass": (
                                result.posterior[0]
                                .mass[0]
                                .model_copy(update={"probability": True}),
                            )
                        }
                    ),
                    result.posterior[1],
                )
            }
        ),
        result.model_copy(
            update={
                "flow": (
                    result.flow[0].model_copy(
                        update={
                            "mass": (
                                result.flow[0].mass[0].model_copy(update={"probability": True}),
                            )
                        }
                    ),
                )
            }
        ),
    ):
        raw = canonical(forged.model_dump(mode="python", warnings=False))
        for value in (forged, raw):
            with pytest.raises(ValueError):
                verify_sequence(request, value)
    with pytest.raises(ValueError):
        infer_sequence(request.model_copy(update={"unexpected_field": 1}))


def test_strict_boundary_rejects_forgery_nonfinite_and_bad_topology():
    a, b = source("a"), source("b")
    request = SequenceRequest(
        rows=(row("x", (a, 0)), row("y", (b, 0))),
        transitions=(transition("x", "y", (a, b, 1)),),
    )
    actual = infer_sequence(request)
    forged = actual.model_copy(update={"log_normalizer": actual.log_normalizer + 0.01})
    with pytest.raises(ValueError, match="differs"):
        verify_sequence(request, forged)
    for forged_request in (
        request.model_copy(update={"temperature": 0.0}),
        request.model_copy(update={"temperature": -1.0}),
        request.model_copy(update={"transitions": (transition("y", "x", (a, b, 1)),)}),
        request.model_copy(update={"rows": (request.rows[0], request.rows[0])}),
        request.model_copy(
            update={
                "rows": (
                    request.rows[0].model_copy(
                        update={"costs": (request.rows[0].costs[0], request.rows[0].costs[0])}
                    ),
                    request.rows[1],
                )
            }
        ),
    ):
        with pytest.raises(ValueError):
            validate_sequence(forged_request)
    for raw in (
        '{"rows":[],"rows":[]}',
        '{"rows":[],"temperature":NaN}',
        '{"rows":[],"temperature":Infinity}',
    ):
        with pytest.raises(ValueError):
            validate_sequence(raw)
    with pytest.raises(ValueError, match="outgoing"):
        SequenceRequest(
            rows=(row("x", (a, 0)), row("y", (b, 0))), transitions=(transition("x", "y"),)
        )
    with pytest.raises(ValueError, match="endpoint"):
        SequenceRequest(
            rows=(row("x", (a, 0)), row("y", (b, 0))),
            transitions=(
                SequenceTransition(
                    from_unit="x",
                    to_unit="y",
                    edges=(TransitionEdge(from_state=a.id, to_state="foreign", probability=1),),
                ),
            ),
        )
    with pytest.raises(ValueError, match="duplicate transition"):
        SequenceRequest(
            rows=(row("x", (a, 0)), row("y", (b, 0))),
            transitions=(transition("x", "y", (a, b, 0.5), (a, b, 0.5)),),
        )
    with pytest.raises(ValueError):
        StateCost(state=a, cost=float("inf"))
