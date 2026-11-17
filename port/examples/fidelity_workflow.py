"""Separate attributed grades over a reusable rich source and two mapped recalls.

The scripted grades are engineering controls, not measured assessor accuracy.
"""

import json
import subprocess
import sys
from pathlib import Path

from storymodel import (
    FidelityRecording,
    export_supported_account,
    fidelity_prompt,
    import_fidelity,
    prepare_fidelity,
    project,
    read_supported_account,
    replay_fidelity,
    verify_fidelity,
)
from storymodel.artifacts import publish_json
from storymodel.fidelity import fidelity_request_digest
from storymodel.serialization import canonical

destination = Path(sys.argv[1])
destination.mkdir(parents=True, exist_ok=False)
subprocess.run(
    [
        sys.executable,
        str(Path(__file__).with_name("rich_alignment_workflow.py")),
        str(destination / "mapping"),
    ],
    check=True,
)
coverage = {}
for name, grade in (("recall-a", "supported"), ("recall-b", "contradicted")):
    root = destination / name
    root.mkdir()
    mapping_path = destination / "mapping" / name / "account.json"
    before = mapping_path.read_bytes()
    account = read_supported_account(mapping_path)
    unit = next(u for u in account.units if u.kind == "recall")
    request = prepare_fidelity(account, unit.id)
    prompt = json.loads(fidelity_prompt(request))
    row = next(u for u in account.units if u.kind == "row")
    source = next(d for d in account.documents if d.id == row.support.document)
    raw = canonical(
        {
            "schema_version": "storymodel.fidelity-proposal/v1",
            "packet_sha256": prompt["packet_sha256"],
            "assessments": [
                {
                    "hypothesis": prompt["questions"][0]["key"],
                    "status": "completed",
                    "grade": grade,
                    "reason": "scripted distinct-grade control; no semantic accuracy claim",
                    "evidence": [
                        {
                            "document": source.id,
                            "span": s.model_dump(mode="json"),
                            "text": s.slice(source.text),
                        }
                        for s in row.support.spans
                    ],
                }
            ],
        }
    )
    recording = FidelityRecording(
        request_sha256=fidelity_request_digest(request),
        origin="abstract recorded assessment control/v1",
        reply=raw,
    )
    publish_json(root / "request.json", request.model_dump(mode="json"))
    publish_json(root / "prompt.json", prompt)
    publish_json(root / "reply.json", recording.model_dump(mode="json"))
    import_fidelity(request, recording, root / "recording")
    result = replay_fidelity(request, root / "recording")
    verify_fidelity(request, raw, result)
    assert result.decisions[0].status == "admitted"
    assert result.account.bindings[0].reference == account.bindings[0].reference
    assert result.account.details == account.details
    assert result.account.states == account.states
    assert result.account.circumstances == account.circumstances
    assert result.account.documents == account.documents
    assert mapping_path.read_bytes() == before
    before_views, after_views = project(account), project(result.account)
    assert before_views.source[0].coverage == "not-assessable"
    coverage[name] = after_views.source[0].coverage
    assert coverage[name] == ("present" if grade == "supported" else "absent")
    publish_json(root / "result.json", result.model_dump(mode="json"))
    export_supported_account(root / "account.json", result.account)
    publish_json(root / "analysis.json", after_views.model_dump(mode="json"))
print(
    canonical(
        {
            "source_reused": 2,
            "mapping_unchanged": True,
            "reference_unchanged": True,
            "conditional_coverage": coverage,
            "accuracy_claim": False,
        }
    )
)
