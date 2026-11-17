"""Construct and join independently valid source and recall accounts."""

from __future__ import annotations

from collections.abc import Iterable
from typing import Literal

from .model import (
    Account,
    Binding,
    Context,
    Detail,
    Document,
    Entity,
    Event,
    Inventory,
    Recipe,
    Relation,
    Unit,
    digest,
    validate_account,
)


def make_account(
    documents: Iterable[Document],
    *,
    units: Iterable[Unit] = (),
    contexts: Iterable[Context] = (),
    entities: Iterable[Entity] = (),
    events: Iterable[Event] = (),
    relations: Iterable[Relation] = (),
    details: Iterable[Detail] = (),
    inventories: Iterable[Inventory] = (),
    bindings: Iterable[Binding] = (),
    recipe: Recipe | None = None,
    export_permission: Literal["allowed", "denied"] = "denied",
) -> Account:
    """Build a v3 account, adding one narrated root for every documented side."""
    document_values = tuple(documents)
    context_values = tuple(contexts)
    for side in ("source", "recall"):
        if any(document.side == side for document in document_values) and not any(
            context.side == side and context.kind == "narrated" for context in context_values
        ):
            context_values += (Context(side=side, kind="narrated"),)
    input_sha256 = tuple(document.sha256 for document in document_values)
    return Account(
        schema_version="storymodel.account/v3",
        documents=document_values,
        units=tuple(units),
        contexts=context_values,
        entities=tuple(entities),
        events=tuple(events),
        relations=tuple(relations),
        details=tuple(details),
        inventories=tuple(inventories),
        bindings=tuple(bindings),
        recipe=recipe
        or Recipe(name="storymodel.accounts.make_account/v1", input_sha256=input_sha256),
        export_permission=export_permission,
    )


def _strict_side_account(account: Account, side: str) -> Account:
    checked = validate_account(account)
    if checked.schema_version != "storymodel.account/v3":
        raise ValueError("pair_accounts requires v3 accounts")
    if not checked.documents or any(document.side != side for document in checked.documents):
        raise ValueError(f"pair_accounts requires a {side}-only account")
    return checked


def pair_accounts(source: Account, recall: Account) -> Account:
    """Join one source-only and one recall-only v3 account without rewriting their graph IDs."""
    source_account = _strict_side_account(source, "source")
    recall_account = _strict_side_account(recall, "recall")
    source_digest = digest(source_account.model_dump(mode="json"))
    recall_digest = digest(recall_account.model_dump(mode="json"))
    documents = (*source_account.documents, *recall_account.documents)
    return Account(
        schema_version="storymodel.account/v3",
        documents=documents,
        units=(*source_account.units, *recall_account.units),
        contexts=(*source_account.contexts, *recall_account.contexts),
        entities=(*source_account.entities, *recall_account.entities),
        events=(*source_account.events, *recall_account.events),
        relations=(*source_account.relations, *recall_account.relations),
        details=(*source_account.details, *recall_account.details),
        inventories=(*source_account.inventories, *recall_account.inventories),
        bindings=(*source_account.bindings, *recall_account.bindings),
        recipe=Recipe(
            name="storymodel.accounts.pair_accounts/v1",
            input_sha256=(
                *(document.sha256 for document in documents),
                source_digest,
                recall_digest,
            ),
        ),
        export_permission=(
            "allowed"
            if source_account.export_permission == recall_account.export_permission == "allowed"
            else "denied"
        ),
    )
