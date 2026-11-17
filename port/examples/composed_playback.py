"""Exact synthetic playback composition, including retained Sherlock row and scene inputs."""

import sys
from pathlib import Path

from sherlock_workflow import source_inputs

from storymodel.artifacts import publish_json
from storymodel.composition import (
    CompositionPart,
    compose_support,
    concatenate_playback,
    inverse_support,
    project_composition,
)
from storymodel.playback import MediaSource, PlaybackSupport, TickInterval, union_playback
from storymodel.prepared_construction import prepare_sherlock_construction
from storymodel.sherlock_composition import compose_sherlock_source, project_sherlock_composition
from storymodel.sherlock_source import prepare_sherlock_source


def run(destination):
    destination.mkdir(parents=True, exist_ok=False)
    a = MediaSource(
        key="AA",
        side="source",
        sha256="a" * 64,
        edition="synthetic",
        stream="AA",
        clock="AA",
        stream_kind="picture",
        start_tick="10",
        end_tick="16",
        seconds_per_tick="1/3",
    )
    b = MediaSource(
        key="BB",
        side="source",
        sha256="b" * 64,
        edition="synthetic",
        stream="BB",
        clock="BB",
        stream_kind="picture",
        start_tick="0",
        end_tick="8",
        seconds_per_tick="1/4",
    )
    composition = concatenate_playback(
        "AA-BB-AA",
        (a, b),
        tuple(
            CompositionPart(source=m.id, native=TickInterval(start=m.start_tick, end=m.end_tick))
            for m in (a, b, a)
        ),
    )
    anchors = tuple(
        compose_support(
            composition,
            segment.occurrence,
            PlaybackSupport(
                source=a.id,
                intervals=(TickInterval(start="11", end="12"),),
            ),
        )
        for segment in (composition.segments[0], composition.segments[2])
    )
    selected = union_playback(anchor.composed for anchor in anchors)
    bins = tuple(segment.target for segment in composition.segments)
    generic = project_composition(composition, selected, bins)
    publish_json(destination / "composition.json", composition.model_dump(mode="json"))
    publish_json(destination / "anchors.json", [a.model_dump(mode="json") for a in anchors])
    publish_json(
        destination / "inverse.json",
        [item.model_dump(mode="json") for item in inverse_support(composition, selected)],
    )
    publish_json(destination / "generic-readout.json", generic.model_dump(mode="json"))

    raw, repair = source_inputs()
    source = prepare_sherlock_source("source", raw, repair)
    prepared = prepare_sherlock_construction(source, export_permission="allowed")
    sherlock = compose_sherlock_source(source, export_permission="allowed")
    bins = (
        TickInterval(start="0", end="75000"),
        TickInterval(start="75000", end="76000"),
        TickInterval(start="76000", end="106000"),
    )
    publish_json(destination / "source-prepared.json", prepared.model_dump(mode="json"))
    publish_json(destination / "sherlock-composition.json", sherlock.model_dump(mode="json"))
    publish_json(destination / "bins.json", [b.model_dump(mode="json") for b in bins])
    publish_json(
        destination / "sherlock-readout.json",
        project_sherlock_composition(
            sherlock,
            bins=bins,
        ).model_dump(mode="json"),
    )
    publish_json(
        destination / "point-readout.json",
        project_sherlock_composition(
            sherlock,
            ("sherlock:row:0003",),
            bins,
        ).model_dump(mode="json"),
    )


if __name__ == "__main__":
    run(Path(sys.argv[1]))
