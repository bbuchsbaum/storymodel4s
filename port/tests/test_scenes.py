"""Source-only scene grouping reidentifies selected rows without widening evidence."""

import pytest

from storymodel import (
    SceneGroup,
    group_source_rows,
    make_account,
    pair_accounts,
    verify_scene_grouping,
)
from storymodel.model import Context, Document, Entity, Event, Span, State, Support, Unit
from storymodel.rich_account import Circumstance, upgrade_account, validate_rich_account


def fixture():
    document = Document.create("source", "source", "AA BB CC DD EE")
    root = Context(side="source", kind="narrated")
    spans = (Span(start=0, end=2), Span(start=2, end=5), Span(start=9, end=11))
    rows = tuple(
        Unit(kind="row", support=Support(document=document.id, spans=(span,))) for span in spans
    )
    old_scene = Unit(
        kind="scene", support=Support(document=document.id, spans=(Span(start=12, end=14),))
    )
    parented = Unit(
        kind="row",
        parent=old_scene.id,
        support=Support(document=document.id, spans=(Span(start=12, end=14),)),
    )
    entity = Entity.create(document.id, "AA", (rows[0].support,))
    event = Event(
        side="source",
        context=root.id,
        predicate="AA",
        evidence=(rows[0].support,),
    )
    return (
        make_account(
            (document,),
            units=(*rows, old_scene, parented),
            contexts=(root,),
            entities=(entity,),
            events=(event,),
            export_permission="allowed",
        ),
        rows,
        old_scene,
    )


def test_grouping_merges_adjacency_preserves_gaps_and_reparents_only_members():
    account, rows, old_scene = fixture()
    groups = (SceneGroup(key="first", rows=(rows[0].id, rows[1].id, rows[2].id)),)
    result = group_source_rows(account, groups)
    scene_id = result.scenes["first"]
    units = {unit.id: unit for unit in result.account.units}

    assert units[scene_id].support.spans == (Span(start=0, end=5), Span(start=9, end=11))
    assert result.rows == {
        row.id: Unit(kind="row", support=row.support, parent=scene_id).id for row in rows
    }
    assert {units[result.rows[row.id]].parent for row in rows} == {scene_id}
    assert all(row.id not in units for row in rows)
    assert units[old_scene.id] == old_scene
    assert result.account.entities == account.entities and result.account.events == account.events
    assert result.account.documents == account.documents
    assert result.account.contexts == account.contexts
    assert verify_scene_grouping(account, groups, result) == result


def test_member_order_changes_receipt_but_not_scene_or_row_identity():
    account, rows, _ = fixture()
    forward = group_source_rows(account, (SceneGroup(key="x", rows=(rows[0].id, rows[2].id)),))
    reverse = group_source_rows(account, (SceneGroup(key="x", rows=(rows[2].id, rows[0].id)),))

    assert forward.scenes == reverse.scenes
    assert forward.rows == reverse.rows
    assert forward.account.units == reverse.account.units
    assert forward.groups_sha256 != reverse.groups_sha256


def test_rich_grouping_preserves_all_non_unit_graph_collections():
    legacy, rows, _ = fixture()
    rich = upgrade_account(legacy)
    state = State(
        side="source",
        context=rich.contexts[0].id,
        predicate="BB",
        evidence=(rows[1].support,),
        polarity="unknown",
        modality="unknown",
    )
    rich = validate_rich_account(
        rich.model_copy(
            update={
                "states": (state,),
                "circumstances": (
                    Circumstance(
                        situation=state.id, kind="time", label="BB", evidence=(rows[1].support,)
                    ),
                ),
            }
        )
    )
    result = group_source_rows(rich, (SceneGroup(key="x", rows=(rows[0].id, rows[1].id)),))

    assert type(result.account) is type(rich)
    for field in type(rich).model_fields:
        if field not in ("units", "recipe"):
            assert getattr(result.account, field) == getattr(rich, field)


@pytest.mark.parametrize(
    "case", ["paired", "foreign", "parented", "duplicate-key", "duplicate-row"]
)
def test_grouping_refuses_non_source_or_ambiguous_membership(case):
    account, rows, old_scene = fixture()
    if case == "paired":
        recall = make_account((Document.create("recall", "recall", "recall"),))
        target, groups = pair_accounts(account, recall), (SceneGroup(key="x", rows=(rows[0].id,)),)
    elif case == "foreign":
        target, groups = account, (SceneGroup(key="x", rows=("unit:foreign",)),)
    elif case == "parented":
        parented = next(unit for unit in account.units if unit.parent == old_scene.id)
        target, groups = account, (SceneGroup(key="x", rows=(parented.id,)),)
    elif case == "duplicate-key":
        target, groups = (
            account,
            (
                SceneGroup(key="x", rows=(rows[0].id,)),
                SceneGroup(key="x", rows=(rows[1].id,)),
            ),
        )
    else:
        target, groups = account, (SceneGroup(key="x", rows=(rows[0].id, rows[0].id)),)

    with pytest.raises(ValueError):
        group_source_rows(target, groups)


def test_cross_document_and_cross_group_rows_are_refused_at_public_boundary():
    account, rows, _ = fixture()
    other = Document.create("second", "source", "FF")
    other_row = Unit(kind="row", support=Support(document=other.id, spans=(Span(start=0, end=2),)))
    multi = make_account((account.documents[0], other), units=(*account.units, other_row))

    with pytest.raises(ValueError, match="one source document"):
        group_source_rows(multi, (SceneGroup(key="x", rows=(rows[0].id, other_row.id)),))
    with pytest.raises(ValueError, match="only one new scene"):
        group_source_rows(
            account,
            (SceneGroup(key="x", rows=(rows[0].id,)), SceneGroup(key="y", rows=(rows[0].id,))),
        )


def test_forged_input_or_receipt_cannot_bypass_public_revalidation():
    account, rows, _ = fixture()
    groups = (SceneGroup(key="x", rows=(rows[0].id,)),)
    result = group_source_rows(account, groups)
    forged_account = account.model_copy(update={"contexts": ()})
    forged_groups = (SceneGroup.model_construct(key="x", rows=()),)

    with pytest.raises(ValueError):
        group_source_rows(forged_account, groups)
    with pytest.raises(ValueError):
        group_source_rows(account, forged_groups)
    with pytest.raises(ValueError, match="differs from its original inputs"):
        verify_scene_grouping(account, groups, result.model_copy(update={"rows": {}}))
