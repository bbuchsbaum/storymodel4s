"""Explicit reconstruction decisions preserve evidence, reference and fidelity.

The decoder maximizes additive anchored mass under a declared group-order policy.
It is not another inference model and does not turn its selections into observed claims.
"""

from __future__ import annotations

import math
from collections import defaultdict
from decimal import Decimal, localcontext
from typing import Annotated, Literal, Self, overload

from pydantic import Field, model_validator

from ._decimal_context import computation_context
from .local_evidence import (
    AccountInferenceResult,
    LocalEvidenceSpec,
    RefinedAccountInference,
    RefinedInferenceSpec,
    _resolve_account_inference,
)
from .model import Digest, Model, Name, _validate_instance
from .rich_account import SupportedAccount, require_text_account
from .sequence import SequenceState
from .serialization import digest
from .source_transitions import SourceProjection, SourceProjectionPolicy, project_source

Nonnegative = Annotated[float, Field(ge=0, allow_inf_nan=False)]


class ReconstructionPolicy(Model):
    """Order and filling are explicit post-inference assumptions, never reference authority."""

    order: Literal["unconstrained", "nondecreasing", "penalized-backward"] = "unconstrained"
    backward_penalty: Nonnegative = 0.0
    forward_penalty: Nonnegative = 0.0
    fill: Literal["disabled", "requested"] = "disabled"

    @model_validator(mode="after")
    def used_parameters(self) -> Self:
        if self.order != "penalized-backward" and self.backward_penalty != 0:
            raise ValueError("backward penalty requires penalized-backward order")
        if self.order != "nondecreasing" and self.forward_penalty != 0:
            raise ValueError("forward penalty requires nondecreasing order")
        if self.order == "unconstrained" and self.fill != "disabled":
            raise ValueError("fill requires a group assignment")
        for name in ("backward_penalty", "forward_penalty"):
            if getattr(self, name) == 0:
                object.__setattr__(self, name, 0.0)
        return self


class FillTarget(Model):
    """A requested leaf for one recall unit and assigned group, without invented mass."""

    unit: Name
    group: int
    anchor: Name


class ReconstructionFill(Model):
    """A fill request is bound to complete input snapshots and its declared grouping."""

    source_sha256: Digest
    recall_sha256: Digest
    projection_sha256: Digest
    roots_sha256: Digest
    targets: tuple[FillTarget, ...]


class ReconstructionSpec(Model):
    """Unique source roots and ranks declare grouping independently of inferred probabilities."""

    schema_version: Literal["storymodel.reconstruction-spec/v1"] = (
        "storymodel.reconstruction-spec/v1"
    )
    roots: dict[Name, int] = Field(default_factory=dict)
    policy: ReconstructionPolicy = Field(default_factory=ReconstructionPolicy)
    fill: ReconstructionFill | None = None

    @model_validator(mode="after")
    def unique_ranks(self) -> Self:
        if len(set(self.roots.values())) != len(self.roots):
            raise ValueError("group ranks must be unique")
        return self


class ReconstructionMass(Model):
    """A finite supplied state weight; normalization and calibration are not presumed."""

    state: SequenceState
    weight: Nonnegative


class ReconstructionRow(Model):
    """Original state weights, including empty or subnormalized rows, remain inspectable."""

    unit: Name
    mass: tuple[ReconstructionMass, ...]

    @model_validator(mode="after")
    def unique_states(self) -> Self:
        ids = [m.state.id for m in self.mass]
        if len(set(ids)) != len(ids):
            raise ValueError("duplicate reconstruction state")
        return self


class ReconstructionDecision(Model):
    """Raw state maximum, raw anchor maximum and chosen-anchor mass are distinct values."""

    unit: Name
    raw_argmax: SequenceState | None
    raw_argmax_mass: Nonnegative | None
    raw_source_maximum: Name | None
    anchor: Name | None
    chosen_mass: Nonnegative | None
    assigned_group: int | None
    origin: Literal["raw-source-maximum", "group-constrained", "requested-fill"]


