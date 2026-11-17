"""Independent contract tests for atomic, account-bound event relation admission."""

from __future__ import annotations

import pytest

from storymodel.model import Account, Context, Document, Event, Recipe, Span, Support, digest
from storymodel.relations import admit_relations, relation_table, verify_relations
from storymodel.serialization import canonical


def account(*, attributed: bool = False, unicode_source: bool = False, relations=()) -> Account:
    source = Document.create(
        "relation-source", "source", "AA 😀 CC DD" if unicode_source else "AA BB CC DD"
    )
    recall = Document.create("relation-recall", "recall", "EE FF GG HH")

    def support(document: Document, start: int, end: int) -> Support:
        return Support(document=document.id, spans=(Span(start=start, end=end),))

    source_root = Context(side="source", kind="narrated")
    recall_root = Context(side="recall", kind="narrated")
    contexts = [source_root, recall_root]
    source_events = [
        Event(
            side="source",
            context=source_root.id,
            predicate="source-one",
            evidence=(support(source, 0, 2),),
        ),
        Event(
            side="source",
            context=source_root.id,
            predicate="source-two",
            evidence=(support(source, 3, 5),),
        ),
    ]
    if attributed:
        reported = Context(
            side="source", kind="reported", parent=source_root.id, evidence=(support(source, 6, 8),)
        )
        contexts.append(reported)
        source_events.append(
            Event(
                side="source",
                context=reported.id,
                predicate="source-three",
                evidence=(support(source, 6, 8),),
            )
        )
    recall_events = [
        Event(
            side="recall",
            context=recall_root.id,
            predicate="recall-one",
            evidence=(support(recall, 0, 2),),
        ),
        Event(
            side="recall",
            context=recall_root.id,
            predicate="recall-two",
            evidence=(support(recall, 3, 5),),
        ),
    ]
    return Account(
        documents=(source, recall),
        units=(),
        contexts=tuple(contexts),
        events=tuple(source_events + recall_events),
        relations=tuple(relations),
        recipe=Recipe(name="relation-control/v1", input_sha256=(source.sha256, recall.sha256)),
    )


def raw(value: Account, *, status="completed", reason=None, relations=()) -> str:
    return canonical(
        {
            "schema_version": "storymodel.relation-proposals/v1",
            "account_sha256": digest(value.model_dump(mode="json")),
            "status": status,
            **({"reason": reason} if reason is not None else {}),
            "relations": relations,
        }
    )


def proposal(key: str, kind: str, source: Event, target: Event, *quotes) -> dict[str, object]:
    return {"key": key, "kind": kind, "source": source.id, "target": target.id, "evidence": quotes}


def quote(
    document: Document, start: int, end: int, *, text: str | None = None
) -> dict[str, object]:
    span = Span(start=start, end=end)
    return {
        "document": document.id,
        "span": span.model_dump(mode="json"),
        "text": span.slice(document.text) if text is None else text,
    }


def test_admits_existing_four_kinds_and_flattens_exact_discontinuous_evidence():
    value = account()
    source, recall = value.documents
    first, second, recall_first, recall_second = value.events
    response = raw(
        value,
        relations=(
            proposal("part", "part_of", first, second, quote(source, 0, 2)),
            proposal("before", "before", second, first, quote(source, 3, 5)),
            proposal("cause", "causes", first, second, quote(source, 0, 2)),
            proposal(
                "reference",
                "references",
                recall_first,
                recall_second,
                quote(recall, 0, 2),
                quote(recall, 3, 5),
            ),
        ),
    )
    result = admit_relations(value, response)
    assert result.status == "admitted"
    assert set(result.accepted) == {"part", "before", "cause", "reference"}
    assert [relation.kind for relation in result.account.relations] == [
        "part_of",
        "before",
        "causes",
        "references",
    ]
    assert tuple(event.id for event in result.account.events) == tuple(
        event.id for event in value.events
    )
    assert tuple(document.id for document in result.account.documents) == tuple(
        document.id for document in value.documents
    )
    rows = relation_table(result.account)
    assert [(row.kind, row.side) for row in rows] == [
        ("part_of", "source"),
        ("before", "source"),
        ("causes", "source"),
        ("references", "recall"),
    ]
    assert [(item.span.start, item.span.end, item.text) for item in rows[-1].evidence] == [
        (0, 2, "EE"),
        (3, 5, "FF"),
    ]


@pytest.mark.parametrize("kind", ["part_of", "before", "causes", "references"])
@pytest.mark.parametrize("side", ["source", "recall"])
def test_each_existing_relation_kind_admits_on_each_account_side(kind, side):
    value = account()
    source, recall = value.documents
    first, second, recall_first, recall_second = value.events
    left, right, document = (
        (first, second, source) if side == "source" else (recall_first, recall_second, recall)
    )
    result = admit_relations(
        value,
        raw(
            value,
            relations=(proposal(f"{side}-{kind}", kind, left, right, quote(document, 0, 2)),),
        ),
    )
    assert result.status == "admitted"
    assert relation_table(result.account)[0].kind == kind
    assert relation_table(result.account)[0].side == side


def test_cross_context_references_are_allowed_but_other_kinds_refuse_atomically():
    value = account(attributed=True)
    source = value.documents[0]
    first, _, attributed, *_ = value.events
    reference = raw(
        value,
        relations=(proposal("ref", "references", first, attributed, quote(source, 0, 2)),),
    )
    assert admit_relations(value, reference).status == "admitted"
    nonreference = raw(
        value,
        relations=(proposal("cause", "causes", first, attributed, quote(source, 0, 2)),),
    )
    refused = admit_relations(value, nonreference)
    assert refused.status == "rejected"
    assert refused.account == value and not refused.accepted
    assert "crosses attributed contexts" in (refused.reason or "")


