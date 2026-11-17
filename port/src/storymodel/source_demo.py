"""Source proposals over unchanged, admitted demo.SOURCE_ROWS.

This introduces no new narrative text. Source bytes are the original project
diagnostic admitted in demo.py; quotes are exact slices. These are synthetic
provider-shaped proposals, not a model run or semantic accuracy gold.
"""

from typing import Any

from .demo import SOURCE_ROWS
from .model import Span
from .serialization import canonical
from .source import SourceRequest, SourceRow, prepare_source


def example_source_request() -> SourceRequest:
    """Prepare observation rows, without constructing any trusted source graph objects."""
    text = "\n".join(SOURCE_ROWS)
    boundary = len(SOURCE_ROWS[0].encode("utf-16-le")) // 2
    return prepare_source(
        "detail-coding-synthetic/v2",
        text,
        (
            SourceRow(key="observation-1", span=Span(start=0, end=boundary)),
            SourceRow(key="observation-2", span=Span(start=boundary + 1, end=len(text))),
        ),
        export_permission="allowed",
    )


def example_source_proposal() -> str:
    """Two extractive event proposals; expected admitted structure lives in the tests."""
    request = example_source_request()
    text = request.document.text

    def quote(start: int, end: int) -> dict[str, Any]:
        span = Span(start=start, end=end)
        return {"span": span.model_dump(), "text": span.slice(text)}

    rows = []
    for row, actor, predicate in zip(request.rows, ("courier", "guard"), ("places", "watches")):
        actor_start = text.index(actor, row.span.start)
        verb_start = text.index(predicate, row.span.start)
        mention = quote(actor_start, actor_start + len(actor))
        rows.append(
            {
                "row": row.key,
                "status": "completed",
                "entities": [{"key": "actor", "mention": mention}],
                "events": [
                    {
                        "key": "action",
                        "context": "narrated",
                        "evidence": quote(row.span.start, row.span.end),
                        "predicate": quote(verb_start, verb_start + len(predicate)),
                        "participants": [{"entity": "actor", "role": "agent", "evidence": mention}],
                    }
                ],
            }
        )
    return canonical({"schema_version": "storymodel.source-proposal/v1", "rows": rows})
