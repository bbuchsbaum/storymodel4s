"""Holder derivation controls use abstract tokens and independently declared identity partitions."""

import itertools
import json
import subprocess
import sys
from pathlib import Path

import pytest
from test_identity import annotation, fixture, raw

from storymodel import attribution_table, resolve_context_holders, verify_attribution
from storymodel.artifacts import publish_json, read_supported_account
from storymodel.attribution import (
    AttributedSituation,
    AttributionResult,
    ContextHolder,
    HolderProposal,
    HolderProposals,
)
from storymodel.cli import main
from storymodel.identity import resolve_text_identity
from storymodel.model import Context, Span, State, Support, validate_account
from storymodel.rich_account import pair_rich_accounts, upgrade_account, validate_rich_account
from storymodel.rich_construction import (
    RichConstructionRequest,
    RichConstructionResult,
    verify_rich_construction,
)
from storymodel.serialization import canonical, digest

PILOT = Path(__file__).resolve().parents[1]


def control(labels=("AA", "AA", "CC"), *, side="source", version=4, kind="reported"):
    text = " ".join(labels)
    spans = [(3 * i, 3 * i + 2) for i in range(len(labels))]
    account = fixture(
        ((side, side, text),),
        tuple((side, label, (span,)) for label, span in zip(labels, spans, strict=True)),
        held=(side,),
    )
    if kind != "reported":
        root, old = account.contexts
        held = Context(side=side, kind=kind, parent=root.id, evidence=old.evidence)
        account = validate_account(
            account.model_copy(
                update={
                    "contexts": (root, held),
                    "events": tuple(
                        e.model_copy(update={"id": "", "context": held.id}) for e in account.events
                    ),
                }
            )
        )
    if version == 4:
        account = upgrade_account(account)
        events = account.events
        account = validate_rich_account(
            account.model_copy(
                update={
                    "events": events[:-1],
                    "states": (
                        State(
                            side=side,
                            context=events[-1].context,
                            predicate=events[-1].predicate,
                            evidence=events[-1].evidence,
                            participants=events[-1].participants,
                        ),
                    ),
                }
            )
        )
    forms = [
        annotation(e, account.documents[0], span, entity_type={"kind": "person"})
        for e, span in zip(account.entities, spans, strict=True)
    ]
    return account, forms


def packet(account, indices=(0, 1), *, status="completed", omitted=False):
    held = next(c for c in account.contexts if c.kind != "narrated")
    proposal = {
        "context": held.id,
        "status": status,
        **(
            {
                "candidates": [account.entities[i].id for i in indices],
                "evidence": [e.model_dump(mode="json") for e in held.evidence],
            }
            if status == "completed"
            else {"reason": "explicit processing outcome"}
        ),
    }
    return {
        "schema_version": "storymodel.holder-proposals/v1",
        "account_sha256": digest(account.model_dump(mode="json")),
        "origin": "abstract control",
        "contexts": [] if omitted else [proposal],
    }


def holder(result):
    return next(h for h in result.holders if h.status != "not-applicable")


@pytest.mark.parametrize("side", ("source", "recall"))
@pytest.mark.parametrize("version", (3, 4))
@pytest.mark.parametrize("kind", ("reported", "believed", "intended"))
def test_named_holder_collapses_identity_and_preserves_every_account_occurrence(
    side, version, kind
):
    account, forms = control(side=side, version=version, kind=kind)
    before = account.model_dump_json()
    annotations, proposals = raw(account, forms), canonical(packet(account))
    result = resolve_context_holders(account, annotations, proposals)
    resolved = holder(result)
    assert resolved.status == "named" and resolved.gap is None
    assert len(resolved.candidates) == 2
    identity = resolve_text_identity(account, annotations)
    assert result.identity == identity
    expected_class = next(r.id for r in identity.referents if account.entities[0].id in r.members)
    assert resolved.referent == expected_class
    table = attribution_table(account, annotations, proposals, result)
    assert len(table) == len(account._situations())
    for row, situation in zip(table, account._situations(), strict=True):
        assert row.situation == situation.id
        assert row.context.id == situation.context
        assert row.context.kind == kind
        assert row.participants == situation.participants
        assert row.evidence == situation.evidence
        assert row.holder == resolved
    assert table[-1].kind == ("state" if version == 4 else "event")
    assert (
        verify_attribution(
            account,
            annotations,
            proposals,
            AttributionResult.model_validate_json(result.model_dump_json()),
        )
        == result
    )
    assert account.model_dump_json() == before


