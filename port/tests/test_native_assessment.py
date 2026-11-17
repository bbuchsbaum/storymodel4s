"""Native observation grading preserves exact evidence and conditional reference semantics."""

import json

import pytest
from test_attribution import control
from test_playback import support
from test_playback_construction import fixture as playback_fixture

from storymodel.analysis import project
from storymodel.direct_mapping import (
    mapping_prompt,
    mapping_request_digest,
    prepare_mapping,
    resolve_mapping,
    verify_mapping,
)
from storymodel.direct_workflow import MappingRecording, import_mapping, replay_mapping
from storymodel.fidelity import (
    fidelity_prompt,
    fidelity_request_digest,
    prepare_fidelity,
    resolve_fidelity,
    verify_fidelity,
)
from storymodel.fidelity_workflow import FidelityRecording, import_fidelity, replay_fidelity
from storymodel.model import Detail, Inventory, Span, Support, Unit
from storymodel.playback_construction import compile_playback
from storymodel.rich_account import (
    pair_rich_accounts,
    upgrade_assessment_account,
    validate_rich_account,
)
from storymodel.serialization import canonical


def fixture(label="CC"):
    req, packet = playback_fixture()
    source = compile_playback(req, canonical(packet)).account
    recall, _ = control(labels=("AA", "AA", label), side="recall")
    doc = recall.documents[0]
    unit = Unit(
        kind="recall", support=Support(document=doc.id, spans=(Span(start=0, end=len(doc.text)),))
    )
    detail = Detail(
        unit=unit.id, kind="event", content="AA", support=unit.support, address="target"
    )
    recall = validate_rich_account(
        recall.model_copy(
            update={
                "export_permission": "allowed",
                "units": (unit,),
                "details": (detail,),
                "inventories": (Inventory(unit=unit.id, status="completed"),),
            }
        )
    )
    paired = upgrade_assessment_account(pair_rich_accounts(source, recall))
    return source, recall, paired


def mapping_reply(request, *, grade=None, citation=None):
    row = next(u for u in request.account.units if u.kind == "row")
    return canonical(
        {
            "schema_version": "storymodel.mapping-proposal/v2",
            "packet_sha256": json.loads(mapping_prompt(request))["packet_sha256"],
            "details": [
                {
                    "detail": "d0",
                    "status": "attempted",
                    "outcome": "resolved",
                    "reason": "control",
                    "hypotheses": [{"key": "h0", "kind": "single", "targets": [row.id]}],
                    "assessments": []
                    if grade is None
                    else [
                        {
                            "hypothesis": "h0",
                            "status": "completed",
                            "grade": grade,
                            "reason": "supplied observation control",
                            "playback_evidence": [citation],
                        }
                    ],
                }
            ],
        }
    )


def citation(account, *, state=False):
    situation = next(s for s in (account.states if state else account.events) if s.side == "source")
    return {"situation": situation.id, "support": situation.evidence[0].model_dump(mode="json")}


def fidelity_reply(request, evidence, *, grade="supported"):
    return canonical(
        {
            "schema_version": "storymodel.fidelity-proposal/v3",
            "packet_sha256": json.loads(fidelity_prompt(request))["packet_sha256"],
            "assessments": [
                {
                    "hypothesis": "a0",
                    "status": "completed",
                    "grade": grade,
                    "reason": "supplied observation control",
                    "playback_evidence": evidence,
                }
            ],
        }
    )


def mapped(account):
    request = prepare_mapping(account, account.details[0].unit)
    return resolve_mapping(request, mapping_reply(request))


def test_native_recorded_mapping_fidelity_and_three_views_reuse_unchanged_source(tmp_path):
    originals = []
    for index, label in enumerate(("CC", "DD")):
        source, recall, account = fixture(label)
        originals.append(source.model_dump_json())
        request = prepare_mapping(account, account.details[0].unit)
        raw = mapping_reply(request)
        root = tmp_path / f"mapping-{index}"
        import_mapping(
            request,
            MappingRecording(
                request_sha256=mapping_request_digest(request), origin="control", reply=raw
            ),
            root,
        )
        result = replay_mapping(request, root)
        assert verify_mapping(request, raw, result) == result
        assert result.decisions[0].status == "admitted"
        assert {r.coverage for r in project(result.account).source} == {"not-assessable"}
        grading = prepare_fidelity(result.account, result.account.details[0].unit)
        reply = fidelity_reply(grading, [citation(account)])
        dest = tmp_path / f"fidelity-{index}"
        import_fidelity(
            grading,
            FidelityRecording(
                request_sha256=fidelity_request_digest(grading), origin="control", reply=reply
            ),
            dest,
        )
        assessed = replay_fidelity(grading, dest)
        assert verify_fidelity(grading, reply, assessed) == assessed
        assert assessed.decisions[0].status == "admitted"
        assert {r.coverage for r in project(assessed.account).source} == {"present"}
        for field in type(account).model_fields:
            if field not in ("recipe", "bindings"):
                assert getattr(assessed.account, field) == getattr(account, field)
        assert assessed.account.bindings[0].reference == result.account.bindings[0].reference
        assert source.media_sources[0].sha256 in assessed.account.recipe.input_sha256
        assert source.media_sources[0].sha256 in result.account.recipe.input_sha256
        with pytest.raises(ValueError, match="absent or pending"):
            prepare_fidelity(assessed.account, assessed.account.details[0].unit)
        assert source.model_dump_json() == originals[index]
    assert originals[0] == originals[1]


