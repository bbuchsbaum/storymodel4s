"""Recurring abstract cast/action controls through public construction and correspondence APIs.

AA/BB are cast tokens; PP/QQ are action tokens; XX/YY/ZZ are manner tokens. Recorded
mapping and grading replies are scripted contract controls, not model accuracy evidence.
"""

import json
import sys
from pathlib import Path

from storymodel import (
    ClaimQuestion,
    FidelityRecording,
    MappingRecording,
    SceneGroup,
    SourceRow,
    admit_details,
    align_account,
    assess_claims,
    claim_population_digest,
    compile_rich_construction,
    export_supported_account,
    fidelity_prompt,
    group_source_rows,
    import_fidelity,
    import_mapping,
    mapping_prompt,
    pair_rich_accounts,
    prepare_claim_assessment,
    prepare_details,
    prepare_fidelity,
    prepare_mapping,
    prepare_rich_construction,
    project,
    replay_fidelity,
    replay_mapping,
    verify_alignment,
    verify_fidelity,
    verify_mapping,
    verify_scene_grouping,
)
from storymodel.artifacts import publish_json
from storymodel.claim_assessment import ClaimCitation, ClaimSubmission
from storymodel.detail_admission import detail_request_digest
from storymodel.direct_mapping import mapping_request_digest
from storymodel.fidelity import fidelity_request_digest
from storymodel.model import Span
from storymodel.rich_construction import verify_rich_construction
from storymodel.serialization import canonical, digest

SOURCE = ("AA PP BB XX", "AA QQ BB YY", "AA PP BB ZZ", "CC RR DD WW")
# key, exact recall, reference kind, source row ordinals, grade, optional graph
CASES = (
    ("second", "AA QQ BB YY", "single", (1,), "supported", True),
    ("first", "AA PP BB XX", "single", (0,), "supported", True),
    ("swap", "BB QQ AA YY", "single", (1,), "contradicted", True),
    ("changed", "AA QQ BB ZZ", "single", (1,), "gist-detail-error", True),
    ("joint", "AA PP BB XX AA QQ BB YY", "joint", (0, 1), "supported", False),
    ("broad", "AA PP QQ BB", "broad", (0, 1), "supported", False),
    ("ambiguous", "AA PP BB", "alternative", (0, 2), "supported", True),
    ("external", "EE TT FF", "external", (), "external", False),
    ("revisit", "AA QQ BB YY", "single", (1,), "supported", True),
)


def save(path, value):
    publish_json(path, value.model_dump(mode="json") if hasattr(value, "model_dump") else value)


def construct(root, key, side, rows):
    """Compile exact recorded graph proposals; all offsets here are ASCII UTF-16 units."""
    root.mkdir()
    text = "\n".join(value for _, value, _ in rows)
    if side == "recall":
        # Intake accounts for every raw character, including separators. Source row gaps
        # remain gaps; recall separators belong to the preceding unit's review scope.
        text += "\n"
    offset = 0
    anchors, proposals = [], []
    for label, value, graph in rows:
        span = Span(start=offset, end=offset + len(value))
        anchors.append(
            SourceRow(
                key=label,
                span=Span(start=span.start, end=span.end + (1 if side == "recall" else 0)),
            )
        )
        if not graph:
            proposals.append(
                {
                    "row": label,
                    "status": "unsupported",
                    "reason": "this control has no local graph proposal",
                }
            )
        else:
            words = value.split()

            def quote(i):
                start = offset + 3 * i
                return {"span": {"start": start, "end": start + 2}, "text": words[i]}

            proposals.append(
                {
                    "row": label,
                    "status": "completed",
                    "entities": [
                        {"key": "a", "mention": quote(0)},
                        {"key": "b", "mention": quote(2)},
                    ],
                    "events": [
                        {
                            "key": "event",
                            "context": "narrated",
                            "evidence": {"span": span.model_dump(), "text": value},
                            "predicate": quote(1),
                            "participants": [
                                {"entity": "a", "role": "agent", "evidence": quote(0)},
                                {"entity": "b", "role": "patient", "evidence": quote(2)},
                            ],
                        }
                    ],
                    "circumstances": (
                        [
                            {
                                "key": "manner",
                                "situation": "event",
                                "kind": "manner",
                                "evidence": quote(3),
                            }
                        ]
                        if len(words) == 4
                        else []
                    ),
                }
            )
        offset += len(value) + 1
    request = prepare_rich_construction(key, side, text, anchors, export_permission="allowed")
    raw = canonical(
        {"schema_version": "storymodel.rich-construction-proposal/v1", "rows": proposals}
    )
    result = compile_rich_construction(request, raw)
    verify_rich_construction(request, raw, result)
    assert all(
        r.status == ("admitted" if graph else "unsupported")
        for r, (_, _, graph) in zip(result.resolutions, rows, strict=True)
    )
    save(root / "request.json", request)
    (root / "reply.json").write_text(raw, encoding="utf-8")
    save(root / "result.json", result)
    return result


