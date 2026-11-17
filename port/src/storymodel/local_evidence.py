"""Account content -> sparse nominations -> mode gates -> costs -> sequence inference.

This version supplies the sketch/lexical path. Chart-aware gates, outcome slots and
structural providers remain separate capabilities; absent charts never become perfect scores.
"""

from __future__ import annotations

import math
from bisect import bisect_left
from collections import defaultdict
from dataclasses import dataclass
from decimal import Decimal, localcontext
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from ._decimal_context import computation_context
from ._lexical import STOPWORDS, lower, stem_set, words
from .model import (
    Digest,
    Event,
    Model,
    Name,
    State,
    Support,
    _validate_instance,
    contained_in,
    text_evidence,
    text_support,
)
from .rich_account import SupportedAccount, require_text_account
from .sequence import (
    External,
    Facet,
    Probability,
    SequenceResult,
    SequenceRow,
    SequenceState,
    StateCost,
    infer_sequence,
)
from .serialization import digest
from .source_transitions import (
    SourceProjectionPolicy,
    SourceSequenceResult,
    SourceSequenceSpec,
    TransitionWeights,
    _project_source_and_supports,
    build_source_sequence,
)

Function = Literal[
    "episodic-assertion",
    "summary",
    "inference",
    "source-monitoring",
    "association",
    "evaluation",
    "task-commentary",
    "uninterpretable",
    "not-assessed",
]
CostTerm = Literal[
    "semantic",
    "propositional",
    "entity",
    "sensory",
    "granularity",
    "distortion",
    "chart",
    "structural",
]
Nonnegative = Annotated[float, Field(ge=0, allow_inf_nan=False)]
_TERMS: tuple[CostTerm, ...] = (
    "semantic",
    "propositional",
    "entity",
    "sensory",
    "granularity",
    "distortion",
    "chart",
    "structural",
)
_PRIOR: dict[Function, float] = {
    "episodic-assertion": 0.0,
    "summary": 0.0,
    "inference": 0.1,
    "source-monitoring": 0.3,
    "association": 0.5,
    "evaluation": 0.5,
    "task-commentary": 0.6,
    "uninterpretable": 0.6,
    "not-assessed": 0.0,
}
_NATURAL: dict[Function, External | None] = {
    "episodic-assertion": "intrusion",
    "summary": "intrusion",
    "inference": "source-consistent-inference",
    "source-monitoring": "commentary",
    "association": "association",
    "evaluation": "commentary",
    "task-commentary": "commentary",
    "uninterpretable": "uninterpretable",
    "not-assessed": None,
}
_EXTERNALS: tuple[External, ...] = (
    "association",
    "commentary",
    "source-consistent-inference",
    "intrusion",
    "uninterpretable",
)


def _weights() -> dict[CostTerm, float]:
    return {
        "semantic": 1.0,
        "propositional": 0.5,
        "entity": 0.4,
        "sensory": 0.15,
        "granularity": 0.3,
        "distortion": 1.0,
        "chart": 0.5,
        "structural": 0.5,
    }


class LocalCostPolicy(Model):
    """Finite nonnegative energy weights and explicitly declared missing-semantic imputation."""

    weights: dict[CostTerm, Nonnegative] = Field(default_factory=_weights)
    function_prior: dict[Function, Nonnegative] = Field(default_factory=lambda: dict(_PRIOR))
    external_floor: Nonnegative = 1.0
    external_mismatch: Nonnegative = 0.5
    missing_semantic: Probability = 0.5
    distortion_penalty: Nonnegative = 0.3

    @model_validator(mode="after")
    def finite_aggregate(self) -> Self:
        try:
            total = math.fsum(self.weights.values())
        except OverflowError as error:
            raise ValueError("aggregate cost weight exceeds finite range") from error
        if not math.isfinite(total) or not math.isfinite(
            self.external_floor + self.external_mismatch
        ):
            raise ValueError("aggregate cost weights or external cost exceeds finite range")
        return self


