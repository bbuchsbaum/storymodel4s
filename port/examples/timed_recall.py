"""Build timed synthetic Sherlock recall readouts without opening media or scanners."""

import sys
from hashlib import sha256
from pathlib import Path

from sherlock_workflow import run as run_sherlock

from storymodel.analysis_workflow import replay_analysis_file
from storymodel.artifacts import publish_json
from storymodel.playback import MediaSource, TickInterval
from storymodel.recall_clock import RecordingQuery, UnitInterval, timed_recall
from storymodel.sherlock_recall import sherlock_recall_timing


def _query(plan: Path) -> RecordingQuery:
    replay = replay_analysis_file(plan)
    intake = replay.recall.request.intake
    timing = sherlock_recall_timing(intake, "princeton")
    recording = MediaSource(
        key="synthetic-recall-audio",
        side="recall",
        sha256=sha256(b"synthetic-recall-audio/v1").hexdigest(),
        edition="synthetic-sherlock-recall",
        stream="audio-0",
        clock="synthetic-recording-ticks",
        stream_kind="audio",
        start_tick="100",
        end_tick="120",
        seconds_per_tick="1/4",
    )
    return RecordingQuery(
        intake_sha256=timing.intake_sha256,
        clock="princeton",
        recording=recording,
        zero_tick="100",
        provenance="synthetic-sherlock-timed-recall/v1",
        bins=(TickInterval(start="100", end="108"), TickInterval(start="108", end="116")),
        # The retained example has one recall unit containing both lexical CSV cells.
        intervals=(UnitInterval(unit=1, native=TickInterval(start="100", end="116")),),
    )


def run(destination: Path) -> None:
    """Create two retained synthetic plans and their recording-clock readouts."""
    run_sherlock(destination)
    for number in (1, 2):
        plan = destination / f"analysis-{number}.json"
        query = _query(plan)
        result = timed_recall(plan, query)
        publish_json(
            destination / f"timed-recall-{number}-query.json", query.model_dump(mode="json")
        )
        publish_json(destination / f"timed-recall-{number}.json", result.model_dump(mode="json"))


if __name__ == "__main__":
    run(Path(sys.argv[1]))
