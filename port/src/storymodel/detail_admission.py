"""Snapshot-bound admission of recorded recall details into a rich account."""

from __future__ import annotations

from hashlib import sha256
from typing import Literal, Self

from pydantic import Field, model_validator

from ._admission import Quote
from .model import (
    Detail,
    Digest,
    Event,
    Inventory,
    Model,
    Name,
    Recipe,
    State,
    Support,
    contained_in,
)
from .rich_account import RichAccount, validate_rich_account
from .serialization import canonical, digest, strict_json

RECIPE: Literal["recorded-detail-admission/v1"] = "recorded-detail-admission/v1"


class DetailRequest(Model):
    """A complete rich-account snapshot and explicit recall units to enrich once."""

    schema_version: Literal["storymodel.detail-request/v1"] = "storymodel.detail-request/v1"
    account: RichAccount
    units: tuple[Name, ...] = Field(min_length=1)

    @model_validator(mode="after")
    def admissible(self) -> Self:
        selected = set(self.units)
        if len(selected) != len(self.units):
            raise ValueError("detail request units must be unique")
        units = {unit.id: unit for unit in self.account.units}
        inventories = {item.unit: item for item in self.account.inventories}
        if any(unit not in units or units[unit].kind != "recall" for unit in selected):
            raise ValueError("detail request requires existing recall units")
        if any(detail.unit in selected for detail in self.account.details):
            raise ValueError("detail request refuses units with existing details")
        if any(
            inventories.get(unit) and inventories[unit].status == "completed" for unit in selected
        ):
            raise ValueError("detail request refuses completed inventories")
        return self


def validate_detail_request(value: DetailRequest | str | bytes) -> DetailRequest:
    raw = canonical(value.model_dump(mode="json")) if isinstance(value, DetailRequest) else value
    return DetailRequest.model_validate_json(canonical(strict_json(raw)))


def prepare_details(account: RichAccount, units: tuple[str, ...]) -> DetailRequest:
    """Select recall units for one input-bound detail pass without overwriting prior work."""
    return DetailRequest(account=validate_rich_account(account), units=units)


def detail_request_digest(value: DetailRequest) -> str:
    return digest(validate_detail_request(value).model_dump(mode="json"))


class DetailDraft(Model):
    """An explicit recall detail whose literal content is derived only from quoted spans."""

    key: Name
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
    address: Literal["target", "other", "personal", "general", "unresolved", "extended"]
    discourse: Literal["content", "commentary", "inference", "repair", "repetition"]
    quotes: tuple[Quote, ...] = Field(min_length=1)
    situation: str | None = None


class DetailUnitProposal(Model):
    """One selected recall unit's completed, limited, failed, or unattempted work."""

    unit: Name
    status: Literal["completed", "search-limited", "failed", "not-attempted"]
    reason: Name
    details: tuple[DetailDraft, ...] = ()

    @model_validator(mode="after")
    def shape(self) -> Self:
        if len({item.key for item in self.details}) != len(self.details):
            raise ValueError("duplicate detail key")
        if self.status in ("failed", "not-attempted") and self.details:
            raise ValueError("unprocessed detail unit cannot contain details")
        return self


class DetailProposal(Model):
    """Recorded provider envelope bound to the complete request snapshot."""

    schema_version: Literal["storymodel.detail-proposal/v1"]
    request_sha256: Digest
    units: tuple[DetailUnitProposal, ...]


class DetailUnitReceipt(Model):
    unit: Name
    status: Literal["admitted", "partial", "rejected", "not-attempted", "failed"]
    reason: Name
    accepted: dict[str, str] = Field(default_factory=dict)


class DetailAdmissionResult(Model):
    schema_version: Literal["storymodel.detail-admission-result/v1"] = (
        "storymodel.detail-admission-result/v1"
    )
    recipe: Literal["recorded-detail-admission/v1"] = RECIPE
    request_sha256: Digest
    response_sha256: Digest
    account: RichAccount
    receipts: tuple[DetailUnitReceipt, ...]


def detail_prompt(value: DetailRequest) -> str:
    """Render the full snapshot explicitly; this profile makes no unit-only input claim."""
    request = validate_detail_request(value)
    return canonical(
        {
            "recipe": RECIPE,
            "request_sha256": detail_request_digest(request),
            "request": request.model_dump(mode="json"),
            "schema": DetailProposal.model_json_schema(),
        }
    )


