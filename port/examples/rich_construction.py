"""Recorded abstract states/circumstances; one source reused unchanged by two recall accounts."""

import sys
from pathlib import Path

from storymodel import (
    RichRecording,
    SourceRow,
    import_rich,
    pair_rich_accounts,
    prepare_rich_construction,
    replay_rich,
    situation_table,
)
from storymodel.artifacts import publish_json
from storymodel.model import Span, digest
from storymodel.serialization import canonical

destination = Path(sys.argv[1])
destination.mkdir(parents=True, exist_ok=False)
accounts = []
for key, side in (("source", "source"), ("recall-a", "recall"), ("recall-b", "recall")):
    request = prepare_rich_construction(
        key,
        side,
        "AA BB CC DD",
        (SourceRow(key="row", span=Span(start=0, end=11)),),
        export_permission="allowed",
    )

    def quote(start, end):
        return {
            "span": {"start": start, "end": end},
            "text": Span(start=start, end=end).slice(request.document.text),
        }

    reply = canonical(
        {
            "schema_version": "storymodel.rich-construction-proposal/v1",
            "rows": [
                {
                    "row": "row",
                    "status": "completed",
                    "contexts": [
                        {
                            "key": "held",
                            "kind": "believed",
                            "parent": "narrated",
                            "cue": quote(0, 2),
                            "scope": quote(3, 11),
                        }
                    ],
                    "entities": [{"key": "location", "mention": quote(6, 8)}],
                    "events": [
                        {
                            "key": "event",
                            "context": "narrated",
                            "evidence": quote(0, 2),
                            "predicate": quote(0, 2),
                        }
                    ],
                    "states": [
                        {
                            "key": "state",
                            "context": "held",
                            "evidence": quote(3, 11),
                            "predicate": quote(3, 5),
                            "polarity": "unknown",
                            "modality": "unknown",
                            "participants": [
                                {"entity": "location", "role": "location", "evidence": quote(6, 8)}
                            ],
                        }
                    ],
                    "circumstances": [
                        {
                            "key": "time",
                            "situation": "state",
                            "kind": "time",
                            "evidence": quote(6, 8),
                        },
                        {
                            "key": "manner",
                            "situation": "state",
                            "kind": "manner",
                            "evidence": quote(9, 11),
                        },
                    ],
                }
            ],
        }
    )
    directory = destination / key
    directory.mkdir()
    recording = RichRecording(
        request_sha256=digest(request.model_dump(mode="json")),
        origin="abstract diagnostic; no provider call",
        reply=reply,
    )
    publish_json(directory / "request.json", request.model_dump(mode="json"))
    publish_json(directory / "reply.json", recording.model_dump(mode="json"))
    import_rich(request, recording, directory / "recording")
    result = replay_rich(request, directory / "recording")
    assert result.resolutions[0].status == "admitted"
    publish_json(directory / "result.json", result.model_dump(mode="json"))
    publish_json(directory / "account.json", result.account.model_dump(mode="json"))
    accounts.append(result.account)
source = accounts[0]
before = source.model_dump_json()
for index, recall in enumerate(accounts[1:]):
    paired = pair_rich_accounts(source, recall)
    publish_json(destination / f"paired-{index}.json", paired.model_dump(mode="json"))
    publish_json(
        destination / f"situations-{index}.json",
        [row.model_dump(mode="json") for row in situation_table(paired)],
    )
    assert len(paired.states) == 2 and len(paired.circumstances) == 4
    assert all(i.status == "not-attempted" for i in paired.inventories)
assert source.model_dump_json() == before
print(
    canonical({"source_reused": 2, "source_unchanged": True, "detail_extraction": "not-attempted"})
)