class ReconstructionResult(Model):
    """Recomputable decisions over unchanged rows; not a categorical mapping or fidelity result."""

    schema_version: Literal["storymodel.reconstruction/v1"] = "storymodel.reconstruction/v1"
    method: Literal["explicit-additive-reconstruction/v1"] = "explicit-additive-reconstruction/v1"
    source_sha256: Digest
    recall_sha256: Digest
    export_permission: Literal["allowed", "denied"]
    projection: SourceProjection
    spec: ReconstructionSpec
    rows: tuple[ReconstructionRow, ...]
    membership: dict[Name, int]
    decisions: tuple[ReconstructionDecision, ...]


class AccountReconstructionResult(Model):
    """Model-conditional reconstruction retains the entire original account inference artifact."""

    schema_version: Literal["storymodel.account-reconstruction/v1"] = (
        "storymodel.account-reconstruction/v1"
    )
    input_kind: Literal["model-conditional-posterior"] = "model-conditional-posterior"
    inference: AccountInferenceResult
    reconstruction: ReconstructionResult


class RefinedAccountReconstructionResult(Model):
    """Model-conditional reconstruction retains the entire original account inference artifact."""

    schema_version: Literal["storymodel.account-reconstruction/v2"] = (
        "storymodel.account-reconstruction/v2"
    )
    input_kind: Literal["model-conditional-posterior"] = "model-conditional-posterior"
    inference: RefinedAccountInference
    reconstruction: ReconstructionResult


def _snapshot(account: SupportedAccount, side: str) -> SupportedAccount:
    checked = require_text_account(account)
    if not checked.documents or any(d.side != side for d in checked.documents):
        raise ValueError(f"reconstruction requires an independent {side}-only account")
    return checked


def _hash(value: Model) -> str:
    return digest(value.model_dump(mode="json"))


def _grouping(projection: SourceProjection, roots: dict[str, int]) -> dict[str, int]:
    nodes = {n.anchor: n for n in projection.nodes}
    if not roots.keys() <= nodes.keys():
        raise ValueError("foreign group root")
    if len(set(roots.values())) != len(roots):
        raise ValueError("group ranks must be unique")
    # Parent-first memoization avoids repeating long ancestry walks for every descendant.
    inherited: dict[str, int | None] = {}
    for start in nodes:
        path: list[str] = []
        seen: set[str] = set()
        cur: str | None = start
        while cur is not None and cur not in inherited:
            if cur in seen or cur not in nodes:
                raise ValueError("cyclic or foreign group ancestry")
            seen.add(cur)
            path.append(cur)
            cur = nodes[cur].parent
        group = inherited.get(cur) if cur is not None else None
        for anchor in reversed(path):
            if anchor in roots:
                if group is not None:
                    raise ValueError("declared groups overlap")
                group = roots[anchor]
            inherited[anchor] = group
    return {anchor: group for anchor, group in inherited.items() if group is not None}


def _check_fill_targets(
    targets: tuple[FillTarget, ...],
    recall: SupportedAccount,
    projection: SourceProjection,
    membership: dict[str, int],
) -> None:
    units = {u.id for u in recall.units if u.kind == "recall"}
    nodes = {n.anchor: n for n in projection.nodes}
    keys = [(t.unit, t.group) for t in targets]
    if len(set(keys)) != len(keys):
        raise ValueError("duplicate fill request")
    for target in targets:
        if target.unit not in units:
            raise ValueError("foreign fill unit")
        # Scala SourceNode.isLeaf uses level == 0, including empty segment leaves.
        if (
            target.anchor not in nodes
            or nodes[target.anchor].level != 0
            or membership.get(target.anchor) != target.group
        ):
            raise ValueError("fill target must be a leaf in the requested group")