def test_complete_small_candidate_space_matches_declared_partition_including_open_candidates():
    # 3 mentions: first two share identity, last has a different identity or remains open.
    # Every subset and every permutation has independently specified expected outcomes.
    account, forms = control()
    for open_last in (False, True):
        annotations = raw(account, forms[:-1] if open_last else forms)
        for size in range(4):
            for subset in itertools.combinations(range(3), size):
                expected = (
                    ("unattributed", "no-candidate")
                    if not subset
                    else ("unattributed", "unresolved-candidate")
                    if open_last and 2 in subset
                    else ("unattributed", "several-candidates")
                    if 2 in subset and len(subset) > 1
                    else ("named", None)
                )
                baseline = None
                for order in itertools.permutations(subset):
                    result = resolve_context_holders(
                        account, annotations, canonical(packet(account, order))
                    )
                    decided = holder(result)
                    assert (decided.status, decided.gap) == expected
                    if baseline is not None:
                        assert decided == baseline
                    baseline = decided


def test_resolved_third_person_holder_carries_antecedent_but_open_competitor_blocks():
    account, forms = control(("AA", "BB", "CC"))
    forms[1] = annotation(
        account.entities[1], account.documents[0], (3, 5), form="pronominal", person="third"
    )
    result = resolve_context_holders(account, raw(account, forms), canonical(packet(account, (1,))))
    assert holder(result).status == "named"
    first = next(m for m in result.identity.mentions if m.mention == account.entities[0].id)
    assert holder(result).referent == first.referent
    mixed = resolve_context_holders(
        account, raw(account, forms[:2]), canonical(packet(account, (1, 2)))
    )
    assert holder(mixed).gap == "unresolved-candidate"
    assert holder(mixed).referent is None


@pytest.mark.parametrize("status", ("not-attempted", "failed", "unsupported"))
def test_processing_outcomes_and_omission_do_not_become_no_candidate(status):
    account, forms = control()
    annotations = raw(account, forms)
    result = resolve_context_holders(
        account, annotations, canonical(packet(account, status=status))
    )
    assert holder(result).status == status
    assert holder(result).reason == "explicit processing outcome"
    assert holder(result).gap is None
    omitted = resolve_context_holders(
        account, annotations, canonical(packet(account, omitted=True))
    )
    assert holder(omitted).status == "not-attempted"
    assert holder(omitted).gap is None
    assert next(h for h in result.holders if h.status == "not-applicable").reason is None


@pytest.mark.parametrize(
    "change,match",
    (
        ("account", "another account"),
        ("context", "holder-bearing"),
        ("root", "holder-bearing"),
        ("duplicate-context", "duplicate context"),
        ("candidate", "context document"),
        ("duplicate-candidate", "duplicate holder"),
        ("evidence", "outside its context"),
        ("unprocessed-evidence", "only a reason"),
        ("missing-evidence", "requires evidence"),
        ("invented-gap", "Extra inputs"),
    ),
)
def test_invalid_attribution_refuses_instead_of_producing_a_holder(change, match):
    account, forms = control()
    data = packet(account)
    proposed = data["contexts"][0]
    if change == "account":
        data["account_sha256"] = "0" * 64
    elif change == "context":
        proposed["context"] = "foreign"
    elif change == "root":
        proposed["context"] = next(c.id for c in account.contexts if c.kind == "narrated")
    elif change == "duplicate-context":
        data["contexts"] *= 2
    elif change == "candidate":
        proposed["candidates"] = ["foreign"]
    elif change == "duplicate-candidate":
        proposed["candidates"] *= 2
    elif change == "evidence":
        proposed["evidence"][0]["spans"][0]["end"] = 999
    elif change == "unprocessed-evidence":
        proposed.update(status="failed", reason="failed")
    elif change == "missing-evidence":
        proposed["evidence"] = []
    else:
        proposed["gap"] = "no-candidate"
    with pytest.raises(ValueError, match=match):
        resolve_context_holders(account, raw(account, forms), canonical(data))


