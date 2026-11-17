"""Compile one source or recall document into an explicit v4 situation account."""

from __future__ import annotations

from collections.abc import Iterable
from hashlib import sha256
from typing import Literal, Self

from pydantic import Field, model_validator

from ._admission import (
    ContextProposal,
    EntityProposal,
    EventProposal,
    ExtendedContextProposal,
    Quote,
    RichUnresolvedClaim,
    StateProposal,
    admit_graph,
)
from .model import (
    AnyContext,
    Context,
    Digest,
    Document,
    Entity,
    Event,
    Inventory,
    Model,
    Name,
    Recipe,
    State,
    Support,
    Unit,
    _validate_instance,
    contained_in,
)
from .rich_account import Circumstance, RichAccount, validate_rich_account
from .serialization import canonical, digest, strict_json
from .source import SourceRow

RECIPE: Literal["rich-extractive-admission/v1"] = "rich-extractive-admission/v1"
RECIPE_V2: Literal["rich-extractive-admission/v2"] = "rich-extractive-admission/v2"


class RichConstructionRequest(Model):
    """One exact source or recall document with caller-supplied, UTF-16 observation rows."""

    schema_version: Literal[
        "storymodel.rich-construction-request/v1", "storymodel.rich-construction-request/v2"
    ] = "storymodel.rich-construction-request/v1"
    recipe: Literal["rich-extractive-admission/v1", "rich-extractive-admission/v2"] = RECIPE
    document: Document
    rows: tuple[SourceRow, ...] = Field(min_length=1)
    export_permission: Literal["allowed", "denied"] = "denied"

    @model_validator(mode="after")
    def anchored(self) -> Self:
        if self.schema_version.rsplit("/", 1)[-1] != self.recipe.rsplit("/", 1)[-1]:
            raise ValueError("construction request version and recipe differ")
        if len({row.key for row in self.rows}) != len(self.rows):
            raise ValueError("duplicate rich construction row key")
        if any(left.span.end > right.span.start for left, right in zip(self.rows, self.rows[1:])):
            raise ValueError("rich construction rows must be ordered and disjoint")
        for row in self.rows:
            if not row.span.slice(self.document.text).strip():
                raise ValueError("rich construction row must contain observation text")
        return self


def validate_rich_construction_request(
    value: RichConstructionRequest | str | bytes,
) -> RichConstructionRequest:
    """Revalidate copied requests before compiling their evidence-bound graph."""
    if isinstance(value, RichConstructionRequest):
        return _validate_instance(RichConstructionRequest, value)
    return RichConstructionRequest.model_validate_json(canonical(strict_json(value)))


def rich_construction_request_digest(value: RichConstructionRequest) -> str:
    return digest(validate_rich_construction_request(value).model_dump(mode="json"))


def prepare_rich_construction(
    key: str,
    side: Literal["source", "recall"],
    text: str,
    rows: Iterable[SourceRow],
    *,
    export_permission: Literal["allowed", "denied"] = "denied",
    full_contexts: bool = False,
) -> RichConstructionRequest:
    """Anchor source or recall observations without normalizing their text or clocks."""
    if not isinstance(full_contexts, bool):
        raise ValueError("full_contexts must be a Boolean")
    return RichConstructionRequest(
        schema_version="storymodel.rich-construction-request/v2"
        if full_contexts
        else "storymodel.rich-construction-request/v1",
        recipe=RECIPE_V2 if full_contexts else RECIPE,
        document=Document.create(key, side, text),
        rows=tuple(rows),
        export_permission=export_permission,
    )


class CircumstanceProposal(Model):
    """A literal time or manner phrase attached to one local state or event key."""

    key: Name
    situation: Name
    kind: Literal["time", "manner"]
    evidence: Quote


class _RichRowFields(Model):
    """Atomic rich graph claims, or an explicit row-local processing outcome."""

    row: Name
    status: Literal["completed", "unsupported", "not-attempted", "failed", "search-limited"]
    reason: str | None = None
    contexts: tuple[ContextProposal | ExtendedContextProposal, ...] = ()
    entities: tuple[EntityProposal, ...] = ()
    events: tuple[EventProposal, ...] = ()
    states: tuple[StateProposal, ...] = ()
    circumstances: tuple[CircumstanceProposal, ...] = ()
    unresolved: tuple[RichUnresolvedClaim, ...] = ()

    @model_validator(mode="after")
    def completeness(self) -> Self:
        if self.status != "completed" and not self.reason:
            raise ValueError("incomplete rich construction proposal requires a reason")
        claims = any(
            (
                self.contexts,
                self.entities,
                self.events,
                self.states,
                self.circumstances,
                self.unresolved,
            )
        )
        if self.status in ("unsupported", "failed", "not-attempted") and claims:
            raise ValueError("unsupported or unprocessed rich row cannot supply claims")
        if self.status == "completed" and not claims:
            raise ValueError("empty rich row must declare unsupported or incomplete work")
        return self