def test_native_prompt_supplies_source_semantics_and_context_without_recall_graph_or_grades():
    source, recall, account = fixture()
    prompt = json.loads(mapping_prompt(prepare_mapping(account, account.details[0].unit)))
    view = prompt["source_observations"]
    assert view["media_sources"] == [source.media_sources[0].model_dump(mode="json")]
    assert {s["id"] for s in view["situations"]} == {s.id for s in (*source.events, *source.states)}
    assert "believed" in {c["kind"] for c in view["contexts"]}
    assert "source_documents" in prompt and not prompt["source_documents"]
    encoded = canonical(view)
    assert all(s.id not in encoded for s in recall.events)
    assert "previous" not in view and "bindings" not in view
    assert account.details[0].id not in canonical(prompt)


@pytest.mark.parametrize("state", [False, True])
def test_native_direct_grade_accepts_exact_intervals_or_explicit_points(state):
    _, _, account = fixture()
    request = prepare_mapping(account, account.details[0].unit)
    raw = mapping_reply(request, grade="supported", citation=citation(account, state=state))
    result = resolve_mapping(request, raw)
    assert result.decisions[0].status == "admitted"
    evidence = result.account.bindings[0].assessments[0].evidence[0]
    if state:
        assert evidence.points == ("22",) and not evidence.intervals
    assert {r.coverage for r in project(result.account).source} == {"present"}


@pytest.mark.parametrize(
    "case", ["gap", "outside-situation", "foreign-source", "point-to-duration", "foreign-situation"]
)
def test_native_fidelity_refuses_forged_citations_without_changing_pending_slot(case):
    source, recall, account = fixture()
    result = mapped(account)
    request = prepare_fidelity(result.account, result.account.details[0].unit)
    cite = citation(account, state=case == "point-to-duration")
    if case == "gap":
        cite["support"] = support(source.media_sources[0], [(4, 23)]).model_dump(mode="json")
    elif case == "outside-situation":
        cite["support"] = support(source.media_sources[0], [(6, 7)]).model_dump(mode="json")
    elif case == "point-to-duration":
        cite["support"] = support(source.media_sources[0], [(22, 23)]).model_dump(mode="json")
    elif case == "foreign-source":
        cite["support"]["source"] = "another-edition"
    else:
        cite["situation"] = recall.events[0].id
    assessed = resolve_fidelity(request, fidelity_reply(request, [cite]))
    assert assessed.decisions[0].status == "rejected"
    assert assessed.account.bindings == result.account.bindings
    assert {r.coverage for r in project(assessed.account).source} == {"not-assessable"}


def test_native_proposal_versions_and_missing_work_cannot_silently_change_information_condition():
    _, _, account = fixture()
    request = prepare_mapping(account, account.details[0].unit)
    raw = json.loads(mapping_reply(request))
    raw["schema_version"] = "storymodel.mapping-proposal/v1"
    with pytest.raises(ValueError, match="schema"):
        resolve_mapping(request, canonical(raw))
    result = mapped(account)
    grading = prepare_fidelity(result.account, result.account.details[0].unit)
    reply = json.loads(fidelity_reply(grading, []))
    reply["assessments"] = []
    receipt = resolve_fidelity(grading, canonical(reply))
    assert receipt.decisions[0].status == "not-attempted"
    assert receipt.account.bindings == result.account.bindings


def test_native_joint_hypothesis_uses_union_but_cannot_borrow_another_targets_evidence():
    source, _, account = fixture()
    media = source.media_sources[0]
    rows = (
        Unit(kind="row", support=support(media, [(3, 5)], [4])),
        Unit(kind="row", support=support(media, [(22, 25)])),
    )
    account = validate_rich_account(
        account.model_copy(
            update={"units": (*rows, *(u for u in account.units if u.kind == "recall"))}
        )
    )
    request = prepare_mapping(account, account.details[0].unit)
    packet = json.loads(mapping_reply(request, grade="supported", citation=citation(account)))
    hypothesis = packet["details"][0]["hypotheses"][0]
    hypothesis.update(kind="joint", targets=[r.id for r in rows])
    result = resolve_mapping(request, canonical(packet))
    assert result.decisions[0].status == "admitted"
    assert {r.coverage for r in project(result.account).source} == {"present"}
    hypothesis.update(kind="single", targets=[rows[0].id])
    refused = resolve_mapping(request, canonical(packet))
    assert refused.decisions[0].status == "rejected"
    assert "outside its source hypothesis" in refused.decisions[0].reason


def test_native_consumer_refuses_hidden_recall_media_even_with_selected_text_unit():
    from test_playback import source as media_source

    from storymodel.model import Recipe

    _, _, account = fixture()
    hidden = media_source(key="hidden", side="recall")
    account = validate_rich_account(
        account.model_copy(
            update={
                "media_sources": (*account.media_sources, hidden),
                "recipe": Recipe(
                    name="control", input_sha256=(*account.recipe.input_sha256, hidden.sha256)
                ),
            }
        )
    )
    for call in (
        lambda: project(account),
        lambda: prepare_mapping(account, account.details[0].unit),
    ):
        with pytest.raises(ValueError, match="source-only media"):
            call()
