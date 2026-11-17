"""Independent checks for explicit reconstruction over abstract AA/BB tokens."""

from __future__ import annotations

import hashlib
import json
import random
from fractions import Fraction
from itertools import product

import pytest

from storymodel.accounts import make_account
from storymodel.cli import main
from storymodel.local_evidence import LocalEvidenceSpec, RecallSelection
from storymodel.model import Context, Document, Event, Span, Support, Unit
from storymodel.reconstruction import (
    FillTarget,
    ReconstructionMass,
    ReconstructionPolicy,
    ReconstructionResult,
    ReconstructionRow,
    ReconstructionSpec,
    prepare_reconstruction_fill,
    reconstruct_accounts,
    reconstruct_rows,
    verify_account_reconstruction,
    verify_reconstruction,
)
from storymodel.sequence import SequenceState
from storymodel.source_transitions import SourceProjectionPolicy


def _support(document: Document, start: int, end: int) -> Support:
    return Support(document=document.id, spans=(Span(start=start, end=end),))


def _accounts():
    """Two independent account snapshots, with source events as leaf anchors."""
    source_document = Document.create("AA-source", "source", "AA BB CC")
    context = Context(side="source", kind="narrated")
    events = tuple(
        Event(
            side="source",
            context=context.id,
            predicate=token,
            evidence=(_support(source_document, i, i + 2),),
        )
        for token, i in (("AA", 0), ("BB", 3), ("CC", 6))
    )
    source = make_account(
        (source_document,), contexts=(context,), events=events, export_permission="allowed"
    )
    recall_document = Document.create("AA-recall", "recall", "AA BB CC")
    units = tuple(
        Unit(kind="recall", support=_support(recall_document, i, i + 2)) for i in (0, 3, 6)
    )
    recall = make_account((recall_document,), units=units, export_permission="allowed")
    return source, recall, events, units


def _source(anchor: str) -> SequenceState:
    return SequenceState(kind="source", anchor_kind="situation", anchor=anchor)


def _external() -> SequenceState:
    return SequenceState(kind="external", external="association")


def _row(unit: str, *entries: tuple[SequenceState, float]) -> ReconstructionRow:
    return ReconstructionRow(
        unit=unit,
        mass=tuple(ReconstructionMass(state=state, weight=weight) for state, weight in entries),
    )


def _oracle(groups: list[dict[int, float]], policy: ReconstructionPolicy) -> tuple[int, ...] | None:
    """Exhaustive Fraction decoder, intentionally independent of the production DP."""
    ranks = sorted({rank for row in groups for rank in row})
    if not groups or not ranks or policy.order == "unconstrained":
        return None
    paths = [
        path
        for path in product(range(len(ranks)), repeat=len(groups))
        if policy.order != "nondecreasing"
        or all(left <= right for left, right in zip(path, path[1:]))
    ]
    penalty = Fraction.from_float(
        policy.forward_penalty if policy.order == "nondecreasing" else policy.backward_penalty
    )

    def score(path: tuple[int, ...]) -> Fraction:
        total = sum(
            (Fraction.from_float(groups[i].get(ranks[j], 0.0)) for i, j in enumerate(path)),
            Fraction(),
        )
        for left, right in zip(path, path[1:]):
            distance = (
                max(0, ranks[right] - ranks[left] - 1)
                if policy.order == "nondecreasing"
                else max(0, left - right)
            )
            total -= penalty * distance
        return total

    best = max(score(path) for path in paths)
    tied = [path for path in paths if score(path) == best]
    # Scala's strict comparisons retain the earliest final index and then predecessors.
    return tuple(ranks[index] for index in min(tied, key=lambda path: tuple(reversed(path))))


