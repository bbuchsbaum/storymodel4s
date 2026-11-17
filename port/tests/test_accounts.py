"""Independent-account construction and compatibility checks."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from storymodel.accounts import make_account, pair_accounts
from storymodel.model import Account, Context, Document, Entity, Recipe, Span, Support, digest


def _documents() -> tuple[Document, Document]:
    return (
        Document.create("source", "source", "source evidence"),
        Document.create("recall", "recall", "recall evidence"),
    )


def _legacy(version: str) -> Account:
    source, recall = _documents()
    return Account(
        schema_version=version,
        documents=(source, recall),
        units=(),
        contexts=(Context(side="source", kind="narrated"), Context(side="recall", kind="narrated")),
        recipe=Recipe(name="legacy", input_sha256=(source.sha256, recall.sha256)),
    )


@pytest.mark.parametrize("version", ("storymodel.account/v1", "storymodel.account/v2"))
def test_v1_v2_paired_rules_remain_compatible(version: str) -> None:
    assert _legacy(version).schema_version == version
    source, recall = _documents()
    with pytest.raises(ValueError, match="each account side needs exactly one narrated world"):
        Account(
            schema_version=version,
            documents=(source, recall),
            units=(),
            contexts=(Context(side="source", kind="narrated"),),
            recipe=Recipe(name="legacy", input_sha256=(source.sha256, recall.sha256)),
        )


def test_v3_single_side_adds_only_its_narrated_root() -> None:
    source, _ = _documents()
    account = make_account((source,))
    assert account.schema_version == "storymodel.account/v3"
    assert [(context.side, context.kind) for context in account.contexts] == [
        ("source", "narrated")
    ]
    with pytest.raises(ValueError, match="absent account sides cannot have contexts"):
        Account(
            schema_version="storymodel.account/v3",
            documents=(source,),
            units=(),
            contexts=(
                Context(side="source", kind="narrated"),
                Context(side="recall", kind="narrated"),
            ),
            recipe=Recipe(name="bad", input_sha256=(source.sha256,)),
        )


def test_pair_accounts_preserves_graph_ids_and_binds_both_input_accounts() -> None:
    source, recall = _documents()
    source_account = make_account((source,), export_permission="allowed")
    recall_account = make_account((recall,), export_permission="allowed")
    paired = pair_accounts(source_account, recall_account)

    assert paired.schema_version == "storymodel.account/v3"
    assert paired.documents == (source, recall)
    assert paired.contexts == (*source_account.contexts, *recall_account.contexts)
    assert paired.export_permission == "allowed"
    account_digests = {
        digest(source_account.model_dump(mode="json")),
        digest(recall_account.model_dump(mode="json")),
    }
    assert account_digests <= set(paired.recipe.input_sha256)


def test_pair_accounts_refuses_wrong_side_or_already_paired_inputs() -> None:
    source, recall = _documents()
    source_account = make_account((source,))
    recall_account = make_account((recall,))
    with pytest.raises(ValueError, match="recall-only"):
        pair_accounts(source_account, source_account)
    with pytest.raises(ValueError, match="source-only"):
        pair_accounts(pair_accounts(source_account, recall_account), recall_account)


def test_entity_origin_alias_preserves_wire_name_and_identity() -> None:
    source, _ = _documents()
    evidence = Support(document=source.id, spans=(Span(start=0, end=6),))
    old = Entity(source_document=source.id, label="source", evidence=(evidence,))
    new = Entity(origin_document=source.id, label="source", evidence=(evidence,))
    assert new.origin_document == source.id
    assert new.model_dump(mode="json")["source_document"] == source.id
    assert new.id == old.id
    assert Entity.create(source.id, "source", (evidence,)) == old
    with pytest.raises(ValidationError):
        Entity(
            source_document=source.id,
            origin_document=source.id,
            label="source",
            evidence=(evidence,),
        )


def test_identified_namespaces_are_explicit_and_do_not_depend_on_class_name() -> None:
    source, _ = _documents()
    evidence = Support(document=source.id, spans=(Span(start=0, end=6),))

    class RenamedEntity(Entity):
        namespace = "entity"

    renamed = RenamedEntity(source_document=source.id, label="source", evidence=(evidence,))
    original = Entity(source_document=source.id, label="source", evidence=(evidence,))
    assert renamed.id == original.id
    with pytest.raises(TypeError, match="explicit stable namespace"):

        class MissingNamespaceEntity(Entity):
            pass


@pytest.mark.parametrize(
    "source_grant,recall_grant", [("allowed", "denied"), ("denied", "allowed")]
)
def test_pairing_never_elevates_export_and_refuses_copied_invalid_inputs(
    source_grant, recall_grant
):
    source, recall = _documents()
    a = make_account((source,), export_permission=source_grant)
    b = make_account((recall,), export_permission=recall_grant)
    assert pair_accounts(a, b).export_permission == "denied"
    with pytest.raises(ValueError, match="narrated world"):
        pair_accounts(a.model_copy(update={"contexts": ()}), b)
