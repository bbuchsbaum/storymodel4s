"""Sparse sequence inference conditional on explicitly supplied states, costs and transitions.

This numerical boundary does not establish account membership, admissibility, calibrated
confidence or fidelity. Those belong to the evidence producers and scientific consumers.
"""

from __future__ import annotations

import math
from decimal import Decimal, localcontext
from typing import Annotated, Literal, Self

from pydantic import Field, field_validator, model_validator

from .model import Digest, Model, Name
from .serialization import canonical, digest, strict_json

Finite = Annotated[float, Field(allow_inf_nan=False)]
Probability = Annotated[float, Field(ge=0, le=1, allow_inf_nan=False)]
Facet = Literal[
    "actor",
    "action",
    "object",
    "location",
    "outcome",
    "cause",
    "context",
    "role-reversal",
    "polarity",
    "modality",
]
External = Literal[
    "association",
    "commentary",
    "source-consistent-inference",
    "intrusion",
    "uninterpretable",
    "unranked",
]
_FACETS = (
    "actor",
    "action",
    "object",
    "location",
    "outcome",
    "cause",
    "context",
    "role-reversal",
    "polarity",
    "modality",
)


def _scala_name(value: str) -> str:
    return "".join(part.title() for part in value.split("-"))


class SequenceState(Model):
    """A declared anchored mode or explicit external destination, not an inferred claim."""

    kind: Literal["source", "distorted", "external"]
    anchor_kind: Literal["situation", "segment"] | None = None
    anchor: Name | None = None
    facets: tuple[Facet, ...] = ()
    external: External | None = None

    @field_validator("facets")
    @classmethod
    def canonical_facets(cls, value: tuple[Facet, ...]) -> tuple[Facet, ...]:
        if len(value) != len(set(value)):
            raise ValueError("duplicate distortion facet")
        return tuple(sorted(value, key=_FACETS.index))

    @model_validator(mode="after")
    def mode_shape(self) -> Self:
        if self.kind == "external":
            if self.external is None or self.anchor is not None or self.anchor_kind or self.facets:
                raise ValueError("external state requires only an external destination")
        elif self.anchor is None or self.anchor_kind is None or self.external is not None:
            raise ValueError("anchored state requires an anchor and no external destination")
        elif (self.kind == "distorted") != bool(self.facets):
            raise ValueError("only distorted states require nonempty facets")
        return self

    @property
    def id(self) -> str:
        """Content identity distinguishes modes even when human-readable keys collide."""
        return "sequence-state:" + digest(self.model_dump(mode="json"))

    @property
    def sort_key(self) -> tuple[bytes, str]:
        """Scala's rendered key order, with content identity breaking ambiguous encodings."""
        if self.external is not None:
            key = "ext:" + _scala_name(self.external)
        else:
            key = f"{self.anchor_kind}/{self.anchor}"
            if self.facets:
                key += "/distorted/" + ",".join(_scala_name(f) for f in self.facets)
        return key.encode("utf-16-be"), self.id


class StateCost(Model):
    """Finite energy for a declared state; negative energies are permitted explicitly."""

    state: SequenceState
    cost: Finite


class SequenceRow(Model):
    """One ordered unit's complete declared state population."""

    unit: Name
    costs: Annotated[tuple[StateCost, ...], Field(min_length=1)]

    @model_validator(mode="after")
    def unique_states(self) -> Self:
        ids = [entry.state.id for entry in self.costs]
        if len(ids) != len(set(ids)):
            raise ValueError("duplicate state in sequence row")
        if len(ids) > 1 and any(entry.state.external == "unranked" for entry in self.costs):
            raise ValueError("unranked must be the sole state in its unit")
        return self


class TransitionEdge(Model):
    """A directed edge between adjacent layers; absent and zero edges are impossible."""

    from_state: Name
    to_state: Name
    probability: Probability


class SequenceTransition(Model):
    """Sparse stochastic edges between two named adjacent units."""

    from_unit: Name
    to_unit: Name
    edges: tuple[TransitionEdge, ...]


