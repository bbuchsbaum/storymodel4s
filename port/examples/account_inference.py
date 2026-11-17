"""Abstract account -> retrieval/gates/costs -> graph transitions -> sequence posterior.

No provider calls, supplied costs or transition matrices. Known graph controls are not an
extraction or scientific accuracy study. All text consists of abstract symbol tokens.
"""

import sys
from pathlib import Path
from typing import Literal

from storymodel import (
    LocalEvidenceSpec,
    RecallSelection,
    infer_accounts,
    make_account,
    verify_account_inference,
)
from storymodel.artifacts import publish_json
from storymodel.model import Context, Document, Entity, Participation, Span, State, Support, Unit
from storymodel.rich_account import RichAccount, upgrade_account, validate_rich_account
from storymodel.source_transitions import SourceProjectionPolicy


def control(side: Literal["source", "recall"], text: str) -> RichAccount:
    doc = Document.create("inference-" + side, side, text)
    context = Context(side=side, kind="narrated")
    rows = tuple(
        Unit(
            kind="row" if side == "source" else "recall",
            support=Support(document=doc.id, spans=(Span(start=i, end=i + 8),)),
        )
        for i in range(0, len(text), 9)
    )
    entities = {
        label: Entity.create(
            doc.id, label, (Support(document=doc.id, spans=(Span(start=start, end=start + 2),)),)
        )
        for label, start in (("AA", 0), ("BB", 6))
    }
    states = []
    for index, row in enumerate(rows):
        tokens = row.support.spans[0].slice(text).split()
        states.append(
            State(
                side=side,
                context=context.id,
                predicate=tokens[1],
                participants=tuple(
                    Participation(entity=entities[token].id, role=role, evidence=(row.support,))
                    for role, token in (("agent", tokens[0]), ("patient", tokens[2]))
                ),
                evidence=(row.support,),
                modality="asserted",
                polarity="negative" if side == "recall" and index == 2 else "positive",
            )
        )
    base = upgrade_account(
        make_account(
            (doc,),
            units=rows,
            contexts=(context,),
            entities=tuple(entities.values()),
            export_permission="allowed",
        )
    )
    return validate_rich_account(base.model_copy(update={"states": tuple(states)}))


def run(output: Path) -> None:
    source = control("source", "AA PP BB\nAA QQ BB")
    recall = control("recall", "AA PP BB\nBB PP AA\nAA PP BB")
    spec = LocalEvidenceSpec(
        units=tuple(
            RecallSelection(unit=u.id, function="episodic-assertion") for u in recall.units
        ),
        projection=SourceProjectionPolicy(
            memberships={s.id: u.id for s, u in zip(source.states, source.units)}
        ),
    )
    result = infer_accounts(source, recall, spec)
    verify_account_inference(source, recall, spec, result)
    # Independent semantic expectations: no faithful mode can reappear after a gate.
    wanted = ((), ("role-reversal",), ("polarity",))
    for unit, facets in zip(recall.units, wanted):
        cell = next(
            c
            for c in result.evidence.cells
            if c.unit == unit.id and c.target == source.states[0].id
        )
        assert cell.state.facets == facets
    output.mkdir(parents=True, exist_ok=False)
    for name, artifact in (
        ("source", source),
        ("recall", recall),
        ("spec", spec),
        ("evidence", result.evidence),
        ("request", result.evidence.sequence.request),
        ("inference", result),
    ):
        publish_json(output / f"{name}.json", artifact.model_dump(mode="json"))
    print("Saved 3 inferred units: ordinary, role-reversed and polarity-conflicting controls.")


if __name__ == "__main__":
    run(Path(sys.argv[1]))
