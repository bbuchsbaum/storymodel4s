"""Compile anchored source rows independently of any participant's recall.

The receipt carries processing completeness. An account alone is a graph snapshot,
not evidence that all source content has been represented.
"""

from __future__ import annotations

from collections.abc import Iterable
from hashlib import sha256
from typing import Literal, Self

from pydantic import Field, model_validator

from ._admission import (
    ContextProposal,
    EntityProposal,
    EventProposal,
    UnresolvedClaim,
    admit_graph,
)
from .accounts import make_account
from .model import (
    Account,
    Context,
    Digest,
    Document,
    Entity,
    Event,
    Model,
    Name,
    Recipe,
    Span,
    Support,
    Unit,
    _validate_instance,
)
from .serialization import canonical, digest, strict_json

RECIPE: Literal["source-extractive-admission/v1"] = "source-extractive-admission/v1"


class SourceRow(Model):
    """A caller-named observation anchored in the exact source edition, in UTF-16."""

    key: Name
    span: Span


class SourceRequest(Model):
    """An independent source edition with ordered, disjoint observation rows."""

    schema_version: Literal["storymodel.source-request/v1"] = "storymodel.source-request/v1"
    recipe: Literal["source-extractive-admission/v1"] = RECIPE
    document: Document
    rows: tuple[SourceRow, ...] = Field(min_length=1)
    export_permission: Literal["allowed", "denied"] = "denied"

    @model_validator(mode="after")
    def anchored(self) -> Self:
        if self.document.side != "source":
            raise ValueError("source compilation requires a source document")
        if len({r.key for r in self.rows}) != len(self.rows):
            raise ValueError("duplicate source row key")
        if any(a.span.end > b.span.start for a, b in zip(self.rows, self.rows[1:])):
            raise ValueError("source rows must be ordered and disjoint")
        for row in self.rows:
            if not row.span.slice(self.document.text).strip():
                raise ValueError("source row must contain observation text")
        return self


def validate_source_request(value: SourceRequest | str | bytes) -> SourceRequest:
    """Recheck copied requests and strict JSON before public consumption."""
    if isinstance(value, SourceRequest):
        return _validate_instance(SourceRequest, value)
    return SourceRequest.model_validate_json(canonical(strict_json(value)))


def source_request_digest(value: SourceRequest) -> str:
    return digest(validate_source_request(value).model_dump(mode="json"))


def prepare_source(
    key: str,
    text: str,
    rows: Iterable[SourceRow],
    *,
    export_permission: Literal["allowed", "denied"] = "denied",
) -> SourceRequest:
    """Anchor supplied observations without canonicalizing text or inventing segmentation."""
    return SourceRequest(
        document=Document.create(key, "source", text),
        rows=tuple(rows),
        export_permission=export_permission,
    )


class SourceRowProposal(Model):
    """Atomic local claims or an explicit unsupported/unprocessed outcome for a row."""

    row: Name
    status: Literal["completed", "unsupported", "not-attempted", "failed", "search-limited"]
    reason: str | None = None
    contexts: tuple[ContextProposal, ...] = ()
    entities: tuple[EntityProposal, ...] = ()
    events: tuple[EventProposal, ...] = ()
    unresolved: tuple[UnresolvedClaim, ...] = ()

    @model_validator(mode="after")
    def completeness(self) -> Self:
        if self.status != "completed" and not self.reason:
            raise ValueError("incomplete source proposal requires a reason")
        claims = any((self.contexts, self.entities, self.events, self.unresolved))
        if self.status in ("unsupported", "failed", "not-attempted") and claims:
            raise ValueError("unsupported or unprocessed row cannot supply claims")
        if self.status == "completed" and not claims:
            raise ValueError("empty source proposal must declare unsupported or incomplete work")
        return self


class SourceProposal(Model):
    """Provider-facing envelope; claims use row-local keys, never trusted graph IDs."""

    schema_version: Literal["storymodel.source-proposal/v1"] = "storymodel.source-proposal/v1"
    rows: tuple[SourceRowProposal, ...]


class SourceRowResolution(Model):
    """Every requested row survives, including malformed or missing provider work."""

    row: Name
    unit: Name
    status: Literal["admitted", "partial", "unsupported", "rejected", "not-attempted", "failed"]
    reason: str | None = None
    accepted: dict[str, str] = Field(default_factory=dict)
    unresolved: tuple[UnresolvedClaim, ...] = ()


