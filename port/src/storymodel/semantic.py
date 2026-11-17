"""Recorded surface embeddings augment sparse nominations through the existing inference path."""

from __future__ import annotations

import heapq
from hashlib import sha256
from itertools import islice
from typing import Annotated, Any, Literal

from pydantic import Field

from .embedding import (
    EmbeddingCache,
    EmbeddingCacheEntry,
    EmbeddingPair,
    EmbeddingSpace,
    EmbeddingValue,
    cosine_distance,
    embedding_key,
    validate_embedding,
)
from .local_evidence import (
    AccountInferenceResult,
    LocalEvidenceSpec,
    RecallSelection,
    RetrievalRow,
    SemanticCell,
    SemanticEstimate,
    derive_local_evidence,
    infer_accounts,
)
from .model import Digest, Model, Name, Support, _validate_instance, text_support
from .rich_account import SupportedAccount, require_text_account
from .serialization import canonical, digest, strict_json
from .source_transitions import _project_source_and_supports

RECIPE: Literal["recorded-surface-semantic-inference/v1"] = "recorded-surface-semantic-inference/v1"


class SemanticRequest(Model):
    """Private account snapshots, embedding geometry and cache fix the complete input condition."""

    schema_version: Literal["storymodel.semantic-request/v1"] = "storymodel.semantic-request/v1"
    source: SupportedAccount
    recall: SupportedAccount
    local: LocalEvidenceSpec
    pair: EmbeddingPair
    cache: EmbeddingCache = Field(default_factory=EmbeddingCache)
    max_comparisons: Annotated[int, Field(gt=0)] = 4096
    max_semantic_candidates: Annotated[int, Field(gt=0)] = 32


class EmbeddingInput(Model):
    """An actual source anchor or recall unit owns the exact material sent for embedding."""

    side: Literal["source", "recall"]
    endpoint: Name
    space: Name
    key: Digest
    text: str


def validate_semantic_request(value: SemanticRequest | str | bytes) -> SemanticRequest:
    request = (
        _validate_instance(SemanticRequest, value)
        if isinstance(value, SemanticRequest)
        else SemanticRequest.model_validate_json(canonical(strict_json(value)))
    )
    require_text_account(request.source)
    require_text_account(request.recall)
    if request.local.semantic != "lexical" or request.local.semantic_cells:
        raise ValueError("embedding integration requires an unmodified lexical baseline spec")
    # Reuse the qualified public boundary for selection, graph, cost and transition checks.
    derive_local_evidence(request.source, request.recall, request.local)
    return request


def prepare_semantic(
    source: SupportedAccount,
    recall: SupportedAccount,
    local: LocalEvidenceSpec,
    pair: EmbeddingPair,
    *,
    cache: EmbeddingCache | None = None,
    max_comparisons: int = 4096,
    max_semantic_candidates: int = 32,
) -> SemanticRequest:
    """Prepare one recorded embedding pass without changing either account or baseline method."""
    return validate_semantic_request(
        SemanticRequest(
            source=source,
            recall=recall,
            local=local,
            pair=pair,
            cache=EmbeddingCache() if cache is None else cache,
            max_comparisons=max_comparisons,
            max_semantic_candidates=max_semantic_candidates,
        )
    )


def _inputs(request: SemanticRequest) -> tuple[EmbeddingInput, ...]:
    _, supports = _project_source_and_supports(request.source, request.local.projection)
    documents = {d.id: d.text for d in request.source.documents}

    def text(values: tuple[Support, ...], docs: dict[str, str]) -> str:
        return " ".join(span.slice(docs[s.document]) for s in values for span in s.spans)

    inputs = []
    for anchor, evidence in sorted(supports.items()):
        payload = text(evidence, documents)
        space = request.pair.document
        inputs.append(
            EmbeddingInput(
                side="source",
                endpoint=anchor,
                space=space.id,
                key=embedding_key(space, payload),
                text=payload,
            )
        )
    units = {u.id: u for u in request.recall.units if u.kind == "recall"}
    documents = {d.id: d.text for d in request.recall.documents}
    for selected in request.local.units:
        unit = units[selected.unit]
        payload = text((text_support(unit.support),), documents)
        space = request.pair.query
        inputs.append(
            EmbeddingInput(
                side="recall",
                endpoint=unit.id,
                space=space.id,
                key=embedding_key(space, payload),
                text=payload,
            )
        )
    return tuple(inputs)


