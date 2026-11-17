"""Explicit analysis policies over an unchanged account (ADR 0030)."""

from __future__ import annotations

from collections import Counter
from typing import Literal

from .model import (
    Detail,
    Model,
    Span,
    Support,
    Unit,
    contained_in,
    digest,
    text_support,
)
from .rich_account import SupportedAccount, require_text_account

Policy = Literal["target-episode-novel-content/v1", "target-event-only/v1"]
Category = Literal["internal", "external", "unresolved", "excluded"]
Coverage = Literal["present", "not-assessable", "uncertain", "absent"]


class RecallRow(Model):
    detail: str
    unit: str
    category: Category
    address: str
    kind: str
    reference_status: str
    reference_outcome: str | None


class InventoryRow(Model):
    unit: str
    status: str
    reason: str | None
    detail_count: int


class CorrespondenceRow(Model):
    detail: str
    interpretation: str
    hypothesis: str
    meaning: str
    alternative: bool
    target: str | None
    external: str | None
    assessment_status: str
    fidelity: str | None


class SourceRow(Model):
    source_unit: str
    coverage: Coverage
    contributing_details: tuple[str, ...]


class Counts(Model):
    internal: int
    external: int
    unresolved: int
    excluded: int
    denominator: int
    internal_fraction: float | None


class Analysis(Model):
    schema_version: Literal["storymodel.analysis/v1"] = "storymodel.analysis/v1"
    account_sha256: str
    policy: Policy
    coverage_policy: Literal["definite-supported-rows/v1"] = "definite-supported-rows/v1"
    recall: tuple[RecallRow, ...]
    inventories: tuple[InventoryRow, ...]
    correspondence: tuple[CorrespondenceRow, ...]
    source: tuple[SourceRow, ...]
    counts: Counts


def classify(detail: Detail, policy: Policy) -> Category:
    """Counting exclusions do not change the detail's stored address."""
    if detail.kind == "discourse" or detail.discourse == "repetition":
        return "excluded"
    if policy == "target-event-only/v1" and detail.kind != "event":
        return "excluded"
    if detail.address == "target":
        return "internal"
    if detail.address in ("other", "personal", "general"):
        return "external"
    return "unresolved"


