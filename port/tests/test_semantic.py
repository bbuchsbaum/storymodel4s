"""Recorded vector controls exercise the real candidate, gate and inference consumers."""

import json
from pathlib import Path

import pytest
from test_local_evidence import one_pair, rich

from storymodel.embedding import EmbeddingCache, EmbeddingPair, EmbeddingSpace, EmbeddingValue
from storymodel.local_evidence import LocalEvidenceSpec, RecallSelection, infer_accounts
from storymodel.semantic import (
    SemanticResult,
    embedding_inputs,
    prepare_semantic,
    resolve_semantic,
    semantic_prompt,
    semantic_request_digest,
    verify_semantic,
)
from storymodel.semantic_workflow import SemanticRecording, import_semantic, replay_semantic
from storymodel.serialization import canonical


def geometry(normalization="l2"):
    common = dict(
        provider="recorded",
        model="abstract-control",
        revision="v1",
        model_sha256="a" * 64,
        dimension=2,
        normalization=normalization,
    )
    return EmbeddingPair(
        query=EmbeddingSpace(**common, role="query"),
        document=EmbeddingSpace(**common, role="document"),
    )


def request(*, targets=None, max_comparisons=4096, top=32, normalization="l2"):
    source = rich(
        "source",
        "SA SB SC",
        (),
        tuple(("P", start, start + 2, "positive", "asserted", ()) for start in (0, 3, 6)),
    )
    recall = rich(
        "recall", "RB", (("recall", 0, 2, None),), (("P", 0, 2, "positive", "asserted", ()),)
    )
    spec = LocalEvidenceSpec(units=(RecallSelection(unit=recall.units[0].id, targets=targets),))
    return prepare_semantic(
        source,
        recall,
        spec,
        geometry(normalization),
        max_comparisons=max_comparisons,
        max_semantic_candidates=top,
    )


def reply(req, values=None):
    vectors = values or {"SA": [1.0, 0.0], "SB": [0.0, 1.0], "SC": [-1.0, 0.0], "RB": [1.0, 0.0]}
    prompt = json.loads(semantic_prompt(req))
    return canonical(
        dict(
            schema_version="storymodel.embedding-reply/v1",
            request_sha256=semantic_request_digest(req),
            rows=[
                dict(key=item["key"], value=dict(status="observed", values=vectors[item["text"]]))
                for item in prompt["items"]
            ],
        )
    )


def test_distances_reach_local_costs_and_actual_inference_without_account_changes():
    req = request()
    before = (req.source.model_dump_json(), req.recall.model_dump_json())
    raw = reply(req)
    result = resolve_semantic(req, raw)
    by_text = {i.text: i.endpoint for i in embedding_inputs(req) if i.side == "source"}
    assert [
        (n.target, n.half_cosine_distance) for n in result.semantic_retrieval[0].nominations
    ] == [(by_text["SA"], 0.0), (by_text["SB"], 0.5), (by_text["SC"], 1.0)]
    assert {c.target: c.estimate.value for c in result.local_spec.semantic_cells} == {
        by_text["SA"]: 0.0,
        by_text["SB"]: 0.5,
        by_text["SC"]: 1.0,
    }
    assert result.inference == infer_accounts(req.source, req.recall, result.local_spec)
    assert verify_semantic(req, raw, result) == result
    assert (req.source.model_dump_json(), req.recall.model_dump_json()) == before
    assert SemanticResult.model_validate_json(result.model_dump_json()) == result


def test_explicit_universe_is_filtered_before_budget_and_never_widened():
    initial = request()
    last = sorted(s.id for s in initial.source.states)[-1]
    req = request(targets=(last,), max_comparisons=1)
    result = resolve_semantic(req, reply(req))
    row = result.semantic_retrieval[0]
    assert row.final_targets == (last,)
    assert row.comparisons_read == row.distances_observed == 1
    assert not row.budget_limited
    assert row.source_status_counts == dict(observed=1, missing=0, ineligible=0, failed=0)
    assert result.local_spec.semantic_cells[0].estimate.status == "observed"


def test_empty_explicit_universe_remains_empty():
    req = request(targets=())
    row = resolve_semantic(req, reply(req)).semantic_retrieval[0]
    assert row.final_targets == row.nominations == ()
    assert row.comparisons_read == 0
    assert not row.budget_limited


