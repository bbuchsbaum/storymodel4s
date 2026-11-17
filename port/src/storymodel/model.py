"""Small, checked narrative accounts with explicit evidence and reference semantics.

These are experimental wire types, not a translation of the Scala object hierarchy.
Public consumers call ``validate_account`` even when given an existing Python object.
"""

from __future__ import annotations

import hashlib
from collections.abc import Iterable
from typing import Annotated, Any, ClassVar, Literal, Self

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, model_validator

from .serialization import canonical as canonical
from .serialization import digest as digest
from .serialization import strict_json

Name = Annotated[str, Field(min_length=1)]
Digest = Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]
Side = Literal["source", "recall"]


class Model(BaseModel):
    """Strict immutable records; contextual validity belongs to Account admission."""

    model_config = ConfigDict(
        extra="forbid", frozen=True, strict=True, revalidate_instances="always"
    )


class Identified(Model):
    """Content-derived identities refuse caller substitutions on readback."""

    namespace: ClassVar[str] = ""
    id: str = ""

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        if "namespace" not in cls.__dict__ or not cls.namespace:
            raise TypeError("identified subclasses require an explicit stable namespace")

    def identity_fields(self) -> dict[str, Any]:
        return self.model_dump(mode="json", exclude={"id"})

    @model_validator(mode="after")
    def identify(self) -> Self:
        expected = self.namespace + ":" + digest(self.identity_fields())
        if self.id and self.id != expected:
            raise ValueError("identity differs from content")
        object.__setattr__(self, "id", expected)
        return self


class Span(Model):
    """Half-open canonical UTF-16 support, never ordinary Python character indices."""

    start: Annotated[int, Field(ge=0)]
    end: Annotated[int, Field(ge=0)]

    @model_validator(mode="after")
    def ordered(self) -> Self:
        if self.end <= self.start:
            raise ValueError("evidence span must be nonempty and ordered")
        return self

    def slice(self, text: str) -> str:
        encoded = text.encode("utf-16-le")
        if self.end * 2 > len(encoded):
            raise ValueError("span exceeds document")
        try:
            return encoded[self.start * 2 : self.end * 2].decode("utf-16-le")
        except UnicodeDecodeError as error:
            raise ValueError("span splits a surrogate pair") from error


class Document(Identified):
    """A named source edition or recall session and its exact canonical text."""

    namespace: ClassVar[str] = "document"
    key: Name
    side: Side
    text: str
    sha256: Digest
    canonicalization: Literal["identity/v1"] = "identity/v1"

    @model_validator(mode="after")
    def checksum(self) -> Self:
        if hashlib.sha256(self.text.encode("utf-8")).hexdigest() != self.sha256:
            raise ValueError("document checksum differs")
        return self

    @classmethod
    def create(cls, key: str, side: Side, text: str) -> Document:
        return cls(key=key, side=side, text=text, sha256=hashlib.sha256(text.encode()).hexdigest())


class Support(Model):
    """Discontinuous evidence in exactly one named document."""

    document: Name
    spans: Annotated[tuple[Span, ...], Field(min_length=1)]

    @model_validator(mode="after")
    def ordered(self) -> Self:
        if any(a.end > b.start for a, b in zip(self.spans, self.spans[1:])):
            raise ValueError("support spans must be sorted and disjoint")
        return self


Evidence = Annotated[tuple[Support, ...], Field(min_length=1)]


class Unit(Identified):
    """An occurrence anchored in a source or recall document."""

    namespace: ClassVar[str] = "unit"
    kind: Literal["row", "scene", "recall"]
    support: Support
    parent: str | None = None


class Context(Identified):
    """Narrated worlds and attributed content remain separate on each account side."""

    namespace: ClassVar[str] = "context"
    side: Side
    kind: Literal["narrated", "reported", "believed", "intended"]
    parent: str | None = None
    evidence: tuple[Support, ...] = ()


class Inventory(Model):
    """Explicit extraction completeness; zero details alone says nothing about processing."""

    unit: Name
    status: Literal["completed", "not-attempted", "failed", "search-limited"]
    reason: str | None = None

    @model_validator(mode="after")
    def explanation(self) -> Self:
        if self.status != "completed" and not self.reason:
            raise ValueError("incomplete inventory requires a reason")
        return self