class RichRowProposal(_RichRowFields):
    """Atomic rich graph claims, or an explicit row-local processing outcome."""

    contexts: tuple[ContextProposal, ...] = ()


class RichRowProposalV2(_RichRowFields):
    """The full context vocabulary uses the same atomic row and scope admission rules."""


class RichConstructionProposal(Model):
    """Provider-facing envelope whose graph keys remain row-local and untrusted."""

    schema_version: Literal["storymodel.rich-construction-proposal/v1"]
    rows: tuple[RichRowProposal, ...]


class RichConstructionProposalV2(Model):
    """Explicit v2 proposals include additional held and holderless context families."""

    schema_version: Literal["storymodel.rich-construction-proposal/v2"]
    rows: tuple[RichRowProposalV2, ...]


class RichRowResolution(Model):
    """Every requested row retains its admitted, partial, rejected, or unattempted outcome."""

    row: Name
    unit: Name
    status: Literal["admitted", "partial", "unsupported", "rejected", "not-attempted", "failed"]
    reason: str | None = None
    accepted: dict[str, str] = Field(default_factory=dict)
    unresolved: tuple[RichUnresolvedClaim, ...] = ()


class RichConstructionResult(Model):
    """A receipted rich graph and complete row accounting, not a semantic truth claim."""

    schema_version: Literal[
        "storymodel.rich-construction-result/v1", "storymodel.rich-construction-result/v2"
    ] = "storymodel.rich-construction-result/v1"
    recipe: Literal["rich-extractive-admission/v1", "rich-extractive-admission/v2"] = RECIPE
    request_sha256: Digest
    response_sha256: Digest
    account: RichAccount
    resolutions: tuple[RichRowResolution, ...]

    @model_validator(mode="after")
    def version_matches(self) -> Self:
        version = self.schema_version.rsplit("/", 1)[-1]
        if self.recipe.rsplit("/", 1)[-1] != version or self.account.schema_version != (
            "storymodel.account/v5" if version == "v2" else "storymodel.account/v4"
        ):
            raise ValueError("construction result version, recipe and account differ")
        return self


