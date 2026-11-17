"""All retained context kinds through recorded construction and checked consumers.

Abstract tokens only. Declared linguistic/attribution interpretations are controls, not
accuracy evidence. One unchanged source is reused across two independently built recalls.
"""

import sys
from pathlib import Path
from typing import Literal

from storymodel import (
    RichRecording,
    SourceRow,
    admit_details,
    align_account,
    attributed_identity_roles,
    attribution_table,
    export_supported_account,
    import_rich,
    pair_rich_accounts,
    prepare_details,
    prepare_rich_construction,
    replay_rich,
    resolve_attributed_identity,
    resolve_context_holders,
    situation_table,
)
from storymodel.artifacts import publish_json
from storymodel.detail_admission import detail_request_digest
from storymodel.model import Span
from storymodel.rich_account import RichAccount
from storymodel.rich_workflow import rich_prompt
from storymodel.serialization import canonical, digest

KINDS = ("desire", "memory", "imagination", "hypothetical", "counterfactual")


def build(destination: Path, key: str, side: Literal["source", "recall"]) -> RichAccount:
    destination.mkdir(parents=True)
    labels = (*KINDS, "nested-holderless", "nested-unattempted")
    text = "\n".join("AA BB CC DD EE FF" for _ in labels)
    request = prepare_rich_construction(
        key,
        side,
        text,
        tuple(
            SourceRow(key=label, span=Span(start=18 * i, end=18 * i + 17))
            for i, label in enumerate(labels)
        ),
        full_contexts=True,
        export_permission="allowed",
    )
    proposals = []
    form_quotes = []
    for i, label in enumerate(labels):
        start = 18 * i

        def quote(left: int, right: int) -> dict[str, object]:
            span = Span(start=start + left, end=start + right)
            return {"span": span.model_dump(), "text": span.slice(text)}

        contexts = [
            {
                "key": "outer",
                "kind": label if label in KINDS else "believed",
                "parent": "narrated",
                "cue": quote(3, 5),
                "scope": quote(6, 17),
            }
        ]
        if label.startswith("nested"):
            contexts.append(
                {
                    "key": "inner",
                    "kind": "hypothetical" if label == "nested-holderless" else "memory",
                    "parent": "outer",
                    "cue": quote(9, 11),
                    "scope": quote(15, 17),
                }
            )
        proposals.append(
            {
                "row": label,
                "status": "completed",
                "contexts": contexts,
                "entities": [
                    {"key": "holder", "mention": quote(0, 2)},
                    {"key": "pronoun", "mention": quote(15, 17)},
                ],
                "events": [
                    {
                        "key": "event",
                        "context": "narrated",
                        "predicate": quote(0, 2),
                        "evidence": quote(0, 2),
                        "participants": [
                            {"entity": "holder", "role": "speaker", "evidence": quote(0, 2)}
                        ],
                    }
                ],
                "states": [
                    {
                        "key": "state",
                        "context": "inner" if label.startswith("nested") else "outer",
                        "predicate": quote(15, 17),
                        "evidence": quote(15, 17),
                        "participants": [
                            {"entity": "pronoun", "role": "experiencer", "evidence": quote(15, 17)}
                        ],
                    }
                ],
            }
        )
        form_quotes.append((quote(0, 2), quote(15, 17)))
    raw = (
        canonical({"schema_version": "storymodel.rich-construction-proposal/v2", "rows": proposals})
        + "\n"
    )
    recording = RichRecording(
        request_sha256=digest(request.model_dump(mode="json")),
        origin="declared abstract full-context construction",
        reply=raw,
    )
    import_rich(request, recording, destination / "recording")
    constructed = replay_rich(request, destination / "recording")
    assert all(r.status == "admitted" for r in constructed.resolutions)
    account = constructed.account
    assert account.schema_version == "storymodel.account/v5"
    original = account.model_dump_json()
    context_by_id = {c.id: c for c in account.contexts}
    annotations = []
    holder_rows = []
    for resolution, (holder_quote, pronoun_quote) in zip(
        constructed.resolutions, form_quotes, strict=True
    ):
        annotations.extend(
            [
                {
                    "mention": resolution.accepted["holder"],
                    "status": "provided",
                    "form": "name",
                    "entity_type": {"kind": "person"},
                    "quote": holder_quote,
                },
                {
                    "mention": resolution.accepted["pronoun"],
                    "status": "provided",
                    "form": "pronominal",
                    "person": "first",
                    "number": "singular",
                    "quote": pronoun_quote,
                },
            ]
        )
        if resolution.row not in ("hypothetical", "counterfactual"):
            context = context_by_id[resolution.accepted["outer"]]
            holder_rows.append(
                {
                    "context": context.id,
                    "status": "completed",
                    "candidates": [resolution.accepted["holder"]],
                    "evidence": [s.model_dump(mode="json") for s in context.evidence],
                }
            )
    forms = {
        "schema_version": "storymodel.identity-annotations/v1",
        "account_sha256": digest(account.model_dump(mode="json")),
        "origin": "declared abstract forms",
        "mentions": annotations,
    }
    holders = {
        "schema_version": "storymodel.holder-proposals/v1",
        "account_sha256": digest(account.model_dump(mode="json")),
        "origin": "declared abstract holder candidates",
        "contexts": holder_rows,
    }
    forms_raw, holders_raw = canonical(forms) + "\n", canonical(holders) + "\n"
    attribution = resolve_context_holders(account, forms_raw, holders_raw)
    identity = resolve_attributed_identity(account, forms_raw, holders_raw, attribution=attribution)
    final = {m.mention: m for m in identity.mentions}
    outcomes = [final[r.accepted["pronoun"]] for r in constructed.resolutions]
    assert [m.outcome for m in outcomes] == ["resolved"] * 3 + ["open", "open", "resolved", "open"]
    assert [outcomes[i].reason for i in (3, 4, 6)] == [
        "outside-held-context",
        "outside-held-context",
        "holder-unresolved",
    ]
    for name, data in (
        ("request", request.model_dump(mode="json")),
        ("construction", constructed.model_dump(mode="json")),
        ("identity-annotations", forms),
        ("holder-proposals", holders),
        ("attribution", attribution.model_dump(mode="json")),
        ("identity", identity.model_dump(mode="json")),
        ("situations", [r.model_dump(mode="json") for r in situation_table(account)]),
        (
            "attributed-situations",
            [
                r.model_dump(mode="json")
                for r in attribution_table(account, forms_raw, holders_raw, attribution)
            ],
        ),
        (
            "identity-roles",
            [
                r.model_dump(mode="json")
                for r in attributed_identity_roles(account, forms_raw, holders_raw, identity)
            ],
        ),
    ):
        publish_json(destination / f"{name}.json", data)
    (destination / "prompt.json").write_text(rich_prompt(request), encoding="utf-8")
    export_supported_account(destination / "account.json", account)
    assert account.model_dump_json() == original
    return account


