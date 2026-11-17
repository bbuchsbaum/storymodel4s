"""Resolve context holders from recorded candidates without rewriting context placement."""

from hashlib import sha256
from typing import Literal, Self

from pydantic import model_validator

from .identity import IdentityResult, MentionIdentity, resolve_text_identity
from .model import (
    Context,
    Digest,
    Evidence,
    Model,
    Name,
    Participation,
    State,
    Support,
    _validate_instance,
    contained_in,
)
from .rich_account import SupportedAccount, validate_supported_account
from .serialization import canonical, digest, strict_json


class HolderProposal(Model):
    """Candidate mention occurrences are interpretations, never caller-assigned referent IDs."""

    context: Name
    status: Literal["completed", "not-attempted", "failed", "unsupported"]
    candidates: tuple[Name, ...] = ()
    evidence: tuple[Support, ...] = ()
    reason: Name | None = None

    @model_validator(mode="after")
    def shape(self) -> Self:
        if len(set(self.candidates)) != len(self.candidates):
            raise ValueError("duplicate holder candidate")
        if self.status == "completed":
            if not self.evidence or self.reason is not None:
                raise ValueError("completed attribution requires evidence, not a processing reason")
        elif self.reason is None or self.candidates or self.evidence:
            raise ValueError("unprocessed attribution requires only a reason")
        return self


class HolderProposals(Model):
    """Recorded attribution interpretations bound to one complete account snapshot."""

    schema_version: Literal["storymodel.holder-proposals/v1"] = "storymodel.holder-proposals/v1"
    account_sha256: Digest
    origin: Name
    contexts: tuple[HolderProposal, ...]

    @model_validator(mode="after")
    def unique(self) -> Self:
        if len({p.context for p in self.contexts}) != len(self.contexts):
            raise ValueError("duplicate context attribution")
        return self


class ContextHolder(Model):
    """One holder question separates absent identity, processing gaps and inapplicability."""

    context: Name
    status: Literal[
        "named", "unattributed", "not-attempted", "failed", "unsupported", "not-applicable"
    ]
    referent: Name | None = None
    gap: Literal["no-candidate", "several-candidates", "unresolved-candidate"] | None = None
    candidates: tuple[MentionIdentity, ...] = ()
    evidence: tuple[Support, ...] = ()
    reason: Name | None = None

    @model_validator(mode="after")
    def shape(self) -> Self:
        if (self.status == "named") != (self.referent is not None):
            raise ValueError("only a named holder has a referent")
        if (self.status == "unattributed") != (self.gap is not None):
            raise ValueError("only an unattributed holder has a derivation gap")
        if self.status in ("named", "unattributed"):
            if not self.evidence or self.reason is not None:
                raise ValueError("derived holder requires evidence, not a processing reason")
        elif self.evidence or self.candidates:
            raise ValueError("unprocessed or inapplicable holder cannot carry accepted evidence")
        if (self.status in ("not-attempted", "failed", "unsupported")) != (self.reason is not None):
            raise ValueError("processing outcomes require exactly a reason")
        return self


class AttributionResult(Model):
    """Full holder accounting retains the original verified identity result and input receipts."""

    schema_version: Literal["storymodel.attribution-result/v1"] = "storymodel.attribution-result/v1"
    recipe: Literal["recorded-context-holders/v1"] = "recorded-context-holders/v1"
    account_sha256: Digest
    proposals_sha256: Digest
    origin: Name
    export_permission: Literal["allowed", "denied"]
    identity: IdentityResult
    holders: tuple[ContextHolder, ...]
    interpretation: Literal["conditional-on-recorded-attribution-and-identity"] = (
        "conditional-on-recorded-attribution-and-identity"
    )


