"""Source graph -> derived transitions -> sequence inference, with declared local costs.

Abstract symbol controls only. This example neither extracts claims nor estimates accuracy.
Run: uv run python examples/source_sequence.py data/source-sequence
"""

import sys
from pathlib import Path

from storymodel import (
    SourceProjectionPolicy,
    SourceSequenceSpec,
    build_source_sequence,
    infer_sequence,
    make_account,
    verify_source_sequence,
)
from storymodel.artifacts import publish_json
from storymodel.model import Context, Document, Event, Relation, Span, Support, Unit
from storymodel.sequence import SequenceRow, SequenceState, StateCost


def run(output: Path) -> None:
    source_doc = Document.create("sequence-source", "source", "AA BB CC")
    root = Context(side="source", kind="narrated")
    rows = tuple(
        Unit(kind="row", support=Support(document=source_doc.id, spans=(Span(start=i, end=i + 2),)))
        for i in (0, 3, 6)
    )
    events = tuple(
        Event(side="source", context=root.id, predicate=token, evidence=(row.support,))
        for token, row in zip(("AA", "BB", "CC"), rows)
    )
    source = make_account(
        (source_doc,),
        contexts=(root,),
        units=rows,
        events=events,
        relations=tuple(
            Relation(
                kind="before",
                source=events[a].id,
                target=events[b].id,
                context=root.id,
                evidence=(rows[a].support, rows[b].support),
            )
            for a, b in ((0, 1), (1, 2), (0, 2))
        ),
        export_permission="allowed",
    )
    recall_doc = Document.create("sequence-recall", "recall", "AA BB AA")
    units = tuple(
        Unit(
            kind="recall",
            support=Support(document=recall_doc.id, spans=(Span(start=i, end=i + 2),)),
        )
        for i in (0, 3, 6)
    )
    recall = make_account((recall_doc,), units=units, export_permission="allowed")
    states = tuple(
        SequenceState(kind="source", anchor_kind="situation", anchor=e.id) for e in events
    )
    external = SequenceState(kind="external", external="intrusion")
    # These energies are declared controls, not output of a semantic or fidelity assessor.
    costs = ((0.0, 3.0, 4.0), (3.0, 0.0, 2.0), (0.0, 3.0, 4.0))
    spec = SourceSequenceSpec(
        rows=tuple(
            SequenceRow(
                unit=u.id,
                costs=(
                    *(StateCost(state=s, cost=c) for s, c in zip(states, values)),
                    StateCost(state=external, cost=2.0),
                ),
            )
            for u, values in zip(units, costs)
        ),
        projection=SourceProjectionPolicy(
            scoring_documents=(source_doc.id,),
            memberships={e.id: r.id for e, r in zip(events, rows)},
        ),
    )
    derived = build_source_sequence(source, recall, spec)
    verify_source_sequence(source, recall, spec, derived)
    result = infer_sequence(derived.request)
    output.mkdir(parents=True, exist_ok=False)
    for name, artifact in (
        ("source", source),
        ("recall", recall),
        ("spec", spec),
        ("derived", derived),
        ("request", derived.request),
        ("result", result),
    ):
        publish_json(output / f"{name}.json", artifact.model_dump(mode="json"))
    print(f"Saved {len(result.posterior)} posterior rows; local costs were supplied explicitly.")


if __name__ == "__main__":
    run(Path(sys.argv[1]))
