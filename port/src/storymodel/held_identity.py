"""Second-pass identity from checked holders, preserving the first-pass artifact."""

from typing import ClassVar, Literal, Self

from pydantic import Field, model_validator

from .attribution import AttributionResult, resolve_context_holders, verify_attribution
from .identity import MentionAnnotation, OpenIdentityReason
from .model import Identified, Model, Name, State, Support, _validate_instance
from .rich_account import SupportedAccount, validate_supported_account
from .serialization import strict_json

HeldIdentityReason = Literal[
    "holder-unresolved",
    "speaker-group",
    "speaker-number-unknown",
    "needs-addressee",
    "holder-document-mismatch",
]


class AttributedReferent(Identified):
    """A final identity class names its unchanged first-pass class and complete membership."""

    namespace: ClassVar[str] = "attributed-text-referent"
    document: Name
    base_referent: Name
    members: tuple[Name, ...] = Field(min_length=1)

    @model_validator(mode="after")
    def ordered_members(self) -> Self:
        if tuple(sorted(set(self.members))) != self.members:
            raise ValueError("referent members must be unique and canonically sorted")
        return self


class AttributedMentionIdentity(Model):
    """One complete final decision retains placement and explicit speaker/addressee gaps."""

    mention: Name
    outcome: Literal["introducing", "resolved", "open"]
    referent: Name | None = None
    candidates: tuple[Name, ...] = ()
    rule: Name | None = None
    reason: OpenIdentityReason | HeldIdentityReason | None = None
    explanation: str | None = None
    contexts: tuple[Name, ...]

    @model_validator(mode="after")
    def shape(self) -> Self:
        if self.outcome == "open":
            if self.reason is None or self.referent is not None or self.rule is not None:
                raise ValueError("open identity requires a reason and no accepted referent or rule")
        elif (
            self.referent is None or self.rule is None or self.reason is not None or self.candidates
        ):
            raise ValueError("accepted identity requires exactly a referent and derivation rule")
        for items in (self.contexts, self.candidates):
            if items != tuple(sorted(set(items))):
                raise ValueError("identity contexts and candidates must be unique and sorted")
        return self


class AttributedIdentityResult(Model):
    """Final identities retain the full base identity, holder decisions and their receipts."""

    schema_version: Literal["storymodel.attributed-identity/v1"] = (
        "storymodel.attributed-identity/v1"
    )
    recipe: Literal["attributed-text-identity/v1"] = "attributed-text-identity/v1"
    attribution: AttributionResult
    referents: tuple[AttributedReferent, ...]
    mentions: tuple[AttributedMentionIdentity, ...]


