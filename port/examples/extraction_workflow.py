"""Raw recall -> recorded synthetic proposals -> account -> existing projections."""

import argparse
from pathlib import Path

from storymodel.analysis import project
from storymodel.artifacts import publish_json
from storymodel.extraction import request_digest
from storymodel.extraction_demo import example_proposal, example_request
from storymodel.extraction_workflow import ExtractionRecording, import_extraction, replay_extraction


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output_directory", type=Path)
    args = parser.parse_args()
    request = example_request()
    recording = ExtractionRecording(
        request_sha256=request_digest(request),
        origin=(
            "Synthetic illustrative extraction proposals over the admitted courier example; "
            "no model call."
        ),
        reply=example_proposal(),
    )
    root = args.output_directory
    root.mkdir(parents=True, exist_ok=False)
    (root / "recall.txt").write_bytes(request.recall.raw_text.encode())
    publish_json(root / "request.json", request.model_dump(mode="json"))
    publish_json(root / "reply.json", recording.model_dump(mode="json"))
    import_extraction(request, recording, root / "recording")
    result = replay_extraction(request, root / "recording")
    publish_json(root / "resolution.json", result.model_dump(mode="json"))
    publish_json(root / "account.json", result.account.model_dump(mode="json"))
    publish_json(root / "analysis.json", project(result.account).model_dump(mode="json"))
    print(f"Wrote {len(result.account.details)} details and their extraction receipts to {root}")


if __name__ == "__main__":
    main()
