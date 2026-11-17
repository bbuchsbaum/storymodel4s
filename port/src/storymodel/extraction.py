"""Resolve bounded, extractive recall proposals into ordinary accounts.

Admission proves quoted support and consistency with declared scopes, not semantic
entailment. Each unit is atomic; failed and missing extraction are never empty success.
"""

from __future__ import annotations

from hashlib import sha256
from typing import Literal, Self

from pydantic import Field, model_validator

from .model import (
    Account,
    Context,
    Detail,
    Digest,
    Entity,
    Event,
    Inventory,
    Model,
    Name,
    Participation,
    Recipe,
    Span,
    Support,
    Unit,
    canonical,
    digest,
    validate_account,
)
from .recall_intake import PreparedRecall, prepare_recall_utf8, validate_prepared_recall
from .serialization import strict_json

RECIPE: Literal["recall-extractive-admission/v1"] = "recall-extractive-admission/v1"
ROOT = "narrated"


class Quote(Model):
    """Exact canonical UTF-16 evidence; the resolver checks the text independently."""

    span: Span
    text: Name


class ContextProposal(Model):
    """Attribution evidence and content scope are different supports."""

    key: Name
    kind: Literal["reported", "believed", "intended"]
    parent: Name
    cue: Quote
    scope: Quote


class EntityProposal(Model):
    """One local mention; no source identity or cross-unit coreference is asserted."""

    key: Name
    mention: Quote


class RoleProposal(Model):
    """A proposed typed role of a local entity in an event."""

    entity: Name
    role: Literal["agent", "patient", "speaker", "recipient", "experiencer", "location"]
    evidence: Quote


class EventProposal(Model):
    """An extractive predicate in an explicitly named context."""

    key: Name
    context: Name
    evidence: Quote
    predicate: Quote
    participants: tuple[RoleProposal, ...] = ()


class DetailProposal(Model):
    """An expressed detail, attached to an event without a source-address judgment."""

    key: Name
    event: Name
    kind: Literal[
        "event",
        "participant",
        "attribute",
        "temporal",
        "spatial",
        "perceptual",
        "dialogue",
        "mental",
        "relation",
        "knowledge",
        "discourse",
    ]
    evidence: Quote


class UnresolvedClaim(Model):
    """A provider abstention retained outside the accepted graph."""

    key: Name
    kind: Literal["entity", "event", "detail", "context", "role"]
    reason: Name
    evidence: Quote


class UnitProposal(Model):
    """One atomic unit proposal, with explicit processing completeness."""

    u: int = Field(ge=1)
    status: Literal["completed", "not-attempted", "failed", "search-limited"]
    reason: str | None = None
    contexts: tuple[ContextProposal, ...] = ()
    entities: tuple[EntityProposal, ...] = ()
    events: tuple[EventProposal, ...] = ()
    details: tuple[DetailProposal, ...] = ()
    unresolved: tuple[UnresolvedClaim, ...] = ()

    @model_validator(mode="after")
    def completeness(self) -> Self:
        if self.status != "completed" and not self.reason:
            raise ValueError("incomplete proposal requires a reason")
        if self.status in ("failed", "not-attempted") and any(
            (self.contexts, self.entities, self.events, self.details, self.unresolved)
        ):
            raise ValueError("unprocessed proposal cannot supply claims")
        return self


class ExtractionProposal(Model):
    """Strict recorded response; unknown fields and ambiguous unit identities refuse."""

    schema_version: Literal["storymodel.extraction-proposal/v1"] = (
        "storymodel.extraction-proposal/v1"
    )
    units: tuple[UnitProposal, ...]