def test_budget_cutoff_ties_and_unmeasured_lexical_candidates_are_distinct():
    req = request(max_comparisons=2, top=1)
    raw = reply(req, {text: [1.0, 0.0] for text in ("SA", "SB", "SC", "RB")})
    result = resolve_semantic(req, raw)
    row = result.semantic_retrieval[0]
    assert row.comparisons_read == row.distances_observed == 2
    assert row.budget_limited and row.cutoff_limited
    assert [n.target for n in row.nominations] == [min(s.id for s in req.source.states)]
    assert set(row.final_targets) == {s.id for s in req.source.states}
    cells = {c.target: c.estimate for c in result.local_spec.semantic_cells}
    last = max(cells)
    assert cells[last].status == "missing" and cells[last].reason == "comparison-budget"
    assert sum(c.status == "observed" for c in cells.values()) == 2


@pytest.mark.parametrize("status", ["missing", "failed", "ineligible"])
def test_provider_outcomes_remain_distinct_and_only_observed_values_enter_cache(status):
    req = request()
    packet = json.loads(reply(req))
    query_key = next(i.key for i in embedding_inputs(req) if i.side == "recall")
    next(r for r in packet["rows"] if r["key"] == query_key)["value"] = dict(
        status=status, reason="control"
    )
    result = resolve_semantic(req, canonical(packet))
    assert result.semantic_retrieval[0].query_status == status
    assert result.semantic_retrieval[0].comparisons_read == 0
    assert len(result.cache.entries) == 3
    assert all(
        c.estimate.status == ("ineligible" if status == "ineligible" else "missing")
        for c in result.local_spec.semantic_cells
    )


def test_invalid_vector_is_rejected_per_key_but_siblings_survive_and_omission_is_missing():
    req = request()
    packet = json.loads(reply(req))
    source_keys = [i.key for i in embedding_inputs(req) if i.side == "source"]
    next(r for r in packet["rows"] if r["key"] == source_keys[0])["value"]["values"] = [2.0, 0.0]
    packet["rows"] = [r for r in packet["rows"] if r["key"] != source_keys[1]]
    result = resolve_semantic(req, canonical(packet))
    outcomes = {o.key: o for o in result.outcomes}
    assert outcomes[source_keys[0]].origin == "rejected"
    assert outcomes[source_keys[0]].value.status == "failed"
    assert outcomes[source_keys[1]].origin == "unattempted"
    assert outcomes[source_keys[1]].value.status == "missing"
    assert outcomes[source_keys[2]].value.status == "observed"
    assert len(result.cache.entries) == 2


def test_zero_vector_has_undefined_distance_not_budget_loss():
    req = request(normalization="unnormalized")
    raw = reply(req, {text: [0.0, 0.0] for text in ("SA", "SB", "SC", "RB")})
    result = resolve_semantic(req, raw)
    row = result.semantic_retrieval[0]
    assert row.comparisons_read == 3 and row.distances_observed == 0
    assert not row.budget_limited
    assert {c.estimate.reason for c in result.local_spec.semantic_cells} == {"undefined-vector"}


@pytest.mark.parametrize(
    "change", ["foreign-key", "duplicate", "unhashable-key", "foreign-request"]
)
def test_reply_binding_refuses_misattributed_batch(change):
    req = request()
    packet = json.loads(reply(req))
    if change == "foreign-key":
        packet["rows"][0]["key"] = "b" * 64
    elif change == "duplicate":
        packet["rows"].append(packet["rows"][0])
    elif change == "unhashable-key":
        packet["rows"][0]["key"] = []
    else:
        packet["request_sha256"] = "b" * 64
    with pytest.raises(ValueError):
        resolve_semantic(req, canonical(packet))


def test_cached_material_reuses_vectors_without_merging_occurrences():
    req = request()
    first = resolve_semantic(req, reply(req))
    second = prepare_semantic(req.source, req.recall, req.local, req.pair, cache=first.cache)
    assert json.loads(semantic_prompt(second))["items"] == []
    result = resolve_semantic(second, reply(second))
    assert {o.origin for o in result.outcomes} == {"cache"}
    assert result.inference == first.inference
    assert result.cache == first.cache
    # Same source text at distinct coordinates has one material key, two anchor identities.
    source = rich(
        "source",
        "SA SA",
        (),
        (("P", 0, 2, "positive", "asserted", ()), ("P", 3, 5, "positive", "asserted", ())),
    )
    repeated = prepare_semantic(source, req.recall, req.local, req.pair, cache=first.cache)
    source_inputs = [i for i in embedding_inputs(repeated) if i.side == "source"]
    assert len({i.endpoint for i in source_inputs}) == 2
    assert len({i.key for i in source_inputs}) == 1
    repeated_result = resolve_semantic(repeated, reply(repeated))
    assert len(repeated_result.semantic_retrieval[0].nominations) == 2