def prepare_reconstruction_fill(
    source: SupportedAccount,
    recall: SupportedAccount,
    roots: dict[str, int],
    targets: tuple[FillTarget, ...],
    *,
    projection: SourceProjectionPolicy | None = None,
) -> ReconstructionFill:
    """Bind explicit requested targets before execution; changing any input invalidates reuse."""
    source = _snapshot(source, "source")
    recall = _snapshot(recall, "recall")
    spec = ReconstructionSpec(roots=roots)
    view = project_source(source, projection)
    checked_targets = tuple(_validate_instance(FillTarget, target) for target in targets)
    _check_fill_targets(checked_targets, recall, view, _grouping(view, spec.roots))
    return ReconstructionFill(
        source_sha256=_hash(source),
        recall_sha256=_hash(recall),
        projection_sha256=_hash(view),
        roots_sha256=digest(spec.roots),
        targets=checked_targets,
    )


def _finite(value: Decimal | float, label: str) -> float:
    result = float(value)
    if not math.isfinite(result):
        raise ValueError(f"{label} overflow")
    return result


def _sum(values: list[float], label: str) -> float:
    try:
        return _finite(math.fsum(values), label)
    except OverflowError as error:
        raise ValueError(f"{label} overflow") from error


def _decode(
    rows: tuple[ReconstructionRow, ...],
    projection: SourceProjection,
    membership: dict[str, int],
    spec: ReconstructionSpec,
) -> tuple[ReconstructionDecision, ...]:
    nodes = {n.anchor: n for n in projection.nodes}
    anchor_keys = {n.anchor: f"{n.kind}/{n.anchor}".encode("utf-16-be") for n in projection.nodes}
    anchors: list[dict[str, float]] = []
    groups: list[dict[int, float]] = []
    totals: list[float] = []
    for row in rows:
        buckets: dict[str, list[float]] = defaultdict(list)
        for mass in row.mass:
            state = mass.state
            if state.anchor is not None:
                if state.anchor not in nodes or state.anchor_kind != nodes[state.anchor].kind:
                    raise ValueError("foreign source target or wrong anchor kind")
                buckets[state.anchor].append(mass.weight)
        totals.append(_sum([m.weight for m in row.mass], "row mass"))
        by_anchor = {a: _sum(values, "anchor mass") for a, values in buckets.items()}
        anchors.append(by_anchor)
        group_buckets: dict[int, list[float]] = defaultdict(list)
        for anchor, weight in by_anchor.items():
            if weight > 0 and anchor in membership:
                group_buckets[membership[anchor]].append(weight)
        groups.append({g: _sum(values, "group mass") for g, values in group_buckets.items()})
    _sum(totals, "total row mass")
    ranks = sorted({g for row in groups for g in row})
    assigned: list[int | None] = [None] * len(rows)
    policy = spec.policy
    if rows and ranks and policy.order != "unconstrained":
        # Exact binary64 inputs in wide arithmetic preserve small mass differences even
        # after a large accumulated score. No float log transforms.
        with localcontext(computation_context(1200)):
            previous = [Decimal.from_float(groups[0].get(g, 0.0)) for g in ranks]
            back: list[list[int]] = []
            for row_groups in groups[1:]:
                current: list[Decimal] = []
                pointers: list[int] = []
                running = previous[0]
                running_index = 0
                for j, rank in enumerate(ranks):
                    if policy.order == "nondecreasing" and policy.forward_penalty == 0:
                        if previous[j] > running:
                            running, running_index = previous[j], j
                        best, arg = running, running_index
                    else:
                        candidate_best: Decimal | None = None
                        arg = 0
                        candidates = (
                            range(j + 1) if policy.order == "nondecreasing" else range(len(ranks))
                        )
                        for k in candidates:
                            distance = (
                                max(0, rank - ranks[k] - 1)
                                if policy.order == "nondecreasing"
                                else max(0, k - j)
                            )
                            penalty = (
                                policy.forward_penalty
                                if policy.order == "nondecreasing"
                                else policy.backward_penalty
                            )
                            charge = Decimal.from_float(penalty) * distance
                            _finite(charge, "transition penalty")
                            score = previous[k] - charge
                            _finite(score, "transition score")
                            if candidate_best is None or score > candidate_best:
                                candidate_best, arg = score, k
                        assert candidate_best is not None
                        best = candidate_best
                    value = best + Decimal.from_float(row_groups.get(rank, 0.0))
                    _finite(value, "accumulated score")
                    current.append(value)
                    pointers.append(arg)
                previous = current
                back.append(pointers)
            chosen = max(range(len(ranks)), key=previous.__getitem__)
            for i in range(len(rows) - 1, -1, -1):
                assigned[i] = ranks[chosen]
                if i:
                    chosen = back[i - 1][chosen]
    fill = {(t.unit, t.group): t.anchor for t in spec.fill.targets} if spec.fill else {}
    decisions: list[ReconstructionDecision] = []
    for row, masses, group in zip(rows, anchors, assigned):
        raw = min(row.mass, key=lambda m: (-m.weight, m.state.sort_key), default=None)
        positive = [a for a, weight in masses.items() if weight > 0]
        maximum = min(positive, key=lambda a: (-masses[a], anchor_keys[a]), default=None)
        chosen_anchor = maximum
        origin: Literal["raw-source-maximum", "group-constrained", "requested-fill"] = (
            "raw-source-maximum"
        )
        if group is not None:
            in_group = [a for a in positive if membership.get(a) == group]
            if in_group:
                chosen_anchor = min(in_group, key=lambda a: (-masses[a], anchor_keys[a]))
                origin = "group-constrained"
            elif (row.unit, group) in fill:
                chosen_anchor = fill[row.unit, group]
                origin = "requested-fill"
        decisions.append(
            ReconstructionDecision(
                unit=row.unit,
                raw_argmax=raw.state if raw else None,
                raw_argmax_mass=raw.weight if raw else None,
                raw_source_maximum=maximum,
                anchor=chosen_anchor,
                chosen_mass=masses.get(chosen_anchor, 0.0) if chosen_anchor is not None else None,
                assigned_group=group,
                origin=origin,
            )
        )
    return tuple(decisions)