class Entity(Identified):
    """Document-local identity; v2 permits recall origins without source identity claims.

    ``source_document`` is the retained wire name for the origin document, preserving IDs.
    """

    namespace: ClassVar[str] = "entity"
    source_document: Name = Field(
        validation_alias=AliasChoices("source_document", "origin_document"),
        serialization_alias="source_document",
    )
    label: Name
    evidence: Evidence

    @property
    def origin_document(self) -> Name:
        """The idiomatic Python name for this entity's document-local origin."""
        return self.source_document

    @classmethod
    def create(cls, origin_document: str, label: str, evidence: Evidence) -> Entity:
        """Construct with a statically typed origin name while retaining legacy wire fields."""
        return cls(source_document=origin_document, label=label, evidence=evidence)


class Participation(Model):
    """An evidenced role assignment, distinct from entity identity."""

    entity: Name
    role: Literal["agent", "patient", "speaker", "recipient", "experiencer", "location"]
    evidence: Evidence


class Event(Identified):
    """An occurrence can group many details without making each detail an event."""

    namespace: ClassVar[str] = "event"
    side: Side
    context: Name
    predicate: Name
    participants: tuple[Participation, ...] = ()
    evidence: Evidence


class State(Identified):
    """A contextual condition is distinct from an event, with explicit unknown truth attributes."""

    namespace: ClassVar[str] = "state"
    side: Side
    context: Name
    predicate: Name
    participants: tuple[Participation, ...] = ()
    evidence: Evidence
    polarity: Literal["positive", "negative", "unknown"] = "unknown"
    modality: Literal[
        "asserted",
        "possible",
        "probable",
        "necessary",
        "intended",
        "desired",
        "counterfactual",
        "reported",
        "unknown",
    ] = "unknown"


class Relation(Identified):
    """Typed, evidenced relationships between contextualized occurrences."""

    namespace: ClassVar[str] = "relation"
    kind: Literal["part_of", "before", "causes", "references"]
    source: Name
    target: Name
    context: Name
    evidence: Evidence


class Detail(Identified):
    """Recall content has occurrence identity independent of its later fidelity assessment."""

    namespace: ClassVar[str] = "detail"
    unit: Name
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
    content: Name
    support: Support
    address: Literal["target", "other", "personal", "general", "unresolved", "extended"]
    discourse: Literal["content", "commentary", "inference", "repair", "repetition"] = "content"
    event: str | None = None

    def identity_fields(self) -> dict[str, Any]:
        return self.model_dump(mode="json", include={"unit", "kind", "content", "support"})


class Hypothesis(Identified):
    """A single, joint, broad or external reference; no invented probability."""

    namespace: ClassVar[str] = "hypothesis"
    kind: Literal["single", "joint", "broad", "external"]
    targets: tuple[str, ...] = ()
    external: Literal["personal", "general", "other_episode"] | None = None

    @model_validator(mode="after")
    def shape(self) -> Self:
        if len(set(self.targets)) != len(self.targets):
            raise ValueError("duplicate hypothesis target")
        if self.kind == "external":
            if self.targets or self.external is None:
                raise ValueError("external hypothesis needs a destination and no source targets")
        elif self.external is not None or not self.targets:
            raise ValueError("source hypothesis needs targets and no external destination")
        elif self.kind in ("single", "broad") and len(self.targets) != 1:
            raise ValueError("single/broad hypothesis needs one source unit")
        elif self.kind == "joint" and len(self.targets) < 2:
            raise ValueError("joint hypothesis needs multiple source units")
        return self


