"""Run the raw-intake workflow with already admitted synthetic inputs; no model calls."""

import argparse
from pathlib import Path

from storymodel.artifacts import publish_json
from storymodel.intake_workflow import prepare_time_study
from storymodel.source_intake import NativeSource
from storymodel.study_workflow import (
    ImportedReplies,
    import_time_replies,
    read_request,
    replay_time_study,
    request_digest,
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output_directory", type=Path)
    args = parser.parse_args()
    fixtures = Path(__file__).resolve().parents[1] / "tests/fixtures"
    supplied = read_request(fixtures / "time-study-request.json")
    # Existing admitted phrases, unchanged; blank lines explicitly separate paragraphs.
    raw = "\n\n".join(u.text for u in supplied.recall.units).encode("utf-8")
    source = NativeSource.model_validate_json((fixtures / "native-time-source.json").read_bytes())
    prepared = prepare_time_study(source, supplied.recall.key, raw, export_permission="allowed")
    request = prepared.request
    assert [u.text for u in request.recall.units] == [u.text for u in supplied.recall.units]
    assert request.source == supplied.source
    replies = ImportedReplies.model_validate_json(
        (fixtures / "time-study-replies.json").read_bytes()
    )
    # This manufactures a new synthetic example, not a relabelling of real provider recordings.
    replies = replies.model_copy(
        update={
            "origin": (
                "Existing synthetic Scala answer constants applied to a new raw-intake example; "
                "no live provider execution."
            ),
            "request_sha256": request_digest(request),
        }
    )
    destination = args.output_directory
    destination.mkdir(parents=True, exist_ok=False)
    (destination / "recall.txt").write_bytes(raw)
    publish_json(destination / "source.json", source.model_dump(mode="json"))
    publish_json(destination / "prepared.json", prepared.model_dump(mode="json"))
    publish_json(destination / "replies.json", replies.model_dump(mode="json"))
    import_time_replies(request, replies, destination / "recording")
    result = replay_time_study(request, destination / "recording")
    publish_json(destination / "result.json", result.model_dump(mode="json"))
    print(
        f"Prepared {len(prepared.recall.units)} recall units; wrote {destination / 'result.json'}"
    )


if __name__ == "__main__":
    main()
