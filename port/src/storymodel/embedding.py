"""Checked embedding sidecars whose geometry and cache identities stay explicit.

Embeddings are evidence channels, not narrative identity.  This small scalar baseline
therefore keeps provider geometry, preprocessing and query/document role in every
space identity, and never turns a distance into a probability.
"""

from __future__ import annotations

import hashlib
import math
from typing import ClassVar, Literal, Self

from pydantic import Field, model_validator

from ._base import Digest, Identified, Model, Name, _validate_instance


class EmbeddingSpace(Identified):
    """One fully specified coordinate system; vectors compare only through this record."""

    namespace: ClassVar[str] = "embedding-space"
    provider: Name
    model: Name
    revision: Name
    model_sha256: Digest
    dimension: int = Field(gt=0)
    normalization: Literal["l2", "unnormalized"]
    role: Literal["query", "document"]
    instruction: str = ""
    preprocessing: Name = "identity/v1"
    view: Literal["surface"] = "surface"


class EmbeddingPair(Model):
    """A checked query/document geometry; instruction asymmetry must be intentional."""

    query: EmbeddingSpace
    document: EmbeddingSpace
    allow_instruction_difference: bool = False

    @model_validator(mode="after")
    def compatible(self) -> Self:
        query = _validate_instance(EmbeddingSpace, self.query)
        document = _validate_instance(EmbeddingSpace, self.document)
        if query.role != "query":
            raise ValueError("query space must have query role")
        if document.role != "document":
            raise ValueError("document space must have document role")
        for field in (
            "provider",
            "model",
            "revision",
            "model_sha256",
            "dimension",
            "normalization",
            "preprocessing",
            "view",
        ):
            if getattr(query, field) != getattr(document, field):
                raise ValueError(f"embedding spaces differ in {field}")
        if not self.allow_instruction_difference and query.instruction != document.instruction:
            raise ValueError("embedding spaces differ in instruction without explicit permission")
        return self


EmbeddingStatus = Literal["observed", "missing", "ineligible", "failed"]


class EmbeddingValue(Model):
    """One provider outcome; only observed outcomes carry coordinates."""

    status: EmbeddingStatus
    values: tuple[float, ...] = ()
    reason: Name | None = None

    @model_validator(mode="after")
    def shape(self) -> Self:
        if any(not math.isfinite(value) for value in self.values):
            raise ValueError("embedding values must be finite")
        if self.status == "observed":
            if not self.values or self.reason is not None:
                raise ValueError("observed embedding requires values and no reason")
        elif self.values or self.reason is None:
            raise ValueError("non-observed embedding requires no values and a reason")
        return self


def _checked_values(value: EmbeddingValue) -> EmbeddingValue:
    return _validate_instance(EmbeddingValue, value)


def validate_embedding(space: EmbeddingSpace, value: EmbeddingValue) -> EmbeddingValue:
    """Check a vector against its declared space and canonicalize accepted L2 coordinates."""
    checked_space = _validate_instance(EmbeddingSpace, space)
    checked_value = _checked_values(value)
    if checked_value.status != "observed":
        return checked_value
    if len(checked_value.values) != checked_space.dimension:
        raise ValueError("embedding dimension differs from space")
    if checked_space.normalization == "unnormalized":
        return checked_value
    norm = math.hypot(*checked_value.values)
    tolerance = 1e-6 + math.ulp(1.0)
    if not math.isfinite(norm) or abs(norm - 1.0) > tolerance:
        raise ValueError("L2 embedding must have unit norm")
    if norm == 0.0:
        raise ValueError("L2 embedding must not be zero")
    # A normalized binary64 vector need not have a norm exactly equal to one.
    # Preserve that rounding band so validation/replay never changes cached coordinates.
    if abs(norm - 1.0) <= 2 * math.ulp(1.0):
        return checked_value
    return EmbeddingValue(
        status="observed", values=tuple(item / norm for item in checked_value.values)
    )


