"""Two-pass held identity keeps independent occurrence, processing and grammatical distinctions."""

import json
import subprocess
import sys
from pathlib import Path

import pytest
from test_attribution import control, packet
from test_identity import annotation, fixture, raw

from storymodel import (
    attributed_identity_roles,
    resolve_attributed_identity,
    resolve_context_holders,
    verify_attributed_identity,
)
from storymodel.artifacts import publish_json, read_supported_account
from storymodel.cli import main
from storymodel.held_identity import (
    AttributedIdentityResult,
    AttributedIdentityRole,
    AttributedMentionIdentity,
    AttributedReferent,
)
from storymodel.model import Context, validate_account
from storymodel.rich_account import validate_rich_account
from storymodel.serialization import canonical, digest

PILOT = Path(__file__).resolve().parents[1]


def prepared(*, person="first", number="singular", side="source", version=4, kind="reported"):
    account, forms = control(side=side, version=version, kind=kind)
    forms[-1] = annotation(
        account.entities[-1],
        account.documents[0],
        (6, 8),
        form="pronominal",
        person=person,
        number=number,
    )
    return account, forms, packet(account)


def mentions(result):
    return {m.mention: m for m in result.mentions}


@pytest.mark.parametrize("side", ("source", "recall"))
@pytest.mark.parametrize("version", (3, 4))
@pytest.mark.parametrize("kind", ("reported", "believed", "intended"))
def test_first_singular_extends_holder_class_preserving_original_artifacts_and_roles(
    side, version, kind
):
    account, forms, proposals = prepared(side=side, version=version, kind=kind)
    before = account.model_dump_json()
    annotations, holder_raw = raw(account, forms), canonical(proposals)
    attribution = resolve_context_holders(account, annotations, holder_raw)
    base_snapshot = attribution.model_dump_json()
    result = resolve_attributed_identity(account, annotations, holder_raw, attribution=attribution)
    assert result == resolve_attributed_identity(account, annotations, holder_raw)
    assert result.attribution == attribution
    assert len(result.referents) == 1
    referent = result.referents[0]
    assert set(referent.members) == {e.id for e in account.entities}
    assert referent.base_referent == attribution.identity.referents[0].id
    assert referent.document == account.documents[0].id
    final = mentions(result)[account.entities[-1].id]
    assert final.outcome == "resolved" and final.rule == "speech-holder/v1"
    assert final.reason is None and final.candidates == ()
    assert final.referent == referent.id
    assert len(attribution.identity.referents[0].members) == 2
    assert (
        next(m for m in attribution.identity.mentions if m.mention == final.mention).reason
        == "holder-not-supplied"
    )
    rows = attributed_identity_roles(account, annotations, holder_raw, result)
    for row, situation in zip(rows, account._situations(), strict=True):
        assert row.situation == situation.id
        assert row.context == situation.context
        assert row.evidence == situation.participants[0].evidence
        assert row.identity == mentions(result)[situation.participants[0].entity]
    assert rows[-1].kind == ("state" if version == 4 else "event")
    assert (
        verify_attributed_identity(
            account,
            annotations,
            holder_raw,
            AttributedIdentityResult.model_validate_json(result.model_dump_json()),
        )
        == result
    )
    assert account.model_dump_json() == before
    assert attribution.model_dump_json() == base_snapshot


@pytest.mark.parametrize(
    "person,number,reason",
    (
        ("first", "plural", "speaker-group"),
        ("first", "unknown", "speaker-number-unknown"),
        ("second", "singular", "needs-addressee"),
        ("second", "plural", "needs-addressee"),
        ("second", "unknown", "needs-addressee"),
    ),
)
def test_plural_unknown_and_addressee_keep_holder_candidate_without_claiming_identity(
    person, number, reason
):
    account, forms, proposals = prepared(person=person, number=number)
    result = resolve_attributed_identity(account, raw(account, forms), canonical(proposals))
    final = mentions(result)[account.entities[-1].id]
    assert final.outcome == "open" and final.reason == reason
    assert final.referent is None and final.rule is None
    assert final.candidates == (result.referents[0].id,)
    assert set(result.referents[0].members) == {e.id for e in account.entities[:2]}


