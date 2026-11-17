"""A separate recorded graded assessment fills pending hypothesis slots without remapping."""

from dataclasses import dataclass
from hashlib import sha256
from typing import Any, Literal, Self

from pydantic import model_validator

from .context_fidelity import (
    ContextAccounting,
    ContextEvidence,
    check_context_accounting,
    context_questions,
)
from .held_identity import resolve_attributed_identity
from .model import (
    AnySupport,
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
    text_support,
)
from .native_assessment import (
    NativeCitation,
    native_citation_supports,
    native_source_view,
    require_native_source_account,
)
from .playback import PlaybackSupport
from .rich_account import (
    RichAccount,
    SupportedAccount,
    require_text_account,
    validate_supported_account,
)
from .serialization import canonical, digest, strict_json

RECIPE: Literal["unit-only-conditional-fidelity/v1"] = "unit-only-conditional-fidelity/v1"
CONTEXT_RECIPE: Literal["context-evidence-conditional-fidelity/v2"] = (
    "context-evidence-conditional-fidelity/v2"
)
NATIVE_RECIPE: Literal["native-observation-conditional-fidelity/v1"] = (
    "native-observation-conditional-fidelity/v1"
)


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


class NativeFidelityProposal(FidelityProposal):
    """Cite supplied native situations separately from exact text quotations."""

    playback_evidence: tuple[NativeCitation, ...] = ()


