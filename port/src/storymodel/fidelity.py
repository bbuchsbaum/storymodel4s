"""A separate recorded graded assessment fills pending hypothesis slots without remapping."""

from dataclasses import dataclass
from hashlib import sha256
from typing import Any, Literal, Self

from pydantic import model_validator

from .model import (
    Assessment,
    Binding,
    Detail,
    Digest,
    Document,
    Hypothesis,
    Model,
    Name,
    Recipe,
    Span,
    Support,
    Unit,
    _validate_instance,
    contained_in,
)
from .rich_account import SupportedAccount, validate_supported_account
from .serialization import canonical, digest, strict_json

RECIPE: Literal["unit-only-conditional-fidelity/v1"] = "unit-only-conditional-fidelity/v1"


class SourceQuote(Model):
    """Exact source support at global UTF-16 coordinates, checked against a hypothesis."""

    document: Name
    span: Span
    text: Name


class FidelityProposal(Model):
    """A grade belongs to one local hypothesis, never implicitly to the whole detail."""

    hypothesis: Name
    status: Literal["pending", "completed", "unavailable", "refused"]
    grade: (
        Literal[
            "supported",
            "gist-detail-error",
            "contradicted",
            "recaller-inference",
            "external",
            "invented",
            "unverifiable",
            "misread",
        ]
        | None
    ) = None
    severity: Literal["minor", "major"] | None = None
    reason: Name
    evidence: tuple[SourceQuote, ...] = ()


def _conditional_assessment(
    hypothesis: Hypothesis,
    item: FidelityProposal,
    targets: dict[str, Unit],
    documents: dict[str, Document],
) -> Assessment:
    """Shared quote/grade admission for direct mapping and subsequent graded assessment."""
    if item.status == "completed":
        if (item.grade == "external") and hypothesis.kind != "external":
            raise ValueError("external fidelity requires an external hypothesis")
        if hypothesis.kind == "external" and item.grade not in (
            "external",
            "unverifiable",
            "misread",
        ):
            raise ValueError("external hypothesis cannot receive source fidelity")
    evidence: dict[str, list[Span]] = {}
    for quote in item.evidence:
        document = documents.get(quote.document)
        if document is None or quote.span.slice(document.text) != quote.text:
            raise ValueError("fidelity quote differs from source document")
        evidence.setdefault(quote.document, []).append(quote.span)
    supports = tuple(
        Support(document=doc, spans=tuple(sorted(spans, key=lambda s: s.start)))
        for doc, spans in sorted(evidence.items())
    )
    if any(t not in targets for t in hypothesis.targets):
        raise ValueError("foreign source target")
    if any(not contained_in(s, (targets[t].support for t in hypothesis.targets)) for s in supports):
        raise ValueError("fidelity evidence lies outside its source hypothesis")
    return Assessment(
        hypothesis=hypothesis.id,
        status=item.status,
        grade=item.grade,
        severity=item.severity,
        reason=item.reason,
        evidence=supports,
    )


@dataclass(frozen=True)
class _Question:
    detail: Detail
    binding: Binding
    hypothesis: Hypothesis


def _questions(request: "FidelityRequest") -> tuple[_Question, ...]:
    details = {d.id: d for d in request.account.details if d.unit == request.unit}
    questions = []
    for binding in request.account.bindings:
        if (
            binding.detail not in details
            or binding.reference.interpretation != request.interpretation
        ):
            continue
        previous = {a.hypothesis: a for a in binding.assessments}
        for h in binding.reference.hypotheses:
            if h.id not in previous or previous[h.id].status == "pending":
                questions.append(_Question(details[binding.detail], binding, h))
    return tuple(
        sorted(
            questions,
            key=lambda q: (
                tuple((s.start, s.end) for s in q.detail.support.spans),
                q.detail.kind,
                q.detail.content,
                q.hypothesis.id,
            ),
        )
    )


class FidelityRequest(Model):
    """A full account snapshot fixes the pending hypotheses; the prompt omits previous grades."""

    schema_version: Literal["storymodel.fidelity-request/v1"] = "storymodel.fidelity-request/v1"
    account: SupportedAccount
    unit: Name
    interpretation: Literal["narrative", "referent"] = "referent"

    @model_validator(mode="after")
    def admissible(self) -> Self:
        unit = next((u for u in self.account.units if u.id == self.unit), None)
        if unit is None or unit.kind != "recall":
            raise ValueError("fidelity requires an existing recall unit")
        if len(unit.support.spans) != 1:
            raise ValueError("unit-only fidelity v1 requires contiguous recall unit support")
        questions = _questions(self)
        if not questions:
            raise ValueError("fidelity requires an absent or pending hypothesis assessment")
        visible = [
            (q.detail.kind, q.detail.content, q.detail.support, q.hypothesis.id) for q in questions
        ]
        if len(set(visible)) != len(visible):
            raise ValueError("fidelity questions must be distinguishable from visible evidence")
        return self