def compile_rich_construction(value: RichConstructionRequest, raw: str) -> RichConstructionResult:
    """Resolve recorded row proposals independently; a bad row cannot erase another row."""
    request = validate_rich_construction_request(value)
    request_sha = rich_construction_request_digest(request)
    response_sha = sha256(raw.encode()).hexdigest()
    full_contexts = request.schema_version == "storymodel.rich-construction-request/v2"
    account_version: Literal["storymodel.account/v4", "storymodel.account/v5"] = (
        "storymodel.account/v5" if full_contexts else "storymodel.account/v4"
    )
    parsed = strict_json(raw)
    if (
        not isinstance(parsed, dict)
        or set(parsed) != {"schema_version", "rows"}
        or parsed["schema_version"]
        != (
            "storymodel.rich-construction-proposal/v2"
            if full_contexts
            else "storymodel.rich-construction-proposal/v1"
        )
        or not isinstance(parsed["rows"], list)
    ):
        raise ValueError("invalid rich construction proposal envelope")
    keys = {row.key for row in request.rows}
    supplied: dict[str, object] = {}
    for item in parsed["rows"]:
        row = item.get("row") if isinstance(item, dict) else None
        if not isinstance(row, str) or row not in keys or row in supplied:
            raise ValueError("duplicate, foreign or invalid rich proposal row")
        supplied[row] = item

    document = request.document
    root = Context(side=document.side, kind="narrated")
    contexts: list[AnyContext] = [root]
    entities: list[Entity] = []
    events: list[Event] = []
    states: list[State] = []
    circumstances: list[Circumstance] = []
    units = []
    inventories = []
    resolutions = []
    for row in request.rows:
        unit = Unit(
            kind="row" if document.side == "source" else "recall",
            support=Support(document=document.id, spans=(row.span,)),
        )
        units.append(unit)
        if document.side == "recall":
            inventories.append(
                Inventory(unit=unit.id, status="not-attempted", reason="detail extraction not run")
            )
        if row.key not in supplied:
            resolutions.append(
                RichRowResolution(
                    row=row.key, unit=unit.id, status="not-attempted", reason="no proposal for row"
                )
            )
            continue
        try:
            proposal: RichRowProposal | RichRowProposalV2 = (
                RichRowProposalV2.model_validate_json(canonical(supplied[row.key]))
                if full_contexts
                else RichRowProposal.model_validate_json(canonical(supplied[row.key]))
            )
            graph = admit_graph(
                document,
                unit,
                root,
                context_proposals_input=proposal.contexts,
                entity_proposals_input=proposal.entities,
                event_proposals_input=proposal.events,
                state_proposals_input=proposal.states,
                unresolved=proposal.unresolved,
                extra_keys=tuple(item.key for item in proposal.circumstances),
            )
            local = {**graph.accepted}
            row_circumstances = []
            situations: dict[str, Event | State] = {
                **{event.id: event for event in graph.events},
                **{state.id: state for state in graph.states},
            }
            for item in proposal.circumstances:
                situation_id = local.get(item.situation)
                if situation_id is None or situation_id not in situations:
                    raise ValueError("foreign local circumstance situation")
                if item.evidence.span.slice(document.text) != item.evidence.text:
                    raise ValueError("circumstance quote differs from canonical document")
                support = Support(document=document.id, spans=(item.evidence.span,))
                if not contained_in(support, situations[situation_id].evidence):
                    raise ValueError("circumstance evidence lies outside its situation")
                row_circumstances.append(
                    Circumstance(
                        situation=situation_id,
                        kind=item.kind,
                        label=item.evidence.text,
                        evidence=(support,),
                    )
                )
            candidate = RichAccount(
                schema_version=account_version,
                documents=(document,),
                units=tuple(units),
                contexts=(*contexts, *graph.contexts),
                entities=(*entities, *graph.entities),
                events=(*events, *graph.events),
                states=(*states, *graph.states),
                circumstances=(*circumstances, *row_circumstances),
                inventories=tuple(inventories),
                recipe=Recipe(
                    name=request.recipe, input_sha256=(document.sha256, request_sha, response_sha)
                ),
                export_permission=request.export_permission,
            )
            validate_rich_account(candidate)
        except ValueError as error:
            resolutions.append(
                RichRowResolution(row=row.key, unit=unit.id, status="rejected", reason=str(error))
            )
            continue
        contexts.extend(graph.contexts)
        entities.extend(graph.entities)
        events.extend(graph.events)
        states.extend(graph.states)
        circumstances.extend(row_circumstances)
        status: Literal["admitted", "partial", "unsupported", "not-attempted", "failed"] = (
            "partial"
            if proposal.unresolved or proposal.status == "search-limited"
            else "admitted"
            if proposal.status == "completed"
            else proposal.status
        )
        resolutions.append(
            RichRowResolution(
                row=row.key,
                unit=unit.id,
                status=status,
                reason=proposal.reason
                or ("unresolved rich claims remain" if proposal.unresolved else None),
                accepted={
                    **graph.accepted,
                    **{
                        item.key: circumstance.id
                        for item, circumstance in zip(
                            proposal.circumstances, row_circumstances, strict=True
                        )
                    },
                },
                unresolved=proposal.unresolved,
            )
        )
    account = RichAccount(
        schema_version=account_version,
        documents=(document,),
        units=tuple(units),
        contexts=tuple(contexts),
        entities=tuple(entities),
        events=tuple(events),
        states=tuple(states),
        circumstances=tuple(circumstances),
        inventories=tuple(inventories),
        recipe=Recipe(
            name=request.recipe, input_sha256=(document.sha256, request_sha, response_sha)
        ),
        export_permission=request.export_permission,
    )
    return RichConstructionResult(
        schema_version="storymodel.rich-construction-result/v2"
        if full_contexts
        else "storymodel.rich-construction-result/v1",
        recipe=request.recipe,
        request_sha256=request_sha,
        response_sha256=response_sha,
        account=account,
        resolutions=tuple(resolutions),
    )


def verify_rich_construction(
    value: RichConstructionRequest, raw: str, result: RichConstructionResult
) -> RichConstructionResult:
    """Recompute row receipts and the rich graph rather than trusting a copied result."""
    checked = _validate_instance(RichConstructionResult, result)
    expected = compile_rich_construction(value, raw)
    if checked != expected:
        raise ValueError("rich construction result differs from recorded inputs")
    return expected
