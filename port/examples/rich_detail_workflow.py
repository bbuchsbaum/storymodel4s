"""Recorded abstract states -> recall details -> reference/fidelity -> three views.

One source is reused across two recalls. Synthetic proposals test data flow, not accuracy.
"""

import sys
from pathlib import Path

from storymodel import (
    DetailRecording,
    MappingRecording,
    RichRecording,
    SourceRow,
    export_supported_account,
    import_details,
    import_mapping,
    import_rich,
    mapping_prompt,
    pair_rich_accounts,
    prepare_details,
    prepare_mapping,
    prepare_rich_construction,
    project,
    replay_details,
    replay_mapping,
    replay_rich,
)
from storymodel.artifacts import publish_json
from storymodel.detail_admission import detail_request_digest
from storymodel.direct_mapping import mapping_request_digest
from storymodel.model import Span
from storymodel.rich_construction import rich_construction_request_digest
from storymodel.serialization import canonical, strict_json

destination = Path(sys.argv[1])
destination.mkdir(parents=True, exist_ok=False)


def construct(key, side):
    directory = destination / key
    directory.mkdir()
    request = prepare_rich_construction(
        key,
        side,
        "AA BB",
        (SourceRow(key="row", span=Span(start=0, end=5)),),
        export_permission="allowed",
    )
    raw = canonical(
        {
            "schema_version": "storymodel.rich-construction-proposal/v1",
            "rows": [
                {
                    "row": "row",
                    "status": "completed",
                    "entities": [
                        {"key": "place", "mention": {"span": {"start": 3, "end": 5}, "text": "BB"}}
                    ],
                    "states": [
                        {
                            "key": "condition",
                            "context": "narrated",
                            "evidence": {"span": {"start": 0, "end": 5}, "text": "AA BB"},
                            "predicate": {"span": {"start": 0, "end": 2}, "text": "AA"},
                            "participants": [
                                {
                                    "entity": "place",
                                    "role": "location",
                                    "evidence": {"span": {"start": 3, "end": 5}, "text": "BB"},
                                }
                            ],
                        }
                    ],
                    "circumstances": [
                        {
                            "key": "how",
                            "situation": "condition",
                            "kind": "manner",
                            "evidence": {"span": {"start": 3, "end": 5}, "text": "BB"},
                        }
                    ],
                }
            ],
        }
    )
    recording = RichRecording(
        request_sha256=rich_construction_request_digest(request),
        origin="abstract control; no provider",
        reply=raw,
    )
    publish_json(directory / "construction-request.json", request.model_dump(mode="json"))
    publish_json(directory / "construction-reply.json", recording.model_dump(mode="json"))
    import_rich(request, recording, directory / "construction-recording")
    result = replay_rich(request, directory / "construction-recording")
    publish_json(directory / "construction-result.json", result.model_dump(mode="json"))
    return result.account


source = construct("source", "source")
before = source.model_dump_json()
export_supported_account(destination / "source/account.json", source)
for name in ("recall-a", "recall-b"):
    recall = construct(name, "recall")
    directory = destination / name
    paired = pair_rich_accounts(source, recall)
    export_supported_account(directory / "paired.json", paired)
    assert {r.coverage for r in project(paired).source} == {"not-assessable"}
    detail_request = prepare_details(paired, (recall.units[0].id,))
    detail_reply = canonical(
        {
            "schema_version": "storymodel.detail-proposal/v1",
            "request_sha256": detail_request_digest(detail_request),
            "units": [
                {
                    "unit": recall.units[0].id,
                    "status": "completed",
                    "reason": "explicit control inventory",
                    "details": [
                        {
                            "key": "attribute",
                            "kind": "attribute",
                            "address": "target",
                            "discourse": "content",
                            "situation": recall.states[0].id,
                            "quotes": [{"span": {"start": 0, "end": 2}, "text": "AA"}],
                        }
                    ],
                }
            ],
        }
    )
    detail_recording = DetailRecording(
        request_sha256=detail_request_digest(detail_request),
        origin="abstract detail control",
        reply=detail_reply,
    )
    publish_json(directory / "detail-request.json", detail_request.model_dump(mode="json"))
    publish_json(directory / "detail-reply.json", detail_recording.model_dump(mode="json"))
    import_details(detail_request, detail_recording, directory / "detail-recording")
    details = replay_details(detail_request, directory / "detail-recording")
    publish_json(directory / "detail-result.json", details.model_dump(mode="json"))
    export_supported_account(directory / "detailed.json", details.account)
    assert (
        details.account.states == paired.states
        and details.account.circumstances == paired.circumstances
    )
    request = prepare_mapping(details.account, recall.units[0].id)
    reply = canonical(
        {
            "schema_version": "storymodel.mapping-proposal/v1",
            "packet_sha256": strict_json(mapping_prompt(request))["packet_sha256"],
            "details": [
                {
                    "detail": "d0",
                    "status": "attempted",
                    "outcome": "resolved",
                    "reason": "explicit abstract target",
                    "hypotheses": [{"key": "h", "kind": "single", "targets": [source.units[0].id]}],
                    "assessments": [
                        {
                            "hypothesis": "h",
                            "status": "completed",
                            "grade": "supported",
                            "reason": "exact token control",
                            "evidence": [
                                {
                                    "document": source.documents[0].id,
                                    "span": {"start": 0, "end": 2},
                                    "text": "AA",
                                }
                            ],
                        }
                    ],
                }
            ],
        }
    )
    recording = MappingRecording(
        request_sha256=mapping_request_digest(request),
        origin="abstract mapping control",
        reply=reply,
    )
    publish_json(directory / "mapping-request.json", request.model_dump(mode="json"))
    publish_json(directory / "mapping-reply.json", recording.model_dump(mode="json"))
    import_mapping(request, recording, directory / "mapping-recording")
    mapped = replay_mapping(request, directory / "mapping-recording")
    publish_json(directory / "mapping-result.json", mapped.model_dump(mode="json"))
    export_supported_account(directory / "account.json", mapped.account)
    assert (
        mapped.account.states == paired.states
        and mapped.account.circumstances == paired.circumstances
    )
    views = project(mapped.account)
    assert views.source[0].coverage == "present" and views.counts.internal == 1
    publish_json(directory / "views.json", views.model_dump(mode="json"))
assert source.model_dump_json() == before
print(
    canonical(
        {
            "source_reused": 2,
            "source_unchanged": True,
            "rich_collections_preserved": True,
            "coverage": "present",
            "detail_inventory": "completed",
        }
    )
)
