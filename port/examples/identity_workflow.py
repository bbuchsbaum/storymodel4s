"""An abstract two-token coreference control with explicit form annotations.

No narrative text, semantic accuracy or automatic grammatical classification is
claimed. Source construction and identity inputs are both saved for replay.
"""

import sys
from pathlib import Path

from storymodel import (
    SourceRow,
    account_digest,
    compile_source,
    export_account,
    identity_role_table,
    prepare_source,
    resolve_text_identity,
    verify_identity,
)
from storymodel.artifacts import publish_json
from storymodel.model import Span
from storymodel.serialization import canonical

destination = Path(sys.argv[1])
destination.mkdir(parents=True, exist_ok=False)
request = prepare_source(
    "abstract-identity-control",
    "AA\nhe",
    (
        SourceRow(key="first", span=Span(start=0, end=2)),
        SourceRow(key="second", span=Span(start=3, end=5)),
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
            "entities": [{"key": "actor", "mention": quote}],
            "events": [
                {
                    "key": "e",
                    "context": "narrated",
                    "evidence": quote,
                    "predicate": quote,
                    "participants": [{"entity": "actor", "role": "agent", "evidence": quote}],
                }
            ],
        }
    )
source_proposal = {"schema_version": "storymodel.source-proposal/v1", "rows": rows}
source = compile_source(request, canonical(source_proposal))
account = source.account
annotations = {
    "schema_version": "storymodel.identity-annotations/v1",
    "account_sha256": account_digest(account),
    "origin": "explicit abstract form annotations; no provider call",
    "mentions": [
        {
            "mention": account.entities[0].id,
            "status": "provided",
            "form": "nominal",
            "entity_type": {"kind": "person"},
            "quote": rows[0]["entities"][0]["mention"],
        },
        {
            "mention": account.entities[1].id,
            "status": "provided",
            "form": "pronominal",
            "person": "third",
            "number": "singular",
            "quote": rows[1]["entities"][0]["mention"],
        },
    ],
}
raw = canonical(annotations)
result = resolve_text_identity(account, raw)
verify_identity(account, raw, result)
publish_json(destination / "source-request.json", request.model_dump(mode="json"))
publish_json(destination / "source-proposals.json", source_proposal)
publish_json(destination / "source-result.json", source.model_dump(mode="json"))
export_account(destination / "account.json", account)
(destination / "annotations.json").write_text(raw, encoding="utf-8")
publish_json(destination / "identity.json", result.model_dump(mode="json"))
table = identity_role_table(account, raw, result)
publish_json(destination / "roles.json", [row.model_dump(mode="json") for row in table])
assert len(result.referents) == 1 and len(result.referents[0].members) == 2
assert table[0].identity.referent == table[1].identity.referent
print("2 mentions, 1 text referent; original event IDs and contexts retained.")
