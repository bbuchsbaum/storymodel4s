"""Fixed-population claim assessment controls over abstract account tokens."""

import json
import subprocess
import sys
from pathlib import Path

import pytest
from test_rich_alignment import rich_control

from storymodel.claim_assessment import (
    ClaimCitation,
    ClaimQuestion,
    ClaimSubmission,
    assess_claims,
    claim_population_digest,
    prepare_claim_assessment,
    verify_claim_assessment,
)
from storymodel.model import Span, Support


def specimen(question_count=1):
    """Use nonnarrative control tokens, with one source and one recall occurrence."""
    account = rich_control()
    recall = next(unit for unit in account.units if unit.kind == "recall")
    source = next(unit for unit in account.units if unit.kind == "row")
    facets = ("action", "actor", "object", "location", "outcome", "cause", "context")
    questions = tuple(
        ClaimQuestion(unit=recall.id, target=source.id, facet=facets[index])
        for index in range(question_count)
    )
    plan = prepare_claim_assessment(account, questions)
    citation = ClaimCitation(origin=source.id, support=source.support)
    return plan, recall, source, citation


def submission(question, recall, citation=None, *, state="expressed", **changes):
    expressed = state in ("expressed", "reference-unresolved")
    values = {
        "question": question.id,
        "recall_state": state,
        "reviewed": recall.support,
        "claim": recall.support if expressed else None,
        "supporting": (citation.id,) if citation and state == "expressed" else (),
        "counterevidence": (),
        "unresolved": ()
        if citation and state == "expressed"
        else (
            ("reference-unresolved",)
            if state == "reference-unresolved"
            else ("recall-extraction-unavailable",)
            if state == "extraction-unresolved"
            else ("source-unavailable",)
            if state == "expressed"
            else ()
        ),
        "assessor": "deterministic-rule",
        "procedure": "synthetic-control/v1",
    }
    values.update(changes)
    return ClaimSubmission(**values)


def raw_reply(plan, citations, submissions):
    return json.dumps(
        {
            "schema_version": "storymodel.claim-assessment-reply/v1",
            "population_sha256": claim_population_digest(plan),
            "citations": [citation.model_dump(mode="json") for citation in citations],
            "submissions": [item.model_dump(mode="json") for item in submissions],
        }
    )


def test_all_statuses_have_fixed_denominator_and_evidence_ablation_preserves_population():
    plan, recall, _, citation = specimen(7)
    states = (
        submission(plan.questions[0], recall, citation),
        submission(plan.questions[1], recall, None, counterevidence=(citation.id,), unresolved=()),
        submission(
            plan.questions[2],
            recall,
            None,
            supporting=(citation.id,),
            counterevidence=(citation.id,),
            unresolved=(),
        ),
        submission(plan.questions[3], recall),
        submission(plan.questions[4], recall, None, state="reviewed-not-expressed"),
        submission(plan.questions[5], recall, None, state="extraction-unresolved"),
        submission(plan.questions[6], recall, None, state="reference-unresolved"),
    )
    raw = raw_reply(plan, (citation,), states)
    receipt = assess_claims(plan, raw)
    assert receipt.counts == {
        "supported": 1,
        "contradicted": 1,
        "conflicted": 1,
        "unresolved": 1,
        "not-expressed": 1,
        "extraction-unresolved": 1,
        "reference-unresolved": 1,
    }
    assert (
        receipt.total_questions,
        receipt.expressed_questions,
        receipt.resolved_questions,
    ) == (7, 5, 2)

    ablated = tuple(
        item.model_copy(
            update={"supporting": (), "counterevidence": (), "unresolved": ("source-unavailable",)}
        )
        if item.recall_state == "expressed"
        else item
        for item in states
    )
    no_evidence = assess_claims(plan, raw_reply(plan, (), ablated))
    assert no_evidence.population_sha256 == receipt.population_sha256
    assert (
        no_evidence.total_questions,
        no_evidence.expressed_questions,
        no_evidence.resolved_questions,
    ) == (
        7,
        5,
        0,
    )


