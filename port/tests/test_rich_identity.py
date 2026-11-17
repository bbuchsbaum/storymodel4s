"""State-aware identity preserves occurrence/context boundaries and legacy sidecars."""

import json
import subprocess
import sys
from pathlib import Path

import pytest
from test_identity import annotation, fixture, outcomes, raw

from storymodel.identity import (
    IdentityAnnotations,
    IdentityResult,
    IdentityRoleRow,
    RichIdentityRoleRow,
    identity_role_table,
    resolve_text_identity,
    verify_identity,
)
from storymodel.model import Event, State
from storymodel.rich_account import Circumstance, upgrade_account, validate_rich_account
from storymodel.serialization import digest


def control(side="source", placement="root", person="third"):
    legacy = fixture(
        ((side, side, "AA BB"),),
        ((side, "AA", ((0, 2),)), (side, "BB", ((3, 5),))),
        held=(side,) if placement in ("held", "mixed") else (),
    )
    rich = upgrade_account(legacy)
    states = tuple(
        State(
            side=e.side,
            context=e.context,
            predicate=e.predicate,
            evidence=e.evidence,
            participants=e.participants if placement != "missing" else (),
        )
        for e in rich.events
    )
    events = ()
    if placement == "mixed":
        root = next(c for c in rich.contexts if c.kind == "narrated")
        e = rich.events[1]
        events = (
            Event(
                side=e.side,
                context=root.id,
                predicate=e.predicate,
                evidence=e.evidence,
                participants=e.participants,
            ),
        )
    circumstance = Circumstance(
        situation=states[1].id, kind="manner", label="control", evidence=states[1].evidence
    )
    rich = validate_rich_account(
        rich.model_copy(
            update={"events": events, "states": states, "circumstances": (circumstance,)}
        )
    )
    a, b = rich.entities
    document = rich.documents[0]
    annotations = raw(
        rich,
        (
            annotation(a, document, (0, 2), entity_type={"kind": "person"}),
            annotation(b, document, (3, 5), form="pronominal", person=person),
        ),
    )
    return rich, annotations


@pytest.mark.parametrize("side", ("source", "recall"))
def test_state_only_third_person_identity_and_roles_preserve_every_field(side):
    account, annotations = control(side)
    before = account.model_dump_json()
    result = resolve_text_identity(account, annotations)
    a, b = account.entities
    assert outcomes(result)[b.id].outcome == "resolved"
    assert outcomes(result)[a.id].referent == outcomes(result)[b.id].referent
    assert result.account_sha256 == digest(account.model_dump(mode="json"))
    assert (
        verify_identity(
            account, annotations, IdentityResult.model_validate_json(result.model_dump_json())
        )
        == result
    )
    rows = identity_role_table(account, annotations, result)
    assert [(r.situation, r.kind, r.context, r.mention, r.role, r.evidence) for r in rows] == [
        (s.id, "state", s.context, p.entity, p.role, p.evidence)
        for s in account.states
        for p in s.participants
    ]
    assert account.model_dump_json() == before


@pytest.mark.parametrize("person", ("first", "second"))
@pytest.mark.parametrize(
    "placement,reason",
    (
        ("root", "outside-held-context"),
        ("held", "holder-not-supplied"),
        ("mixed", "ambiguous-context"),
        ("missing", "context-unknown"),
    ),
)
def test_state_placements_distinguish_context_missingness(person, placement, reason):
    account, annotations = control(placement=placement, person=person)
    result = resolve_text_identity(account, annotations)
    decision = outcomes(result)[account.entities[1].id]
    assert decision.outcome == "open" and decision.referent is None
    assert decision.reason == reason
    rows = identity_role_table(account, annotations, result)
    assert len(rows) == sum(len(s.participants) for s in account._situations())
    if placement == "mixed":
        assert [(r.kind, r.context) for r in rows] == [
            ("event" if isinstance(s, Event) else "state", s.context)
            for s in account._situations()
            for _ in s.participants
        ]


def test_full_rich_snapshot_and_forged_objects_are_checked_before_identity_consumption():
    account, annotations = control()
    result = resolve_text_identity(account, annotations)
    changed = validate_rich_account(account.model_copy(update={"circumstances": ()}))
    with pytest.raises(ValueError, match="foreign"):
        verify_identity(changed, annotations, result)
    with pytest.raises(ValueError):
        resolve_text_identity(account.model_copy(update={"unexpected": True}), annotations)
    with pytest.raises(ValueError):
        identity_role_table(account, annotations, result.model_copy(update={"unexpected": True}))
    with pytest.raises(ValueError, match="differs from recorded"):
        identity_role_table(account, annotations, result.model_copy(update={"mentions": ()}))
    state = account.states[1]
    forged = state.model_copy(
        update={"participants": (state.participants[0].model_copy(update={"entity": "foreign"}),)}
    )
    with pytest.raises(ValueError):
        resolve_text_identity(
            account.model_copy(update={"states": (account.states[0], forged)}), annotations
        )