class Reference(Model):
    """Reference processing status and reference outcome are orthogonal."""

    status: Literal["attempted", "not-attempted", "failed", "search-limited"]
    interpretation: Literal["narrative", "referent"] = "referent"
    outcome: Literal["resolved", "alternative", "unresolved"] | None = None
    hypotheses: tuple[Hypothesis, ...] = ()
    reason: str | None = None

    @model_validator(mode="after")
    def shape(self) -> Self:
        if self.status != "attempted":
            if self.outcome is not None or self.hypotheses or not self.reason:
                raise ValueError("unattempted/failed/search-limited reference needs only a reason")
        elif self.outcome == "unresolved":
            if self.hypotheses or not self.reason:
                raise ValueError("unresolved reference needs a reason and no hypotheses")
        elif self.outcome in ("resolved", "alternative"):
            n = len(self.hypotheses)
            if (self.outcome == "resolved" and n != 1) or (self.outcome == "alternative" and n < 2):
                raise ValueError("reference hypothesis count differs from its meaning")
            if len({h.id for h in self.hypotheses}) != n:
                raise ValueError("duplicate alternative hypothesis")
        else:
            raise ValueError("attempted reference requires an outcome")
        return self


class Assessment(Model):
    """Fidelity is conditional on one explicit reference hypothesis."""

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
    reason: str | None = None
    evidence: tuple[Support, ...] = ()

    @model_validator(mode="after")
    def shape(self) -> Self:
        if self.status == "completed":
            if self.grade is None:
                raise ValueError("completed assessment needs a grade")
            if self.grade in ("supported", "contradicted", "gist-detail-error", "invented"):
                if not self.evidence:
                    raise ValueError("substantive source fidelity requires evidence")
            if (self.grade == "gist-detail-error") != (self.severity is not None):
                raise ValueError("severity belongs exactly to gist-detail-error")
        elif self.grade is not None or self.severity is not None or not self.reason:
            raise ValueError("unassessed fidelity needs a reason and no grade/severity")
        return self


class Binding(Model):
    """Reference and conditional fidelity attach to an already admitted recall detail."""

    detail: Name
    reference: Reference
    assessments: tuple[Assessment, ...] = ()


class Recipe(Model):
    """A named derivation and its input digests, with no credential material."""

    name: Name
    input_sha256: Annotated[tuple[Digest, ...], Field(min_length=1)]


def indexed[T: Identified](items: Iterable[T], label: str) -> dict[str, T]:
    result: dict[str, T] = {}
    for item in items:
        if item.id in result:
            raise ValueError(f"duplicate {label} identity")
        result[item.id] = item
    return result


def contained(child: Support, parent: Support) -> bool:
    """Containment in the exact support union, not the hull across gaps."""
    if child.document != parent.document:
        return False
    for part in child.spans:
        reach = part.start
        for span in parent.spans:
            if span.start <= reach < span.end:
                reach = span.end
            if reach >= part.end:
                break
        if reach < part.end:
            return False
    return True


def contained_in(child: Support, parents: Iterable[Support]) -> bool:
    """Combine same-document evidence without inventing support across gaps."""
    spans = sorted(
        (s for p in parents if p.document == child.document for s in p.spans),
        key=lambda s: (s.start, s.end),
    )
    merged: list[Span] = []
    for span in spans:
        if merged and span.start <= merged[-1].end:
            merged[-1] = Span(start=merged[-1].start, end=max(merged[-1].end, span.end))
        else:
            merged.append(span)
    return bool(merged) and contained(child, Support(document=child.document, spans=tuple(merged)))


def acyclic(edges: Iterable[tuple[str, str]], label: str) -> None:
    """Sparse Kahn traversal; graph validation does not allocate all pairs."""
    outgoing: dict[str, list[str]] = {}
    incoming: dict[str, int] = {}
    for source, target in edges:
        outgoing.setdefault(source, []).append(target)
        incoming.setdefault(source, 0)
        incoming[target] = incoming.get(target, 0) + 1
    ready = [node for node, degree in incoming.items() if degree == 0]
    seen = 0
    while ready:
        source = ready.pop()
        seen += 1
        for target in outgoing.get(source, []):
            incoming[target] -= 1
            if incoming[target] == 0:
                ready.append(target)
    if seen != len(incoming):
        raise ValueError(f"cyclic {label}")


