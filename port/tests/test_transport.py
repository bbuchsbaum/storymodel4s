"""Account-to-transport baseline checks, including the independently executed JVM plan."""

import json
import math
from fractions import Fraction
from pathlib import Path

import pytest
from test_local_evidence import rich
from test_recall_clock import build_clock_example

from storymodel.artifacts import read_supported_account
from storymodel.cli import main
from storymodel.local_evidence import RecallSelection, SemanticCell, SemanticEstimate
from storymodel.sinkhorn import SinkhornConfig, solve_transport
from storymodel.source_grid import TransportGrid, TransportGridQuery, project_source_grid
from storymodel.transport import TransportSpec, align_transport

example = pytest.fixture(scope="module")(build_clock_example)
witness = Path(__file__).resolve().parents[1] / "evidence/transport/sinkhorn-jvm.json"


def _baseline():
    oracle = json.loads(witness.read_bytes())["baseline"]
    source = rich("source", "AA BB", (("row", 0, 2, None), ("row", 3, 5, None)), ())
    recall = rich(
        "recall", "AA BB CC DD", tuple(("recall", 3 * i, 3 * i + 2, None) for i in range(4)), ()
    )
    columns = dict(
        zip(
            oracle["union"],
            sorted(source.units, key=lambda u: u.support.spans[0].start),
            strict=True,
        )
    )
    units = sorted(recall.units, key=lambda u: u.support.spans[0].start)
    selections = tuple(
        RecallSelection(unit=u.id, targets=tuple(columns[t].id for t in row["nominated"]))
        for u, row in zip(units, oracle["perUnitNominations"], strict=True)
    )
    table = []
    for u, costs in zip(units, oracle["cost"], strict=True):
        for target, cost in zip(oracle["union"], costs, strict=True):
            if cost == "+Infinity":
                table.append(
                    SemanticCell(
                        unit=u.id,
                        target=columns[target].id,
                        estimate=SemanticEstimate(status="ineligible", reason="different source"),
                    )
                )
    spec = TransportSpec(
        units=selections,
        semantic="table",
        semantic_cells=tuple(table),
        missing_distance=oracle["missingDistance"],
    )
    return source, recall, spec, columns, oracle


def test_account_transport_reproduces_jvm_candidate_union_and_forbidden_edge():
    source, recall, spec, columns, oracle = _baseline()
    result = align_transport(source, recall, spec)
    forbidden = spec.semantic_cells[0].target
    assert dict(result.iter_row(0))[forbidden] == 0
    assert result.status == "converged" and result.measure == "transport-mass"
    assert result.scaling.iterations == oracle["result"]["iterations"]
    assert list(result.row_targets) == oracle["a"] and list(result.column_targets) == oracle["b"]
    assert result.scaling.row_marginals == pytest.approx(
        oracle["result"]["rowMarginals"], abs=1e-12, rel=0
    )
    assert [
        result.scaling.column_marginals[result.columns.index(columns[t].id)]
        for t in oracle["union"]
    ] == pytest.approx(oracle["result"]["colMarginals"], abs=1e-12, rel=0)
    for i, expected in enumerate(oracle["alignPlan"]):
        by_target = dict(result.iter_row(spec.units[i].unit))
        assert [by_target[columns[t].id] for t in oracle["union"]] == pytest.approx(
            expected, rel=0, abs=1e-12
        )
    assert result.costs.default_kind == "imputed" and result.costs.default_distance == 0.5
    # A source nominated only in other units can receive all of this unit's mass.
    assert set(spec.units[0].targets) == {forbidden}
    assert sum(m for t, m in result.iter_row(0) if t != forbidden) > 0.9


def test_lexical_retrieval_contributes_to_a_shared_candidate_union():
    source, recall, spec, columns, _ = _baseline()
    selections = tuple(RecallSelection(unit=u.unit) for u in spec.units)
    plan = align_transport(source, recall, TransportSpec(units=selections))
    assert plan.retrieval[0].status == plan.retrieval[1].status == "ranked"
    assert plan.retrieval[2].status == plan.retrieval[3].status == "empty"
    target_a, target_b = (u.id for u in columns.values())
    assert set(plan.columns) == {target_a, target_b}
    assert plan.costs.default_kind == "observed" and plan.costs.default_distance == 1
    assert plan.costs.rows[0] == {plan.columns.index(target_a): 0.0}
    assert plan.costs.rows[2] == {}
    assert dict(plan.iter_row(0))[target_a] > dict(plan.iter_row(0))[target_b]
    assert all(mass > 0 for _, mass in plan.iter_row(2))


