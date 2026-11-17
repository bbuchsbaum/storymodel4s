"""Exact raw study inputs through recorded construction, mapping and conditional fidelity.

AA/BB/CC/DD are abstract token controls, not narrative or participant text. Scripted
proposals test contracts and refusal behavior; they make no semantic accuracy claim.
"""

import json
import sys
from pathlib import Path

from storymodel.analysis_workflow import (
    AnalysisAccountInput,
    AnalysisFile,
    AnalysisPlan,
    AnalysisStage,
    replay_analysis,
)
from storymodel.artifacts import publish_json
from storymodel.detail_admission import detail_request_digest, prepare_details
from storymodel.detail_workflow import DetailRecording, import_details, replay_details
from storymodel.direct_mapping import mapping_prompt, mapping_request_digest, prepare_mapping
from storymodel.direct_workflow import MappingRecording, import_mapping, replay_mapping
from storymodel.fidelity import fidelity_prompt, fidelity_request_digest, prepare_fidelity
from storymodel.fidelity_workflow import FidelityRecording, import_fidelity, replay_fidelity
from storymodel.prepared_construction import (
    PreparedConstructionRecording,
    import_prepared_construction,
    prepare_construction,
    replay_prepared_construction,
)
from storymodel.rich_account import pair_rich_accounts
from storymodel.serialization import canonical, digest


def construction_reply(prepared):
    request = prepared.request
    rows = []
    for row in request.rows:
        start = row.span.start

        def quote(begin, end):
            from storymodel.model import Span

            span = Span(start=start + begin, end=start + end)
            return {"span": span.model_dump(mode="json"), "text": span.slice(request.document.text)}

        rows.append(
            {
                "row": row.key,
                "status": "completed",
                "entities": [{"key": "place", "mention": quote(3, 5)}],
                "states": [
                    {
                        "key": "condition",
                        "context": "narrated",
                        "evidence": quote(0, 5),
                        "predicate": quote(0, 2),
                        "participants": [
                            {"entity": "place", "role": "location", "evidence": quote(3, 5)}
                        ],
                    }
                ],
            }
        )
    return canonical({"schema_version": "storymodel.rich-construction-proposal/v1", "rows": rows})


def construct(directory, key, side, raw):
    directory.mkdir()
    (directory / "raw-input").write_bytes(raw)
    prepared = prepare_construction(key, side, raw, export_permission="allowed")
    recording = PreparedConstructionRecording(
        prepared_sha256=digest(prepared.model_dump(mode="json")),
        origin="abstract intake control",
        reply=construction_reply(prepared),
    )
    publish_json(directory / "prepared.json", prepared.model_dump(mode="json"))
    publish_json(directory / "reply.json", recording.model_dump(mode="json"))
    import_prepared_construction(prepared, recording, directory / "recording")
    result = replay_prepared_construction(prepared, directory / "recording")
    publish_json(directory / "construction.json", result.model_dump(mode="json"))
    return result.account


def input_for(directory):
    return AnalysisAccountInput(
        kind="prepared-rich",
        file=AnalysisFile(path=f"{directory}/prepared.json"),
        recording=f"{directory}/recording",
    )


