"""V5 context vocabulary remains explicit, checked and wire-compatible with v1."""

import json
import shutil
import subprocess
import sys
from hashlib import sha256
from pathlib import Path

import pytest
from test_attribution import control, packet
from test_identity import annotation, raw
from test_rich_alignment import rich_control

from storymodel import (
    align_account,
    attributed_identity_roles,
    attribution_table,
    pair_rich_accounts,
    resolve_attributed_identity,
    resolve_context_holders,
    upgrade_context_account,
    verify_attributed_identity,
)
from storymodel._admission import ContextProposal, ExtendedContextProposal
from storymodel.artifacts import publish_json, read_supported_account
from storymodel.cli import main
from storymodel.held_identity import AttributedIdentityResult
from storymodel.identity import resolve_text_identity
from storymodel.model import Account, Context, ExtendedContext, Span, State
from storymodel.rich_account import situation_table, validate_rich_account
from storymodel.rich_construction import (
    RichConstructionProposal,
    RichConstructionProposalV2,
    RichConstructionRequest,
    RichConstructionResult,
    RichRowProposalV2,
    compile_rich_construction,
    prepare_rich_construction,
    verify_rich_construction,
)
from storymodel.rich_workflow import read_rich_request, replay_rich, rich_prompt
from storymodel.serialization import canonical, digest
from storymodel.source import SourceRow

PILOT = Path(__file__).resolve().parents[1]
FIXTURE = Path(__file__).parent / "fixtures" / "context-v1"
EXTENDED = ("desire", "memory", "imagination", "hypothetical", "counterfactual")
HOLDERLESS = {"hypothetical", "counterfactual"}


def quote(text, start, end):
    return {"span": {"start": start, "end": end}, "text": Span(start=start, end=end).slice(text)}


def v2_request(side="source"):
    text = "AA BB CC DD EE FF GG HH II JJ"
    return prepare_rich_construction(
        f"v2-{side}",
        side,
        text,
        (SourceRow(key="row", span=Span(start=0, end=len(text))),),
        export_permission="allowed",
        full_contexts=True,
    )


def v2_packet(request):
    text = request.document.text
    starts = (0, 3, 6, 9, 12)
    contexts, states = [], []
    for index, (kind, start) in enumerate(zip(EXTENDED, starts, strict=True)):
        key = f"c{index}"
        contexts.append(
            {
                "key": key,
                "kind": kind,
                "parent": "narrated",
                "cue": quote(text, start, start + 2),
                "scope": quote(text, start, start + 2),
            }
        )
        states.append(
            {
                "key": f"s{index}",
                "context": key,
                "evidence": quote(text, start, start + 2),
                "predicate": quote(text, start, start + 2),
            }
        )
    return {
        "schema_version": "storymodel.rich-construction-proposal/v2",
        "rows": [{"row": "row", "status": "completed", "contexts": contexts, "states": states}],
    }


@pytest.mark.parametrize("side", ("source", "recall"))
def test_v2_construction_roundtrips_all_new_contexts_and_exposes_checked_consumers(side):
    request = v2_request(side)
    raw_packet = canonical(v2_packet(request))
    result = compile_rich_construction(request, raw_packet)
    assert request.schema_version == "storymodel.rich-construction-request/v2"
    assert result.schema_version == "storymodel.rich-construction-result/v2"
    assert result.account.schema_version == "storymodel.account/v5"
    assert {context.kind for context in result.account.contexts} == {"narrated", *EXTENDED}
    assert {row.context for row in situation_table(result.account)} == {
        state.context for state in result.account.states
    }
    assert (
        verify_rich_construction(
            request,
            raw_packet,
            RichConstructionResult.model_validate_json(result.model_dump_json()),
        )
        == result
    )

    annotations = raw(
        result.account,
        (),
        origin="context-v2-control",
    )
    proposals = canonical(
        {
            "schema_version": "storymodel.holder-proposals/v1",
            "account_sha256": digest(result.account.model_dump(mode="json")),
            "origin": "context-v2-control",
            "contexts": [],
        }
    )
    attribution = resolve_context_holders(result.account, annotations, proposals)
    by_kind = {
        context.kind: holder
        for context, holder in zip(
            sorted(result.account.contexts, key=lambda c: c.id), attribution.holders, strict=True
        )
    }
    assert by_kind["narrated"].status == "not-applicable"
    assert {by_kind[kind].status for kind in HOLDERLESS} == {"not-applicable"}
    assert {by_kind[kind].status for kind in set(EXTENDED) - HOLDERLESS} == {"not-attempted"}
    assert attribution_table(result.account, annotations, proposals, attribution)


