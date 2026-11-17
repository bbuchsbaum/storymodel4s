"""Graph-free recorded mapping of abstract tokens; no linguistic accuracy claim."""

import json
import sys
from pathlib import Path

from storymodel import (
    MappingRecording,
    export_account,
    import_mapping,
    make_account,
    mapping_prompt,
    prepare_mapping,
    project,
    replay_mapping,
    verify_mapping,
)
from storymodel.artifacts import publish_json
from storymodel.direct_mapping import mapping_request_digest
from storymodel.model import Detail, Document, Inventory, Span, Support, Unit
from storymodel.serialization import canonical

destination = Path(sys.argv[1])
destination.mkdir(parents=True, exist_ok=False)
source = Document.create("abstract-source", "source", "AA BB")
recall = Document.create("abstract-recall", "recall", "AA BB")
row = Unit(kind="row", support=Support(document=source.id, spans=(Span(start=0, end=5),)))
unit = Unit(kind="recall", support=Support(document=recall.id, spans=(Span(start=0, end=5),)))
detail = Detail(unit=unit.id, kind="event", content="AA BB", support=unit.support, address="target")
account = make_account(
    (source, recall),
    units=(row, unit),
    details=(detail,),
    inventories=(Inventory(unit=unit.id, status="completed"),),
    export_permission="allowed",
)
request = prepare_mapping(account, unit.id)
prompt = json.loads(mapping_prompt(request))
reply = canonical(
    {
        "schema_version": "storymodel.mapping-proposal/v1",
        "packet_sha256": prompt["packet_sha256"],
        "details": [
            {
                "detail": "d0",
                "status": "attempted",
                "outcome": "resolved",
                "reason": "explicit abstract control",
                "hypotheses": [{"key": "h", "kind": "single", "targets": [row.id]}],
                "assessments": [
                    {
                        "hypothesis": "h",
                        "status": "completed",
                        "grade": "supported",
                        "reason": "identical abstract token control",
                        "evidence": [
                            {"document": source.id, "span": {"start": 0, "end": 5}, "text": "AA BB"}
                        ],
                    }
                ],
            }
        ],
    }
)
recording = MappingRecording(
    request_sha256=mapping_request_digest(request),
    origin="synthetic control; no provider call",
    reply=reply,
)
publish_json(destination / "request.json", request.model_dump(mode="json"))
publish_json(destination / "prompt.json", prompt)
publish_json(destination / "reply.json", recording.model_dump(mode="json"))
import_mapping(request, recording, destination / "recording")
result = replay_mapping(request, destination / "recording")
verify_mapping(request, reply, result)
publish_json(destination / "result.json", result.model_dump(mode="json"))
export_account(destination / "account.json", result.account)
views = project(result.account)
publish_json(destination / "views.json", views.model_dump(mode="json"))
assert views.source[0].coverage == "present"
print(
    canonical(
        {
            "details": len(views.recall),
            "coverage": views.source[0].coverage,
            "event_graph_required": False,
        }
    )
)