class SemanticEstimate(Model):
    """An observed distance, eligible missing measurement, or ineligible comparison."""

    status: Literal["observed", "missing", "ineligible"]
    value: Probability | None = None
    reason: Name | None = None

    @model_validator(mode="after")
    def shape(self) -> Self:
        if self.status == "observed":
            if self.value is None or self.reason is not None:
                raise ValueError("observed semantic estimate requires only a value")
        elif self.value is not None or self.reason is None:
            raise ValueError("unmeasured semantic estimate requires only a reason")
        return self


class SemanticCell(Model):
    """A supplied measurement binds a particular recall unit and source anchor."""

    unit: Name
    target: Name
    estimate: SemanticEstimate


class RecallSelection(Model):
    """Select an ambiguous unit's sketch explicitly; function remains a declared input."""

    unit: Name
    situation: Name | None = None
    content: Literal["auto", "text-only"] = "auto"
    function: Function = "not-assessed"
    # None invokes the sparse retriever. An explicit tuple is an audited nomination,
    # not a reference judgment. Empty supplied tuples still differ from abstention.
    targets: tuple[Name, ...] | None = None

    @model_validator(mode="after")
    def compatible_selection(self) -> Self:
        if self.situation is not None and self.content == "text-only":
            raise ValueError("text-only selection cannot also select a situation")
        if self.targets is not None and len(set(self.targets)) != len(self.targets):
            raise ValueError("duplicate nominated target")
        return self


class LocalEvidenceSpec(Model):
    """A versioned content recipe with ordered units, retrieval limits and transition policy."""

    schema_version: Literal["storymodel.local-evidence-spec/v1"] = (
        "storymodel.local-evidence-spec/v1"
    )
    units: Annotated[tuple[RecallSelection, ...], Field(min_length=1)]
    projection: SourceProjectionPolicy = Field(default_factory=SourceProjectionPolicy)
    costs: LocalCostPolicy = Field(default_factory=LocalCostPolicy)
    transitions: TransitionWeights = Field(default_factory=TransitionWeights)
    temperature: Annotated[float, Field(gt=0, allow_inf_nan=False)] = 1.0
    max_candidates: Annotated[int, Field(gt=0)] = 32
    max_postings: Annotated[int, Field(gt=0)] = 4096
    semantic: Literal["lexical", "table"] = "lexical"
    semantic_cells: tuple[SemanticCell, ...] = ()

    @model_validator(mode="after")
    def unique_inputs(self) -> Self:
        if len({u.unit for u in self.units}) != len(self.units):
            raise ValueError("duplicate recall selection")
        if self.semantic == "lexical" and self.semantic_cells:
            raise ValueError("semantic table requires table mode")
        pairs = [(c.unit, c.target) for c in self.semantic_cells]
        if len(set(pairs)) != len(pairs):
            raise ValueError("duplicate semantic table cell")
        return self


class Nomination(Model):
    """A retrieval score selects candidates without becoming a cost or probability."""

    target: Name
    score: Nonnegative | None


class RetrievalRow(Model):
    """Distinguish supplied, complete, search-limited and abstained candidate populations."""

    unit: Name
    situation: Name | None
    status: Literal["supplied", "ranked", "empty", "search-limited", "abstained"]
    nominations: tuple[Nomination, ...]
    postings_read: Annotated[int, Field(ge=0)]
    candidates_seen: Annotated[int, Field(ge=0)]


class MemberGate(Model):
    """Retain which actual leaves engaged a segment and why they contradicted recall."""

    target: Name
    engaged: bool
    facets: tuple[Facet, ...]


class LocalCell(Model):
    """A gated state with separate priced, measured, missing and eligible term populations."""

    unit: Name
    target: Name
    state: SequenceState
    gate_members: tuple[MemberGate, ...]
    terms: dict[CostTerm, Nonnegative]
    slot_defaults: dict[CostTerm, Name]
    missing: dict[CostTerm, Name]
    imputed: dict[CostTerm, Name]
    eligible: tuple[CostTerm, ...]
    measured: tuple[CostTerm, ...]
    support: Probability | None
    support_reason: Literal["assessed", "zero-eligible-weight"]
    excluded: bool
    total: Nonnegative | None


