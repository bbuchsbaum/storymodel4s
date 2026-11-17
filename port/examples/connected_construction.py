"""Recorded row-local construction -> explicit links -> source projection and inference.

AA/BB/PP/QQ are abstract controls. Supplied links assert engineering test conditions;
they do not establish linguistic accuracy or infer story time from row order.
"""

import json
import sys
from pathlib import Path
from typing import Literal

from storymodel import (
    LocalEvidenceSpec,
    RecallSelection,
    RichRecording,
    SourceRow,
    connect_rich_construction,
    construction_link_prompt,
    export_supported_account,
    import_rich,
    infer_accounts,
    pair_rich_accounts,
    prepare_rich_construction,
    project_source,
    relation_table,
    replay_rich,
    verify_account_inference,
    verify_connected_construction,
)
from storymodel.artifacts import publish_json
from storymodel.model import Span
from storymodel.serialization import canonical, digest


def construct(path: Path, side: Literal["source", "recall"], tokens: tuple[str, ...]):
    path.mkdir()
    text = "\n".join(f"AA {token} BB" for token in tokens)
    request = prepare_rich_construction(
        path.name,
        side,
        text,
        tuple(
            SourceRow(key=f"row-{i}", span=Span(start=9 * i, end=9 * i + 8))
            for i in range(len(tokens))
        ),
        full_contexts=True,
        export_permission="allowed",
    )

    def quote(start: int, end: int):
        return {"span": {"start": start, "end": end}, "text": text[start:end]}

    graph_raw = (
        canonical(
            {
                "schema_version": "storymodel.rich-construction-proposal/v2",
                "rows": [
                    {
                        "row": f"row-{i}",
                        "status": "completed",
                        "entities": [
                            {"key": "agent", "mention": quote(9 * i, 9 * i + 2)},
                            {"key": "patient", "mention": quote(9 * i + 6, 9 * i + 8)},
                        ],
                        "states": [
                            {
                                "key": "s",
                                "context": "narrated",
                                "predicate": quote(9 * i + 3, 9 * i + 5),
                                "evidence": quote(9 * i, 9 * i + 8),
                                "polarity": "positive",
                                "modality": "asserted",
                                "participants": [
                                    {
                                        "entity": "agent",
                                        "role": "agent",
                                        "evidence": quote(9 * i, 9 * i + 2),
                                    },
                                    {
                                        "entity": "patient",
                                        "role": "patient",
                                        "evidence": quote(9 * i + 6, 9 * i + 8),
                                    },
                                ],
                            }
                        ],
                    }
                    for i in range(len(tokens))
                ],
            }
        )
        + "\n"
    )
    import_rich(
        request,
        RichRecording(
            request_sha256=digest(request.model_dump(mode="json")),
            origin="abstract-symbol-control",
            reply=graph_raw,
        ),
        path / "recording",
    )
    construction = replay_rich(request, path / "recording")
    prompt = json.loads(construction_link_prompt(request, graph_raw))
    # Explicit diagnostic assertions, not an automatic adjacency/chronology rule.
    links = [
        {
            "key": "declared-before",
            "kind": "before",
            "source": {"row": "row-0", "key": "s"},
            "target": {"row": "row-1", "key": "s"},
            "evidence": [
                {"row": "row-0", "quote": quote(0, 8)},
                {"row": "row-1", "quote": quote(9, 17)},
            ],
        }
    ]
    if side == "source":
        links.append(
            {
                "key": "declared-cause",
                "kind": "causes",
                "source": {"row": "row-1", "key": "s"},
                "target": {"row": "row-2", "key": "s"},
                "evidence": [
                    {"row": "row-1", "quote": quote(9, 17)},
                    {"row": "row-2", "quote": quote(18, 26)},
                ],
            }
        )
    links_raw = (
        canonical(
            {
                "schema_version": "storymodel.construction-links/v1",
                "construction_sha256": prompt["construction_sha256"],
                "status": "completed",
                "relations": links,
            }
        )
        + "\n"
    )
    result = connect_rich_construction(request, graph_raw, links_raw)
    assert result.status == "admitted" and result.construction == construction
    verify_connected_construction(request, graph_raw, links_raw, result)
    assert len({s.id for s in result.account.states}) == len(tokens)
    for name, value in (
        ("request", request.model_dump(mode="json")),
        ("construction", construction.model_dump(mode="json")),
        ("link-prompt", prompt),
        ("connected", result.model_dump(mode="json")),
        ("relations", [r.model_dump(mode="json") for r in relation_table(result.account)]),
    ):
        publish_json(path / f"{name}.json", value)
    (path / "graph-proposals.json").write_text(graph_raw, encoding="utf-8")
    (path / "links.json").write_text(links_raw, encoding="utf-8")
    export_supported_account(path / "account.json", result.account)
    return result.account


def main(destination: Path):
    destination.mkdir(parents=True, exist_ok=False)
    source = construct(destination / "source", "source", ("PP", "QQ", "PP"))
    before = source.model_dump_json()
    projection = project_source(source)
    assert any(e.layer == "world-time" for e in projection.edges)
    assert any(e.layer == "causal" for e in projection.edges)
    publish_json(destination / "source-projection.json", projection.model_dump(mode="json"))
    for number, tokens in enumerate((("PP", "QQ"), ("QQ", "PP")), 1):
        recall = construct(destination / f"recall-{number}", "recall", tokens)
        paired = pair_rich_accounts(source, recall)
        export_supported_account(destination / f"paired-{number}.json", paired)
        spec = LocalEvidenceSpec(
            units=tuple(
                RecallSelection(unit=u.id, function="episodic-assertion") for u in recall.units
            )
        )
        inference = infer_accounts(source, recall, spec)
        verify_account_inference(source, recall, spec, inference)
        publish_json(destination / f"inference-{number}.json", inference.model_dump(mode="json"))
        publish_json(destination / f"inference-spec-{number}.json", spec.model_dump(mode="json"))
        assert source.model_dump_json() == before
    print(
        canonical(
            {
                "source_unchanged": True,
                "source_relations": len(source.relations),
                "recalls": 2,
                "cross_unit_projection_and_inference": True,
            }
        )
    )


if __name__ == "__main__":
    main(Path(sys.argv[1]))
