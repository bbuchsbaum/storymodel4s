"""Conditional fidelity adds grades without reopening direct mapping decisions."""

import json

import pytest
from test_direct_mapping import decision, fixture, quote, raw

from storymodel import project
from storymodel.direct_mapping import resolve_mapping
from storymodel.fidelity import (
    FidelityResult,
    fidelity_prompt,
    fidelity_request_digest,
    prepare_fidelity,
    resolve_fidelity,
    verify_fidelity,
)
from storymodel.model import Assessment, Binding
from storymodel.rich_account import upgrade_account
from storymodel.serialization import canonical


def mapped(*, alternative=False, extra_detail=False):
    request, rows, _ = fixture(extra_detail=extra_detail)
    proposal = decision(rows[:1])
    if alternative:
        proposal["outcome"] = "alternative"
        proposal["hypotheses"].append({"key": "h1", "kind": "single", "targets": [rows[1].id]})
    return resolve_mapping(request, raw(request, proposal)), rows


def reply(request, *assessments):
    return canonical(
        {
            "schema_version": "storymodel.fidelity-proposal/v1",
            "packet_sha256": json.loads(fidelity_prompt(request))["packet_sha256"],
            "assessments": list(assessments),
        }
    )


def assessment(key, *, status="completed", grade="supported", evidence=(), **extra):
    row = {"hypothesis": key, "status": status, "reason": "controlled assessment", **extra}
    if status == "completed":
        row.update(grade=grade, evidence=list(evidence))
        if grade == "gist-detail-error":
            row["severity"] = "minor"
    return row


def test_fidelity_only_changes_pending_grade_and_projected_coverage():
    mapping, rows = mapped()
    request = prepare_fidelity(mapping.account, mapping.account.details[0].unit)
    baseline = project(mapping.account)
    result = resolve_fidelity(request, reply(request, assessment("a0", evidence=(quote(rows[0]),))))

    assert [d.status for d in result.decisions] == ["admitted"]
    assert result.account.details == mapping.account.details
    assert result.account.inventories == mapping.account.inventories
    assert result.account.bindings[0].reference == mapping.account.bindings[0].reference
    assert result.account.bindings[0].assessments[0].grade == "supported"
    assert {r.source_unit: r.coverage for r in baseline.source}[rows[0].id] == "not-assessable"
    assert {r.source_unit: r.coverage for r in project(result.account).source}[rows[0].id] == (
        "present"
    )


def test_alternatives_and_interpretations_remain_conditional_and_independent():
    mapping, rows = mapped(alternative=True)
    detail = mapping.account.details[0]
    original = mapping.account.bindings[0]
    referent = original.model_copy(
        update={
            "assessments": (
                Assessment(
                    hypothesis=original.reference.hypotheses[0].id,
                    status="refused",
                    reason="prior refusal",
                ),
                original.assessments[1],
            )
        }
    )
    narrative = Binding(
        detail=detail.id,
        reference=referent.reference.model_copy(update={"interpretation": "narrative"}),
        assessments=(
            Assessment(
                hypothesis=referent.reference.hypotheses[0].id,
                status="completed",
                grade="supported",
                reason="old completed grade",
                evidence=(rows[0].support,),
            ),
            Assessment(
                hypothesis=referent.reference.hypotheses[1].id,
                status="unavailable",
                reason="old unavailable grade",
            ),
        ),
    )
    account = mapping.account.model_copy(update={"bindings": (referent, narrative)})
    request = prepare_fidelity(account, detail.unit)
    result = resolve_fidelity(
        request, reply(request, assessment("a0", evidence=(quote(rows[1], text="CC DD"),)))
    )

    assert [d.local_key for d in result.decisions] == ["a0"]
    assert [d.status for d in result.decisions] == ["admitted"]
    assert result.account.bindings[0].assessments[0] == referent.assessments[0]
    assert result.account.bindings[0].assessments[1].hypothesis == (
        referent.reference.hypotheses[1].id
    )
    assert result.account.bindings[1] == narrative


@pytest.mark.parametrize(
    "status,grade",
    [
        ("pending", None),
        ("unavailable", None),
        ("refused", None),
        ("completed", "supported"),
        ("completed", "gist-detail-error"),
        ("completed", "contradicted"),
        ("completed", "recaller-inference"),
        ("completed", "external"),
        ("completed", "invented"),
        ("completed", "unverifiable"),
        ("completed", "misread"),
    ],
)
def test_every_fidelity_status_and_grade_preserves_the_fixed_reference(status, grade):
    if grade == "external":
        mapping_request, rows, _ = fixture()
        proposal = decision(rows[:1])
        proposal["hypotheses"] = [{"key": "h0", "kind": "external", "external": "personal"}]
        proposal["assessments"] = []
        mapping = resolve_mapping(mapping_request, raw(mapping_request, proposal))
    else:
        mapping, rows = mapped()
    request = prepare_fidelity(mapping.account, mapping.account.details[0].unit)
    kwargs = (
        {}
        if grade is None or grade == "external"
        else {"grade": grade, "evidence": (quote(rows[0]),)}
    )
    if grade == "external":
        kwargs["grade"] = grade
    result = resolve_fidelity(request, reply(request, assessment("a0", status=status, **kwargs)))

    assert result.decisions[0].status == "admitted"
    stored = result.account.bindings[0].assessments[0]
    assert (stored.status, stored.grade) == (status, grade)
    assert result.account.bindings[0].reference == mapping.account.bindings[0].reference