def test_foreign_recall_candidate_cannot_attribute_source_even_in_paired_account():
    source, forms = control()
    recall, _ = control(side="recall")
    account = pair_rich_accounts(source, recall)
    data = packet(account)
    data["contexts"][0]["candidates"] = [recall.entities[0].id]
    with pytest.raises(ValueError, match="context document"):
        resolve_context_holders(account, raw(account, forms), canonical(data))


def test_same_side_other_document_candidate_is_not_a_holder():
    account = fixture(
        (("one", "source", "AA"), ("two", "source", "BB")),
        (("one", "AA", ((0, 2),)), ("two", "BB", ((0, 2),))),
        held=("one",),
    )
    forms = [
        annotation(e, d, (0, 2)) for e, d in zip(account.entities, account.documents, strict=True)
    ]
    data = packet(account, (1,))
    with pytest.raises(ValueError, match="context document"):
        resolve_context_holders(account, raw(account, forms), canonical(data))


@pytest.mark.parametrize("version", (1, 2))
def test_legacy_paired_wire_accounts_keep_their_ids_and_support(version):
    value = fixture(
        (("source", "source", "AA"), ("recall", "recall", "BB")),
        (("source", "AA", ((0, 2),)),),
        held=("source",),
    )
    account = validate_account(
        value.model_copy(update={"schema_version": f"storymodel.account/v{version}"})
    )
    before = account.model_dump_json()
    forms = [annotation(account.entities[0], account.documents[0], (0, 2))]
    annotations, proposals = raw(account, forms), canonical(packet(account, (0,)))
    result = resolve_context_holders(account, annotations, proposals)
    assert holder(result).status == "named"
    assert len(attribution_table(account, annotations, proposals, result)) == 1
    assert account.model_dump_json() == before


def test_changed_input_identity_and_forged_results_are_rejected_at_consumption():
    account, forms = control()
    annotations, proposals = raw(account, forms), canonical(packet(account))
    result = resolve_context_holders(account, annotations, proposals)
    bad = holder(result).model_copy(update={"referent": "invented"})
    invalid = [
        result.model_copy(
            update={
                "holders": tuple(bad if h.context == bad.context else h for h in result.holders)
            }
        ),
        result.model_copy(update={"holders": ()}),
        result.model_copy(update={"export_permission": "allowed"}),
        result.model_copy(update={"identity": result.identity.model_copy(update={"mentions": ()})}),
        result.model_copy(update={"unknown": "discarded if serialized too early"}),
        result.model_copy(
            update={"holders": (holder(result).model_copy(update={"context": True}),)}
        ),
    ]
    for forged in invalid:
        with pytest.raises(ValueError):
            attribution_table(account, annotations, proposals, forged)
    with pytest.raises(ValueError, match="differs from recorded"):
        verify_attribution(account, annotations + "\n", proposals, result)
    with pytest.raises(ValueError, match="differs from recorded"):
        verify_attribution(account, annotations, proposals + "\n", result)
    changed = account.model_copy(update={"export_permission": "allowed"})
    with pytest.raises(ValueError, match="another account"):
        verify_attribution(changed, annotations, proposals, result)


def test_partial_utf16_evidence_cannot_split_surrogate_even_inside_valid_context_support():
    account = fixture(
        (("source", "source", "AA 😀"),), (("source", "AA", ((0, 2),)),), held=("source",)
    )
    forms = [annotation(account.entities[0], account.documents[0], (0, 2))]
    data = packet(account, (0,))
    data["contexts"][0]["evidence"][0]["spans"] = [{"start": 3, "end": 4}]
    with pytest.raises(ValueError, match="surrogate"):
        resolve_context_holders(account, raw(account, forms), canonical(data))


