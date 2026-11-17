"""Raw recall through recorded extraction, sparse alignment and existing projections.

Reuses the already admitted courier example and synthetic proposal generator. No
new narrative passages or provider calls. The application-specific alias is explicit.
"""

import argparse
from pathlib import Path

from storymodel.alignment import AlignmentPolicy, PredicateAliases, align_account
from storymodel.analysis import project
from storymodel.artifacts import publish_json
from storymodel.extraction import request_digest
from storymodel.extraction_demo import example_proposal, example_request
from storymodel.extraction_workflow import ExtractionRecording, import_extraction, replay_extraction


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output_directory", type=Path)
    args = parser.parse_args()
    root = args.output_directory
    root.mkdir(parents=True, exist_ok=False)
    request = example_request()
    recording = ExtractionRecording(
        request_sha256=request_digest(request),
        origin="Synthetic extraction proposals over the admitted courier example; no model call.",
        reply=example_proposal(),
    )
    publish_json(root / "request.json", request.model_dump(mode="json"))
    import_extraction(request, recording, root / "recording")
    extracted = replay_extraction(request, root / "recording")
    policy = AlignmentPolicy(
        aliases=(
            PredicateAliases(
                terms=("left", "placing"),
                basis=(
                    "Illustrative courier placement equivalence for this synthetic example only; "
                    "not a general lexicon."
                ),
            ),
        )
    )
    result = align_account(extracted.account, policy)
    publish_json(root / "extraction.json", extracted.model_dump(mode="json"))
    publish_json(root / "input-account.json", extracted.account.model_dump(mode="json"))
    publish_json(root / "policy.json", policy.model_dump(mode="json"))
    publish_json(root / "alignment.json", result.model_dump(mode="json"))
    publish_json(root / "account.json", result.account.model_dump(mode="json"))
    publish_json(root / "analysis.json", project(result.account).model_dump(mode="json"))
    print(f"Wrote {len(result.details)} reference decisions with candidate evidence to {root}")


if __name__ == "__main__":
    main()