class SourceResult(Model):
    """Source graph plus complete row accounting; verify against the request and raw reply."""

    schema_version: Literal["storymodel.source-result/v1"] = "storymodel.source-result/v1"
    recipe: Literal["source-extractive-admission/v1"] = RECIPE
    request_sha256: Digest
    response_sha256: Digest
    account: Account
    resolutions: tuple[SourceRowResolution, ...]


def compile_source(value: SourceRequest, raw: str) -> SourceResult:
    """Resolve recorded proposals deterministically, with atomic admission per source row.

    Checks prove exact quotes and consistency with declared attribution scopes, not
    semantic entailment or completeness of the provider's description. Bad framing
    refuses the response; a bad row does not erase other rows.
    """
    request = validate_source_request(value)
    request_sha = digest(request.model_dump(mode="json"))
    response_sha = sha256(raw.encode()).hexdigest()
    parsed = strict_json(raw)
    if (
        not isinstance(parsed, dict)
        or set(parsed) != {"schema_version", "rows"}
        or parsed["schema_version"] != "storymodel.source-proposal/v1"
        or not isinstance(parsed["rows"], list)
    ):
        raise ValueError("invalid source proposal envelope")
    keys = {r.key for r in request.rows}
    supplied: dict[str, object] = {}
    for item in parsed["rows"]:
        row = item.get("row") if isinstance(item, dict) else None
        if not isinstance(row, str) or row not in keys or row in supplied:
            raise ValueError("duplicate, foreign or invalid proposal row")
        supplied[row] = item

    document = request.document
    root = Context(side="source", kind="narrated")
    contexts = [root]
    entities: list[Entity] = []
    events: list[Event] = []
    units = []
    resolutions = []
    for row in request.rows:
        unit = Unit(kind="row", support=Support(document=document.id, spans=(row.span,)))
        units.append(unit)
        if row.key not in supplied:
            resolutions.append(
                SourceRowResolution(
                    row=row.key, unit=unit.id, status="not-attempted", reason="no proposal for row"
                )
            )
            continue
        try:
            proposal = SourceRowProposal.model_validate_json(canonical(supplied[row.key]))
            graph = admit_graph(
                document,
                unit,
                root,
                context_proposals_input=proposal.contexts,
                entity_proposals_input=proposal.entities,
                event_proposals_input=proposal.events,
                unresolved=proposal.unresolved,
            )
            # Account admission also checks duplicate roles and graph relationships.
            make_account(
                (document,),
                units=(unit,),
                contexts=(root, *graph.contexts),
                entities=graph.entities,
                events=graph.events,
            )
        except ValueError as error:
            resolutions.append(
                SourceRowResolution(row=row.key, unit=unit.id, status="rejected", reason=str(error))
            )
            continue
        contexts.extend(graph.contexts)
        entities.extend(graph.entities)
        events.extend(graph.events)
        status: Literal["admitted", "partial", "unsupported", "not-attempted", "failed"] = (
            "partial"
            if proposal.unresolved or proposal.status == "search-limited"
            else "admitted"
            if proposal.status == "completed"
            else proposal.status
        )
        resolutions.append(
            SourceRowResolution(
                row=row.key,
                unit=unit.id,
                status=status,
                reason=proposal.reason
                or ("unresolved source claims remain" if proposal.unresolved else None),
                accepted=graph.accepted,
                unresolved=proposal.unresolved,
            )
        )
    account = make_account(
        (document,),
        units=units,
        contexts=contexts,
        entities=entities,
        events=events,
        recipe=Recipe(name=RECIPE, input_sha256=(document.sha256, request_sha, response_sha)),
        export_permission=request.export_permission,
    )
    return SourceResult(
        request_sha256=request_sha,
        response_sha256=response_sha,
        account=account,
        resolutions=tuple(resolutions),
    )


def verify_source(value: SourceRequest, raw: str, result: SourceResult) -> SourceResult:
    """Recompute the receipt, row decisions and graph instead of trusting edited artifacts."""
    checked = _validate_instance(SourceResult, result)
    expected = compile_source(value, raw)
    if expected != checked:
        raise ValueError("source result differs from its recorded inputs")
    return expected
