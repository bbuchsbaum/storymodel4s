"""An executable version of the already admitted detail-coding-synthetic/v2 example.

Source: docs/design/detail-coding/synthetic-worked-example.md, machine-authored project
diagnostic, Toronto 2026. Basis: original Apache-2.0 contribution; no participant or
traditional content. Modifications: source observations joined with one newline; original
recall is unchanged. Predicate labels and graph structure below are illustrative proposals,
not empirically established labels. Admitted by: independent reviewer /root/legacy_port,
2026-11-16, exact text literals compared to admitted v2. Reviewed pre-header file SHA-256:
197479e5bd584aaf5ed59a6e7716c284f3bf34c6d9d050666da78047471f5d72.
"""

from .model import (
    Account,
    Assessment,
    Binding,
    Context,
    Detail,
    Document,
    Entity,
    Event,
    Hypothesis,
    Inventory,
    Participation,
    Recipe,
    Reference,
    Span,
    Support,
    Unit,
)

SOURCE_ROWS = (
    "A courier places a blue parcel by a bench.",
    "A guard watches from the doorway.",
)
RECALL_TEXT = "The courier left a blue parcel by the bench, and someone watched."


def example_account() -> Account:
    """Construct the three-detail policy witness with two independently anchored source rows."""
    source = Document.create("detail-coding-synthetic/v2", "source", "\n".join(SOURCE_ROWS))
    recall = Document.create("synthetic-participant/session-1", "recall", RECALL_TEXT)

    def evidence(document: Document, start: int, end: int) -> Support:
        return Support(document=document.id, spans=(Span(start=start, end=end),))

    source_all = evidence(source, 0, len(source.text))
    scene = Unit(kind="scene", support=source_all)
    row1 = Unit(kind="row", support=evidence(source, 0, len(SOURCE_ROWS[0])), parent=scene.id)
    row2 = Unit(
        kind="row",
        support=evidence(source, len(SOURCE_ROWS[0]) + 1, len(source.text)),
        parent=scene.id,
    )
    recall_all = evidence(recall, 0, len(RECALL_TEXT))
    unit = Unit(kind="recall", support=recall_all)
    source_context, recall_context = (
        Context(side="source", kind="narrated"),
        Context(side="recall", kind="narrated"),
    )
    courier = Entity(source_document=source.id, label="courier", evidence=(row1.support,))
    source_event = Event(
        side="source",
        context=source_context.id,
        predicate="placing",
        evidence=(row1.support,),
        participants=(Participation(entity=courier.id, role="agent", evidence=(row1.support,)),),
    )
    recall_event = Event(
        side="recall",
        context=recall_context.id,
        predicate="placing",
        evidence=(recall_all,),
        participants=(
            Participation(entity=courier.id, role="agent", evidence=(evidence(recall, 4, 11),)),
        ),
    )
    details = (
        Detail(
            unit=unit.id,
            kind="event",
            content="placement",
            address="target",
            support=evidence(recall, 4, 43),
            event=recall_event.id,
        ),
        Detail(
            unit=unit.id,
            kind="attribute",
            content="colour",
            address="target",
            support=evidence(recall, 19, 23),
            event=recall_event.id,
        ),
        Detail(
            unit=unit.id,
            kind="event",
            content="watching",
            address="target",
            support=evidence(recall, 49, 64),
        ),
    )
    bindings = []
    for detail, row in zip(details, (row1, row1, row2), strict=True):
        hypothesis = Hypothesis(kind="single", targets=(row.id,))
        bindings.append(
            Binding(
                detail=detail.id,
                reference=Reference(
                    status="attempted", outcome="resolved", hypotheses=(hypothesis,)
                ),
                assessments=(
                    Assessment(
                        hypothesis=hypothesis.id,
                        status="completed",
                        grade="supported",
                        evidence=(row.support,),
                    ),
                ),
            )
        )
    return Account(
        documents=(source, recall),
        units=(scene, row1, row2, unit),
        contexts=(source_context, recall_context),
        entities=(courier,),
        events=(source_event, recall_event),
        details=details,
        inventories=(Inventory(unit=unit.id, status="completed"),),
        bindings=tuple(bindings),
        recipe=Recipe(
            name="admitted-worked-example/v1", input_sha256=(source.sha256, recall.sha256)
        ),
        export_permission="allowed",
    )