def test_complete_population_and_all_text_ownership_boundaries_refuse():
    plan, recall, source, citation = specimen()
    good = submission(plan.questions[0], recall, citation)
    accepted = assess_claims(plan, raw_reply(plan, (citation,), (good,)))
    assert accepted.decisions[0].status == "supported"

    for invalid_question in (
        ClaimQuestion(unit="unit:foreign", target=source.id, facet="action"),
        ClaimQuestion(unit=recall.id, target="unit:foreign", facet="action"),
    ):
        with pytest.raises(ValueError):
            prepare_claim_assessment(plan.account, (invalid_question,))

    foreign_question = ClaimQuestion(unit=recall.id, target=source.id, facet="actor")
    cases = [
        raw_reply(plan, (citation,), ()),
        raw_reply(plan, (citation,), (good, good)),
        raw_reply(plan, (citation,), (good.model_copy(update={"question": foreign_question.id}),)),
        raw_reply(plan, (), (good.model_copy(update={"supporting": ("claim-citation:foreign",)}),)),
        raw_reply(
            plan,
            (citation.model_copy(update={"origin": recall.id}),),
            (good.model_copy(update={"supporting": (citation.id,)}),),
        ),
        raw_reply(
            plan,
            (citation,),
            (good.model_copy(update={"claim": source.support}),),
        ),
        raw_reply(
            plan,
            (citation,),
            (good.model_copy(update={"reviewed": source.support, "claim": source.support}),),
        ),
    ]
    for raw in cases:
        with pytest.raises(ValueError):
            assess_claims(plan, raw)


def test_citations_require_their_declared_source_but_can_cross_origin_targets():
    account = rich_control([("P", "X", "agent"), ("Q", "Y", "agent")])
    recall = next(unit for unit in account.units if unit.kind == "recall")
    source_units = tuple(unit for unit in account.units if unit.kind == "row")
    question = ClaimQuestion(unit=recall.id, target=source_units[0].id, facet="action")
    plan = prepare_claim_assessment(account, (question,))
    cross_origin = ClaimCitation(origin=source_units[1].id, support=source_units[1].support)
    accepted = submission(question, recall, cross_origin)
    receipt = assess_claims(plan, raw_reply(plan, (cross_origin,), (accepted,)))
    assert receipt.counts["supported"] == 1

    wrong_document = ClaimCitation(origin=source_units[0].id, support=source_units[1].support)
    rejected = submission(question, recall, wrong_document)
    with pytest.raises(ValueError, match="outside its source origin"):
        assess_claims(plan, raw_reply(plan, (wrong_document,), (rejected,)))


def test_duplicate_semantics_and_adjacent_citation_identity_are_not_count_multipliers():
    plan, recall, source, citation = specimen()
    duplicate = ClaimCitation(origin=source.id, support=source.support)
    assert duplicate.id == citation.id
    with pytest.raises(ValueError, match="duplicate claim question"):
        prepare_claim_assessment(plan.account, (plan.questions[0], plan.questions[0]))
    with pytest.raises(ValueError):
        assess_claims(
            plan,
            raw_reply(
                plan,
                (citation, duplicate),
                (submission(plan.questions[0], recall, citation),),
            ),
        )

    split = ClaimCitation(
        origin=source.id,
        support=Support(
            document=source.support.document,
            spans=(Span(start=0, end=2), Span(start=2, end=8)),
        ),
    )
    gap = ClaimCitation(
        origin=source.id,
        support=Support(
            document=source.support.document,
            spans=(Span(start=0, end=2), Span(start=3, end=8)),
        ),
    )
    assert split.id == citation.id and gap.id != citation.id
    several = submission(plan.questions[0], recall, supporting=(citation.id, gap.id), unresolved=())
    assert assess_claims(plan, raw_reply(plan, (citation, gap), (several,))).resolved_questions == 1


def test_raw_and_typed_boundaries_reject_forgery_and_bind_exact_context_and_bytes():
    plan, recall, _, citation = specimen()
    raw = raw_reply(plan, (citation,), (submission(plan.questions[0], recall, citation),))
    receipt = assess_claims(plan, raw)
    assert verify_claim_assessment(plan, raw, receipt) == receipt
    with pytest.raises(ValueError):
        verify_claim_assessment(plan, raw + " ", receipt)
    with pytest.raises(ValueError):
        verify_claim_assessment(plan, raw, receipt.model_copy(update={"total_questions": 99}))
    with pytest.raises(ValueError):
        verify_claim_assessment(plan, raw, receipt.model_copy(update={"total_questions": True}))
    with pytest.raises(ValueError):
        assess_claims(plan.model_copy(update={"unexpected": 1}), raw)
    with pytest.raises(ValueError):
        assess_claims(plan.model_copy(update={"questions": ()}), raw)
    with pytest.raises(ValueError):
        duplicate_citations = raw[:-1] + ', "citations": []}'
        assess_claims(plan, duplicate_citations)
    with pytest.raises(ValueError):
        nonfinite_population = raw.replace(
            '"population_sha256": "', '"population_sha256": NaN, "x": "', 1
        )
        assess_claims(plan, nonfinite_population)