@pytest.mark.parametrize("mode", ("no-candidate", "open-candidate", "several", "omitted", "failed"))
def test_holder_gaps_stay_open_and_keep_original_derivation(mode):
    account, forms, proposals = prepared()
    if mode == "no-candidate":
        proposals["contexts"][0]["candidates"] = []
    elif mode == "open-candidate":
        forms = forms[1:]
    elif mode == "several":
        forms[1]["entity_type"] = {"kind": "group"}
    elif mode == "omitted":
        proposals["contexts"] = []
    else:
        proposals = packet(account, status="failed")
    result = resolve_attributed_identity(account, raw(account, forms), canonical(proposals))
    final = mentions(result)[account.entities[-1].id]
    assert final.outcome == "open" and final.reason == "holder-unresolved"
    assert final.referent is None and final.candidates == ()
    h = next(h for h in result.attribution.holders if h.status != "not-applicable")
    expected = {
        "no-candidate": ("unattributed", "no-candidate"),
        "open-candidate": ("unattributed", "unresolved-candidate"),
        "several": ("unattributed", "several-candidates"),
        "omitted": ("not-attempted", None),
        "failed": ("failed", None),
    }
    assert (h.status, h.gap) == expected[mode]


@pytest.mark.parametrize("mode", ("missing", "bad-quote", "unsupported"))
def test_holder_cannot_rescue_missing_or_invalid_form_evidence(mode):
    account, forms, proposals = prepared()
    if mode == "missing":
        forms.pop()
    elif mode == "bad-quote":
        forms[-1]["quote"]["text"] = "wrong"
    else:
        forms[-1] = {
            "mention": account.entities[-1].id,
            "status": "unsupported",
            "reason": "control",
        }
    result = resolve_attributed_identity(account, raw(account, forms), canonical(proposals))
    final = mentions(result)[account.entities[-1].id]
    assert (
        final.reason
        == {
            "missing": "form-not-supplied",
            "bad-quote": "invalid-annotation",
            "unsupported": "annotation-unsupported",
        }[mode]
    )
    assert final.outcome == "open" and final.referent is None


def nested(*, inner_holder=1, multiple=False):
    account, forms, proposals = prepared()
    root, outer = account.contexts
    inner = Context(side="source", kind="reported", parent=outer.id, evidence=outer.evidence)
    state = account.states[0]
    state = state.model_copy(update={"id": "", "context": inner.id})
    account = validate_rich_account(
        account.model_copy(
            update={
                "contexts": (root, outer, inner),
                "states": (state, account.states[0]) if multiple else (state,),
            }
        )
    )
    proposals["account_sha256"] = digest(account.model_dump(mode="json"))
    proposals["contexts"][0]["candidates"] = [account.entities[0].id]
    proposals["contexts"].append(
        {
            "context": inner.id,
            "status": "completed",
            "candidates": [account.entities[inner_holder].id],
            "evidence": [s.model_dump(mode="json") for s in inner.evidence],
        }
    )
    return account, forms, proposals


def test_nested_holder_noninheritance_and_ambiguous_placements():
    account, forms, proposals = nested()
    # Distinguish the two otherwise same-label introducing classes by explicit ontology.
    forms[1]["entity_type"] = {"kind": "group"}
    result = resolve_attributed_identity(account, raw(account, forms), canonical(proposals))
    final = mentions(result)
    assert final[account.entities[2].id].referent == final[account.entities[1].id].referent
    assert final[account.entities[2].id].referent != final[account.entities[0].id].referent
    proposals["contexts"].pop()
    missing = resolve_attributed_identity(account, raw(account, forms), canonical(proposals))
    assert mentions(missing)[account.entities[-1].id].reason == "holder-unresolved"
    ambiguous, forms, proposals = nested(multiple=True)
    # Even agreement on the referent does not justify erasing conflicting placements.
    result = resolve_attributed_identity(ambiguous, raw(ambiguous, forms), canonical(proposals))
    final = mentions(result)[ambiguous.entities[-1].id]
    assert final.reason == "ambiguous-context" and len(final.contexts) == 2


def test_second_pass_never_uses_newly_resolved_first_person_as_another_holder():
    account, forms, proposals = nested()
    forms[1] = annotation(
        account.entities[1],
        account.documents[0],
        (3, 5),
        form="pronominal",
        person="first",
        number="singular",
    )
    result = resolve_attributed_identity(account, raw(account, forms), canonical(proposals))
    final = mentions(result)
    assert final[account.entities[1].id].rule == "speech-holder/v1"
    assert final[account.entities[2].id].reason == "holder-unresolved"
    assert final[account.entities[2].id].referent is None
    assert set(result.referents[0].members) == {e.id for e in account.entities[:2]}
    # Self-reference cannot manufacture a class either.
    self_account, forms, proposals = nested(inner_holder=2)
    self_result = resolve_attributed_identity(
        self_account, raw(self_account, forms), canonical(proposals)
    )
    assert mentions(self_result)[self_account.entities[2].id].reason == "holder-unresolved"