@pytest.mark.parametrize(
    ("policy", "weights"),
    [
        (ReconstructionPolicy(), ((0.1, 0.9), (0.8, 0.2))),
        (ReconstructionPolicy(order="nondecreasing"), ((0.1, 0.9), (0.8, 0.2))),
        (
            ReconstructionPolicy(order="nondecreasing", forward_penalty=0.25),
            ((0.9, 0.1, 0.0), (0.0, 0.1, 0.9)),
        ),
        (
            ReconstructionPolicy(order="penalized-backward", backward_penalty=0.3),
            ((0.1, 0.2, 0.9), (0.9, 0.2, 0.1)),
        ),
    ],
)
def test_group_paths_match_an_exhaustive_fraction_oracle(policy, weights):
    source, recall, events, units = _accounts()
    roots = {event.id: rank for event, rank in zip(events, (1, 9, 100))}
    rows = tuple(
        _row(
            units[index].id,
            *tuple(
                (_source(event.id), float(weight)) for event, weight in zip(events, row_weights)
            ),
        )
        for index, row_weights in enumerate(weights)
    )
    result = reconstruct_rows(source, recall, rows, ReconstructionSpec(roots=roots, policy=policy))
    groups = [
        {rank: float(weight) for rank, weight in zip((1, 9, 100), row_weights) if weight > 0}
        for row_weights in weights
    ]
    expected = _oracle(groups, policy)
    assert tuple(decision.assigned_group for decision in result.decisions) == (
        (None,) * len(rows) if expected is None else expected
    )


def test_three_row_policy_grid_matches_exhaustive_fraction_oracle():
    """Forty-eight deterministic sparse grids exercise two backtracking steps."""
    source, recall, events, units = _accounts()
    roots = {event.id: rank for event, rank in zip(events, (1, 9, 100))}
    policies = (
        ReconstructionPolicy(),
        ReconstructionPolicy(order="nondecreasing"),
        ReconstructionPolicy(order="nondecreasing", forward_penalty=0.25),
        ReconstructionPolicy(order="penalized-backward", backward_penalty=0.3),
    )
    generator = random.Random(240117)
    values = (0.0, 0.125, 0.25, 0.5, 0.75)
    for case in range(48):
        weights = tuple(tuple(generator.choice(values) for _ in events) for _ in range(3))
        policy = policies[case % len(policies)]
        rows = tuple(
            _row(
                units[index].id,
                *tuple((_source(event.id), weight) for event, weight in zip(events, row_weights)),
            )
            for index, row_weights in enumerate(weights)
        )
        result = reconstruct_rows(
            source, recall, rows, ReconstructionSpec(roots=roots, policy=policy)
        )
        groups = [
            {rank: weight for rank, weight in zip((1, 9, 100), row_weights) if weight > 0}
            for row_weights in weights
        ]
        expected = _oracle(groups, policy)
        assert tuple(decision.assigned_group for decision in result.decisions) == (
            (None,) * len(rows) if expected is None else expected
        )


def test_ties_choose_earliest_final_rank_then_earliest_predecessors():
    source, recall, events, units = _accounts()
    rows = (
        _row(units[0].id, *tuple((_source(event.id), 1.0) for event in events)),
        _row(units[1].id, *tuple((_source(event.id), 1.0) for event in events)),
    )
    result = reconstruct_rows(
        source,
        recall,
        rows,
        ReconstructionSpec(
            roots={event.id: rank for event, rank in zip(events, (1, 9, 100))},
            policy=ReconstructionPolicy(order="nondecreasing"),
        ),
    )
    assert [decision.assigned_group for decision in result.decisions] == [1, 1]


def test_negative_zero_policy_knobs_have_one_canonical_identity():
    negative = ReconstructionPolicy.model_validate_json(
        '{"order":"nondecreasing","forward_penalty":-0.0}'
    )
    positive = ReconstructionPolicy(order="nondecreasing", forward_penalty=0.0)
    assert negative == positive
    assert "-0.0" not in negative.model_dump_json()


def test_raw_external_maximum_remains_distinct_from_anchor_aggregate_and_choice():
    source, recall, events, units = _accounts()
    distorted = SequenceState(
        kind="distorted", anchor_kind="situation", anchor=events[0].id, facets=("action",)
    )
    rows = (_row(units[0].id, (_external(), 0.9), (_source(events[0].id), 0.3), (distorted, 0.4)),)
    result = reconstruct_rows(source, recall, rows, ReconstructionSpec())
    decision = result.decisions[0]
    assert decision.raw_argmax == _external()
    assert decision.raw_argmax_mass == 0.9
    assert decision.raw_source_maximum == events[0].id
    assert decision.anchor == events[0].id and decision.chosen_mass == 0.7


