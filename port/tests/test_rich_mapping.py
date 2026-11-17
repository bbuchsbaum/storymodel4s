"""Explicit rich mapping envelopes preserve graphs and frozen legacy behavior."""

from hashlib import sha256

import pytest

from storymodel.analysis import Analysis, project
from storymodel.direct_mapping import (
    MappingProposal,
    MappingRequest,
    MappingResult,
    RichMappingRequest,
    RichMappingResult,
    mapping_prompt,
    mapping_request_digest,
    prepare_mapping,
    resolve_mapping,
    validate_mapping_request,
    verify_mapping,
)
from storymodel.direct_workflow import MappingRecording, import_mapping, replay_mapping
from storymodel.extraction import resolve_extraction
from storymodel.extraction_demo import example_proposal, example_request
from storymodel.model import State
from storymodel.rich_account import Circumstance, upgrade_account, validate_rich_account
from storymodel.serialization import canonical, digest, strict_json


def fixture():
    account = resolve_extraction(example_request(), example_proposal()).account
    # Explicit fixture conversion to independent-account v3; no production downgrade/upgrade guess.
    account = account.model_copy(update={"schema_version": "storymodel.account/v3"})
    detail = account.details[0].model_copy(update={"address": "target"})
    account = account.model_copy(update={"details": (detail,)})
    rich = upgrade_account(account)
    event = next(e for e in rich.events if e.id == detail.event)
    state = State(
        side=event.side,
        context=event.context,
        predicate=event.predicate,
        participants=event.participants,
        evidence=event.evidence,
        polarity="unknown",
        modality="unknown",
    )
    circumstance = Circumstance(
        situation=state.id, kind="manner", label="control", evidence=state.evidence
    )
    rich = validate_rich_account(
        rich.model_copy(
            update={
                "states": (state,),
                "circumstances": (circumstance,),
                "details": (detail.model_copy(update={"event": state.id}),),
            }
        )
    )
    return account, rich


def response(request):
    account = request.account
    target = next(u for u in account.units if u.kind == "row")
    doc = next(d for d in account.documents if d.id == target.support.document)
    return canonical(
        {
            "schema_version": "storymodel.mapping-proposal/v1",
            "packet_sha256": strict_json(mapping_prompt(request))["packet_sha256"],
            "details": [
                {
                    "detail": "d0",
                    "status": "attempted",
                    "outcome": "resolved",
                    "reason": "control",
                    "hypotheses": [{"key": "h", "kind": "single", "targets": [target.id]}],
                    "assessments": [
                        {
                            "hypothesis": "h",
                            "status": "completed",
                            "grade": "supported",
                            "reason": "structural control only",
                            "evidence": [
                                {
                                    "document": doc.id,
                                    "span": s.model_dump(),
                                    "text": s.slice(doc.text),
                                }
                                for s in target.support.spans
                            ],
                        }
                    ],
                }
            ],
        }
    )


def test_rich_mapping_preserves_every_collection_and_supports_three_views(tmp_path, monkeypatch):
    legacy, rich = fixture()
    request = prepare_mapping(rich, rich.details[0].unit)
    assert isinstance(request, RichMappingRequest)
    assert mapping_prompt(request) == mapping_prompt(
        prepare_mapping(legacy, legacy.details[0].unit)
    )
    raw = response(request)
    monkeypatch.setattr("socket.socket", lambda *a, **k: pytest.fail("unexpected network"))
    root = tmp_path / "recording"
    import_mapping(
        request,
        MappingRecording(
            request_sha256=mapping_request_digest(request), origin="control", reply=raw
        ),
        root,
    )
    result = replay_mapping(request, root)
    assert isinstance(result, RichMappingResult)
    assert result.schema_version == "storymodel.mapping-result/v2"
    for name in type(rich).model_fields:
        if name not in ("recipe", "bindings"):
            assert getattr(result.account, name) == getattr(rich, name)
    assert (
        verify_mapping(
            request, raw, RichMappingResult.model_validate_json(result.model_dump_json())
        )
        == result
    )
    views = project(result.account)
    target = next(u for u in rich.units if u.kind == "row")
    assert next(r.coverage for r in views.source if r.source_unit == target.id) == "present"
    assert len(views.recall) == len(views.correspondence) == 1
    assert views.account_sha256 == digest(result.account.model_dump(mode="json"))
    assert views.counts.internal == 1
    assert validate_mapping_request(request.model_dump_json()) == request


def test_wrong_envelope_or_dropped_rich_collections_cannot_verify():
    _, rich = fixture()
    request = prepare_mapping(rich, rich.details[0].unit)
    with pytest.raises(ValueError):
        validate_mapping_request(
            request.model_copy(update={"schema_version": "storymodel.mapping-request/v1"})
        )
    raw = response(request)
    result = resolve_mapping(request, raw)
    lost = result.account.model_copy(update={"circumstances": ()})
    with pytest.raises(ValueError, match="differs from recorded"):
        verify_mapping(request, raw, result.model_copy(update={"account": lost}))
    with pytest.raises(ValueError):
        MappingResult.model_validate_json(result.model_dump_json())


def test_pre_v4_mapping_and_analysis_contracts_remain_identical():
    # Captured at c333e0584, before broadening the qualified consumers.
    account = resolve_extraction(example_request(), example_proposal()).account
    request = prepare_mapping(account, account.details[0].unit)
    prompt = mapping_prompt(request)
    raw = canonical(
        {
            "schema_version": "storymodel.mapping-proposal/v1",
            "packet_sha256": strict_json(prompt)["packet_sha256"],
            "details": [],
        }
    )
    actual = {
        name: digest(cls.model_json_schema())
        for name, cls in (
            ("request_schema", MappingRequest),
            ("result_schema", MappingResult),
            ("proposal_schema", MappingProposal),
            ("recording_schema", MappingRecording),
            ("analysis_schema", Analysis),
        )
    }
    actual.update(
        request_digest=mapping_request_digest(request),
        prompt_digest=sha256(prompt.encode()).hexdigest(),
        result_digest=digest(resolve_mapping(request, raw).model_dump(mode="json")),
        analysis_digest=digest(project(account).model_dump(mode="json")),
    )
    assert actual == {
        "request_schema": "749f7e14976a67b0916b891c4e3cc0a53a010b76d765e62204083fea486f6261",
        "result_schema": "f80308921d7f477ed86a516c8756cf3ebcd81c0ffbc22c70aae44afe782847ca",
        "proposal_schema": "c46a493a6a51a2687352727436e221463392785ee769b7f13f502e8b5367d579",
        "recording_schema": "880eca5f33c241dea2cf2341937194c6103e9f52a4f662c8d23069a2b9ea6b5d",
        "analysis_schema": "b085c40b6d371b4b78ded71efceaa32c6c60a8209fe22de0d8ad44798fe14132",
        "request_digest": "9e525650f389ece4e2df5c1de3c8e8c37c28cf33143d0d73698858fdcc3c284c",
        "prompt_digest": "d7856251aa3a7069b9405e27585e1346440132a3e49c0f31a7795dcc3ec3a7fa",
        "result_digest": "d52cf3b415fd3444a8b6e7a57468520dbeacc0f3a7b2698f8ffd7d86684632dd",
        "analysis_digest": "44e715a122cebcdfad1b17a6152f97b8eadf9437f3ca7d5f61cfa22e67e009a9",
    }
