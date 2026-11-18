"""Sparse, log-domain unbalanced transport with explicit forbidden support.

The solver is deliberately a small numerical primitive: callers own candidate
generation and decide whether absent cells mean forbidden or a background cost.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Sequence
from typing import Self, cast

from pydantic import Field, model_validator

from ._base import Model, _validate_instance

_LOG_MAX = math.log(float.fromhex("0x1.fffffffffffffp+1023"))


class SinkhornConfig(Model):
    """Numerical regularization and stopping limits for one transport solve."""

    epsilon: float = 0.1
    rho_rows: float = 1.0
    rho_cols: float = 1.0
    max_iterations: int = Field(default=200, gt=0)
    tolerance: float = 1e-6

    @model_validator(mode="after")
    def valid(self) -> Self:
        values = {
            "epsilon": self.epsilon,
            "rho_rows": self.rho_rows,
            "rho_cols": self.rho_cols,
            "tolerance": self.tolerance,
        }
        if any(type(value) is not float or not math.isfinite(value) for value in values.values()):
            raise ValueError("SinkhornConfig scales must be finite floats")
        if self.epsilon <= 0.0 or self.tolerance <= 0.0:
            raise ValueError("epsilon and tolerance must be positive")
        if self.rho_rows < 0.0 or self.rho_cols < 0.0:
            raise ValueError("marginal penalties must be nonnegative")
        if type(self.max_iterations) is not int or self.max_iterations <= 0:
            raise ValueError("max_iterations must be a positive integer")
        return self


class TransportScaling(Model):
    """Finite dual factors, with ``None`` standing for a zero-mass log factor."""

    log_u: tuple[float | None, ...]
    log_v: tuple[float | None, ...]
    row_marginals: tuple[float, ...]
    column_marginals: tuple[float, ...]
    iterations: int
    converged: bool

    @model_validator(mode="after")
    def finite(self) -> Self:
        if len(self.row_marginals) != len(self.log_u):
            raise ValueError("row marginals must match log_u")
        if len(self.column_marginals) != len(self.log_v):
            raise ValueError("column marginals must match log_v")
        values = (*self.log_u, *self.log_v, *self.row_marginals, *self.column_marginals)
        if any(value is not None and not math.isfinite(value) for value in values):
            raise ValueError("transport scaling cannot serialize non-finite values")
        if any(value < 0.0 for value in (*self.row_marginals, *self.column_marginals)):
            raise ValueError("transport marginals must be nonnegative")
        if type(self.iterations) is not int or self.iterations < 0:
            raise ValueError("iterations must be a nonnegative integer")
        if type(self.converged) is not bool:
            raise ValueError("converged must be boolean")
        return self

    def mass_at(self, i: int, j: int, cost: float, epsilon: float) -> float:
        """Reconstruct one allowed cell, refusing overflow rather than concealing it."""
        if (
            type(i) is not int
            or type(j) is not int
            or not (0 <= i < len(self.log_u))
            or not (0 <= j < len(self.log_v))
        ):
            raise ValueError("transport cell index is out of range")
        _finite_positive_float(epsilon, "epsilon")
        _cost(cost, f"cost({i},{j})")
        left, right = self.log_u[i], self.log_v[j]
        if left is None or right is None or cost == math.inf:
            return 0.0
        log_mass = _cell_log(left, right, cost, epsilon)
        if log_mass is None:
            return 0.0
        return _exp_finite(log_mass, f"transport cell ({i},{j})")


class SinkhornResult(Model):
    """Sparse transport masses and the marginals they induce."""

    plan: tuple[dict[int, float], ...]
    row_marginals: tuple[float, ...]
    column_marginals: tuple[float, ...]
    iterations: int
    converged: bool

    @model_validator(mode="after")
    def finite(self) -> Self:
        if len(self.plan) != len(self.row_marginals):
            raise ValueError("plan rows must match row marginals")
        if any(
            type(key) is not int or key < 0 or key >= len(self.column_marginals)
            for row in self.plan
            for key in row
        ):
            raise ValueError("plan contains an invalid column index")
        values = (
            *(value for row in self.plan for value in row.values()),
            *self.row_marginals,
            *self.column_marginals,
        )
        if any(
            type(value) is not float or not math.isfinite(value) or value < 0.0 for value in values
        ):
            raise ValueError("transport result must contain finite nonnegative floats")
        if (
            type(self.iterations) is not int
            or self.iterations < 0
            or type(self.converged) is not bool
        ):
            raise ValueError("invalid transport termination record")
        return self


def solve_transport(
    costs: Sequence[dict[int, float | int]],
    row_targets: Sequence[float | int],
    column_targets: Sequence[float | int],
    config: SinkhornConfig | None = None,
) -> SinkhornResult:
    """Solve transport over exactly the finite sparse cells supplied in ``costs``."""
    checked_costs, checked_rows, checked_columns = _validate_inputs(
        costs, row_targets, column_targets, default_cost=None
    )
    checked_config = _checked_config(config)
    scaling = _solve(checked_costs, checked_rows, checked_columns, checked_config, None)
    plan = tuple(
        {
            j: scaling.mass_at(i, j, cost, checked_config.epsilon)
            for j, cost in row.items()
            if cost != math.inf
        }
        for i, row in enumerate(checked_costs)
    )
    return SinkhornResult(
        plan=plan,
        row_marginals=scaling.row_marginals,
        column_marginals=scaling.column_marginals,
        iterations=scaling.iterations,
        converged=scaling.converged,
    )


def scale_transport(
    costs: Sequence[dict[int, float | int]],
    row_targets: Sequence[float | int],
    column_targets: Sequence[float | int],
    config: SinkhornConfig | None = None,
    *,
    default_cost: float | None = None,
) -> TransportScaling:
    """Return dual scaling for sparse overrides of an optional finite background cost."""
    checked_costs, checked_rows, checked_columns = _validate_inputs(
        costs, row_targets, column_targets, default_cost=default_cost
    )
    checked_config = _checked_config(config)
    return _solve(checked_costs, checked_rows, checked_columns, checked_config, default_cost)


def _checked_config(config: SinkhornConfig | None) -> SinkhornConfig:
    return SinkhornConfig() if config is None else _validate_instance(SinkhornConfig, config)


def _finite_positive_float(value: object, label: str) -> float:
    if type(value) is not float or not math.isfinite(value) or value <= 0.0:
        raise ValueError(f"{label} must be a finite positive float")
    return value


def _number(value: object, label: str) -> float:
    if type(value) not in (int, float):
        raise ValueError(f"{label} must be numeric")
    try:
        return float(cast(int | float, value))
    except OverflowError as error:
        raise ValueError(f"{label} must be finite") from error


def _cost(value: object, label: str) -> float:
    checked = _number(value, label)
    if math.isnan(checked) or checked == -math.inf:
        raise ValueError(f"{label} must be finite or +inf")
    return checked


def _targets(values: Sequence[float | int], label: str) -> tuple[float, ...]:
    checked_values: list[float] = []
    for index, value in enumerate(values):
        checked = _number(value, f"{label}[{index}]")
        if not math.isfinite(checked) or checked < 0.0:
            raise ValueError(f"{label}[{index}] must be a finite nonnegative float")
        checked_values.append(checked)
    return tuple(checked_values)


def _validate_inputs(
    costs: Sequence[dict[int, float | int]],
    row_targets: Sequence[float | int],
    column_targets: Sequence[float | int],
    *,
    default_cost: float | None,
) -> tuple[tuple[dict[int, float], ...], tuple[float, ...], tuple[float, ...]]:
    if (
        isinstance(costs, (str, bytes))
        or isinstance(row_targets, (str, bytes))
        or isinstance(column_targets, (str, bytes))
    ):
        raise ValueError("costs and targets must be sequences")
    if len(costs) != len(row_targets):
        raise ValueError("cost rows must match row targets")
    if default_cost is not None:
        _cost(default_cost, "default_cost")
        if default_cost == math.inf:
            raise ValueError("default_cost must be finite when supplied")
    checked_rows = _targets(row_targets, "row_targets")
    checked_columns = _targets(column_targets, "column_targets")
    n = len(checked_columns)
    checked: list[dict[int, float]] = []
    for i, row in enumerate(costs):
        if type(row) is not dict:
            raise ValueError(f"cost row {i} must be a dict")
        checked_row: dict[int, float] = {}
        for j, value in row.items():
            if type(j) is not int or not 0 <= j < n:
                raise ValueError(f"cost column index ({i},{j!r}) is out of range")
            checked_row[j] = _cost(value, f"cost({i},{j})")
        checked.append(checked_row)
    return tuple(checked), checked_rows, checked_columns


def _exp_finite(log_value: float, label: str) -> float:
    if not math.isfinite(log_value) or log_value > _LOG_MAX:
        raise ValueError(f"{label} is numerically unresolved")
    value = math.exp(log_value)
    if not math.isfinite(value):
        raise ValueError(f"{label} is numerically unresolved")
    return value


def _logsumexp(values: Iterable[float]) -> float | None:
    values = tuple(values)
    if not values:
        return None
    maximum = max(values)
    if maximum == -math.inf:
        return None
    if not math.isfinite(maximum):
        raise ValueError("transport scaling is numerically unresolved")
    total = math.fsum(math.exp(value - maximum) for value in values)
    if not math.isfinite(total) or total <= 0.0:
        raise ValueError("transport scaling is numerically unresolved")
    return maximum + math.log(total)


def _effective_cost(row: dict[int, float], j: int, default_cost: float | None) -> float:
    return row[j] if j in row else math.inf if default_cost is None else default_cost


def _scaled_cost(cost: float, epsilon: float) -> float:
    if cost == math.inf:
        return math.inf
    value = cost / epsilon
    if not math.isfinite(value):
        raise ValueError("finite transport edge is numerically unresolved")
    return value


def _cell_log(left: float | None, right: float | None, cost: float, epsilon: float) -> float | None:
    if left is None or right is None or cost == math.inf:
        return None
    scaled = _scaled_cost(cost, epsilon)
    if math.ulp(abs(left) + abs(right) + abs(scaled)) > 1e-6:
        raise ValueError("transport dual reconstruction is numerically unresolved")
    value = left + right - scaled
    if not math.isfinite(value):
        raise ValueError("transport scaling is numerically unresolved")
    return value


def _penalty_factor(rho: float, epsilon: float) -> float:
    """Compute rho / (rho + epsilon) without overflowing the denominator."""
    if rho == 0.0:
        return 0.0
    if rho <= epsilon:
        ratio = rho / epsilon
        return ratio / (1.0 + ratio)
    return 1.0 / (1.0 + epsilon / rho)


def _default_marginal_log(
    own: float | None, common: float | None, default_cost: float, epsilon: float
) -> float | None:
    if own is None or common is None:
        return None
    return _cell_log(own, common, default_cost, epsilon)


def _solve(
    costs: tuple[dict[int, float], ...],
    row_targets: tuple[float, ...],
    column_targets: tuple[float, ...],
    config: SinkhornConfig,
    default_cost: float | None,
) -> TransportScaling:
    m, n = len(row_targets), len(column_targets)
    row_neighbors_list: list[list[int]] = [[] for _ in range(m)]
    col_neighbors_list: list[list[int]] = [[] for _ in range(n)]
    row_override_counts = [0] * m
    col_override_counts = [0] * n
    overridden_columns = [False] * n
    for i, row in enumerate(costs):
        for j, cost in row.items():
            row_override_counts[i] += 1
            col_override_counts[j] += 1
            overridden_columns[j] = True
            if cost != math.inf:
                row_neighbors_list[i].append(j)
                col_neighbors_list[j].append(i)
    row_neighbors = tuple(tuple(partners) for partners in row_neighbors_list)
    col_neighbors = tuple(tuple(partners) for partners in col_neighbors_list)
    default_columns = tuple(not overridden for overridden in overridden_columns)
    # Positive mass with no declared partner is a caller error, even if its KL
    # penalty is zero: it would otherwise look identical to a deliberate zero.
    for i, target in enumerate(row_targets):
        no_partner = (
            not n
            or (default_cost is None and not row_neighbors[i])
            or (default_cost is not None and row_override_counts[i] == n and not row_neighbors[i])
        )
        if target > 0.0 and no_partner:
            raise ValueError(f"row {i} has positive mass but no admissible partner")
    for j, target in enumerate(column_targets):
        no_partner = (
            not m
            or (default_cost is None and not col_neighbors[j])
            or (default_cost is not None and col_override_counts[j] == m and not col_neighbors[j])
        )
        if target > 0.0 and no_partner:
            raise ValueError(f"column {j} has positive mass but no admissible partner")

    log_a = tuple(math.log(value) if value > 0.0 else None for value in row_targets)
    log_b = tuple(math.log(value) if value > 0.0 else None for value in column_targets)
    row_factor = _penalty_factor(config.rho_rows, config.epsilon)
    col_factor = _penalty_factor(config.rho_cols, config.epsilon)
    contraction_gap = 1.0 - row_factor * col_factor
    log_u: tuple[float | None, ...] = (0.0,) * m
    log_v: tuple[float | None, ...] = (0.0,) * n
    iterations, converged = 0, False

    for iteration in range(1, config.max_iterations + 1):
        common_v = _logsumexp(value for value in log_v if value is not None)
        new_u: list[float | None] = []
        for i, row in enumerate(costs):
            if config.rho_rows == 0.0:
                new_u.append(0.0)
                continue
            log_target = log_a[i]
            if log_target is None:
                new_u.append(None)
                continue
            if default_cost is not None and not row:
                common = common_v
                log_sum = (
                    None if common is None else common - _scaled_cost(default_cost, config.epsilon)
                )
            else:
                partners = row_neighbors[i] if default_cost is None else range(n)
                log_sum = _logsumexp(
                    value
                    for j in partners
                    if (
                        value := _cell_log(
                            0.0,
                            log_v[j],
                            _effective_cost(row, j, default_cost),
                            config.epsilon,
                        )
                    )
                    is not None
                )
            # Every remaining partner is hard-zero.  The KL objective permits
            # this row to send no mass; represent that directly, never as NaN.
            new_u.append(None if log_sum is None else row_factor * (log_target - log_sum))
        new_v: list[float | None] = []
        common_u = _logsumexp(value for value in new_u if value is not None)
        for j in range(n):
            if config.rho_cols == 0.0:
                new_v.append(0.0)
                continue
            log_target = log_b[j]
            if log_target is None:
                new_v.append(None)
                continue
            if default_cost is not None and default_columns[j]:
                common = common_u
                log_sum = (
                    None if common is None else common - _scaled_cost(default_cost, config.epsilon)
                )
            else:
                partners = col_neighbors[j] if default_cost is None else range(m)
                log_sum = _logsumexp(
                    value
                    for i in partners
                    if (
                        value := _cell_log(
                            new_u[i],
                            0.0,
                            _effective_cost(costs[i], j, default_cost),
                            config.epsilon,
                        )
                    )
                    is not None
                )
            new_v.append(None if log_sum is None else col_factor * (log_target - log_sum))
        delta = max(
            (_delta(new, old) for new, old in zip((*new_u, *new_v), (*log_u, *log_v))),
            default=0.0,
        )
        log_u, log_v = tuple(new_u), tuple(new_v)
        iterations = iteration
        # A tiny fixed-point step is not enough when the alternating map is
        # nearly non-contractive: its remaining error can be delta/(1-q).
        threshold = (
            config.tolerance
            if contraction_gap >= config.tolerance
            else config.tolerance * contraction_gap
        )
        if contraction_gap > 0.0 and delta < threshold:
            converged = True
            break

    common_v = _logsumexp(value for value in log_v if value is not None)
    common_u = _logsumexp(value for value in log_u if value is not None)
    row_logs = tuple(
        None
        if log_u[i] is None
        else (
            _default_marginal_log(log_u[i], common_v, default_cost, config.epsilon)
            if default_cost is not None and not row
            else _logsumexp(
                value
                for j in (row_neighbors[i] if default_cost is None else range(n))
                if (
                    value := _cell_log(
                        log_u[i],
                        log_v[j],
                        _effective_cost(row, j, default_cost),
                        config.epsilon,
                    )
                )
                is not None
            )
        )
        for i, row in enumerate(costs)
    )
    column_logs = tuple(
        None
        if log_v[j] is None
        else (
            _default_marginal_log(log_v[j], common_u, default_cost, config.epsilon)
            if default_cost is not None and default_columns[j]
            else _logsumexp(
                value
                for i in (col_neighbors[j] if default_cost is None else range(m))
                if (
                    value := _cell_log(
                        log_u[i],
                        log_v[j],
                        _effective_cost(costs[i], j, default_cost),
                        config.epsilon,
                    )
                )
                is not None
            )
        )
        for j in range(n)
    )
    stationary = _stationary(
        row_logs, log_a, log_u, config.rho_rows, config.epsilon, config.tolerance
    ) and _stationary(column_logs, log_b, log_v, config.rho_cols, config.epsilon, config.tolerance)
    if converged and not stationary:
        raise ValueError("transport stationarity is numerically unresolved")
    return TransportScaling(
        log_u=log_u,
        log_v=log_v,
        row_marginals=tuple(
            0.0 if value is None else _exp_finite(value, "row marginal") for value in row_logs
        ),
        column_marginals=tuple(
            0.0 if value is None else _exp_finite(value, "column marginal") for value in column_logs
        ),
        iterations=iterations,
        converged=converged,
    )


def _stationary(
    marginal_logs: tuple[float | None, ...],
    target_logs: tuple[float | None, ...],
    potentials: tuple[float | None, ...],
    rho: float,
    epsilon: float,
    tolerance: float,
) -> bool:
    if rho == 0.0:
        return True
    ratio = epsilon / rho
    for marginal, target, potential in zip(marginal_logs, target_logs, potentials):
        if target is None:
            continue
        if marginal is None or potential is None:
            # Reduced support has a lawful zero marginal only when all its
            # partners are hard-zero; it is not a finite stationarity witness.
            continue
        if potential == 0.0:
            correction = 0.0
        else:
            correction = potential * ratio
        if not math.isfinite(correction) or abs(marginal - target + correction) > tolerance:
            return False
    return True


def _delta(new: float | None, old: float | None) -> float:
    if new is None and old is None:
        return 0.0
    if new is None or old is None:
        return math.inf
    return abs(new - old)