def run(destination):
    destination.mkdir(parents=True, exist_ok=False)
    source_result = construct(
        destination / "source",
        "source",
        "source",
        tuple((f"r{i}", text, True) for i, text in enumerate(SOURCE)),
    )
    original_rows = tuple(r.unit for r in source_result.resolutions)
    groups = (
        SceneGroup(key="scene-a", rows=original_rows[:2]),
        SceneGroup(key="scene-b", rows=original_rows[2:]),
    )
    grouped = group_source_rows(source_result.account, groups)
    verify_scene_grouping(source_result.account, groups, grouped)
    source = grouped.account
    save(destination / "source/groups.json", [g.model_dump(mode="json") for g in groups])
    save(destination / "source/grouped.json", grouped)
    export_supported_account(destination / "source/account.json", source)
    source_bytes = (destination / "source/account.json").read_bytes()
    source_digest = digest(source.model_dump(mode="json"))
    source_rows = tuple(grouped.rows[r] for r in original_rows)
    source_units = {u.id: u for u in source.units}
    source_documents = {d.id: d for d in source.documents}
    previous_prompts = {}
    previous_requests = {}
    summaries = {}
    for session, cases in (("recall-a", CASES), ("recall-b", tuple(reversed(CASES)))):
        root = destination / session
        root.mkdir()
        recall = construct(
            root / "construction", session, "recall", tuple((c[0], c[1], c[5]) for c in cases)
        )
        units = {r.row: r.unit for r in recall.resolutions}
        situations = {r.row: r.accepted.get("event") for r in recall.resolutions}
        paired = pair_rich_accounts(source, recall.account)
        detail_request = prepare_details(paired, tuple(units[c[0]] for c in cases))
        by_unit = {u.id: u for u in recall.account.units}
        detail_raw = canonical(
            {
                "schema_version": "storymodel.detail-proposal/v1",
                "request_sha256": detail_request_digest(detail_request),
                "units": [
                    {
                        "unit": units[c[0]],
                        "status": "completed",
                        "reason": "explicit abstract control inventory",
                        "details": [
                            {
                                "key": "claim",
                                "kind": "knowledge" if c[0] == "external" else "event",
                                "address": "personal" if c[0] == "external" else "target",
                                "discourse": "repetition" if c[0] == "revisit" else "content",
                                "situation": situations[c[0]],
                                "quotes": [
                                    {
                                        "span": Span(
                                            start=by_unit[units[c[0]]].support.spans[0].start,
                                            end=by_unit[units[c[0]]].support.spans[0].start
                                            + len(c[1]),
                                        ).model_dump(),
                                        "text": c[1],
                                    }
                                ],
                            }
                        ],
                    }
                    for c in cases
                ],
            }
        )
        details = admit_details(detail_request, detail_raw)
        assert all(r.status == "admitted" for r in details.receipts)
        save(root / "detail-request.json", detail_request)
        (root / "detail-reply.json").write_text(detail_raw, encoding="utf-8")
        save(root / "details.json", details)
        structural = align_account(details.account)
        verify_alignment(details.account, structural)
        save(root / "structural.json", structural)
        save(root / "structural-analysis.json", project(structural.account))
        structural_bindings = {
            next(d.unit for d in details.account.details if d.id == b.detail): b
            for b in structural.account.bindings
        }
        structural_outcomes = {}
        for c in cases:
            reference = structural_bindings[units[c[0]]].reference
            structural_outcomes[c[0]] = {
                "status": reference.status,
                "outcome": reference.outcome,
            }
            expected = (
                "resolved"
                if c[0] in ("second", "changed", "revisit")
                else "alternative"
                if c[0] in ("first", "ambiguous")
                else "unresolved"
                if c[0] == "swap"
                else None
            )
            assert reference.outcome == expected, (c[0], reference)
            assert reference.status == ("attempted" if c[5] else "not-attempted")
        assert all(not b.assessments for b in structural.account.bindings)
        swap_candidates = next(
            s for s in structural.situations if s.recall_situation == situations["swap"]
        )
        swapped_qq = next(c for c in swap_candidates.candidates if c.source_predicate == "QQ")
        assert set(swapped_qq.conflicting_roles) == {"agent", "patient"} and not swapped_qq.eligible
        changed_candidates = next(
            s for s in structural.situations if s.recall_situation == situations["changed"]
        )
        changed_qq = next(c for c in changed_candidates.candidates if c.source_predicate == "QQ")
        assert [c.label for c in changed_qq.source_circumstances] == ["YY"]
        assert [c.label for c in changed_qq.recall_circumstances] == ["ZZ"]
        account = details.account
        for label, text, kind, ordinals, grade, graph in cases:
            case_root = root / label
            case_root.mkdir()
            request = prepare_mapping(account, units[label])
            prompt = mapping_prompt(request)
            if session == "recall-a":
                previous_prompts[label] = prompt
                previous_requests[label] = mapping_request_digest(request)
            else:
                assert prompt == previous_prompts[label]
                assert mapping_request_digest(request) != previous_requests[label]
            if kind == "alternative":
                hypotheses = [
                    {"key": f"h{i}", "kind": "single", "targets": [source_rows[j]]}
                    for i, j in enumerate(ordinals)
                ]
            elif kind == "external":
                hypotheses = [{"key": "h0", "kind": "external", "external": "personal"}]
            else:
                hypotheses = [
                    {
                        "key": "h0",
                        "kind": kind,
                        "targets": [grouped.scenes["scene-a"]]
                        if kind == "broad"
                        else [source_rows[i] for i in ordinals],
                    }
                ]
            raw = canonical(
                {
                    "schema_version": "storymodel.mapping-proposal/v1",
                    "packet_sha256": json.loads(prompt)["packet_sha256"],
                    "details": [
                        {
                            "detail": "d0",
                            "status": "attempted",
                            "outcome": "alternative" if kind == "alternative" else "resolved",
                            "reason": "scripted abstract reference control",
                            "hypotheses": hypotheses,
                        }
                    ],
                }
            )
            recording = MappingRecording(
                request_sha256=mapping_request_digest(request),
                origin="abstract correspondence control/v1",
                reply=raw,
            )
            save(case_root / "mapping-request.json", request)
            save(case_root / "mapping-prompt.json", json.loads(prompt))
            save(case_root / "mapping-reply.json", recording)
            import_mapping(request, recording, case_root / "mapping-recording")
            mapped = replay_mapping(request, case_root / "mapping-recording")
            verify_mapping(request, raw, mapped)
            save(case_root / "mapping-result.json", mapped)
            assert mapped.decisions[0].status == "admitted"
            assert all(a.status == "pending" for a in mapped.account.bindings[-1].assessments)
            grading = prepare_fidelity(mapped.account, units[label])
            grade_prompt = json.loads(fidelity_prompt(grading))
            proposals = []
            for question in grade_prompt["questions"]:
                evidence = []
                for target in question["hypothesis"]["targets"]:
                    target_unit = source_units[target]
                    for span in target_unit.support.spans:
                        evidence.append(
                            {
                                "document": target_unit.support.document,
                                "span": span.model_dump(),
                                "text": span.slice(
                                    source_documents[target_unit.support.document].text
                                ),
                            }
                        )
                proposals.append(
                    {
                        "hypothesis": question["key"],
                        "status": "completed",
                        "grade": grade,
                        "reason": "scripted abstract conditional grade control",
                        "evidence": evidence,
                        **({"severity": "minor"} if grade == "gist-detail-error" else {}),
                    }
                )
            grade_raw = canonical(
                {
                    "schema_version": "storymodel.fidelity-proposal/v1",
                    "packet_sha256": grade_prompt["packet_sha256"],
                    "assessments": proposals,
                }
            )
            grade_recording = FidelityRecording(
                request_sha256=fidelity_request_digest(grading),
                origin="abstract conditional grade control/v1",
                reply=grade_raw,
            )
            save(case_root / "fidelity-request.json", grading)
            save(case_root / "fidelity-prompt.json", grade_prompt)
            save(case_root / "fidelity-reply.json", grade_recording)
            import_fidelity(grading, grade_recording, case_root / "fidelity-recording")
            graded = replay_fidelity(grading, case_root / "fidelity-recording")
            verify_fidelity(grading, grade_raw, graded)
            assert all(d.status == "admitted" for d in graded.decisions)
            assert [b.reference for b in graded.account.bindings] == [
                b.reference for b in mapped.account.bindings
            ]
            save(case_root / "fidelity-result.json", graded)
            account = graded.account
        assert (
            len({d.id for d in account.details if d.unit in (units["second"], units["revisit"])})
            == 2
        )
        assert previous_prompts["second"] == previous_prompts["revisit"]
        views = project(account)
        export_supported_account(root / "account.json", account)
        save(root / "analysis.json", views)
        # Independent fixed-question accounting is not converted into the grades above.
        questions = tuple(
            ClaimQuestion(unit=units[label], target=source_rows[index], facet="action")
            for label, index in (("first", 0), ("swap", 1), ("ambiguous", 0))
        )
        plan = prepare_claim_assessment(account, questions)
        citations = tuple(
            ClaimCitation(origin=source_rows[i], support=source_units[source_rows[i]].support)
            for i in (0, 1)
        )
        submissions = []
        for i, label in enumerate(("first", "swap", "ambiguous")):
            scope = by_unit[units[label]].support
            submissions.append(
                ClaimSubmission(
                    question=questions[i].id,
                    recall_state="reference-unresolved" if i == 2 else "expressed",
                    reviewed=scope,
                    claim=scope,
                    supporting=(citations[0].id,) if i != 1 else (),
                    counterevidence=(citations[1].id,) if i == 1 else (),
                    unresolved=("reference-unresolved",) if i == 2 else (),
                    assessor="deterministic-rule",
                    procedure="abstract-correspondence-accounting/v1",
                )
            )
        claim_raw = canonical(
            {
                "schema_version": "storymodel.claim-assessment-reply/v1",
                "population_sha256": claim_population_digest(plan),
                "citations": [c.model_dump(mode="json") for c in citations],
                "submissions": [s.model_dump(mode="json") for s in submissions],
            }
        )
        claim = assess_claims(plan, claim_raw)
        assert (claim.total_questions, claim.expressed_questions, claim.resolved_questions) == (
            3,
            3,
            2,
        )
        save(root / "claim-plan.json", plan)
        (root / "claim-reply.json").write_text(claim_raw, encoding="utf-8")
        save(root / "claim-receipt.json", claim)
        summaries[session] = {
            "units": units,
            "structural_outcomes": structural_outcomes,
            "source_coverage": {u.source_unit: u.coverage for u in views.source},
        }
        assert source.model_dump_json() == grouped.account.model_dump_json()
        assert digest(source.model_dump(mode="json")) == source_digest
        assert (destination / "source/account.json").read_bytes() == source_bytes
        assert tuple(u for u in account.units if u.kind != "recall") == source.units
    summary = {
        "source_rows": source_rows,
        "scenes": grouped.scenes,
        "sessions": summaries,
        "source_reused": 2,
        "source_unchanged": True,
        "unit_only_prompts_equal_after_reordering": True,
        "recorded_replies_are_scripted": True,
        "accuracy_claim": False,
    }
    save(destination / "summary.json", summary)
    print(canonical(summary))


if __name__ == "__main__":
    run(Path(sys.argv[1]))