def reconstruct_rows(
    source: SupportedAccount,
    recall: SupportedAccount,
    rows: tuple[ReconstructionRow, ...],
    spec: ReconstructionSpec,
    *,
    projection: SourceProjectionPolicy | None = None,
) -> ReconstructionResult:
    """Decode supplied weights, preserving their original scale and declared row order."""
    source = _snapshot(source, "source")
    recall = _snapshot(recall, "recall")
    spec = _validate_instance(ReconstructionSpec, spec)
    rows = tuple(_validate_instance(ReconstructionRow, row) for row in rows)
    units = {u.id for u in recall.units if u.kind == "recall"}
    if len({r.unit for r in rows}) != len(rows) or any(r.unit not in units for r in rows):
        raise ValueError("duplicate or foreign reconstruction unit")
    view = project_source(source, projection)
    membership = _grouping(view, spec.roots)
    if spec.fill is not None:
        if spec.policy.fill == "disabled":
            raise ValueError("fill is disabled")
        bound = (
            spec.fill.source_sha256,
            spec.fill.recall_sha256,
            spec.fill.projection_sha256,
            spec.fill.roots_sha256,
        )
        if bound != (_hash(source), _hash(recall), _hash(view), digest(spec.roots)):
            raise ValueError("fill belongs to different source, recall, projection or grouping")
        _check_fill_targets(spec.fill.targets, recall, view, membership)
        if any(t.unit not in {r.unit for r in rows} for t in spec.fill.targets):
            raise ValueError("fill unit is absent from reconstruction rows")
    return ReconstructionResult(
        source_sha256=_hash(source),
        recall_sha256=_hash(recall),
        export_permission="allowed"
        if source.export_permission == recall.export_permission == "allowed"
        else "denied",
        projection=view,
        spec=spec,
        rows=rows,
        membership=membership,
        decisions=_decode(rows, view, membership, spec),
    )