def run(destination):
    destination.mkdir(parents=True, exist_ok=False)
    source_raw = canonical(
        {
            "schema_version": "storymodel.source-annotations/v1",
            "parts": [
                {"key": "b", "ordinal": 2},
                {"key": "a", "ordinal": 1},
            ],
            "rows": [
                {"key": "second", "part": "b", "ordinal": 1, "text": "CC DD"},
                {"key": "first", "part": "a", "ordinal": 1, "text": "AA BB"},
            ],
        }
    ).encode()
    source = construct(destination / "source", "source", "source", source_raw)
    original = source.model_dump_json()
    for number, raw in ((1, b"AA BB\r\n"), (2, b"AA BB\n")):
        relative = f"recall-{number}"
        root = destination / relative
        # Deliberately identical keys and canonical content: raw lineage alone differs.
        recall = construct(root, "recall", "recall", raw)
        paired = pair_rich_accounts(source, recall)
        unit = recall.units[0].id
        detail_request = prepare_details(paired, (unit,))
        detail_reply = canonical(
            {
                "schema_version": "storymodel.detail-proposal/v1",
                "request_sha256": detail_request_digest(detail_request),
                "units": [
                    {
                        "unit": unit,
                        "status": "completed",
                        "reason": "abstract inventory control",
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
        import_details(
            detail_request,
            DetailRecording(
                request_sha256=detail_request_digest(detail_request),
                origin="abstract inventory control",
                reply=detail_reply,
            ),
            root / "details-recording",
        )
        detailed = replay_details(detail_request, root / "details-recording")
        mapping = prepare_mapping(detailed.account, unit)
        target = next(u for u in source.units if u.support.spans[0].start == 0)
        mapping_reply = canonical(
            {
                "schema_version": "storymodel.mapping-proposal/v1",
                "packet_sha256": json.loads(mapping_prompt(mapping))["packet_sha256"],
                "details": [
                    {
                        "detail": "d0",
                        "status": "attempted",
                        "outcome": "resolved",
                        "reason": "abstract reference control",
                        "hypotheses": [{"key": "h", "kind": "single", "targets": [target.id]}],
                    }
                ],
            }
        )
        import_mapping(
            mapping,
            MappingRecording(
                request_sha256=mapping_request_digest(mapping),
                origin="abstract reference control",
                reply=mapping_reply,
            ),
            root / "mapping-recording",
        )
        mapped = replay_mapping(mapping, root / "mapping-recording")
        fidelity = prepare_fidelity(mapped.account, unit)
        fidelity_reply = canonical(
            {
                "schema_version": "storymodel.fidelity-proposal/v1",
                "packet_sha256": json.loads(fidelity_prompt(fidelity))["packet_sha256"],
                "assessments": [
                    {
                        "hypothesis": "a0",
                        "status": "completed",
                        "grade": "supported",
                        "reason": "abstract exact-quote versus mismatched-quote control",
                        "evidence": [
                            {
                                "document": source.documents[0].id,
                                "span": {"start": 0, "end": 2},
                                "text": "AA" if number == 1 else "ZZ",
                            }
                        ],
                    }
                ],
            }
        )
        import_fidelity(
            fidelity,
            FidelityRecording(
                request_sha256=fidelity_request_digest(fidelity),
                origin="abstract grade control",
                reply=fidelity_reply,
            ),
            root / "fidelity-recording",
        )
        assessed = replay_fidelity(fidelity, root / "fidelity-recording")
        assert assessed.decisions[0].status == ("admitted" if number == 1 else "rejected")
        stages = []
        for kind, request in (
            ("details", detail_request),
            ("mapping", mapping),
            ("fidelity", fidelity),
        ):
            publish_json(root / f"{kind}-request.json", request.model_dump(mode="json"))
            stages.append(
                AnalysisStage(
                    name=kind,
                    kind=kind,
                    request=AnalysisFile(path=f"{relative}/{kind}-request.json"),
                    recording=f"{relative}/{kind}-recording",
                )
            )
        plan = AnalysisPlan(
            schema_version="storymodel.analysis-plan/v2",
            source=input_for("source"),
            recall=input_for(relative),
            stages=tuple(stages),
        )
        publish_json(destination / f"analysis-{number}.json", plan.model_dump(mode="json"))
        result = replay_analysis(plan, destination)
        assert result.account == assessed.account
        publish_json(destination / f"analysis-{number}-result.json", result.model_dump(mode="json"))
        assert source.model_dump_json() == original
    print(canonical({"source_unchanged": True, "recalls": 2, "raw_lineage_distinct": True}))


if __name__ == "__main__":
    run(Path(sys.argv[1]))