def main(destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=False)
    source = build(destination / "source", "context-source", "source")
    original = source.model_dump_json()
    for i in (1, 2):
        recall = build(destination / f"recall-{i}", f"context-recall-{i}", "recall")
        detail_request = prepare_details(recall, tuple(u.id for u in recall.units))
        detail_proposals = {
            "schema_version": "storymodel.detail-proposal/v1",
            "request_sha256": detail_request_digest(detail_request),
            "units": [
                {
                    "unit": unit.id,
                    "status": "completed",
                    "reason": "abstract condition control",
                    "details": [
                        {
                            "key": "condition",
                            "kind": "attribute",
                            "address": "target",
                            "discourse": "content",
                            "situation": state.id,
                            "quotes": [
                                {
                                    "span": span.model_dump(),
                                    "text": span.slice(recall.documents[0].text),
                                }
                                for span in state.evidence[0].spans
                            ],
                        }
                    ],
                }
                for unit, state in zip(recall.units, recall.states, strict=True)
            ],
        }
        detailed = admit_details(detail_request, canonical(detail_proposals) + "\n")
        assert all(r.status == "admitted" for r in detailed.receipts)
        directory = destination / f"recall-{i}"
        publish_json(directory / "detail-request.json", detail_request.model_dump(mode="json"))
        publish_json(directory / "detail-proposals.json", detail_proposals)
        publish_json(directory / "detail-result.json", detailed.model_dump(mode="json"))
        export_supported_account(directory / "detailed-account.json", detailed.account)
        pair = pair_rich_accounts(source, detailed.account)
        export_supported_account(destination / f"paired-{i}.json", pair)
        alignment = align_account(pair)
        publish_json(destination / f"alignment-{i}.json", alignment.model_dump(mode="json"))
        states = [s for s in alignment.situations if s.kind == "state"]
        assert len(states) == 7 and all(s.candidates for s in states)
        assert all(
            c.context != "root-compatible" and not c.eligible for s in states for c in s.candidates
        )
        assert source.model_dump_json() == original


if __name__ == "__main__":
    main(Path(sys.argv[1]))