class LocalEvidenceResult(Model):
    """Recomputable account-bound evidence and the resulting numerical sequence inputs."""

    schema_version: Literal["storymodel.local-evidence/v1"] = "storymodel.local-evidence/v1"
    source_sha256: Digest
    recall_sha256: Digest
    spec_sha256: Digest
    retrieval: tuple[RetrievalRow, ...]
    cells: tuple[LocalCell, ...]
    sequence: SourceSequenceResult


class AccountInferenceResult(Model):
    """Local evidence stays inspectable alongside model-conditional sequence posteriors."""

    schema_version: Literal["storymodel.account-inference/v1"] = "storymodel.account-inference/v1"
    evidence: LocalEvidenceResult
    inference: SequenceResult


@dataclass(frozen=True)
class _Content:
    predicate: str | None
    participants: tuple[tuple[str, frozenset[str]], ...]
    context: str
    polarity: str
    modality: str
    lemmas: frozenset[str]
    sensory: frozenset[str] = frozenset()

    def role(self, name: str) -> frozenset[str]:
        # All same-role participants, not whichever happened to be serialized first.
        return frozenset(
            token for role, names in self.participants if role == name for token in names
        )


def _text(supports: tuple[Support, ...], documents: dict[str, str]) -> str:
    return " ".join(span.slice(documents[s.document]) for s in supports for span in s.spans)


def _situation_content(
    situation: Event | State,
    labels: dict[str, str],
    contexts: dict[str, str],
    documents: dict[str, str],
) -> _Content:
    participants = tuple(
        sorted(
            [
                (
                    p.role,
                    frozenset(
                        [
                            lower(labels[p.entity]),
                            *(w for w in words(labels[p.entity]) if w not in STOPWORDS),
                        ]
                    ),
                )
                for p in situation.participants
            ],
            key=lambda p: (p[0], tuple(sorted(p[1]))),
        )
    )
    full_predicate = lower(situation.predicate)
    predicate = full_predicate.split("-")[0] or None
    text = " ".join(
        (
            _text(text_evidence(situation.evidence), documents),
            full_predicate.replace("-", " "),
            *(labels[p.entity] for p in situation.participants),
        )
    )
    return _Content(
        predicate,
        participants,
        contexts[situation.context],
        situation.polarity if isinstance(situation, State) else "unknown",
        {"necessary": "asserted", "probable": "possible"}.get(
            situation.modality, situation.modality
        )
        if isinstance(situation, State)
        else "unknown",
        stem_set(text),
    )


def _contradictions(unit: _Content, target: _Content) -> tuple[Facet, ...]:
    same_predicate = unit.predicate is not None and unit.predicate == target.predicate
    ua, up, ta, tp = (
        unit.role("agent"),
        unit.role("patient"),
        target.role("agent"),
        target.role("patient"),
    )
    pa, aa, ap, pp = bool(up & ta), bool(ua & ta), bool(ua & tp), bool(up & tp)
    reversed_roles = (pa and bool(ua) and not aa) or (ap and bool(up) and not pp)
    facets: list[Facet] = []
    if (pa and ap) or (reversed_roles and same_predicate):
        facets.append("role-reversal")
    if (
        same_predicate
        and "unknown" not in (unit.polarity, target.polarity)
        and unit.polarity != target.polarity
    ):
        facets.append("polarity")
    if same_predicate and unit.modality == "asserted" and target.context != "narrated":
        facets.append("context")
    unrealized = {"intended", "desired", "counterfactual"}
    if same_predicate and (
        (unit.modality == "asserted" and target.modality in unrealized)
        or (target.modality == "asserted" and unit.modality in unrealized)
    ):
        facets.append("modality")
    return tuple(facets)


def _query(content: _Content) -> set[tuple[int, str, str]]:
    result = {(2, "lemma", token) for token in content.lemmas}
    if content.predicate is not None:
        result.add((0, "predicate", content.predicate))
    for role, names in content.participants:
        result.update((1, role, name) for name in names)
    return result