def _nested_account(kind):
    account, forms = control()
    account = upgrade_context_account(account)
    root, outer = account.contexts
    inner = ExtendedContext(side="source", kind=kind, parent=outer.id, evidence=outer.evidence)
    state = account.states[0].model_copy(update={"id": "", "context": inner.id})
    account = validate_rich_account(
        account.model_copy(update={"contexts": (root, outer, inner), "states": (state,)})
    )
    # The last mention is a first-person control token and occurs only in the inner state.
    forms[-1] = annotation(
        account.entities[-1],
        account.documents[0],
        (6, 8),
        form="pronominal",
        person="first",
        number="singular",
    )
    proposals = packet(account)
    proposals["contexts"][0]["candidates"] = [account.entities[0].id, account.entities[1].id]
    return account, forms, proposals, outer, inner


def test_holderless_inner_scope_uses_nearest_outer_holder_but_memory_gap_blocks_it():
    account, forms, proposals, outer, inner = _nested_account("hypothetical")
    result = resolve_attributed_identity(account, raw(account, forms), canonical(proposals))
    mention = next(item for item in result.mentions if item.mention == account.entities[-1].id)
    outer_holder = next(item for item in result.attribution.holders if item.context == outer.id)
    resolved = next(item for item in result.referents if item.id == mention.referent)
    assert mention.rule == "speech-holder/v1" and resolved.base_referent == outer_holder.referent
    assert (
        next(item for item in result.attribution.holders if item.context == inner.id).status
        == "not-applicable"
    )

    account, forms, proposals, outer, inner = _nested_account("memory")
    blocked = resolve_attributed_identity(account, raw(account, forms), canonical(proposals))
    mention = next(item for item in blocked.mentions if item.mention == account.entities[-1].id)
    assert mention.outcome == "open" and mention.reason == "holder-unresolved"
    assert (
        next(item for item in blocked.attribution.holders if item.context == inner.id).status
        == "not-attempted"
    )


def test_root_first_person_stays_outside_held_context():
    account, forms = control()
    root = account.contexts[0]
    root_state = account.states[0].model_copy(update={"id": "", "context": root.id})
    account = validate_rich_account(account.model_copy(update={"states": (root_state,)}))
    forms[-1] = annotation(
        account.entities[-1],
        account.documents[0],
        (6, 8),
        form="pronominal",
        person="first",
        number="singular",
    )
    identity = resolve_text_identity(account, raw(account, forms))
    last = next(item for item in identity.mentions if item.mention == account.entities[-1].id)
    assert last.outcome == "open" and last.reason == "outside-held-context"


@pytest.mark.parametrize("mutation", ("root", "quote", "cross-context", "scope"))
def test_v2_admission_rejects_invalid_context_claims_without_partial_graph(mutation):
    request = v2_request()
    packet = v2_packet(request)
    row = packet["rows"][0]
    if mutation == "root":
        row["contexts"][0]["key"] = "narrated"
    elif mutation == "quote":
        row["contexts"][0]["cue"]["text"] = "ZZ"
    elif mutation == "cross-context":
        row["states"][0]["context"] = "narrated"
    else:
        row["contexts"][1]["scope"] = quote(request.document.text, 1, 5)
    result = compile_rich_construction(request, canonical(packet))
    assert result.resolutions[0].status == "rejected"
    assert result.account.contexts[0].kind == "narrated"
    assert not result.account.states