def embedding_inputs(value: SemanticRequest) -> tuple[EmbeddingInput, ...]:
    """Expose deterministic material/identity rows for local providers and auditable imports."""
    return _inputs(validate_semantic_request(value))


def semantic_request_digest(value: SemanticRequest) -> str:
    return digest(validate_semantic_request(value).model_dump(mode="json"))


class EmbeddingReplyRow(Model):
    key: Digest
    value: EmbeddingValue


class EmbeddingReply(Model):
    """Imported rows bind actual material keys; errors/absence are not replacement vectors."""

    schema_version: Literal["storymodel.embedding-reply/v1"] = "storymodel.embedding-reply/v1"
    request_sha256: Digest
    rows: tuple[EmbeddingReplyRow, ...] = ()


def _spaces(request: SemanticRequest) -> dict[str, EmbeddingSpace]:
    return {s.id: s for s in (request.pair.query, request.pair.document)}


def semantic_prompt(value: SemanticRequest) -> str:
    """Export only uncached nonempty material after both account grants are checked."""
    request = validate_semantic_request(value)
    if (
        request.source.export_permission != "allowed"
        or request.recall.export_permission != "allowed"
    ):
        raise PermissionError("producer denies semantic embedding export")
    spaces = _spaces(request)
    cached_keys = {entry.key for entry in request.cache.entries}
    required = {
        item.key: {"key": item.key, "space": item.space, "text": item.text}
        for item in _inputs(request)
        if item.text.strip() and item.key not in cached_keys
    }
    return canonical(
        {
            "recipe": RECIPE,
            "request_sha256": semantic_request_digest(request),
            "spaces": [s.model_dump(mode="json") for s in spaces.values()],
            "items": [required[k] for k in sorted(required)],
            "schema": EmbeddingReply.model_json_schema(),
            "instruction": (
                "Return declared-model embeddings only. Echo request_sha256 and material keys. "
                "Preserve failures, missing and ineligible outcomes; never invent a vector. "
                "This import does not verify a model execution or calibrated semantic accuracy."
            ),
        }
    )


class EmbeddingOutcome(Model):
    """One distinct material key records its origin and checked vector or explicit absence."""

    key: Digest
    space: Name
    origin: Literal["cache", "imported", "empty", "unattempted", "rejected"]
    value: EmbeddingValue


class SemanticNomination(Model):
    target: Name
    cosine_distance: Annotated[float, Field(ge=0, le=2, allow_inf_nan=False)]
    half_cosine_distance: Annotated[float, Field(ge=0, le=1, allow_inf_nan=False)]


class SemanticRetrieval(Model):
    """Vector scan budgets and channel availability remain separate from lexical retrieval."""

    unit: Name
    query_status: Literal["observed", "missing", "ineligible", "failed"]
    source_status_counts: dict[str, int]
    comparisons_read: int
    distances_observed: int
    budget_limited: bool
    cutoff_limited: bool
    nominations: tuple[SemanticNomination, ...]
    lexical_targets: tuple[Name, ...]
    final_targets: tuple[Name, ...]


class SemanticResult(Model):
    """Inspect embedding outcomes, sparse retrieval, local energies and posterior separately."""

    schema_version: Literal["storymodel.semantic-result/v1"] = "storymodel.semantic-result/v1"
    recipe: Literal["recorded-surface-semantic-inference/v1"] = RECIPE
    request_sha256: Digest
    response_sha256: Digest
    outcomes: tuple[EmbeddingOutcome, ...]
    cache: EmbeddingCache
    lexical_retrieval: tuple[RetrievalRow, ...]
    semantic_retrieval: tuple[SemanticRetrieval, ...]
    local_spec: LocalEvidenceSpec
    inference: AccountInferenceResult


