"""Recorded temporal proposals, checked account claims and context-specific interval queries."""

from hashlib import sha256
from typing import Literal, Self

from pydantic import Field, model_validator

from .chronology import (
    RELATIONS,
    EpistemicStatus,
    TemporalClaim,
    TemporalPolicy,
    TemporalRelation,
    TemporalRow,
    _within,
    interval_consistent,
    temporal_rows,
)
from .model import AnySupport, Digest, Model, Name, Recipe, Support, _validate_instance
from .playback import PlaybackSupport
from .relations import RelationQuote
from .rich_account import (
    RichAccount,
    SupportedAccount,
    upgrade_temporal_account,
    validate_rich_account,
    validate_supported_account,
)
from .serialization import canonical, digest, strict_json


class TemporalProposal(Model):
    """A proposed direction and scope; exact quotes or native evidence retain their coordinates."""

    key: Name
    source: Name
    target: Name
    relation: TemporalRelation
    context: Name
    status: EpistemicStatus
    evidence: tuple[RelationQuote | PlaybackSupport, ...] = ()
    premises: tuple[Name, ...] = ()


class TemporalProposals(Model):
    """Input-bound batch processing status is separate from each claim's epistemic status."""

    schema_version: Literal["storymodel.temporal-proposals/v1"] = "storymodel.temporal-proposals/v1"
    account_sha256: Digest
    origin: Name
    procedure: Name
    status: Literal["completed", "search-limited", "not-attempted", "failed"]
    reason: Name | None = None
    relations: tuple[TemporalProposal, ...] = ()

    @model_validator(mode="after")
    def processing(self) -> Self:
        if self.status != "completed" and self.reason is None:
            raise ValueError("incomplete temporal processing requires a reason")
        if self.status in ("not-attempted", "failed") and self.relations:
            raise ValueError("unprocessed temporal batch cannot contain claims")
        if len({r.key for r in self.relations}) != len(self.relations):
            raise ValueError("duplicate temporal proposal key")
        return self


class TemporalResult(Model):
    """An atomic replayable admission receipt preserves rejected input accounts unchanged."""

    schema_version: Literal["storymodel.temporal-result/v1"] = "storymodel.temporal-result/v1"
    recipe: Literal["temporal-claim-admission/v1"] = "temporal-claim-admission/v1"
    account_sha256: Digest
    response_sha256: Digest
    adjudicator: Name | None = None
    status: Literal["admitted", "partial", "rejected", "not-attempted", "failed"]
    reason: str | None = None
    account: RichAccount
    accepted: dict[Name, Name] = Field(default_factory=dict)


def admit_temporal_relations(
    value: RichAccount, raw: str, *, adjudicator: str | None = None
) -> TemporalResult:
    """Admit the entire batch against existing claims; a provider cannot name its own adjudicator.

    The optional adjudicator is supplied separately by the caller and recorded, not verified
    by an authentication service. It is required for a human-adjudicated claim. No status
    certifies semantic truth merely because evidence coordinates and consistency are valid.
    """
    account = validate_rich_account(value)
    proposals = TemporalProposals.model_validate_json(canonical(strict_json(raw)))
    account_sha = digest(account.model_dump(mode="json"))
    if proposals.account_sha256 != account_sha:
        raise ValueError("temporal proposals belong to another account")
    if adjudicator is not None and (not isinstance(adjudicator, str) or not adjudicator):
        raise ValueError("adjudicator must be a nonempty name or None")
    response_sha = sha256(raw.encode("utf-8")).hexdigest()

    def result(
        status: Literal["admitted", "partial", "rejected", "not-attempted", "failed"],
        output: RichAccount = account,
        accepted: dict[str, str] | None = None,
        reason: str | None = proposals.reason,
    ) -> TemporalResult:
        return TemporalResult(
            account_sha256=account_sha,
            response_sha256=response_sha,
            adjudicator=adjudicator,
            status=status,
            reason=reason,
            account=output,
            accepted={} if accepted is None else accepted,
        )

    if proposals.status in ("not-attempted", "failed"):
        return result(proposals.status)
    docs = {d.id: d for d in account.documents}
    claims = list(account.temporal_relations)
    accepted = {}
    try:
        for proposal in proposals.relations:
            evidence: list[AnySupport] = []
            for item in proposal.evidence:
                if isinstance(item, PlaybackSupport):
                    evidence.append(item)
                else:
                    document = docs.get(item.document)
                    if document is None or item.span.slice(document.text) != item.text:
                        raise ValueError("temporal quote differs from its actual document")
                    evidence.append(Support(document=item.document, spans=(item.span,)))
            claim = TemporalClaim.create(
                source=proposal.source,
                target=proposal.target,
                relation=proposal.relation,
                context=proposal.context,
                status=proposal.status,
                evidence=tuple(evidence),
                premises=proposal.premises,
                origin=proposals.origin,
                procedure=proposals.procedure,
                adjudicator=adjudicator if proposal.status == "human-adjudicated" else None,
            )
            claims.append(claim)
            accepted[proposal.key] = claim.id
        upgraded = upgrade_temporal_account(account)
        output = validate_rich_account(
            upgraded.model_copy(
                update={
                    "temporal_relations": tuple(claims),
                    "recipe": Recipe(
                        name="temporal-claim-admission/v1",
                        input_sha256=(
                            *(d.sha256 for d in account.documents),
                            *(m.sha256 for m in account.media_sources),
                            account_sha,
                            response_sha,
                            digest({"adjudicator": adjudicator}),
                        ),
                    ),
                }
            )
        )
    except ValueError as error:
        return result("rejected", reason=str(error))
    return result(
        "partial" if proposals.status == "search-limited" else "admitted", output, accepted
    )


