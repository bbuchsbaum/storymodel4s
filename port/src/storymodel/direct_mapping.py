"""Recorded, unit-local reference proposals with hypothesis-conditional fidelity.

Admission checks evidence identity and declared semantics, not linguistic truth.
The provider sees no recall session identity, global position or neighboring unit.
"""

from hashlib import sha256
from typing import Any, Literal, Self

from pydantic import model_validator

from .model import (
    Account,
    Assessment,
    Binding,
    Detail,
    Digest,
    Hypothesis,
    Model,
    Name,
    Recipe,
    Reference,
    Span,
    Support,
    Unit,
    contained_in,
)
from .rich_account import RichAccount, SupportedAccount, validate_supported_account
from .serialization import canonical, digest, strict_json

RECIPE = "unit-only-direct-mapping/v1"
Interpretation = Literal["narrative", "referent"]


class _MappingRequestFields(Model):
    """Common unit-only request checks, shared by explicit legacy and rich wire versions."""

    schema_version: str = "storymodel.mapping-request/v1"
    account: SupportedAccount
    unit: Name
    interpretation: Interpretation = "referent"

    @model_validator(mode="after")
    def admissible(self) -> Self:
        unit = next((u for u in self.account.units if u.id == self.unit), None)
        if unit is None or unit.kind != "recall":
            raise ValueError("mapping requires an existing recall unit")
        if len(unit.support.spans) != 1:
            raise ValueError("unit-only v1 requires contiguous recall unit support")
        if not any(u.kind != "recall" for u in self.account.units):
            raise ValueError("mapping requires a source target catalog")
        details = _details(self)
        if not details:
            raise ValueError("mapping requires an unbound detail in this interpretation")
        visible = [(d.kind, tuple((s.start, s.end) for s in d.support.spans)) for d in details]
        if len(set(visible)) != len(visible):
            raise ValueError(
                "unit-only mapping cannot distinguish details with identical kind/quotes"
            )
        return self


class MappingRequest(_MappingRequestFields):
    """Private replay input; only the explicitly rendered subset goes into a prompt."""

    schema_version: Literal["storymodel.mapping-request/v1"] = "storymodel.mapping-request/v1"
    account: Account


class RichMappingRequest(_MappingRequestFields):
    """A v2 mapping request retains its complete v4 input graph throughout replay."""

    schema_version: Literal["storymodel.mapping-request/v2"] = "storymodel.mapping-request/v2"
    account: RichAccount


MappingInput = MappingRequest | RichMappingRequest


def _details(request: _MappingRequestFields) -> tuple[Detail, ...]:
    occupied = {
        b.detail
        for b in request.account.bindings
        if b.reference.interpretation == request.interpretation
    }
    return tuple(
        sorted(
            (d for d in request.account.details if d.unit == request.unit and d.id not in occupied),
            key=lambda d: (tuple((s.start, s.end) for s in d.support.spans), d.kind),
        )
    )


def validate_mapping_request(value: MappingInput | str | bytes) -> MappingInput:
    """Revalidate nested copied objects before generating prompts or changing bindings."""
    raw = (
        canonical(value.model_dump(mode="json"))
        if isinstance(value, _MappingRequestFields)
        else value
    )
    parsed = strict_json(raw)
    if not isinstance(parsed, dict):
        raise ValueError("mapping request must be a JSON object")
    if parsed.get("schema_version") == "storymodel.mapping-request/v2":
        return RichMappingRequest.model_validate_json(canonical(parsed))
    return MappingRequest.model_validate_json(canonical(parsed))


def prepare_mapping(
    account: SupportedAccount, unit: str, *, interpretation: Interpretation = "referent"
) -> MappingInput:
    """Prepare one independent unit; existing bindings are excluded and never overwritten."""
    checked = validate_supported_account(account)
    if isinstance(checked, RichAccount):
        return RichMappingRequest(account=checked, unit=unit, interpretation=interpretation)
    return MappingRequest(account=checked, unit=unit, interpretation=interpretation)


def mapping_request_digest(value: MappingInput) -> str:
    """Bind the private input snapshot, including identities omitted from the prompt."""
    return digest(validate_mapping_request(value).model_dump(mode="json"))


class SourceQuote(Model):
    """Exact source support at global UTF-16 coordinates, checked against a hypothesis."""

    document: Name
    span: Span
    text: Name


class MappingHypothesis(Model):
    """A local proposal key avoids asking the provider to compute content-addressed IDs."""

    key: Name
    kind: Literal["single", "joint", "broad", "external"]
    targets: tuple[str, ...] = ()
    external: Literal["personal", "general", "other_episode"] | None = None


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