class _AccountFields(Model):
    """Shared flat fields and relational checks; public wire versions declare their own schema."""

    schema_version: str = "storymodel.account/v1"
    documents: tuple[Document, ...]
    units: tuple[Unit, ...]
    contexts: tuple[Context, ...]
    entities: tuple[Entity, ...] = ()
    events: tuple[Event, ...] = ()
    relations: tuple[Relation, ...] = ()
    details: tuple[Detail, ...] = ()
    inventories: tuple[Inventory, ...] = ()
    bindings: tuple[Binding, ...] = ()
    recipe: Recipe
    export_permission: Literal["allowed", "denied"] = "denied"

    def _situations(self) -> tuple[Event | State, ...]:
        return self.events

    @model_validator(mode="after")
    def validate_relations(self) -> Self:
        docs = indexed(self.documents, "document")
        units = indexed(self.units, "unit")
        contexts = indexed(self.contexts, "context")
        entities = indexed(self.entities, "entity")
        events = indexed(self._situations(), "event")
        details = indexed(self.details, "detail")
        indexed(self.relations, "relation")
        if len({(d.side, d.key) for d in docs.values()}) != len(docs):
            raise ValueError("document session/edition keys must be unique per side")

        def support(value: Support, side: Side | None = None) -> None:
            document = docs.get(value.document)
            if document is None or (side is not None and document.side != side):
                raise ValueError("foreign evidence document or wrong account side")
            for span in value.spans:
                span.slice(document.text)

        for unit in units.values():
            support(unit.support, "recall" if unit.kind == "recall" else "source")
            if unit.parent:
                parent = units.get(unit.parent)
                if parent is None or not contained(unit.support, parent.support):
                    raise ValueError("unit parent must contain exact child support")
                if unit.kind != "recall" and parent.kind != "scene":
                    raise ValueError("source hierarchy parents must be scene containers")
        acyclic(((u.id, u.parent) for u in units.values() if u.parent), "unit hierarchy")

        inventoried: set[str] = set()
        for inventory in self.inventories:
            inventory_unit = units.get(inventory.unit)
            if (
                inventory_unit is None
                or inventory_unit.kind != "recall"
                or inventory_unit.id in inventoried
            ):
                raise ValueError("foreign or duplicate recall inventory")
            inventoried.add(inventory_unit.id)

        for context in contexts.values():
            for evidence in context.evidence:
                support(evidence, context.side)
            if context.kind == "narrated":
                if context.parent is not None:
                    raise ValueError("narrated world must be a root")
            else:
                parent_context = contexts.get(context.parent or "")
                if (
                    parent_context is None
                    or parent_context.side != context.side
                    or not context.evidence
                ):
                    raise ValueError("attributed context requires same-side parent and evidence")
        acyclic(((c.id, c.parent) for c in contexts.values() if c.parent), "context hierarchy")
        for side in ("source", "recall"):
            narrated = sum(c.kind == "narrated" and c.side == side for c in contexts.values())
            present = any(document.side == side for document in docs.values())
            if self.schema_version.endswith(("/v3", "/v4")):
                if present and narrated != 1:
                    raise ValueError("each present account side needs exactly one narrated world")
                if not present and any(context.side == side for context in contexts.values()):
                    raise ValueError("absent account sides cannot have contexts")
            elif narrated != 1:
                raise ValueError("each account side needs exactly one narrated world")
        for entity in entities.values():
            origin = docs.get(entity.source_document)
            if origin is None or (self.schema_version.endswith("/v1") and origin.side != "source"):
                raise ValueError(
                    "entity requires a source-local identity in v1, a local origin in v2"
                )
            for evidence in entity.evidence:
                support(evidence, origin.side)
                if evidence.document != entity.source_document:
                    raise ValueError("entity evidence belongs to another source")
        for event in events.values():
            event_context = contexts.get(event.context)
            if event_context is None or event_context.side != event.side:
                raise ValueError("event requires a context on its own account side")
            if len({(p.entity, p.role) for p in event.participants}) != len(event.participants):
                raise ValueError("duplicate participant")
            if any(p.entity not in entities for p in event.participants):
                raise ValueError("foreign event participant")
            for participation in event.participants:
                origin = docs[entities[participation.entity].source_document]
                if origin.side == "recall" and (
                    event.side != "recall" or any(e.document != origin.id for e in event.evidence)
                ):
                    raise ValueError("recall entity belongs to another event document or side")
                if event.side == "source" and not any(
                    e.document == entities[participation.entity].source_document
                    for e in event.evidence
                ):
                    raise ValueError("source event uses an entity from another source")
                for evidence in participation.evidence:
                    support(evidence, event.side)
                    if not contained_in(evidence, event.evidence):
                        raise ValueError("participant role evidence lies outside event support")
            for evidence in event.evidence:
                support(evidence, event.side)
        for relation in self.relations:
            left, right = events.get(relation.source), events.get(relation.target)
            if left is None or right is None or left.id == right.id:
                raise ValueError("relation requires distinct existing event endpoints")
            if relation.context != left.context or left.side != right.side:
                raise ValueError("relation context/side differs from its source event")
            if relation.kind != "references" and left.context != right.context:
                raise ValueError("relation crosses attributed contexts")
            for evidence in relation.evidence:
                support(evidence, left.side)
        for kind in ("part_of", "before", "causes"):
            acyclic(((r.source, r.target) for r in self.relations if r.kind == kind), kind)
        for detail in details.values():
            detail_unit = units.get(detail.unit)
            support(detail.support, "recall")
            if (
                detail_unit is None
                or detail_unit.kind != "recall"
                or not contained(detail.support, detail_unit.support)
            ):
                raise ValueError("detail lies outside its recall unit's exact support")
            if detail.event:
                detail_event = events.get(detail.event)
                if detail_event is None or detail_event.side != "recall":
                    raise ValueError("detail event must belong to recall")
                if not contained_in(detail.support, detail_event.evidence):
                    raise ValueError("detail evidence lies outside its recall event")
        slots: set[tuple[str, str]] = set()
        for binding in self.bindings:
            slot = (binding.detail, binding.reference.interpretation)
            if binding.detail not in details or slot in slots:
                raise ValueError("foreign or duplicate detail binding")
            slots.add(slot)
            hypotheses = indexed(binding.reference.hypotheses, "hypothesis")
            for hypothesis in hypotheses.values():
                for target in hypothesis.targets:
                    if target not in units or units[target].kind == "recall":
                        raise ValueError("reference target must be an existing source unit")
                if hypothesis.kind == "broad" and units[hypothesis.targets[0]].kind != "scene":
                    raise ValueError("broad reference requires a scene container")
            assessed: set[str] = set()
            for assessment in binding.assessments:
                if assessment.hypothesis not in hypotheses or assessment.hypothesis in assessed:
                    raise ValueError("foreign or duplicate fidelity hypothesis")
                assessed.add(assessment.hypothesis)
                for evidence in assessment.evidence:
                    support(evidence)
                    hypothesis = hypotheses[assessment.hypothesis]
                    if hypothesis.kind != "external" and assessment.grade in (
                        "supported",
                        "contradicted",
                        "gist-detail-error",
                        "invented",
                    ):
                        if not contained_in(
                            evidence, (units[t].support for t in hypothesis.targets)
                        ):
                            raise ValueError("fidelity evidence lies outside its source hypothesis")
        required = {d.sha256 for d in docs.values()}
        if not required <= set(self.recipe.input_sha256):
            raise ValueError("recipe does not bind all source/recall document inputs")
        return self


class Account(_AccountFields):
    """One validated paired account; absent information stays absent."""

    schema_version: Literal[
        "storymodel.account/v1", "storymodel.account/v2", "storymodel.account/v3"
    ] = "storymodel.account/v1"


def validate_account(value: Account | str | bytes) -> Account:
    """Revalidate even model_copy/model_construct results at every public operation."""
    if isinstance(value, _AccountFields) and not isinstance(value, Account):
        raise ValueError("legacy account consumer cannot accept a different wire version")
    raw = canonical(value.model_dump(mode="json")) if isinstance(value, Account) else value
    return Account.model_validate_json(canonical(strict_json(raw)))


def account_digest(value: Account) -> str:
    return digest(validate_account(value).model_dump(mode="json"))