def verify_temporal_relations(
    account: RichAccount, raw: str, result: TemporalResult, *, adjudicator: str | None = None
) -> TemporalResult:
    """Recompute claims and processing decisions from original inputs and caller review metadata."""
    actual = _validate_instance(TemporalResult, result)
    expected = admit_temporal_relations(account, raw, adjudicator=adjudicator)
    if actual != expected:
        raise ValueError("temporal admission differs from recorded inputs")
    return expected


def temporal_table(
    value: SupportedAccount, context: str | None = None, *, policy: TemporalPolicy | None = None
) -> tuple[TemporalRow, ...]:
    """Inspect only the exact requested context; child or parent chronology is never inherited."""
    account = validate_supported_account(value)
    selected = _validate_instance(TemporalPolicy, TemporalPolicy() if policy is None else policy)
    if context is not None and (
        not isinstance(context, str) or context not in {c.id for c in account.contexts}
    ):
        raise ValueError("temporal table context is outside the account")
    claims = account.temporal_relations if isinstance(account, RichAccount) else ()
    rows = temporal_rows(account, claims)
    eligible = {
        row.id
        for row in rows
        if (row.status is None and selected.include_legacy) or row.status in selected.statuses
    }
    # A selected conclusion remains conditional on its upstream temporal assertions.
    # Propagate exclusions through the premise DAG without recursion or all-pairs work.
    dependents: dict[str, list[str]] = {}
    for row in rows:
        for premise in row.premises:
            dependents.setdefault(premise, []).append(row.id)
    excluded = [row.id for row in rows if row.id not in eligible]
    while excluded:
        for dependent in dependents.get(excluded.pop(), ()):
            if dependent in eligible:
                eligible.remove(dependent)
                excluded.append(dependent)
    return tuple(
        row for row in rows if row.id in eligible and (context is None or row.context == context)
    )


class TemporalQuery(Model):
    """Feasible pair relations conditional on assertions, never measured occurrence times."""

    schema_version: Literal["storymodel.temporal-query/v1"] = "storymodel.temporal-query/v1"
    interpretation: Literal["interval-consistency-not-measured-time"] = (
        "interval-consistency-not-measured-time"
    )
    account_sha256: Digest
    source: Name
    target: Name
    context: Name
    policy: TemporalPolicy
    possible: tuple[TemporalRelation, ...]
    direct_claims: tuple[Name, ...]
    constraint_claims: tuple[Name, ...]


def query_temporal_relation(
    value: SupportedAccount,
    source: str,
    target: str,
    context: str,
    *,
    policy: TemporalPolicy | None = None,
) -> TemporalQuery:
    """Check thirteen atomic relations on demand using sparse endpoint feasibility."""
    account = validate_supported_account(value)
    selected = _validate_instance(TemporalPolicy, TemporalPolicy() if policy is None else policy)
    situations = {s.id: s for s in account._situations()}
    contexts = {c.id: c for c in account.contexts}
    if any(not isinstance(v, str) for v in (source, target, context)):
        raise ValueError("temporal query requires actual situation/context IDs")
    left, right, frame = situations.get(source), situations.get(target), contexts.get(context)
    if left is None or right is None or frame is None or source == target:
        raise ValueError("temporal query requires distinct existing situations and context")
    if (
        left.side != right.side
        or frame.side != left.side
        or not (
            _within(contexts, context, left.context) and _within(contexts, context, right.context)
        )
    ):
        raise ValueError("temporal query context is not within both same-side endpoint contexts")
    rows = temporal_table(account, context, policy=selected)
    edges = tuple((r.source, r.target, r.relation) for r in rows)
    possible = tuple(
        relation
        for relation in RELATIONS
        if interval_consistent((*edges, (source, target, relation)))
    )
    return TemporalQuery(
        account_sha256=digest(account.model_dump(mode="json")),
        source=source,
        target=target,
        context=context,
        policy=selected,
        possible=possible,
        direct_claims=tuple(r.id for r in rows if {r.source, r.target} == {source, target}),
        constraint_claims=tuple(r.id for r in rows),
    )


def verify_temporal_query(
    account: SupportedAccount,
    source: str,
    target: str,
    context: str,
    result: TemporalQuery,
    *,
    policy: TemporalPolicy | None = None,
) -> TemporalQuery:
    """Recompute saved conditional possibilities against the actual account and query policy."""
    actual = _validate_instance(TemporalQuery, result)
    expected = query_temporal_relation(account, source, target, context, policy=policy)
    if actual != expected:
        raise ValueError("temporal query differs from account or policy")
    return expected