def resolve_context_holders(
    value: SupportedAccount, identity_annotations: str, proposals: str
) -> AttributionResult:
    """Collapse all candidate mentions by verified identity; one open candidate forces abstention.

    This text profile accepts proposals for reported, believed and intended contexts. It
    validates evidence ownership, not semantic entailment or automatic speaker discovery.
    Invalid proposals refuse the batch; omitted contexts remain explicitly unattempted.
    """
    account = validate_supported_account(value)
    account_sha = digest(account.model_dump(mode="json"))
    supplied = HolderProposals.model_validate_json(canonical(strict_json(proposals)))
    if supplied.account_sha256 != account_sha:
        raise ValueError("holder proposals belong to another account snapshot")
    contexts = {c.id: c for c in account.contexts}
    entities = {e.id: e for e in account.entities}
    documents = {d.id: d for d in account.documents}
    by_context = {p.context: p for p in supplied.contexts}
    for supplied_proposal in supplied.contexts:
        context = contexts.get(supplied_proposal.context)
        if context is None or context.kind == "narrated":
            raise ValueError("holder proposal requires an existing holder-bearing context")
        if supplied_proposal.status != "completed":
            continue
        context_documents = {support.document for support in context.evidence}
        if len(context_documents) != 1:
            raise ValueError("text attribution requires a context in exactly one document")
        document = documents[next(iter(context_documents))]
        for support in supplied_proposal.evidence:
            if not contained_in(support, context.evidence):
                raise ValueError("attribution evidence lies outside its context evidence")
            for span in support.spans:
                span.slice(document.text)
        for candidate in supplied_proposal.candidates:
            entity = entities.get(candidate)
            if entity is None or entity.origin_document != document.id:
                raise ValueError("holder candidate must be a mention in the context document")

    identity = resolve_text_identity(account, identity_annotations)
    decisions = {m.mention: m for m in identity.mentions}
    holders: list[ContextHolder] = []
    for context in sorted(account.contexts, key=lambda c: c.id):
        proposal = by_context.get(context.id)
        if context.kind == "narrated":
            holders.append(ContextHolder(context=context.id, status="not-applicable"))
        elif proposal is None:
            holders.append(
                ContextHolder(
                    context=context.id,
                    status="not-attempted",
                    reason="No attribution proposal supplied",
                )
            )
        elif proposal.status != "completed":
            holders.append(
                ContextHolder(context=context.id, status=proposal.status, reason=proposal.reason)
            )
        else:
            candidates = tuple(decisions[m] for m in sorted(proposal.candidates))
            referents = {m.referent for m in candidates if m.referent is not None}
            # Do not let a readable candidate hide an unreadable competing candidate.
            gap: Literal["no-candidate", "several-candidates", "unresolved-candidate"] | None
            gap = (
                "no-candidate"
                if not candidates
                else "unresolved-candidate"
                if any(m.referent is None for m in candidates)
                else "several-candidates"
                if len(referents) != 1
                else None
            )
            holders.append(
                ContextHolder(
                    context=context.id,
                    status="unattributed" if gap is not None else "named",
                    referent=next(iter(referents)) if gap is None else None,
                    gap=gap,
                    candidates=candidates,
                    evidence=proposal.evidence,
                )
            )
    return AttributionResult(
        account_sha256=account_sha,
        proposals_sha256=sha256(proposals.encode("utf-8")).hexdigest(),
        origin=supplied.origin,
        export_permission=account.export_permission,
        identity=identity,
        holders=tuple(holders),
    )


def verify_attribution(
    value: SupportedAccount,
    identity_annotations: str,
    proposals: str,
    result: AttributionResult,
) -> AttributionResult:
    """Recompute against both original input recordings before consuming a saved result."""
    checked = _validate_instance(AttributionResult, result)
    expected = resolve_context_holders(value, identity_annotations, proposals)
    if checked != expected:
        raise ValueError("attribution result differs from recorded inputs")
    return expected


class AttributedSituation(Model):
    """Situation inspection preserves placement, role occurrences and the holder's derivation."""

    situation: Name
    kind: Literal["event", "state"]
    predicate: Name
    context: Context
    holder: ContextHolder
    participants: tuple[Participation, ...]
    evidence: Evidence


def attribution_table(
    value: SupportedAccount,
    identity_annotations: str,
    proposals: str,
    result: AttributionResult,
) -> tuple[AttributedSituation, ...]:
    """Inspect every event/state with its verified holder without promoting held claims."""
    account = validate_supported_account(value)
    checked = verify_attribution(account, identity_annotations, proposals, result)
    contexts = {c.id: c for c in account.contexts}
    holders = {h.context: h for h in checked.holders}
    return tuple(
        AttributedSituation(
            situation=s.id,
            kind="state" if isinstance(s, State) else "event",
            predicate=s.predicate,
            context=contexts[s.context],
            holder=holders[s.context],
            participants=s.participants,
            evidence=s.evidence,
        )
        for s in account._situations()
    )
