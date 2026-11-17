"""Two abstract recalls reuse one source and observed embedding cache, fully offline.

Vectors are scripted geometry controls, not outputs of a learned model. This example
qualifies integration and preservation, not semantic accuracy or a ranking improvement.
"""

import json
import sys
from pathlib import Path

from account_inference import control

from storymodel import (
    EmbeddingCache,
    EmbeddingPair,
    EmbeddingSpace,
    LocalEvidenceSpec,
    RecallSelection,
    SemanticRecording,
    import_semantic,
    infer_accounts,
    prepare_semantic,
    replay_semantic,
    semantic_prompt,
    semantic_request_digest,
    verify_semantic,
)
from storymodel.artifacts import publish_json
from storymodel.serialization import canonical
from storymodel.source_transitions import SourceProjectionPolicy


def run(output: Path) -> None:
    source = control("source", "AA PP BB\nAA QQ BB")
    source_before = source.model_dump_json()
    common = dict(
        provider="scripted-control",
        model="abstract-2d",
        revision="v1",
        model_sha256="a" * 64,
        dimension=2,
        normalization="l2",
    )
    pair = EmbeddingPair(
        query=EmbeddingSpace(**common, role="query"),
        document=EmbeddingSpace(**common, role="document"),
    )
    cache = EmbeddingCache()
    vectors = {"AA PP BB": [1.0, 0.0], "BB PP AA": [1.0, 0.0], "AA QQ BB": [0.0, 1.0]}
    output.mkdir(parents=True, exist_ok=False)
    publish_json(output / "source.json", source.model_dump(mode="json"))
    publish_json(output / "pair.json", pair.model_dump(mode="json"))
    summaries = []
    for number, text in enumerate(("AA PP BB\nBB PP AA\nAA PP BB", "AA QQ BB\nAA PP BB"), 1):
        recall = control("recall", text)
        local = LocalEvidenceSpec(
            units=tuple(
                RecallSelection(unit=u.id, function="episodic-assertion") for u in recall.units
            ),
            projection=SourceProjectionPolicy(
                memberships={s.id: u.id for s, u in zip(source.states, source.units)}
            ),
        )
        request = prepare_semantic(
            source, recall, local, pair, cache=cache, max_semantic_candidates=2
        )
        prompt = json.loads(semantic_prompt(request))
        raw = (
            canonical(
                {
                    "schema_version": "storymodel.embedding-reply/v1",
                    "request_sha256": semantic_request_digest(request),
                    "rows": [
                        {
                            "key": row["key"],
                            "value": {"status": "observed", "values": vectors[row["text"]]},
                        }
                        for row in prompt["items"]
                    ],
                }
            )
            + "\r\n"
        )
        case = output / f"recall-{number}"
        case.mkdir()
        recording = SemanticRecording(
            request_sha256=semantic_request_digest(request),
            origin="scripted-geometry-control",
            reply=raw,
        )
        import_semantic(request, recording, case / "recording")
        result = replay_semantic(request, case / "recording")
        assert verify_semantic(request, raw, result) == result
        assert result.inference == infer_accounts(source, recall, result.local_spec)
        assert source.model_dump_json() == source_before
        if number == 1:
            wanted = ((), ("role-reversal",), ("polarity",))
            for unit, facets in zip(recall.units, wanted):
                cell = next(
                    c
                    for c in result.inference.evidence.cells
                    if c.unit == unit.id and c.target == source.states[0].id
                )
                assert cell.state.facets == facets
        else:
            assert sum(o.origin == "cache" for o in result.outcomes) == 3
            assert len(prompt["items"]) == 1
        for name, artifact in (
            ("recall", recall),
            ("spec", local),
            ("cache-before", cache),
            ("request", request),
            ("result", result),
            ("lexical-inference", infer_accounts(source, recall, local)),
        ):
            publish_json(case / f"{name}.json", artifact.model_dump(mode="json"))
        publish_json(case / "prompt.json", prompt)
        (case / "reply.txt").write_bytes(raw.encode("utf-8"))
        cache = result.cache
        summaries.append(
            dict(
                recall=number,
                units=len(recall.units),
                imported=len(prompt["items"]),
                reused=sum(o.origin == "cache" for o in result.outcomes),
                comparisons=sum(r.comparisons_read for r in result.semantic_retrieval),
            )
        )
    print(
        json.dumps(dict(recalls=summaries, cache_entries=len(cache.entries), source_unchanged=True))
    )


if __name__ == "__main__":
    run(Path(sys.argv[1]))