def test_requested_fill_is_zero_mass_and_preserves_raw_source_fallback_without_a_request():
    source, recall, events, units = _accounts()
    roots = {events[0].id: 1, events[1].id: 9}
    fill = prepare_reconstruction_fill(
        source, recall, roots, (FillTarget(unit=units[1].id, group=9, anchor=events[1].id),)
    )
    rows = (
        _row(units[0].id, (_source(events[1].id), 1.0)),
        _row(units[1].id, (_source(events[0].id), 0.2)),
    )
    policy = ReconstructionPolicy(order="nondecreasing", fill="requested")
    filled = reconstruct_rows(
        source, recall, rows, ReconstructionSpec(roots=roots, policy=policy, fill=fill)
    )
    unfilled = reconstruct_rows(
        source,
        recall,
        rows,
        ReconstructionSpec(roots=roots, policy=ReconstructionPolicy(order="nondecreasing")),
    )
    assert filled.decisions[1].anchor == events[1].id
    assert filled.decisions[1].chosen_mass == 0 and filled.decisions[1].origin == "requested-fill"
    assert unfilled.decisions[1].anchor == events[0].id
    assert unfilled.decisions[1].origin == "raw-source-maximum"


def test_empty_zero_and_subnormalized_rows_are_inspectable():
    source, recall, events, units = _accounts()
    rows = (
        ReconstructionRow(unit=units[0].id, mass=()),
        _row(units[1].id, (_source(events[0].id), 0.0)),
        _row(units[2].id, (_source(events[1].id), 0.2)),
    )
    result = reconstruct_rows(source, recall, rows, ReconstructionSpec())
    assert [(d.anchor, d.chosen_mass) for d in result.decisions] == [
        (None, None),
        (None, None),
        (events[1].id, 0.2),
    ]


def test_reconstruction_refuses_wrong_membership_and_receipt_reuse():
    source, recall, events, units = _accounts()
    rows = (_row(units[0].id, (_source(events[0].id), 1.0)),)
    with pytest.raises(ValueError, match="foreign group root"):
        reconstruct_rows(source, recall, rows, ReconstructionSpec(roots={"AA": 1}))
    wrong_kind = SequenceState(kind="source", anchor_kind="segment", anchor=events[0].id)
    with pytest.raises(ValueError, match="wrong anchor kind"):
        reconstruct_rows(
            source, recall, (_row(units[0].id, (wrong_kind, 1.0)),), ReconstructionSpec()
        )
    result = reconstruct_rows(source, recall, rows, ReconstructionSpec())
    assert verify_reconstruction(source, recall, rows, ReconstructionSpec(), result) == result
    with pytest.raises(ValueError, match="differs"):
        verify_reconstruction(
            source, recall, rows, ReconstructionSpec(), result.model_copy(update={"decisions": ()})
        )
    wire_forgery = result.model_dump(mode="json")
    wire_forgery["decisions"] = []
    with pytest.raises(ValueError, match="differs"):
        verify_reconstruction(
            source,
            recall,
            rows,
            ReconstructionSpec(),
            ReconstructionResult.model_validate_json(json.dumps(wire_forgery)),
        )


def test_overlapping_derived_membership_roots_refuse():
    source, recall, events, units = _accounts()
    segment = Unit(kind="row", support=_support(source.documents[0], 0, 2))
    source = source.model_copy(update={"units": (segment,)})
    with pytest.raises(ValueError, match="groups overlap"):
        reconstruct_rows(
            source,
            recall,
            (_row(units[0].id, (_source(events[0].id), 1.0)),),
            ReconstructionSpec(roots={segment.id: 1, events[0].id: 9}),
            projection=SourceProjectionPolicy(memberships={events[0].id: segment.id}),
        )


def test_fill_rejects_foreign_recall_and_snapshot_mismatch():
    source, recall, events, units = _accounts()
    roots = {events[0].id: 1}
    with pytest.raises(ValueError, match="foreign fill unit"):
        prepare_reconstruction_fill(
            source, recall, roots, (FillTarget(unit="AA", group=1, anchor=events[0].id),)
        )
    fill = prepare_reconstruction_fill(
        source, recall, roots, (FillTarget(unit=units[0].id, group=1, anchor=events[0].id),)
    )
    spec = ReconstructionSpec(
        roots=roots, policy=ReconstructionPolicy(order="nondecreasing", fill="requested"), fill=fill
    )
    changed_recall = recall.model_copy(update={"export_permission": "denied"})
    with pytest.raises(ValueError, match="different source, recall"):
        reconstruct_rows(
            source, changed_recall, (_row(units[0].id, (_source(events[0].id), 1.0)),), spec
        )