def test_invalid_slot_isolated_and_utf16_foreign_quote_is_rejected():
    mapping, rows = mapped(alternative=True)
    request = prepare_fidelity(mapping.account, mapping.account.details[0].unit)
    invalid = assessment("a0", evidence=(quote(rows[0]),))
    invalid["evidence"][0]["span"] = {"start": 1, "end": 3}
    valid = assessment("a1", evidence=(quote(rows[1], text="CC DD"),))
    result = resolve_fidelity(request, reply(request, invalid, valid))

    assert [d.status for d in result.decisions] == ["rejected", "admitted"]
    assert "differs from source" in result.decisions[0].reason
    assert [a.hypothesis for a in result.account.bindings[0].assessments] == [
        h.id for h in mapping.account.bindings[0].reference.hypotheses
    ]
    assert [a.status for a in result.account.bindings[0].assessments] == ["pending", "completed"]


def test_prompt_is_blind_to_private_state_and_stable_when_non_bmp_offsets_shift():
    first, _ = mapped(alternative=True)
    shifted_request, shifted_rows, _ = fixture(prefix="ZZ😀\n", suffix="\nNEIGHBOR-SECRET")
    shifted_proposal = decision(shifted_rows[:1])
    shifted_proposal["outcome"] = "alternative"
    shifted_proposal["hypotheses"].append(
        {"key": "h1", "kind": "single", "targets": [shifted_rows[1].id]}
    )
    shifted = resolve_mapping(shifted_request, raw(shifted_request, shifted_proposal))

    def with_private_prior(result):
        binding = result.account.bindings[0].model_copy(
            update={
                "assessments": (
                    Assessment(
                        hypothesis=result.account.bindings[0].reference.hypotheses[0].id,
                        status="refused",
                        reason="PRIVATE-OLD-GRADE",
                    ),
                )
            }
        )
        return result.model_copy(
            update={"account": result.account.model_copy(update={"bindings": (binding,)})}
        )

    first, shifted = with_private_prior(first), with_private_prior(shifted)
    left, right = (
        prepare_fidelity(first.account, first.account.details[0].unit),
        prepare_fidelity(shifted.account, shifted.account.details[0].unit),
    )

    assert fidelity_prompt(left) == fidelity_prompt(right)
    assert fidelity_request_digest(left) != fidelity_request_digest(right)
    prompt = fidelity_prompt(right)
    assert "PRIVATE-OLD-GRADE" not in prompt and "NEIGHBOR-SECRET" not in prompt
    payload = json.loads(prompt)
    assert payload["recall"] == {"text": "AA BB"}
    assert payload["questions"][0]["claim"] == "normalized-hidden"
    assert payload["questions"][0]["quotes"] == [{"span": {"start": 0, "end": 5}, "text": "AA BB"}]


def test_forged_request_or_receipt_refuses_and_rich_account_is_preserved():
    mapping, rows = mapped()
    rich = upgrade_account(mapping.account)
    request = prepare_fidelity(rich, rich.details[0].unit)
    response = reply(request, assessment("a0", evidence=(quote(rows[0]),)))
    result = resolve_fidelity(request, response)

    assert type(result.account) is type(rich)
    for field in type(rich).model_fields:
        if field not in ("bindings", "recipe"):
            assert getattr(result.account, field) == getattr(rich, field)
    with pytest.raises(ValueError):
        fidelity_prompt(request.model_copy(update={"unit": "foreign"}))
    with pytest.raises(ValueError, match="differs from recorded"):
        verify_fidelity(request, response, result.model_copy(update={"decisions": ()}))
    with pytest.raises(ValueError):
        FidelityResult.model_validate_json(
            result.model_dump_json().replace("fidelity-result/v1", "x")
        )


def test_missing_work_is_preserved_and_packet_identity_cannot_select_foreign_slots():
    mapping, rows = mapped()
    request = prepare_fidelity(mapping.account, mapping.account.details[0].unit)
    missing = resolve_fidelity(request, reply(request))
    assert missing.decisions[0].status == "not-attempted"
    assert missing.account.bindings == mapping.account.bindings
    valid = assessment("a0", evidence=(quote(rows[0]),))
    packet = json.loads(reply(request, valid))
    for invalid in (
        {**packet, "packet_sha256": "0" * 64},
        {**packet, "schema_version": "wrong"},
        {**packet, "extra": True},
        {**packet, "assessments": [valid, valid]},
        {**packet, "assessments": [{**valid, "hypothesis": "a99"}]},
        {**packet, "assessments": [True]},
        {**packet, "assessments": {}},
    ):
        with pytest.raises(ValueError):
            resolve_fidelity(request, canonical(invalid))
    result = resolve_fidelity(request, reply(request, valid))
    with pytest.raises(ValueError, match="absent or pending"):
        prepare_fidelity(result.account, request.unit)
    assert verify_fidelity(request, reply(request, valid), result) == result
    with pytest.raises(ValueError, match="differs from recorded"):
        verify_fidelity(request, reply(request, valid) + " ", result)
    with pytest.raises(ValueError):
        verify_fidelity(request, reply(request, valid), result.model_copy(update={"extra": True}))
    with pytest.raises(ValueError):
        fidelity_prompt(
            request.model_copy(
                update={"account": request.account.model_copy(update={"export_permission": True})}
            )
        )


