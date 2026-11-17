"""Sparse, inspectable local reference baseline; no fidelity or sequence inference.

Lexical overlap nominates events. A declared rule admits reference hypotheses only
with predicate, role, context and row-anchor support. No score is a probability.
"""

from __future__ import annotations

import unicodedata
from collections import defaultdict
from typing import Literal, Self

from pydantic import Field, model_validator

from .model import (
    Account,
    Binding,
    Digest,
    Event,
    Hypothesis,
    Model,
    Name,
    Recipe,
    Reference,
    Support,
    Unit,
    _validate_instance,
    canonical,
    contained,
    digest,
    validate_account,
)
from .serialization import strict_json

RECIPE = "local-predicate-role-reference/v1"


def normalize(text: str) -> str:
    """Case/whitespace normalization only: retain negation, punctuation and word order."""
    return " ".join(text.casefold().split())


class PredicateAliases(Model):
    """Caller-declared equivalence for this application, never an implicit thesaurus."""

    terms: tuple[Name, ...] = Field(min_length=2)
    basis: Name


class AlignmentPolicy(Model):
    """Versioned local rule and retrieval budget, bound into every derived account."""

    schema_version: Literal["storymodel.alignment-policy/v1"] = "storymodel.alignment-policy/v1"
    aliases: tuple[PredicateAliases, ...] = ()
    max_candidates: int = Field(default=64, ge=1, le=10000)
    normalization: Literal["casefold-whitespace/v1"] = "casefold-whitespace/v1"
    unicode_version: str = unicodedata.unidata_version

    @model_validator(mode="after")
    def unambiguous(self) -> Self:
        if self.unicode_version != unicodedata.unidata_version:
            raise ValueError("alignment Unicode version differs from this runtime")
        seen: set[str] = set()
        for group in self.aliases:
            for term in group.terms:
                key = normalize(term)
                if not key or key in seen:
                    raise ValueError("blank, duplicate or overlapping predicate alias")
                seen.add(key)
        return self


def validate_policy(value: AlignmentPolicy | str | bytes) -> AlignmentPolicy:
    if isinstance(value, AlignmentPolicy):
        return _validate_instance(AlignmentPolicy, value)
    return AlignmentPolicy.model_validate_json(canonical(strict_json(value)))


class RoleMatch(Model):
    """A shared role/label is lexical evidence, not a cross-account identity assertion."""

    role: Literal["agent", "patient", "speaker", "recipient", "experiencer", "location"]
    label: str
    recall_entity: str
    source_entity: str
    recall_evidence: tuple[Support, ...]
    source_evidence: tuple[Support, ...]


class Candidate(Model):
    """Separate channels and gates for one nominated source event."""

    source_event: str
    predicate_match: Literal["exact", "declared-alias", "none"]
    recall_predicate: str
    source_predicate: str
    recall_evidence: tuple[Support, ...]
    source_evidence: tuple[Support, ...]
    role_matches: tuple[RoleMatch, ...]
    conflicting_roles: tuple[str, ...]
    context: Literal["root-compatible", "attributed-unverified", "kind-mismatch"]
    source_rows: tuple[str, ...]
    eligible: bool
    reasons: tuple[str, ...]


class EventCandidates(Model):
    """A bounded nomination set; overflow withholds resolution, not only lower-ranked hits."""

    recall_event: str
    retrieved_count_lower_bound: int
    truncated: bool
    candidates: tuple[Candidate, ...]


class DetailResolution(Model):
    """How an existing reference was preserved or an unbound detail was localized."""

    detail: str
    action: Literal["preserved", "generated"]
    reference: Reference
    source_events: tuple[str, ...] = ()


class AlignmentResult(Model):
    """Input-bound diagnostics and the existing account with reference bindings added."""

    schema_version: Literal["storymodel.alignment-result/v1"] = "storymodel.alignment-result/v1"
    recipe: Literal["local-predicate-role-reference/v1"] = "local-predicate-role-reference/v1"
    input_sha256: Digest
    policy: AlignmentPolicy
    source_event_universe: tuple[str, ...]
    events: tuple[EventCandidates, ...]
    details: tuple[DetailResolution, ...]
    account: Account