class DetailMapping(Model):
    """One unit-local detail's explicit reference outcome and optional fidelity proposals."""

    detail: Name
    status: Literal["attempted", "not-attempted", "failed", "search-limited"]
    outcome: Literal["resolved", "alternative", "unresolved"] | None = None
    reason: Name
    hypotheses: tuple[MappingHypothesis, ...] = ()
    assessments: tuple[FidelityProposal, ...] = ()


class MappingProposal(Model):
    """Complete prompt binding; omitted details receive explicit unattempted outcomes."""

    schema_version: Literal["storymodel.mapping-proposal/v1"]
    packet_sha256: Digest
    details: tuple[DetailMapping, ...] = ()


def mapping_prompt(value: MappingInput) -> str:
    """Render only source observations and local recall quotes, excluding prior decisions.

    Local offsets are relative to the unit, not the document. Normalized detail content,
    account hashes, recall IDs, neighbors, timing, address and prior bindings are absent.
    Source order remains visible. This controls the input condition, not provider behavior.
    """
    request = validate_mapping_request(value)
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
        "recall": {
            "text": extent.slice(document.text),
            "details": [
                {
                    "key": f"d{i}",
                    "kind": detail.kind,
                    "quotes": [
                        {
                            "span": {"start": s.start - extent.start, "end": s.end - extent.start},
                            "text": s.slice(document.text),
                        }
                        for s in detail.support.spans
                    ],
                }
                for i, detail in enumerate(_details(request))
            ],
        },
        "schema": MappingProposal.model_json_schema(),
        "instruction": (
            "Return mapping-proposal/v1 and echo the supplied packet_sha256. "
            "Map each local detail under the declared interpretation: narrative asks which "
            "source passage this telling invokes; referent asks what source content it denotes. "
            "Single/joint/broad are distinct from alternative hypotheses. Broad requires a scene. "
            "External requires an explicit destination; failed search is unresolved, not external. "
            "Give a reason for every decision. Do not infer fidelity from reference or address. "
            "Assess fidelity separately under each hypothesis, citing exact source quotes within "
            "its targets. If not assessed, leave pending. Gist-detail-error requires severity. "
            "Misread means the attributed content is not expressed in this recall unit; keep the "
            "detail. Annotation absence alone does not prove invention. No probabilities. "
            "Use only this recall unit; do not infer its global order or neighbors."
        ),
    }
    return canonical({"packet_sha256": digest(payload), **payload})


class MappingDecision(Model):
    """Every requested detail has an admission outcome, including missing or invalid work."""

    detail: Name
    local_key: Name
    status: Literal["admitted", "rejected", "not-attempted", "failed", "search-limited"]
    reason: Name


class _MappingResultFields(Model):
    """Common receipt fields; each public version pins its account shape."""

    schema_version: str = "storymodel.mapping-result/v1"
    recipe: Literal["unit-only-direct-mapping/v1"] = "unit-only-direct-mapping/v1"
    request_sha256: Digest
    prompt_sha256: Digest
    response_sha256: Digest
    account: SupportedAccount
    decisions: tuple[MappingDecision, ...]


class MappingResult(_MappingResultFields):
    """Input-bound mapping result retains the original account and every processing outcome."""

    schema_version: Literal["storymodel.mapping-result/v1"] = "storymodel.mapping-result/v1"
    account: Account


class RichMappingResult(_MappingResultFields):
    """The v2 receipt preserves all v4 graph collections alongside mapping decisions."""

    schema_version: Literal["storymodel.mapping-result/v2"] = "storymodel.mapping-result/v2"
    account: RichAccount


MappingOutput = MappingResult | RichMappingResult


def _binding(request: MappingInput, detail: Detail, proposal: DetailMapping) -> Binding:
    account = request.account
    keys = [h.key for h in proposal.hypotheses]
    if len(set(keys)) != len(keys):
        raise ValueError("duplicate hypothesis key")
    hypotheses = {
        h.key: Hypothesis(kind=h.kind, targets=tuple(sorted(h.targets)), external=h.external)
        for h in proposal.hypotheses
    }
    reference = Reference(
        status=proposal.status,
        interpretation=request.interpretation,
        outcome=proposal.outcome,
        hypotheses=tuple(hypotheses.values()),
        reason=proposal.reason,
    )
    targets: dict[str, Unit] = {u.id: u for u in account.units if u.kind != "recall"}
    documents = {d.id: d for d in account.documents if d.side == "source"}
    assessments: dict[str, Assessment] = {}
    for item in proposal.assessments:
        if item.hypothesis not in hypotheses or item.hypothesis in assessments:
            raise ValueError("foreign or duplicate conditional fidelity hypothesis")
        hypothesis = hypotheses[item.hypothesis]
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
        if any(
            not contained_in(s, (targets[t].support for t in hypothesis.targets)) for s in supports
        ):
            raise ValueError("fidelity evidence lies outside its source hypothesis")
        assessments[item.hypothesis] = Assessment(
            hypothesis=hypothesis.id,
            status=item.status,
            grade=item.grade,
            severity=item.severity,
            reason=item.reason,
            evidence=supports,
        )
    return Binding(
        detail=detail.id,
        reference=reference,
        assessments=tuple(
            assessments.get(
                key,
                Assessment(
                    hypothesis=h.id, status="pending", reason="no conditional assessment supplied"
                ),
            )
            for key, h in hypotheses.items()
        ),
    )