def _lexical_distance(left: _Content, right: _Content) -> float:
    union = left.lemmas | right.lemmas
    return 1 - len(left.lemmas & right.lemmas) / len(union) if union else 1.0


def _nominate(
    unit: str,
    situation: str | None,
    content: _Content,
    selection: RecallSelection,
    targets: dict[str, _Content],
    postings: dict[tuple[int, str, str], tuple[str, ...]],
    spec: LocalEvidenceSpec,
) -> RetrievalRow:
    if selection.targets is not None:
        if any(target not in targets for target in selection.targets):
            raise ValueError("nominated anchor is outside the checked source")
        return RetrievalRow(
            unit=unit,
            situation=situation,
            status="supplied",
            nominations=tuple(Nomination(target=t, score=None) for t in selection.targets),
            postings_read=0,
            candidates_seen=len(selection.targets),
        )
    query = _query(content)
    if not query:
        return RetrievalRow(
            unit=unit,
            situation=situation,
            status="abstained",
            nominations=(),
            postings_read=0,
            candidates_seen=0,
        )
    candidates: set[str] = set()
    reads = 0
    limited = False
    for key in sorted(query):
        for target_id in postings.get(key, ()):
            if reads == spec.max_postings:
                limited = True
                break
            reads += 1
            candidates.add(target_id)
        if limited:
            break
    scores = []
    for anchor in candidates:
        target = targets[anchor]
        role_hits = sum(bool(names & target.role(role)) for role, names in content.participants)
        score = (
            1
            - _lexical_distance(content, target)
            + float(content.predicate is not None and content.predicate == target.predicate)
            + role_hits / max(1, len(content.participants))
        )
        scores.append(Nomination(target=anchor, score=score))
    scores.sort(key=lambda n: (-(n.score or 0.0), n.target))
    limited = limited or len(scores) > spec.max_candidates
    return RetrievalRow(
        unit=unit,
        situation=situation,
        status="search-limited" if limited else "ranked" if scores else "empty",
        nominations=tuple(scores[: spec.max_candidates]),
        postings_read=reads,
        candidates_seen=len(candidates),
    )


