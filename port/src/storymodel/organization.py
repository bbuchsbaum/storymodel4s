"""Conditional organization arithmetic with explicit dependence and original adjacency.

Numerical availability is not an independent reference-measurement certificate. Inference
wrappers retain model dependence; independent products require an explicit declaration.
"""

from __future__ import annotations

import math
from collections import defaultdict
from fractions import Fraction
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from .local_evidence import (
    AccountInferenceResult,
    LocalEvidenceSpec,
    infer_accounts,
    verify_account_inference,
)
from .model import Digest, Model, Name, _validate_instance
from .rich_account import SupportedAccount, validate_supported_account
from .sequence import External, Finite, SequenceState
from .serialization import digest

Category = Literal[
    "backward", "forward", "same-target", "external", "incomparable", "unresolved", "failed"
]
_CATEGORIES: tuple[Category, ...] = (
    "backward",
    "forward",
    "same-target",
    "external",
    "incomparable",
    "unresolved",
    "failed",
)
Nonnegative = Annotated[float, Field(ge=0, allow_inf_nan=False)]
_TOLERANCE = Fraction.from_float(1e-9)


class OrganizationDestination(Model):
    """A target or explicit external destination; fidelity modes do not change target identity."""

    target: Name | None = None
    external: External | None = None

    @model_validator(mode="after")
    def one_destination(self) -> Self:
        if (self.target is None) == (self.external is None):
            raise ValueError("destination requires exactly one target or external state")
        return self

    @property
    def id(self) -> str:
        return "organization-destination:" + digest(self.model_dump(mode="json"))


class DestinationMass(Model):
    """A finite normalized-row weight, not an empirical calibration claim."""

    destination: OrganizationDestination
    weight: Nonnegative


class OrganizationRow(Model):
    """Unavailable rows remain in their original position; admissibility is explicit."""

    unit: Name
    status: Literal["available", "unresolved", "failed"] = "available"
    mass: tuple[DestinationMass, ...] = ()
    admissible: tuple[OrganizationDestination, ...] | None = None

    @model_validator(mode="after")
    def checked_row(self) -> Self:
        ids = [m.destination.id for m in self.mass]
        if len(set(ids)) != len(ids):
            raise ValueError("duplicate organization destination")
        if self.status != "available":
            if self.mass or self.admissible:
                raise ValueError(
                    "unavailable organization rows cannot contain weights or candidates"
                )
            object.__setattr__(self, "admissible", ())
            return self
        if (
            not ids
            or abs(sum((Fraction(m.weight) for m in self.mass), Fraction()) - 1) > _TOLERANCE
        ):
            raise ValueError("organization row mass must sum to one")
        if self.admissible is None:
            object.__setattr__(self, "admissible", tuple(m.destination for m in self.mass))
        assert self.admissible is not None
        admissible = [d.id for d in self.admissible]
        if (
            not admissible
            or len(set(admissible)) != len(admissible)
            or not set(admissible) <= set(ids)
        ):
            raise ValueError("admissible destinations must be unique nonempty row members")
        return self


class OrganizationOrder(Model):
    """Caller-declared comparable source ranks, separate from inference scoring coordinates."""

    axis: Literal["declared", "discourse", "story-world"] = "declared"
    positions: dict[Name, Finite] = Field(default_factory=dict)


class JointMass(Model):
    """One sparse pair of destinations with its supplied adjacent joint weight."""

    left: OrganizationDestination
    right: OrganizationDestination
    weight: Nonnegative


class OrganizationJoint(Model):
    """A joint belongs to one original adjacency and must reproduce both actual marginals."""

    left_unit: Name
    right_unit: Name
    mass: tuple[JointMass, ...]


class OrganizationSpec(Model):
    """Declare the row population, order, dependence and whether product-space bounds are wanted."""

    schema_version: Literal["storymodel.organization-spec/v1"] = "storymodel.organization-spec/v1"
    rows: tuple[OrganizationRow, ...]
    order: OrganizationOrder = Field(default_factory=OrganizationOrder)
    dependence: Literal["independent-rows", "adjacent-joints"]
    joints: tuple[OrganizationJoint, ...] = ()
    product_bounds: bool = True

    @model_validator(mode="after")
    def dependence_contract(self) -> Self:
        if self.dependence == "independent-rows" and self.joints:
            raise ValueError("independent rows cannot carry joints")
        if self.dependence == "adjacent-joints" and self.product_bounds:
            raise ValueError("product-space bounds are unsupported for coupled inputs")
        return self