def test_context_support_union_preserves_discontinuous_gaps():
    account, forms = control()
    root, old = account.contexts
    context = Context(
        side="source",
        kind="reported",
        parent=root.id,
        evidence=(
            Support(
                document=account.documents[0].id, spans=(Span(start=0, end=2), Span(start=6, end=8))
            ),
        ),
    )
    account = validate_rich_account(
        account.model_copy(
            update={
                "contexts": (root, context),
                "events": tuple(
                    e.model_copy(update={"id": "", "context": context.id}) for e in account.events
                ),
                "states": tuple(
                    s.model_copy(update={"id": "", "context": context.id}) for s in account.states
                ),
            }
        )
    )
    data = packet(account)
    annotations = raw(account, forms)
    assert holder(resolve_context_holders(account, annotations, canonical(data))).status == "named"
    data["contexts"][0]["evidence"][0]["spans"] = [{"start": 0, "end": 8}]
    with pytest.raises(ValueError, match="outside its context"):
        resolve_context_holders(account, annotations, canonical(data))


def test_construction_example_api_and_all_cli_commands_agree(tmp_path, capsys):
    destination = tmp_path / "example"
    process = subprocess.run(
        [sys.executable, str(PILOT / "examples/attribution_workflow.py"), str(destination)],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert process.returncode == 0, process.stderr
    account = read_supported_account(destination / "account.json")
    annotations = (destination / "identity-annotations.json").read_text()
    proposals = (destination / "holder-proposals.json").read_text()
    result = resolve_context_holders(account, annotations, proposals)
    saved_result = AttributionResult.model_validate_json(
        (destination / "attribution.json").read_bytes()
    )
    assert verify_attribution(account, annotations, proposals, saved_result) == result
    assert json.loads((destination / "situations.json").read_text()) == [
        r.model_dump(mode="json")
        for r in attribution_table(account, annotations, proposals, saved_result)
    ]
    verify_rich_construction(
        RichConstructionRequest.model_validate_json(
            (destination / "construction-request.json").read_bytes()
        ),
        (destination / "construction-proposals.json").read_text(),
        RichConstructionResult.model_validate_json(
            (destination / "construction-result.json").read_bytes()
        ),
    )
    inputs = [
        str(destination / n)
        for n in ("account.json", "identity-annotations.json", "holder-proposals.json")
    ]
    output = destination / "cli.json"
    assert main(["resolve-holders", *inputs, "--output", str(output)]) == 0
    assert json.loads(output.read_text()) == result.model_dump(mode="json")
    assert main(["verify-attribution", *inputs, str(output)]) == 0
    assert json.loads(capsys.readouterr().out)["verified"] is True
    table = destination / "table.json"
    assert main(["attribution-table", *inputs, str(output), "--output", str(table)]) == 0
    assert json.loads(table.read_text()) == [
        r.model_dump(mode="json")
        for r in attribution_table(account, annotations, proposals, result)
    ]


@pytest.mark.parametrize("command", ("resolve-holders", "attribution-table"))
def test_cli_denies_export_before_reading_recordings(tmp_path, capsys, command):
    account, _ = control()
    publish_json(tmp_path / "account.json", account.model_dump(mode="json"))
    args = [command, str(tmp_path / "account.json"), "missing-forms", "missing-proposals"]
    if command == "attribution-table":
        args.append("missing-result")
    assert main([*args, "--output", str(tmp_path / "refused.json")]) == 2
    assert "producer denies attribution export" in capsys.readouterr().err
    assert not (tmp_path / "refused.json").exists()


def test_attribution_schema_drift():
    assert json.loads((PILOT / "schemas/attribution.json").read_text()) == {
        "proposal": HolderProposal.model_json_schema(),
        "proposals": HolderProposals.model_json_schema(),
        "holder": ContextHolder.model_json_schema(),
        "result": AttributionResult.model_json_schema(),
        "situation": AttributedSituation.model_json_schema(),
    }