def _price(
    unit: str,
    target_id: str,
    content: _Content,
    target: _Content,
    level: int,
    max_level: int,
    function: Function,
    state: SequenceState,
    gates: tuple[MemberGate, ...],
    semantic: SemanticEstimate,
    policy: LocalCostPolicy,
) -> LocalCell:
    distortion = policy.distortion_penalty * len(state.facets)
    if not math.isfinite(distortion):
        raise ValueError("raw distortion term exceeds finite numeric range")
    terms: dict[CostTerm, float] = {
        "propositional": float(content.predicate != target.predicate)
        if content.predicate is not None and target.predicate is not None
        else 0.5,
        "distortion": distortion,
    }
    names = frozenset(n for _, values in target.participants for n in values)
    credit = sum(
        1.0 if values & target.role(role) else 0.5 if values & names else 0.0
        for role, values in content.participants
    )
    terms["entity"] = 1 - credit / len(content.participants) if content.participants else 0.5
    preferred = (
        None
        if function == "not-assessed"
        else 1
        if function == "summary"
        else max_level
        if function in ("association", "evaluation")
        else 1
        if content.predicate is None and not content.participants
        else 0
    )
    terms["granularity"] = min(1.0, 0.5 * abs(level - preferred)) if preferred is not None else 0.0
    slot_defaults: dict[CostTerm, str] = {}
    if content.predicate is None or target.predicate is None:
        slot_defaults["propositional"] = "unspecified-predicate-half-cost"
    if not content.participants:
        slot_defaults["entity"] = "unspecified-participants-half-cost"
    if preferred is None:
        slot_defaults["granularity"] = "unassessed-function-no-level-preference"
    missing: dict[CostTerm, str] = {
        "chart": "no-chart-representation",
        "structural": "no-chart-representation",
    }
    imputed: dict[CostTerm, str] = {}
    if semantic.status != "ineligible":
        terms["semantic"] = (
            semantic.value if semantic.value is not None else policy.missing_semantic
        )
        if semantic.status == "missing":
            assert semantic.reason is not None
            imputed["semantic"] = semantic.reason
    else:
        assert semantic.reason is not None
        missing["semantic"] = semantic.reason
    eligible: set[CostTerm] = set(terms)
    eligible.add("sensory")
    if content.sensory:
        terms["sensory"] = 1 - len(content.sensory & target.lemmas) / len(content.sensory)
    else:
        missing["sensory"] = "all-missing"
    measured = set(terms) - set(imputed)
    w_eligible = math.fsum(policy.weights.get(k, 0.0) for k in eligible)
    w_measured = math.fsum(policy.weights.get(k, 0.0) for k in measured)
    support = w_measured / w_eligible if w_eligible > 0 else None
    if support == 0 and w_measured > 0:
        raise ValueError("positive support share cannot be represented in binary64")
    lost_semantic = semantic.status == "ineligible" and policy.weights.get("semantic", 0.0) > 0
    excluded = (support is not None and w_measured == 0) or (support is None and lost_semantic)
    total = None
    if not excluded:
        # Wide intermediate arithmetic avoids overflow/underflow in the missing-term
        # scaling ratio. Absolute outputs must still fit a finite binary64 cost.
        with localcontext(computation_context(80)):
            weights = {k: Decimal.from_float(policy.weights.get(k, 0.0)) for k in _TERMS}
            priced = sum((weights[k] * Decimal.from_float(v) for k, v in terms.items()), Decimal(0))
            w_present = sum((weights[k] for k in terms), Decimal(0))
            denominator = sum((weights[k] for k in eligible), Decimal(0))
            scaled = (
                priced * denominator / w_present if w_present > 0 and denominator > 0 else priced
            )
            total = float(scaled + Decimal.from_float(policy.function_prior.get(function, 0.0)))
        if not math.isfinite(total):
            raise ValueError("local cost exceeds finite numeric range")
    return LocalCell(
        unit=unit,
        target=target_id,
        state=state,
        gate_members=gates,
        terms=terms,
        slot_defaults=slot_defaults,
        missing=missing,
        imputed=imputed,
        eligible=tuple(k for k in _TERMS if k in eligible),
        measured=tuple(k for k in _TERMS if k in measured),
        support=support,
        support_reason="assessed" if support is not None else "zero-eligible-weight",
        excluded=excluded,
        total=total,
    )


