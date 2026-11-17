"""A numeric sparse-path control: equal total costs must retain unequal prior odds.

No story/participant text or provider is involved. Anchor labels are abstract symbols,
not admitted account memberships. Run with one new output directory as the argument.
"""

import sys
from pathlib import Path

from storymodel import infer_sequence, verify_sequence
from storymodel.artifacts import publish_json
from storymodel.sequence import (
    SequenceRequest,
    SequenceRow,
    SequenceState,
    SequenceTransition,
    StateCost,
    TransitionEdge,
)


def example_request() -> SequenceRequest:
    """Two complete paths cost 1e20; their posterior odds must remain 3:1."""
    states = {
        label: SequenceState(kind="source", anchor_kind="situation", anchor=label)
        for label in ("s", "a", "b", "c", "d")
    }
    rows = tuple(
        SequenceRow(
            unit=f"u{i}",
            costs=tuple(StateCost(state=states[label], cost=cost) for label, cost in entries),
        )
        for i, entries in enumerate(
            ([("s", 0.0)], [("a", 1e20), ("b", 0.0)], [("c", 0.0), ("d", 1e20)])
        )
    )
    transitions = tuple(
        SequenceTransition(
            from_unit=f"u{i}",
            to_unit=f"u{i + 1}",
            edges=tuple(
                TransitionEdge(
                    from_state=states[left].id, to_state=states[right].id, probability=probability
                )
                for left, right, probability in entries
            ),
        )
        for i, entries in enumerate(
            ([("s", "a", 0.75), ("s", "b", 0.25)], [("a", "c", 1.0), ("b", "d", 1.0)])
        )
    )
    return SequenceRequest(rows=rows, transitions=transitions, export_permission="allowed")


if __name__ == "__main__":
    destination = Path(sys.argv[1])
    destination.mkdir(parents=True, exist_ok=False)
    request = example_request()
    result = infer_sequence(request)
    publish_json(destination / "request.json", request.model_dump(mode="json"))
    publish_json(destination / "result.json", result.model_dump(mode="json"))
    verify_sequence(
        (destination / "request.json").read_bytes(), (destination / "result.json").read_bytes()
    )
    print("Checked sparse sequence; posterior odds remain 3:1 after equal total costs.")
