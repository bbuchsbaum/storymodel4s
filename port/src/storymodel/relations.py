"""Input-bound event relation admission and a small inspectable table.

This profile constructs the four existing Account relation kinds. It does not
equate them with Scala's full temporal algebra, entity relations or hierarchy.
"""

from __future__ import annotations

from hashlib import sha256
from typing import Literal, Self

from pydantic import Field, model_validator

from .model import (
    Account,
    Digest,
    Model,
    Name,
    Recipe,
    Relation,
    Span,
    Support,
    _validate_instance,
    validate_account,
)
from .serialization import canonical, digest, strict_json

RECIPE = "event-relation-admission/v1"


class RelationQuote(Model):
    """An exact UTF-16 quote in a named account document, including discontinuous evidence."""

    document: Name
    span: Span
    text: Name


class RelationProposal(Model):
    """One proposed event relationship; context is derived from the source event."""

    key: Name
    kind: Literal["part_of", "before", "causes", "references"]
    source: Name
    target: Name
    evidence: tuple[RelationQuote, ...] = Field(min_length=1)


class RelationProposals(Model):
    """A recorded batch bound to one exact graph snapshot and explicit processing status."""

    schema_version: Literal["storymodel.relation-proposals/v1"] = "storymodel.relation-proposals/v1"
    account_sha256: Digest
    status: Literal["completed", "search-limited", "not-attempted", "failed"]
    reason: str | None = None
    relations: tuple[RelationProposal, ...] = ()

    @model_validator(mode="after")
    def processing(self) -> Self:
        if self.status != "completed" and not self.reason:
            raise ValueError("incomplete relation processing requires a reason")
        if self.status in ("not-attempted", "failed") and self.relations:
            raise ValueError("unprocessed relation batch cannot contain claims")
        if len({r.key for r in self.relations}) != len(self.relations):
            raise ValueError("duplicate relation proposal key")
        return self


class RelationResult(Model):
    """Atomic batch outcome; a refused batch retains the original graph unchanged."""

    schema_version: Literal["storymodel.relation-result/v1"] = "storymodel.relation-result/v1"
    recipe: Literal["event-relation-admission/v1"] = "event-relation-admission/v1"
    account_sha256: Digest
    response_sha256: Digest
    status: Literal["admitted", "partial", "rejected", "not-attempted", "failed"]
    reason: str | None = None
    account: Account
    accepted: dict[str, str] = Field(default_factory=dict)


def admit_relations(value: Account, raw: str) -> RelationResult:
    """Admit all declared edges together, or refuse them without choosing a cycle winner.

    Malformed/foreign envelopes raise ValueError. Invalid graph claims return a rejected
    receipt with the original account. Quotes establish support identity, not causal or
    temporal entailment. Processing completeness never asserts absence of unproposed edges.
    """
    account = validate_account(value)
    account_sha = digest(account.model_dump(mode="json"))
    response_sha = sha256(raw.encode()).hexdigest()
    proposals = RelationProposals.model_validate_json(canonical(strict_json(raw)))
    if proposals.account_sha256 != account_sha:
        raise ValueError("relation proposals belong to another account")

    def result(
        status: Literal["admitted", "partial", "rejected", "not-attempted", "failed"],
        *,
        output: Account = account,
        accepted: dict[str, str] | None = None,
        reason: str | None = proposals.reason,
    ) -> RelationResult:
        return RelationResult(
            account_sha256=account_sha,
            response_sha256=response_sha,
            status=status,
            reason=reason,
            account=output,
            accepted=accepted or {},
        )

    if proposals.status in ("not-attempted", "failed"):
        return result(proposals.status)
    documents = {d.id: d for d in account.documents}
    events = {e.id: e for e in account.events}
    relations = list(account.relations)
    accepted = {}
    try:
        for proposal in proposals.relations:
            left, right = events.get(proposal.source), events.get(proposal.target)
            if left is None or right is None:
                raise ValueError(f"{proposal.key}: foreign event endpoint")
            evidence_by_document: dict[str, list[Span]] = {}
            for quote in proposal.evidence:
                document = documents.get(quote.document)
                if document is None or document.side != left.side:
                    raise ValueError(f"{proposal.key}: foreign evidence document or side")
                if quote.span.slice(document.text) != quote.text:
                    raise ValueError(f"{proposal.key}: quoted evidence differs from document")
                evidence_by_document.setdefault(document.id, []).append(quote.span)
            evidence = tuple(
                Support(document=doc, spans=tuple(sorted(spans, key=lambda s: (s.start, s.end))))
                for doc, spans in sorted(evidence_by_document.items())
            )
            relation = Relation(
                kind=proposal.kind,
                source=left.id,
                target=right.id,
                context=left.context,
                evidence=evidence,
            )
            relations.append(relation)
            accepted[proposal.key] = relation.id
        # Validate the union, including cycles involving previously admitted relations.
        output = validate_account(
            account.model_copy(
                update={
                    "relations": tuple(relations),
                    "recipe": Recipe(
                        name=RECIPE,
                        input_sha256=(
                            *(document.sha256 for document in account.documents),
                            account_sha,
                            response_sha,
                        ),
                    ),
                }
            )
        )
    except ValueError as error:
        return result("rejected", reason=str(error))
    return result(
        "partial" if proposals.status == "search-limited" else "admitted",
        output=output,
        accepted=accepted,
    )


def verify_relations(value: Account, raw: str, receipt: RelationResult) -> RelationResult:
    """Recompute both graph and processing decisions from independently supplied inputs."""
    checked = _validate_instance(RelationResult, receipt)
    expected = admit_relations(value, raw)
    if checked != expected:
        raise ValueError("relation result differs from recorded inputs")
    return expected


class RelationRow(Model):
    """A relation table retains occurrence IDs, context and exact evidence alongside labels."""

    relation: Name
    kind: Name
    side: Literal["source", "recall"]
    source_event: Name
    source_predicate: Name
    target_event: Name
    target_predicate: Name
    source_context: Name
    target_context: Name
    evidence: tuple[RelationQuote, ...]


def relation_table(value: Account) -> tuple[RelationRow, ...]:
    """Return Python-derived rows for notebooks or inspection; do not infer additional edges."""
    account = validate_account(value)
    events = {e.id: e for e in account.events}
    documents = {d.id: d for d in account.documents}
    return tuple(
        RelationRow(
            relation=r.id,
            kind=r.kind,
            side=events[r.source].side,
            source_event=r.source,
            source_predicate=events[r.source].predicate,
            target_event=r.target,
            target_predicate=events[r.target].predicate,
            source_context=events[r.source].context,
            target_context=events[r.target].context,
            evidence=tuple(
                RelationQuote(document=e.document, span=s, text=s.slice(documents[e.document].text))
                for e in r.evidence
                for s in e.spans
            ),
        )
        for r in account.relations
    )
