"""Reference weights from unit-local measurements on one declared source level."""

import math
from collections import defaultdict
from heapq import nsmallest
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from ._base import Digest, Model, Name, _validate_instance
from .local_evidence import (
    LocalCell,
    LocalEvidenceSpec,
    Nomination,
    RetrievalRow,
    SemanticEstimate,
    _Content,
    _lexical_distance,
    _price_account_rows,
)
from .rich_account import SupportedAccount, require_text_account
from .sequence import Probability, SequenceRow, SequenceState, StateCost
from .serialization import digest
from .source_transitions import SourceProjection


class ReferenceRetrieval(RetrievalRow):
    """Measurement counts cover the full level before tie completion and the union budget."""

    observed: int
    missing: int
    ineligible: int
    uniform_semantic: bool | None
    lexical_hits: tuple[Name, ...]
    cutoff_distance: Probability | None


class LocalReferenceSpec(Model):
    """Declare the scoring basis, target level, cutoff, and normalization population."""

    schema_version: Literal["storymodel.local-reference-spec/v1"] = (
        "storymodel.local-reference-spec/v1"
    )
    evidence: LocalEvidenceSpec
    level: Annotated[int, Field(ge=0)] = 0
    population: Literal["supplied-destinations", "admitted-source-targets"] = (
        "supplied-destinations"
    )
    pricing: Literal["local-features", "measured-semantic"] = "local-features"
    tie_budget: Annotated[int, Field(gt=0)] | None = None
    lexical_overlap: bool = False

    @model_validator(mode="after")
    def compatible(self) -> Self:
        if any(u.targets is not None for u in self.evidence.units):
            raise ValueError("supplied targets bypass complete-level nomination")
        if self.tie_budget is not None and self.tie_budget < self.evidence.max_candidates:
            raise ValueError("tie budget must be at least the per-level candidate cutoff")
        costs = self.evidence.costs
        if self.pricing == "measured-semantic" and (
            costs.weights.get("semantic") != 1
            or any(v for k, v in costs.weights.items() if k != "semantic")
            or any(costs.function_prior.values())
            or costs.distortion_penalty
        ):
            raise ValueError(
                "measured semantic pricing requires semantic-only weights "
                "and zero function and distortion priors"
            )
        return self

    def _nominate(
        self,
        unit: str,
        situation: str | None,
        content: _Content,
        targets: dict[str, _Content],
        table: dict[str, SemanticEstimate],
    ) -> ReferenceRetrieval:
        ineligible = 0
        if self.evidence.semantic == "lexical":
            scores = {
                anchor: _lexical_distance(content, target) for anchor, target in targets.items()
            }
        else:
            # Absent cells are missing. A sparse table compares only its supplied entries.
            scores = {}
            for anchor, measurement in table.items():
                if anchor in targets:
                    if measurement.value is not None:
                        scores[anchor] = measurement.value
                    elif measurement.status == "ineligible":
                        ineligible += 1
        lexical: set[str] = set()
        if self.lexical_overlap:
            names = {name for _, values in content.participants for name in values}
            lexical = {
                anchor
                for anchor, target in targets.items()
                if not content.lemmas.isdisjoint(target.lemmas)
                or any(not names.isdisjoint(values) for _, values in target.participants)
            }
        k = self.evidence.max_candidates
        cutoff = (
            nsmallest(k, scores.values())[-1]
            if k < len(scores)
            else max(scores.values(), default=None)
        )
        kept = {a: d for a, d in scores.items() if cutoff is not None and d <= cutoff}
        selected = lexical | kept.keys()
        overflow = self.tie_budget is not None and len(selected) > self.tie_budget
        return ReferenceRetrieval(
            unit=unit,
            situation=situation,
            status="search-limited" if overflow else "ranked" if selected else "abstained",
            nominations=()
            if overflow
            else tuple(Nomination(target=a, score=kept.get(a)) for a in sorted(selected)),
            postings_read=0,
            candidates_seen=len(selected),
            observed=len(scores),
            missing=len(targets) - len(scores) - ineligible,
            ineligible=ineligible,
            uniform_semantic=min(scores.values()) == max(scores.values())
            if len(scores) >= 2
            else None,
            lexical_hits=tuple(sorted(lexical)),
            cutoff_distance=cutoff,
        )


class LocalReferenceMass(Model):
    """One normalized weight over the declared population, retaining the actual fidelity mode."""

    state: SequenceState
    weight: Probability


