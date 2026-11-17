"""Recorded abstract state construction -> detail admission -> reference diagnostics.

Different declared polarities preserve reference but earn no fidelity or supported coverage.
"""

import sys
from pathlib import Path

from storymodel import (
    SourceRow,
    admit_details,
    align_account,
    compile_rich_construction,
    export_supported_account,
    pair_rich_accounts,
    prepare_details,
    prepare_rich_construction,
    project,
    verify_alignment,
)
from storymodel.artifacts import publish_json
from storymodel.detail_admission import detail_request_digest
from storymodel.model import Span
from storymodel.serialization import canonical

destination = Path(sys.argv[1])
destination.mkdir(parents=True, exist_ok=False)


def construct(key, side, polarity):
    request = prepare_rich_construction(
        key,
        side,
        "AA BB",
        (SourceRow(key="row", span=Span(start=0, end=5)),),
        export_permission="allowed",
    )
    aa = {"span": {"start": 0, "end": 2}, "text": "AA"}
    bb = {"span": {"start": 3, "end": 5}, "text": "BB"}
    packet = {
        "schema_version": "storymodel.rich-construction-proposal/v1",
        "rows": [
            {
                "row": "row",
                "status": "completed",
                "entities": [{"key": "place", "mention": bb}],
                "states": [
                    {
                        "key": "s",
                        "context": "narrated",
                        "polarity": polarity,
                        "evidence": {"span": {"start": 0, "end": 5}, "text": "AA BB"},
                        "predicate": aa,
                        "participants": [{"entity": "place", "role": "location", "evidence": bb}],
                    }
                ],
                "circumstances": [
                    {"key": "how", "situation": "s", "kind": "manner", "evidence": bb}
                ],
            }
        ],
    }
    publish_json(destination / f"{key}-request.json", request.model_dump(mode="json"))
    publish_json(destination / f"{key}-proposal.json", packet)
    result = compile_rich_construction(request, canonical(packet))
    assert result.resolutions[0].status == "admitted"
    publish_json(destination / f"{key}-result.json", result.model_dump(mode="json"))
    return result.account


source = construct("source", "source", "positive")
source_before = source.model_dump_json()
for name, polarity in (("recall-a", "positive"), ("recall-b", "negative")):
    recall = construct(name, "recall", polarity)
    root = destination / name
    root.mkdir()
    paired = pair_rich_accounts(source, recall)
    request = prepare_details(paired, (recall.units[0].id,))
    packet = {
        "schema_version": "storymodel.detail-proposal/v1",
        "request_sha256": detail_request_digest(request),
        "units": [
            {
                "unit": recall.units[0].id,
                "status": "completed",
                "reason": "explicit abstract inventory",
                "details": [
                    {
                        "key": "condition",
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
    publish_json(root / "detail-request.json", request.model_dump(mode="json"))
    publish_json(root / "detail-proposal.json", packet)
    details = admit_details(request, canonical(packet))
    assert len(details.account.details) == 1
    export_supported_account(root / "input-account.json", details.account)
    result = align_account(details.account)
    verify_alignment(details.account, result)
    assert result.details[0].reference.outcome == "resolved"
    assert result.details[0].reference.hypotheses[0].targets == (source.units[0].id,)
    candidate = result.situations[0].candidates[0]
    assert candidate.polarity.comparison == (
        "same-declared" if polarity == "positive" else "different-declared"
    )
    assert candidate.source_circumstances and candidate.recall_circumstances
    assert all(not b.assessments for b in result.account.bindings)
    views = project(result.account)
    assert views.source[0].coverage == "not-assessable"
    publish_json(root / "alignment.json", result.model_dump(mode="json"))
    export_supported_account(root / "account.json", result.account)
    publish_json(root / "analysis.json", views.model_dump(mode="json"))
assert source.model_dump_json() == source_before
print(
    canonical(
        {
            "source_reused": 2,
            "source_unchanged": True,
            "reference": "resolved",
            "qualifier_differences": "visible",
            "fidelity": "pending",
            "coverage": "not-assessable",
        }
    )
)
