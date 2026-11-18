"""Run synthetic Friends intake through real source admission and save portable artifacts.

The values are abstract AA/BB tokens, not Friends material.  The example proves
the input boundary and saved-artifact path, not narrative extraction, event-code
scoring, timestamp recovery, or scientific validity.
"""

import json
import sys
from pathlib import Path

from storymodel.analysis_workflow import (
    AnalysisAccountInput,
    AnalysisFile,
    AnalysisPlan,
    replay_analysis,
)
from storymodel.artifacts import export_account, publish_json
from storymodel.extraction import prepare_extraction, resolve_extraction
from storymodel.friends import (
    FriendsRecallSheet,
    FriendsStoryboard,
    FriendsStoryboardRow,
    FriendsStudy,
    compile_friends_source,
    prepare_friends_study,
    unresolved_source_proposal,
)


def study() -> FriendsStudy:
    def event(number: int, scene: int, presentation: int, original: int, token: str):
        return FriendsStoryboardRow(
            event=number,
            episode=1,
            scene=scene,
            presentation_seconds=presentation,
            original_seconds=original,
            surface=token,
        )

    return FriendsStudy(
        storyboard=FriendsStoryboard(
            # The scales deliberately disagree in cardinality and keep both clocks.
            narrcomb_52=(
                event(1, 1, 0, 8, "AA"),
                event(2, 2, 10, 3, "BB"),
            ),
            more_ems_56=(
                event(1, 1, 0, 8, "AA"),
                event(2, 1, 5, 6, "BB"),
                event(3, 2, 10, 3, "AA"),
            ),
            source_scale="narrcomb-52",
        ),
        recalls=(
            FriendsRecallSheet(
                participant="synthetic-01", raw_text="AA. BB.", seconds_of_recall=12
            ),
        ),
        export_permission="allowed",
    )


def run(destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=False)
    prepared = prepare_friends_study(study())
    proposal = unresolved_source_proposal(prepared)
    result = compile_friends_source(prepared, proposal)
    publish_json(destination / "prepared.json", prepared.model_dump(mode="json"))
    publish_json(destination / "source-proposal.json", json.loads(proposal))
    publish_json(destination / "source-result.json", result.model_dump(mode="json"))
    export_account(destination / "source-account.json", result.account)
    recall_request = prepare_extraction(
        prepared.recalls[0].participant,
        prepared.recalls[0].recall.raw_text.encode("utf-8"),
        export_permission="allowed",
    )
    recall_reply = json.dumps(
        {
            "schema_version": "storymodel.extraction-proposal/v1",
            "units": [
                {
                    "u": unit.u,
                    "status": "not-attempted",
                    "reason": "synthetic Friends fixture deliberately makes no recall claim",
                }
                for unit in recall_request.recall.units
            ],
        }
    )
    recall_result = resolve_extraction(recall_request, recall_reply)
    publish_json(destination / "recall-request.json", recall_request.model_dump(mode="json"))
    publish_json(destination / "recall-result.json", recall_result.model_dump(mode="json"))
    export_account(destination / "recall-account.json", recall_result.account)
    plan = AnalysisPlan(
        source=AnalysisAccountInput(kind="account", file=AnalysisFile(path="source-account.json")),
        recall=AnalysisAccountInput(kind="account", file=AnalysisFile(path="recall-account.json")),
    )
    publish_json(destination / "analysis-plan.json", plan.model_dump(mode="json"))
    replay = replay_analysis(plan, destination)
    publish_json(destination / "analysis-result.json", replay.model_dump(mode="json"))
    assert prepared.recalls[0].timing_status == "no-within-recall-timestamps-observed"
    assert len(prepared.storyboard.narrcomb_52) == 2
    assert len(prepared.storyboard.more_ems_56) == 3
    print("Saved Friends synthetic analysis replay with an untimed recall artifact.")


if __name__ == "__main__":
    run(Path(sys.argv[1]))
