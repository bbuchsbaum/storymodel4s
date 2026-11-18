"""Account-bound unbalanced optimal transport over the union of nominated source targets."""

import math
from collections import defaultdict
from collections.abc import Iterator
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from ._base import Digest, Model, Name, _validate_instance
from .local_evidence import (
    LocalEvidenceSpec,
    RecallSelection,
    RetrievalRow,
    SemanticCell,
    _AccountContent,
    _candidate_postings,
    _lexical_distance,
    _nominate,
    _prepare_account_content,
)
from .rich_account import SupportedAccount, require_text_account
from .sequence import Probability
from .serialization import canonical, digest, strict_json
from .sinkhorn import SinkhornConfig, TransportScaling, scale_transport
from .source_transitions import SourceProjection, SourceProjectionPolicy


def _baseline_config() -> SinkhornConfig:
    return SinkhornConfig(epsilon=0.05, rho_rows=10.0, rho_cols=0.1, max_iterations=300)


class TransportSpec(Model):
    """Select units, nominate source candidates, and declare the baseline's semantic distances."""

    schema_version: Literal["storymodel.transport-spec/v1"] = "storymodel.transport-spec/v1"
    units: Annotated[tuple[RecallSelection, ...], Field(min_length=1)]
    projection: SourceProjectionPolicy = Field(default_factory=SourceProjectionPolicy)
    max_candidates: Annotated[int, Field(gt=0)] = 32
    max_postings: Annotated[int, Field(gt=0)] = 4096
    semantic: Literal["lexical", "table"] = "lexical"
    semantic_cells: tuple[SemanticCell, ...] = ()
    missing_distance: Probability = 0.5
    config: SinkhornConfig = Field(default_factory=_baseline_config)

    def _selection(self) -> LocalEvidenceSpec:
        return LocalEvidenceSpec(
            units=self.units,
            projection=self.projection,
            max_candidates=self.max_candidates,
            max_postings=self.max_postings,
            semantic=self.semantic,
            semantic_cells=self.semantic_cells,
        )


class TransportCosts(Model):
    """Exceptions to the common cost; null denotes an ineligible pair with exactly zero mass."""

    default_kind: Literal["observed", "imputed"]
    default_distance: Probability
    rows: tuple[dict[int, Probability | None], ...]


class TransportAlignment(Model):
    """Candidate identities, costs, achieved marginals and fit status for a compact plan."""

    schema_version: Literal["storymodel.account-transport/v1"] = "storymodel.account-transport/v1"
    measure: Literal["transport-mass"] = "transport-mass"
    source_sha256: Digest
    recall_sha256: Digest
    spec: TransportSpec
    projection: SourceProjection
    retrieval: tuple[RetrievalRow, ...]
    columns: tuple[Name, ...]
    row_targets: tuple[float, ...]
    column_targets: tuple[float, ...]
    costs: TransportCosts
    status: Literal["converged", "iteration-limit", "no-candidates"]
    scaling: TransportScaling | None

    @model_validator(mode="after")
    def dimensions(self) -> Self:
        if self.source_sha256 != self.projection.account_sha256:
            raise ValueError("transport source differs from its projection")
        units = tuple(u.unit for u in self.spec.units)
        if tuple(r.unit for r in self.retrieval) != units or len(set(units)) != len(units):
            raise ValueError("transport rows differ from selected recall units")
        union = {n.target for r in self.retrieval for n in r.nominations}
        if self.columns != tuple(sorted(union)):
            raise ValueError("transport columns differ from candidate union")
        m, n = len(units), len(self.columns)
        if self.row_targets != (1.0,) * m or self.column_targets != ((m / n,) * n if n else ()):
            raise ValueError("transport target masses differ from baseline budget")
        if len(self.costs.rows) != m or any(j < 0 or j >= n for r in self.costs.rows for j in r):
            raise ValueError("transport cost geometry differs from candidate union")
        if self.scaling is None:
            if n or self.status != "no-candidates":
                raise ValueError("only an empty candidate union can omit transport scaling")
        elif (
            not n
            or self.status != ("converged" if self.scaling.converged else "iteration-limit")
            or len(self.scaling.log_u) != m
            or len(self.scaling.log_v) != n
            or len(self.scaling.row_marginals) != m
            or len(self.scaling.column_marginals) != n
        ):
            raise ValueError("transport scaling differs from requested geometry or status")
        return self

    def mass_at(self, row: int, column: int) -> float:
        """Read one plan entry by zero-based position in the saved row and column order."""
        if (
            type(row) is not int
            or not 0 <= row < len(self.retrieval)
            or type(column) is not int
            or not 0 <= column < len(self.columns)
        ):
            raise ValueError("transport cell is outside the candidate union or recall selection")
        assert self.scaling is not None
        cost = self.costs.rows[row].get(column, self.costs.default_distance)
        return (
            0.0
            if cost is None
            else self.scaling.mass_at(row, column, cost, self.spec.config.epsilon)
        )

    def iter_row(self, row: int | str) -> Iterator[tuple[str, float]]:
        """Read one recall unit by ID or position, yielding each source ID and its mass."""
        position = (
            next((i for i, r in enumerate(self.retrieval) if r.unit == row), -1)
            if isinstance(row, str)
            else row
        )
        if type(position) is not int or not 0 <= position < len(self.retrieval):
            raise ValueError("unknown transport row")
        for j, target in enumerate(self.columns):
            yield target, self.mass_at(position, j)