def test_identity_retains_nonempty_detail_and_mapping_collections():
    from test_rich_mapping import fixture as mapped_fixture
    from test_rich_mapping import response

    from storymodel.analysis import project
    from storymodel.direct_mapping import prepare_mapping, resolve_mapping

    _, account = mapped_fixture()
    request = prepare_mapping(account, account.details[0].unit)
    account = resolve_mapping(request, response(request)).account
    assert account.details and account.bindings and account.circumstances
    before = account.model_dump_json()
    views = project(account)
    annotations = raw(account, ())
    result = resolve_text_identity(account, annotations)
    rows = identity_role_table(account, annotations, result)
    assert {r.situation for r in rows} == {s.id for s in account._situations() if s.participants}
    assert all(r.identity.outcome == "open" for r in rows)
    assert account.model_dump_json() == before and project(account) == views


def test_installed_shape_rich_identity_example_cli_and_export_boundary(tmp_path, capsys):
    from storymodel.artifacts import publish_json, read_supported_account
    from storymodel.cli import main

    destination = tmp_path / "example"
    completed = subprocess.run(
        [
            sys.executable,
            str(Path(__file__).parents[1] / "examples/rich_identity_workflow.py"),
            str(destination),
        ],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert completed.returncode == 0, completed.stderr
    assert (
        main(
            [
                "resolve-identity",
                str(destination / "account.json"),
                str(destination / "annotations.json"),
                "--output",
                str(destination / "cli.json"),
            ]
        )
        == 0
    )
    assert (
        main(
            [
                "identity-roles",
                str(destination / "account.json"),
                str(destination / "annotations.json"),
                str(destination / "cli.json"),
                "--output",
                str(destination / "cli-roles.json"),
            ]
        )
        == 0
    )
    assert json.loads((destination / "cli.json").read_text()) == json.loads(
        (destination / "identity.json").read_text()
    )
    assert json.loads((destination / "cli-roles.json").read_text()) == json.loads(
        (destination / "roles.json").read_text()
    )
    account = read_supported_account(destination / "account.json")
    publish_json(
        tmp_path / "denied.json",
        account.model_copy(update={"export_permission": "denied"}).model_dump(mode="json"),
    )
    for command in ("resolve-identity", "identity-roles"):
        args = [command, str(tmp_path / "denied.json"), str(tmp_path / "missing")]
        if command == "identity-roles":
            args.append(str(tmp_path / "missing-result"))
        assert main([*args, "--output", str(tmp_path / "forbidden")]) == 2
        assert "producer denies identity export" in capsys.readouterr().err
        assert not (tmp_path / "forbidden").exists()


def test_rich_role_schema_and_frozen_legacy_hashes():
    schema = json.loads((Path(__file__).parents[1] / "schemas/rich-identity.json").read_text())
    assert schema == {"role": RichIdentityRoleRow.model_json_schema()}
    value = fixture(
        (("source", "source", "AA BB"),), (("source", "AA", ((0, 2),)), ("source", "BB", ((3, 5),)))
    )
    a, b = value.entities
    document = value.documents[0]
    annotations = raw(
        value,
        (
            annotation(a, document, (0, 2), entity_type={"kind": "person"}),
            annotation(b, document, (3, 5), form="pronominal", person="third"),
        ),
    )
    result = resolve_text_identity(value, annotations)
    assert {
        k: digest(v)
        for k, v in {
            "annotations": IdentityAnnotations.model_json_schema(),
            "result_schema": IdentityResult.model_json_schema(),
            "row_schema": IdentityRoleRow.model_json_schema(),
            "result": result.model_dump(mode="json"),
            "rows": [
                r.model_dump(mode="json") for r in identity_role_table(value, annotations, result)
            ],
        }.items()
    } == {
        "annotations": "04ba67cad37e04e120bf74a0612093f3684e108dd96df0026a0988876bd5717c",
        "result_schema": "ccfadf25b2470c03f1ab3d7e3ece7d0df866dbaa258ff71405739592641c993c",
        "row_schema": "9c068d8702cb4a2feb02d3a60969ecf6f09f451a76d955f676ff323852b4d6b3",
        "result": "787be8eee276ce98d3bdcda51438a676cbda101652a8029367cdba2962a5b60f",
        "rows": "41bb4f5514c6441bc4aa7029c696efed2e965be098a3cfff3285f0201a2118bd",
    }