class SequenceRequest(Model):
    """Checked numerical inputs, independently of a provider or account representation."""

    schema_version: Literal["storymodel.sequence-request/v1"] = "storymodel.sequence-request/v1"
    rows: Annotated[tuple[SequenceRow, ...], Field(min_length=1)]
    transitions: tuple[SequenceTransition, ...] = ()
    temperature: Annotated[float, Field(gt=0, allow_inf_nan=False)] = 1.0
    export_permission: Literal["allowed", "denied"] = "denied"

    @model_validator(mode="after")
    def topology(self) -> Self:
        if len({row.unit for row in self.rows}) != len(self.rows):
            raise ValueError("duplicate sequence unit")
        if len(self.transitions) != len(self.rows) - 1:
            raise ValueError("exactly one transition step per adjacent unit pair is required")
        for left, right, step in zip(self.rows, self.rows[1:], self.transitions):
            if (step.from_unit, step.to_unit) != (left.unit, right.unit):
                raise ValueError("transition units differ from ordered adjacency")
            origins: dict[str, list[float]] = {entry.state.id: [] for entry in left.costs}
            destinations = {entry.state.id for entry in right.costs}
            seen: set[tuple[str, str]] = set()
            for edge in step.edges:
                pair = edge.from_state, edge.to_state
                if pair in seen:
                    raise ValueError("duplicate transition edge")
                seen.add(pair)
                if edge.from_state not in origins or edge.to_state not in destinations:
                    raise ValueError("transition endpoint is outside its adjacent layer")
                origins[edge.from_state].append(edge.probability)
            for probabilities in origins.values():
                if not math.isclose(math.fsum(probabilities), 1.0, rel_tol=0, abs_tol=1e-12):
                    raise ValueError("every outgoing transition row must sum to one")
        return self


class StateMass(Model):
    """Model-conditional posterior mass, never an empirical calibration claim."""

    state: Name
    probability: Probability


class PosteriorRow(Model):
    """Posterior over all declared states of one unit, including zero mass."""

    unit: Name
    mass: Annotated[tuple[StateMass, ...], Field(min_length=1)]


class FlowMass(Model):
    """Posterior mass for one explicitly possible adjacent transition."""

    from_state: Name
    to_state: Name
    probability: Probability


class FlowStep(Model):
    """Sparse pairwise posterior whose marginals match its neighboring rows."""

    from_unit: Name
    to_unit: Name
    mass: tuple[FlowMass, ...]


class SequenceResult(Model):
    """Numerical outputs bound to exact inputs; use verify_sequence on external results."""

    schema_version: Literal["storymodel.sequence-result/v1"] = "storymodel.sequence-result/v1"
    method: Literal["sparse-forward-backward/v1"] = "sparse-forward-backward/v1"
    request_sha256: Digest
    posterior: Annotated[tuple[PosteriorRow, ...], Field(min_length=1)]
    flow: tuple[FlowStep, ...]
    best_path: tuple[Name, ...]
    log_normalizer: Finite
    best_path_log_weight: Finite

    @model_validator(mode="after")
    def probability_structure(self) -> Self:
        if len({row.unit for row in self.posterior}) != len(self.posterior):
            raise ValueError("duplicate posterior unit")
        if len(self.best_path) != len(self.posterior) or len(self.flow) != len(self.posterior) - 1:
            raise ValueError("path or flow length differs from posterior")
        for row, chosen in zip(self.posterior, self.best_path):
            ids = [entry.state for entry in row.mass]
            if len(ids) != len(set(ids)) or chosen not in ids:
                raise ValueError("posterior states or best path are inconsistent")
            if not math.isclose(
                math.fsum(m.probability for m in row.mass), 1, abs_tol=1e-10, rel_tol=0
            ):
                raise ValueError("posterior row is not normalized")
        for left, right, step in zip(self.posterior, self.posterior[1:], self.flow):
            if (left.unit, right.unit) != (step.from_unit, step.to_unit):
                raise ValueError("flow units differ from posterior adjacency")
            outgoing: dict[str, list[float]] = {m.state: [] for m in left.mass}
            incoming: dict[str, list[float]] = {m.state: [] for m in right.mass}
            seen: set[tuple[str, str]] = set()
            for mass in step.mass:
                pair = mass.from_state, mass.to_state
                if pair in seen or mass.from_state not in outgoing or mass.to_state not in incoming:
                    raise ValueError("flow has duplicate or foreign endpoints")
                seen.add(pair)
                outgoing[mass.from_state].append(mass.probability)
                incoming[mass.to_state].append(mass.probability)
            for row, grouped in ((left, outgoing), (right, incoming)):
                for state_mass in row.mass:
                    if not math.isclose(
                        math.fsum(grouped[state_mass.state]),
                        state_mass.probability,
                        abs_tol=1e-10,
                        rel_tol=0,
                    ):
                        raise ValueError("flow marginal differs from posterior")
        if self.best_path_log_weight > self.log_normalizer + 1e-10:
            raise ValueError("best path weight exceeds the sequence normalizer")
        return self