def derive_local_evidence(
    source: SupportedAccount, recall: SupportedAccount, spec: LocalEvidenceSpec
) -> LocalEvidenceResult:
    """Generate local evidence from independent accounts; never alter reference or fidelity."""
    source, recall = require_text_account(source), require_text_account(recall)
    spec = _validate_instance(LocalEvidenceSpec, spec)
    projection, anchor_supports = _project_source_and_supports(source, spec.projection)
    if not recall.documents or any(d.side != "recall" for d in recall.documents):
        raise ValueError("local evidence requires an independent recall-only account")
    nodes = {n.anchor: n for n in projection.nodes}
    source_situations = {s.id: s for s in source._situations()}
    source_docs = {d.id: d.text for d in source.documents}
    source_labels = {e.id: e.label for e in source.entities}
    source_contexts: dict[str, str] = {c.id: c.kind for c in source.contexts}
    targets = {
        key: _situation_content(s, source_labels, source_contexts, source_docs)
        for key, s in source_situations.items()
    }
    children: dict[str, list[str]] = defaultdict(list)
    for node in projection.nodes:
        if node.parent is not None:
            children[node.parent].append(node.anchor)
    leaves: dict[str, tuple[str, ...]] = {}

    def under(anchor: str) -> tuple[str, ...]:
        if anchor not in leaves:
            pending, found = [anchor], []
            while pending:
                child = pending.pop()
                if child in source_situations:
                    found.append(child)
                else:
                    pending.extend(children.get(child, ()))
            leaves[anchor] = tuple(sorted(found))
        return leaves[anchor]

    for segment_id, segment_supports in anchor_supports.items():
        if segment_id in source_situations:
            continue
        members = under(segment_id)
        participants = tuple(
            sorted(
                set(p for member in members for p in targets[member].participants),
                key=lambda p: (p[0], tuple(sorted(p[1]))),
            )
        )
        lemmas = stem_set(_text(segment_supports, source_docs)) | frozenset(
            token for _, names in participants for name in names for token in stem_set(name)
        )
        targets[segment_id] = _Content(None, participants, "narrated", "unknown", "unknown", lemmas)
    inverted: dict[tuple[int, str, str], list[str]] = defaultdict(list)
    for anchor, content in targets.items():
        for key in _query(content):
            inverted[key].append(anchor)
    postings = {key: tuple(sorted(values)) for key, values in inverted.items()}
    recall_units = {u.id: u for u in recall.units if u.kind == "recall"}
    recall_situations = {s.id: s for s in recall._situations()}
    recall_docs = {d.id: d.text for d in recall.documents}
    recall_labels = {e.id: e.label for e in recall.entities}
    recall_contexts: dict[str, str] = {c.id: c.kind for c in recall.contexts}
    by_document: dict[str, list[tuple[int, str]]] = defaultdict(list)
    for situation_id, item in recall_situations.items():
        evidence = text_evidence(item.evidence)
        documents = {s.document for s in evidence}
        if len(documents) == 1:
            by_document[next(iter(documents))].append(
                (min(p.start for s in evidence for p in s.spans), situation_id)
            )
    for entries in by_document.values():
        entries.sort()
    sensory_by_unit: dict[str, set[str]] = defaultdict(set)
    for detail in recall.details:
        if detail.kind == "perceptual":
            sensory_by_unit[detail.unit].update(stem_set(detail.content))
    selected_units = {s.unit for s in spec.units}
    semantic_table = {(c.unit, c.target): c.estimate for c in spec.semantic_cells}
    if any(c.unit not in selected_units or c.target not in targets for c in spec.semantic_cells):
        raise ValueError("semantic cell is outside selected units or source anchors")
    retrieval, cells, rows = [], [], []
    max_level = max((n.level for n in projection.nodes), default=0)
    for selected in spec.units:
        unit = recall_units.get(selected.unit)
        if unit is None:
            raise ValueError("selected unit is outside the recall account")
        situation = None
        if selected.situation is not None:
            situation = recall_situations.get(selected.situation)
            if situation is None or not all(
                contained_in(s, (unit.support,)) for s in situation.evidence
            ):
                raise ValueError("selected situation must have all evidence within its recall unit")
        elif selected.content == "auto":
            support = text_support(unit.support)
            entries = by_document.get(support.document, [])
            first = bisect_left(entries, (support.spans[0].start, ""))
            last = bisect_left(entries, (support.spans[-1].end, ""))
            available = [
                recall_situations[key]
                for _, key in entries[first:last]
                if all(contained_in(e, (unit.support,)) for e in recall_situations[key].evidence)
            ]
            if len(available) > 1:
                raise ValueError(
                    "several situations fit recall unit; select one or use text-only content"
                )
            situation = available[0] if available else None
        unit_text = _text((text_support(unit.support),), recall_docs)
        content = (
            _situation_content(situation, recall_labels, recall_contexts, recall_docs)
            if situation is not None
            else _Content(None, (), "narrated", "unknown", "unknown", stem_set(unit_text))
        )
        # Unit-local text remains available even when the selected graph covers only part of it.
        sensory = frozenset(sensory_by_unit.get(unit.id, ()))
        content = _Content(
            content.predicate,
            content.participants,
            content.context,
            content.polarity,
            content.modality,
            content.lemmas | stem_set(unit_text),
            sensory,
        )
        nominated = _nominate(
            unit.id, situation.id if situation else None, content, selected, targets, postings, spec
        )
        retrieval.append(nominated)
        costs = []
        for nomination in nominated.nominations:
            anchor = nomination.target
            target, node = targets[anchor], nodes[anchor]
            gates: tuple[MemberGate, ...]
            if node.kind == "situation":
                facets = _contradictions(content, target)
                gates = (
                    MemberGate(
                        target=anchor,
                        engaged=bool(facets)
                        or (
                            content.predicate is not None and content.predicate == target.predicate
                        ),
                        facets=facets,
                    ),
                )
            else:
                gates = tuple(
                    MemberGate(
                        target=member,
                        engaged=bool(cs)
                        or (
                            content.predicate is not None
                            and content.predicate == targets[member].predicate
                        ),
                        facets=cs,
                    )
                    for member in under(anchor)
                    for cs in [_contradictions(content, targets[member])]
                )
                engaged = [gate for gate in gates if gate.engaged]
                facets = (
                    tuple(sorted({facet for gate in engaged for facet in gate.facets}))
                    if engaged and all(gate.facets for gate in engaged)
                    else ()
                )
            state = SequenceState(
                kind="distorted" if facets else "source",
                anchor_kind=node.kind,
                anchor=anchor,
                facets=facets,
            )
            semantic = (
                SemanticEstimate(status="observed", value=_lexical_distance(content, target))
                if spec.semantic == "lexical"
                else semantic_table.get(
                    (unit.id, anchor),
                    SemanticEstimate(status="missing", reason="provider-abstained"),
                )
            )
            cell = _price(
                unit.id,
                anchor,
                content,
                target,
                node.level,
                max_level,
                selected.function,
                state,
                gates,
                semantic,
                spec.costs,
            )
            cells.append(cell)
            if not cell.excluded:
                assert cell.total is not None
                costs.append(StateCost(state=state, cost=cell.total))
        external = ("unranked",) if nominated.status == "abstained" else _EXTERNALS
        natural = _NATURAL[selected.function]
        for destination in external:
            external_cost = spec.costs.external_floor + (
                0
                if destination == "unranked" or natural is None or destination == natural
                else spec.costs.external_mismatch
            )
            costs.append(
                StateCost(
                    state=SequenceState(kind="external", external=destination), cost=external_cost
                )
            )
        rows.append(SequenceRow(unit=unit.id, costs=tuple(costs)))
    sequence = build_source_sequence(
        source,
        recall,
        SourceSequenceSpec(
            rows=tuple(rows),
            projection=spec.projection,
            weights=spec.transitions,
            temperature=spec.temperature,
        ),
    )
    return LocalEvidenceResult(
        source_sha256=sequence.source_sha256,
        recall_sha256=sequence.recall_sha256,
        spec_sha256=digest(spec.model_dump(mode="json")),
        retrieval=tuple(retrieval),
        cells=tuple(cells),
        sequence=sequence,
    )