class ExtractionRequest(Model):
    """Independent recall intake and an optional immutable source-only account."""

    schema_version: Literal["storymodel.extraction-request/v1"] = "storymodel.extraction-request/v1"
    recipe: Literal["recall-extractive-admission/v1"] = RECIPE
    recall: PreparedRecall
    source: Account | None = None
    export_permission: Literal["allowed", "denied"] = "denied"

    @model_validator(mode="after")
    def inputs(self) -> Self:
        validate_prepared_recall(self.recall)
        if self.source is not None:
            source = validate_account(self.source)
            if (
                any(d.side != "source" for d in source.documents)
                or any(u.kind == "recall" for u in source.units)
                or any(e.side != "source" for e in source.events)
                or any(c.side == "recall" and c.kind != "narrated" for c in source.contexts)
                or source.details
                or source.inventories
                or source.bindings
            ):
                raise ValueError("extraction source must be a source-only account")
            if self.export_permission == "allowed" and source.export_permission != "allowed":
                raise ValueError("extraction cannot elevate source export permission")
        return self


def validate_request(value: ExtractionRequest | str | bytes) -> ExtractionRequest:
    """Recheck copied Python values and duplicate/nonfinite JSON before consumption."""
    raw = (
        canonical(value.model_dump(mode="json")) if isinstance(value, ExtractionRequest) else value
    )
    return ExtractionRequest.model_validate_json(canonical(strict_json(raw)))


def request_digest(value: ExtractionRequest) -> str:
    return digest(validate_request(value).model_dump(mode="json"))


def prepare_extraction(
    key: str,
    raw: bytes,
    *,
    source: Account | None = None,
    export_permission: Literal["allowed", "denied"] = "denied",
) -> ExtractionRequest:
    """Prepare raw recall without inventing a source account or running a model."""
    return validate_request(
        ExtractionRequest(
            recall=prepare_recall_utf8(key, raw),
            source=source,
            export_permission=export_permission,
        )
    )


class UnitResolution(Model):
    """A unit's admission result; keys link recorded proposals to accepted IDs."""

    u: int
    unit: str
    status: Literal["admitted", "partial", "rejected", "not-attempted", "failed"]
    reason: str | None = None
    accepted: dict[str, str] = Field(default_factory=dict)
    unresolved: tuple[UnresolvedClaim, ...] = ()


class ExtractionResult(Model):
    """Inspectable account plus resolution evidence; raw proposals stay in the recording."""

    schema_version: Literal["storymodel.extraction-result/v1"] = "storymodel.extraction-result/v1"
    recipe: str = RECIPE
    request_sha256: Digest
    response_sha256: Digest
    account: Account
    resolutions: tuple[UnitResolution, ...]


def _inside(child: Span, parent: Span) -> bool:
    return parent.start <= child.start and child.end <= parent.end


def _overlap(a: Span, b: Span) -> bool:
    return a.start < b.end and b.start < a.end