def validate_sequence(value: SequenceRequest | str | bytes) -> SequenceRequest:
    """Revalidate all input relationships, including unchecked model_copy changes."""
    if isinstance(value, SequenceRequest):
        # Serialization can coerce forged bools to floats and discard unknown fields.
        # Revalidate Python values before any serialization (including nested copies).
        return SequenceRequest.model_validate(value)
    return SequenceRequest.model_validate_json(canonical(strict_json(value)))


_NEG_INF = Decimal("-Infinity")
_ZERO = Decimal(0)


def _logsumexp(values: list[Decimal]) -> Decimal:
    """Wide cancellation with binary64 transcendental accuracy, retaining tiny tails."""
    if not values:
        return _NEG_INF
    largest = max(values)
    if largest == _NEG_INF:
        return _NEG_INF
    pivot = values.index(largest)
    tail = math.fsum(
        math.exp(float(value - largest)) for i, value in enumerate(values) if i != pivot
    )
    return largest + Decimal.from_float(math.log1p(tail))


def _probabilities(values: list[Decimal]) -> list[float]:
    """Cancel large log energies before conversion; unreachable states remain explicit zeros."""
    largest = max(values)
    if largest == _NEG_INF:
        raise ValueError("sequence has no possible path")
    weights = [math.exp(float(value - largest)) for value in values]
    total = math.fsum(weights)
    return [weight / total for weight in weights]


def _finite_output(value: Decimal) -> float:
    result = float(value)
    if not math.isfinite(result):
        raise ValueError("absolute sequence log weight exceeds the binary64 output range")
    return result