def project(
    value: SupportedAccount,
    policy: Policy = "target-episode-novel-content/v1",
    *,
    eligible_rows: tuple[str, ...] | None = None,
) -> Analysis:
    """Produce three views; broad/alternative references never earn definite row credit.

    Unlocalized or unprocessed details make otherwise absent rows not-assessable. A
    source row can still be definitely present despite an unresolved detail elsewhere.
    Rows outside the declared eligible universe are absent from this projection only.
    """
    account = require_text_account(value)
    if policy not in ("target-episode-novel-content/v1", "target-event-only/v1"):
        raise ValueError("unknown analysis policy")
    units = {u.id: u for u in account.units}
    all_rows = {u.id for u in account.units if u.kind == "row"}
    eligible = all_rows if eligible_rows is None else set(eligible_rows)
    if not eligible <= all_rows or (
        eligible_rows is not None and len(eligible) != len(eligible_rows)
    ):
        raise ValueError("eligible universe must contain distinct existing source rows")
    descendants: dict[str, set[str]] = {}
    for row in sorted(eligible):
        current: Unit | None = units[row]
        while current:
            descendants.setdefault(current.id, set()).add(row)
            current = units.get(current.parent or "")
    outcomes: dict[str, set[Coverage]] = {row: set() for row in eligible}
    contributors: dict[str, set[str]] = {row: set() for row in eligible}
    bindings = {(b.detail, b.reference.interpretation): b for b in account.bindings}
    recall: list[RecallRow] = []
    correspondence: list[CorrespondenceRow] = []
    inventories = {i.unit: i for i in account.inventories}
    detail_counts = Counter(d.unit for d in account.details)
    inventory_rows = tuple(
        InventoryRow(
            unit=u.id,
            status=inventories[u.id].status if u.id in inventories else "not-attempted",
            reason=inventories[u.id].reason if u.id in inventories else "no extraction record",
            detail_count=detail_counts[u.id],
        )
        for u in sorted(account.units, key=lambda u: u.id)
        if u.kind == "recall"
    )
    recall_documents = [d for d in account.documents if d.side == "recall"]
    # Completeness requires the exact document support, not merely the units supplied.
    # Gaps (even whitespace) conservatively withhold absence until intake covers them.
    complete_support = bool(recall_documents) and all(
        not d.text
        or contained_in(
            Support(
                document=d.id,
                spans=(Span(start=0, end=len(d.text.encode("utf-16-le")) // 2),),
            ),
            (text_support(u.support) for u in account.units if u.kind == "recall"),
        )
        for d in recall_documents
    )
    unavailable_global = not complete_support or any(
        i.status != "completed" for i in inventory_rows
    )
    for detail in sorted(account.details, key=lambda d: d.id):
        binding = bindings.get((detail.id, "referent"))
        reference = binding.reference if binding else None
        recall.append(
            RecallRow(
                detail=detail.id,
                unit=detail.unit,
                category=classify(detail, policy),
                address=detail.address,
                kind=detail.kind,
                reference_status=reference.status if reference else "not-attempted",
                reference_outcome=reference.outcome if reference else None,
            )
        )
        if (
            reference is None
            or reference.status != "attempted"
            or reference.outcome == "unresolved"
        ):
            unavailable_global = True
    for binding in account.bindings:
        reference = binding.reference
        assessments = {a.hypothesis: a for a in binding.assessments}
        for hypothesis in reference.hypotheses:
            assessment = assessments.get(hypothesis.id)
            for target in hypothesis.targets or (None,):
                correspondence.append(
                    CorrespondenceRow(
                        detail=binding.detail,
                        interpretation=reference.interpretation,
                        hypothesis=hypothesis.id,
                        meaning=hypothesis.kind,
                        alternative=reference.outcome == "alternative",
                        target=target,
                        external=hypothesis.external,
                        assessment_status=assessment.status if assessment else "pending",
                        fidelity=assessment.grade if assessment else None,
                    )
                )
                if target is None or reference.interpretation != "referent":
                    continue
                rows = descendants.get(target, set())
                if assessment and assessment.grade == "misread":
                    # ADR 0030 excludes this invalid fidelity judgment, not the atom.
                    continue
                state: Coverage | None
                if assessment is None or assessment.status != "completed":
                    state = "not-assessable"
                elif assessment.grade == "unverifiable":
                    state = "not-assessable"
                elif reference.outcome == "alternative" or hypothesis.kind == "broad":
                    state = "uncertain"
                elif units[target].kind != "row":
                    # Single at scene grain is broad at row grain.
                    state = "uncertain"
                elif assessment.grade == "supported":
                    state = "present"
                else:
                    state = None
                for row in rows:
                    contributors[row].add(binding.detail)
                    if state is not None:
                        outcomes[row].add(state)
    source: list[SourceRow] = []
    for row in sorted(eligible):
        states = outcomes[row]
        if unavailable_global:
            states.add("not-assessable")
        coverage: Coverage = next(
            (s for s in ("present", "not-assessable", "uncertain") if s in states), "absent"
        )
        source.append(
            SourceRow(
                source_unit=row,
                coverage=coverage,
                contributing_details=tuple(sorted(contributors[row])),
            )
        )
    counts = Counter(row.category for row in recall)
    denominator = counts["internal"] + counts["external"]
    correspondence.sort(key=lambda r: (r.detail, r.interpretation, r.hypothesis, r.target or ""))
    return Analysis(
        account_sha256=digest(account.model_dump(mode="json")),
        policy=policy,
        recall=tuple(recall),
        inventories=inventory_rows,
        correspondence=tuple(correspondence),
        source=tuple(source),
        counts=Counts(
            internal=counts["internal"],
            external=counts["external"],
            unresolved=counts["unresolved"],
            excluded=counts["excluded"],
            denominator=denominator,
            internal_fraction=counts["internal"] / denominator if denominator else None,
        ),
    )
