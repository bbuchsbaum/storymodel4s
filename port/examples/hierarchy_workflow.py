"""Recorded construction -> unchanged occurrences -> primary hierarchy -> numerical inference.

Reuses the admitted abstract controls in connected_construction and playback_workflow.
No hierarchy is inferred from token order; these are explicit engineering declarations.
"""

import sys
from pathlib import Path

from connected_construction import construct
from playback_workflow import main as playback_example

from storymodel import (
    HierarchyGroup,
    HierarchyRef,
    LocalEvidenceSpec,
    RecallSelection,
    SituationMembership,
    SourceProjectionPolicy,
    build_hierarchy,
    infer_accounts,
    pair_rich_accounts,
    prepare_hierarchy,
    project_source,
    query_hierarchy,
    verify_account_inference,
    verify_hierarchy,
)
from storymodel.artifacts import publish_json, read_supported_account
from storymodel.model import contained_in
from storymodel.serialization import canonical


def group_account(account, directory):
    original = account.model_dump_json()
    memberships = []
    for situation in account._situations():
        candidates = [
            u
            for u in account.units
            if all(contained_in(e, (u.support,)) for e in situation.evidence)
        ]
        assert len(candidates) == 1  # Declared fixture rows are unambiguous observation envelopes.
        memberships.append(SituationMembership(situation=situation.id, unit=candidates[0].id))
    groups = (
        HierarchyGroup(
            key="scene",
            kind="scene",
            members=tuple(HierarchyRef(kind="anchor", key=u.id) for u in account.units),
        ),
        HierarchyGroup(
            key="episode", kind="episode", members=(HierarchyRef(kind="group", key="scene"),)
        ),
        HierarchyGroup(
            key="story", kind="story", members=(HierarchyRef(kind="group", key="episode"),)
        ),
    )
    spec = prepare_hierarchy(
        account,
        groups,
        memberships=tuple(memberships),
        expected_root=HierarchyRef(kind="group", key="story"),
    )
    result = build_hierarchy(account, spec)
    verify_hierarchy(account, spec, result)
    assert result.root is not None and not result.unrooted
    query = query_hierarchy(account, result, result.root)
    assert set(query.situations) == {s.id for s in account._situations()}
    assert account.model_dump_json() == original
    for name, artifact in (
        ("hierarchy-spec", spec),
        ("hierarchy", result),
        ("hierarchy-query", query),
    ):
        publish_json(directory / f"{name}.json", artifact.model_dump(mode="json"))
    return spec, result


def main(destination):
    destination.mkdir(parents=True, exist_ok=False)
    source = construct(destination / "source", "source", ("PP", "QQ", "PP"))
    original = source.model_dump_json()
    hierarchy_spec, hierarchy = group_account(source, destination / "source")
    policy = SourceProjectionPolicy(recipe="account-source-layers/v2", hierarchy=hierarchy_spec)
    projection = project_source(source, policy)
    assert hierarchy.root in {n.anchor for n in projection.nodes}
    publish_json(destination / "projection.json", projection.model_dump(mode="json"))
    for number, tokens in enumerate((("PP", "QQ"), ("QQ", "PP")), 1):
        directory = destination / f"recall-{number}"
        recall = construct(directory, "recall", tokens)
        group_account(recall, directory)
        paired = pair_rich_accounts(source, recall)
        assert all(s in paired.states for s in source.states)
        spec = LocalEvidenceSpec(
            units=tuple(
                RecallSelection(unit=u.id, function="episodic-assertion") for u in recall.units
            ),
            projection=policy,
        )
        result = infer_accounts(source, recall, spec)
        verify_account_inference(source, recall, spec, result)
        assert result.evidence.sequence.projection == projection
        publish_json(destination / f"inference-spec-{number}.json", spec.model_dump(mode="json"))
        publish_json(destination / f"inference-{number}.json", result.model_dump(mode="json"))
        assert source.model_dump_json() == original
    # Native grouping has real query consumers; numerical text consumers still refuse it.
    playback_example(destination / "native")
    native = read_supported_account(destination / "native/source-account.json")
    native_spec, _ = group_account(native, destination / "native")
    try:
        project_source(
            native, SourceProjectionPolicy(recipe="account-source-layers/v2", hierarchy=native_spec)
        )
    except ValueError as error:
        assert "unsupported modality" in str(error)
    else:
        raise AssertionError("native numerical projection is not yet qualified")
    print(
        canonical(
            {
                "source_unchanged": True,
                "recalls": 2,
                "native_hierarchy_query": True,
                "text_hierarchy_inference": True,
            }
        )
    )


if __name__ == "__main__":
    main(Path(sys.argv[1]))
