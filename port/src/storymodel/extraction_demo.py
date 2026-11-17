"""Synthetic proposals over the unchanged, already admitted demo.RECALL_TEXT.

No new narrative text is introduced. Quotes are slices of detail-coding-synthetic/v2,
admitted in demo.py. This generates illustrative raw proposal JSON, not a live model
recording or extraction accuracy gold. Expected graph assertions live in tests.
"""

from typing import Any

from .demo import RECALL_TEXT, example_account
from .extraction import ExtractionRequest, prepare_extraction
from .model import Account, Recipe, Span, canonical


def example_request() -> ExtractionRequest:
    """Keep the admitted source graph; discard the hand-assembled recall and bindings."""
    existing = example_account()
    source_documents = tuple(d for d in existing.documents if d.side == "source")
    source = Account(
        documents=source_documents,
        units=tuple(u for u in existing.units if u.kind != "recall"),
        contexts=existing.contexts,
        entities=existing.entities,
        events=tuple(e for e in existing.events if e.side == "source"),
        recipe=Recipe(
            name="admitted-source-only/v1", input_sha256=tuple(d.sha256 for d in source_documents)
        ),
        export_permission="allowed",
    )
    return prepare_extraction(
        "synthetic-participant/session-1",
        RECALL_TEXT.encode(),
        source=source,
        export_permission="allowed",
    )


def example_proposal() -> str:
    """Produce synthetic provider-shaped text, without constructing internal graph objects."""

    def quote(start: int, end: int) -> dict[str, Any]:
        return {
            "span": {"start": start, "end": end},
            "text": Span(start=start, end=end).slice(RECALL_TEXT),
        }

    return canonical(
        {
            "schema_version": "storymodel.extraction-proposal/v1",
            "units": [
                {
                    "u": 1,
                    "status": "completed",
                    "entities": [
                        {"key": "courier", "mention": quote(4, 11)},
                        {"key": "someone", "mention": quote(49, 56)},
                    ],
                    "events": [
                        {
                            "key": "placement",
                            "context": "narrated",
                            "evidence": quote(4, 43),
                            "predicate": quote(12, 16),
                            "participants": [
                                {"entity": "courier", "role": "agent", "evidence": quote(4, 11)},
                            ],
                        },
                        {
                            "key": "watching",
                            "context": "narrated",
                            "evidence": quote(49, 64),
                            "predicate": quote(57, 64),
                            "participants": [
                                {"entity": "someone", "role": "agent", "evidence": quote(49, 56)},
                            ],
                        },
                    ],
                    "details": [
                        {
                            "key": "action",
                            "event": "placement",
                            "kind": "event",
                            "evidence": quote(4, 43),
                        },
                        {
                            "key": "colour",
                            "event": "placement",
                            "kind": "attribute",
                            "evidence": quote(19, 23),
                        },
                        {
                            "key": "watching-detail",
                            "event": "watching",
                            "kind": "event",
                            "evidence": quote(49, 64),
                        },
                    ],
                    "unresolved": [
                        {
                            "key": "actor-identity",
                            "kind": "entity",
                            "reason": "indefinite mention identity unresolved",
                            "evidence": quote(49, 56),
                        }
                    ],
                }
            ],
        }
    )