def resolve_attributed_identity(
    value: SupportedAccount,
    identity_annotations: str,
    holder_proposals: str,
    *,
    attribution: AttributionResult | None = None,
) -> AttributedIdentityResult:
    """Attach explicitly singular first-person mentions to their unique placed context's holder.

    Holders come only from the first pass, never from newly attached first-person mentions.
    Unknown number, plural speakers and addressees stay open. Multiple placements do not
    license choosing a deepest, nearest or most convenient context.
    """
    account = validate_supported_account(value)
    checked = (
        resolve_context_holders(account, identity_annotations, holder_proposals)
        if attribution is None
        else verify_attribution(account, identity_annotations, holder_proposals, attribution)
    )
    base = checked.identity
    # Attribution rechecks the annotation envelope and all individual identity decisions.
    parsed = strict_json(identity_annotations)
    supplied = {item["mention"]: item for item in parsed["mentions"]}
    entities = {e.id: e for e in account.entities}
    holders = {h.context: h for h in checked.holders}
    base_referents = {r.id: r for r in base.referents}
    placements: dict[str, set[str]] = {}
    for situation in account._situations():
        for role in situation.participants:
            placements.setdefault(role.entity, set()).add(situation.context)

    # Decisions temporarily point to first-pass classes; remap once complete memberships exist.
    decisions: list[AttributedMentionIdentity] = []
    additions: dict[str, list[str]] = {}
    for original in base.mentions:
        contexts = tuple(sorted(placements.get(original.mention, ())))
        decision = AttributedMentionIdentity(**original.model_dump(), contexts=contexts)
        # Invalid, missing or unsupported form input must never be rescued by a holder.
        if original.outcome == "open" and original.reason == "holder-not-supplied":
            annotation = MentionAnnotation.model_validate(supplied[original.mention])
            if len(contexts) != 1:
                decision = decision.model_copy(update={"reason": "ambiguous-context"})
            else:
                holder = holders[contexts[0]]
                if holder.referent is None:
                    decision = decision.model_copy(update={"reason": "holder-unresolved"})
                elif (
                    base_referents[holder.referent].document
                    != entities[original.mention].origin_document
                ):
                    decision = decision.model_copy(update={"reason": "holder-document-mismatch"})
                elif annotation.person == "first" and annotation.number == "singular":
                    additions.setdefault(holder.referent, []).append(original.mention)
                    decision = decision.model_copy(
                        update={
                            "outcome": "resolved",
                            "referent": holder.referent,
                            "rule": "speech-holder/v1",
                            "reason": None,
                        }
                    )
                else:
                    reason: HeldIdentityReason = (
                        "needs-addressee"
                        if annotation.person == "second"
                        else "speaker-group"
                        if annotation.number == "plural"
                        else "speaker-number-unknown"
                    )
                    decision = decision.model_copy(
                        update={"reason": reason, "candidates": (holder.referent,)}
                    )
        decisions.append(decision)

    final_classes = {
        r.id: AttributedReferent(
            document=r.document,
            base_referent=r.id,
            members=tuple(sorted((*r.members, *additions.get(r.id, ())))),
        )
        for r in base.referents
    }
    final_decisions = tuple(
        AttributedMentionIdentity(
            **{
                **d.model_dump(),
                "referent": final_classes[d.referent].id if d.referent is not None else None,
                "candidates": tuple(sorted(final_classes[c].id for c in d.candidates)),
            }
        )
        for d in decisions
    )
    return AttributedIdentityResult(
        attribution=checked,
        referents=tuple(sorted(final_classes.values(), key=lambda r: r.id)),
        mentions=final_decisions,
    )


def verify_attributed_identity(
    value: SupportedAccount,
    identity_annotations: str,
    holder_proposals: str,
    result: AttributedIdentityResult,
) -> AttributedIdentityResult:
    """Verify all final classes and decisions against the original account and recordings."""
    checked = _validate_instance(AttributedIdentityResult, result)
    expected = resolve_attributed_identity(value, identity_annotations, holder_proposals)
    if checked != expected:
        raise ValueError("attributed identity differs from recorded inputs")
    return expected


class AttributedIdentityRole(Model):
    """A role keeps its occurrence, context and evidence while exposing checked final identity."""

    situation: Name
    kind: Literal["event", "state"]
    context: Name
    mention: Name
    role: Name
    evidence: tuple[Support, ...]
    identity: AttributedMentionIdentity


def attributed_identity_roles(
    value: SupportedAccount,
    identity_annotations: str,
    holder_proposals: str,
    result: AttributedIdentityResult,
) -> tuple[AttributedIdentityRole, ...]:
    """Consume final identity for every event/state role without rewriting the account graph."""
    account = validate_supported_account(value)
    checked = verify_attributed_identity(account, identity_annotations, holder_proposals, result)
    identities = {m.mention: m for m in checked.mentions}
    return tuple(
        AttributedIdentityRole(
            situation=s.id,
            kind="state" if isinstance(s, State) else "event",
            context=s.context,
            mention=p.entity,
            role=p.role,
            evidence=p.evidence,
            identity=identities[p.entity],
        )
        for s in account._situations()
        for p in s.participants
    )
