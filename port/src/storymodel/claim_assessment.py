"""Fixed-population attributed text assessments, separate from reference and fidelity grades."""

from hashlib import sha256
from typing import Any, ClassVar, Literal, Self, get_args

from pydantic import Field, field_validator, model_validator

from .model import (
    Digest,
    Identified,
    Model,
    Name,
    Support,
    _validate_instance,
    contained,
    contained_in,
    indexed,
)
from .rich_account import SupportedAccount, validate_supported_account
from .sequence import Facet
from .serialization import canonical, digest, strict_json

RecallClaimState = Literal[
    "expressed", "reviewed-not-expressed", "extraction-unresolved", "reference-unresolved"
]
ClaimUnknown = Literal[
    "source-unavailable",
    "source-partial",
    "recall-extraction-unavailable",
    "reference-unresolved",
    "context-unresolved",
    "semantic-comparison-unavailable",
]
ClaimStatus = Literal[
    "supported",
    "contradicted",
    "conflicted",
    "unresolved",
    "not-expressed",
    "extraction-unresolved",
    "reference-unresolved",
]
AssessorKind = Literal[
    "human-review", "agent-proposal", "deterministic-rule", "imported-assessment"
]


class ClaimQuestion(Identified):
    """A semantic question has content identity independent of whether extraction succeeded."""

    namespace: ClassVar[str] = "claim-question"
    unit: Name
    target: Name
    facet: Facet


def _source_supports(account: SupportedAccount) -> dict[str, tuple[Support, ...]]:
    return {
        **{u.id: (u.support,) for u in account.units if u.kind != "recall"},
        **{s.id: s.evidence for s in account._situations() if s.side == "source"},
    }


class ClaimAssessmentPlan(Model):
    """The complete account and declared question population are fixed before assessment."""

    schema_version: Literal["storymodel.claim-assessment-plan/v1"] = (
        "storymodel.claim-assessment-plan/v1"
    )
    account: SupportedAccount
    questions: tuple[ClaimQuestion, ...]

    @model_validator(mode="after")
    def population(self) -> Self:
        indexed(self.questions, "claim question")
        units = {u.id for u in self.account.units if u.kind == "recall"}
        source = _source_supports(self.account)
        for question in self.questions:
            if question.unit not in units:
                raise ValueError("claim question requires an existing recall unit")
            if question.target not in source:
                raise ValueError("claim question requires an existing source target")
        return self


def prepare_claim_assessment(
    account: SupportedAccount, questions: tuple[ClaimQuestion, ...]
) -> ClaimAssessmentPlan:
    """Declare questions over a checked snapshot without deriving them from extracted claims."""
    return ClaimAssessmentPlan(account=validate_supported_account(account), questions=questions)


def validate_claim_plan(value: ClaimAssessmentPlan | str | bytes) -> ClaimAssessmentPlan:
    if isinstance(value, ClaimAssessmentPlan):
        return _validate_instance(ClaimAssessmentPlan, value)
    return ClaimAssessmentPlan.model_validate_json(canonical(strict_json(value)))


def _population_digest(plan: ClaimAssessmentPlan) -> str:
    return digest(
        {
            "schema_version": plan.schema_version,
            "account_sha256": digest(plan.account.model_dump(mode="json")),
            "questions": [q.model_dump(mode="json") for q in plan.questions],
        }
    )


def claim_population_digest(value: ClaimAssessmentPlan) -> str:
    """Bind the population, ordering and full mapping/account context, not assessment success."""
    return _population_digest(validate_claim_plan(value))


class ClaimCitation(Identified):
    """Owned source text may support a different assessed target; that link is attributed."""

    namespace: ClassVar[str] = "claim-citation"
    origin: Name
    support: Support

    def identity_fields(self) -> dict[str, Any]:
        # Canonical identity merges adjacent coordinates, but retains the original spans in
        # the record: each original span must still pass UTF-16 boundary checks on admission.
        fields = super().identity_fields()
        spans: list[dict[str, int]] = []
        for span in self.support.spans:
            if spans and spans[-1]["end"] == span.start:
                spans[-1]["end"] = span.end
            else:
                spans.append({"start": span.start, "end": span.end})
        fields["support"] = {"document": self.support.document, "spans": spans}
        return fields


class ClaimSubmission(Model):
    """An attributed review keeps evidence, counterevidence and unanswered reasons distinct."""

    question: Name
    recall_state: RecallClaimState
    reviewed: Support
    claim: Support | None = None
    supporting: tuple[Name, ...] = ()
    counterevidence: tuple[Name, ...] = ()
    unresolved: tuple[ClaimUnknown, ...] = ()
    assessor: AssessorKind
    procedure: Name

    @field_validator("supporting", "counterevidence", "unresolved")
    @classmethod
    def unique_sorted(cls, values: tuple[Any, ...]) -> tuple[Any, ...]:
        if len(values) != len(set(values)):
            raise ValueError("duplicate assessment evidence or unresolved reason")
        return tuple(sorted(values))

    @model_validator(mode="after")
    def availability(self) -> Self:
        expressed = self.recall_state in ("expressed", "reference-unresolved")
        if expressed != (self.claim is not None):
            raise ValueError(
                "expressed claims require spans; "
                "unavailable or unexpressed claims cannot assert spans"
            )
        links = self.supporting or self.counterevidence
        if not expressed and links:
            raise ValueError("unavailable or unexpressed claims cannot carry decided evidence")
        if self.recall_state == "expressed" and not links and not self.unresolved:
            raise ValueError("an unanswered expressed claim requires an unresolved reason")
        if (
            self.recall_state == "extraction-unresolved"
            and "recall-extraction-unavailable" not in self.unresolved
        ):
            raise ValueError("extraction failure requires its own reason")
        if (
            self.recall_state == "reference-unresolved"
            and "reference-unresolved" not in self.unresolved
        ):
            raise ValueError("unresolved reference requires its own reason")
        if self.recall_state == "reviewed-not-expressed" and self.unresolved:
            raise ValueError("reviewed nonexpression cannot also mean failed assessment")
        return self


