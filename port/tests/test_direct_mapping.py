"""Unit-local mapping controls use abstract tokens, not narrative or linguistic gold."""

import json

import pytest

from storymodel import make_account, project
from storymodel.direct_mapping import (
    mapping_prompt,
    mapping_request_digest,
    prepare_mapping,
    resolve_mapping,
    verify_mapping,
)
from storymodel.model import (
    Binding,
    Detail,
    Document,
    Inventory,
    Reference,
    Span,
    Support,
    Unit,
    validate_account,
)
from storymodel.serialization import canonical


def fixture(*, prefix="", suffix="", address="target", extra_detail=False):
    source = Document.create("source", "source", "AA BB CC DD")
    recall = Document.create("recall", "recall", prefix + "AA BB" + suffix)
    scene = Unit(kind="scene", support=Support(document=source.id, spans=(Span(start=0, end=11),)))
    rows = tuple(
        Unit(
            kind="row",
            parent=scene.id,
            support=Support(document=source.id, spans=(Span(start=i, end=i + 5),)),
        )
        for i in (0, 6)
    )
    offset = len(prefix.encode("utf-16-le")) // 2
    unit = Unit(
        kind="recall",
        support=Support(document=recall.id, spans=(Span(start=offset, end=offset + 5),)),
    )
    details = [
        Detail(
            unit=unit.id,
            kind="event",
            content="normalized-hidden",
            support=unit.support,
            address=address,
        )
    ]
    if extra_detail:
        details.append(
            Detail(
                unit=unit.id,
                kind="attribute",
                content="second-hidden",
                support=Support(
                    document=recall.id, spans=(Span(start=offset + 3, end=offset + 5),)
                ),
                address=address,
            )
        )
    account = make_account(
        (source, recall),
        units=(scene, *rows, unit),
        details=details,
        inventories=(Inventory(unit=unit.id, status="completed"),),
        export_permission="allowed",
    )
    return prepare_mapping(account, unit.id), rows, scene


def decision(targets, *, kind="single", grade=None, evidence=(), key="d0"):
    return {
        "detail": key,
        "status": "attempted",
        "outcome": "resolved",
        "reason": "control",
        "hypotheses": [{"key": "h0", "kind": kind, "targets": [t.id for t in targets]}],
        "assessments": (
            [
                {
                    "hypothesis": "h0",
                    "status": "completed",
                    "grade": grade,
                    "reason": "control assessment",
                    "evidence": list(evidence),
                }
            ]
            if grade
            else []
        ),
    }


def quote(row, *, text="AA BB"):
    return {
        "document": row.support.document,
        "span": row.support.spans[0].model_dump(),
        "text": text,
    }


def raw(request, *decisions):
    return canonical(
        {
            "schema_version": "storymodel.mapping-proposal/v1",
            "packet_sha256": json.loads(mapping_prompt(request))["packet_sha256"],
            "details": decisions,
        }
    )


def test_graph_free_mapping_separates_reference_fidelity_and_three_views():
    request, rows, _ = fixture()
    pending = resolve_mapping(request, raw(request, decision(rows[:1])))
    assert not pending.account.events and not pending.account.entities
    assert pending.decisions[0].status == "admitted"
    assert pending.account.bindings[0].assessments[0].status == "pending"
    assert {r.source_unit: r.coverage for r in project(pending.account).source}[
        rows[0].id
    ] == "not-assessable"
    response = raw(request, decision(rows[:1], grade="supported", evidence=(quote(rows[0]),)))
    result = resolve_mapping(request, response)
    views = project(result.account)
    assert {r.source_unit: r.coverage for r in views.source} == {
        rows[0].id: "present",
        rows[1].id: "absent",
    }
    assert len(views.recall) == 1 and len(views.correspondence) == 1
    assert result.account.details == request.account.details
    assert result.account.inventories == request.account.inventories
    assert validate_account(result.account.model_dump_json()) == result.account
    assert verify_mapping(request, response, result) == result


def test_prompt_is_invariant_to_neighbor_text_global_offsets_and_session_identity():
    first, _, _ = fixture()
    shifted, _, _ = fixture(prefix="ZZ😀\n", suffix="\nNEIGHBOR-SECRET")
    assert first.unit != shifted.unit
    assert mapping_request_digest(first) != mapping_request_digest(shifted)
    assert mapping_prompt(first) == mapping_prompt(shifted)
    prompt = mapping_prompt(shifted)
    for forbidden in (
        shifted.unit,
        "NEIGHBOR-SECRET",
        "normalized-hidden",
        shifted.account.documents[1].id,
        shifted.account.documents[1].sha256,
    ):
        assert forbidden not in prompt
    data = json.loads(prompt)
    assert data["recall"] == {
        "text": "AA BB",
        "details": [
            {
                "key": "d0",
                "kind": "event",
                "quotes": [{"span": {"start": 0, "end": 5}, "text": "AA BB"}],
            }
        ],
    }
    reversed_account = shifted.account.model_copy(
        update={
            "documents": tuple(reversed(shifted.account.documents)),
            "units": tuple(reversed(shifted.account.units)),
        }
    )
    assert mapping_prompt(prepare_mapping(reversed_account, shifted.unit)) == prompt


