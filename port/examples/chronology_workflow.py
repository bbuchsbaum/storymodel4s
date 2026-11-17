"""Recorded construction -> temporal claims -> scoped queries -> existing numerical inference.

Uses existing abstract token/native controls. Temporal relations are declared test conditions,
not inferred durations or claims about linguistic or audiovisual recognition accuracy.
"""

import sys
from pathlib import Path

from connected_construction import construct
from playback_workflow import main as playback_example

from storymodel import (
    LocalEvidenceSpec,
    RecallSelection,
    SourceProjectionPolicy,
    TemporalProposal,
    TemporalProposals,
    admit_temporal_relations,
    infer_accounts,
    pair_rich_accounts,
    project_source,
    query_temporal_relation,
    temporal_table,
    verify_account_inference,
    verify_temporal_query,
    verify_temporal_relations,
)
from storymodel.artifacts import export_supported_account, publish_json, read_supported_account
from storymodel.model import Support
from storymodel.relations import RelationQuote
from storymodel.rich_account import RichAccount
from storymodel.serialization import canonical, digest


def add_chronology(account, directory, left, right, *, query_pair):
    original = account.model_dump_json()
    if isinstance(left.evidence[0], Support):
        support = left.evidence[0]
        document = next(d for d in account.documents if d.id == support.document)
        evidence = tuple(
            RelationQuote(document=document.id, span=span, text=span.slice(document.text))
            for span in support.spans
        )
    else:
        evidence = left.evidence
    batch = TemporalProposals(
        account_sha256=digest(account.model_dump(mode="json")),
        origin="abstract-control",
        procedure="declared-interval-constraints/v1",
        status="completed",
        relations=(
            TemporalProposal(
                key="declared-meets",
                source=left.id,
                target=right.id,
                relation="meets",
                context=left.context,
                status="hypothesized",
                evidence=evidence,
            ),
        ),
    )
    raw = canonical(batch.model_dump(mode="json")) + "\n"
    result = admit_temporal_relations(account, raw)
    assert result.status == "admitted", result.reason
    verify_temporal_relations(account, raw, result)
    output = result.account
    assert output.events == account.events and output.states == account.states
    assert output.units == account.units and output.contexts == account.contexts
    assert output.relations == account.relations
    query = query_temporal_relation(output, *query_pair, left.context)
    verify_temporal_query(output, *query_pair, left.context, query)
    (directory / "temporal-proposals.json").write_text(raw, encoding="utf-8")
    export_supported_account(directory / "temporal-input.json", account)
    export_supported_account(directory / "temporal-account.json", output)
    publish_json(directory / "temporal-result.json", result.model_dump(mode="json"))
    publish_json(directory / "temporal-query.json", query.model_dump(mode="json"))
    publish_json(
        directory / "temporal-table.json",
        [r.model_dump(mode="json") for r in temporal_table(output)],
    )
    assert account.model_dump_json() == original
    return output, query


def main(destination):
    destination.mkdir(parents=True, exist_ok=False)
    base = construct(destination / "source", "source", ("PP", "QQ", "PP"))
    a, b, c = base.states
    source, source_query = add_chronology(
        base, destination / "source", b, c, query_pair=(a.id, c.id)
    )
    # A before B is a legacy assertion; B meets C is new. Composition entails A before C.
    assert source_query.possible == ("before",) and not source_query.direct_claims
    assert len(source_query.constraint_claims) == 2
    policy = SourceProjectionPolicy(recipe="account-source-layers/v3")
    projection = project_source(source, policy)
    world = {(e.source, e.target) for e in projection.edges if e.layer == "world-time"}
    assert world == {(a.id, b.id), (b.id, c.id)}
    publish_json(destination / "projection.json", projection.model_dump(mode="json"))
    original = source.model_dump_json()
    for number, tokens in enumerate((("PP", "QQ", "PP"), ("QQ", "PP", "QQ")), 1):
        directory = destination / f"recall-{number}"
        base_recall = construct(directory, "recall", tokens)
        ra, rb, rc = base_recall.states
        recall, query = add_chronology(base_recall, directory, rb, rc, query_pair=(ra.id, rc.id))
        assert query.possible == ("before",)
        paired = pair_rich_accounts(source, recall)
        assert all(r in paired.temporal_relations for r in source.temporal_relations)
        export_supported_account(destination / f"paired-{number}.json", paired)
        spec = LocalEvidenceSpec(
            units=tuple(
                RecallSelection(unit=u.id, function="episodic-assertion") for u in recall.units
            ),
            projection=policy,
        )
        inference = infer_accounts(source, recall, spec)
        verify_account_inference(source, recall, spec, inference)
        publish_json(destination / f"inference-spec-{number}.json", spec.model_dump(mode="json"))
        publish_json(destination / f"inference-{number}.json", inference.model_dump(mode="json"))
        assert source.model_dump_json() == original
    playback_example(destination / "native")
    native = read_supported_account(destination / "native/source-account.json")
    assert isinstance(native, RichAccount)
    left, right = native.events[0], native.states[0]
    native_output, native_query = add_chronology(
        native, destination / "native", left, right, query_pair=(left.id, right.id)
    )
    assert native_query.possible == ("meets",)
    assert not native_output.documents and native_output.media_sources == native.media_sources
    # Overlapping playback evidence does not contradict a separately asserted world-time relation.
    assert native_output.events[0].evidence == left.evidence
    print(
        canonical(
            {
                "source_unchanged": True,
                "recalls": 2,
                "composed_before": True,
                "native_temporal_query": True,
                "new_precedence_consumed": True,
            }
        )
    )


if __name__ == "__main__":
    main(Path(sys.argv[1]))
