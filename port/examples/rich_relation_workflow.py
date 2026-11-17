"""Construct abstract event/state accounts and inspect their recorded relations offline.

AA/BB tokens are nonnarrative controls. Declared relations test data flow, not entailment.
"""

import sys
from pathlib import Path

from storymodel import (
    SourceRow,
    admit_relations,
    compile_rich_construction,
    export_supported_account,
    pair_rich_accounts,
    prepare_rich_construction,
    read_supported_account,
    relation_table,
    verify_relations,
)
from storymodel.artifacts import publish_json
from storymodel.model import Span
from storymodel.serialization import canonical, digest

destination = Path(sys.argv[1])
destination.mkdir(parents=True, exist_ok=False)


def construct(side):
    request = prepare_rich_construction(
        f"rich-relation-{side}",
        side,
        "AA BB",
        (SourceRow(key="row", span=Span(start=0, end=5)),),
        export_permission="allowed",
    )
    full = {"span": {"start": 0, "end": 5}, "text": "AA BB"}
    aa = {"span": {"start": 0, "end": 2}, "text": "AA"}
    bb = {"span": {"start": 3, "end": 5}, "text": "BB"}
    packet = {
        "schema_version": "storymodel.rich-construction-proposal/v1",
        "rows": [
            {
                "row": "row",
                "status": "completed",
                "events": [{"key": "e", "context": "narrated", "evidence": full, "predicate": aa}],
                "states": [{"key": "s", "context": "narrated", "evidence": full, "predicate": bb}],
                "circumstances": [
                    {"key": "how", "situation": "s", "kind": "manner", "evidence": bb}
                ],
            }
        ],
    }
    publish_json(destination / f"{side}-request.json", request.model_dump(mode="json"))
    publish_json(destination / f"{side}-proposal.json", packet)
    result = compile_rich_construction(request, canonical(packet))
    assert result.resolutions[0].status == "admitted"
    publish_json(destination / f"{side}-result.json", result.model_dump(mode="json"))
    return result.account


source, recall = construct("source"), construct("recall")
account = pair_rich_accounts(source, recall)
relations = []
for side in (source, recall):
    relations.append(
        {
            "key": side.documents[0].side,
            "kind": "before",
            "source": side.events[0].id,
            "target": side.states[0].id,
            "evidence": [
                {"document": side.documents[0].id, "span": {"start": 0, "end": 5}, "text": "AA BB"}
            ],
        }
    )
packet = {
    "schema_version": "storymodel.relation-proposals/v2",
    "account_sha256": digest(account.model_dump(mode="json")),
    "status": "completed",
    "relations": relations,
}
raw = canonical(packet)
export_supported_account(destination / "input.json", account)
(destination / "proposals.json").write_text(raw, encoding="utf-8")
result = admit_relations(account, raw)
assert result.status == "admitted"
verify_relations(account, raw, result)
for field in type(account).model_fields:
    if field not in ("relations", "recipe"):
        assert getattr(result.account, field) == getattr(account, field), field
publish_json(destination / "result.json", result.model_dump(mode="json"))
export_supported_account(destination / "account.json", result.account)
rows = relation_table(read_supported_account(destination / "account.json"))
assert [(r.side, r.source_kind, r.target_kind) for r in rows] == [
    ("source", "event", "state"),
    ("recall", "event", "state"),
]
publish_json(destination / "table.json", [r.model_dump(mode="json") for r in rows])
print(canonical({"relations": len(rows), "rich_collections_preserved": True, "replay": "passed"}))
