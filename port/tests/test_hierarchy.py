"""Independent witnesses for explicit primary narrative hierarchies.

The fixtures use only synthetic tokens.  Expected supports, tree traversal, and
the transition oracle are calculated in this file rather than by hierarchy
implementation helpers.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import pytest

from storymodel.accounts import make_account
from storymodel.cli import main
from storymodel.hierarchy import (
    HierarchyGroup,
    HierarchyRef,
    HierarchyResult,
    SituationMembership,
    build_hierarchy,
    prepare_hierarchy,
    query_hierarchy,
)
from storymodel.local_evidence import LocalEvidenceSpec, RecallSelection, derive_local_evidence
from storymodel.model import Context, Document, Event, Recipe, Span, Support, Unit
from storymodel.playback import MediaSource, PlaybackSupport, TickInterval
from storymodel.rich_account import RichAccount, validate_rich_account
from storymodel.sequence import SequenceRow, SequenceState, StateCost
from storymodel.source_transitions import (
    SourceProjectionPolicy,
    SourceSequenceSpec,
    TransitionWeights,
    build_source_sequence,
    project_source,
)


def _support(document: Document, start: int, end: int) -> Support:
    return Support(document=document.id, spans=(Span(start=start, end=end),))


def _source(*, legacy_parent: bool = False):
    """Three synthetic source occurrences, with a deliberate coordinate gap."""
    document = Document.create("hierarchy-source", "source", "AA BB CC")
    root = Context(side="source", kind="narrated")
    supports = (_support(document, 0, 2), _support(document, 3, 5), _support(document, 6, 8))
    events = tuple(
        Event(side="source", context=root.id, predicate=token, evidence=(support,))
        for token, support in zip(("AA", "BB", "CC"), supports)
    )
    scene = Unit(kind="scene", support=_support(document, 0, 8))
    row_a = Unit(kind="row", support=supports[0], parent=scene.id if legacy_parent else None)
    row_b = Unit(kind="row", support=supports[1], parent=scene.id if legacy_parent else None)
    row_c = Unit(kind="row", support=supports[2], parent=scene.id if legacy_parent else None)
    return (
        make_account(
            (document,),
            units=((scene, row_a, row_b, row_c) if legacy_parent else (row_a, row_b, row_c)),
            contexts=(root,),
            events=events,
            export_permission="allowed",
        ),
        events,
        (row_a, row_b, row_c),
    )


def _recall():
    document = Document.create("hierarchy-recall", "recall", "AA BB")
    return make_account(
        (document,),
        units=(
            Unit(kind="recall", support=_support(document, 0, 2)),
            Unit(kind="recall", support=_support(document, 3, 5)),
        ),
        export_permission="allowed",
    )


def _tree(events, rows):
    row_a, row_b, row_c = rows
    groups = (
        HierarchyGroup(
            key="beat-a", kind="scene", members=(HierarchyRef(kind="anchor", key=row_a.id),)
        ),
        HierarchyGroup(
            key="beat-b", kind="scene", members=(HierarchyRef(kind="anchor", key=row_b.id),)
        ),
        HierarchyGroup(
            key="episode",
            kind="episode",
            members=(
                HierarchyRef(kind="group", key="beat-a"),
                HierarchyRef(kind="group", key="beat-b"),
            ),
        ),
        HierarchyGroup(
            key="story",
            kind="story",
            members=(
                HierarchyRef(kind="group", key="episode"),
                HierarchyRef(kind="anchor", key=row_c.id),
            ),
        ),
    )
    memberships = tuple(
        SituationMembership(situation=event.id, unit=row.id) for event, row in zip(events, rows)
    )
    return groups, memberships


def test_complete_tree_has_expected_supports_levels_and_stable_query():
    account, events, rows = _source()
    groups, memberships = _tree(events, rows)
    spec = prepare_hierarchy(
        account,
        groups,
        memberships=memberships,
        expected_root=HierarchyRef(kind="group", key="story"),
    )
    result = build_hierarchy(account, spec)
    by_group = result.groups
    story = by_group["story"]
    episode = by_group["episode"]
    beat_a, beat_b = by_group["beat-a"], by_group["beat-b"]

    assert result.root == story and result.roots == (story,) and not result.unrooted
    assert result.empty_segments == ()
    nodes = {node.anchor: node for node in result.nodes}
    assert nodes[story].children == tuple(sorted((episode, rows[2].id)))
    assert nodes[episode].children == tuple(sorted((beat_a, beat_b)))
    assert nodes[story].level == 4 and nodes[episode].level == 3
    # The AA/BB gap remains a gap: exact unions must not invent support from 2 to 3.
    episode_segment = next(segment for segment in result.segments if segment.id == episode)
    assert episode_segment.evidence[0].spans == (Span(start=0, end=2), Span(start=3, end=5))
    query = query_hierarchy(account, result, story)
    assert query.ancestors == () and query.parent is None
    assert set(query.descendants) == {
        episode,
        beat_a,
        beat_b,
        *(row.id for row in rows),
        *(e.id for e in events),
    }
    assert set(query.situations) == {event.id for event in events}
    assert (
        query_hierarchy(account, result, story, descendant_level=0).descendants == query.situations
    )


def test_group_identity_is_bottom_up_and_legacy_parents_remain_unchanged():
    account, events, rows = _source(legacy_parent=True)
    scene = account.units[0]
    before = account.model_dump(mode="json")
    leaf = HierarchyGroup(
        key="unrelated-local-key",
        kind="scene",
        members=(HierarchyRef(kind="anchor", key=scene.id),),
    )
    alone = build_hierarchy(
        account, prepare_hierarchy(account, (leaf,), completeness="partial", reason="synthetic")
    )
    wrapped = HierarchyGroup(
        key="new-parent", kind="story", members=(HierarchyRef(kind="group", key=leaf.key),)
    )
    together = build_hierarchy(
        account,
        prepare_hierarchy(account, (leaf, wrapped), completeness="partial", reason="synthetic"),
    )
    assert alone.groups[leaf.key] == together.groups[leaf.key]
    renamed = leaf.model_copy(update={"key": "renamed"})
    relabeled = build_hierarchy(
        account, prepare_hierarchy(account, (renamed,), completeness="partial", reason="synthetic")
    )
    assert relabeled.groups["renamed"] == alone.groups[leaf.key]
    assert account.model_dump(mode="json") == before
    assert account.units[1].parent == scene.id
    # A new membership cannot silently override an established legacy parent.
    conflicting = HierarchyGroup(
        key="conflict", kind="scene", members=(HierarchyRef(kind="anchor", key=rows[0].id),)
    )
    with pytest.raises(ValueError, match="duplicate or conflicting parents"):
        prepare_hierarchy(
            account,
            (conflicting,),
            completeness="partial",
            reason="conflict",
        )


@pytest.mark.parametrize("bad", ["cycle", "duplicate", "dangling", "foreign", "forged"])
def test_invalid_or_forged_hierarchies_refuse_at_public_boundaries(bad):
    account, events, rows = _source()
    if bad == "cycle":
        groups = (
            HierarchyGroup(key="a", kind="scene", members=(HierarchyRef(kind="group", key="b"),)),
            HierarchyGroup(key="b", kind="scene", members=(HierarchyRef(kind="group", key="a"),)),
        )
    elif bad == "duplicate":
        groups = (
            HierarchyGroup(
                key="a", kind="scene", members=(HierarchyRef(kind="anchor", key=rows[1].id),)
            ),
        ) * 2
    elif bad == "dangling":
        groups = (
            HierarchyGroup(
                key="a", kind="scene", members=(HierarchyRef(kind="anchor", key="unit:missing"),)
            ),
        )
    elif bad == "foreign":
        groups = (
            HierarchyGroup(
                key="a", kind="scene", members=(HierarchyRef(kind="anchor", key="event:foreign"),)
            ),
        )
    else:
        groups = (HierarchyGroup.model_construct(key="a", kind="scene", members=()),)
    with pytest.raises(ValueError):
        prepare_hierarchy(account, groups, completeness="partial", reason="negative")


def test_partial_is_explicit_but_complete_requires_one_nonempty_segment_root():
    account, events, rows = _source()
    with pytest.raises(ValueError, match="partial hierarchy requires a reason"):
        prepare_hierarchy(account, completeness="partial")
    partial = prepare_hierarchy(account, completeness="partial", reason="awaiting grouping")
    assert build_hierarchy(account, partial).unrooted
    with pytest.raises(ValueError, match="complete hierarchy"):
        prepare_hierarchy(account)


def test_v2_projection_exposes_generated_segments_and_local_evidence_can_score_them():
    account, events, rows = _source()
    groups, memberships = _tree(events, rows)
    hierarchy = prepare_hierarchy(
        account,
        groups,
        memberships=memberships,
        expected_root=HierarchyRef(kind="group", key="story"),
    )
    policy = SourceProjectionPolicy(recipe="account-source-layers/v2", hierarchy=hierarchy)
    projected = project_source(account, policy)
    root = build_hierarchy(account, hierarchy).root
    assert root is not None and any(
        node.anchor == root and node.kind == "segment" for node in projected.nodes
    )
    recall = _recall()
    evidence = derive_local_evidence(
        account,
        recall,
        LocalEvidenceSpec(
            units=(RecallSelection(unit=recall.units[0].id, targets=(root,)),), projection=policy
        ),
    )
    assert evidence.retrieval[0].nominations[0].target == root
    cell = next(cell for cell in evidence.cells if cell.target == root)
    assert cell.measured and cell.terms and not cell.excluded


def test_membership_refuses_a_same_document_gap_outside_its_parent_support():
    document = Document.create("gap", "source", "AA BB CC")
    root = Context(side="source", kind="narrated")
    left = Support(document=document.id, spans=(Span(start=0, end=2), Span(start=6, end=8)))
    right = _support(document, 3, 5)
    event = Event(side="source", context=root.id, predicate="BB", evidence=(right,))
    account = make_account(
        (document,), units=(Unit(kind="row", support=left),), contexts=(root,), events=(event,)
    )
    with pytest.raises(ValueError, match="contain all exact child support"):
        prepare_hierarchy(
            account,
            memberships=(SituationMembership(situation=event.id, unit=account.units[0].id),),
            completeness="partial",
            reason="gap is not support",
        )


def test_stale_account_binding_is_rejected_before_hierarchy_consumption():
    account, events, rows = _source()
    groups, memberships = _tree(events, rows)
    spec = prepare_hierarchy(account, groups, memberships=memberships)
    stale = account.model_copy(update={"export_permission": "denied"})
    with pytest.raises(ValueError, match="another account snapshot"):
        build_hierarchy(stale, spec)


def test_hierarchy_transition_weight_oracle_is_independent_of_generated_edge_order():
    account, events, rows = _source()
    groups, memberships = _tree(events, rows)
    hierarchy = prepare_hierarchy(
        account,
        groups,
        memberships=memberships,
        expected_root=HierarchyRef(kind="group", key="story"),
    )
    policy = SourceProjectionPolicy(recipe="account-source-layers/v2", hierarchy=hierarchy)
    built = build_hierarchy(account, hierarchy)
    root = built.root
    assert root is not None
    recall = _recall()
    left = SequenceState(kind="source", anchor_kind="segment", anchor=built.groups["episode"])
    right = SequenceState(kind="source", anchor_kind="segment", anchor=built.groups["beat-a"])
    peer = SequenceState(kind="source", anchor_kind="segment", anchor=rows[2].id)
    external = SequenceState(kind="external", external="intrusion")
    rows_spec = (
        SequenceRow(unit=recall.units[0].id, costs=(StateCost(state=left, cost=0.0),)),
        SequenceRow(
            unit=recall.units[1].id,
            costs=(
                StateCost(state=right, cost=0.0),
                StateCost(state=peer, cost=0.0),
                StateCost(state=external, cost=0.0),
            ),
        ),
    )
    result = build_source_sequence(
        account,
        recall,
        SourceSequenceSpec(
            rows=rows_spec,
            projection=policy,
            weights=TransitionWeights(theta={"hierarchy-down": math.log(3.0)}),
        ),
    )
    edge = next(
        e
        for e in result.request.transitions[0].edges
        if e.from_state == left.id and e.to_state == right.id
    )
    # source mass is 1/2 (external-in logit zero); hierarchy scores 3:1, so 3/8.
    assert edge.probability == pytest.approx(0.375)


def test_hierarchy_cli_and_schema_snapshot(tmp_path, capsys):
    account, events, rows = _source()
    groups, memberships = _tree(events, rows)
    spec = prepare_hierarchy(
        account,
        groups,
        memberships=memberships,
        expected_root=HierarchyRef(kind="group", key="story"),
    )
    result = build_hierarchy(account, spec)
    account_path, spec_path, receipt_path, result_path, output = (
        tmp_path / n
        for n in ("account.json", "spec.json", "receipt.json", "result.json", "query.json")
    )
    account_path.write_text(account.model_dump_json())
    spec_path.write_text(spec.model_dump_json())
    receipt_path.write_text(result.model_dump_json())
    assert (
        main(["build-hierarchy", str(account_path), str(spec_path), "--output", str(result_path)])
        == 0
    )
    assert HierarchyResult.model_validate_json(result_path.read_bytes()) == result
    assert main(["verify-hierarchy", str(account_path), str(spec_path), str(receipt_path)]) == 0
    assert json.loads(capsys.readouterr().out)["valid"] is True
    assert (
        main(
            [
                "query-hierarchy",
                str(account_path),
                str(receipt_path),
                result.root,
                "--level",
                "0",
                "--output",
                str(output),
            ]
        )
        == 0
    )
    assert json.loads(output.read_text())["anchor"] == result.root
    schema = Path(__file__).resolve().parents[1] / "schemas/hierarchy.json"
    assert json.loads(schema.read_text()) == {
        "spec": type(spec).model_json_schema(),
        "result": HierarchyResult.model_json_schema(),
        "query": type(query_hierarchy(account, result, result.root)).model_json_schema(),
    }


@pytest.mark.parametrize("side", ["source", "recall"])
def test_native_hierarchy_retains_interval_gaps_points_and_refuses_foreign_axis(side):
    media = MediaSource(
        key="native",
        side=side,
        sha256="a" * 64,
        edition="edition",
        stream="picture",
        clock="clock",
        stream_kind="picture",
        start_tick="0",
        end_tick="40",
        seconds_per_tick="1/2",
    )
    root = Context(side=side, kind="narrated")
    first = PlaybackSupport(
        source=media.id,
        intervals=(TickInterval(start="0", end="5"), TickInterval(start="20", end="25")),
        points=("2", "10"),
    )
    second = PlaybackSupport(source=media.id, intervals=(TickInterval(start="5", end="10"),))
    events = tuple(
        Event(side=side, context=root.id, predicate=name, evidence=(support,))
        for name, support in (("AA", first), ("BB", second))
    )
    account = validate_rich_account(
        RichAccount(
            schema_version="storymodel.account/v6",
            documents=(),
            units=(),
            contexts=(root,),
            events=events,
            media_sources=(media,),
            recipe=Recipe(name="native-hierarchy/v1", input_sha256=(media.sha256,)),
        )
    )
    group = HierarchyGroup(
        key="native",
        kind="scene",
        members=tuple(HierarchyRef(kind="anchor", key=e.id) for e in events),
    )
    segment = build_hierarchy(account, prepare_hierarchy(account, (group,))).segments[0]
    assert segment.evidence[0].intervals == (
        TickInterval(start="0", end="10"),
        TickInterval(start="20", end="25"),
    )
    assert segment.evidence[0].points == ("2", "10")
    foreign = MediaSource(
        key="other-native",
        side=side,
        sha256="a" * 64,
        edition="edition",
        stream="picture",
        clock="other",
        stream_kind="picture",
        start_tick="0",
        end_tick="40",
        seconds_per_tick="1/2",
    )
    alien = Event(
        side=side,
        context=root.id,
        predicate="CC",
        evidence=(PlaybackSupport(source=foreign.id, points=("2",)),),
    )
    with pytest.raises(ValueError):
        prepare_hierarchy(
            account.model_copy(update={"events": (*events, alien)}),
            (group,),
            completeness="partial",
            reason="axis",
        )

    # Register both clocks lawfully: hierarchy still cannot treat equal ticks as one axis.
    two_axes = validate_rich_account(
        account.model_copy(
            update={
                "media_sources": (media, foreign),
                "events": (*events, alien),
                "units": (
                    Unit(
                        kind="row" if side == "source" else "recall",
                        support=PlaybackSupport(
                            source=foreign.id, intervals=(TickInterval(start="0", end="30"),)
                        ),
                    ),
                ),
            }
        )
    )
    with pytest.raises(ValueError, match="contain all exact child support"):
        prepare_hierarchy(
            two_axes,
            memberships=(SituationMembership(situation=events[0].id, unit=two_axes.units[0].id),),
            completeness="partial",
            reason="incompatible clocks",
        )
    all_origins = HierarchyGroup(
        key="both",
        kind="story",
        members=tuple(
            HierarchyRef(kind="anchor", key=a.id) for a in (*two_axes.events, *two_axes.units)
        ),
    )
    both = build_hierarchy(two_axes, prepare_hierarchy(two_axes, (all_origins,)))
    assert {support.source for support in both.segments[0].evidence} == {media.id, foreign.id}
    assert set(query_hierarchy(two_axes, both, both.root).situations) == {
        e.id for e in two_axes.events
    }


def test_deep_groups_iterate_and_v1_v2_policy_boundaries_are_strict():
    account, events, rows = _source()
    groups = [
        HierarchyGroup(
            key="g0", kind="scene", members=(HierarchyRef(kind="anchor", key=rows[0].id),)
        )
    ]
    groups.extend(
        HierarchyGroup(
            key=f"g{n}", kind="story", members=(HierarchyRef(kind="group", key=f"g{n - 1}"),)
        )
        for n in range(1, 1050)
    )
    spec = prepare_hierarchy(
        account,
        tuple(groups),
        memberships=(SituationMembership(situation=events[0].id, unit=rows[0].id),),
        completeness="partial",
        reason="other roots remain explicit",
    )
    result = build_hierarchy(account, spec)
    assert len(query_hierarchy(account, result, events[0].id).ancestors) == 1051
    v1 = SourceProjectionPolicy()
    assert "hierarchy" not in json.loads(v1.model_dump_json())
    with pytest.raises(ValueError):
        SourceProjectionPolicy(recipe="account-source-layers/v1", hierarchy=None)
    with pytest.raises(ValueError):
        SourceProjectionPolicy(
            recipe="account-source-layers/v2",
            hierarchy=spec,
            memberships={events[0].id: rows[0].id},
        )
    with pytest.raises(ValueError):
        build_hierarchy(account, spec.model_copy(update={"account_sha256": True}))
    with pytest.raises(ValueError):
        query_hierarchy(account, result.model_copy(update={"root": True}), result.root)