def infer_sequence(value: SequenceRequest | str | bytes) -> SequenceResult:
    """Infer sparse posterior, flow and a deterministic MAP path from checked numeric inputs.

    Log messages retain wide arithmetic so expensive prefixes can recover after later
    evidence. Transcendental summation and returned probabilities have binary64 precision.
    Absolute log weights outside binary64 range refuse explicitly, never become infinity.
    """
    request = validate_sequence(value)
    ordered = [sorted(row.costs, key=lambda entry: entry.state.sort_key) for row in request.rows]
    ids = [[entry.state.id for entry in row] for row in ordered]
    indices = [{name: i for i, name in enumerate(row)} for row in ids]
    costs = [[Decimal.from_float(entry.cost) for entry in row] for row in ordered]
    tau = Decimal.from_float(request.temperature)
    exponent = max((abs(c).adjusted() for row in costs for c in row if c), default=0)
    # Finite binary64 inputs need at most about 632 magnitude digits after division.
    # Preserve at least 32 absolute fractional digits, with path-length accumulation room.
    precision = max(50, exponent - tau.adjusted() + 2 * len(str(len(ordered))) + 34)
    with localcontext() as context:
        context.prec = precision
        minima = [min(row) for row in costs]
        emissions = [[-(cost - base) / tau for cost in row] for row, base in zip(costs, minima)]
        offset = sum((-base / tau for base in minima), _ZERO)
        # Each entry is an explicitly supplied positive edge. No dense transition matrix.
        edges: list[list[tuple[int, int, Decimal]]] = []
        log_cache: dict[Decimal, Decimal] = {}

        def logarithm(number: Decimal) -> Decimal:
            if number not in log_cache:
                log_cache[number] = number.ln()
            return log_cache[number]

        for i, step in enumerate(request.transitions):
            selected = sorted(
                (edge for edge in step.edges if edge.probability > 0),
                key=lambda edge: (indices[i][edge.from_state], indices[i + 1][edge.to_state]),
            )
            totals = [_ZERO] * len(ids[i])
            for edge in selected:
                j = indices[i][edge.from_state]
                totals[j] += Decimal.from_float(edge.probability)
            edges.append(
                [
                    (
                        indices[i][edge.from_state],
                        indices[i + 1][edge.to_state],
                        logarithm(Decimal.from_float(edge.probability))
                        - logarithm(totals[indices[i][edge.from_state]]),
                    )
                    for edge in selected
                ]
            )

        initial = -logarithm(Decimal(len(ids[0])))
        alpha = [[initial + emission for emission in emissions[0]]]
        delta = alpha[0][:]
        back: list[list[int]] = []
        # This is below binary64 probability resolution, but above Decimal rounding noise.
        # Numerically tied predecessors use the greatest rendered state key, as in Scala.
        tie_tolerance = Decimal("1e-28") * len(ordered)
        for i, edge_step in enumerate(edges):
            incoming: list[list[Decimal]] = [[] for _ in ids[i + 1]]
            choices: list[list[tuple[Decimal, int]]] = [[] for _ in ids[i + 1]]
            for origin, target, log_probability in edge_step:
                incoming[target].append(alpha[-1][origin] + log_probability)
                if delta[origin] != _NEG_INF:
                    choices[target].append((delta[origin] + log_probability, origin))
            alpha.append(
                [
                    _logsumexp(terms) + emission
                    for terms, emission in zip(incoming, emissions[i + 1])
                ]
            )
            next_delta = [_NEG_INF] * len(ids[i + 1])
            predecessors = [-1] * len(ids[i + 1])
            for target, options in enumerate(choices):
                if options:
                    maximum = max(score for score, _ in options)
                    # indices follow state.sort_key, so largest index is the declared tie rule.
                    predecessor = max(j for score, j in options if maximum - score <= tie_tolerance)
                    score = next(score for score, j in options if j == predecessor)
                    next_delta[target] = score + emissions[i + 1][target]
                    predecessors[target] = predecessor
            delta = next_delta
            back.append(predecessors)

        beta = [[_ZERO] * len(row) for row in ids]
        for i in range(len(edges) - 1, -1, -1):
            outgoing: list[list[Decimal]] = [[] for _ in ids[i]]
            for origin, target, log_probability in edges[i]:
                outgoing[origin].append(
                    log_probability + emissions[i + 1][target] + beta[i + 1][target]
                )
            beta[i] = [_logsumexp(terms) for terms in outgoing]

        maximum = max(delta)
        chosen = max(
            i
            for i, score in enumerate(delta)
            if score != _NEG_INF and maximum - score <= tie_tolerance
        )
        path = [chosen]
        for predecessors in reversed(back):
            chosen = predecessors[chosen]
            path.append(chosen)
        path.reverse()
        posterior = tuple(
            PosteriorRow(
                unit=row.unit,
                mass=tuple(
                    StateMass(state=state, probability=probability)
                    for state, probability in zip(
                        ids[i], _probabilities([a + b for a, b in zip(alpha[i], beta[i])])
                    )
                ),
            )
            for i, row in enumerate(request.rows)
        )
        flow = tuple(
            FlowStep(
                from_unit=request.rows[i].unit,
                to_unit=request.rows[i + 1].unit,
                mass=tuple(
                    FlowMass(
                        from_state=ids[i][origin],
                        to_state=ids[i + 1][target],
                        probability=probability,
                    )
                    for (origin, target, _), probability in zip(
                        step,
                        _probabilities(
                            [
                                alpha[i][origin]
                                + log_probability
                                + emissions[i + 1][target]
                                + beta[i + 1][target]
                                for origin, target, log_probability in step
                            ]
                        ),
                    )
                ),
            )
            for i, step in enumerate(edges)
        )
        return SequenceResult(
            request_sha256=digest(request.model_dump(mode="json")),
            posterior=posterior,
            flow=flow,
            best_path=tuple(row[index] for row, index in zip(ids, path)),
            log_normalizer=_finite_output(_logsumexp(alpha[-1]) + offset),
            best_path_log_weight=_finite_output(delta[path[-1]] + offset),
        )


def verify_sequence(
    request: SequenceRequest | str | bytes, value: SequenceResult | str | bytes
) -> SequenceResult:
    """Recompute externally supplied results against independently supplied exact inputs."""
    supplied = (
        SequenceResult.model_validate(value)
        if isinstance(value, SequenceResult)
        else SequenceResult.model_validate_json(canonical(strict_json(value)))
    )
    expected = infer_sequence(request)
    if supplied != expected:
        raise ValueError("sequence result differs from recomputed input-bound result")
    return supplied