def align_account(value: Account, policy: AlignmentPolicy | None = None) -> AlignmentResult:
    """Fill missing referent bindings using a named conservative heuristic.

    Existing bindings, addresses, inventories, entity identities and fidelity are
    untouched. No-match is unresolved, never external. The generator uses inverted
    predicate/role indexes and stores at most the declared budget per recall event.
    """
    account = validate_account(value)
    policy = validate_policy(policy if policy is not None else AlignmentPolicy())
    input_sha = digest(account.model_dump(mode="json"))
    entities = {e.id: e for e in account.entities}
    contexts = {c.id: c for c in account.contexts}
    events = {e.id: e for e in account.events}
    source_events = sorted((e for e in account.events if e.side == "source"), key=lambda e: e.id)
    aliases = {
        normalize(t): min(normalize(x) for x in g.terms) for g in policy.aliases for t in g.terms
    }

    def predicate_key(event: Event) -> str:
        key = normalize(event.predicate)
        return aliases.get(key, key)

    def role_keys(event: Event) -> set[tuple[str, str]]:
        return {
            (p.role, normalize(entities[p.entity].label))
            for p in event.participants
            if normalize(entities[p.entity].label)
        }

    predicate_index: dict[str, list[str]] = defaultdict(list)
    role_index: dict[tuple[str, str], list[str]] = defaultdict(list)
    for event in source_events:
        if predicate_key(event):
            predicate_index[predicate_key(event)].append(event.id)
        for role in sorted(role_keys(event)):
            role_index[role].append(event.id)
    rows_by_document: dict[str, list[Unit]] = defaultdict(list)
    for unit in account.units:
        if unit.kind == "row":
            rows_by_document[unit.support.document].append(unit)
    row_cache: dict[str, tuple[str, ...]] = {}

    def row_targets(event: Event) -> tuple[str, ...]:
        if event.id not in row_cache:
            # Whole event support must fit one row, not just overlap it. No invented joint/broad.
            row_cache[event.id] = tuple(
                sorted(
                    row.id
                    for row in rows_by_document.get(event.evidence[0].document, ())
                    if all(contained(e, row.support) for e in event.evidence)
                )
            )
        return row_cache[event.id]

    def candidate(recall: Event, source: Event) -> Candidate:
        left, right = normalize(recall.predicate), normalize(source.predicate)
        predicate_match: Literal["exact", "declared-alias", "none"] = (
            "exact"
            if left and left == right
            else "declared-alias"
            if left and predicate_key(recall) == predicate_key(source)
            else "none"
        )
        matched: list[RoleMatch] = []
        for rp in recall.participants:
            for sp in source.participants:
                label = normalize(entities[rp.entity].label)
                if label and rp.role == sp.role and label == normalize(entities[sp.entity].label):
                    matched.append(
                        RoleMatch(
                            role=rp.role,
                            label=label,
                            recall_entity=rp.entity,
                            source_entity=sp.entity,
                            recall_evidence=rp.evidence,
                            source_evidence=sp.evidence,
                        )
                    )
        lr, rr = role_keys(recall), role_keys(source)
        shared_roles = {r for r, _ in lr} & {r for r, _ in rr}
        conflicts = tuple(
            sorted(
                r
                for r in shared_roles
                if not (
                    {label for role, label in lr if role == r}
                    & {label for role, label in rr if role == r}
                )
            )
        )
        lc, rc = contexts[recall.context], contexts[source.context]
        context: Literal["root-compatible", "attributed-unverified", "kind-mismatch"] = (
            "kind-mismatch"
            if lc.kind != rc.kind
            else "root-compatible"
            if lc.kind == "narrated"
            else "attributed-unverified"
        )
        rows = row_targets(source)
        reasons = []
        if predicate_match == "none":
            reasons.append("no predicate support")
        if not matched:
            reasons.append("no matching participant role")
        if conflicts:
            reasons.append("conflicting participant labels within a shared role")
        if context != "root-compatible":
            reasons.append("attributed context correspondence is not established")
        if len(rows) != 1:
            reasons.append("source event lacks one unambiguous containing row")
        return Candidate(
            source_event=source.id,
            predicate_match=predicate_match,
            recall_predicate=recall.predicate,
            source_predicate=source.predicate,
            recall_evidence=recall.evidence,
            source_evidence=source.evidence,
            role_matches=tuple(
                sorted(matched, key=lambda m: (m.role, m.label, m.recall_entity, m.source_entity))
            ),
            conflicting_roles=conflicts,
            context=context,
            source_rows=rows,
            eligible=not reasons,
            reasons=tuple(reasons),
        )

    existing = {(b.detail, b.reference.interpretation): b for b in account.bindings}
    needed = {d.event for d in account.details if d.event and (d.id, "referent") not in existing}
    nominations: dict[str, EventCandidates] = {}
    for recall_id in sorted(needed):
        recall = events[recall_id]
        postings = [predicate_index.get(predicate_key(recall), [])]
        postings.extend(role_index.get(key, []) for key in sorted(role_keys(recall)))
        found: set[str] = set()
        for posting in postings:
            for source_id in posting:
                found.add(source_id)
                if len(found) > policy.max_candidates:
                    break
            if len(found) > policy.max_candidates:
                break
        truncated = len(found) > policy.max_candidates
        kept = sorted(found)[: policy.max_candidates]
        nominations[recall_id] = EventCandidates(
            recall_event=recall_id,
            retrieved_count_lower_bound=len(found),
            truncated=truncated,
            candidates=tuple(candidate(recall, events[s]) for s in kept),
        )
    bindings = list(account.bindings)
    resolutions: list[DetailResolution] = []
    for detail in sorted(account.details, key=lambda d: d.id):
        previous = existing.get((detail.id, "referent"))
        if previous:
            resolutions.append(
                DetailResolution(detail=detail.id, action="preserved", reference=previous.reference)
            )
            continue
        group = nominations.get(detail.event or "")
        selected: tuple[Candidate, ...] = ()
        if group is None:
            reference = Reference(status="not-attempted", reason="detail has no recall event")
        elif group.truncated:
            reference = Reference(
                status="search-limited", reason="candidate budget exceeded; resolution withheld"
            )
        else:
            selected = tuple(c for c in group.candidates if c.eligible)
            targets = [c.source_rows[0] for c in selected]
            if not selected:
                reference = Reference(
                    status="attempted",
                    outcome="unresolved",
                    reason="no candidate satisfies the declared local rule",
                )
            elif len(set(targets)) != len(targets):
                reference = Reference(
                    status="attempted",
                    outcome="unresolved",
                    reason=(
                        "distinct source events share a row; "
                        "row hypotheses cannot express this ambiguity"
                    ),
                )
            else:
                reference = Reference(
                    status="attempted",
                    outcome="resolved" if len(selected) == 1 else "alternative",
                    hypotheses=tuple(
                        Hypothesis(kind="single", targets=(t,)) for t in sorted(targets)
                    ),
                )
        bindings.append(Binding(detail=detail.id, reference=reference))
        resolutions.append(
            DetailResolution(
                detail=detail.id,
                action="generated",
                reference=reference,
                source_events=tuple(c.source_event for c in selected),
            )
        )
    derived = validate_account(
        account.model_copy(
            update={
                "bindings": tuple(bindings),
                "recipe": Recipe(
                    name=RECIPE,
                    input_sha256=tuple(
                        dict.fromkeys(
                            (
                                *account.recipe.input_sha256,
                                input_sha,
                                digest(policy.model_dump(mode="json")),
                            )
                        )
                    ),
                ),
            }
        )
    )
    return AlignmentResult(
        input_sha256=input_sha,
        policy=policy,
        source_event_universe=tuple(e.id for e in source_events),
        events=tuple(nominations.values()),
        details=tuple(resolutions),
        account=derived,
    )


def verify_alignment(original: Account, result: AlignmentResult | str | bytes) -> AlignmentResult:
    """Recompute diagnostics and bindings against the original input before trusting a receipt."""
    checked = (
        _validate_instance(AlignmentResult, result)
        if isinstance(result, AlignmentResult)
        else AlignmentResult.model_validate_json(canonical(strict_json(result)))
    )
    expected = align_account(original, checked.policy)
    if checked != expected:
        raise ValueError("alignment receipt differs from deterministic recomputation")
    return expected
