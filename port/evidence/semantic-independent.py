"""Cold-review oracles; run with PYTHONPATH=src:tests:examples .venv/bin/pytest this_file."""

import itertools
import json
import math
import random
from decimal import Decimal, localcontext

import pytest
from account_inference import control
from test_semantic import geometry, reply, request

from storymodel.embedding import (
    EmbeddingCache,
    EmbeddingPair,
    EmbeddingSpace,
    EmbeddingValue,
    cosine_distance,
    embedding_key,
)
from storymodel.local_evidence import LocalEvidenceSpec, RecallSelection
from storymodel.semantic import (
    embedding_inputs,
    prepare_semantic,
    resolve_semantic,
    semantic_prompt,
    semantic_request_digest,
    verify_semantic,
)
from storymodel.semantic_workflow import SemanticRecording, import_semantic, replay_semantic
from storymodel.serialization import canonical


@pytest.mark.parametrize(
    "field,value",
    [
        ("provider", "other"),
        ("model", "other"),
        ("revision", "other"),
        ("model_sha256", "b" * 64),
        ("dimension", 3),
        ("normalization", "unnormalized"),
        ("preprocessing", "other"),
        ("instruction", "other"),
        ("role", "document"),
    ],
)
def test_space_fields_change_cache_identity(field, value):
    s = geometry().query
    altered = EmbeddingSpace(**{**s.model_dump(exclude={"id"}), field: value})
    cache = EmbeddingCache().put(
        s, "same\U0001f642\r\n", EmbeddingValue(status="observed", values=(1.0, 0.0))
    )
    assert cache.lookup(s, "same\U0001f642\r\n") is not None
    assert cache.lookup(altered, "same\U0001f642\r\n") is None
    assert embedding_key(s, "same\U0001f642\r\n") != embedding_key(altered, "same\U0001f642\r\n")


@pytest.mark.parametrize(
    "field,value",
    [
        ("provider", "other"),
        ("model", "other"),
        ("revision", "other"),
        ("model_sha256", "b" * 64),
        ("dimension", 3),
        ("normalization", "unnormalized"),
        ("preprocessing", "other"),
    ],
)
def test_pair_rejects_actual_geometry_mismatch(field, value):
    pair = geometry()
    assert EmbeddingPair(query=pair.query, document=pair.document) == pair
    altered = EmbeddingSpace(**{**pair.document.model_dump(exclude={"id"}), field: value})
    with pytest.raises(ValueError, match=f"embedding spaces differ in {field}"):
        EmbeddingPair(query=pair.query, document=altered)


@pytest.mark.parametrize(
    "bad",
    [
        {"status": "observed", "values": [1.0]},
        {"status": "observed", "values": [2.0, 0.0]},
        {"status": "observed", "values": [1.0, 0.0], "reason": "wrong"},
        {"status": "observed", "values": []},
        {"status": "failed", "values": [1.0, 0.0], "reason": "wrong"},
        {"status": "missing"},
        {"status": "unknown", "reason": "wrong"},
    ],
)
def test_malformed_value_preserves_siblings(bad):
    req = request()
    packet = json.loads(reply(req))
    before = resolve_semantic(req, canonical(packet))
    assert all(o.value.status == "observed" for o in before.outcomes)
    rejected = packet["rows"][0]["key"]
    packet["rows"][0]["value"] = bad
    result = resolve_semantic(req, canonical(packet))
    assert {(o.origin, o.value.status) for o in result.outcomes if o.key == rejected} == {
        ("rejected", "failed")
    }
    assert sum(o.value.status == "observed" for o in result.outcomes) == 3


def test_universe_budget_ranking_and_distance_units():
    initial = request()
    items = sorted(
        (i for i in embedding_inputs(initial) if i.side == "source"), key=lambda i: i.endpoint
    )
    # Exclude first two IDs so a globally budgeted scan would fail this witness.
    req = request(targets=(items[-1].endpoint,), max_comparisons=1, top=1)
    result = resolve_semantic(
        req,
        reply(
            req,
            {
                t: [0.0, 1.0] if t == items[-1].text else [1.0, 0.0]
                for t in ["SA", "SB", "SC", "RB"]
            },
        ),
    )
    row = result.semantic_retrieval[0]
    assert row.final_targets == (items[-1].endpoint,)
    assert row.comparisons_read == row.distances_observed == 1 and not row.budget_limited
    assert row.nominations[0].cosine_distance == 1.0
    assert result.local_spec.semantic_cells[0].estimate.value == 0.5