def resolve_mapping(value: MappingInput, raw: str) -> MappingOutput:
    """Resolve each detail atomically; malformed/foreign packet framing refuses the batch.

    A bad detail yields a failed binding and rejected receipt. Missing work is unattempted.
    Accepted source/recall graphs, addresses, inventories and existing bindings are preserved.
    """
    request = validate_mapping_request(value)
    prompt_sha = sha256(mapping_prompt(request).encode()).hexdigest()
    parsed: Any = strict_json(raw)
    if not isinstance(parsed, dict) or set(parsed) != {
        "schema_version",
        "packet_sha256",
        "details",
    }:
        raise ValueError("mapping proposal requires explicit schema, prompt digest and details")
    if parsed["schema_version"] != "storymodel.mapping-proposal/v1":
        raise ValueError("unsupported mapping proposal schema")
    if parsed["packet_sha256"] != strict_json(mapping_prompt(request))["packet_sha256"]:
        raise ValueError("mapping proposal belongs to another prompt")
    rows = parsed["details"]
    if not isinstance(rows, list):
        raise ValueError("mapping details must be an array")
    details = {f"d{i}": detail for i, detail in enumerate(_details(request))}
    proposals: dict[str, Any] = {}
    for item in rows:
        if (
            not isinstance(item, dict)
            or not isinstance(item.get("detail"), str)
            or item["detail"] not in details
            or item["detail"] in proposals
        ):
            raise ValueError("foreign or duplicate local detail key")
        proposals[item["detail"]] = item
    account = request.account
    bindings = list(account.bindings)
    decisions = []
    for key, detail in details.items():
        status: Literal["admitted", "rejected", "not-attempted", "failed", "search-limited"]
        if key not in proposals:
            status, reason = "not-attempted", "no mapping proposal supplied"
            binding = Binding(
                detail=detail.id,
                reference=Reference(
                    status="not-attempted", interpretation=request.interpretation, reason=reason
                ),
            )
        else:
            try:
                proposal = DetailMapping.model_validate_json(canonical(proposals[key]))
                binding = _binding(request, detail, proposal)
                validate_supported_account(
                    account.model_copy(update={"bindings": (*bindings, binding)})
                )
                status = "admitted" if proposal.status == "attempted" else proposal.status
                reason = proposal.reason
            except ValueError as error:
                status, reason = "rejected", str(error)
                binding = Binding(
                    detail=detail.id,
                    reference=Reference(
                        status="failed", interpretation=request.interpretation, reason=reason
                    ),
                )
        bindings.append(binding)
        decisions.append(
            MappingDecision(detail=detail.id, local_key=key, status=status, reason=reason)
        )
    request_sha = mapping_request_digest(request)
    response_sha = sha256(raw.encode()).hexdigest()
    output = validate_supported_account(
        account.model_copy(
            update={
                "bindings": tuple(bindings),
                "recipe": Recipe(
                    name=RECIPE,
                    input_sha256=(
                        *(d.sha256 for d in account.documents),
                        request_sha,
                        response_sha,
                    ),
                ),
            }
        )
    )
    result_class = RichMappingResult if isinstance(output, RichAccount) else MappingResult
    return result_class.model_validate_json(
        canonical(
            dict(
                schema_version="storymodel.mapping-result/v2"
                if isinstance(output, RichAccount)
                else "storymodel.mapping-result/v1",
                request_sha256=request_sha,
                prompt_sha256=prompt_sha,
                response_sha256=response_sha,
                account=output.model_dump(mode="json"),
                decisions=[d.model_dump(mode="json") for d in decisions],
            )
        )
    )


def verify_mapping(value: MappingInput, raw: str, receipt: MappingOutput) -> MappingOutput:
    """Recompute receipts, evidence and bindings from independently supplied original inputs."""
    result_class = RichMappingResult if isinstance(receipt, RichMappingResult) else MappingResult
    checked = result_class.model_validate_json(canonical(receipt.model_dump(mode="json")))
    expected = resolve_mapping(value, raw)
    if checked != expected:
        raise ValueError("mapping result differs from recorded inputs")
    return expected
