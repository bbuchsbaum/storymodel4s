"""Abstract token control: construction, identity, holder resolution and inspection.

No narrative/participant text or semantic accuracy claim. Forms, roles and attribution
are declared test interpretations. Run with an output directory as the only argument.
"""

import sys
from pathlib import Path

from storymodel import (
    SourceRow,
    attribution_table,
    compile_rich_construction,
    export_supported_account,
    prepare_rich_construction,
    resolve_context_holders,
    verify_attribution,
)
from storymodel.artifacts import publish_json
from storymodel.model import Span
from storymodel.serialization import canonical, digest

destination = Path(sys.argv[1])
destination.mkdir(parents=True, exist_ok=False)
request = prepare_rich_construction(
    "attribution-control",
    "source",
    "AA AA CC",
    (SourceRow(key="row", span=Span(start=0, end=8)),),
    export_permission="allowed",
)
quotes = [
    {"span": {"start": start, "end": start + 2}, "text": request.document.text[start : start + 2]}
    for start in (0, 3, 6)
]
construction = {
    "schema_version": "storymodel.rich-construction-proposal/v1",
    "rows": [
        {
            "row": "row",
            "status": "completed",
            "contexts": [
                {
                    "key": "held",
                    "kind": "reported",
                    "parent": "narrated",
                    "cue": quotes[1],
                    "scope": quotes[2],
                }
            ],
            "entities": [{"key": f"m{i}", "mention": q} for i, q in enumerate(quotes)],
            "events": [
                {
                    "key": f"e{i}",
                    "context": "narrated",
                    "predicate": q,
                    "evidence": q,
                    "participants": [{"entity": f"m{i}", "role": "speaker", "evidence": q}],
                }
                for i, q in enumerate(quotes[:2])
            ],
            "states": [
                {
                    "key": "s",
                    "context": "held",
                    "predicate": quotes[2],
                    "evidence": quotes[2],
                    "participants": [
                        {"entity": "m2", "role": "experiencer", "evidence": quotes[2]}
                    ],
                }
            ],
        }
    ],
}
# publish_json writes canonical JSON plus a newline; receipts must bind those exact bytes.
built = compile_rich_construction(request, canonical(construction) + "\n")
assert built.resolutions[0].status == "admitted"
account = built.account
before = account.model_dump_json()
annotations = {
    "schema_version": "storymodel.identity-annotations/v1",
    "account_sha256": digest(account.model_dump(mode="json")),
    "origin": "declared abstract token forms",
    "mentions": [
        {
            "mention": entity.id,
            "status": "provided",
            "quote": quote,
            **(
                {"form": "name", "entity_type": {"kind": "person"}}
                if i < 2
                else {"form": "pronominal", "person": "first", "number": "singular"}
            ),
        }
        for i, (entity, quote) in enumerate(zip(account.entities, quotes, strict=True))
    ],
}
held = next(c for c in account.contexts if c.kind == "reported")
proposals = {
    "schema_version": "storymodel.holder-proposals/v1",
    "account_sha256": digest(account.model_dump(mode="json")),
    "origin": "declared abstract attribution candidates",
    "contexts": [
        {
            "context": held.id,
            "status": "completed",
            "candidates": [e.id for e in account.entities[:2]],
            "evidence": [s.model_dump(mode="json") for s in held.evidence],
        }
    ],
}
raw_forms, raw_holders = canonical(annotations) + "\n", canonical(proposals) + "\n"
result = resolve_context_holders(account, raw_forms, raw_holders)
verify_attribution(account, raw_forms, raw_holders, result)
table = attribution_table(account, raw_forms, raw_holders, result)
assert table[-1].holder.status == "named"
assert len(table[-1].holder.candidates) == 2
assert table[-1].context.id == account.states[0].context
assert account.model_dump_json() == before
for name, data in (
    ("construction-request", request.model_dump(mode="json")),
    ("construction-proposals", construction),
    ("construction-result", built.model_dump(mode="json")),
    ("identity-annotations", annotations),
    ("holder-proposals", proposals),
    ("attribution", result.model_dump(mode="json")),
    ("situations", [r.model_dump(mode="json") for r in table]),
):
    publish_json(destination / f"{name}.json", data)
export_supported_account(destination / "account.json", account)