def validate_fidelity_request(value: FidelityRequest | str | bytes) -> FidelityRequest:
    """Revalidate nested relationships before prompt construction or admission."""
    if isinstance(value, FidelityRequest):
        return _validate_instance(FidelityRequest, value)
    return FidelityRequest.model_validate_json(canonical(strict_json(value)))


def prepare_fidelity(
    account: SupportedAccount,
    unit: str,
    *,
    interpretation: Literal["narrative", "referent"] = "referent",
) -> FidelityRequest:
    """Select only absent/pending grades; references and other assessment statuses stay fixed."""
    return FidelityRequest(
        account=validate_supported_account(account), unit=unit, interpretation=interpretation
    )


def fidelity_request_digest(value: FidelityRequest) -> str:
    """Bind the complete private snapshot, including prior decisions hidden from the prompt."""
    return digest(validate_fidelity_request(value).model_dump(mode="json"))


class FidelityReply(Model):
    """Local hypothesis aliases identify the exact detail/interpretation/hypothesis slot."""

    schema_version: Literal["storymodel.fidelity-proposal/v1"] = "storymodel.fidelity-proposal/v1"
    packet_sha256: Digest
    assessments: tuple[FidelityProposal, ...] = ()


def fidelity_prompt(value: FidelityRequest) -> str:
    """Show claim content and verbatim local recall, source and hypotheses, never prior grades."""
    request = validate_fidelity_request(value)
    account = request.account
    unit = next(u for u in account.units if u.id == request.unit)
    document = next(d for d in account.documents if d.id == unit.support.document)
    extent = unit.support.spans[0]
    payload = {
        "recipe": RECIPE,
        "interpretation": request.interpretation,
        "source_documents": [
            d.model_dump(mode="json")
            for d in sorted(
                (d for d in account.documents if d.side == "source"), key=lambda d: d.id
            )
        ],
        "source_targets": [
            u.model_dump(mode="json")
            for u in sorted((u for u in account.units if u.kind != "recall"), key=lambda u: u.id)
        ],
        "recall": {"text": extent.slice(document.text)},
        "questions": [
            {
                "key": f"a{i}",
                "kind": q.detail.kind,
                "claim": q.detail.content,
                "quotes": [
                    {
                        "span": {"start": s.start - extent.start, "end": s.end - extent.start},
                        "text": s.slice(document.text),
                    }
                    for s in q.detail.support.spans
                ],
                "hypothesis": {
                    "kind": q.hypothesis.kind,
                    "targets": q.hypothesis.targets,
                    "external": q.hypothesis.external,
                },
            }
            for i, q in enumerate(_questions(request))
        ],
        "schema": FidelityReply.model_json_schema(),
        "instruction": (
            "Return fidelity-proposal/v1 and echo packet_sha256. Assess each question under its "
            "fixed reference hypothesis; hypothesis in each response is the local question key. "
            "First compare the claim with the recall quotes: misread means an extraction or "
            "interpretation error, not false recall. Then compare the expressed claim with source "
            "observations. Supported allows paraphrase/compression; gist-detail-error preserves "
            "the central event but has a specific wrong detail (name it in reason and give minor "
            "or major severity); contradicted requires incompatibility in the central claim. "
            "Recaller-inference is an expressed conclusion, external is explicit non-source "
            "content, unverifiable means the record cannot settle it, and invented requires an "
            "adequate record with no source basis. Annotation absence alone cannot "
            "prove invention. "
            "Cite exact source quotes inside this hypothesis's targets. If that scoped evidence "
            "cannot settle the question, report unverifiable or unavailable with a reason. "
            "External hypotheses accept only external, unverifiable or misread grades. "
            "Do not change reference, address or claim content. Do not infer a grade from "
            "reference. Pending/unavailable/refused are statuses, not substantive grades. "
            "Give a reason for each outcome. Use only this recall unit; no neighbors, global "
            "order or previous grades are supplied. Do not output probabilities."
        ),
    }
    return canonical({"packet_sha256": digest(payload), **payload})


class FidelityDecision(Model):
    """Every requested hypothesis gets an admission outcome without altering reference status."""

    detail: Name
    hypothesis: Name
    local_key: Name
    status: Literal["admitted", "rejected", "not-attempted"]
    reason: Name


