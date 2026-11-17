"""Input-bound situation relation admission and inspectable tables.

This profile constructs the four existing Account relation kinds. It does not
equate them with Scala's full temporal algebra, entity relations or hierarchy.
"""

from __future__ import annotations

from hashlib import sha256
from typing import Literal, Self, overload

from pydantic import Field, model_validator

from .model import (
    Account,
    Digest,
    Model,
    Name,
    Recipe,
    Relation,
    Span,
    State,
    Support,
    _validate_instance,
    text_evidence,
)
from .rich_account import (
    RichAccount,
    SupportedAccount,
    require_text_account,
    validate_supported_account,
)
from .serialization import canonical, digest, strict_json

RECIPE = "event-relation-admission/v1"
RICH_RECIPE = "situation-relation-admission/v1"


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


class RichRelationProposal(RelationProposal):
    """One proposed event or state relationship; context comes from the source situation."""


class _ProposalFields(Model):
    """Shared batch accounting; public versions pin their endpoint vocabulary."""

    schema_version: str
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


class RelationProposals(_ProposalFields):
    """A recorded batch bound to one exact graph snapshot and explicit processing status."""

    schema_version: Literal["storymodel.relation-proposals/v1"] = "storymodel.relation-proposals/v1"


class RichRelationProposals(_ProposalFields):
    """A v2 batch addresses events and states in one exact rich account snapshot."""

    schema_version: Literal["storymodel.relation-proposals/v2"] = "storymodel.relation-proposals/v2"
    relations: tuple[RichRelationProposal, ...] = ()


class _ResultFields(Model):
    """Shared atomic receipt fields, with the account shape pinned by each public version."""

    schema_version: str
    recipe: str
    account_sha256: Digest
    response_sha256: Digest
    status: Literal["admitted", "partial", "rejected", "not-attempted", "failed"]
    reason: str | None = None
    account: SupportedAccount
    accepted: dict[str, str] = Field(default_factory=dict)


class RelationResult(_ResultFields):
    """Atomic batch outcome; a refused batch retains the original graph unchanged."""

    schema_version: Literal["storymodel.relation-result/v1"] = "storymodel.relation-result/v1"
    recipe: Literal["event-relation-admission/v1"] = "event-relation-admission/v1"
    account: Account


class RichRelationResult(_ResultFields):
    """A v2 receipt retains every rich graph collection, including states and circumstances."""

    schema_version: Literal["storymodel.relation-result/v2"] = "storymodel.relation-result/v2"
    recipe: Literal["situation-relation-admission/v1"] = "situation-relation-admission/v1"
    account: RichAccount


RelationOutput = RelationResult | RichRelationResult


@overload
def admit_relations(value: Account, raw: str) -> RelationResult: ...


@overload
def admit_relations(value: RichAccount, raw: str) -> RichRelationResult: ...


def admit_relations(value: SupportedAccount, raw: str) -> RelationOutput:
    """Admit all declared edges together, or refuse them without choosing a cycle winner.

    Malformed/foreign envelopes raise ValueError. Invalid graph claims return a rejected
    receipt with the original account. Quotes establish support identity, not causal or
    temporal entailment. Processing completeness never asserts absence of unproposed edges.
    """
    account = require_text_account(value)
    account_sha = digest(account.model_dump(mode="json"))
    response_sha = sha256(raw.encode()).hexdigest()
    proposal_class = (
        RichRelationProposals if isinstance(account, RichAccount) else RelationProposals
    )
    proposals = proposal_class.model_validate_json(canonical(strict_json(raw)))
    if proposals.account_sha256 != account_sha:
        raise ValueError("relation proposals belong to another account")

    def result(
        status: Literal["admitted", "partial", "rejected", "not-attempted", "failed"],
        *,
        output: SupportedAccount = account,
        accepted: dict[str, str] | None = None,
        reason: str | None = proposals.reason,
    ) -> RelationOutput:
        result_class = RichRelationResult if isinstance(account, RichAccount) else RelationResult
        return result_class.model_validate(
            dict(
                account_sha256=account_sha,
                response_sha256=response_sha,
                status=status,
                reason=reason,
                account=output,
                accepted=accepted or {},
            )
        )

    if proposals.status in ("not-attempted", "failed"):
        return result(proposals.status)
    documents = {d.id: d for d in account.documents}
    situations = {s.id: s for s in account._situations()}
    relations = list(account.relations)
    accepted = {}
    try:
        for proposal in proposals.relations:
            left, right = situations.get(proposal.source), situations.get(proposal.target)
            if left is None or right is None:
                endpoint = "situation" if isinstance(account, RichAccount) else "event"
                raise ValueError(f"{proposal.key}: foreign {endpoint} endpoint")
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
        output = validate_supported_account(
            account.model_copy(
                update={
                    "relations": tuple(relations),
                    "recipe": Recipe(
                        name=RICH_RECIPE if isinstance(account, RichAccount) else RECIPE,
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


def verify_relations(value: SupportedAccount, raw: str, receipt: RelationOutput) -> RelationOutput:
    """Recompute both graph and processing decisions from independently supplied inputs."""
    result_class = RichRelationResult if isinstance(receipt, RichRelationResult) else RelationResult
    checked = _validate_instance(result_class, receipt)
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


class RichRelationRow(Model):
    """Rich endpoints retain their situation kind instead of labeling a state as an event."""

    relation: Name
    kind: Name
    side: Literal["source", "recall"]
    source_situation: Name
    source_kind: Literal["event", "state"]
    source_predicate: Name
    target_situation: Name
    target_kind: Literal["event", "state"]
    target_predicate: Name
    source_context: Name
    target_context: Name
    evidence: tuple[RelationQuote, ...]


@overload
def relation_table(value: Account) -> tuple[RelationRow, ...]: ...


@overload
def relation_table(value: RichAccount) -> tuple[RichRelationRow, ...]: ...


def relation_table(
    value: SupportedAccount,
) -> tuple[RelationRow, ...] | tuple[RichRelationRow, ...]:
    """Return Python-derived rows for notebooks or inspection; do not infer additional edges."""
    account = require_text_account(value)
    situations = {s.id: s for s in account._situations()}
    documents = {d.id: d for d in account.documents}
    rows = []
    for r in account.relations:
        left, right = situations[r.source], situations[r.target]
        fields = dict(
            relation=r.id,
            kind=r.kind,
            side=left.side,
            source_predicate=left.predicate,
            target_predicate=right.predicate,
            source_context=left.context,
            target_context=right.context,
            evidence=tuple(
                RelationQuote(document=e.document, span=s, text=s.slice(documents[e.document].text))
                for e in text_evidence(r.evidence)
                for s in e.spans
            ),
        )
        if isinstance(account, RichAccount):
            fields.update(
                source_situation=left.id,
                source_kind="state" if isinstance(left, State) else "event",
                target_situation=right.id,
                target_kind="state" if isinstance(right, State) else "event",
            )
        else:
            fields.update(source_event=left.id, target_event=right.id)
        rows.append(fields)
    if isinstance(account, RichAccount):
        return tuple(RichRelationRow.model_validate(row) for row in rows)
    return tuple(RelationRow.model_validate(row) for row in rows)
