"""Independent witnesses for sparse unbalanced transport arithmetic."""

from __future__ import annotations

import math

import pytest

from storymodel.sinkhorn import SinkhornConfig, scale_transport, solve_transport


def test_one_edge_matches_the_closed_form_unbalanced_solution():
    config = SinkhornConfig(epsilon=0.5, rho_rows=2.0, rho_cols=3.0, tolerance=1e-12)
    result = solve_transport(({0: 1.25},), (4.0,), (9.0,), config)
    expected = math.exp((2.0 * math.log(4.0) + 3.0 * math.log(9.0) - 1.25) / 5.5)
    assert result.converged
    assert result.plan == ({0: pytest.approx(expected)},)
    assert result.row_marginals == pytest.approx((expected,))
    assert result.column_marginals == pytest.approx((expected,))


def test_equal_huge_penalties_do_not_overflow_the_dual_ratio():
    huge = 1e308
    result = solve_transport(
        ({0: 0.0},),
        (4.0,),
        (9.0,),
        SinkhornConfig(epsilon=huge, rho_rows=huge, rho_cols=huge, tolerance=1e-12),
    )
    assert result.plan[0][0] == pytest.approx(36.0 ** (1.0 / 3.0))


def test_underflowed_positive_penalty_still_hard_zeros_a_zero_prior():
    result = solve_transport(
        ({0: 0.0},),
        (0.0,),
        (1.0,),
        SinkhornConfig(epsilon=1e308, rho_rows=float.fromhex("0x0.0000000000001p-1022")),
    )
    assert result.plan == ({0: 0.0},)


def test_unrepresentable_huge_dual_cancellation_is_refused_not_converged():
    with pytest.raises(ValueError, match="dual reconstruction"):
        solve_transport(
            [{0: 1e16}],
            [1],
            [1],
            SinkhornConfig(epsilon=0.1, rho_rows=1e16, rho_cols=1e16),
        )


def test_implicit_default_uses_the_same_unrepresentable_reconstruction_guard():
    with pytest.raises(ValueError, match="dual reconstruction"):
        scale_transport(
            ({},),
            (1.0,),
            (1.0,),
            SinkhornConfig(epsilon=0.1, rho_rows=1e16, rho_cols=1e16),
            default_cost=1e16,
        )


def test_representable_large_penalty_has_the_one_edge_positive_control():
    result = solve_transport(
        ({0: 1.0},),
        (1.0,),
        (1.0,),
        SinkhornConfig(epsilon=0.1, rho_rows=1.0, rho_cols=1.0),
    )
    assert result.plan[0][0] == pytest.approx(math.exp(-1.0 / 2.1))


def test_stagnant_dual_delta_does_not_claim_stationarity():
    result = solve_transport(
        ({0: 1e4},),
        (1.0,),
        (1.0,),
        SinkhornConfig(epsilon=0.1, rho_rows=1e6, rho_cols=1e6),
    )
    assert not result.converged
    assert result.iterations == 200


def test_sparse_plan_never_invents_absent_or_explicitly_forbidden_edges():
    result = solve_transport(({0: 0.0, 1: math.inf}, {1: 0.0}), (1.0, 1.0), (1.0, 1.0))
    assert tuple(result.plan[0]) == (0,)
    assert tuple(result.plan[1]) == (1,)
    assert result.plan[0][0] > 0.0
    assert result.plan[1][1] > 0.0


def test_list_and_integer_numeric_inputs_normalize_at_the_public_boundary():
    result = solve_transport([{0: 0}], [1], [1])
    assert result.plan == ({0: 1.0},)


def test_positive_target_with_no_admissible_partner_is_refused():
    with pytest.raises(ValueError, match="row 0.*positive mass"):
        solve_transport(({0: math.inf},), (1.0,), (0.0,))
    with pytest.raises(ValueError, match="column 0.*positive mass"):
        solve_transport(({},), (0.0,), (1.0,))


def test_finite_default_does_not_restore_all_forbidden_row_or_column():
    with pytest.raises(ValueError, match="row 0.*positive mass"):
        scale_transport(({0: math.inf},), (1.0,), (0.0,), default_cost=0.0)
    with pytest.raises(ValueError, match="column 0.*positive mass"):
        scale_transport(({0: math.inf},), (0.0,), (1.0,), default_cost=0.0)


def test_hard_zero_prior_is_distinct_from_a_zero_penalty():
    hard_zero = solve_transport(({0: 0.0},), (0.0,), (1.0,))
    assert hard_zero.plan == ({0: 0.0},)
    assert hard_zero.row_marginals == (0.0,)

    unconstrained = solve_transport(
        ({0: 0.0},),
        (0.0,),
        (1.0,),
        SinkhornConfig(rho_rows=0.0),
    )
    assert unconstrained.plan[0][0] > 0.0
    assert unconstrained.row_marginals[0] > 0.0


def test_opposite_hard_zero_support_stays_explicitly_zero_without_nan():
    result = solve_transport(({0: 0.0},), (1.0,), (0.0,))
    assert result.plan == ({0: 0.0},)
    assert result.row_marginals == (0.0,)
    assert result.column_marginals == (0.0,)
    assert all(math.isfinite(value) for value in result.plan[0].values())


def test_reduced_support_keeps_the_viable_component_of_the_jvm_nan_fixture():
    """A hard-zero row removes only its incident support, rather than its component's neighbor."""
    config = SinkhornConfig(epsilon=0.1, rho_rows=1.0, rho_cols=1.0, tolerance=1e-12)
    result = solve_transport(
        ({0: math.inf, 1: 0.2}, {0: 0.2, 1: math.inf}),
        (1.0, 0.0),
        (1.0, 1.0),
        config,
    )
    expected = math.exp(-0.2 / 2.1)
    assert result.converged
    assert result.plan[0][1] == pytest.approx(expected)
    assert result.plan[1][0] == 0.0
    assert result.row_marginals == pytest.approx((expected, 0.0))
    assert result.column_marginals == pytest.approx((0.0, expected))


def test_default_cost_scales_implicit_dense_support_without_a_dense_plan():
    scaling = scale_transport(({}, {}), (1.0, 1.0), (1.0, 1.0), default_cost=0.0)
    cells = tuple(scaling.mass_at(i, j, 0.0, 0.1) for i in range(2) for j in range(2))
    assert cells == pytest.approx((cells[0],) * 4)
    assert tuple(sum(cells[2 * i : 2 * i + 2]) for i in range(2)) == pytest.approx(
        scaling.row_marginals
    )
    assert (
        cells[0] + cells[2],
        cells[1] + cells[3],
    ) == pytest.approx(scaling.column_marginals)


@pytest.mark.parametrize(
    ("costs", "rows", "columns"),
    [
        (({True: 0.0},), (1.0,), (1.0,)),
        (({1: 0.0},), (1.0,), (1.0,)),
        (({0: math.nan},), (1.0,), (1.0,)),
        (({0: -math.inf},), (1.0,), (1.0,)),
        (({0: 0.0},), (True,), (1.0,)),
    ],
)
def test_dimensions_booleans_and_nonfinite_input_are_refused(costs, rows, columns):
    with pytest.raises(ValueError):
        solve_transport(costs, rows, columns)


def test_forged_model_copy_and_unresolved_extreme_are_refused():
    forged = SinkhornConfig().model_copy(update={"max_iterations": True})
    with pytest.raises(ValueError, match="max_iterations"):
        solve_transport(({0: 0.0},), (1.0,), (1.0,), forged)
    with pytest.raises(ValueError, match="numerically unresolved"):
        solve_transport(({0: -1e308},), (1.0,), (1.0,))