def _outcomes(
    request: SemanticRequest, raw: str, inputs: tuple[EmbeddingInput, ...]
) -> tuple[tuple[EmbeddingOutcome, ...], EmbeddingCache]:
    parsed: Any = strict_json(raw)
    if not isinstance(parsed, dict) or set(parsed) != {"schema_version", "request_sha256", "rows"}:
        raise ValueError("embedding reply requires explicit schema, request digest and rows")
    if parsed["schema_version"] != "storymodel.embedding-reply/v1" or parsed[
        "request_sha256"
    ] != semantic_request_digest(request):
        raise ValueError("embedding reply belongs to another request or schema")
    spaces = _spaces(request)
    items = {i.key: i for i in inputs}
    cached_values = {
        entry.key: validate_embedding(entry.space, entry.value) for entry in request.cache.entries
    }
    required = {
        key for key, item in items.items() if item.text.strip() and key not in cached_values
    }
    if not isinstance(parsed["rows"], list):
        raise ValueError("embedding reply rows must be an array")
    rows = {}
    for row in parsed["rows"]:
        if (
            not isinstance(row, dict)
            or not isinstance(row.get("key"), str)
            or row["key"] not in required
            or row["key"] in rows
        ):
            raise ValueError("foreign, duplicate or already-cached embedding key")
        rows[row["key"]] = row
    additions: list[EmbeddingCacheEntry] = []
    outcomes = []
    for key, item in sorted(items.items()):
        space = spaces[item.space]
        cached = cached_values.get(key)
        origin: Literal["cache", "imported", "empty", "unattempted", "rejected"]
        if not item.text.strip():
            value, origin = EmbeddingValue(status="ineligible", reason="empty-text"), "empty"
        elif cached is not None:
            value, origin = cached, "cache"
        elif key not in rows:
            value, origin = (
                EmbeddingValue(status="missing", reason="provider-not-attempted"),
                "unattempted",
            )
        else:
            try:
                supplied = EmbeddingReplyRow.model_validate_json(canonical(rows[key]))
                value = validate_embedding(space, supplied.value)
                origin = "imported"
                if value.status == "observed":
                    additions.append(
                        EmbeddingCacheEntry(
                            space=space,
                            payload_sha256=sha256(item.text.encode("utf-8")).hexdigest(),
                            value=value,
                        )
                    )
            except ValueError:
                # Preserve sibling observations without leaking payload/provider text in errors.
                value, origin = (
                    EmbeddingValue(status="failed", reason="invalid-embedding-output"),
                    "rejected",
                )
        outcomes.append(EmbeddingOutcome(key=key, space=space.id, origin=origin, value=value))
    return tuple(outcomes), EmbeddingCache(entries=(*request.cache.entries, *additions))


