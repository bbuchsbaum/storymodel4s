"""Friends adapter regressions: dual scales, two clocks, and no fabricated recall timing."""

import subprocess
import sys
from pathlib import Path

import pytest

from storymodel.analysis_workflow import read_analysis_plan, replay_analysis, verify_analysis
from storymodel.artifacts import read_account
from storymodel.friends import (
    FriendsRecallSheet,
    FriendsStoryboard,
    FriendsStoryboardRow,
    FriendsStudy,
    compile_friends_source,
    prepare_friends_study,
    unresolved_source_proposal,
)


def row(event, presentation, original, token="AA"):
    return FriendsStoryboardRow(
        event=event,
        episode=1,
        scene=event,
        presentation_seconds=presentation,
        original_seconds=original,
        surface=token,
    )


def fixture(source_scale="narrcomb-52"):
    return FriendsStudy(
        storyboard=FriendsStoryboard(
            narrcomb_52=(row(1, 0, 9, "AA"), row(2, 10, 2, "BB")),
            more_ems_56=(row(1, 0, 9, "AA"), row(2, 5, 6, "BB"), row(3, 10, 2, "AA")),
            source_scale=source_scale,
        ),
        recalls=(
            FriendsRecallSheet(
                participant="synthetic-01", raw_text="AA. BB.", seconds_of_recall=12
            ),
        ),
        export_permission="allowed",
    )


def test_preserves_competing_event_scales_and_original_presentation_clocks():
    prepared = prepare_friends_study(fixture())
    assert len(prepared.storyboard.narrcomb_52) == 2
    assert len(prepared.storyboard.more_ems_56) == 3
    assert prepared.storyboard.narrcomb_52[1].presentation_seconds == 10
    assert prepared.storyboard.narrcomb_52[1].original_seconds == 2
    assert [r.key for r in prepared.source_request.rows] == ["narrcomb-52:1", "narrcomb-52:2"]
    alternate = prepare_friends_study(fixture("more-ems-56"))
    assert [r.key for r in alternate.source_request.rows] == [
        "more-ems-56:1",
        "more-ems-56:2",
        "more-ems-56:3",
    ]


def test_crosswalk_rederives_exact_tolerance_ambiguous_and_unmatched_evidence():
    study = FriendsStudy(
        storyboard=FriendsStoryboard(
            narrcomb_52=(row(1, 0, 9), row(2, 11, 2), row(3, 20, 4)),
            more_ems_56=(row(1, 0, 9), row(2, 10, 6), row(3, 15, 5), row(4, 99, 1)),
            source_scale="narrcomb-52",
            crosswalk_tolerance_seconds=1,
        ),
        recalls=(FriendsRecallSheet(participant="synthetic-01", raw_text="AA."),),
    )
    entries = prepare_friends_study(study).crosswalk.entries
    assert [(entry.kind, entry.target_event) for entry in entries] == [
        ("exact", 1),
        ("tolerance", 2),
        ("unmatched", None),
        ("unmatched", None),
    ]
    assert entries[1].tolerance_candidates[0].delta_seconds == 1
    ambiguous = prepare_friends_study(
        fixture().model_copy(
            update={
                "storyboard": FriendsStoryboard(
                    narrcomb_52=(row(1, 0, 1), row(2, 10, 2)),
                    more_ems_56=(row(1, 5, 3),),
                    source_scale="narrcomb-52",
                    crosswalk_tolerance_seconds=5,
                )
            }
        )
    )
    entry = ambiguous.crosswalk.entries[0]
    assert entry.kind == "ambiguous" and entry.target_event is None
    assert [candidate.event for candidate in entry.tolerance_candidates] == [1, 2]


def test_recall_duration_never_becomes_a_recall_timestamp():
    prepared = prepare_friends_study(fixture())
    recall = prepared.recalls[0]
    assert recall.seconds_of_recall == 12
    assert recall.timing_status == "no-within-recall-timestamps-observed"
    assert not hasattr(recall, "onsets")
    assert len(recall.recall.units) == 2


def test_source_identity_binds_selected_rows_and_both_storyboard_clocks_not_participants():
    original = prepare_friends_study(fixture())
    changed_storyboard = FriendsStoryboard(
        narrcomb_52=(row(1, 0, 9, "AA"), row(2, 10, 99, "BB")),
        more_ems_56=fixture().storyboard.more_ems_56,
        source_scale="narrcomb-52",
    )
    changed = prepare_friends_study(fixture().model_copy(update={"storyboard": changed_storyboard}))
    assert original.source_request.document.text == changed.source_request.document.text
    assert original.source_rows_sha256 != changed.source_rows_sha256
    assert original.source_request.document.key != changed.source_request.document.key


def test_export_is_denied_by_default_and_prepared_receipt_rejects_forgery():
    denied = fixture().model_copy(update={"export_permission": "denied"})
    prepared = prepare_friends_study(denied)
    assert prepared.source_request.export_permission == "denied"
    forged = prepared.model_copy(
        update={
            "source_request": prepared.source_request.model_copy(
                update={
                    "document": prepared.source_request.document.model_copy(
                        update={"key": "foreign"}
                    )
                }
            )
        }
    )
    with pytest.raises(ValueError):
        compile_friends_source(forged, unresolved_source_proposal(prepared))


@pytest.mark.parametrize(
    "mutate",
    [
        lambda prepared: prepared.model_copy(
            update={"recalls": (prepared.recalls[0].model_copy(update={"seconds_of_recall": -1}),)}
        ),
        lambda prepared: prepared.model_copy(
            update={"recalls": (prepared.recalls[0], prepared.recalls[0])}
        ),
        lambda prepared: prepared.model_copy(update={"recalls": ()}),
    ],
)
def test_prepared_readback_refuses_forged_recall_duration_or_participants(mutate):
    prepared = prepare_friends_study(fixture())
    with pytest.raises(ValueError):
        compile_friends_source(mutate(prepared), unresolved_source_proposal(prepared))


def test_duplicate_or_nonascending_event_scales_refuse():
    with pytest.raises(ValueError, match="ascending"):
        FriendsStoryboard(
            narrcomb_52=(row(2, 10, 2), row(1, 0, 9)),
            more_ems_56=(row(1, 0, 9),),
            source_scale="narrcomb-52",
        )
    with pytest.raises(ValueError, match="repeats"):
        FriendsStoryboard(
            narrcomb_52=(row(1, 0, 1), row(1, 2, 3)),
            more_ems_56=(row(1, 0, 1),),
            source_scale="narrcomb-52",
        )


def test_saved_synthetic_source_artifact_exercises_real_admission():
    prepared = prepare_friends_study(fixture())
    result = compile_friends_source(prepared, unresolved_source_proposal(prepared))
    assert [resolution.status for resolution in result.resolutions] == ["partial", "partial"]
    assert len(result.account.units) == 2
    assert not result.account.events


def test_example_writes_replayable_python_artifacts(tmp_path):
    output = tmp_path / "friends"
    script = Path(__file__).resolve().parents[1] / "examples/friends_workflow.py"
    done = subprocess.run(
        [sys.executable, str(script), str(output)], capture_output=True, text=True
    )
    assert done.returncode == 0, done.stdout + done.stderr
    assert read_account(output / "source-account.json").units
    assert (output / "prepared.json").is_file() and (output / "source-result.json").is_file()
    plan = read_analysis_plan(output / "analysis-plan.json")
    replay = replay_analysis(plan, output)
    assert verify_analysis(plan, output, replay) == replay
    assert replay.stages == () and replay.recall_details == ()
