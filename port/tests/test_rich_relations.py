"""Rich event/state relation admission remains atomic and leaves v1 wire behavior frozen."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

from storymodel.model import State, digest
from storymodel.relations import (
    RelationProposals,
    RelationResult,
    RelationRow,
    RichRelationProposals,
    RichRelationResult,
    RichRelationRow,
    admit_relations,
    relation_table,
    verify_relations,
)
from storymodel.rich_account import pair_rich_accounts, validate_rich_account
from storymodel.serialization import canonical


def rich_account():
    """Reuse the existing admitted-rich fixture, adding one lawful second state per side."""
    from test_rich_account import fixture

    source, recall = fixture("source", "relation-source"), fixture("recall", "relation-recall")
    paired = pair_rich_accounts(source, recall)
    additions = tuple(
        State(
            side=state.side,
            context=state.context,
            predicate="AA",
            evidence=state.evidence,
            polarity=state.polarity,
            modality=state.modality,
        )
        for state in paired.states
    )
    return validate_rich_account(paired.model_copy(update={"states": (*paired.states, *additions)}))


def quote(account, side):
    document = next(document for document in account.documents if document.side == side)
    return {
        "document": document.id,
        "span": {"start": 0, "end": 2},
        "text": document.text[:2],
    }


def raw(account, relations, *, status="completed"):
    return canonical(
        {
            "schema_version": "storymodel.relation-proposals/v2",
            "account_sha256": digest(account.model_dump(mode="json")),
            "status": status,
            "relations": relations,
        }
    )


def proposal(key, kind, source, target, evidence):
    return {
        "key": key,
        "kind": kind,
        "source": source.id,
        "target": target.id,
        "evidence": (evidence,),
    }


def situations(account, side):
    events = [event for event in account.events if event.side == side]
    states = [state for state in account.states if state.side == side]
    return events, states


@pytest.mark.parametrize("side", ("source", "recall"))
@pytest.mark.parametrize("kind", ("part_of", "before", "causes", "references"))
@pytest.mark.parametrize("endpoints", (("event", "state"), ("state", "event"), ("state", "state")))
def test_rich_admits_event_and_state_endpoint_combinations(side, kind, endpoints):
    account = validate_rich_account(rich_account().model_copy(update={"relations": ()}))
    events, states = situations(account, side)
    lookup = {"event": events[0], "state": states[0]}
    if endpoints == ("state", "state"):
        lookup["target-state"] = states[1]
        left, right = lookup["state"], lookup["target-state"]
    else:
        left, right = (lookup[item] for item in endpoints)
    result = admit_relations(
        account, raw(account, (proposal("edge", kind, left, right, quote(account, side)),))
    )
    assert isinstance(result, RichRelationResult)
    assert result.status == "admitted" and set(result.accepted) == {"edge"}
    row = relation_table(result.account)[-1]
    assert isinstance(row, RichRelationRow)
    assert (row.side, row.source_kind, row.target_kind) == (side, *endpoints)
    assert row.kind == kind


def test_rich_preserves_every_collection_and_rejects_bad_union_atomically():
    account = rich_account()
    events, states = situations(account, "source")
    before = {name: getattr(account, name) for name in type(account).model_fields}
    accepted = admit_relations(
        account,
        raw(account, (proposal("edge", "causes", events[0], states[0], quote(account, "source")),)),
    )
    for name, value in before.items():
        if name not in {"relations", "recipe"}:
            assert getattr(accepted.account, name) == value
    forward = proposal("forward", "before", events[0], states[0], quote(account, "source"))
    backward = proposal("backward", "before", states[0], events[0], quote(account, "source"))
    refused = admit_relations(account, raw(account, (forward, backward)))
    assert refused.status == "rejected" and refused.account == account and not refused.accepted
    # A reverse edge alone must also refuse against the edge already in the input graph.
    refused = admit_relations(account, raw(account, (backward,)))
    assert refused.status == "rejected" and refused.account == account and not refused.accepted
    wrong_side = proposal("wrong-side", "before", events[0], states[0], quote(account, "recall"))
    refused = admit_relations(account, raw(account, (wrong_side,)))
    assert refused.status == "rejected" and refused.account == account


def test_rich_cross_context_references_admit_while_other_kinds_refuse_atomically():
    account = rich_account()
    events, _ = situations(account, "source")
    root = next(
        context
        for context in account.contexts
        if context.side == "source" and context.kind == "narrated"
    )
    root_state = State(
        side="source",
        context=root.id,
        predicate="AA",
        evidence=events[0].evidence,
        polarity="unknown",
        modality="unknown",
    )
    account = validate_rich_account(
        account.model_copy(update={"states": (*account.states, root_state)})
    )
    reference = proposal("reference", "references", events[0], root_state, quote(account, "source"))
    assert admit_relations(account, raw(account, (reference,))).status == "admitted"
    rejected = admit_relations(
        account,
        raw(
            account, (proposal("cause", "causes", events[0], root_state, quote(account, "source")),)
        ),
    )
    assert rejected.status == "rejected" and rejected.account == account and not rejected.accepted


def test_version_receipt_and_copy_tampering_refuse():
    account = rich_account()
    events, states = situations(account, "source")
    response = raw(
        account, (proposal("edge", "references", events[0], states[0], quote(account, "source")),)
    )
    result = admit_relations(account, response)
    assert verify_relations(account, response, result) == result
    with pytest.raises(ValueError):
        admit_relations(account, response.replace("proposals/v2", "proposals/v1"))
    from test_relations import account as legacy_account

    with pytest.raises(ValueError):
        admit_relations(legacy_account(), raw(legacy_account(), ()))
    with pytest.raises(ValueError):
        verify_relations(account, response, result.model_copy(update={"unexpected": 1}))
    with pytest.raises(ValueError):
        verify_relations(account, response, result.model_copy(update={"status": "rejected"}))


def test_rich_admission_preserves_nonempty_details_bindings_and_analysis():
    from test_rich_mapping import fixture, response

    from storymodel.analysis import project
    from storymodel.direct_mapping import prepare_mapping, resolve_mapping

    _, account = fixture()
    request = prepare_mapping(account, account.details[0].unit)
    account = resolve_mapping(request, response(request)).account
    assert account.details and account.bindings and account.states and account.circumstances
    state = account.states[0]
    event = next(e for e in account.events if e.side == state.side and e.context == state.context)
    support = state.evidence[0]
    document = next(d for d in account.documents if d.id == support.document)
    span = support.spans[0]
    evidence = {
        "document": document.id,
        "span": span.model_dump(),
        "text": span.slice(document.text),
    }
    result = admit_relations(
        account, raw(account, (proposal("edge", "references", event, state, evidence),))
    )
    assert result.status == "admitted"
    for name in type(account).model_fields:
        if name not in ("relations", "recipe"):
            assert getattr(result.account, name) == getattr(account, name), name
    before, after = project(account), project(result.account)
    assert after.account_sha256 == digest(result.account.model_dump(mode="json"))
    assert after.account_sha256 != before.account_sha256
    assert after.model_dump(exclude={"account_sha256"}) == before.model_dump(
        exclude={"account_sha256"}
    )


def test_rich_example_cli_and_api_agree_and_export_denial_precedes_io(tmp_path, capsys):
    from storymodel.artifacts import publish_json
    from storymodel.cli import main

    output = tmp_path / "example"
    process = subprocess.run(
        [
            sys.executable,
            str(Path(__file__).parents[1] / "examples/rich_relation_workflow.py"),
            str(output),
        ],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert process.returncode == 0, process.stderr
    assert (
        main(
            [
                "admit-relations",
                str(output / "input.json"),
                str(output / "proposals.json"),
                "--output",
                str(output / "cli.json"),
            ]
        )
        == 0
    )
    assert json.loads((output / "cli.json").read_text()) == json.loads(
        (output / "result.json").read_text()
    )
    assert (
        main(
            [
                "relation-table",
                str(output / "account.json"),
                "--output",
                str(output / "cli-table.json"),
            ]
        )
        == 0
    )
    assert json.loads((output / "cli-table.json").read_text()) == json.loads(
        (output / "table.json").read_text()
    )
    denied = rich_account().model_copy(update={"export_permission": "denied"})
    publish_json(tmp_path / "denied.json", denied.model_dump(mode="json"))
    for args in (
        ["admit-relations", str(tmp_path / "denied.json"), str(tmp_path / "missing.json")],
        ["relation-table", str(tmp_path / "denied.json")],
    ):
        assert main([*args, "--output", str(tmp_path / "forbidden.json")]) == 2
        assert "producer denies relation export" in capsys.readouterr().err
        assert not (tmp_path / "forbidden.json").exists()


def test_rich_schema_file_and_legacy_frozen_values():
    schema = json.loads((Path(__file__).parents[1] / "schemas" / "rich-relations.json").read_text())
    assert set(schema) == {"proposals", "result", "row"}
    assert schema["proposals"] == RichRelationProposals.model_json_schema()
    assert schema["result"] == RichRelationResult.model_json_schema()
    assert schema["row"] == RichRelationRow.model_json_schema()

    from test_relations import (
        account,
    )
    from test_relations import (
        proposal as legacy_proposal,
    )
    from test_relations import (
        quote as legacy_quote,
    )
    from test_relations import (
        raw as legacy_raw,
    )

    value = account()
    document = value.documents[0]
    first, second = value.events[:2]
    response = legacy_raw(
        value,
        relations=(legacy_proposal("edge", "before", first, second, legacy_quote(document, 0, 2)),),
    )
    result = admit_relations(value, response)
    assert {
        "proposals": digest(RelationProposals.model_json_schema()),
        "result": digest(RelationResult.model_json_schema()),
        "row": digest(RelationRow.model_json_schema()),
        "admitted": digest(result.model_dump(mode="json")),
        "rows": digest([row.model_dump(mode="json") for row in relation_table(result.account)]),
    } == {
        "proposals": "656bb2459aad5733fc15a365933efad5ce2d22ef73ec0237be56855c339f98f3",
        "result": "0aec8a78570b57d6e740c8e9d61f5b6a0452a8971aee30ec08b797d239a2ddd1",
        "row": "1d91ec99fc356f089af7df6d2acea125b6ef28de5aa12790e411b6a0ecc34209",
        "admitted": "55d67f4c0f58b88874429bf0c98b27bf4d3dd0794bf2bdeea7a45dc9fac2a65a",
        "rows": "3f4b337c2e78cc4e9b8cfcfe9677f94e38c26f795614e77d2ba55bea8a720a02",
    }