class OrganizationTransition(Model):
    """Expected category counts retain the two original unit identifiers."""

    left_unit: Name
    right_unit: Name
    counts: dict[Category, Nonnegative]


class OrganizationBounds(Model):
    """Whole-path extrema share a coherent assignment at every intermediate unit."""

    minimum: Annotated[int, Field(ge=0)]
    maximum: Annotated[int, Field(ge=0)]


class OrganizationResult(Model):
    """Conditional numerical readout; a source-bound receipt is not scientific qualification."""

    schema_version: Literal["storymodel.organization/v1"] = "storymodel.organization/v1"
    method: Literal["exact-product-sums-coherent-bounds/v1"] = (
        "exact-product-sums-coherent-bounds/v1"
    )
    interpretation: Literal["conditional-numerical-readout"] = "conditional-numerical-readout"
    source_sha256: Digest
    recall_sha256: Digest
    export_permission: Literal["allowed", "denied"]
    spec: OrganizationSpec
    transitions: tuple[OrganizationTransition, ...]
    counts: dict[Category, Nonnegative]
    adjacency_count: Annotated[int, Field(ge=0)]
    comparable_mass: Nonnegative
    backward_rate: Nonnegative | None
    available_units: Annotated[int, Field(ge=0)]
    resolved_adjacencies: Annotated[int, Field(ge=0)]
    information: Literal["uniform-rows", "distinguished-rows", "unavailable"]
    bounds: dict[Category, OrganizationBounds] | None
    bounds_status: Literal["product-space", "not-requested", "unavailable-rows"]


class AccountOrganizationResult(Model):
    """Joint-flow readout retains its model; it cannot certify independent organization."""

    schema_version: Literal["storymodel.account-organization/v1"] = (
        "storymodel.account-organization/v1"
    )
    interpretation: Literal["model-conditional-not-independent-measurement"] = (
        "model-conditional-not-independent-measurement"
    )
    inference: AccountInferenceResult
    organization: OrganizationResult


def _side(value: SupportedAccount, side: str) -> SupportedAccount:
    value = validate_supported_account(value)
    if not value.documents or any(d.side != side for d in value.documents):
        raise ValueError(f"organization requires an independent {side}-only account")
    return value


def _hash(value: Model) -> str:
    return digest(value.model_dump(mode="json"))


def _number(value: Fraction, label: str) -> float:
    try:
        result = float(value)
    except OverflowError as error:
        raise ValueError(f"{label} exceeds finite binary64 range") from error
    if not math.isfinite(result) or (value > 0 and result == 0):
        raise ValueError(f"positive {label} cannot be represented in binary64")
    return result


def _category(
    left: OrganizationDestination, right: OrganizationDestination, order: OrganizationOrder
) -> Category:
    if left.external == "unranked" or right.external == "unranked":
        return "unresolved"
    if left.external is not None or right.external is not None:
        return "external"
    if left.target == right.target:
        return "same-target"
    assert left.target is not None and right.target is not None
    a, b = order.positions.get(left.target), order.positions.get(right.target)
    if a is None or b is None or a == b:
        return "incomparable"
    return "backward" if b < a else "forward"


def _check_joint(left: OrganizationRow, right: OrganizationRow, joint: OrganizationJoint) -> None:
    a = {m.destination.id: Fraction(m.weight) for m in left.mass}
    b = {m.destination.id: Fraction(m.weight) for m in right.mass}
    outgoing: dict[str, Fraction] = defaultdict(Fraction)
    incoming: dict[str, Fraction] = defaultdict(Fraction)
    seen: set[tuple[str, str]] = set()
    for entry in joint.mass:
        pair = entry.left.id, entry.right.id
        if pair in seen or pair[0] not in a or pair[1] not in b:
            raise ValueError("joint has duplicate or foreign destinations")
        seen.add(pair)
        outgoing[pair[0]] += Fraction(entry.weight)
        incoming[pair[1]] += Fraction(entry.weight)
    # A global check also rejects many individually tiny marginal discrepancies that add up.
    if abs(sum(outgoing.values(), Fraction()) - 1) > _TOLERANCE:
        raise ValueError("joint mass must sum to one")
    for expected, actual in ((a, outgoing), (b, incoming)):
        if any(
            abs(value - actual.get(key, Fraction())) > _TOLERANCE for key, value in expected.items()
        ):
            raise ValueError("joint differs from actual row marginals")