def resolve_semantic(value: SemanticRequest, raw: str) -> SemanticResult:
    """Derive bounded semantic nominations, then reuse the qualified local gates and inference."""
    request = validate_semantic_request(value)
    inputs = _inputs(request)
    outcomes, cache = _outcomes(request, raw, inputs)
    by_key = {o.key: o.value for o in outcomes}
    source = {i.endpoint: by_key[i.key] for i in inputs if i.side == "source"}
    recall = {i.endpoint: by_key[i.key] for i in inputs if i.side == "recall"}
    baseline = derive_local_evidence(request.source, request.recall, request.local)
    lexical = {r.unit: r for r in baseline.retrieval}
    observed = [(key, v) for key, v in sorted(source.items()) if v.status == "observed"]
    selections: list[RecallSelection] = []
    cells = []
    retrieval = []
    for selected in request.local.units:
        query = recall[selected.unit]
        universe = (
            source if selected.targets is None else {key: source[key] for key in selected.targets}
        )
        status_counts = {
            status: sum(v.status == status for v in universe.values())
            for status in ("observed", "missing", "ineligible", "failed")
        }
        lexical_targets = tuple(n.target for n in lexical[selected.unit].nominations)
        lexical_set = set(lexical_targets)
        heap: list[tuple[float, int, str]] = []
        measured: dict[str, float] = {}
        undefined: set[str] = set()
        eligible_observed = (
            observed
            if selected.targets is None
            else [
                (key, source[key])
                for key in sorted(selected.targets)
                if source[key].status == "observed"
            ]
        )
        reads = successes = 0
        if query.status == "observed":
            for rank, (target, vector) in enumerate(
                islice(eligible_observed, request.max_comparisons)
            ):
                reads += 1
                try:
                    distance = cosine_distance(query.values, vector.values)
                except ValueError:
                    if target in lexical_set:
                        undefined.add(target)
                    continue
                successes += 1
                if target in lexical_set:
                    measured[target] = distance
                entry = (-distance, -rank, target)
                if len(heap) < request.max_semantic_candidates:
                    heapq.heappush(heap, entry)
                else:
                    heapq.heappushpop(heap, entry)
        nominations = tuple(
            SemanticNomination(
                target=target, cosine_distance=-negative, half_cosine_distance=-negative / 2
            )
            for negative, _, target in sorted(heap, key=lambda row: (-row[0], row[2]))
        )
        measured.update({n.target: n.cosine_distance for n in nominations})
        # Explicit nominations remain the universe; embedding retrieval cannot widen it.
        final = (
            tuple(dict.fromkeys((*lexical_targets, *(n.target for n in nominations))))
            if selected.targets is None
            else selected.targets
        )
        if not final and lexical[selected.unit].status == "abstained":
            selections.append(selected)
        else:
            selections.append(selected.model_copy(update={"targets": final}))
        for target in final:
            vector = source[target]
            if query.status == "ineligible" or vector.status == "ineligible":
                estimate = SemanticEstimate(status="ineligible", reason="embedding-ineligible")
            elif target in measured:
                estimate = SemanticEstimate(status="observed", value=measured[target] / 2)
            else:
                reason = (
                    "query-" + query.status
                    if query.status != "observed"
                    else "target-" + vector.status
                    if vector.status != "observed"
                    else "undefined-vector"
                    if target in undefined
                    else "comparison-budget"
                )
                estimate = SemanticEstimate(status="missing", reason=reason)
            cells.append(SemanticCell(unit=selected.unit, target=target, estimate=estimate))
        retrieval.append(
            SemanticRetrieval(
                unit=selected.unit,
                query_status=query.status,
                source_status_counts=status_counts,
                comparisons_read=reads,
                distances_observed=successes,
                budget_limited=query.status == "observed" and reads < len(eligible_observed),
                cutoff_limited=successes > request.max_semantic_candidates,
                nominations=nominations,
                lexical_targets=lexical_targets,
                final_targets=final,
            )
        )
    local = LocalEvidenceSpec.model_validate(
        request.local.model_copy(
            update={"units": tuple(selections), "semantic": "table", "semantic_cells": tuple(cells)}
        )
    )
    result = infer_accounts(request.source, request.recall, local)
    return SemanticResult(
        request_sha256=semantic_request_digest(request),
        response_sha256=sha256(raw.encode("utf-8")).hexdigest(),
        outcomes=outcomes,
        cache=cache,
        lexical_retrieval=baseline.retrieval,
        semantic_retrieval=tuple(retrieval),
        local_spec=local,
        inference=result,
    )


def verify_semantic(request: SemanticRequest, raw: str, receipt: SemanticResult) -> SemanticResult:
    """Recompute admission, nominations, costs and inference from independently held inputs."""
    checked = _validate_instance(SemanticResult, receipt)
    expected = resolve_semantic(request, raw)
    if checked != expected:
        raise ValueError("semantic inference differs from original request or recorded reply")
    return expected
