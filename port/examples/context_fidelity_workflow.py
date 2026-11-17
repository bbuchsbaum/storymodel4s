"""Recorded contextual fidelity over abstract tokens; no semantic accuracy claim."""

import sys
from pathlib import Path

from storymodel import (
    ContextEvidence,
    DetailRecording,
    FidelityRecording,
    MappingRecording,
    RichRecording,
    SourceRow,
    fidelity_prompt,
    import_details,
    import_fidelity,
    import_mapping,
    import_rich,
    mapping_prompt,
    pair_rich_accounts,
    prepare_details,
    prepare_fidelity,
    prepare_mapping,
    prepare_rich_construction,
    project,
    replay_details,
    replay_fidelity,
    replay_mapping,
    replay_rich,
    verify_fidelity,
)
from storymodel.artifacts import export_supported_account, publish_json
from storymodel.detail_admission import detail_prompt, detail_request_digest
from storymodel.direct_mapping import mapping_request_digest
from storymodel.fidelity import fidelity_request_digest
from storymodel.model import Span
from storymodel.rich_construction import rich_construction_request_digest
from storymodel.serialization import canonical, digest, strict_json

CONTEXT_INDEPENDENT_REASON = " ".join(
    ("source quote settles this scripted grade independently of", "holder identity")
)

destination = Path(sys.argv[1])
destination.mkdir(parents=True, exist_ok=False)
inputs, recordings, prompts, results, views = (
    destination / name for name in ("inputs", "recordings", "prompts", "results", "views")
)
for directory in (inputs, recordings, prompts, results, views):
    directory.mkdir()


def construct(key, side):
    request = prepare_rich_construction(
        key,
        side,
        "AA AA CC",
        (SourceRow(key="row", span=Span(start=0, end=8)),),
        export_permission="allowed",
    )
    aa0 = {"span": {"start": 0, "end": 2}, "text": "AA"}
    aa1 = {"span": {"start": 3, "end": 5}, "text": "AA"}
    cc = {"span": {"start": 6, "end": 8}, "text": "CC"}
    packet = {
        "schema_version": "storymodel.rich-construction-proposal/v1",
        "rows": [
            {
                "row": "row",
                "status": "completed",
                "contexts": [
                    {
                        "key": "quoted",
                        "kind": "reported",
                        "parent": "narrated",
                        "cue": aa1,
                        "scope": cc,
                    }
                ],
                "entities": [
                    {"key": "named0", "mention": aa0},
                    {"key": "named1", "mention": aa1},
                    {"key": "first", "mention": cc},
                ],
                "states": [
                    {
                        "key": "root",
                        "context": "narrated",
                        "predicate": aa0,
                        "evidence": aa0,
                        "participants": [{"entity": "named0", "role": "speaker", "evidence": aa0}],
                    },
                    {
                        "key": "held-state",
                        "context": "quoted",
                        "predicate": cc,
                        "evidence": cc,
                        "participants": [
                            {"entity": "first", "role": "experiencer", "evidence": cc}
                        ],
                    },
                ],
            }
        ],
    }
    recording = RichRecording(
        request_sha256=rich_construction_request_digest(request),
        origin="abstract contextual construction/v1",
        reply=canonical(packet),
    )
    publish_json(inputs / f"{key}-construction-request.json", request.model_dump(mode="json"))
    publish_json(inputs / f"{key}-construction-proposal.json", packet)
    import_rich(request, recording, recordings / f"{key}-construction")
    built = replay_rich(request, recordings / f"{key}-construction")
    assert built.resolutions[0].status == "admitted"
    publish_json(results / f"{key}-construction.json", built.model_dump(mode="json"))
    return built.account