class FidelityResult(Model):
    """An input-bound receipt retains the full account with only lawful pending-slot updates."""

    schema_version: Literal["storymodel.fidelity-result/v1"] = "storymodel.fidelity-result/v1"
    recipe: Literal["unit-only-conditional-fidelity/v1"] = RECIPE
    request_sha256: Digest
    prompt_sha256: Digest
    response_sha256: Digest
    account: SupportedAccount
    decisions: tuple[FidelityDecision, ...]


def resolve_fidelity(value: FidelityRequest, raw: str) -> FidelityResult:
    """Admit each grade atomically; missing/invalid work preserves existing reference and grades."""
    request = validate_fidelity_request(value)
    prompt = fidelity_prompt(request)
    parsed: Any = strict_json(raw)
    if not isinstance(parsed, dict) or set(parsed) != {
        "schema_version",
        "packet_sha256",
        "assessments",
    }:
        raise ValueError("fidelity proposal requires explicit schema, digest and assessments")
    if parsed["schema_version"] != "storymodel.fidelity-proposal/v1":
        raise ValueError("unsupported fidelity proposal schema")
    if parsed["packet_sha256"] != strict_json(prompt)["packet_sha256"]:
        raise ValueError("fidelity proposal belongs to another prompt")
    rows = parsed["assessments"]
    if not isinstance(rows, list):
        raise ValueError("fidelity assessments must be an array")
    questions = {f"a{i}": q for i, q in enumerate(_questions(request))}
    proposals: dict[str, Any] = {}
    for item in rows:
        if (
            not isinstance(item, dict)
            or not isinstance(item.get("hypothesis"), str)
            or item["hypothesis"] not in questions
            or item["hypothesis"] in proposals
        ):
            raise ValueError("foreign or duplicate local fidelity question")
        proposals[item["hypothesis"]] = item
    account = request.account
    targets = {u.id: u for u in account.units if u.kind != "recall"}
    documents = {d.id: d for d in account.documents if d.side == "source"}
    updates: dict[tuple[str, str], dict[str, Assessment]] = {}
    decisions = []
    for key, q in questions.items():
        status: Literal["admitted", "rejected", "not-attempted"]
        if key not in proposals:
            status, reason = "not-attempted", "no conditional assessment supplied"
        else:
            try:
                item = FidelityProposal.model_validate_json(canonical(proposals[key]))
                assessment = _conditional_assessment(q.hypothesis, item, targets, documents)
                updates.setdefault((q.detail.id, q.binding.reference.interpretation), {})[
                    q.hypothesis.id
                ] = assessment
                status, reason = "admitted", item.reason
            except ValueError as error:
                status, reason = "rejected", str(error)
        decisions.append(
            FidelityDecision(
                detail=q.detail.id,
                hypothesis=q.hypothesis.id,
                local_key=key,
                status=status,
                reason=reason,
            )
        )
    bindings = []
    for binding in account.bindings:
        changed = updates.get((binding.detail, binding.reference.interpretation))
        if changed:
            # Preserve previous order/values; append new grades in reference order.
            assessments = tuple(changed.get(a.hypothesis, a) for a in binding.assessments)
            previous = {a.hypothesis for a in binding.assessments}
            assessments += tuple(
                changed[h.id]
                for h in binding.reference.hypotheses
                if h.id in changed and h.id not in previous
            )
            binding = binding.model_copy(update={"assessments": assessments})
        bindings.append(binding)
    request_sha = fidelity_request_digest(request)
    response_sha = sha256(raw.encode("utf-8")).hexdigest()
    output = validate_supported_account(
        account.model_copy(
            update={
                "bindings": tuple(bindings),
                "recipe": Recipe(
                    name=RECIPE,
                    input_sha256=tuple(
                        dict.fromkeys(
                            [
                                *(d.sha256 for d in account.documents),
                                request_sha,
                                response_sha,
                            ]
                        )
                    ),
                ),
            }
        )
    )
    return FidelityResult(
        request_sha256=request_sha,
        prompt_sha256=sha256(prompt.encode("utf-8")).hexdigest(),
        response_sha256=response_sha,
        account=output,
        decisions=tuple(decisions),
    )


def verify_fidelity(value: FidelityRequest, raw: str, receipt: FidelityResult) -> FidelityResult:
    """Recompute all decisions and account updates from the original request and exact response."""
    checked = _validate_instance(FidelityResult, receipt)
    expected = resolve_fidelity(value, raw)
    if checked != expected:
        raise ValueError("fidelity result differs from recorded inputs")
    return expected