def _transport_costs(
    prepared: _AccountContent, spec: TransportSpec, columns: tuple[str, ...]
) -> TransportCosts:
    by_target = {target: j for j, target in enumerate(columns)}
    rows: list[dict[int, float | None]] = []
    if spec.semantic == "table":
        for u in spec.units:
            # A measured distance equal to the missing-distance policy is still an observation.
            rows.append(
                {
                    by_target[t]: e.value
                    for t, e in prepared.table.get(u.unit, {}).items()
                    if t in by_target and e.status != "missing"
                }
            )
        return TransportCosts(
            default_kind="imputed", default_distance=spec.missing_distance, rows=tuple(rows)
        )
    # Disjoint lemma sets have the baseline's exact observed distance of one.
    postings: dict[str, list[int]] = defaultdict(list)
    for j, target in enumerate(columns):
        for word in prepared.targets[target].lemmas:
            postings[word].append(j)
    for u in spec.units:
        content, _ = prepared.units[u.unit]
        hits = {j for term in content.lemmas for j in postings.get(term, ())}
        rows.append(
            {j: _lexical_distance(content, prepared.targets[columns[j]]) for j in sorted(hits)}
        )
    return TransportCosts(default_kind="observed", default_distance=1.0, rows=tuple(rows))


def align_transport(
    source: SupportedAccount, recall: SupportedAccount, spec: TransportSpec
) -> TransportAlignment:
    """Fit the semantic-only transport baseline over every selected unit and the candidate union."""
    spec = _validate_instance(TransportSpec, spec)
    source, recall = require_text_account(source), require_text_account(recall)
    selection = spec._selection()
    prepared = _prepare_account_content(source, recall, selection)
    postings = (
        _candidate_postings(prepared.targets) if any(s.targets is None for s in spec.units) else {}
    )
    retrieval = tuple(
        _nominate(
            u.unit,
            prepared.units[u.unit][1],
            prepared.units[u.unit][0],
            u,
            prepared.targets,
            postings,
            selection,
        )
        for u in spec.units
    )
    columns = tuple(sorted({n.target for r in retrieval for n in r.nominations}))
    costs = _transport_costs(prepared, spec, columns)
    row_targets = (1.0,) * len(retrieval)
    column_targets = (len(retrieval) / len(columns),) * len(columns) if columns else ()
    scaling = None
    if columns:
        overrides = tuple(
            {j: math.inf if cost is None else cost for j, cost in row.items()} for row in costs.rows
        )
        scaling = scale_transport(
            overrides, row_targets, column_targets, spec.config, default_cost=costs.default_distance
        )
    return TransportAlignment(
        source_sha256=prepared.projection.account_sha256,
        recall_sha256=digest(recall.model_dump(mode="json")),
        spec=spec,
        projection=prepared.projection,
        retrieval=retrieval,
        columns=columns,
        row_targets=row_targets,
        column_targets=column_targets,
        costs=costs,
        status="no-candidates"
        if scaling is None
        else "converged"
        if scaling.converged
        else "iteration-limit",
        scaling=scaling,
    )


def verify_transport(
    source: SupportedAccount,
    recall: SupportedAccount,
    spec: TransportSpec,
    saved: TransportAlignment | str | bytes,
) -> TransportAlignment:
    """Recompute and compare the complete transport artifact, including every dual factor."""
    expected = align_transport(source, recall, spec)
    actual = (
        strict_json(saved)
        if isinstance(saved, (str, bytes))
        else _validate_instance(TransportAlignment, saved).model_dump(mode="json")
    )
    if canonical(actual) != canonical(expected.model_dump(mode="json")):
        raise ValueError("transport plan differs from original accounts or specification")
    return expected