def _conditional_assessment(
    hypothesis: Hypothesis,
    item: FidelityProposal,
    targets: dict[str, Unit],
    documents: dict[str, Document],
    *,
    playback_evidence: tuple[PlaybackSupport, ...] = (),
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
    combined: tuple[AnySupport, ...] = (*supports, *playback_evidence)
    if any(not contained_in(s, (targets[t].support for t in hypothesis.targets)) for s in combined):
        raise ValueError("fidelity evidence lies outside its source hypothesis")
    return Assessment(
        hypothesis=hypothesis.id,
        status=item.status,
        grade=item.grade,
        severity=item.severity,
        reason=item.reason,
        evidence=combined,
    )


@dataclass(frozen=True)
class _Question:
    detail: Detail
    binding: Binding
    hypothesis: Hypothesis


def _questions(request: "_FidelityRequestFields") -> tuple[_Question, ...]:
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


class _FidelityRequestFields(Model):
    """Common pending-slot checks for explicitly versioned fidelity input conditions."""

    schema_version: str = "storymodel.fidelity-request/v1"
    account: SupportedAccount
    unit: Name
    interpretation: Literal["narrative", "referent"] = "referent"

    @model_validator(mode="after")
    def admissible(self) -> Self:
        unit = next((u for u in self.account.units if u.id == self.unit), None)
        if unit is None or unit.kind != "recall":
            raise ValueError("fidelity requires an existing recall unit")
        if len(text_support(unit.support).spans) != 1:
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


class FidelityRequest(_FidelityRequestFields):
    """A full account snapshot fixes the pending hypotheses; the prompt omits previous grades."""

    schema_version: Literal["storymodel.fidelity-request/v1"] = "storymodel.fidelity-request/v1"


class ContextFidelityRequest(_FidelityRequestFields):
    """Explicitly expand evidence to linked situations, contexts and recorded identity witnesses."""

    schema_version: Literal["storymodel.fidelity-request/v2"] = "storymodel.fidelity-request/v2"
    context_evidence: ContextEvidence


class NativeFidelityRequest(_FidelityRequestFields):
    """Grade text recall against supplied source observations with native evidence."""

    schema_version: Literal["storymodel.fidelity-request/v3"] = "storymodel.fidelity-request/v3"
    account: RichAccount


FidelityInput = FidelityRequest | ContextFidelityRequest | NativeFidelityRequest


def fidelity_recipe(request: FidelityInput) -> str:
    """Name the actual information condition used by a checked request."""
    if isinstance(request, NativeFidelityRequest):
        return NATIVE_RECIPE
    return CONTEXT_RECIPE if isinstance(request, ContextFidelityRequest) else RECIPE


def validate_fidelity_request(value: FidelityInput | str | bytes) -> FidelityInput:
    """Revalidate nested relationships and contextual recordings before consumption."""
    request: FidelityInput
    if isinstance(value, (FidelityRequest, ContextFidelityRequest, NativeFidelityRequest)):
        request = _validate_instance(type(value), value)
    else:
        parsed = strict_json(value)
        if not isinstance(parsed, dict):
            raise ValueError("fidelity request must be an object")
        cls = (
            NativeFidelityRequest
            if parsed.get("schema_version") == "storymodel.fidelity-request/v3"
            else ContextFidelityRequest
            if parsed.get("schema_version") == "storymodel.fidelity-request/v2"
            else FidelityRequest
        )
        request = cls.model_validate_json(canonical(parsed))
    if isinstance(request, NativeFidelityRequest):
        require_native_source_account(request.account)
    else:
        require_text_account(request.account)
    if isinstance(request, ContextFidelityRequest):
        resolve_attributed_identity(
            request.account,
            request.context_evidence.identity_annotations,
            request.context_evidence.holder_proposals,
        )
    return request


def prepare_fidelity(
    account: SupportedAccount,
    unit: str,
    *,
    interpretation: Literal["narrative", "referent"] = "referent",
    context_evidence: ContextEvidence | None = None,
) -> FidelityInput:
    """Select only absent/pending grades; references and other assessment statuses stay fixed."""
    if (
        isinstance(account, RichAccount)
        and account.schema_version == "storymodel.account/v8"
        and account.media_sources
    ):
        if context_evidence is not None:
            raise ValueError("native fidelity does not support text context/holder recordings")
        return validate_fidelity_request(
            NativeFidelityRequest(
                account=require_native_source_account(account),
                unit=unit,
                interpretation=interpretation,
            )
        )
    checked = require_text_account(account)
    if context_evidence is not None:
        return validate_fidelity_request(
            ContextFidelityRequest(
                account=checked,
                unit=unit,
                interpretation=interpretation,
                context_evidence=_validate_instance(ContextEvidence, context_evidence),
            )
        )
    return FidelityRequest(account=checked, unit=unit, interpretation=interpretation)


def fidelity_request_digest(value: FidelityInput) -> str:
    """Bind the complete private snapshot, including prior decisions hidden from the prompt."""
    return digest(validate_fidelity_request(value).model_dump(mode="json"))


class FidelityReply(Model):
    """Local hypothesis aliases identify the exact detail/interpretation/hypothesis slot."""

    schema_version: Literal["storymodel.fidelity-proposal/v1"] = "storymodel.fidelity-proposal/v1"
    packet_sha256: Digest
    assessments: tuple[FidelityProposal, ...] = ()


class ContextFidelityProposal(FidelityProposal):
    """One v2 grade explicitly accounts for its context and identity dependencies."""

    context_accounting: ContextAccounting


class ContextFidelityReply(Model):
    """Contextual replies have a separate wire contract from unit-only grading."""

    schema_version: Literal["storymodel.fidelity-proposal/v2"] = "storymodel.fidelity-proposal/v2"
    packet_sha256: Digest
    assessments: tuple[ContextFidelityProposal, ...] = ()


class NativeFidelityReply(Model):
    """Native replies retain situation citations and exact playback support."""

    schema_version: Literal["storymodel.fidelity-proposal/v3"] = "storymodel.fidelity-proposal/v3"
    packet_sha256: Digest
    assessments: tuple[NativeFidelityProposal, ...] = ()


def fidelity_prompt(value: FidelityInput) -> str:
    """Show claim content and verbatim local recall, source and hypotheses, never prior grades."""
    request = validate_fidelity_request(value)
    account = request.account
    unit = next(u for u in account.units if u.id == request.unit)
    support = text_support(unit.support)
    document = next(d for d in account.documents if d.id == support.document)
    extent = support.spans[0]
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
    if isinstance(request, ContextFidelityRequest):
        views = context_questions(
            account,
            tuple((f"a{i}", q.detail, q.hypothesis) for i, q in enumerate(_questions(request))),
            request.context_evidence,
        )
        payload["recipe"] = CONTEXT_RECIPE
        payload["schema"] = ContextFidelityReply.model_json_schema()
        payload["context_evidence"] = views
        payload["input_condition"] = (
            "one recall unit plus explicitly linked situations, ancestor contexts, holder "
            "candidates and account-derived document-local identity; evidence can extend "
            "outside the recall unit; unrelated recall situations and previous grades are withheld"
        )
        payload["instruction"] = (
            str(payload["instruction"])
            .replace("fidelity-proposal/v1", "fidelity-proposal/v2")
            .replace(
                "Use only this recall unit; no neighbors, global order or previous grades "
                "are supplied.",
                "Use the declared expanded context evidence; unrelated recall, global order "
                "and previous grades are withheld.",
            )
            + " For each question consider every supplied context alias exactly once. Declare "
            "required_holders and required_mentions, and explain their relevance or why unresolved "
            "dependencies are not needed. An unresolved or withheld required dependency permits "
            "only noncompleted status or completed/unverifiable; never infer contradiction from "
            "missing attribution. Reported, believed or intended content is not root-world fact. "
            "Document-local identity aliases do not establish identity or inequality between "
            "source and recall. Context/holder background excerpts cannot replace hypothesis-owned "
            "source quotes for a grade. No recorded situation placement is a gap, "
            "not narrated-world placement."
        )
    if isinstance(request, NativeFidelityRequest):
        payload["recipe"] = NATIVE_RECIPE
        payload["schema"] = NativeFidelityReply.model_json_schema()
        payload["source_observations"] = native_source_view(request.account)
        payload["instruction"] = str(payload["instruction"]).replace(
            "fidelity-proposal/v1", "fidelity-proposal/v3"
        ) + (
            " Native evidence uses playback_evidence entries naming a supplied source situation "
            "and exact support within both that situation and the hypothesis. The full cited "
            "situation must belong to the hypothesis. These are admitted symbolic annotations, "
            "not access to raw audiovisual content. Respect epistemic contexts; missing evidence "
            "or unresolved attribution is not contradiction. Use unverifiable when needed. "
            "No cross-modal identity or holder resolution is supplied."
        )
    return canonical({"packet_sha256": digest(payload), **payload})


class FidelityDecision(Model):
    """Every requested hypothesis gets an admission outcome without altering reference status."""

    detail: Name
    hypothesis: Name
    local_key: Name
    status: Literal["admitted", "rejected", "not-attempted"]
    reason: Name


class _FidelityResultFields(Model):
    """An input-bound receipt retains the full account with only lawful pending-slot updates."""

    schema_version: str = "storymodel.fidelity-result/v1"
    recipe: str = RECIPE
    request_sha256: Digest
    prompt_sha256: Digest
    response_sha256: Digest
    account: SupportedAccount
    decisions: tuple[FidelityDecision, ...]


class FidelityResult(_FidelityResultFields):
    """An input-bound receipt retains the full account with only lawful pending-slot updates."""

    schema_version: Literal["storymodel.fidelity-result/v1"] = "storymodel.fidelity-result/v1"
    recipe: Literal["unit-only-conditional-fidelity/v1"] = RECIPE


class ContextFidelityResult(_FidelityResultFields):
    """Context accounting survives admission alongside unchanged reference and graph content."""

    schema_version: Literal["storymodel.fidelity-result/v2"] = "storymodel.fidelity-result/v2"
    recipe: Literal["context-evidence-conditional-fidelity/v2"] = CONTEXT_RECIPE
    context_accounting: dict[Name, ContextAccounting]


class NativeFidelityResult(_FidelityResultFields):
    """Native assessment receipt preserves the full v8 account and admission outcomes."""

    schema_version: Literal["storymodel.fidelity-result/v3"] = "storymodel.fidelity-result/v3"
    recipe: Literal["native-observation-conditional-fidelity/v1"] = NATIVE_RECIPE
    account: RichAccount


FidelityOutput = FidelityResult | ContextFidelityResult | NativeFidelityResult


def resolve_fidelity(value: FidelityInput, raw: str) -> FidelityOutput:
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
    version = (
        "v3"
        if isinstance(request, NativeFidelityRequest)
        else "v2"
        if isinstance(request, ContextFidelityRequest)
        else "v1"
    )
    if parsed["schema_version"] != f"storymodel.fidelity-proposal/{version}":
        raise ValueError("unsupported fidelity proposal schema")
    if parsed["packet_sha256"] != strict_json(prompt)["packet_sha256"]:
        raise ValueError("fidelity proposal belongs to another prompt")
    rows = parsed["assessments"]
    if not isinstance(rows, list):
        raise ValueError("fidelity assessments must be an array")
    questions = {f"a{i}": q for i, q in enumerate(_questions(request))}
    proposals: dict[str, Any] = {}
    for row in rows:
        if (
            not isinstance(row, dict)
            or not isinstance(row.get("hypothesis"), str)
            or row["hypothesis"] not in questions
            or row["hypothesis"] in proposals
        ):
            raise ValueError("foreign or duplicate local fidelity question")
        proposals[row["hypothesis"]] = row
    account = request.account
    targets = {u.id: u for u in account.units if u.kind != "recall"}
    documents = {d.id: d for d in account.documents if d.side == "source"}
    updates: dict[tuple[str, str], dict[str, Assessment]] = {}
    context_reviews: dict[str, ContextAccounting] = {}
    context_views = strict_json(prompt).get("context_evidence", {})
    decisions = []
    for key, q in questions.items():
        status: Literal["admitted", "rejected", "not-attempted"]
        if key not in proposals:
            status, reason = "not-attempted", "no conditional assessment supplied"
        else:
            try:
                item: FidelityProposal
                if isinstance(request, ContextFidelityRequest):
                    contextual = ContextFidelityProposal.model_validate_json(
                        canonical(proposals[key])
                    )
                    check_context_accounting(
                        contextual.context_accounting,
                        context_views[key],
                        contextual.status,
                        contextual.grade,
                    )
                    item = contextual
                elif isinstance(request, NativeFidelityRequest):
                    item = NativeFidelityProposal.model_validate_json(canonical(proposals[key]))
                else:
                    item = FidelityProposal.model_validate_json(canonical(proposals[key]))
                native = (
                    native_citation_supports(
                        require_native_source_account(account), q.hypothesis, item.playback_evidence
                    )
                    if isinstance(item, NativeFidelityProposal)
                    else ()
                )
                assessment = _conditional_assessment(
                    q.hypothesis, item, targets, documents, playback_evidence=native
                )
                updates.setdefault((q.detail.id, q.binding.reference.interpretation), {})[
                    q.hypothesis.id
                ] = assessment
                if isinstance(item, ContextFidelityProposal):
                    context_reviews[key] = item.context_accounting
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
                    name=fidelity_recipe(request),
                    input_sha256=tuple(
                        dict.fromkeys(
                            [
                                *(d.sha256 for d in account.documents),
                                *(
                                    (s.sha256 for s in account.media_sources)
                                    if isinstance(account, RichAccount)
                                    else ()
                                ),
                                request_sha,
                                response_sha,
                            ]
                        )
                    ),
                ),
            }
        )
    )
    result_fields = dict(
        request_sha256=request_sha,
        prompt_sha256=sha256(prompt.encode("utf-8")).hexdigest(),
        response_sha256=response_sha,
        account=output,
        decisions=tuple(decisions),
    )
    if isinstance(request, NativeFidelityRequest):
        return NativeFidelityResult.model_validate(result_fields)
    if isinstance(request, ContextFidelityRequest):
        return ContextFidelityResult.model_validate(
            result_fields | {"context_accounting": context_reviews}
        )
    return FidelityResult.model_validate(result_fields)


def verify_fidelity(value: FidelityInput, raw: str, receipt: FidelityOutput) -> FidelityOutput:
    """Recompute all decisions and account updates from the original request and exact response."""
    cls = (
        NativeFidelityResult
        if isinstance(receipt, NativeFidelityResult)
        else ContextFidelityResult
        if isinstance(receipt, ContextFidelityResult)
        else FidelityResult
    )
    checked = _validate_instance(cls, receipt)
    expected = resolve_fidelity(value, raw)
    if checked != expected:
        raise ValueError("fidelity result differs from recorded inputs")
    return expected