def test_reordering_is_normalized_and_empty_population_has_counts_without_ratios():
    plan, recall, _, citation = specimen(2)
    second = ClaimCitation(
        origin=citation.origin,
        support=Support(document=citation.support.document, spans=(Span(start=0, end=2),)),
    )
    first_submission = submission(plan.questions[0], recall, citation)
    second_submission = submission(plan.questions[1], recall, second)
    receipt = assess_claims(
        plan,
        raw_reply(plan, (second, citation), (second_submission, first_submission)),
    )
    assert tuple(item.question for item in receipt.submissions) == tuple(
        question.id for question in plan.questions
    )
    assert tuple(item.id for item in receipt.citations) == tuple(sorted((citation.id, second.id)))

    empty = prepare_claim_assessment(plan.account, ())
    no_questions = assess_claims(empty, raw_reply(empty, (), ()))
    assert no_questions.counts == {
        status: 0
        for status in (
            "supported",
            "contradicted",
            "conflicted",
            "unresolved",
            "not-expressed",
            "extraction-unresolved",
            "reference-unresolved",
        )
    }
    assert (
        no_questions.total_questions,
        no_questions.expressed_questions,
        no_questions.resolved_questions,
    ) == (0, 0, 0)
    assert "ratio" not in no_questions.model_dump_json()


@pytest.mark.parametrize(
    "state,support,counter,unknown,expected",
    [
        ("expressed", True, False, ("context-unresolved",), "unresolved"),
        ("expressed", False, True, ("context-unresolved",), "unresolved"),
        ("expressed", True, True, ("context-unresolved",), "conflicted"),
        ("reference-unresolved", True, True, ("reference-unresolved",), "reference-unresolved"),
    ],
)
def test_evidence_and_reference_status_precedence(state, support, counter, unknown, expected):
    plan, recall, _, citation = specimen()
    item = submission(
        plan.questions[0],
        recall,
        state=state,
        supporting=(citation.id,) if support else (),
        counterevidence=(citation.id,) if counter else (),
        unresolved=unknown,
    )
    receipt = assess_claims(plan, raw_reply(plan, (citation,), (item,)))
    assert receipt.decisions[0].status == expected and receipt.resolved_questions == 0
    assert receipt.submissions[0].supporting == item.supporting
    assert receipt.submissions[0].counterevidence == item.counterevidence


@pytest.mark.parametrize(
    "change",
    [
        {"claim": None},
        {"recall_state": "reviewed-not-expressed"},
        {
            "recall_state": "extraction-unresolved",
            "claim": None,
            "supporting": (),
            "unresolved": (),
        },
        {"recall_state": "reference-unresolved", "unresolved": ()},
        {
            "recall_state": "reviewed-not-expressed",
            "claim": None,
            "supporting": (),
            "unresolved": ("source-unavailable",),
        },
        {"supporting": (), "unresolved": ()},
    ],
)
def test_recall_availability_cannot_be_replaced_by_plausible_empty_fields(change):
    plan, recall, _, citation = specimen()
    good = submission(plan.questions[0], recall, citation)
    assert assess_claims(plan, raw_reply(plan, (citation,), (good,))).resolved_questions == 1
    with pytest.raises(ValueError):
        assess_claims(plan, raw_reply(plan, (citation,), (good.model_copy(update=change),)))


def test_canonical_citation_identity_never_hides_original_utf16_split():
    from storymodel.accounts import make_account
    from storymodel.model import Document, Unit

    source = Document.create("unicode-source", "source", "😀 AA")
    recall = Document.create("unicode-recall", "recall", "BB")
    row = Unit(kind="row", support=Support(document=source.id, spans=(Span(start=0, end=5),)))
    unit = Unit(kind="recall", support=Support(document=recall.id, spans=(Span(start=0, end=2),)))
    account = make_account((source, recall), units=(row, unit))
    question = ClaimQuestion(unit=unit.id, target=row.id, facet="action")
    plan = prepare_claim_assessment(account, (question,))
    whole = ClaimCitation(
        origin=row.id, support=Support(document=source.id, spans=(Span(start=0, end=2),))
    )
    split = ClaimCitation(
        origin=row.id,
        support=Support(document=source.id, spans=(Span(start=0, end=1), Span(start=1, end=2))),
    )
    assert whole.id == split.id
    item = submission(question, unit, whole)
    assert assess_claims(plan, raw_reply(plan, (whole,), (item,))).resolved_questions == 1
    with pytest.raises(ValueError, match="surrogate"):
        assess_claims(plan, raw_reply(plan, (split,), (item,)))