def test_v4_refuses_extended_contexts_and_forged_v5_objects_at_public_boundaries():
    request = v2_request()
    result = compile_rich_construction(request, canonical(v2_packet(request)))
    v4 = result.account.model_copy(update={"schema_version": "storymodel.account/v4"})
    with pytest.raises(ValueError, match="extended context"):
        validate_rich_account(v4)
    with pytest.raises(ValueError, match="extended context"):
        validate_rich_account(v4.model_dump_json())
    forged = result.account.model_copy(
        update={
            "states": (result.account.states[0].model_copy(update={"id": "", "side": "recall"}),)
        }
    )
    with pytest.raises(ValueError, match="context on its own account side"):
        situation_table(forged)


def test_context_upgrade_and_pairing_preserve_existing_ids_and_select_v5():
    (
        source,
        _,
    ) = control(side="source")
    (
        recall,
        _,
    ) = control(side="recall")
    source_v5, recall_v5 = upgrade_context_account(source), upgrade_context_account(recall)
    assert source_v5.schema_version == recall_v5.schema_version == "storymodel.account/v5"
    for before, after in ((source, source_v5), (recall, recall_v5)):
        for field in (
            "documents",
            "units",
            "contexts",
            "entities",
            "events",
            "states",
            "relations",
        ):
            assert getattr(before, field) == getattr(after, field)
    paired = pair_rich_accounts(source_v5, recall)
    assert paired.schema_version == "storymodel.account/v5"
    assert {item.id for item in paired.contexts} == {
        item.id for item in (*source_v5.contexts, *recall.contexts)
    }


def test_alignment_reports_v5_contexts_as_never_root_compatible():
    account = rich_control()
    roots = {context.side: context for context in account.contexts if context.kind == "narrated"}
    extended = {
        side: ExtendedContext(
            side=side,
            kind="hypothetical",
            parent=root.id,
            evidence=next(state.evidence for state in account.states if state.side == side),
        )
        for side, root in roots.items()
    }
    states = tuple(
        State.model_validate(
            state.model_dump(exclude={"id"}) | {"context": extended[state.side].id}
        )
        for state in account.states
    )
    paired = validate_rich_account(
        account.model_copy(
            update={
                "schema_version": "storymodel.account/v5",
                "contexts": (*account.contexts, *extended.values()),
                "states": states,
                "details": tuple(
                    detail.model_copy(
                        update={"event": next(s.id for s in states if s.side == "recall")}
                    )
                    for detail in account.details
                ),
            }
        )
    )
    result = align_account(paired)
    assert len(result.situations) == 1
    candidates = result.situations[0].candidates
    assert len(candidates) == 1
    assert candidates[0].source_situation == next(s.id for s in states if s.side == "source")
    assert candidates[0].context == "attributed-unverified"
    assert not candidates[0].eligible


@pytest.mark.parametrize("kind", ("hypothetical", "counterfactual"))
def test_holderless_frames_refuse_holder_proposals(kind):
    account, forms, proposals, _, inner = _nested_account(kind)
    proposals["contexts"][0]["context"] = inner.id
    with pytest.raises(ValueError, match="holder-bearing context"):
        resolve_context_holders(account, raw(account, forms), canonical(proposals))