def _resolve_unit(
    proposal: UnitProposal,
    request: ExtractionRequest,
    unit: Unit,
    root: Context,
) -> tuple[
    tuple[Context, ...], tuple[Entity, ...], tuple[Event, ...], tuple[Detail, ...], dict[str, str]
]:
    document = request.recall.document
    unit_span = unit.support.spans[0]

    def evidence(quote: Quote, parent: Span = unit_span) -> Support:
        if not _inside(quote.span, parent):
            raise ValueError("evidence lies outside its unit or owning claim")
        if quote.span.slice(document.text) != quote.text:
            raise ValueError("quoted evidence differs from canonical recall")
        return Support(document=document.id, spans=(quote.span,))

    keys = [
        x.key
        for group in (
            proposal.contexts,
            proposal.entities,
            proposal.events,
            proposal.details,
            proposal.unresolved,
        )
        for x in group
    ]
    if ROOT in keys or len(set(keys)) != len(keys):
        raise ValueError("duplicate or reserved proposal key")
    for claim in proposal.unresolved:
        evidence(claim.evidence)
    context_proposals = {c.key: c for c in proposal.contexts}
    contexts = {ROOT: root}
    pending = dict(context_proposals)
    while pending:
        ready = sorted(k for k, c in pending.items() if c.parent in contexts)
        if not ready:
            raise ValueError("cyclic or unknown context parent")
        for key in ready:
            c = pending.pop(key)
            cue = evidence(c.cue)
            evidence(c.scope)
            if c.parent != ROOT and (
                not _inside(c.scope.span, context_proposals[c.parent].scope.span)
                or not _inside(c.cue.span, context_proposals[c.parent].scope.span)
                or c.scope.span == context_proposals[c.parent].scope.span
            ):
                raise ValueError("nested context must have a strictly narrower scope within parent")
            contexts[key] = Context(
                side="recall",
                kind=c.kind,
                parent=contexts[c.parent].id,
                evidence=(cue,),
            )
    # A scope must be a tree: overlapping siblings or undeclared spatial nesting is ambiguous.
    ordered = sorted(proposal.contexts, key=lambda c: (c.scope.span.start, -c.scope.span.end))
    stack: list[ContextProposal] = []
    for c in ordered:
        while stack and stack[-1].scope.span.end <= c.scope.span.start:
            stack.pop()
        if c.parent != (stack[-1].key if stack else ROOT):
            raise ValueError("overlapping or inconsistently nested context scopes")
        if stack and not _inside(c.scope.span, stack[-1].scope.span):
            raise ValueError("partially overlapping context scopes")
        stack.append(c)

    def context_for(span: Span) -> str:
        matches = []
        for c in ordered:
            if _overlap(span, c.scope.span):
                if not _inside(span, c.scope.span):
                    raise ValueError("claim crosses an attributed scope boundary")
                matches.append(c)
        return matches[-1].key if matches else ROOT

    entities = {
        e.key: Entity(
            source_document=document.id, label=e.mention.text, evidence=(evidence(e.mention),)
        )
        for e in proposal.entities
    }
    events: dict[str, Event] = {}
    event_proposals = {e.key: e for e in proposal.events}
    for e in proposal.events:
        support = evidence(e.evidence)
        evidence(e.predicate, e.evidence.span)
        if e.context not in contexts or context_for(e.evidence.span) != e.context:
            raise ValueError("event context differs from its declared content scope")
        participants: list[Participation] = []
        for p in e.participants:
            if p.entity not in entities:
                raise ValueError("unknown local entity reference")
            role_support = evidence(p.evidence, e.evidence.span)
            # This profile supports mention-local roles only, not asserted coreference.
            if role_support != entities[p.entity].evidence[0]:
                raise ValueError("role evidence must be the referenced local entity mention")
            participants.append(
                Participation(entity=entities[p.entity].id, role=p.role, evidence=(role_support,))
            )
        events[e.key] = Event(
            side="recall",
            context=contexts[e.context].id,
            predicate=e.predicate.text,
            evidence=(support,),
            participants=tuple(participants),
        )
    details: dict[str, Detail] = {}
    for d in proposal.details:
        if d.event not in events:
            raise ValueError("unknown local event reference")
        support = evidence(d.evidence, event_proposals[d.event].evidence.span)
        details[d.key] = Detail(
            unit=unit.id,
            kind=d.kind,
            content=d.evidence.text,
            support=support,
            address="unresolved",
            event=events[d.event].id,
        )
    accepted = {
        k: v.id
        for group in (contexts, entities, events, details)
        for k, v in group.items()
        if k != ROOT
    }
    # Equal content under two temporary keys is not two accepted occurrences.
    if len(set(accepted.values())) != len(accepted):
        raise ValueError("duplicate semantic proposal")
    Account(
        schema_version="storymodel.account/v2",
        documents=(document,),
        units=(unit,),
        contexts=(Context(side="source", kind="narrated"), *contexts.values()),
        entities=tuple(entities.values()),
        events=tuple(events.values()),
        details=tuple(details.values()),
        recipe=Recipe(name=RECIPE, input_sha256=(document.sha256,)),
    )
    return (
        tuple(c for k, c in contexts.items() if k != ROOT),
        tuple(entities.values()),
        tuple(events.values()),
        tuple(details.values()),
        accepted,
    )