def test_stable_normalization_and_decimal_cosine_oracle():
    rng = random.Random(147)
    for n in (2, 3, 16, 64):
        for _ in range(50):
            # Independent high precision dot/norm oracle, no max-scaled path.
            a = tuple(rng.uniform(-1, 1) * 10.0 ** rng.randint(-300, 300) for _ in range(n))
            b = tuple(rng.uniform(-1, 1) * 10.0 ** rng.randint(-300, 300) for _ in range(n))
            with localcontext() as ctx:
                ctx.prec = 90
                aa = list(map(Decimal.from_float, a))
                bb = list(map(Decimal.from_float, b))
                dot = sum(x * y for x, y in zip(aa, bb))
                exact = 1 - dot / (sum(x * x for x in aa).sqrt() * sum(y * y for y in bb).sqrt())
            assert cosine_distance(a, b) == pytest.approx(float(exact), abs=5e-15)
    s = EmbeddingSpace(**{**geometry().query.model_dump(exclude={"id"}), "dimension": 64})
    for _ in range(100):
        a = tuple(rng.uniform(-1, 1) for _ in range(64))
        norm = math.hypot(*a)
        v = EmbeddingValue(status="observed", values=tuple(x / norm * (1 + 8e-7) for x in a))
        cache = EmbeddingCache().put(s, "payload", v)
        dumped = cache.model_dump_json()
        for _ in range(5):
            cache = EmbeddingCache.model_validate_json(cache.model_dump_json())
            assert cache.model_dump_json() == dumped
            assert cache.put(s, "payload", cache.lookup(s, "payload")) == cache


@pytest.mark.parametrize("side", ["source", "recall"])
def test_both_grants_precede_io_with_valid_digests(side, tmp_path):
    req = request()
    allowed_raw = reply(req)
    rec = SemanticRecording(
        request_sha256=semantic_request_digest(req), origin="control", reply=allowed_raw
    )
    root = tmp_path / "positive"
    import_semantic(req, rec, root)
    assert replay_semantic(req, root) == resolve_semantic(req, allowed_raw)
    denied = req.model_copy(
        update={side: getattr(req, side).model_copy(update={"export_permission": "denied"})}
    )
    with pytest.raises(PermissionError, match="denies"):
        semantic_prompt(denied)
    denial = SemanticRecording(
        request_sha256=semantic_request_digest(denied), origin="control", reply="{}"
    )
    with pytest.raises(PermissionError, match="denies"):
        import_semantic(denied, denial, tmp_path / "denied")
    assert not (tmp_path / "denied").exists()
    with pytest.raises(PermissionError, match="denies"):
        replay_semantic(denied, tmp_path / "nonexistent")


def test_receipt_tamper_and_reply_whitespace_are_bound():
    req = request()
    raw = reply(req)
    receipt = resolve_semantic(req, raw)
    assert verify_semantic(req, raw, receipt) == receipt
    row = receipt.semantic_retrieval[0]
    altered = receipt.model_copy(
        update={"semantic_retrieval": (row.model_copy(update={"comparisons_read": 100}),)}
    )
    with pytest.raises(ValueError, match="differs from original"):
        verify_semantic(req, raw, altered)
    with pytest.raises(ValueError, match="differs from original"):
        verify_semantic(req, raw + "\n", receipt)


def test_multistep_posterior_against_complete_path_enumeration():
    source = control("source", "AA PP BB\nAA QQ BB")
    recall = control("recall", "AA QQ BB\nAA PP BB\nAA QQ BB")
    local = LocalEvidenceSpec(units=tuple(RecallSelection(unit=u.id) for u in recall.units))
    req = prepare_semantic(source, recall, local, geometry(), max_semantic_candidates=4)
    vectors = {"AA PP BB": [1.0, 0.0], "AA QQ BB": [0.0, 1.0]}
    packet = {
        "schema_version": "storymodel.embedding-reply/v1",
        "request_sha256": semantic_request_digest(req),
        "rows": [
            {"key": i["key"], "value": {"status": "observed", "values": vectors[i["text"]]}}
            for i in json.loads(semantic_prompt(req))["items"]
        ],
    }
    result = resolve_semantic(req, canonical(packet))
    numerical = result.inference.evidence.sequence.request
    edges = [
        {(e.from_state, e.to_state): e.probability for e in step.edges}
        for step in numerical.transitions
    ]
    weights = []
    for path in itertools.product(*(row.costs for row in numerical.rows)):
        weight = math.exp(-sum(x.cost for x in path) / numerical.temperature)
        for step, a, b in zip(edges, path, path[1:]):
            weight *= step.get((a.state.id, b.state.id), 0.0)
        weights.append((path, weight))
    total = math.fsum(w for _, w in weights)
    assert total > 0 and len(numerical.transitions) == 2
    for index, row in enumerate(result.inference.inference.posterior):
        for mass in row.mass:
            expected = (
                math.fsum(w for path, w in weights if path[index].state.id == mass.state) / total
            )
            assert mass.probability == pytest.approx(expected, abs=2e-14)
    # A semantic measurement changes the actual result, not just an unused diagnostic.
    for row in packet["rows"]:
        if row["value"]["values"] == [0.0, 1.0]:
            row["value"]["values"] = [1.0, 0.0]
    changed = resolve_semantic(req, canonical(packet))
    assert changed.inference.inference.posterior != result.inference.inference.posterior