@pytest.mark.parametrize("kind", ["joint", "broad", "alternative", "external"])
def test_reference_meanings_are_not_interchangeable(kind):
    request, rows, scene = fixture()
    proposal = decision(
        rows if kind == "joint" else (scene,),
        kind="broad" if kind == "broad" else "joint" if kind == "joint" else "single",
    )
    if kind == "alternative":
        proposal["outcome"] = "alternative"
        proposal["hypotheses"] = [
            {"key": f"h{i}", "kind": "single", "targets": [row.id]} for i, row in enumerate(rows)
        ]
    if kind == "external":
        proposal["hypotheses"] = [{"key": "h0", "kind": "external", "external": "personal"}]
        proposal["assessments"] = [
            {
                "hypothesis": "h0",
                "status": "completed",
                "grade": "external",
                "reason": "explicit destination",
            }
        ]
    result = resolve_mapping(request, raw(request, proposal))
    assert result.decisions[0].status == "admitted"
    binding = result.account.bindings[0]
    if kind == "alternative":
        assert binding.reference.outcome == "alternative"
        assert len(binding.reference.hypotheses) == len(binding.assessments) == 2
    else:
        assert binding.reference.outcome == "resolved"
        assert binding.reference.hypotheses[0].kind == kind
    assert result.account.details[0].address == "target"


def test_conditional_fidelity_stays_with_its_own_alternative():
    request, rows, _ = fixture()
    proposal = decision(rows[:1], grade="supported", evidence=(quote(rows[0]),))
    proposal["outcome"] = "alternative"
    proposal["hypotheses"].append({"key": "h1", "kind": "single", "targets": [rows[1].id]})
    result = resolve_mapping(request, raw(request, proposal))
    assert [a.status for a in result.account.bindings[0].assessments] == ["completed", "pending"]
    assert {r.source_unit: r.coverage for r in project(result.account).source} == {
        rows[0].id: "uncertain",
        rows[1].id: "not-assessable",
    }
    proposal["assessments"][0]["hypothesis"] = "h1"
    refused = resolve_mapping(request, raw(request, proposal))
    assert refused.decisions[0].status == "rejected"
    assert "outside its source hypothesis" in refused.decisions[0].reason


@pytest.mark.parametrize(
    "mutation,reason",
    [
        ("quote", "differs from source"),
        ("target", "source unit"),
        ("broad-row", "scene container"),
        ("severity", "severity"),
        ("external-grade", "external hypothesis"),
        ("duplicate-assessment", "duplicate"),
        ("foreign-hypothesis", "foreign"),
        ("duplicate-hypothesis", "duplicate"),
    ],
)
def test_invalid_decision_is_atomic_and_does_not_poison_sibling(mutation, reason):
    request, rows, _ = fixture(extra_detail=True)
    proposal = decision(rows[:1], grade="supported", evidence=(quote(rows[0]),))
    if mutation == "quote":
        proposal["assessments"][0]["evidence"][0]["text"] = "WRONG"
    elif mutation == "target":
        proposal["hypotheses"][0]["targets"] = [request.unit]
        proposal["assessments"] = []
    elif mutation == "broad-row":
        proposal["hypotheses"][0]["kind"] = "broad"
    elif mutation == "severity":
        proposal["assessments"][0]["grade"] = "gist-detail-error"
    elif mutation == "external-grade":
        proposal["assessments"][0]["grade"] = "external"
    elif mutation == "duplicate-assessment":
        proposal["assessments"] *= 2
    elif mutation == "foreign-hypothesis":
        proposal["assessments"][0]["hypothesis"] = "foreign"
    else:
        proposal["hypotheses"] *= 2
    result = resolve_mapping(request, raw(request, proposal, decision(rows[1:], key="d1")))
    assert [d.status for d in result.decisions] == ["rejected", "admitted"]
    assert reason in result.decisions[0].reason
    assert result.account.bindings[0].reference.status == "failed"
    assert not result.account.bindings[0].assessments
    assert result.account.bindings[1].reference.hypotheses[0].targets == (rows[1].id,)


@pytest.mark.parametrize("status", ["not-attempted", "failed", "search-limited"])
def test_processing_failure_does_not_become_external_or_invented(status):
    request, _, _ = fixture(extra_detail=True)
    result = resolve_mapping(
        request, raw(request, {"detail": "d0", "status": status, "reason": "deliberate control"})
    )
    assert [d.status for d in result.decisions] == [status, "not-attempted"]
    assert not result.account.bindings[0].reference.hypotheses
    assert not result.account.bindings[0].assessments
    assert {r.coverage for r in project(result.account).source} == {"not-assessable"}