def test_claim_and_source_support_gaps_cannot_be_filled_by_a_hull():
    plan, recall, source, _ = specimen()
    reviewed = Support(
        document=recall.support.document, spans=(Span(start=0, end=2), Span(start=6, end=8))
    )
    item = submission(plan.questions[0], recall, reviewed=reviewed, claim=reviewed)
    assert assess_claims(plan, raw_reply(plan, (), (item,))).decisions[0].status == "unresolved"
    with pytest.raises(ValueError, match="review scope"):
        assess_claims(
            plan, raw_reply(plan, (), (item.model_copy(update={"claim": recall.support}),))
        )
    from storymodel.model import Unit
    from storymodel.rich_account import validate_rich_account

    gap_row = Unit(
        kind="row",
        support=Support(
            document=source.support.document, spans=(Span(start=0, end=2), Span(start=6, end=8))
        ),
    )
    account = validate_rich_account(
        plan.account.model_copy(
            update={
                "units": (gap_row,) + tuple(u for u in plan.account.units if u.kind == "recall")
            }
        )
    )
    question = ClaimQuestion(unit=recall.id, target=gap_row.id, facet="action")
    gap_plan = prepare_claim_assessment(account, (question,))
    good = ClaimCitation(origin=gap_row.id, support=gap_row.support)
    assert (
        assess_claims(
            gap_plan, raw_reply(gap_plan, (good,), (submission(question, recall, good),))
        ).resolved_questions
        == 1
    )
    hull = ClaimCitation(origin=gap_row.id, support=source.support)
    with pytest.raises(ValueError, match="outside its source origin"):
        assess_claims(gap_plan, raw_reply(gap_plan, (hull,), (submission(question, recall, hull),)))


def test_cli_workflow_schema_and_grants(tmp_path, capsys):
    from storymodel.artifacts import publish_json
    from storymodel.claim_assessment import (
        ClaimAssessmentPlan,
        ClaimAssessmentReceipt,
        ClaimAssessmentReply,
    )
    from storymodel.cli import main

    pilot = Path(__file__).parents[1]
    output = tmp_path / "example"
    completed = subprocess.run(
        [sys.executable, str(pilot / "examples/claim_assessment_workflow.py"), str(output)],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert completed.returncode == 0, completed.stderr
    assert (
        main(
            [
                "prepare-claim-assessment",
                str(output / "mapping/recall-b/account.json"),
                str(output / "questions.json"),
                "--output",
                str(output / "cli-plan.json"),
            ]
        )
        == 0
    )
    assert json.loads((output / "cli-plan.json").read_text()) == json.loads(
        (output / "plan.json").read_text()
    )
    assert (
        main(
            [
                "assess-claims",
                str(output / "plan.json"),
                str(output / "reply.json"),
                "--output",
                str(output / "cli.json"),
            ]
        )
        == 0
    )
    assert json.loads((output / "cli.json").read_text()) == json.loads(
        (output / "receipt.json").read_text()
    )
    assert (
        main(
            [
                "verify-claims",
                str(output / "plan.json"),
                str(output / "reply.json"),
                str(output / "cli.json"),
            ]
        )
        == 0
    )
    assert '"valid":true' in capsys.readouterr().out
    plan, *_ = specimen()
    denied_account = plan.account.model_copy(update={"export_permission": "denied"})
    denied = prepare_claim_assessment(denied_account, plan.questions)
    publish_json(tmp_path / "denied.json", denied.model_dump(mode="json"))
    publish_json(tmp_path / "denied-account.json", denied_account.model_dump(mode="json"))
    for args in (
        [
            "prepare-claim-assessment",
            str(tmp_path / "denied-account.json"),
            str(tmp_path / "missing"),
        ],
        ["assess-claims", str(tmp_path / "denied.json"), str(tmp_path / "missing")],
        [
            "verify-claims",
            str(tmp_path / "denied.json"),
            str(tmp_path / "missing"),
            str(tmp_path / "missing-result"),
        ],
    ):
        assert main([*args, "--output", str(tmp_path / "forbidden")]) == 2
        assert "denies claim assessment export" in capsys.readouterr().err
        assert not (tmp_path / "forbidden").exists()
    assert json.loads((pilot / "schemas/claim-assessment.json").read_text()) == {
        "plan": ClaimAssessmentPlan.model_json_schema(),
        "reply": ClaimAssessmentReply.model_json_schema(),
        "receipt": ClaimAssessmentReceipt.model_json_schema(),
    }


@pytest.mark.parametrize("field", ("chart", "playback"))
def test_unsupported_evidence_is_explicitly_refused(field):
    plan, recall, _, citation = specimen()
    packet = json.loads(
        raw_reply(plan, (citation,), (submission(plan.questions[0], recall, citation),))
    )
    packet["citations"][0][field] = {"owner": "unchecked"}
    with pytest.raises(ValueError, match="Extra inputs"):
        assess_claims(plan, json.dumps(packet))
