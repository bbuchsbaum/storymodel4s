"""Fixed-population accounting over the existing abstract rich mapping workflow.

These are attributed diagnostic submissions, not automated truth or accuracy judgments.
"""

import subprocess
import sys
from pathlib import Path

from storymodel import (
    ClaimQuestion,
    assess_claims,
    claim_population_digest,
    prepare_claim_assessment,
    read_supported_account,
    verify_claim_assessment,
)
from storymodel.artifacts import publish_json
from storymodel.claim_assessment import ClaimCitation, ClaimSubmission
from storymodel.serialization import canonical

destination = Path(sys.argv[1])
destination.mkdir(parents=True, exist_ok=False)
subprocess.run(
    [
        sys.executable,
        str(Path(__file__).with_name("rich_alignment_workflow.py")),
        str(destination / "mapping"),
    ],
    check=True,
)
account_path = destination / "mapping/recall-b/account.json"
before = account_path.read_bytes()
account = read_supported_account(account_path)
unit = next(u for u in account.units if u.kind == "recall")
target = next(s for s in account.states if s.side == "source")
questions = tuple(
    ClaimQuestion(unit=unit.id, target=target.id, facet=facet)
    for facet in ("actor", "action", "object", "location", "outcome", "cause", "polarity")
)
plan = prepare_claim_assessment(account, questions)
source_row = next(u for u in account.units if u.kind == "row")
citation = ClaimCitation(origin=source_row.id, support=source_row.support)
submissions = []
for index, question in enumerate(questions):
    state = (
        "expressed"
        if index < 4
        else "reviewed-not-expressed"
        if index == 4
        else "extraction-unresolved"
        if index == 5
        else "reference-unresolved"
    )
    submissions.append(
        ClaimSubmission(
            question=question.id,
            recall_state=state,
            reviewed=unit.support,
            claim=unit.support if state in ("expressed", "reference-unresolved") else None,
            supporting=(citation.id,) if index in (0, 2, 6) else (),
            counterevidence=(citation.id,) if index in (1, 2) else (),
            unresolved=("source-unavailable",)
            if index == 3
            else ("recall-extraction-unavailable",)
            if index == 5
            else ("reference-unresolved",)
            if index == 6
            else (),
            assessor="deterministic-rule",
            procedure="abstract-seven-state-control/v1",
        )
    )
raw = canonical(
    {
        "schema_version": "storymodel.claim-assessment-reply/v1",
        "population_sha256": claim_population_digest(plan),
        "citations": [citation.model_dump(mode="json")],
        "submissions": [s.model_dump(mode="json") for s in reversed(submissions)],
    }
)
receipt = assess_claims(plan, raw)
verify_claim_assessment(plan, raw, receipt)
assert set(receipt.counts.values()) == {1}
assert (receipt.total_questions, receipt.expressed_questions, receipt.resolved_questions) == (
    7,
    5,
    2,
)
assert account_path.read_bytes() == before
publish_json(destination / "questions.json", [q.model_dump(mode="json") for q in questions])
publish_json(destination / "plan.json", plan.model_dump(mode="json"))
(destination / "reply.json").write_text(raw, encoding="utf-8")
publish_json(destination / "receipt.json", receipt.model_dump(mode="json"))
print(
    canonical(
        {
            "question_counts": receipt.counts,
            "total": 7,
            "expressed": 5,
            "resolved": 2,
            "mapping_unchanged": True,
            "accuracy_claim": False,
        }
    )
)
