"""Regression coverage for checked embedding geometry and immutable cache boundaries."""

from __future__ import annotations

import math

import pytest

from storymodel.embedding import (
    EmbeddingCache,
    EmbeddingCacheEntry,
    EmbeddingPair,
    EmbeddingSpace,
    EmbeddingValue,
    cosine_distance,
    embedding_key,
    half_cosine_distance,
    validate_embedding,
)


def space(
    *,
    role: str = "query",
    instruction: str = "",
    model: str = "model-a",
    normalization: str = "l2",
    dimension: int = 2,
) -> EmbeddingSpace:
    return EmbeddingSpace(
        provider="provider",
        model=model,
        revision="revision",
        model_sha256="a" * 64,
        dimension=dimension,
        normalization=normalization,
        role=role,
        instruction=instruction,
    )


def observed(*values: float) -> EmbeddingValue:
    return EmbeddingValue(status="observed", values=values)


def test_cosine_distance_distinguishes_equal_orthogonal_and_opposite_vectors():
    assert cosine_distance((1.0, 0.0), (1.0, 0.0)) == 0.0
    assert cosine_distance((1.0, 0.0), (0.0, 1.0)) == 1.0
    assert cosine_distance((1.0, 0.0), (-1.0, 0.0)) == 2.0


def test_cosine_distance_is_stable_for_huge_and_tiny_scales():
    assert cosine_distance((1e308, 1e308), (1e-308, 1e-308)) == pytest.approx(0.0)
    assert cosine_distance((1e-308, 0.0), (0.0, 1e308)) == pytest.approx(1.0)


def test_cosine_distance_preserves_huge_and_subnormal_four_dimensional_orientations():
    huge = (1e308,) * 4
    assert cosine_distance(huge, huge) == pytest.approx(0.0)
    assert cosine_distance(huge, tuple(-value for value in huge)) == pytest.approx(2.0)
    tiny = (5e-324,) * 4
    assert cosine_distance(tiny, tiny) == pytest.approx(0.0)
    assert cosine_distance(tiny, tuple(-value for value in tiny)) == pytest.approx(2.0)


@pytest.mark.parametrize(
    ("left", "right"),
    [((1.0,), (1.0, 0.0)), ((0.0,), (1.0,)), ((math.nan,), (1.0,))],
)
def test_cosine_distance_rejects_mismatched_zero_and_nonfinite_vectors(left, right):
    with pytest.raises(ValueError):
        cosine_distance(left, right)


def test_l2_validation_canonicalizes_tolerance_and_rejects_nonunit_values():
    value = validate_embedding(space(), observed(1.0, 1e-7))
    assert math.hypot(*value.values) == pytest.approx(1.0)
    with pytest.raises(ValueError, match="unit norm"):
        validate_embedding(space(), observed(2.0, 0.0))
    with pytest.raises(ValueError, match="dimension"):
        validate_embedding(space(), observed(1.0))
    assert validate_embedding(space(normalization="unnormalized"), observed(0.0, 0.0)).values == (
        0.0,
        0.0,
    )


def test_pair_requires_roles_identical_geometry_and_explicit_instruction_permission():
    query = space()
    document = space(role="document")
    pair = EmbeddingPair(query=query, document=document)
    assert half_cosine_distance(pair, observed(1.0, 0.0), observed(0.0, 1.0)) == 0.5
    with pytest.raises(ValueError, match="instruction"):
        EmbeddingPair(query=query, document=space(role="document", instruction="search"))
    assert EmbeddingPair(
        query=query,
        document=space(role="document", instruction="search"),
        allow_instruction_difference=True,
    )
    with pytest.raises(ValueError, match="model"):
        EmbeddingPair(query=query, document=space(role="document", model="model-b"))


def test_cache_key_includes_role_instruction_model_and_exact_payload():
    query = space()
    assert embedding_key(query, "same") != embedding_key(space(role="document"), "same")
    assert embedding_key(query, "same") != embedding_key(space(instruction="find"), "same")
    assert embedding_key(query, "same") != embedding_key(space(model="model-b"), "same")
    assert embedding_key(query, "same") != embedding_key(query, "same\n")


def test_cache_is_immutable_idempotent_and_refuses_conflicts_or_nonobserved_values():
    query = space()
    original = EmbeddingCache()
    cached = original.put(query, "payload", observed(1.0, 0.0))
    assert original.lookup(query, "payload") is None
    assert cached.lookup(query, "payload") == observed(1.0, 0.0)
    assert cached.lookup(query, "other payload") is None
    assert cached.lookup(space(instruction="different"), "payload") is None
    assert cached.lookup(space(model="other-model"), "payload") is None
    assert cached.put(query, "payload", observed(1.0, 0.0)) == cached
    with pytest.raises(ValueError, match="conflicting"):
        cached.put(query, "payload", observed(0.0, 1.0))
    with pytest.raises(ValueError, match="observed"):
        original.put(query, "payload", EmbeddingValue(status="failed", reason="provider-error"))


def test_l2_cache_canonicalization_is_idempotent_near_tolerance():
    query = space()
    near_unit = observed(1.0, 1e-7)
    cached = EmbeddingCache().put(query, "payload", near_unit)
    first = cached.lookup(query, "payload")
    assert first is not None
    assert math.hypot(*first.values) == pytest.approx(1.0)
    assert cached.put(query, "payload", first) == cached


def test_public_boundaries_revalidate_copied_forged_space_and_entry():
    query = space()
    forged_space = query.model_copy(update={"model": "other"})
    with pytest.raises(ValueError, match="identity differs"):
        embedding_key(forged_space, "payload")

    entry = EmbeddingCacheEntry(
        space=query,
        payload_sha256="b" * 64,
        value=observed(1.0, 0.0),
    )
    forged_entry = entry.model_copy(update={"value": observed(2.0, 0.0)})
    forged_cache = EmbeddingCache.model_construct(entries=(forged_entry,))
    with pytest.raises(ValueError, match="unit norm"):
        forged_cache.lookup(query, "payload")


def test_general_l2_cache_roundtrip_does_not_renormalize_into_conflict():
    import random

    generator = random.Random(991)
    query = space(dimension=8)
    for _ in range(100):
        coordinates = tuple(generator.uniform(-1, 1) for _ in range(8))
        norm = math.hypot(*coordinates)
        value = observed(*(x / norm for x in coordinates))
        cached = EmbeddingCache().put(query, "payload", value)
        for _ in range(3):
            retrieved = cached.lookup(query, "payload")
            assert retrieved is not None
            assert cached.put(query, "payload", retrieved) == cached
            assert EmbeddingCache.model_validate_json(cached.model_dump_json()) == cached
