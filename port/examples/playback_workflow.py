"""Document-free native graph, exact support readout and reuse across two text recalls.

The media bytes and semantic labels are abstract controls, not a playable film or
evidence of audiovisual recognition. All native envelopes are explicitly supplied.
"""

import json
import sys
from hashlib import sha256
from pathlib import Path

from storymodel import (
    MediaSource,
    PlaybackObservation,
    PlaybackRecording,
    PlaybackSupport,
    RichRecording,
    SourceRow,
    TickInterval,
    export_supported_account,
    import_playback,
    import_rich,
    pair_rich_accounts,
    prepare_playback,
    prepare_rich_construction,
    replay_playback,
    replay_rich,
    situation_playback,
    situation_table,
    verify_playback,
    verify_situation_playback,
)
from storymodel.artifacts import publish_json
from storymodel.model import Span
from storymodel.serialization import canonical, digest


def main(destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=False)
    media_bytes = bytes(range(64))
    method_bytes = b"supplied abstract observation envelope/v1"
    (destination / "control.media").write_bytes(media_bytes)
    (destination / "observation-method.txt").write_bytes(method_bytes)
    source = MediaSource(
        key="native-control",
        side="source",
        sha256=sha256(media_bytes).hexdigest(),
        edition="control-edition",
        stream="control-picture",
        clock="primary",
        stream_kind="picture",
        start_tick="-10",
        end_tick="100",
        seconds_per_tick="1/3",
    )

    def support(
        intervals: tuple[tuple[int, int], ...] = (), points: tuple[int, ...] = ()
    ) -> PlaybackSupport:
        return PlaybackSupport(
            source=source.id,
            intervals=tuple(TickInterval(start=str(a), end=str(b)) for a, b in intervals),
            points=tuple(str(p) for p in points),
        )

    def wire(
        intervals: tuple[tuple[int, int], ...] = (), points: tuple[int, ...] = ()
    ) -> dict[str, object]:
        return support(intervals, points).model_dump(mode="json")

    observations = (
        PlaybackObservation(
            key="observation",
            support=support(((0, 10), (20, 30)), (10,)),
            origin="abstract-control",
            procedure="supplied-observation/v1",
            input_sha256=(sha256(method_bytes).hexdigest(),),
        ),
    )
    request = prepare_playback(source, observations, export_permission="allowed")
    proposals = {
        "schema_version": "storymodel.playback-proposals/v1",
        "request_sha256": digest(request.model_dump(mode="json")),
        "observations": [
            {
                "observation": "observation",
                "status": "completed",
                "contexts": [
                    {
                        "key": "held",
                        "kind": "believed",
                        "parent": "narrated",
                        "cue": wire(points=(0,)),
                        "scope": wire(((2, 8), (20, 28))),
                    }
                ],
                "entities": [{"key": "entity", "label": "AA", "evidence": wire(points=(3,))}],
                "situations": [
                    {
                        "key": "event",
                        "kind": "event",
                        "context": "held",
                        "predicate": "BB",
                        "evidence": wire(((3, 5), (22, 25)), (4,)),
                        "participants": [
                            {"entity": "entity", "role": "agent", "evidence": wire(points=(4,))}
                        ],
                    },
                    {
                        "key": "state",
                        "kind": "state",
                        "context": "held",
                        "predicate": "CC",
                        "evidence": wire(points=(22,)),
                        "participants": [
                            {
                                "entity": "entity",
                                "role": "experiencer",
                                "evidence": wire(points=(22,)),
                            }
                        ],
                    },
                ],
            }
        ],
    }
    raw = canonical(proposals) + "\n"
    recording = PlaybackRecording(
        request_sha256=digest(request.model_dump(mode="json")), origin="supplied control", reply=raw
    )
    import_playback(request, recording, destination / "recording")
    result = replay_playback(request, destination / "recording")
    verify_playback(request, raw, result)
    assert [r.status for r in result.resolutions] == ["admitted"]
    account = result.account
    assert not account.documents
    original = account.model_dump_json()
    bins = tuple(TickInterval(start=str(i), end=str(i + 10)) for i in (0, 10, 20))
    event = situation_playback(account, account.events[0].id, source.id, bins)
    state = situation_playback(account, account.states[0].id, source.id, bins)
    assert event.projection.duration_seconds == "5/3"
    assert state.projection.duration_seconds == "0/1"
    assert event.context.kind == state.context.kind == "believed"
    verify_situation_playback(account, account.events[0].id, source.id, bins, event)
    for name, data in (
        ("media-source", source.model_dump(mode="json")),
        ("observations", [o.model_dump(mode="json") for o in observations]),
        ("request", request.model_dump(mode="json")),
        ("proposals", proposals),
        ("construction", result.model_dump(mode="json")),
        ("bins", [b.model_dump(mode="json") for b in bins]),
        ("event-playback", event.model_dump(mode="json")),
        ("state-playback", state.model_dump(mode="json")),
        ("source-situations", [r.model_dump(mode="json") for r in situation_table(account)]),
    ):
        publish_json(destination / f"{name}.json", data)
    export_supported_account(destination / "source-account.json", account)
    for number in (1, 2):
        directory = destination / f"recall-{number}"
        directory.mkdir()
        text = "AA BB"
        recall_request = prepare_rich_construction(
            f"recall-{number}",
            "recall",
            text,
            (SourceRow(key="row", span=Span(start=0, end=5)),),
            full_contexts=True,
            export_permission="allowed",
        )

        def quote(start: int, end: int) -> dict[str, object]:
            return {"span": {"start": start, "end": end}, "text": text[start:end]}

        recall_raw = (
            canonical(
                {
                    "schema_version": "storymodel.rich-construction-proposal/v2",
                    "rows": [
                        {
                            "row": "row",
                            "status": "completed",
                            "entities": [{"key": "entity", "mention": quote(0, 2)}],
                            "events": [
                                {
                                    "key": "event",
                                    "context": "narrated",
                                    "predicate": quote(3, 5),
                                    "evidence": quote(0, 5),
                                    "participants": [
                                        {
                                            "entity": "entity",
                                            "role": "agent",
                                            "evidence": quote(0, 2),
                                        }
                                    ],
                                }
                            ],
                        }
                    ],
                }
            )
            + "\n"
        )
        import_rich(
            recall_request,
            RichRecording(
                request_sha256=digest(recall_request.model_dump(mode="json")),
                origin="abstract recall control",
                reply=recall_raw,
            ),
            directory / "recording",
        )
        recalled = replay_rich(recall_request, directory / "recording")
        assert all(r.status == "admitted" for r in recalled.resolutions)
        publish_json(directory / "request.json", recall_request.model_dump(mode="json"))
        publish_json(directory / "construction.json", recalled.model_dump(mode="json"))
        export_supported_account(directory / "account.json", recalled.account)
        paired = pair_rich_accounts(account, recalled.account)
        export_supported_account(destination / f"paired-{number}.json", paired)
        paired_readout = situation_playback(paired, account.events[0].id, source.id, bins)
        assert paired_readout.projection == event.projection
        assert paired_readout.account_sha256 != event.account_sha256
        publish_json(
            destination / f"paired-playback-{number}.json", paired_readout.model_dump(mode="json")
        )
        assert account.model_dump_json() == original
    print(
        json.dumps(
            {
                "source_unchanged": True,
                "source_documents": 0,
                "event_duration": "5/3",
                "point_state_duration": "0/1",
            }
        )
    )


if __name__ == "__main__":
    main(Path(sys.argv[1]))