def cosine_distance(query: tuple[float, ...], document: tuple[float, ...]) -> float:
    """Return raw cosine distance in [0, 2], rejecting undefined or incompatible input."""
    if len(query) != len(document):
        raise ValueError("cosine distance requires equal dimensions")
    if not query:
        raise ValueError("cosine distance requires nonempty vectors")
    if any(not math.isfinite(value) for value in (*query, *document)):
        raise ValueError("cosine distance requires finite vectors")
    query_scale = max(abs(value) for value in query)
    document_scale = max(abs(value) for value in document)
    if query_scale == 0.0 or document_scale == 0.0:
        raise ValueError("cosine distance is undefined for a zero vector")
    query_norm = math.hypot(*(value / query_scale for value in query))
    document_norm = math.hypot(*(value / document_scale for value in document))
    # Normalize before multiplying: this avoids overflow in products and makes fsum
    # retain cancellation that a running scalar accumulator would lose.
    cosine = math.fsum(
        ((left / query_scale) / query_norm) * ((right / document_scale) / document_norm)
        for left, right in zip(query, document)
    )
    return 1.0 - max(-1.0, min(1.0, cosine))


def half_cosine_distance(
    pair: EmbeddingPair, query: EmbeddingValue, document: EmbeddingValue
) -> float:
    """Compare two observed pair-compatible vectors, explicitly rescaling cosine distance by two."""
    checked_pair = _validate_instance(EmbeddingPair, pair)
    checked_query = validate_embedding(checked_pair.query, query)
    checked_document = validate_embedding(checked_pair.document, document)
    if checked_query.status != "observed" or checked_document.status != "observed":
        raise ValueError("half cosine distance requires two observed embeddings")
    return cosine_distance(checked_query.values, checked_document.values) / 2.0


def _payload_sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def embedding_key(space: EmbeddingSpace, text: str) -> str:
    """Content-address a space and exact UTF-8 payload without account-dependent material."""
    checked_space = _validate_instance(EmbeddingSpace, space)
    payload = _payload_sha256(text)
    return hashlib.sha256(f"{checked_space.id}{payload}".encode()).hexdigest()


class EmbeddingCacheEntry(Model):
    """An observed cached vector tied to its complete space and exact payload digest."""

    space: EmbeddingSpace
    payload_sha256: Digest
    value: EmbeddingValue

    @model_validator(mode="after")
    def observed_vector(self) -> Self:
        space = _validate_instance(EmbeddingSpace, self.space)
        value = _checked_values(self.value)
        if value.status != "observed":
            raise ValueError("cache entries require observed embeddings")
        object.__setattr__(self, "value", validate_embedding(space, value))
        return self

    @property
    def key(self) -> str:
        return hashlib.sha256(f"{self.space.id}{self.payload_sha256}".encode()).hexdigest()


class EmbeddingCache(Model):
    """Immutable observed-vector cache that refuses stale-model or stale-payload collisions."""

    entries: tuple[EmbeddingCacheEntry, ...] = ()

    @model_validator(mode="after")
    def entries_are_unique(self) -> Self:
        seen: dict[str, EmbeddingCacheEntry] = {}
        for entry in self.entries:
            checked = _validate_instance(EmbeddingCacheEntry, entry)
            if checked.key in seen:
                raise ValueError("embedding cache contains duplicate keys")
            seen[checked.key] = checked
        return self

    def lookup(self, space: EmbeddingSpace, text: str) -> EmbeddingValue | None:
        checked_cache = _validate_instance(EmbeddingCache, self)
        key = embedding_key(space, text)
        for entry in checked_cache.entries:
            if entry.key == key:
                return validate_embedding(entry.space, entry.value)
        return None

    def put(self, space: EmbeddingSpace, text: str, value: EmbeddingValue) -> EmbeddingCache:
        checked_cache = _validate_instance(EmbeddingCache, self)
        checked_space = _validate_instance(EmbeddingSpace, space)
        checked_value = validate_embedding(checked_space, value)
        if checked_value.status != "observed":
            raise ValueError("only observed embeddings may be cached")
        payload = _payload_sha256(text)
        entry = EmbeddingCacheEntry(
            space=checked_space, payload_sha256=payload, value=checked_value
        )
        for old in checked_cache.entries:
            if old.key == entry.key:
                if old.value == entry.value:
                    return checked_cache
                raise ValueError("conflicting embedding cache value")
        return EmbeddingCache(entries=(*checked_cache.entries, entry))