def test_full_context_example_saved_consumers_and_cli(tmp_path, capsys):
    destination = tmp_path / "example"
    process = subprocess.run(
        [sys.executable, str(PILOT / "examples/context_workflow.py"), str(destination)],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert process.returncode == 0, process.stdout + process.stderr
    for name in ("source", "recall-1", "recall-2"):
        directory = destination / name
        account = read_supported_account(directory / "account.json")
        forms = (directory / "identity-annotations.json").read_text()
        holders = (directory / "holder-proposals.json").read_text()
        saved = AttributedIdentityResult.model_validate_json(
            (directory / "identity.json").read_bytes()
        )
        assert verify_attributed_identity(account, forms, holders, saved) == saved
        assert json.loads((directory / "identity-roles.json").read_text()) == [
            r.model_dump(mode="json")
            for r in attributed_identity_roles(account, forms, holders, saved)
        ]
        kinds = {c.id: c.kind for c in account.contexts}
        named = [h for h in saved.attribution.holders if h.status == "named"]
        assert {kinds[h.context] for h in named} == {"desire", "memory", "imagination", "believed"}
        request = read_rich_request(directory / "request.json")
        assert replay_rich(request, directory / "recording").model_dump(mode="json") == json.loads(
            (directory / "construction.json").read_text()
        )
        output = directory / "cli-identity.json"
        inputs = [
            str(directory / n)
            for n in ("account.json", "identity-annotations.json", "holder-proposals.json")
        ]
        assert main(["resolve-attributed-identity", *inputs, "--output", str(output)]) == 0
        assert json.loads(output.read_text()) == saved.model_dump(mode="json")
        assert main(["verify-attributed-identity", *inputs, str(output)]) == 0
        assert json.loads(capsys.readouterr().out)["verified"] is True

    source = read_supported_account(destination / "source/account.json")
    for number in (1, 2):
        paired = read_supported_account(destination / f"paired-{number}.json")
        assert all(c in paired.contexts for c in source.contexts)
        assert all(s in paired.states for s in source.states)
        assert align_account(paired).model_dump(mode="json") == json.loads(
            (destination / f"alignment-{number}.json").read_text()
        )

    request = read_rich_request(destination / "source/request.json")
    text, rows, output = tmp_path / "text", tmp_path / "rows.json", tmp_path / "request.json"
    text.write_text(request.document.text)
    publish_json(rows, [r.model_dump(mode="json") for r in request.rows])
    assert (
        main(
            [
                "prepare-rich",
                str(text),
                str(rows),
                "--document-key",
                request.document.key,
                "--side",
                "source",
                "--allow-export",
                "--full-contexts",
                "--output",
                str(output),
            ]
        )
        == 0
    )
    assert read_rich_request(output) == request


def test_v1_schemas_prompt_result_and_recording_replay_are_frozen(tmp_path):
    request = read_rich_request(FIXTURE / "request.json")
    expected = RichConstructionResult.model_validate_json((FIXTURE / "result.json").read_bytes())
    assert request.schema_version == "storymodel.rich-construction-request/v1"
    assert (
        sha256(rich_prompt(request).encode()).hexdigest()
        == (FIXTURE / "prompt.sha256").read_text().strip()
    )
    assert (
        digest(Context.model_json_schema())
        == "4888004812ec22117062f0a57c2e87d51ce3cfb71f7795256c849f31f2be7c0a"
    )
    assert (
        digest(ContextProposal.model_json_schema())
        == "041673eb829dfa3b1d62fb7a5626d5454eda1b10b274a6dcef9fc8a9bd639c22"
    )
    assert (
        digest(RichConstructionProposal.model_json_schema())
        == "e40fa9ab674a45298c7bd6806b29f8d6767c7b4dd08b545b70f839914ff75882"
    )
    assert (
        digest(Account.model_json_schema())
        == "c084379adb61cb0ece8dc8b44723bc2d24b2dcd8c6ee2df28b4b177b4dc6d685"
    )
    recording = tmp_path / "recording"
    shutil.copytree(FIXTURE / "recording", recording)
    assert replay_rich(request, recording) == expected


def test_committed_v2_context_schemas_match_public_models():
    assert json.loads((PILOT / "schemas" / "contexts.json").read_text()) == {
        "extended_context": ExtendedContext.model_json_schema(),
        "extended_proposal": ExtendedContextProposal.model_json_schema(),
        "row_v2": RichRowProposalV2.model_json_schema(),
        "proposal_v2": RichConstructionProposalV2.model_json_schema(),
    }


def test_request_reply_result_versions_cannot_be_crossed():
    v1 = prepare_rich_construction(
        "v1", "source", "AA", (SourceRow(key="row", span=Span(start=0, end=2)),)
    )
    v2 = v2_request()
    with pytest.raises(ValueError, match="version and recipe differ"):
        RichConstructionRequest.model_validate(
            v1.model_dump() | {"recipe": "rich-extractive-admission/v2"}
        )
    with pytest.raises(ValueError, match="invalid rich construction proposal envelope"):
        compile_rich_construction(v1, canonical(v2_packet(v2)))
    result = compile_rich_construction(v2, canonical(v2_packet(v2)))
    with pytest.raises(ValueError, match="version, recipe and account differ"):
        RichConstructionResult.model_validate(
            result.model_dump() | {"schema_version": "storymodel.rich-construction-result/v1"}
        )
