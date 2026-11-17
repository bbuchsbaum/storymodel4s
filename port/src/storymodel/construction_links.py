"""Connect verified row-local construction names through the existing relation admission."""

from hashlib import sha256
from typing import Literal, Self

from pydantic import Field, model_validator

from ._admission import Quote
from .model import Digest, Model, Name, Recipe, State, _validate_instance
from .relations import (
    RichRelationProposal,
    RichRelationProposals,
    admit_relations,
)
from .rich_account import RichAccount, validate_rich_account
from .rich_construction import (
    RichConstructionRequest,
    RichConstructionResult,
    compile_rich_construction,
    validate_rich_construction_request,
)
from .serialization import canonical, digest, strict_json

RECIPE = "construction-links/v1"


class ConstructionRef(Model):
    """A name is qualified by its observation row, never guessed from a repeated label."""

    row: Name
    key: Name


class ConstructionQuote(Model):
    """A relation cites an exact quote in one of its endpoint observation rows."""

    row: Name
    quote: Quote


class ConstructionLink(Model):
    """Explicit links address admitted situations without caller-generated content IDs."""

    key: Name
    kind: Literal["part_of", "before", "causes", "references"]
    source: ConstructionRef
    target: ConstructionRef
    evidence: tuple[ConstructionQuote, ...] = Field(min_length=1)


class ConstructionLinks(Model):
    """One atomic relation batch binds the complete original construction receipt."""

    schema_version: Literal["storymodel.construction-links/v1"] = "storymodel.construction-links/v1"
    construction_sha256: Digest
    status: Literal["completed", "search-limited", "not-attempted", "failed"]
    reason: Name | None = None
    relations: tuple[ConstructionLink, ...] = ()

    @model_validator(mode="after")
    def processing(self) -> Self:
        if self.status != "completed" and self.reason is None:
            raise ValueError("incomplete construction links require a reason")
        if self.status in ("not-attempted", "failed") and self.relations:
            raise ValueError("unprocessed construction links cannot carry relations")
        if len({r.key for r in self.relations}) != len(self.relations):
            raise ValueError("duplicate construction link key")
        return self


class ConnectedConstructionResult(Model):
    """Link admission retains the independent construction and its complete row accounting."""

    schema_version: Literal["storymodel.connected-construction/v1"] = (
        "storymodel.connected-construction/v1"
    )
    recipe: Literal["construction-links/v1"] = "construction-links/v1"
    construction: RichConstructionResult
    links_sha256: Digest
    status: Literal["admitted", "partial", "rejected", "not-attempted", "failed"]
    reason: str | None = None
    accepted: dict[str, str] = Field(default_factory=dict)
    account: RichAccount


def construction_link_prompt(request: RichConstructionRequest, raw: str) -> str:
    """Describe admitted local names and exact row observations for a supplied link proposal."""
    checked = validate_rich_construction_request(request)
    if checked.export_permission != "allowed":
        raise PermissionError("producer denies construction links export")
    result = compile_rich_construction(checked, raw)
    situations = {s.id: s for s in result.account._situations()}
    rows = {r.key: r for r in checked.rows}
    return canonical(
        {
            "recipe": RECIPE,
            "construction_sha256": digest(result.model_dump(mode="json")),
            "document": checked.document.model_dump(mode="json"),
            "contexts": [c.model_dump(mode="json") for c in result.account.contexts],
            "rows": [
                {
                    "row": r.row,
                    "status": r.status,
                    "reason": r.reason,
                    "span": rows[r.row].span.model_dump(mode="json"),
                    "situations": {
                        key: {
                            "kind": "state" if isinstance(situations[sid], State) else "event",
                            **situations[sid].model_dump(mode="json"),
                        }
                        for key, sid in r.accepted.items()
                        if sid in situations
                    },
                }
                for r in result.resolutions
            ],
            "schema": ConstructionLinks.model_json_schema(),
            "instruction": (
                "Return construction-links/v1 bound to the exact construction_sha256. "
                "Use source and target {row,key} references to listed situations. "
                "Evidence names an endpoint row and an exact UTF-16 quote within it. "
                "Never infer before or causes from row order. Missing situations are not "
                "endpoints. Generic part_of does not establish primary narrative containment. "
                "Non-reference links require the same context; references may cross contexts. "
                "Report failed, not-attempted and search-limited processing explicitly. "
                "This bounded link profile does not implement the full temporal or causal algebra."
            ),
        }
    )