def _bounds(
    rows: tuple[OrganizationRow, ...], order: OrganizationOrder
) -> dict[Category, OrganizationBounds]:
    result: dict[Category, OrganizationBounds] = {}
    for category in _CATEGORIES:
        if not rows:
            result[category] = OrganizationBounds(minimum=0, maximum=0)
            continue
        previous = {d.id: (d, 0, 0) for d in rows[0].admissible or ()}
        for row in rows[1:]:
            current: dict[str, tuple[OrganizationDestination, int, int]] = {}
            for destination in row.admissible or ():
                minimum, maximum = len(rows), 0
                for origin, low, high in previous.values():
                    inc = int(_category(origin, destination, order) == category)
                    minimum = min(minimum, low + inc)
                    maximum = max(maximum, high + inc)
                current[destination.id] = destination, minimum, maximum
            previous = current
        result[category] = OrganizationBounds(
            minimum=min(v[1] for v in previous.values()),
            maximum=max(v[2] for v in previous.values()),
        )
    return result


def organization_readout(
    source: SupportedAccount, recall: SupportedAccount, spec: OrganizationSpec
) -> OrganizationResult:
    """Compute row expectations and coherent bounds under an explicit dependence assumption."""
    source = _side(source, "source")
    recall = _side(recall, "recall")
    spec = _validate_instance(OrganizationSpec, spec)
    targets = {u.id for u in source.units} | {s.id for s in source._situations()}
    units = {u.id for u in recall.units if u.kind == "recall"}
    if not spec.order.positions.keys() <= targets:
        raise ValueError("foreign organization order target")
    if len({r.unit for r in spec.rows}) != len(spec.rows) or any(
        r.unit not in units for r in spec.rows
    ):
        raise ValueError("duplicate or foreign organization unit")
    for row in spec.rows:
        if any(
            m.destination.target is not None and m.destination.target not in targets
            for m in row.mass
        ):
            raise ValueError("foreign organization target")
    pairs = tuple(zip(spec.rows, spec.rows[1:]))
    joints = {(j.left_unit, j.right_unit): j for j in spec.joints}
    if spec.dependence == "adjacent-joints":
        expected = {(a.unit, b.unit) for a, b in pairs if a.status == b.status == "available"}
        if len(joints) != len(spec.joints) or joints.keys() != expected:
            raise ValueError("joints must match exactly the available original adjacencies")
    totals = {c: Fraction() for c in _CATEGORIES}
    transitions: list[OrganizationTransition] = []
    resolved = 0
    for left, right in pairs:
        counts = {c: Fraction() for c in _CATEGORIES}
        if "failed" in (left.status, right.status):
            counts["failed"] = Fraction(1)
        elif left.status != "available" or right.status != "available":
            counts["unresolved"] = Fraction(1)
        elif spec.dependence == "independent-rows":
            # Stream exact binary64 products into seven accumulators. Tiny products can
            # combine into a representable category mass; no dense pair table is built.
            right_weights = [(m.destination, Fraction(m.weight)) for m in right.mass]
            for a in left.mass:
                weight = Fraction(a.weight)
                for destination, other in right_weights:
                    counts[_category(a.destination, destination, spec.order)] += weight * other
        else:
            joint = joints[left.unit, right.unit]
            _check_joint(left, right, joint)
            for entry in joint.mass:
                counts[_category(entry.left, entry.right, spec.order)] += Fraction(entry.weight)
        for category, value in counts.items():
            totals[category] += value
        resolved += int(counts["unresolved"] == counts["failed"] == 0)
        transitions.append(
            OrganizationTransition(
                left_unit=left.unit,
                right_unit=right.unit,
                counts={c: _number(v, "transition category mass") for c, v in counts.items()},
            )
        )
    comparable = totals["backward"] + totals["forward"] + totals["same-target"]
    available = [r for r in spec.rows if r.status == "available"]
    information: Literal["uniform-rows", "distinguished-rows", "unavailable"] = "unavailable"
    if available:
        information = (
            "uniform-rows"
            if all(len(r.mass) > 1 and len({m.weight for m in r.mass}) == 1 for r in available)
            else "distinguished-rows"
        )
    bounds_status: Literal["product-space", "not-requested", "unavailable-rows"] = "not-requested"
    bounds = None
    if spec.product_bounds:
        if len(available) != len(spec.rows):
            bounds_status = "unavailable-rows"
        else:
            bounds_status = "product-space"
            bounds = _bounds(spec.rows, spec.order)
    return OrganizationResult(
        source_sha256=_hash(source),
        recall_sha256=_hash(recall),
        export_permission="allowed"
        if source.export_permission == recall.export_permission == "allowed"
        else "denied",
        spec=spec,
        transitions=tuple(transitions),
        counts={c: _number(v, "category count") for c, v in totals.items()},
        adjacency_count=len(pairs),
        comparable_mass=_number(comparable, "comparable mass"),
        backward_rate=_number(totals["backward"] / comparable, "backward rate")
        if comparable
        else None,
        available_units=len(available),
        resolved_adjacencies=resolved,
        information=information,
        bounds=bounds,
        bounds_status=bounds_status,
    )


