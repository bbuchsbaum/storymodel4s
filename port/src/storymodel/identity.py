"""Conservative, document-local text identity over unchanged mention occurrences.

Explicit form annotations supply the classification that Scala obtains from checked
charts. Missing annotations never become nominals or silently narrow antecedent sets.
"""

from __future__ import annotations

import unicodedata
from hashlib import sha256
from typing import ClassVar, Literal, Self

from pydantic import Field, model_validator

from ._admission import Quote
from .model import (
    Account,
    Digest,
    Identified,
    Model,
    Name,
    Support,
    _validate_instance,
    contained_in,
    validate_account,
)
from .recall_intake import _simple_lower
from .serialization import canonical, digest, strict_json

RECIPE = "annotated-text-identity/v1"
OpenIdentityReason = Literal[
    "form-not-supplied",
    "annotation-not-attempted",
    "annotation-failed",
    "annotation-unsupported",
    "invalid-annotation",
    "nonlocal-mention-support",
    "unrecognized-form",
    "incomplete-antecedent-input",
    "several-antecedents",
    "no-antecedent",
    "context-unknown",
    "outside-held-context",
    "holder-not-supplied",
    "ambiguous-context",
]


class EntityKind(Model):
    """A declared entity family; unknown ontology types carry an explicit namespace."""

    kind: Literal["person", "group", "object", "location", "abstract", "custom"]
    namespace: Name | None = None
    label: Name | None = None

    @model_validator(mode="after")
    def custom(self) -> Self:
        if (self.namespace is not None and self.label is not None) != (self.kind == "custom"):
            raise ValueError("custom entity kinds require exactly namespace and label")
        if self.kind != "custom" and (self.namespace is not None or self.label is not None):
            raise ValueError("built-in entity kinds cannot carry custom fields")
        return self


class MentionAnnotation(Model):
    """A recorded form interpretation supported by an exact mention quote, not a referent ID."""

    mention: Name
    status: Literal["provided", "not-attempted", "failed", "unsupported"]
    form: Literal["name", "nominal", "pronominal", "other"] | None = None
    quote: Quote | None = None
    entity_type: EntityKind | None = None
    person: Literal["first", "second", "third"] | None = None
    number: Literal["singular", "plural", "unknown"] = "unknown"
    reason: str | None = None

    @model_validator(mode="after")
    def shape(self) -> Self:
        if self.status != "provided":
            if (
                not self.reason
                or any((self.form, self.quote, self.entity_type, self.person))
                or self.number != "unknown"
            ):
                raise ValueError("unprocessed annotation requires only a reason")
        else:
            if self.form is None or self.quote is None:
                raise ValueError("provided annotation requires a form and exact quote")
            if (self.form == "pronominal") != (self.person is not None):
                raise ValueError("grammatical person belongs exactly to pronominal forms")
            if self.entity_type is not None and self.form not in ("name", "nominal"):
                raise ValueError("entity type is supported only for introducing forms")
            if self.form != "pronominal" and self.number != "unknown":
                raise ValueError("antecedent quantity evidence is not supported by this profile")
            if self.form == "other" and not self.reason:
                raise ValueError("unrecognized form requires a reason")
        return self


class IdentityAnnotations(Model):
    """Input-bound annotation provenance; missing mentions stay explicitly unprocessed."""

    schema_version: Literal["storymodel.identity-annotations/v1"] = (
        "storymodel.identity-annotations/v1"
    )
    account_sha256: Digest
    origin: Name
    mentions: tuple[MentionAnnotation, ...]


class TextReferent(Identified):
    """A snapshot-derived identity class, never a replacement for a local mention ID."""

    namespace: ClassVar[str] = "text-referent"
    document: Name
    rule: Literal["annotated-text-identity/v1"] = "annotated-text-identity/v1"
    members: tuple[Name, ...] = Field(min_length=1)

    @model_validator(mode="after")
    def ordered_members(self) -> Self:
        if tuple(sorted(set(self.members))) != self.members:
            raise ValueError("referent members must be unique and canonically sorted")
        return self


class MentionIdentity(Model):
    """One occurrence's identity outcome; open mentions receive no invented referent."""

    mention: Name
    outcome: Literal["introducing", "resolved", "open"]
    referent: str | None = None
    candidates: tuple[str, ...] = ()
    rule: str | None = None
    reason: OpenIdentityReason | None = None
    explanation: str | None = None