def test_perfect_semantics_cannot_restore_faithful_mode_after_polarity_gate():
    source, recall, state, unit, recalled = one_pair()
    from storymodel.model import State
    from storymodel.rich_account import validate_rich_account

    negative = State(**{**recalled.model_dump(exclude={"id"}), "polarity": "negative"})
    recall = validate_rich_account(recall.model_copy(update={"states": (negative,)}))
    req = prepare_semantic(
        source, recall, LocalEvidenceSpec(units=(RecallSelection(unit=unit.id),)), geometry()
    )
    result = resolve_semantic(req, reply(req, {"SA": [1.0, 0.0], "RB": [1.0, 0.0]}))
    cell = next(c for c in result.inference.evidence.cells if c.target == state.id)
    assert "polarity" in cell.state.facets
    assert cell.state.kind == "distorted"


def test_recording_preserves_exact_reply_and_checks_original_request(tmp_path: Path):
    req = request()
    raw = reply(req) + "\r\n"
    recording = SemanticRecording(
        request_sha256=semantic_request_digest(req), origin="scripted-control", reply=raw
    )
    root = tmp_path / "recording"
    import_semantic(req, recording, root)
    assert replay_semantic(req, root) == resolve_semantic(req, raw)
    changed = req.model_copy(update={"max_comparisons": 1})
    with pytest.raises(ValueError, match="another request"):
        replay_semantic(changed, root)
    with pytest.raises(ValueError, match="external SHA"):
        replay_semantic(req, root, expected_import_sha256="c" * 64)
    with pytest.raises(ValueError, match="differs"):
        verify_semantic(req, raw, resolve_semantic(req, reply(req)))


def test_denied_export_precedes_recording_io(tmp_path: Path):
    req = request()
    denied = req.model_copy(
        update={"source": req.source.model_copy(update={"export_permission": "denied"})}
    )
    with pytest.raises(PermissionError):
        semantic_prompt(denied)
    with pytest.raises(PermissionError):
        replay_semantic(denied, tmp_path / "does-not-exist")
    recording = SemanticRecording(
        request_sha256=semantic_request_digest(denied), origin="control", reply="{}"
    )
    with pytest.raises(PermissionError):
        import_semantic(denied, recording, tmp_path / "no-write")
    assert not (tmp_path / "no-write").exists()


def test_cache_boundary_is_rechecked_before_inference():
    req = request()
    first = resolve_semantic(req, reply(req))
    entry = first.cache.entries[0]
    forged = entry.model_copy(
        update={"value": EmbeddingValue(status="observed", values=(2.0, 0.0))}
    )
    broken = req.model_copy(update={"cache": EmbeddingCache.model_construct(entries=(forged,))})
    with pytest.raises(ValueError, match="unit norm"):
        resolve_semantic(broken, reply(req))


def test_semantics_can_nominate_a_target_absent_from_lexical_retrieval():
    source, recall, state, unit, _ = one_pair(source_predicate="PP", recall_predicate="ZZ")
    req = prepare_semantic(
        source, recall, LocalEvidenceSpec(units=(RecallSelection(unit=unit.id),)), geometry()
    )
    result = resolve_semantic(req, reply(req, {"SA": [1.0, 0.0], "RB": [1.0, 0.0]}))
    assert result.lexical_retrieval[0].nominations == ()
    assert result.semantic_retrieval[0].final_targets == (state.id,)
    assert any(cell.target == state.id for cell in result.inference.evidence.cells)


def test_empty_text_is_not_requested_and_remains_ineligible():
    req = request()
    recall = rich(
        "recall", "  ", (("recall", 0, 2, None),), (("P", 0, 2, "positive", "asserted", ()),)
    )
    empty = prepare_semantic(
        req.source,
        recall,
        LocalEvidenceSpec(units=(RecallSelection(unit=recall.units[0].id),)),
        req.pair,
    )
    assert all(item["text"].strip() for item in json.loads(semantic_prompt(empty))["items"])
    result = resolve_semantic(empty, reply(empty))
    assert result.semantic_retrieval[0].query_status == "ineligible"
    assert any(o.origin == "empty" for o in result.outcomes)