def admit_details(value: DetailRequest, raw: str) -> DetailAdmissionResult:
    """Admit each selected unit atomically while preserving all untouched rich graph records."""
    request = validate_detail_request(value)
    request_sha = detail_request_digest(request)
    response_sha = sha256(raw.encode()).hexdigest()
    parsed = strict_json(raw)
    if not isinstance(parsed, dict) or set(parsed) != {"schema_version", "request_sha256", "units"}:
        raise ValueError("invalid detail proposal envelope")
    if (
        parsed["schema_version"] != "storymodel.detail-proposal/v1"
        or parsed["request_sha256"] != request_sha
    ):
        raise ValueError("detail proposal belongs to another request")
    if not isinstance(parsed["units"], list):
        raise ValueError("detail proposal units must be an array")
    supplied: dict[str, object] = {}
    for item in parsed["units"]:
        unit = item.get("unit") if isinstance(item, dict) else None
        if not isinstance(unit, str) or unit not in request.units or unit in supplied:
            raise ValueError("duplicate or foreign detail unit proposal")
        supplied[unit] = item
    account = request.account
    units = {unit.id: unit for unit in account.units}
    documents = {document.id: document for document in account.documents}
    situations: dict[str, Event | State] = {}
    for recall_event in account.events:
        if recall_event.side == "recall":
            situations[recall_event.id] = recall_event
    for state in account.states:
        if state.side == "recall":
            situations[state.id] = state
    details = list(account.details)
    inventories = [item for item in account.inventories if item.unit not in request.units]
    receipts = []
    for unit_id in request.units:
        unit = units[unit_id]
        if unit_id not in supplied:
            inventories.append(
                Inventory(
                    unit=unit_id, status="not-attempted", reason="no detail proposal supplied"
                )
            )
            receipts.append(
                DetailUnitReceipt(
                    unit=unit_id, status="not-attempted", reason="no detail proposal supplied"
                )
            )
            continue
        try:
            proposal = DetailUnitProposal.model_validate_json(canonical(supplied[unit_id]))
            if proposal.status in ("failed", "not-attempted"):
                inventories.append(
                    Inventory(unit=unit_id, status=proposal.status, reason=proposal.reason)
                )
                receipts.append(
                    DetailUnitReceipt(
                        unit=unit_id,
                        status=proposal.status,
                        reason=proposal.reason,
                    )
                )
                continue
            document = documents[unit.support.document]
            admitted = []
            for draft in proposal.details:
                spans = []
                for quote in draft.quotes:
                    if quote.span.slice(document.text) != quote.text:
                        raise ValueError("detail quote differs from canonical recall document")
                    spans.append(quote.span)
                support = Support(document=document.id, spans=tuple(spans))
                if not contained_in(support, (unit.support,)):
                    raise ValueError("detail evidence lies outside its recall unit")
                event: str | None = None
                if draft.situation is not None:
                    situation = situations.get(draft.situation)
                    if situation is None:
                        raise ValueError("detail requires an existing recall situation")
                    if not contained_in(support, situation.evidence):
                        raise ValueError("detail evidence lies outside its recall situation")
                    event = situation.id
                content = ""
                for index, quote in enumerate(draft.quotes):
                    if index and draft.quotes[index - 1].span.end != quote.span.start:
                        content += " … "
                    content += quote.text
                admitted.append(
                    Detail(
                        unit=unit_id,
                        kind=draft.kind,
                        content=content,
                        support=support,
                        address=draft.address,
                        discourse=draft.discourse,
                        event=event,
                    )
                )
            candidate = RichAccount(
                **{
                    **account.model_dump(),
                    "details": (*details, *admitted),
                    "inventories": (
                        *inventories,
                        Inventory(unit=unit_id, status=proposal.status, reason=proposal.reason),
                    ),
                    "recipe": Recipe(
                        name=RECIPE,
                        input_sha256=(
                            *(document.sha256 for document in account.documents),
                            request_sha,
                            response_sha,
                        ),
                    ),
                }
            )
            validate_rich_account(candidate)
        except ValueError as error:
            inventories.append(Inventory(unit=unit_id, status="failed", reason=str(error)))
            receipts.append(DetailUnitReceipt(unit=unit_id, status="rejected", reason=str(error)))
            continue
        details.extend(admitted)
        inventories.append(Inventory(unit=unit_id, status=proposal.status, reason=proposal.reason))
        receipts.append(
            DetailUnitReceipt(
                unit=unit_id,
                status="partial" if proposal.status == "search-limited" else "admitted",
                reason=proposal.reason,
                accepted={
                    draft.key: detail.id
                    for draft, detail in zip(proposal.details, admitted, strict=True)
                },
            )
        )
    output = RichAccount(
        **{
            **account.model_dump(),
            "details": tuple(details),
            "inventories": tuple(inventories),
            "recipe": Recipe(
                name=RECIPE,
                input_sha256=(
                    *(document.sha256 for document in account.documents),
                    request_sha,
                    response_sha,
                ),
            ),
        }
    )
    return DetailAdmissionResult(
        request_sha256=request_sha,
        response_sha256=response_sha,
        account=output,
        receipts=tuple(receipts),
    )


def verify_details(
    value: DetailRequest, raw: str, result: DetailAdmissionResult
) -> DetailAdmissionResult:
    checked = DetailAdmissionResult.model_validate_json(canonical(result.model_dump(mode="json")))
    expected = admit_details(value, raw)
    if checked != expected:
        raise ValueError("detail admission result differs from recorded inputs")
    return expected