class IdentityResult(Model):
    """Complete identity decisions tied to the graph, raw annotations and text normalization."""

    schema_version: Literal["storymodel.identity-result/v1"] = "storymodel.identity-result/v1"
    recipe: Literal["annotated-text-identity/v1"] = "annotated-text-identity/v1"
    unicode_version: Name = unicodedata.unidata_version
    account_sha256: Digest
    response_sha256: Digest
    origin: Name
    referents: tuple[TextReferent, ...]
    mentions: tuple[MentionIdentity, ...]


def resolve_text_identity(value: Account, raw: str) -> IdentityResult:
    """Apply introducing-label/type and unique-preceding-antecedent rules within documents.

    Forms/types are explicit interpretations, not grammar certified by quotation. This
    first profile has no quantity or accepted holder inputs. It preserves those gaps.
    """
    account = validate_account(value)
    account_sha = digest(account.model_dump(mode="json"))
    parsed = strict_json(raw)
    if (
        not isinstance(parsed, dict)
        or set(parsed) != {"schema_version", "account_sha256", "origin", "mentions"}
        or parsed["schema_version"] != "storymodel.identity-annotations/v1"
        or parsed["account_sha256"] != account_sha
        or not isinstance(parsed["origin"], str)
        or not parsed["origin"]
        or not isinstance(parsed["mentions"], list)
    ):
        raise ValueError("invalid or foreign identity annotation envelope")
    entities = {e.id: e for e in account.entities}
    documents = {d.id: d for d in account.documents}
    supplied = {}
    for item in parsed["mentions"]:
        mention = item.get("mention") if isinstance(item, dict) else None
        if not isinstance(mention, str) or mention not in entities or mention in supplied:
            raise ValueError("duplicate or foreign annotated mention")
        supplied[mention] = item
    annotations = {}
    reasons: dict[str, OpenIdentityReason] = {}
    explanations: dict[str, str | None] = {}
    positions = {}
    ends = {}
    for entity in account.entities:
        positions[entity.id] = min(s.start for evidence in entity.evidence for s in evidence.spans)
        ends[entity.id] = max(s.end for evidence in entity.evidence for s in evidence.spans)
        if len(entity.evidence) != 1 or len(entity.evidence[0].spans) != 1:
            reasons[entity.id] = "nonlocal-mention-support"
            continue
        if entity.id not in supplied:
            reasons[entity.id] = "form-not-supplied"
            continue
        try:
            annotation = MentionAnnotation.model_validate_json(canonical(supplied[entity.id]))
            if annotation.status != "provided":
                reasons[entity.id] = (
                    "annotation-not-attempted"
                    if annotation.status == "not-attempted"
                    else "annotation-failed"
                    if annotation.status == "failed"
                    else "annotation-unsupported"
                )
                explanations[entity.id] = annotation.reason
                continue
            assert annotation.quote is not None
            support = Support(document=entity.origin_document, spans=(annotation.quote.span,))
            if not contained_in(support, entity.evidence):
                raise ValueError("form evidence lies outside the mention support")
            if (
                annotation.quote.span.slice(documents[entity.origin_document].text)
                != annotation.quote.text
            ):
                raise ValueError("form quote differs from the mention document")
            annotations[entity.id] = annotation
            if annotation.form == "other":
                reasons[entity.id] = "unrecognized-form"
                explanations[entity.id] = annotation.reason
        except ValueError as error:
            reasons[entity.id] = "invalid-annotation"
            explanations[entity.id] = str(error)

    # Unknown entity types do not form a shared invented type; introduce singletons.
    groups: dict[tuple[str, str, str], list[str]] = {}
    for mention, annotation in annotations.items():
        if annotation.form in ("name", "nominal"):
            entity = entities[mention]
            type_key = (
                canonical(annotation.entity_type.model_dump(mode="json"))
                if annotation.entity_type
                else mention
            )
            groups.setdefault(
                (entity.origin_document, _simple_lower(entity.label), type_key), []
            ).append(mention)
    ordered_groups = sorted(groups.values(), key=lambda members: min(members))
    introducing: dict[str, int] = {}
    group_documents = []
    for index, members in enumerate(ordered_groups):
        introducing.update((m, index) for m in members)
        group_documents.append(entities[members[0]].origin_document)
    attached: dict[str, int] = dict(introducing)
    context_by_id = {c.id: c for c in account.contexts}
    mention_contexts: dict[str, set[str]] = {}
    for event in account.events:
        for participation in event.participants:
            mention_contexts.setdefault(participation.entity, set()).add(event.context)
    candidates: dict[str, tuple[int, ...]] = {}
    for mention in sorted(entities, key=lambda m: (entities[m].origin_document, positions[m], m)):
        current_annotation = annotations.get(mention)
        if current_annotation is None or current_annotation.form != "pronominal":
            continue
        if current_annotation.person != "third":
            # No accepted placement/holder contract exists yet; nearby speaker roles are not it.
            kinds = {context_by_id[c].kind for c in mention_contexts.get(mention, ())}
            reasons[mention] = (
                "context-unknown"
                if not kinds
                else "outside-held-context"
                if kinds == {"narrated"}
                else "holder-not-supplied"
                if "narrated" not in kinds
                else "ambiguous-context"
            )
            continue
        document = entities[mention].origin_document
        available = tuple(
            index
            for index in range(len(ordered_groups))
            if group_documents[index] == document
            and any(ends[m] <= positions[mention] for m in ordered_groups[index])
        )
        candidates[mention] = available
        # A missing/unsupported earlier form could be another introducing mention.
        incomplete = any(
            entities[m].origin_document == document
            and m != mention
            and positions[m] <= positions[mention]
            and (m not in annotations or annotations[m].form == "other")
            for m in entities
        )
        incomplete = incomplete or any(
            entities[m].origin_document == document and positions[m] <= positions[mention] < ends[m]
            for m in introducing
        )
        if incomplete:
            reasons[mention] = "incomplete-antecedent-input"
        elif len(available) == 1:
            attached[mention] = available[0]
        else:
            reasons[mention] = "several-antecedents" if available else "no-antecedent"

    members_by_group = [list(members) for members in ordered_groups]
    for mention, group in attached.items():
        if mention not in introducing:
            members_by_group[group].append(mention)
    referents = tuple(
        TextReferent(document=group_documents[i], members=tuple(sorted(members)))
        for i, members in enumerate(members_by_group)
    )
    decisions = []
    for mention in sorted(entities):
        resolved_group = attached.get(mention)
        if resolved_group is not None:
            is_introducing = mention in introducing
            decisions.append(
                MentionIdentity(
                    mention=mention,
                    outcome="introducing" if is_introducing else "resolved",
                    referent=referents[resolved_group].id,
                    rule=(
                        "introducing-label/v1"
                        if annotations[mention].entity_type
                        else "introducing-singleton/v1"
                    )
                    if is_introducing
                    else "pronoun-unique-antecedent/v1",
                )
            )
        else:
            decisions.append(
                MentionIdentity(
                    mention=mention,
                    outcome="open",
                    reason=reasons[mention],
                    explanation=explanations.get(mention),
                    candidates=tuple(sorted(referents[i].id for i in candidates.get(mention, ()))),
                )
            )
    return IdentityResult(
        account_sha256=account_sha,
        response_sha256=sha256(raw.encode()).hexdigest(),
        origin=parsed["origin"],
        referents=tuple(sorted(referents, key=lambda r: r.id)),
        mentions=tuple(decisions),
    )


def verify_identity(value: Account, raw: str, result: IdentityResult) -> IdentityResult:
    """Recompute the sidecar against original graph and annotation bytes before consumption."""
    checked = _validate_instance(IdentityResult, result)
    expected = resolve_text_identity(value, raw)
    if checked != expected:
        raise ValueError("identity result differs from recorded inputs")
    return expected


class IdentityRoleRow(Model):
    """Event roles retain context and occurrence evidence even when referent identity is open."""

    event: Name
    context: Name
    mention: Name
    role: Name
    evidence: tuple[Support, ...]
    identity: MentionIdentity


def identity_role_table(
    value: Account, raw: str, result: IdentityResult
) -> tuple[IdentityRoleRow, ...]:
    """Consume a verified identity sidecar without rewriting or promoting any event claim."""
    account = validate_account(value)
    checked = verify_identity(account, raw, result)
    identities = {m.mention: m for m in checked.mentions}
    return tuple(
        IdentityRoleRow(
            event=e.id,
            context=e.context,
            mention=p.entity,
            role=p.role,
            evidence=p.evidence,
            identity=identities[p.entity],
        )
        for e in account.events
        for p in e.participants
    )
