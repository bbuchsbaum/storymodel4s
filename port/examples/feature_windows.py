"""Build two synthetic Sherlock readouts from saved analyses and a tiny lexical norm."""

import sys
from pathlib import Path

from timed_recall import run as build_plans

from storymodel.alignment_method import CaptionEvidence, run_alignment_method
from storymodel.analysis_workflow import replay_analysis_file
from storymodel.artifacts import publish_json
from storymodel.feature_windows import Lexicon, feature_windows
from storymodel.recall_clock import RecordingQuery
from storymodel.serialization import digest


def run(destination: Path) -> None:
    build_plans(destination)
    norms = Lexicon(name="synthetic", units="score", entries={"AA": 0.0})
    publish_json(destination / "norms.json", norms.model_dump(mode="json"))
    for n in (1, 2):
        query = RecordingQuery.model_validate_json(
            (destination / f"timed-recall-{n}-query.json").read_bytes()
        )
        result = feature_windows(destination / f"analysis-{n}.json", query, norms)
        publish_json(destination / f"feature-windows-{n}.json", result.model_dump(mode="json"))

    plan = destination / "analysis-1.json"
    source = replay_analysis_file(plan).source.account
    # Independent abstract caption annotations, each bound to a source occurrence.
    captions = CaptionEvidence(
        source_sha256=digest(source.model_dump(mode="json")),
        captions={state.id: "BB" for state in source.states},
    )
    publish_json(destination / "captions.json", captions.model_dump(mode="json"))
    alignment = run_alignment_method(plan, captions)
    publish_json(destination / "caption-alignment.json", alignment.model_dump(mode="json"))


if __name__ == "__main__":
    run(Path(sys.argv[1]))