def verify_reconstruction(
    source: SupportedAccount,
    recall: SupportedAccount,
    rows: tuple[ReconstructionRow, ...],
    spec: ReconstructionSpec,
    result: ReconstructionResult,
    *,
    projection: SourceProjectionPolicy | None = None,
) -> ReconstructionResult:
    """Recompute external decisions against their actual inputs instead of trusting receipts."""
    checked = _validate_instance(ReconstructionResult, result)
    expected = reconstruct_rows(source, recall, rows, spec, projection=projection)
    if checked != expected:
        raise ValueError("reconstruction differs from original inputs")
    return expected


@overload
def reconstruct_accounts(
    source: SupportedAccount,
    recall: SupportedAccount,
    evidence_spec: LocalEvidenceSpec,
    spec: ReconstructionSpec,
    *,
    inference: AccountInferenceResult | RefinedAccountInference | None = None,
) -> AccountReconstructionResult: ...


@overload
def reconstruct_accounts(
    source: SupportedAccount,
    recall: SupportedAccount,
    evidence_spec: RefinedInferenceSpec,
    spec: ReconstructionSpec,
    *,
    inference: AccountInferenceResult | RefinedAccountInference | None = None,
) -> RefinedAccountReconstructionResult: ...


def reconstruct_accounts(
    source: SupportedAccount,
    recall: SupportedAccount,
    evidence_spec: LocalEvidenceSpec | RefinedInferenceSpec,
    spec: ReconstructionSpec,
    *,
    inference: AccountInferenceResult | RefinedAccountInference | None = None,
) -> AccountReconstructionResult | RefinedAccountReconstructionResult:
    """Connect account inference to reconstruction while retaining the original evidence."""
    spec = _validate_instance(ReconstructionSpec, spec)
    result = _resolve_account_inference(source, recall, evidence_spec, inference)
    populations = {
        r.unit: {c.state.id: c.state for c in r.costs}
        for r in result.evidence.sequence.request.rows
    }
    rows = tuple(
        ReconstructionRow(
            unit=r.unit,
            mass=tuple(
                ReconstructionMass(state=populations[r.unit][m.state], weight=m.probability)
                for m in r.mass
            ),
        )
        for r in result.inference.posterior
    )
    decisions = reconstruct_rows(
        source, recall, rows, spec, projection=result.evidence.sequence.projection.policy
    )
    if isinstance(result, RefinedAccountInference):
        return RefinedAccountReconstructionResult(inference=result, reconstruction=decisions)
    return AccountReconstructionResult(inference=result, reconstruction=decisions)


@overload
def verify_account_reconstruction(
    source: SupportedAccount,
    recall: SupportedAccount,
    evidence_spec: LocalEvidenceSpec | RefinedInferenceSpec,
    spec: ReconstructionSpec,
    result: AccountReconstructionResult,
) -> AccountReconstructionResult: ...


@overload
def verify_account_reconstruction(
    source: SupportedAccount,
    recall: SupportedAccount,
    evidence_spec: LocalEvidenceSpec | RefinedInferenceSpec,
    spec: ReconstructionSpec,
    result: RefinedAccountReconstructionResult,
) -> RefinedAccountReconstructionResult: ...


def verify_account_reconstruction(
    source: SupportedAccount,
    recall: SupportedAccount,
    evidence_spec: LocalEvidenceSpec | RefinedInferenceSpec,
    spec: ReconstructionSpec,
    result: AccountReconstructionResult | RefinedAccountReconstructionResult,
) -> AccountReconstructionResult | RefinedAccountReconstructionResult:
    """Verify both inference and reconstruction, including all supplied and generated inputs."""
    checked = (
        _validate_instance(RefinedAccountReconstructionResult, result)
        if isinstance(result, RefinedAccountReconstructionResult)
        else _validate_instance(AccountReconstructionResult, result)
    )
    expected = reconstruct_accounts(source, recall, evidence_spec, spec)
    if checked != expected:
        raise ValueError("account reconstruction differs from original inputs")
    return expected