def connect_rich_construction(
    request: RichConstructionRequest, construction_raw: str, links_raw: str
) -> ConnectedConstructionResult:
    """Rebuild local-name bindings from original inputs, then admit the complete relation batch."""
    checked = validate_rich_construction_request(request)
    construction = compile_rich_construction(checked, construction_raw)
    construction_sha = digest(construction.model_dump(mode="json"))
    links_sha = sha256(links_raw.encode("utf-8")).hexdigest()
    links = ConstructionLinks.model_validate_json(canonical(strict_json(links_raw)))
    if links.construction_sha256 != construction_sha:
        raise ValueError("construction links belong to another construction")
    account = construction.account
    resolutions = {r.row: r for r in construction.resolutions}
    rows = {r.key: r for r in checked.rows}
    situations = {s.id for s in account._situations()}

    def resolve(ref: ConstructionRef) -> str:
        row = resolutions.get(ref.row)
        sid = row.accepted.get(ref.key) if row else None
        if sid is None or sid not in situations:
            raise ValueError(f"{ref.row}/{ref.key}: no admitted event or state")
        return sid

    translated = []
    try:
        for link in links.relations:
            left, right = resolve(link.source), resolve(link.target)
            quotes = []
            for item in link.evidence:
                if item.row not in (link.source.row, link.target.row):
                    raise ValueError(f"{link.key}: evidence must belong to an endpoint row")
                row = rows[item.row]
                if (
                    not row.span.start
                    <= item.quote.span.start
                    < item.quote.span.end
                    <= row.span.end
                ):
                    raise ValueError(f"{link.key}: evidence escapes its declared row")
                # The existing relation engine checks exact UTF-16 slicing and quote identity.
                quotes.append(
                    {"document": checked.document.id, **item.quote.model_dump(mode="json")}
                )
            translated.append(
                RichRelationProposal.model_validate_json(
                    canonical(
                        {
                            "key": link.key,
                            "kind": link.kind,
                            "source": left,
                            "target": right,
                            "evidence": quotes,
                        }
                    )
                )
            )
    except ValueError as error:
        return ConnectedConstructionResult(
            construction=construction,
            links_sha256=links_sha,
            status="rejected",
            reason=str(error),
            account=account,
        )
    proposals = RichRelationProposals(
        account_sha256=digest(account.model_dump(mode="json")),
        status=links.status,
        reason=links.reason,
        relations=tuple(translated),
    )
    admitted = admit_relations(account, canonical(proposals.model_dump(mode="json")))
    output = admitted.account
    if admitted.status in ("admitted", "partial"):
        output = validate_rich_account(
            output.model_copy(
                update={
                    "recipe": Recipe(
                        name=RECIPE,
                        input_sha256=(
                            checked.document.sha256,
                            construction_sha,
                            links_sha,
                            digest(admitted.model_dump(mode="json")),
                        ),
                    ),
                }
            )
        )
    return ConnectedConstructionResult(
        construction=construction,
        links_sha256=links_sha,
        status=admitted.status,
        reason=admitted.reason,
        accepted=admitted.accepted,
        account=output,
    )


def verify_connected_construction(
    request: RichConstructionRequest,
    construction_raw: str,
    links_raw: str,
    result: ConnectedConstructionResult,
) -> ConnectedConstructionResult:
    """Recompute local resolution, graph admission and provenance from all original inputs."""
    checked = _validate_instance(ConnectedConstructionResult, result)
    expected = connect_rich_construction(request, construction_raw, links_raw)
    if checked != expected:
        raise ValueError("connected construction differs from original inputs")
    return expected