def test_equal_numeric_costs_retain_measurement_and_missingness():
    source, recall, spec, columns, _ = _baseline()
    target = next(iter(columns.values())).id
    extra = SemanticCell(
        unit=spec.units[1].unit,
        target=target,
        estimate=SemanticEstimate(status="observed", value=0.5),
    )
    result = align_transport(
        source, recall, spec.model_copy(update={"semantic_cells": (*spec.semantic_cells, extra)})
    )
    j = result.columns.index(target)
    assert result.costs.rows[1][j] == 0.5 and j not in result.costs.rows[2]
    assert result.scaling is not None
    assert dict(result.iter_row(1))[target] == pytest.approx(dict(result.iter_row(2))[target])
    expensive = align_transport(
        source,
        recall,
        spec.model_copy(
            update={"missing_distance": 0.9, "semantic_cells": (*spec.semantic_cells, extra)}
        ),
    )
    assert dict(expensive.iter_row(1))[target] > dict(expensive.iter_row(2))[target]
    assert dict(expensive.iter_row(0))[spec.semantic_cells[0].target] == 0


def test_transport_empty_union_and_account_bound_refusals():
    source, recall, spec, _, _ = _baseline()
    empty = spec.model_copy(
        update={"units": tuple(u.model_copy(update={"targets": ()}) for u in spec.units)}
    )
    result = align_transport(source, recall, empty)
    assert result.status == "no-candidates" and result.scaling is None and result.columns == ()
    assert [dict(result.iter_row(i)) for i in range(4)] == [{}, {}, {}, {}]
    with pytest.raises(ValueError, match="outside"):
        result.mass_at(0, 0)
    with pytest.raises(ValueError, match="outside"):
        align_transport(
            source,
            recall,
            spec.model_copy(
                update={
                    "units": (RecallSelection(unit=spec.units[0].unit, targets=("foreign",)),),
                    "semantic_cells": (),
                }
            ),
        )
    with pytest.raises(ValueError, match="recall-only"):
        align_transport(source, source, spec)
    with pytest.raises(ValueError, match="duplicate"):
        align_transport(
            source, recall, spec.model_copy(update={"units": (spec.units[0], spec.units[0])})
        )


def test_transport_grid_and_cli_keep_row_budgets_and_achieved_mass(example, tmp_path):
    q = TransportGridQuery.model_validate_json((example / "transport-grid-query.json").read_bytes())
    plan = example / "analysis-1.json"
    grid = project_source_grid(plan, q)
    assert grid == TransportGrid.model_validate_json((example / "transport-grid.json").read_bytes())
    names = [
        str(example / p) for p in ("local-source.json", "local-recall.json", "transport-spec.json")
    ]
    local = align_transport(
        read_supported_account(Path(names[0])), read_supported_account(Path(names[1])), q.transport
    )
    assert local == grid.transport
    (row,) = grid.rows
    (target,) = q.declarations
    achieved = {t: Fraction(m) for t, m in local.iter_row(0)}
    assert row.ledger.row_budget == 1 and row.ledger.supplied == sum(achieved.values()) != 1
    assert row.ledger.external == {}
    assert row.ledger.allocated_source == achieved[target] > 0
    assert row.cells[0].allocated == achieved[target] * Fraction(2, 5)
    assert row.weighted_seconds[0].allocated == achieved[target] * Fraction(3, 10)
    assert row.ledger.allocated_outside == achieved[target] / 5
    receipt = tmp_path / "transport.json"
    assert main(["align-transport", *names, "--output", str(receipt)]) == 0
    assert receipt.read_bytes() == (example / "transport.json").read_bytes()
    assert main(["verify-transport", *names, str(receipt)]) == 0
    raw = json.loads(receipt.read_bytes())
    raw["scaling"]["log_u"][0] += 1
    receipt.write_text(json.dumps(raw))
    assert main(["verify-transport", *names, str(receipt)]) == 2
    args = [str(plan), str(example / "transport-grid-query.json")]
    output = tmp_path / "grid.json"
    assert main(["source-time-grid", *args, "--output", str(output)]) == 0
    assert output.read_bytes() == (example / "transport-grid.json").read_bytes()
    assert main(["verify-source-time-grid", *args, str(output)]) == 0
    assert main(["verify-source-time-grid", *args, str(example / "local-score-grid.json")]) == 2