def test_finite_mass_overflow_refuses_and_a_penalized_leap_is_not_free():
    source, recall, events, units = _accounts()
    largest = float.fromhex("0x1.fffffffffffffp+1023")
    with pytest.raises(ValueError, match="row mass overflow"):
        reconstruct_rows(
            source,
            recall,
            (
                _row(
                    units[0].id, (_source(events[0].id), largest), (_source(events[1].id), largest)
                ),
            ),
            ReconstructionSpec(),
        )
    rows = (
        _row(units[0].id, (_source(events[0].id), 0.75), (_source(events[1].id), 0.25)),
        _row(units[1].id, (_source(events[0].id), 0.0), (_source(events[1].id), 0.0)),
    )
    result = reconstruct_rows(
        source,
        recall,
        rows,
        ReconstructionSpec(
            roots={events[0].id: 1, events[1].id: 100},
            policy=ReconstructionPolicy(order="nondecreasing", forward_penalty=1e20),
        ),
    )
    assert [d.assigned_group for d in result.decisions] == [1, 1]


def test_near_cancellation_preserves_the_one_unit_advantage_until_backtracking():
    source, recall, events, units = _accounts()
    rows = (
        _row(units[0].id, (_source(events[0].id), 1e20), (_source(events[1].id), 0.0)),
        _row(units[1].id, (_source(events[0].id), 0.0), (_source(events[1].id), 1.0)),
    )
    result = reconstruct_rows(
        source,
        recall,
        rows,
        ReconstructionSpec(
            roots={events[0].id: 1, events[1].id: 9},
            policy=ReconstructionPolicy(order="nondecreasing"),
        ),
    )
    assert [decision.assigned_group for decision in result.decisions] == [1, 9]


def test_transition_penalty_overflow_refuses_instead_of_becoming_a_free_jump():
    source, recall, events, units = _accounts()
    rows = (
        _row(units[0].id, (_source(events[0].id), 1.0)),
        _row(units[1].id, (_source(events[1].id), 1.0)),
    )
    with pytest.raises(ValueError, match="transition penalty overflow"):
        reconstruct_rows(
            source,
            recall,
            rows,
            ReconstructionSpec(
                roots={events[0].id: 1, events[1].id: 9},
                policy=ReconstructionPolicy(
                    order="nondecreasing",
                    forward_penalty=float.fromhex("0x1.fffffffffffffp+1023"),
                ),
            ),
        )


def test_account_reconstruction_cli_is_file_independent_and_verification_allows_denied_inputs(
    tmp_path, capsys
):
    source, recall, events, units = _accounts()
    evidence = LocalEvidenceSpec(
        units=(RecallSelection(unit=units[0].id, targets=(events[0].id,)),)
    )
    spec = ReconstructionSpec(
        roots={events[0].id: 1}, policy=ReconstructionPolicy(order="nondecreasing")
    )
    source_path, recall_path, evidence_path, spec_path = (
        tmp_path / name for name in ("source.json", "recall.json", "evidence.json", "spec.json")
    )
    for path, artifact in zip(
        (source_path, recall_path, evidence_path, spec_path), (source, recall, evidence, spec)
    ):
        path.write_text(artifact.model_dump_json())
    output = tmp_path / "result.json"
    source_file_hash = hashlib.sha256(source_path.read_bytes()).hexdigest()
    recall_file_hash = hashlib.sha256(recall_path.read_bytes()).hexdigest()
    assert (
        main(
            [
                "reconstruct-accounts",
                str(source_path),
                str(recall_path),
                str(evidence_path),
                str(spec_path),
                "--source-sha256",
                source_file_hash,
                "--recall-sha256",
                recall_file_hash,
                "--output",
                str(output),
            ]
        )
        == 0
    )
    direct = reconstruct_accounts(source, recall, evidence, spec)
    assert json.loads(output.read_text()) == direct.model_dump(mode="json")
    assert (
        main(
            [
                "verify-account-reconstruction",
                str(source_path),
                str(recall_path),
                str(evidence_path),
                str(spec_path),
                str(output),
                "--source-sha256",
                source_file_hash,
                "--recall-sha256",
                recall_file_hash,
            ]
        )
        == 0
    )
    assert json.loads(capsys.readouterr().out)["valid"] is True

    denied_source = source.model_copy(update={"export_permission": "denied"})
    source_path.write_text(denied_source.model_dump_json())
    assert (
        main(
            [
                "reconstruct-accounts",
                str(source_path),
                str(recall_path),
                str(tmp_path / "unreadable.json"),
                str(tmp_path / "also-unreadable.json"),
            ]
        )
        == 2
    )
    assert "denies source reconstruction export" in capsys.readouterr().err

    denied_result = reconstruct_accounts(denied_source, recall, evidence, spec)
    output.write_text(denied_result.model_dump_json())
    assert (
        verify_account_reconstruction(denied_source, recall, evidence, spec, denied_result)
        == denied_result
    )
    assert (
        main(
            [
                "verify-account-reconstruction",
                str(source_path),
                str(recall_path),
                str(evidence_path),
                str(spec_path),
                str(output),
            ]
        )
        == 0
    )
    assert json.loads(capsys.readouterr().out)["valid"] is True