def mapped(name, source, recall):
    paired = pair_rich_accounts(source, recall)
    detail_request = prepare_details(paired, (recall.units[0].id,))
    detail_packet = {
        "schema_version": "storymodel.detail-proposal/v1",
        "request_sha256": detail_request_digest(detail_request),
        "units": [
            {
                "unit": recall.units[0].id,
                "status": "completed",
                "reason": "abstract held detail",
                "details": [
                    {
                        "key": "held",
                        "kind": "attribute",
                        "address": "target",
                        "discourse": "content",
                        "situation": recall.states[1].id,
                        "quotes": [{"span": {"start": 6, "end": 8}, "text": "CC"}],
                    }
                ],
            }
        ],
    }
    detail_recording = DetailRecording(
        request_sha256=detail_request_digest(detail_request),
        origin="abstract detail control/v1",
        reply=canonical(detail_packet),
    )
    publish_json(inputs / f"{name}-detail-request.json", detail_request.model_dump(mode="json"))
    publish_json(prompts / f"{name}-detail.json", strict_json(detail_prompt(detail_request)))
    import_details(detail_request, detail_recording, recordings / f"{name}-detail")
    detailed = replay_details(detail_request, recordings / f"{name}-detail")
    mapping_request = prepare_mapping(detailed.account, recall.units[0].id)
    mapping_packet = {
        "schema_version": "storymodel.mapping-proposal/v1",
        "packet_sha256": strict_json(mapping_prompt(mapping_request))["packet_sha256"],
        "details": [
            {
                "detail": "d0",
                "status": "attempted",
                "outcome": "resolved",
                "reason": "abstract paired target",
                "hypotheses": [{"key": "h", "kind": "single", "targets": [source.units[0].id]}],
                "assessments": [],
            }
        ],
    }
    mapping_recording = MappingRecording(
        request_sha256=mapping_request_digest(mapping_request),
        origin="abstract mapping control/v1",
        reply=canonical(mapping_packet),
    )
    publish_json(inputs / f"{name}-mapping-request.json", mapping_request.model_dump(mode="json"))
    publish_json(prompts / f"{name}-mapping.json", strict_json(mapping_prompt(mapping_request)))
    import_mapping(mapping_request, mapping_recording, recordings / f"{name}-mapping")
    result = replay_mapping(mapping_request, recordings / f"{name}-mapping")
    assert result.account.bindings[0].reference.outcome == "resolved"
    assert result.account.bindings[0].assessments[0].status == "pending"
    publish_json(results / f"{name}-detail.json", detailed.model_dump(mode="json"))
    publish_json(results / f"{name}-mapping.json", result.model_dump(mode="json"))
    return result.account


def evidence(account, unresolved):
    documents = {d.id: d for d in account.documents}
    annotations = {
        "schema_version": "storymodel.identity-annotations/v1",
        "account_sha256": digest(account.model_dump(mode="json")),
        "origin": "declared abstract forms",
        "mentions": [],
    }
    by_document = {}
    for entity in account.entities:
        span = entity.evidence[0].spans[0]
        text = span.slice(documents[entity.origin_document].text)
        annotations["mentions"].append(
            {
                "mention": entity.id,
                "status": "provided",
                "quote": {"span": span.model_dump(mode="json"), "text": text},
                **(
                    {"form": "pronominal", "person": "first", "number": "singular"}
                    if text == "CC"
                    else {"form": "name", "entity_type": {"kind": "person"}}
                ),
            }
        )
        by_document.setdefault(entity.origin_document, []).append(entity)
    proposals = {
        "schema_version": "storymodel.holder-proposals/v1",
        "account_sha256": digest(account.model_dump(mode="json")),
        "origin": "declared abstract holder candidates",
        "contexts": [],
    }
    for context in account.contexts:
        if context.kind == "reported":
            label = "CC" if unresolved and context.side == "recall" else "AA"
            proposals["contexts"].append(
                {
                    "context": context.id,
                    "status": "completed",
                    "candidates": [
                        e.id for e in by_document[context.evidence[0].document] if e.label == label
                    ],
                    "evidence": [s.model_dump(mode="json") for s in context.evidence],
                }
            )
    suffix = "unresolved" if unresolved else "named"
    publish_json(inputs / f"identity-annotations-{suffix}.json", annotations)
    publish_json(inputs / f"holder-proposals-{suffix}.json", proposals)
    return ContextEvidence(
        identity_annotations=canonical(annotations) + "\n",
        holder_proposals=canonical(proposals) + "\n",
    )


def fidelity_reply(request, requires_holder, reason):
    prompt = strict_json(fidelity_prompt(request))
    question = prompt["questions"][0]
    context = prompt["context_evidence"][question["key"]]
    held = next(
        row["key"]
        for row in context["contexts"]
        if row["side"] == "recall" and row["holder"]["applicable"]
    )
    packet = {
        "schema_version": "storymodel.fidelity-proposal/v2",
        "packet_sha256": prompt["packet_sha256"],
        "assessments": [
            {
                "hypothesis": question["key"],
                "status": "completed",
                "grade": "supported",
                "reason": reason,
                "evidence": [
                    {
                        "document": prompt["source_documents"][0]["id"],
                        "span": {"start": 6, "end": 8},
                        "text": "CC",
                    }
                ],
                "context_accounting": {
                    "considered_contexts": [row["key"] for row in context["contexts"]],
                    "required_holders": [held] if requires_holder else [],
                    "required_mentions": [],
                    "reason": "holder is required"
                    if requires_holder
                    else CONTEXT_INDEPENDENT_REASON,
                },
            }
        ],
    }
    return prompt, canonical(packet)