def resolve_extraction(value: ExtractionRequest, raw: str) -> ExtractionResult:
    """Resolve one recorded response, without provider calls or source matching.

    Bad framing rejects the response; malformed/invalid unit payloads fail only that
    unit. All raw data remains receipted by its hash and in the caller's recording.
    """
    request = validate_request(value)
    request_sha = digest(request.model_dump(mode="json"))
    parsed = strict_json(raw)
    if (
        not isinstance(parsed, dict)
        or set(parsed) != {"schema_version", "units"}
        or (
            parsed["schema_version"] != "storymodel.extraction-proposal/v1"
            or not isinstance(parsed["units"], list)
        )
    ):
        raise ValueError("invalid extraction response envelope")
    supplied: dict[int, object] = {}
    for item in parsed["units"]:
        u = item.get("u") if isinstance(item, dict) else None
        if type(u) is not int or not 1 <= u <= len(request.recall.units) or u in supplied:
            raise ValueError("duplicate, foreign or invalid proposal unit")
        supplied[u] = item
    source = request.source
    root = Context(side="recall", kind="narrated")
    contexts = list(source.contexts if source else ())
    if not any(c.side == "recall" for c in contexts):
        contexts.append(root)
    entities = list(source.entities if source else ())
    events = list(source.events if source else ())
    details: list[Detail] = []
    units = list(source.units if source else ())
    inventories: list[Inventory] = []
    resolutions: list[UnitResolution] = []
    for occurrence in request.recall.units:
        reason: str | None
        unit = Unit(
            kind="recall",
            support=Support(document=request.recall.document.id, spans=(occurrence.support,)),
        )
        units.append(unit)
        if occurrence.u not in supplied:
            reason = "no proposal for this unit"
            inventories.append(Inventory(unit=unit.id, status="not-attempted", reason=reason))
            resolutions.append(
                UnitResolution(u=occurrence.u, unit=unit.id, status="not-attempted", reason=reason)
            )
            continue
        try:
            proposal = UnitProposal.model_validate_json(canonical(supplied[occurrence.u]))
            cs, es, evs, ds, ids = _resolve_unit(proposal, request, unit, root)
        except ValueError as error:
            reason = str(error)
            inventories.append(Inventory(unit=unit.id, status="failed", reason=reason))
            resolutions.append(
                UnitResolution(u=occurrence.u, unit=unit.id, status="rejected", reason=reason)
            )
            continue
        contexts.extend(cs)
        entities.extend(es)
        events.extend(evs)
        details.extend(ds)
        status = "search-limited" if proposal.unresolved else proposal.status
        reason = (
            (proposal.reason or "unresolved extraction claims remain")
            if proposal.unresolved
            else proposal.reason
        )
        inventories.append(Inventory(unit=unit.id, status=status, reason=reason))
        resolution_status: Literal["admitted", "partial", "failed", "not-attempted"] = (
            "partial"
            if status == "search-limited"
            else "admitted"
            if status == "completed"
            else status
        )
        resolutions.append(
            UnitResolution(
                u=occurrence.u,
                unit=unit.id,
                status=resolution_status,
                reason=reason,
                accepted=ids,
                unresolved=proposal.unresolved,
            )
        )
    inputs = [
        request.recall.document.sha256,
        request_sha,
        sha256(raw.encode()).hexdigest(),
    ]
    if source:
        inputs.extend(d.sha256 for d in source.documents)
        inputs.append(digest(source.model_dump(mode="json")))
    account = Account(
        schema_version=(
            "storymodel.account/v2"
            if source and source.schema_version != "storymodel.account/v3"
            else "storymodel.account/v3"
        ),
        documents=(*source.documents, request.recall.document)
        if source
        else (request.recall.document,),
        units=tuple(units),
        contexts=tuple(contexts),
        entities=tuple(entities),
        events=tuple(events),
        relations=source.relations if source else (),
        details=tuple(details),
        inventories=tuple(inventories),
        recipe=Recipe(name=RECIPE, input_sha256=tuple(dict.fromkeys(inputs))),
        export_permission=request.export_permission,
    )
    return ExtractionResult(
        request_sha256=request_sha,
        response_sha256=sha256(raw.encode()).hexdigest(),
        account=account,
        resolutions=tuple(resolutions),
    )