def test_third_person_holder_is_allowed_and_all_candidate_references_use_final_classes():
    account, forms, proposals = prepared()
    forms[1] = annotation(
        account.entities[1],
        account.documents[0],
        (3, 5),
        form="pronominal",
        person="third",
        number="singular",
    )
    proposals["contexts"][0]["candidates"] = [account.entities[1].id]
    result = resolve_attributed_identity(account, raw(account, forms), canonical(proposals))
    assert len(result.referents) == 1 and len(result.referents[0].members) == 3
    assert mentions(result)[account.entities[1].id].rule == "pronoun-unique-antecedent/v1"
    assert mentions(result)[account.entities[2].id].rule == "speech-holder/v1"
    # Ordinary unresolved third-person candidates must also address final class IDs.
    forms[1] = annotation(
        account.entities[1], account.documents[0], (3, 5), entity_type={"kind": "group"}
    )
    forms[2]["person"] = "third"
    proposals["contexts"][0]["candidates"] = [account.entities[0].id]
    result = resolve_attributed_identity(account, raw(account, forms), canonical(proposals))
    assert set(mentions(result)[account.entities[2].id].candidates) == {
        r.id for r in result.referents
    }
    assert mentions(result)[account.entities[2].id].reason == "several-antecedents"


def test_context_in_another_document_cannot_merge_document_local_identity():
    account = fixture(
        (("one", "source", "AA"), ("two", "source", "BB")),
        (("one", "AA", ((0, 2),)), ("two", "BB", ((0, 2),))),
        held=("one",),
    )
    held = next(c for c in account.contexts if c.kind == "reported")
    # Existing accounts permit a same-side context whose cue is in another document.
    event = account.events[1].model_copy(update={"id": "", "context": held.id})
    account = validate_account(account.model_copy(update={"events": (account.events[0], event)}))
    forms = [
        annotation(account.entities[0], account.documents[0], (0, 2)),
        annotation(
            account.entities[1],
            account.documents[1],
            (0, 2),
            form="pronominal",
            person="first",
            number="singular",
        ),
    ]
    result = resolve_attributed_identity(
        account, raw(account, forms), canonical(packet(account, (0,)))
    )
    final = mentions(result)[account.entities[1].id]
    assert final.reason == "holder-document-mismatch" and final.referent is None
    assert result.referents[0].members == (account.entities[0].id,)


def test_ambiguous_candidates_follow_classes_that_grow_in_the_same_pass():
    account = fixture(
        (("source", "source", "AA BB CC DD"),),
        tuple(
            ("source", label, ((3 * i, 3 * i + 2),))
            for i, label in enumerate(("AA", "BB", "CC", "DD"))
        ),
        held=("source",),
    )
    forms = [
        annotation(e, account.documents[0], (3 * i, 3 * i + 2), entity_type={"kind": "person"})
        if i < 2
        else annotation(
            e,
            account.documents[0],
            (3 * i, 3 * i + 2),
            form="pronominal",
            person="first" if i == 2 else "third",
            number="singular",
        )
        for i, e in enumerate(account.entities)
    ]
    result = resolve_attributed_identity(
        account, raw(account, forms), canonical(packet(account, (0,)))
    )
    final = mentions(result)
    grown = next(r for r in result.referents if account.entities[0].id in r.members)
    assert set(grown.members) == {account.entities[0].id, account.entities[2].id}
    assert final[account.entities[2].id].referent == grown.id
    assert set(final[account.entities[3].id].candidates) == {r.id for r in result.referents}
    assert final[account.entities[3].id].reason == "several-antecedents"
    assert not set(final[account.entities[3].id].candidates) & {
        r.id for r in result.attribution.identity.referents
    }