source = construct("source", "source")
source_before = source.model_dump_json()
export_supported_account(inputs / "source-account.json", source)
recall_a, recall_b = construct("recall-a", "recall"), construct("recall-b", "recall")
mapped_a, mapped_b = mapped("recall-a", source, recall_a), mapped("recall-b", source, recall_b)
assert source.model_dump_json() == source_before

named_request = prepare_fidelity(
    mapped_a, recall_a.units[0].id, context_evidence=evidence(mapped_a, False)
)
v1_request = prepare_fidelity(mapped_a, recall_a.units[0].id)
assert v1_request.schema_version == "storymodel.fidelity-request/v1"
assert strict_json(fidelity_prompt(v1_request))["recipe"] == "unit-only-conditional-fidelity/v1"
named_prompt, named_raw = fidelity_reply(named_request, True, "named-holder contextual control")
named_recording = FidelityRecording(
    request_sha256=fidelity_request_digest(named_request),
    origin="abstract named-holder control/v2",
    reply=named_raw,
)
publish_json(inputs / "named-fidelity-request.json", named_request.model_dump(mode="json"))
publish_json(prompts / "named-fidelity.json", named_prompt)
import_fidelity(named_request, named_recording, recordings / "named-fidelity")
named = replay_fidelity(named_request, recordings / "named-fidelity")
verify_fidelity(named_request, named_raw, named)
assert named.decisions[0].status == "admitted"

unresolved_request = prepare_fidelity(
    mapped_b, recall_b.units[0].id, context_evidence=evidence(mapped_b, True)
)
rejected_prompt, rejected_raw = fidelity_reply(
    unresolved_request, True, "this deliberately requires the unresolved holder"
)
rejected_recording = FidelityRecording(
    request_sha256=fidelity_request_digest(unresolved_request),
    origin="abstract unresolved-holder rejection/v2",
    reply=rejected_raw,
)
publish_json(
    inputs / "unresolved-required-fidelity-request.json", unresolved_request.model_dump(mode="json")
)
publish_json(prompts / "unresolved-required-fidelity.json", rejected_prompt)
import_fidelity(unresolved_request, rejected_recording, recordings / "unresolved-required-fidelity")
rejected = replay_fidelity(unresolved_request, recordings / "unresolved-required-fidelity")
verify_fidelity(unresolved_request, rejected_raw, rejected)
assert rejected.decisions[0].status == "rejected", rejected.decisions[0]
assert rejected.account.bindings == mapped_b.bindings
assert rejected.account.bindings[0].assessments[0].status == "pending"

independent_prompt, independent_raw = fidelity_reply(
    unresolved_request,
    False,
    "source quote settles the abstract token control without holder identity",
)
independent_recording = FidelityRecording(
    request_sha256=fidelity_request_digest(unresolved_request),
    origin="abstract context-independent control/v2",
    reply=independent_raw,
)
publish_json(prompts / "unresolved-independent-fidelity.json", independent_prompt)
publish_json(
    inputs / "unresolved-independent-fidelity-request.json",
    unresolved_request.model_dump(mode="json"),
)
import_fidelity(
    unresolved_request, independent_recording, recordings / "unresolved-independent-fidelity"
)
independent = replay_fidelity(unresolved_request, recordings / "unresolved-independent-fidelity")
verify_fidelity(unresolved_request, independent_raw, independent)
assert independent.decisions[0].status == "admitted"

for name, before, receipt in (
    ("named", mapped_a, named),
    ("rejected", mapped_b, rejected),
    ("independent", mapped_b, independent),
):
    after = receipt.account
    assert (
        after.details == before.details
        and after.entities == before.entities
        and after.contexts == before.contexts
    )
    assert after.states == before.states and after.circumstances == before.circumstances
    assert [b.reference for b in after.bindings] == [b.reference for b in before.bindings]
    publish_json(results / f"{name}-fidelity-receipt.json", receipt.model_dump(mode="json"))
    export_supported_account(results / f"{name}-account.json", after)
    analysis = project(after)
    publish_json(
        views / f"{name}-recall.json", [r.model_dump(mode="json") for r in analysis.recall]
    )
    publish_json(
        views / f"{name}-correspondence.json",
        [r.model_dump(mode="json") for r in analysis.correspondence],
    )
    publish_json(
        views / f"{name}-source.json", [r.model_dump(mode="json") for r in analysis.source]
    )

assert source.model_dump_json() == source_before
print(
    canonical(
        {
            "source_reused": 2,
            "source_unchanged": True,
            "named_holder_grade": "admitted",
            "unresolved_holder_grade": "rejected",
            "context_independent_grade": "admitted",
            "semantic_accuracy_claim": False,
            "unit_only_profile_retained": True,
        }
    )
)
