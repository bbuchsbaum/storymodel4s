"""Build timed synthetic Sherlock recall readouts without opening media or scanners."""

import sys
from hashlib import sha256
from pathlib import Path

from sherlock_workflow import run as run_sherlock

from storymodel.analysis_workflow import replay_analysis_file
from storymodel.artifacts import export_supported_account, publish_json
from storymodel.concentration import (
    ConcentrationQuery,
    CoverageTarget,
    ShortestWindow,
    source_concentration,
)
from storymodel.exposure import ExposureQuery, WordIntervals, recall_exposure
from storymodel.local_evidence import LocalEvidenceSpec, RecallSelection
from storymodel.local_reference import LocalReferenceSpec
from storymodel.playback import MediaSource, TickInterval
from storymodel.recall_clock import RecordingQuery, UnitInterval, timed_recall
from storymodel.sherlock_composition import compose_sherlock_source
from storymodel.sherlock_recall import sherlock_recall_timing
from storymodel.source_grid import (
    LocalScoreGridQuery,
    SourceGridQuery,
    SupportDeclaration,
    TransportGridQuery,
    project_source_grid,
)
from storymodel.transport import TransportSpec


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
        # The first example has two lexical observations. Supply an independently
        # declared end for the first, leaving the second as an onset-only event.
        if number == 1:
            first_word = next(iter(result.word_onsets))
            exposure_query = ExposureQuery(
                **query.model_dump(exclude={"schema_version", "bins", "intervals"}),
                mapping_stage="mapping",
                window=TickInterval(start="100", end="116"),
                word_intervals=WordIntervals(
                    recording_id=query.recording.id,
                    provenance="synthetic-word-interval/v1",
                    by_word={first_word: TickInterval(start="105", end="108")},
                ),
            )
            exposure = recall_exposure(plan, exposure_query)
            publish_json(
                destination / "exposure-query.json", exposure_query.model_dump(mode="json")
            )
            publish_json(destination / "exposure.json", exposure.model_dump(mode="json"))
            replay = replay_analysis_file(plan)
            source = compose_sherlock_source(replay.source.request.intake)
            # The receipt identifies the graph row that owns this measured interval.
            first = replay.source.construction.construction.resolutions[0]
            axis = next(r for r in source.rows if r.key == first.row).anchors[0].native.source
            grid_query = SourceGridQuery(
                inference=LocalEvidenceSpec(
                    units=tuple(RecallSelection(unit=r.inventory.unit) for r in exposure.units)
                ),
                selection="part",
                axis=axis,
                bins=(
                    TickInterval(start="0", end="10000"),
                    TickInterval(start="10000", end="20000"),
                ),
                declarations={
                    first.unit: SupportDeclaration(
                        assumption="supplied-support-contains-referent",
                        allocation="uniform-intervals",
                    )
                },
                exposure=exposure_query,
            )
            grid = project_source_grid(plan, grid_query)
            publish_json(destination / "source-grid-query.json", grid_query.model_dump(mode="json"))
            publish_json(destination / "source-grid.json", grid.model_dump(mode="json"))
            # Level 1 is the complete set of constructed annotation rows in this example.
            reference = LocalReferenceSpec(level=1, evidence=grid_query.inference)
            local_query = LocalScoreGridQuery(
                reference=reference,
                selection=grid_query.selection,
                axis=axis,
                bins=grid_query.bins,
                declarations=grid_query.declarations,
                exposure=exposure_query,
            )
            scores = project_source_grid(plan, local_query)
            export_supported_account(destination / "local-source.json", replay.source.account)
            export_supported_account(destination / "local-recall.json", replay.recall.account)
            # Hold the comparison population fixed at the same six row candidates.
            transport_spec = TransportSpec(
                units=tuple(
                    selected.model_copy(update={"targets": scores.reference.universe})
                    for selected in reference.evidence.units
                )
            )
            transport_query = TransportGridQuery(
                transport=transport_spec,
                selection=local_query.selection,
                axis=axis,
                bins=local_query.bins,
                declarations=local_query.declarations,
                exposure=exposure_query,
            )
            transported = project_source_grid(plan, transport_query)
            concentration_query = ConcentrationQuery(
                grid=transport_query,
                unit=transport_spec.units[0].unit,
                operation=ShortestWindow(
                    target=CoverageTarget(basis="share-of-allocated-source", value="1/2")
                ),
            )
            concentration = source_concentration(plan, concentration_query)
            for filename, artifact in (
                ("concentration-query.json", concentration_query),
                ("concentration.json", concentration),
                ("transport-spec.json", transport_spec),
                ("transport.json", transported.transport),
                ("transport-grid-query.json", transport_query),
                ("transport-grid.json", transported),
                ("local-reference-spec.json", reference),
                ("local-reference.json", scores.reference),
                ("local-score-grid-query.json", local_query),
                ("local-score-grid.json", scores),
            ):
                publish_json(destination / filename, artifact.model_dump(mode="json"))


if __name__ == "__main__":
    run(Path(sys.argv[1]))