def test_reordered_inputs_and_account_storage_preserve_decisions_after_rebinding():
    account, forms, proposals = prepared()
    first = resolve_attributed_identity(account, raw(account, forms), canonical(proposals))
    account = validate_rich_account(
        account.model_copy(
            update={
                "entities": tuple(reversed(account.entities)),
                "events": tuple(reversed(account.events)),
                "contexts": tuple(reversed(account.contexts)),
            }
        )
    )
    proposals["account_sha256"] = digest(account.model_dump(mode="json"))
    proposals["contexts"][0]["candidates"].reverse()
    second = resolve_attributed_identity(
        account, raw(account, list(reversed(forms))), canonical(proposals)
    )
    assert first.referents == second.referents and first.mentions == second.mentions


def test_forged_final_classes_decisions_or_upstream_attribution_refuse():
    account, forms, proposals = prepared()
    annotations, holders = raw(account, forms), canonical(proposals)
    result = resolve_attributed_identity(account, annotations, holders)
    pronoun = mentions(result)[account.entities[-1].id]
    bad_decision = pronoun.model_copy(update={"rule": "invented"})
    bad_class = result.referents[0].model_copy(update={"members": result.referents[0].members[:-1]})
    for bad in (
        result.model_copy(
            update={
                "mentions": tuple(
                    bad_decision if m.mention == pronoun.mention else m for m in result.mentions
                )
            }
        ),
        result.model_copy(update={"referents": (bad_class,)}),
        result.model_copy(update={"unknown": "discarded if serialized too early"}),
        result.model_copy(update={"mentions": ()}),
        result.model_copy(
            update={
                "attribution": result.attribution.model_copy(
                    update={"export_permission": "allowed"}
                )
            }
        ),
    ):
        with pytest.raises(ValueError):
            attributed_identity_roles(account, annotations, holders, bad)
    with pytest.raises(ValueError, match="differs from recorded"):
        verify_attributed_identity(account, annotations + "\n", holders, result)
    with pytest.raises(ValueError):
        resolve_attributed_identity(
            account,
            annotations,
            holders,
            attribution=result.attribution.model_copy(update={"holders": ()}),
        )


def test_extended_construction_example_saved_api_and_all_cli_operations(tmp_path, capsys):
    destination = tmp_path / "example"
    run = subprocess.run(
        [sys.executable, str(PILOT / "examples/attribution_workflow.py"), str(destination)],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert run.returncode == 0, run.stderr
    account = read_supported_account(destination / "account.json")
    annotations = (destination / "identity-annotations.json").read_text()
    holders = (destination / "holder-proposals.json").read_text()
    saved = AttributedIdentityResult.model_validate_json(
        (destination / "attributed-identity.json").read_bytes()
    )
    assert verify_attributed_identity(account, annotations, holders, saved) == saved
    rows = attributed_identity_roles(account, annotations, holders, saved)
    assert json.loads((destination / "attributed-identity-roles.json").read_text()) == [
        r.model_dump(mode="json") for r in rows
    ]
    inputs = [
        str(destination / n)
        for n in ("account.json", "identity-annotations.json", "holder-proposals.json")
    ]
    output = destination / "cli-identity.json"
    assert main(["resolve-attributed-identity", *inputs, "--output", str(output)]) == 0
    assert json.loads(output.read_text()) == saved.model_dump(mode="json")
    assert main(["verify-attributed-identity", *inputs, str(output)]) == 0
    assert json.loads(capsys.readouterr().out)["verified"] is True
    role_output = destination / "cli-roles.json"
    assert (
        main(["attributed-identity-roles", *inputs, str(output), "--output", str(role_output)]) == 0
    )
    assert json.loads(role_output.read_text()) == [r.model_dump(mode="json") for r in rows]


@pytest.mark.parametrize("command", ("resolve-attributed-identity", "attributed-identity-roles"))
def test_cli_export_denial_precedes_recording_reads(tmp_path, capsys, command):
    account, _, _ = prepared()
    publish_json(tmp_path / "account.json", account.model_dump(mode="json"))
    args = [command, str(tmp_path / "account.json"), "missing-annotations", "missing-holders"]
    if command == "attributed-identity-roles":
        args.append("missing-result")
    assert main([*args, "--output", str(tmp_path / "refused.json")]) == 2
    assert "producer denies attribution export" in capsys.readouterr().err
    assert not (tmp_path / "refused.json").exists()


def test_held_identity_schema_drift():
    assert json.loads((PILOT / "schemas/held-identity.json").read_text()) == {
        "referent": AttributedReferent.model_json_schema(),
        "mention": AttributedMentionIdentity.model_json_schema(),
        "result": AttributedIdentityResult.model_json_schema(),
        "role": AttributedIdentityRole.model_json_schema(),
    }