def test_fill_request_policy_targets_and_input_bindings_are_all_checked():
    source, recall, events, units = _accounts()
    roots = {events[0].id: 1, events[1].id: 9}
    target = FillTarget(unit=units[0].id, group=1, anchor=events[0].id)
    with pytest.raises(ValueError, match="leaf in the requested group"):
        prepare_reconstruction_fill(
            source, recall, roots, (target.model_copy(update={"group": 9}),)
        )
    with pytest.raises(ValueError, match="duplicate fill"):
        prepare_reconstruction_fill(source, recall, roots, (target, target))
    fill = prepare_reconstruction_fill(source, recall, roots, (target,))
    rows = (_row(units[0].id, (_source(events[0].id), 1.0)),)
    disabled = ReconstructionSpec(
        roots=roots, policy=ReconstructionPolicy(order="nondecreasing"), fill=fill
    )
    with pytest.raises(ValueError, match="fill is disabled"):
        reconstruct_rows(source, recall, rows, disabled)
    enabled = ReconstructionSpec(
        roots=roots, policy=ReconstructionPolicy(order="nondecreasing", fill="requested"), fill=fill
    )
    assert (
        reconstruct_rows(source, recall, rows, enabled).decisions[0].origin == "group-constrained"
    )
    with pytest.raises(ValueError, match="absent from reconstruction rows"):
        reconstruct_rows(source, recall, (), enabled)
    with pytest.raises(ValueError, match="different source, recall"):
        reconstruct_rows(
            source.model_copy(update={"export_permission": "denied"}), recall, rows, enabled
        )
    with pytest.raises(ValueError, match="different source, recall"):
        reconstruct_rows(
            source,
            recall,
            rows,
            enabled,
            projection=SourceProjectionPolicy(scoring_documents=(source.documents[0].id,)),
        )
    with pytest.raises(ValueError, match="different source, recall"):
        reconstruct_rows(
            source,
            recall,
            rows,
            enabled.model_copy(update={"roots": {events[0].id: 2, events[1].id: 9}}),
        )


def test_forged_policy_and_weights_cannot_be_coerced_at_public_boundaries():
    source, recall, events, units = _accounts()
    row = _row(units[0].id, (_source(events[0].id), 1.0))
    bad_weight = row.model_copy(update={"mass": (row.mass[0].model_copy(update={"weight": True}),)})
    with pytest.raises(ValueError):
        reconstruct_rows(source, recall, (bad_weight,), ReconstructionSpec())
    bad_policy = ReconstructionPolicy(order="nondecreasing").model_copy(
        update={"forward_penalty": True}
    )
    bad_spec = ReconstructionSpec().model_copy(update={"policy": bad_policy})
    with pytest.raises(ValueError):
        reconstruct_rows(source, recall, (row,), bad_spec)
    for kwargs in (
        {"backward_penalty": 1.0},
        {"forward_penalty": 1.0},
        {"order": "unconstrained", "fill": "requested"},
        {"order": "penalized-backward", "backward_penalty": float("nan")},
        {"order": "nondecreasing", "forward_penalty": -1.0},
    ):
        with pytest.raises(ValueError):
            ReconstructionPolicy(**kwargs)


def test_reconstruction_wire_schema_matches_public_models():
    from pathlib import Path

    from storymodel.reconstruction import AccountReconstructionResult

    path = Path(__file__).parents[1] / "schemas/reconstruction.json"
    assert json.loads(path.read_text()) == {
        "spec": ReconstructionSpec.model_json_schema(),
        "rows_result": ReconstructionResult.model_json_schema(),
        "account_result": AccountReconstructionResult.model_json_schema(),
    }