@pytest.mark.parametrize(
    "case",
    [
        "foreign-endpoint",
        "foreign-document",
        "wrong-evidence-side",
        "wrong-endpoint-side",
        "self-edge",
        "overlap",
    ],
)
def test_named_invalid_claims_reject_whole_batch_without_a_winner(case):
    value = account()
    source, recall = value.documents
    first, second, recall_first, _ = value.events
    external = Document.create("external", "source", "ZZ")
    relation = proposal("invalid", "before", first, second, quote(source, 0, 2))
    if case == "foreign-endpoint":
        relation |= {"target": "foreign"}
    elif case == "foreign-document":
        relation["evidence"] = (quote(external, 0, 2),)
    elif case == "wrong-evidence-side":
        relation["evidence"] = (quote(recall, 0, 2),)
    elif case == "wrong-endpoint-side":
        relation |= {"kind": "references", "target": recall_first.id}
    elif case == "self-edge":
        relation |= {"target": first.id}
    else:
        relation["evidence"] = (quote(source, 0, 2), quote(source, 1, 3))
    result = admit_relations(value, raw(value, relations=(relation,)))
    assert result.status == "rejected"
    assert result.account == value and not result.accepted


def test_wrong_quote_is_rejected_without_mutating_the_input_account():
    value = account()
    source = value.documents[0]
    first, second, *_ = value.events
    result = admit_relations(
        value,
        raw(
            value,
            relations=(
                proposal("quote", "before", first, second, quote(source, 0, 2, text="tampered")),
            ),
        ),
    )
    assert result.status == "rejected"
    assert result.account == value and not result.accepted


def test_surrogate_split_quote_is_rejected_without_parsing_a_partial_character():
    value = account(unicode_source=True)
    source = value.documents[0]
    first, second, *_ = value.events
    split = {"document": source.id, "span": {"start": 4, "end": 5}, "text": "x"}
    result = admit_relations(
        value,
        raw(value, relations=(proposal("split", "before", first, second, split),)),
    )
    assert result.status == "rejected"
    assert result.account == value and not result.accepted


@pytest.mark.parametrize("order", ["forward-reverse", "reverse-forward"])
def test_batch_cycle_refuses_regardless_of_proposal_order(order):
    value = account()
    source = value.documents[0]
    first, second, *_ = value.events
    forward = proposal("forward", "before", first, second, quote(source, 0, 2))
    reverse = proposal("reverse", "before", second, first, quote(source, 3, 5))
    batch = (forward, reverse) if order == "forward-reverse" else (reverse, forward)
    result = admit_relations(value, raw(value, relations=batch))
    assert result.status == "rejected"
    assert result.account == value and not result.accepted


def test_duplicate_edges_and_existing_edge_cycles_are_rejected_atomically():
    value = account()
    source = value.documents[0]
    first, second, *_ = value.events
    duplicate = admit_relations(
        value,
        raw(
            value,
            relations=(
                proposal("one", "causes", first, second, quote(source, 0, 2)),
                proposal("duplicate", "causes", first, second, quote(source, 0, 2)),
            ),
        ),
    )
    assert duplicate.status == "rejected" and duplicate.account == value and not duplicate.accepted

    distinct_evidence = admit_relations(
        value,
        raw(
            value,
            relations=(
                proposal("first-support", "causes", first, second, quote(source, 0, 2)),
                proposal("second-support", "causes", first, second, quote(source, 3, 5)),
            ),
        ),
    )
    assert distinct_evidence.status == "admitted"
    assert set(distinct_evidence.accepted) == {"first-support", "second-support"}
    assert [
        tuple((span.start, span.end) for span in relation.evidence[0].spans)
        for relation in distinct_evidence.account.relations
    ] == [((0, 2),), ((3, 5),)]

    existing = admit_relations(
        value,
        raw(value, relations=(proposal("existing", "before", first, second, quote(source, 0, 2)),)),
    ).account
    backwards = raw(
        existing,
        relations=(proposal("backwards", "before", second, first, quote(source, 3, 5)),),
    )
    result = admit_relations(existing, backwards)
    assert result.status == "rejected" and result.account == existing and not result.accepted


def test_processing_missingness_and_verification_bind_exact_account_and_result():
    value = account()
    source = value.documents[0]
    first, second, *_ = value.events
    limited = raw(
        value,
        status="search-limited",
        reason="bounded control search",
        relations=(proposal("edge", "before", first, second, quote(source, 0, 2)),),
    )
    result = admit_relations(value, limited)
    assert result.status == "partial"
    assert (
        admit_relations(value, raw(value, status="not-attempted", reason="not run")).status
        == "not-attempted"
    )
    with pytest.raises(ValueError, match="unprocessed relation batch"):
        admit_relations(
            value,
            raw(
                value,
                status="failed",
                reason="interrupted",
                relations=(proposal("edge", "before", first, second, quote(source, 0, 2)),),
            ),
        )
    duplicate_key = proposal("same", "before", first, second, quote(source, 0, 2))
    with pytest.raises(ValueError, match="duplicate relation proposal key"):
        admit_relations(value, raw(value, relations=(duplicate_key, duplicate_key)))
    with pytest.raises(ValueError):
        admit_relations(value, canonical({"schema_version": "storymodel.relation-proposals/v1"}))

    assert verify_relations(value, limited, result) == result
    with pytest.raises(ValueError, match="differs from recorded"):
        verify_relations(value, limited, result.model_copy(update={"status": "admitted"}))
    with pytest.raises(ValueError, match="another account"):
        verify_relations(account(attributed=True), limited, result)