def test_recall_reordering_changes_occurrence_ids_but_not_local_decision():
    a, rows, _ = fixture(prefix="ZZ ")
    b, _, _ = fixture(suffix=" ZZ")
    response = raw(a, decision(rows[:1]))
    left, right = resolve_mapping(a, response), resolve_mapping(b, response)
    assert left.account.bindings[0].detail != right.account.bindings[0].detail
    assert left.account.bindings[0].reference == right.account.bindings[0].reference
    assert left.request_sha256 != right.request_sha256


def test_existing_bindings_and_other_interpretation_are_preserved():
    request, rows, _ = fixture(extra_detail=True)
    old = Binding(
        detail=request.account.details[0].id,
        reference=Reference(status="failed", interpretation="referent", reason="previous attempt"),
    )
    account = validate_account(request.account.model_copy(update={"bindings": (old,)}))
    request = prepare_mapping(account, request.unit)
    result = resolve_mapping(request, raw(request, decision(rows[:1])))
    assert result.account.bindings[0] == old
    assert result.account.bindings[1].detail == account.details[1].id
    narrative = prepare_mapping(result.account, request.unit, interpretation="narrative")
    assert len(json.loads(mapping_prompt(narrative))["recall"]["details"]) == 2
    assert (
        resolve_mapping(narrative, raw(narrative)).account.bindings[:2] == result.account.bindings
    )
    with pytest.raises(ValueError, match="unbound detail"):
        prepare_mapping(result.account, request.unit)


def test_packet_framing_and_forged_input_or_result_refuse():
    request, rows, _ = fixture()
    proposal = decision(rows[:1])
    for response in (
        raw(request, proposal, proposal),
        raw(request, {**proposal, "detail": "foreign"}),
        raw(request).replace(json.loads(mapping_prompt(request))["packet_sha256"], "0" * 64),
        "{}",
        '{"schema_version":0,"schema_version":1}',
    ):
        with pytest.raises(ValueError):
            resolve_mapping(request, response)
    forged = request.model_copy(
        update={
            "account": request.account.model_copy(
                update={
                    "details": (request.account.details[0].model_copy(update={"unit": "foreign"}),)
                }
            )
        }
    )
    with pytest.raises(ValueError):
        mapping_prompt(forged)
    response = raw(request, proposal)
    result = resolve_mapping(request, response)
    with pytest.raises(ValueError, match="differs from recorded"):
        verify_mapping(request, response, result.model_copy(update={"decisions": ()}))


@pytest.mark.parametrize(
    "grade",
    [
        "supported",
        "contradicted",
        "gist-detail-error",
        "invented",
        "recaller-inference",
        "unverifiable",
        "misread",
    ],
)
def test_every_source_grade_retains_detail_address_and_conditional_evidence(grade):
    request, rows, _ = fixture(address="unresolved")
    proposal = decision(rows[:1], grade=grade, evidence=(quote(rows[0]),))
    if grade == "gist-detail-error":
        proposal["assessments"][0]["severity"] = "minor"
    result = resolve_mapping(request, raw(request, proposal))
    assert result.decisions[0].status == "admitted"
    assert result.account.details == request.account.details
    assessment = result.account.bindings[0].assessments[0]
    assert assessment.grade == grade and assessment.evidence == (rows[0].support,)
    assert project(result.account).counts.unresolved == 1


def test_discontinuous_hypothesis_support_does_not_admit_evidence_in_gap():
    request, rows, _ = fixture()
    discontinuous = Unit(
        kind="row",
        support=Support(
            document=rows[0].support.document, spans=(Span(start=0, end=2), Span(start=9, end=11))
        ),
    )
    account = validate_account(
        request.account.model_copy(update={"units": (*request.account.units, discontinuous)})
    )
    request = prepare_mapping(account, request.unit)
    valid = decision(
        (discontinuous,),
        grade="supported",
        evidence=(
            {
                "document": discontinuous.support.document,
                "span": {"start": 0, "end": 2},
                "text": "AA",
            },
            {
                "document": discontinuous.support.document,
                "span": {"start": 9, "end": 11},
                "text": "DD",
            },
        ),
    )
    result = resolve_mapping(request, raw(request, valid))
    assert result.decisions[0].status == "admitted"
    assert result.account.bindings[0].assessments[0].evidence == (discontinuous.support,)
    valid["assessments"][0]["evidence"] = [quote(rows[0])]
    assert resolve_mapping(request, raw(request, valid)).decisions[0].status == "rejected"


def test_external_hypothesis_cannot_receive_source_fidelity():
    request, rows, _ = fixture()
    proposal = decision(rows[:1], grade="supported", evidence=(quote(rows[0]),))
    proposal["hypotheses"] = [{"key": "h0", "kind": "external", "external": "personal"}]
    result = resolve_mapping(request, raw(request, proposal))
    assert result.decisions[0].status == "rejected"
    assert "cannot receive source fidelity" in result.decisions[0].reason