def verify_organization_readout(
    source: SupportedAccount,
    recall: SupportedAccount,
    spec: OrganizationSpec,
    result: OrganizationResult,
) -> OrganizationResult:
    """Recompute a saved numerical readout against its actual accounts and input specification."""
    checked = _validate_instance(OrganizationResult, result)
    expected = organization_readout(source, recall, spec)
    if checked != expected:
        raise ValueError("organization readout differs from original inputs")
    return expected


def _destination(state: SequenceState) -> OrganizationDestination:
    return OrganizationDestination(target=state.anchor, external=state.external)


def organize_accounts(
    source: SupportedAccount,
    recall: SupportedAccount,
    evidence_spec: LocalEvidenceSpec,
    order: OrganizationOrder,
    *,
    inference: AccountInferenceResult | None = None,
) -> AccountOrganizationResult:
    """Read adjacent joint flow over the exact ordered inference population, retaining its model."""
    evidence_spec = _validate_instance(LocalEvidenceSpec, evidence_spec)
    order = _validate_instance(OrganizationOrder, order)
    result = (
        infer_accounts(source, recall, evidence_spec)
        if inference is None
        else verify_account_inference(source, recall, evidence_spec, inference)
    )
    populations = {
        r.unit: {m.state.id: _destination(m.state) for m in r.costs}
        for r in result.evidence.sequence.request.rows
    }
    rows: list[OrganizationRow] = []
    for row in result.inference.posterior:
        destinations = {d.id: d for d in populations[row.unit].values()}
        masses: dict[str, Fraction] = defaultdict(Fraction)
        for mass in row.mass:
            masses[populations[row.unit][mass.state].id] += Fraction(mass.probability)
        rows.append(
            OrganizationRow(
                unit=row.unit,
                mass=tuple(
                    DestinationMass(
                        destination=destinations[key], weight=_number(value, "collapsed row mass")
                    )
                    for key, value in sorted(masses.items())
                ),
            )
        )
    joints: list[OrganizationJoint] = []
    for flow in result.inference.flow:
        joint_masses: dict[tuple[str, str], Fraction] = defaultdict(Fraction)
        destination_pairs: dict[
            tuple[str, str], tuple[OrganizationDestination, OrganizationDestination]
        ] = {}
        for flow_mass in flow.mass:
            a, b = (
                populations[flow.from_unit][flow_mass.from_state],
                populations[flow.to_unit][flow_mass.to_state],
            )
            key = a.id, b.id
            destination_pairs[key] = a, b
            joint_masses[key] += Fraction(flow_mass.probability)
        joints.append(
            OrganizationJoint(
                left_unit=flow.from_unit,
                right_unit=flow.to_unit,
                mass=tuple(
                    JointMass(
                        left=destination_pairs[key][0],
                        right=destination_pairs[key][1],
                        weight=_number(value, "collapsed joint mass"),
                    )
                    for key, value in sorted(joint_masses.items())
                ),
            )
        )
    spec = OrganizationSpec(
        rows=tuple(rows),
        order=order,
        dependence="adjacent-joints",
        joints=tuple(joints),
        product_bounds=False,
    )
    return AccountOrganizationResult(
        inference=result, organization=organization_readout(source, recall, spec)
    )


def verify_account_organization(
    source: SupportedAccount,
    recall: SupportedAccount,
    evidence_spec: LocalEvidenceSpec,
    order: OrganizationOrder,
    result: AccountOrganizationResult,
) -> AccountOrganizationResult:
    """Verify both the original account inference and its conditional joint-flow readout."""
    checked = _validate_instance(AccountOrganizationResult, result)
    expected = organize_accounts(source, recall, evidence_spec, order)
    if checked != expected:
        raise ValueError("account organization differs from original inputs")
    return expected
