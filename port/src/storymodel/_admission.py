"""Shared extractive graph admission; quoted support is not semantic entailment."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from .model import (
    Context,
    Document,
    Entity,
    Event,
    Model,
    Name,
    Participation,
    Span,
    State,
    Support,
    Unit,
)

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


class StateProposal(EventProposal):
    """A contextual state preserves explicit unknown polarity and modality."""

    polarity: Literal["positive", "negative", "unknown"] = "unknown"
    modality: Literal[
        "asserted",
        "possible",
        "probable",
        "necessary",
        "intended",
        "desired",
        "counterfactual",
        "reported",
        "unknown",
    ] = "unknown"


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


class RichUnresolvedClaim(Model):
    """Rich construction abstains about new families without widening legacy wire contracts."""

    key: Name
    kind: Literal["entity", "event", "state", "circumstance", "detail", "context", "role"]
    reason: Name
    evidence: Quote


def _inside(child: Span, parent: Span) -> bool:
    return parent.start <= child.start and child.end <= parent.end


def _overlap(a: Span, b: Span) -> bool:
    return a.start < b.end and b.start < a.end


@dataclass(frozen=True)
class AdmittedGraph:
    contexts: tuple[Context, ...]
    entities: tuple[Entity, ...]
    events: tuple[Event, ...]
    accepted: dict[str, str]
    states: tuple[State, ...] = ()


def admit_graph(
    document: Document,
    unit: Unit,
    root: Context,
    *,
    context_proposals_input: tuple[ContextProposal, ...],
    entity_proposals_input: tuple[EntityProposal, ...],
    event_proposals_input: tuple[EventProposal, ...],
    unresolved: tuple[UnresolvedClaim | RichUnresolvedClaim, ...],
    extra_keys: tuple[str, ...] = (),
    state_proposals_input: tuple[StateProposal, ...] = (),
) -> AdmittedGraph:
    """Admit one row/unit's local graph with exact evidence and declared attribution scopes."""
    unit_span = unit.support.spans[0]

    def evidence(quote: Quote, parent: Span = unit_span) -> Support:
        if not _inside(quote.span, parent):
            raise ValueError("evidence lies outside its unit or owning claim")
        if quote.span.slice(document.text) != quote.text:
            raise ValueError("quoted evidence differs from canonical document")
        return Support(document=document.id, spans=(quote.span,))

    keys = [
        x if isinstance(x, str) else x.key
        for group in (
            context_proposals_input,
            entity_proposals_input,
            event_proposals_input,
            state_proposals_input,
            extra_keys,
            unresolved,
        )
        for x in group
    ]
    if ROOT in keys or len(set(keys)) != len(keys):
        raise ValueError("duplicate or reserved proposal key")
    for claim in unresolved:
        evidence(claim.evidence)
    context_proposals = {c.key: c for c in context_proposals_input}
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
                side=document.side,
                kind=c.kind,
                parent=contexts[c.parent].id,
                evidence=(cue,),
            )
    # A scope must be a tree: overlapping siblings or undeclared spatial nesting is ambiguous.
    ordered = sorted(context_proposals_input, key=lambda c: (c.scope.span.start, -c.scope.span.end))
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
        for e in entity_proposals_input
    }

    def situation(e: EventProposal) -> tuple[Support, tuple[Participation, ...]]:
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
        return support, tuple(participants)

    events: dict[str, Event] = {}
    for e in event_proposals_input:
        support, participants = situation(e)
        events[e.key] = Event(
            side=document.side,
            context=contexts[e.context].id,
            predicate=e.predicate.text,
            evidence=(support,),
            participants=participants,
        )
    states: dict[str, State] = {}
    for s in state_proposals_input:
        support, participants = situation(s)
        states[s.key] = State(
            side=document.side,
            context=contexts[s.context].id,
            predicate=s.predicate.text,
            evidence=(support,),
            participants=participants,
            polarity=s.polarity,
            modality=s.modality,
        )
    accepted = {
        k: v.id
        for group in (contexts, entities, events, states)
        for k, v in group.items()
        if k != ROOT
    }
    if len(set(accepted.values())) != len(accepted):
        raise ValueError("duplicate semantic proposal")
    return AdmittedGraph(
        tuple(c for k, c in contexts.items() if k != ROOT),
        tuple(entities.values()),
        tuple(events.values()),
        accepted,
        tuple(states.values()),
    )