def verify_local_evidence(
    source: SupportedAccount,
    recall: SupportedAccount,
    spec: LocalEvidenceSpec,
    result: LocalEvidenceResult,
) -> LocalEvidenceResult:
    """Recompute nominations, gates, support, costs and transitions against original snapshots."""
    checked = _validate_instance(LocalEvidenceResult, result)
    expected = derive_local_evidence(source, recall, spec)
    if checked != expected:
        raise ValueError("local evidence differs from original account inputs or specification")
    return expected


def infer_accounts(
    source: SupportedAccount, recall: SupportedAccount, spec: LocalEvidenceSpec
) -> AccountInferenceResult:
    """Run the account-to-inference path without supplied state costs or transition matrices."""
    evidence = derive_local_evidence(source, recall, spec)
    return AccountInferenceResult(
        evidence=evidence, inference=infer_sequence(evidence.sequence.request)
    )


def verify_account_inference(
    source: SupportedAccount,
    recall: SupportedAccount,
    spec: LocalEvidenceSpec,
    result: AccountInferenceResult,
) -> AccountInferenceResult:
    """Bind posterior output and every preceding evidence stage to the actual independent inputs."""
    checked = _validate_instance(AccountInferenceResult, result)
    expected = infer_accounts(source, recall, spec)
    if checked != expected:
        raise ValueError("account inference differs from original inputs")
    return expected