class ClaimAssessmentReply(Model):
    """A raw attributed reply must account for the independently declared population exactly."""

    schema_version: Literal["storymodel.claim-assessment-reply/v1"] = (
        "storymodel.claim-assessment-reply/v1"
    )
    population_sha256: Digest
    citations: tuple[ClaimCitation, ...] = ()
    submissions: tuple[ClaimSubmission, ...]


class ClaimDecision(Model):
    """A derived status is reported alongside the exact question it accounts for."""

    question: ClaimQuestion
    status: ClaimStatus


class ClaimAssessmentReceipt(Model):
    """This receipt certifies attributed accounting and ownership, not truth or accuracy."""

    schema_version: Literal["storymodel.claim-assessment-receipt/v1"] = (
        "storymodel.claim-assessment-receipt/v1"
    )
    recipe: Literal["attributed-text-claim-assessment/v1"] = "attributed-text-claim-assessment/v1"
    account_sha256: Digest
    population_sha256: Digest
    response_sha256: Digest
    citations: tuple[ClaimCitation, ...]
    submissions: tuple[ClaimSubmission, ...]
    decisions: tuple[ClaimDecision, ...]
    counts: dict[ClaimStatus, int]
    total_questions: int = Field(ge=0)
    expressed_questions: int = Field(ge=0)
    resolved_questions: int = Field(ge=0)


def _status(submission: ClaimSubmission) -> ClaimStatus:
    if submission.recall_state == "reviewed-not-expressed":
        return "not-expressed"
    if submission.recall_state == "extraction-unresolved":
        return "extraction-unresolved"
    if submission.recall_state == "reference-unresolved":
        return "reference-unresolved"
    if submission.supporting and submission.counterevidence:
        return "conflicted"
    if not submission.unresolved:
        if submission.supporting:
            return "supported"
        if submission.counterevidence:
            return "contradicted"
    return "unresolved"


def assess_claims(value: ClaimAssessmentPlan, raw: str | bytes) -> ClaimAssessmentReceipt:
    """Check complete text ownership and derive statuses without changing the input account."""
    plan = validate_claim_plan(value)
    population_sha = _population_digest(plan)
    reply = ClaimAssessmentReply.model_validate_json(canonical(strict_json(raw)))
    if reply.population_sha256 != population_sha:
        raise ValueError("claim reply belongs to another population or account")
    questions = {q.id: q for q in plan.questions}
    submissions = {s.question: s for s in reply.submissions}
    if len(submissions) != len(reply.submissions) or set(submissions) != set(questions):
        raise ValueError(
            "submissions must account for the complete question population exactly once"
        )
    citations = indexed(reply.citations, "claim citation")
    source = _source_supports(plan.account)
    documents = {d.id: d for d in plan.account.documents}
    units = {u.id: u for u in plan.account.units}

    def check_text(support: Support) -> None:
        document = documents.get(support.document)
        if document is None:
            raise ValueError("assessment support has a foreign document")
        for span in support.spans:
            span.slice(document.text)

    for citation in citations.values():
        check_text(citation.support)
        if citation.origin not in source or not contained_in(
            citation.support, source[citation.origin]
        ):
            raise ValueError("citation lies outside its source origin")
    for submission in submissions.values():
        question = questions[submission.question]
        check_text(submission.reviewed)
        if not contained(submission.reviewed, units[question.unit].support):
            raise ValueError("reviewed recall spans lie outside their unit")
        if submission.claim is not None:
            check_text(submission.claim)
            if not contained(submission.claim, submission.reviewed):
                raise ValueError("claim spans lie outside their review scope")
        if not set((*submission.supporting, *submission.counterevidence)).issubset(citations):
            raise ValueError("foreign assessment evidence link")
    decisions = tuple(
        ClaimDecision(question=q, status=_status(submissions[q.id])) for q in plan.questions
    )
    counts: dict[ClaimStatus, int] = {status: 0 for status in get_args(ClaimStatus)}
    for decision in decisions:
        counts[decision.status] += 1
    return ClaimAssessmentReceipt(
        account_sha256=digest(plan.account.model_dump(mode="json")),
        population_sha256=population_sha,
        response_sha256=sha256(raw.encode("utf-8") if isinstance(raw, str) else raw).hexdigest(),
        citations=tuple(citations[key] for key in sorted(citations)),
        submissions=tuple(submissions[q.id] for q in plan.questions),
        decisions=decisions,
        counts=counts,
        total_questions=len(plan.questions),
        expressed_questions=sum(
            s.recall_state in ("expressed", "reference-unresolved") for s in submissions.values()
        ),
        resolved_questions=counts["supported"] + counts["contradicted"],
    )


def verify_claim_assessment(
    plan: ClaimAssessmentPlan,
    raw: str | bytes,
    receipt: ClaimAssessmentReceipt | str | bytes,
) -> ClaimAssessmentReceipt:
    """Recompute every status and count from the original population and exact reply bytes."""
    checked = (
        _validate_instance(ClaimAssessmentReceipt, receipt)
        if isinstance(receipt, ClaimAssessmentReceipt)
        else ClaimAssessmentReceipt.model_validate_json(canonical(strict_json(receipt)))
    )
    expected = assess_claims(plan, raw)
    if checked != expected:
        raise ValueError("claim assessment receipt differs from recorded inputs")
    return expected