@pytest.mark.parametrize("kind", ["joint", "broad", "external"])
def test_separate_grades_preserve_joint_broad_and_external_meanings(kind):
    mapping_request, rows, scene = fixture()
    proposal = decision(rows if kind == "joint" else (scene,), kind=kind)
    if kind == "external":
        proposal["hypotheses"] = [{"key": "h0", "kind": "external", "external": "personal"}]
    mapped_result = resolve_mapping(mapping_request, raw(mapping_request, proposal))
    request = prepare_fidelity(mapped_result.account, mapping_request.unit)
    result = resolve_fidelity(
        request,
        reply(
            request,
            assessment(
                "a0",
                grade="external" if kind == "external" else "supported",
                evidence=()
                if kind == "external"
                else tuple(
                    quote(row, text="AA BB" if i == 0 else "CC DD") for i, row in enumerate(rows)
                ),
            ),
        ),
    )
    assert result.decisions[0].status == "admitted"
    assert result.account.bindings[0].reference == mapped_result.account.bindings[0].reference
    if kind == "joint":
        assert {row.coverage for row in project(result.account).source} == {"present"}
    elif kind == "broad":
        assert {row.coverage for row in project(result.account).source} == {"uncertain"}
    else:
        invalid = resolve_fidelity(
            request, reply(request, assessment("a0", evidence=(quote(rows[0]),)))
        )
        assert invalid.decisions[0].status == "rejected"
        assert invalid.account.bindings == mapped_result.account.bindings


def test_source_quotes_check_original_utf16_boundaries_and_hypothesis_ownership():
    from storymodel import make_account, prepare_mapping
    from storymodel.model import Detail, Document, Span, Support, Unit

    source = Document.create("unicode-source", "source", "😀 AA")
    recall = Document.create("unicode-recall", "recall", "BB")
    row = Unit(kind="row", support=Support(document=source.id, spans=(Span(start=0, end=5),)))
    unit = Unit(kind="recall", support=Support(document=recall.id, spans=(Span(start=0, end=2),)))
    detail = Detail(
        unit=unit.id, kind="event", content="BB", support=unit.support, address="target"
    )
    account = make_account((source, recall), units=(row, unit), details=(detail,))
    mapping_request = prepare_mapping(account, unit.id)
    mapped_result = resolve_mapping(mapping_request, raw(mapping_request, decision((row,))))
    request = prepare_fidelity(mapped_result.account, unit.id)
    whole = {"document": source.id, "span": {"start": 0, "end": 2}, "text": "😀"}
    assert (
        resolve_fidelity(request, reply(request, assessment("a0", evidence=(whole,))))
        .decisions[0]
        .status
        == "admitted"
    )
    split = {**whole, "span": {"start": 0, "end": 1}}
    refused = resolve_fidelity(request, reply(request, assessment("a0", evidence=(split,))))
    assert refused.decisions[0].status == "rejected" and "surrogate" in refused.decisions[0].reason
    assert refused.account.bindings == mapped_result.account.bindings
    mapping, rows = mapped(alternative=True)
    request = prepare_fidelity(mapping.account, mapping.account.details[0].unit)
    packet = json.loads(fidelity_prompt(request))
    key = next(q["key"] for q in packet["questions"] if q["hypothesis"]["targets"] == [rows[0].id])
    foreign = resolve_fidelity(
        request, reply(request, assessment(key, evidence=(quote(rows[1], text="CC DD"),)))
    )
    rejected = next(d for d in foreign.decisions if d.local_key == key)
    assert rejected.status == "rejected" and "outside its source hypothesis" in rejected.reason
    assert foreign.account.bindings == mapping.account.bindings


def test_shared_hypothesis_identity_never_shares_a_grade_between_details():
    mapping_request, rows, _ = fixture(extra_detail=True)
    mapping = resolve_mapping(
        mapping_request,
        raw(mapping_request, decision(rows[:1], key="d0"), decision(rows[:1], key="d1")),
    )
    assert len({b.reference.hypotheses[0].id for b in mapping.account.bindings}) == 1
    request = prepare_fidelity(mapping.account, mapping_request.unit)
    result = resolve_fidelity(
        request,
        reply(
            request,
            assessment("a0", grade="supported", evidence=(quote(rows[0]),)),
            assessment("a1", grade="contradicted", evidence=(quote(rows[0]),)),
        ),
    )
    assert [a.grade for b in result.account.bindings for a in b.assessments] == [
        "supported",
        "contradicted",
    ]
    assert [b.reference for b in result.account.bindings] == [
        b.reference for b in mapping.account.bindings
    ]