def test_grid_distinguishes_unfinished_fit_from_missing_plan(example):
    q = TransportGridQuery.model_validate_json((example / "transport-grid-query.json").read_bytes())
    plan = example / "analysis-1.json"
    spec = q.transport
    short = spec.model_copy(update={"config": spec.config.model_copy(update={"max_iterations": 1})})
    fitted = project_source_grid(plan, q.model_copy(update={"transport": short}))
    assert (
        fitted.transport.status == "iteration-limit"
        and fitted.rows[0].status == "transport-iteration-limit"
    )
    assert fitted.rows[0].ledger.supplied > 0
    no_targets = spec.model_copy(
        update={"units": tuple(u.model_copy(update={"targets": ()}) for u in spec.units)}
    )
    empty = project_source_grid(plan, q.model_copy(update={"transport": no_targets}))
    (row,) = empty.rows
    assert (
        row.status == "no-transport-candidates"
        and row.ledger is row.cells is row.weighted_seconds is None
    )
    assert row.duration_seconds == Fraction(3, 4)


@pytest.mark.parametrize("name", ["direct", "zeroPriorZeroPenalty", "positiveColumnOnlyZeroRow"])
def test_solver_against_jvm_and_reduced_support_control(name):
    case = json.loads(witness.read_bytes())[name]
    costs = tuple({j: c for j, c in enumerate(row) if c != "+Infinity"} for row in case["cost"])
    cfg = case["config"]
    result = solve_transport(
        costs,
        tuple(case["a"]),
        tuple(case["b"]),
        SinkhornConfig(
            epsilon=cfg["epsilon"],
            rho_rows=cfg["rhoRows"],
            rho_cols=cfg["rhoCols"],
            tolerance=cfg["tolerance"],
            max_iterations=cfg["maxIterations"],
        ),
    )
    jvm = case["result"]
    if name == "positiveColumnOnlyZeroRow":
        assert not jvm["converged"] and jvm["plan"] == [[0.0, 0.0], [0.0, 0.0]]
        assert result.converged and result.plan[0][1] == pytest.approx(
            math.exp(-0.2 / 2.1), rel=1e-8
        )
        assert result.plan[1][0] == 0.0
        return
    assert result.converged == jvm["converged"] and result.iterations == jvm["iterations"]
    for i, row in enumerate(jvm["plan"]):
        assert [result.plan[i].get(j, 0.0) for j in range(len(row))] == pytest.approx(
            row, rel=1e-12, abs=1e-14
        )
    assert result.row_marginals == pytest.approx(jvm["rowMarginals"])
    assert result.column_marginals == pytest.approx(jvm["colMarginals"])


def test_implicit_plan_agrees_with_stationarity_on_each_admissible_edge():
    from storymodel.sinkhorn import scale_transport

    a, b = (1.0, 0.5, 1.75), (0.25, 1.25, 0.5, 1.5)
    cfg = SinkhornConfig(epsilon=0.4, rho_rows=1.3, rho_cols=0.8, tolerance=1e-12)
    overrides = ({1: 0.1, 3: math.inf}, {}, {0: 0.9, 2: 0.0})
    result = scale_transport(overrides, a, b, cfg, default_cost=0.45)
    assert result.converged
    plan = []
    for i, row in enumerate(overrides):
        masses = []
        for j in range(len(b)):
            cost = row.get(j, 0.45)
            mass = result.mass_at(i, j, cost, cfg.epsilon)
            masses.append(mass)
            if cost == math.inf:
                assert mass == 0.0
            else:
                # Primal first-order condition of the KL-penalized objective.
                residual = (
                    cost
                    + cfg.epsilon * math.log(mass)
                    + cfg.rho_rows * math.log(result.row_marginals[i] / a[i])
                    + cfg.rho_cols * math.log(result.column_marginals[j] / b[j])
                )
                assert abs(residual) < 1e-10
        plan.append(masses)
    assert result.row_marginals == pytest.approx([sum(row) for row in plan], abs=1e-13)
    assert result.column_marginals == pytest.approx(
        [sum(col) for col in zip(*plan, strict=True)], abs=1e-13
    )


@pytest.mark.parametrize("side", ["local-source.json", "local-recall.json"])
def test_transport_cli_checks_both_evidence_export_grants(example, tmp_path, side):
    raw = json.loads((example / side).read_bytes())
    raw["export_permission"] = "denied"
    denied = tmp_path / side
    denied.write_text(json.dumps(raw))
    files = [
        str(denied if side == name else example / name)
        for name in ("local-source.json", "local-recall.json", "transport-spec.json")
    ]
    result = tmp_path / "must-not-exist.json"
    assert main(["align-transport", *files, "--output", str(result)]) == 2
    assert not result.exists()