_Status = Literal[
    "computed",
    "tie-overflow",
    "unranked",
    "no-admitted-targets",
    "prior-only-target-cost",
    "unmeasured-target-cost",
    "incomparable-target-cost",
]


class LocalReferenceRow(Model):
    """Measured rows retain their prices; a withheld result has no distribution."""

    unit: Name
    status: _Status
    costs: tuple[StateCost, ...]
    information: Literal["uniform-costs", "distinguished-costs"] | None
    mass: tuple[LocalReferenceMass, ...]


class LocalReferenceResult(Model):
    """The exact source, recall, evidence recipe, candidate cut and per-unit results."""

    schema_version: Literal["storymodel.local-reference/v1"] = "storymodel.local-reference/v1"
    measure: Literal["normalized-score-mass"] = "normalized-score-mass"
    source_sha256: Digest
    recall_sha256: Digest
    spec: LocalReferenceSpec
    projection: SourceProjection
    universe: tuple[Name, ...]
    retrieval: tuple[ReferenceRetrieval, ...]
    cells: tuple[LocalCell, ...]
    rows: tuple[LocalReferenceRow, ...]


def _score_row(
    prices: SequenceRow,
    receipt: ReferenceRetrieval,
    cells: list[LocalCell],
    spec: LocalReferenceSpec,
) -> LocalReferenceRow:
    def unavailable(reason: _Status) -> LocalReferenceRow:
        return LocalReferenceRow(
            unit=prices.unit, status=reason, costs=prices.costs, mass=(), information=None
        )

    if receipt.status == "search-limited":
        return unavailable("tie-overflow")
    if receipt.status == "abstained":
        return unavailable("unranked")
    admitted = [c for c in cells if not c.excluded]
    if not admitted:
        return unavailable("no-admitted-targets")
    if any(c.support_reason == "zero-eligible-weight" for c in admitted):
        return unavailable("prior-only-target-cost")
    if any(c.support is None or c.support <= 0 for c in admitted):
        return unavailable("unmeasured-target-cost")
    groups: list[list[StateCost]]
    if spec.population == "admitted-source-targets":
        if any(
            set(c.terms) != {"semantic"}
            or c.imputed
            or c.total != c.terms["semantic"]
            or c.measured != ("semantic",)
            or c.eligible != ("semantic",)
            for c in admitted
        ):
            return unavailable("incomparable-target-cost")
        by_target: dict[str, list[StateCost]] = defaultdict(list)
        for entry in prices.costs:
            if entry.state.anchor is not None:
                by_target[entry.state.anchor].append(entry)
        groups = list(by_target.values())
        if any(any(e.cost != g[0].cost for e in g) for g in groups):
            return unavailable("incomparable-target-cost")
    else:
        groups = [[entry] for entry in prices.costs]
    energies = [group[0].cost for group in groups]
    least = min(energies)
    weights = [math.exp(-((cost - least) / spec.evidence.temperature)) for cost in energies]
    total = math.fsum(weights)
    mass = tuple(
        LocalReferenceMass(state=e.state, weight=(w / total) / len(group))
        for w, group in zip(weights, groups, strict=True)
        for e in group
    )
    return LocalReferenceRow(
        unit=prices.unit,
        status="computed",
        costs=prices.costs,
        information="uniform-costs" if max(energies) == least else "distinguished-costs",
        mass=mass,
    )


def local_reference(
    source: SupportedAccount, recall: SupportedAccount, spec: LocalReferenceSpec
) -> LocalReferenceResult:
    """Construct local evidence from independent accounts and normalize admitted prices by unit."""
    spec = _validate_instance(LocalReferenceSpec, spec)
    source, recall = require_text_account(source), require_text_account(recall)
    priced = _price_account_rows(source, recall, spec.evidence, reference=spec)
    cells: dict[str, list[LocalCell]] = defaultdict(list)
    for cell in priced.cells:
        cells[cell.unit].append(cell)
    receipts = []
    rows = []
    for prices, retrieval in zip(priced.rows, priced.retrieval, strict=True):
        assert isinstance(retrieval, ReferenceRetrieval)
        receipts.append(retrieval)
        rows.append(_score_row(prices, retrieval, cells[prices.unit], spec))
    return LocalReferenceResult(
        source_sha256=priced.projection.account_sha256,
        recall_sha256=digest(recall.model_dump(mode="json")),
        spec=spec,
        projection=priced.projection,
        universe=tuple(sorted(n.anchor for n in priced.projection.nodes if n.level == spec.level)),
        retrieval=tuple(receipts),
        cells=priced.cells,
        rows=tuple(rows),
    )
