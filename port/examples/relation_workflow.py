"""Abstract token graph: recorded relation admission, readback and table inspection.

Tokens are nonnarrative controls. The relation is an explicit diagnostic proposal,
not a claim inferred from text order or an example of scientific accuracy.
"""

import sys
from pathlib import Path

from storymodel import (
    SourceRow,
    account_digest,
    admit_relations,
    compile_source,
    export_account,
    prepare_source,
    read_account,
    relation_table,
    verify_relations,
)
from storymodel.artifacts import publish_json
from storymodel.model import Span
from storymodel.serialization import canonical

destination = Path(sys.argv[1])
destination.mkdir(parents=True, exist_ok=False)
request = prepare_source(
    "abstract-relation-control",
    "AA BB CC DD",
    (
        SourceRow(key="first", span=Span(start=0, end=5)),
        SourceRow(key="second", span=Span(start=6, end=11)),
    ),
    export_permission="allowed",
)
rows = []
for row in request.rows:
    quote = {"span": row.span.model_dump(), "text": row.span.slice(request.document.text)}
    rows.append(
        {
            "row": row.key,
            "status": "completed",
            "events": [{"key": "e", "context": "narrated", "evidence": quote, "predicate": quote}],
        }
    )
source = compile_source(
    request, canonical({"schema_version": "storymodel.source-proposal/v1", "rows": rows})
).account
left, right = source.events
raw = canonical(
    {
        "schema_version": "storymodel.relation-proposals/v1",
        "account_sha256": account_digest(source),
        "status": "completed",
        "relations": [
            {
                "key": "declared-before",
                "kind": "before",
                "source": left.id,
                "target": right.id,
                "evidence": [
                    {"document": request.document.id, "span": {"start": 0, "end": 2}, "text": "AA"},
                    {
                        "document": request.document.id,
                        "span": {"start": 9, "end": 11},
                        "text": "DD",
                    },
                ],
            }
        ],
    }
)
export_account(destination / "input.json", source)
(destination / "proposals.json").write_text(raw, encoding="utf-8")
result = admit_relations(source, raw)
verify_relations(source, raw, result)
publish_json(destination / "result.json", result.model_dump(mode="json"))
export_account(destination / "account.json", result.account)
table = relation_table(read_account(destination / "account.json"))
publish_json(destination / "table.json", [row.model_dump(mode="json") for row in table])
print(f"{result.status}: {len(table)} declared relation, exact discontinuous evidence retained.")
