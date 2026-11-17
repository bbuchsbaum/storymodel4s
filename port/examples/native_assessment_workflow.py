"""Recorded native source -> text recall details -> reference -> fidelity -> three views.

Reuses playback_workflow's admitted abstract controls. Supplied grades test contracts,
not audiovisual or linguistic accuracy. Both source construction and recalls are replayed.
"""

import json
import sys
from pathlib import Path

from playback_workflow import main as construct

from storymodel import (
    DetailRecording,
    import_details,
    pair_rich_accounts,
    prepare_details,
    project,
    replay_details,
    upgrade_assessment_account,
)
from storymodel.artifacts import publish_json, read_supported_account
from storymodel.detail_admission import detail_request_digest
from storymodel.direct_mapping import mapping_prompt, mapping_request_digest, prepare_mapping
from storymodel.direct_workflow import MappingRecording, import_mapping, replay_mapping
from storymodel.fidelity import fidelity_prompt, fidelity_request_digest, prepare_fidelity
from storymodel.fidelity_workflow import FidelityRecording, import_fidelity, replay_fidelity
from storymodel.rich_account import validate_rich_account
from storymodel.serialization import canonical


def main(destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=False)
    construct(destination / "construction")
    source = validate_rich_account(
        read_supported_account(destination / "construction/source-account.json")
    )
    original = source.model_dump_json()
    for index in (1, 2):
        case = destination / f"recall-{index}"
        case.mkdir()
        recall = validate_rich_account(
            read_supported_account(destination / f"construction/recall-{index}/account.json")
        )
        detail_request = prepare_details(recall, (recall.units[0].id,))
        detail_reply = canonical(
            {
                "schema_version": "storymodel.detail-proposal/v1",
                "request_sha256": detail_request_digest(detail_request),
                "units": [
                    {
                        "unit": recall.units[0].id,
                        "status": "completed",
                        "reason": "control inventory",
                        "details": [
                            {
                                "key": "detail",
                                "kind": "event",
                                "address": "target",
                                "discourse": "content",
                                "situation": recall.events[0].id,
                                "quotes": [{"span": {"start": 0, "end": 5}, "text": "AA BB"}],
                            }
                        ],
                    }
                ],
            }
        )
        detail_recording = DetailRecording(
            request_sha256=detail_request_digest(detail_request),
            origin="abstract control",
            reply=detail_reply,
        )
        import_details(detail_request, detail_recording, case / "details-recording")
        details = replay_details(detail_request, case / "details-recording")
        assert all(r.status == "admitted" for r in details.receipts)
        paired = pair_rich_accounts(source, details.account)
        account = upgrade_assessment_account(paired)
        unit = details.account.units[0].id
        mapping = prepare_mapping(account, unit)
        target = next(u for u in source.units if u.kind == "row")
        mapping_reply = canonical(
            {
                "schema_version": "storymodel.mapping-proposal/v2",
                "packet_sha256": json.loads(mapping_prompt(mapping))["packet_sha256"],
                "details": [
                    {
                        "detail": "d0",
                        "status": "attempted",
                        "outcome": "resolved",
                        "reason": "control reference",
                        "hypotheses": [{"key": "h", "kind": "single", "targets": [target.id]}],
                    }
                ],
            }
        )
        mapping_recording = MappingRecording(
            request_sha256=mapping_request_digest(mapping),
            origin="abstract control",
            reply=mapping_reply,
        )
        import_mapping(mapping, mapping_recording, case / "mapping-recording")
        mapped = replay_mapping(mapping, case / "mapping-recording")
        assert all(d.status == "admitted" for d in mapped.decisions)
        assert {r.coverage for r in project(mapped.account).source} == {"not-assessable"}
        fidelity = prepare_fidelity(mapped.account, unit)
        event = source.events[0]
        evidence = event.evidence[0].model_dump(mode="json")
        if index == 2:
            # A hull through the unobserved gap is not lawful situation evidence.
            evidence["intervals"] = [{"start": "4", "end": "23"}]
        fidelity_reply = canonical(
            {
                "schema_version": "storymodel.fidelity-proposal/v3",
                "packet_sha256": json.loads(fidelity_prompt(fidelity))["packet_sha256"],
                "assessments": [
                    {
                        "hypothesis": "a0",
                        "status": "completed",
                        "grade": "supported",
                        "reason": "scripted contract control; not scientific truth",
                        "playback_evidence": [{"situation": event.id, "support": evidence}],
                    }
                ],
            }
        )
        fidelity_recording = FidelityRecording(
            request_sha256=fidelity_request_digest(fidelity),
            origin="abstract control",
            reply=fidelity_reply,
        )
        import_fidelity(fidelity, fidelity_recording, case / "fidelity-recording")
        assessed = replay_fidelity(fidelity, case / "fidelity-recording")
        assert assessed.decisions[0].status == ("admitted" if index == 1 else "rejected")
        assert {r.coverage for r in project(assessed.account).source} == (
            {"present"} if index == 1 else {"not-assessable"}
        )
        assert source.model_dump_json() == original
        for name, obj in (
            ("detail-request", detail_request),
            ("detail-result", details),
            ("paired", paired),
            ("account-v8", account),
            ("mapping-request", mapping),
            ("mapping-result", mapped),
            ("fidelity-request", fidelity),
            ("fidelity-result", assessed),
            ("views", project(assessed.account)),
        ):
            publish_json(case / f"{name}.json", obj.model_dump(mode="json"))
        publish_json(case / "mapping-prompt.json", json.loads(mapping_prompt(mapping)))
        publish_json(case / "fidelity-prompt.json", json.loads(fidelity_prompt(fidelity)))
    print(
        json.dumps(
            {
                "source_unchanged": True,
                "recalls": 2,
                "native_grade": "admitted",
                "gap_grade": "rejected",
                "scientific_accuracy_claim": False,
            }
        )
    )


if __name__ == "__main__":
    main(Path(sys.argv[1]))
