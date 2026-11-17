"""Independent organization readout checks over abstract AA/BB/CC accounts."""

from __future__ import annotations

import json
from itertools import product
from pathlib import Path

import pytest

from storymodel.accounts import make_account
from storymodel.cli import main
from storymodel.local_evidence import LocalEvidenceSpec, RecallSelection, infer_accounts
from storymodel.model import Context, Document, Event, Span, Support, Unit
from storymodel.organization import (
    AccountOrganizationResult,
    DestinationMass,
    JointMass,
    OrganizationDestination,
    OrganizationJoint,
    OrganizationOrder,
    OrganizationResult,
    OrganizationRow,
    OrganizationSpec,
    organization_readout,
    organize_accounts,
    verify_account_organization,
    verify_organization_readout,
)


def _support(document: Document, start: int, end: int) -> Support:
    return Support(document=document.id, spans=(Span(start=start, end=end),))


def _accounts(*, units: int = 3):
    source_document = Document.create("AA-source", "source", "AA BB CC")
    context = Context(side="source", kind="narrated")
    events = tuple(
        Event(
            side="source",
            context=context.id,
            predicate=token,
            evidence=(_support(source_document, index, index + 2),),
        )
        for token, index in (("AA", 0), ("BB", 3), ("CC", 6))
    )
    source = make_account(
        (source_document,), contexts=(context,), events=events, export_permission="allowed"
    )
    recall_document = Document.create("AA-recall", "recall", "AA BB CC")
    recalls = tuple(
        Unit(kind="recall", support=_support(recall_document, index, index + 2))
        for index in (0, 3, 6)[:units]
    )
    recall = make_account((recall_document,), units=recalls, export_permission="allowed")
    return source, recall, events, recalls


def _destination(target: str) -> OrganizationDestination:
    return OrganizationDestination(target=target)


def _external(value: str) -> OrganizationDestination:
    return OrganizationDestination(external=value)  # type: ignore[arg-type]


def _row(unit: str, *entries: tuple[OrganizationDestination, float]) -> OrganizationRow:
    return OrganizationRow(
        unit=unit,
        mass=tuple(
            DestinationMass(destination=destination, weight=weight)
            for destination, weight in entries
        ),
    )


def _order(events) -> OrganizationOrder:
    return OrganizationOrder(positions={event.id: rank for rank, event in enumerate(events, 1)})


def test_coherent_bounds_match_all_343_subset_triples():
    source, recall, events, units = _accounts()
    destinations = tuple(_destination(event.id) for event in events)
    order = _order(events)
    # All nonempty subsets across three rows (7**3): coherent assignments must span the oracle.
    for subsets in product(range(1, 8), repeat=3):
        rows = tuple(
            _row(
                unit.id,
                *tuple(
                    (destinations[index], 1.0 / mask.bit_count())
                    for index in range(3)
                    if mask & (1 << index)
                ),
            )
            for unit, mask in zip(units, subsets)
        )
        result = organization_readout(
            source, recall, OrganizationSpec(rows=rows, order=order, dependence="independent-rows")
        )
        assert result.bounds is not None
        paths = tuple(product(*(tuple(i for i in range(3) if mask & (1 << i)) for mask in subsets)))
        # Compare integer ranks directly, independently of destination classification and DP.
        for category, compare in (
            ("backward", lambda a, b: b < a),
            ("forward", lambda a, b: b > a),
            ("same-target", lambda a, b: b == a),
        ):
            values = [sum(compare(a, b) for a, b in zip(path, path[1:])) for path in paths]
            bound = result.bounds[category]
            assert (bound.minimum, bound.maximum) == (min(values), max(values))
            assert result.counts[category] == pytest.approx(sum(values) / len(values), abs=1e-12)
        assert all(
            result.counts[c] == 0 for c in ("failed", "unresolved", "external", "incomparable")
        )


def test_coherent_middle_choice_prevents_independent_pair_bounds():
    source, recall, events, units = _accounts()
    rows = (
        _row(units[0].id, (_destination(events[1].id), 1.0)),
        _row(units[1].id, (_destination(events[0].id), 0.5), (_destination(events[2].id), 0.5)),
        _row(units[2].id, (_destination(events[1].id), 1.0)),
    )
    result = organization_readout(
        source,
        recall,
        OrganizationSpec(rows=rows, order=_order(events), dependence="independent-rows"),
    )
    assert result.bounds is not None
    assert result.bounds["backward"].minimum == result.bounds["backward"].maximum == 1


def test_adjacent_joint_flow_changes_counts_with_identical_marginals():
    source, recall, events, units = _accounts(units=2)
    first, second = (_destination(event.id) for event in events[:2])
    rows = (
        _row(units[0].id, (first, 0.5), (second, 0.5)),
        _row(units[1].id, (first, 0.5), (second, 0.5)),
    )
    diagonal = OrganizationJoint(
        left_unit=units[0].id,
        right_unit=units[1].id,
        mass=(
            JointMass(left=first, right=first, weight=0.5),
            JointMass(left=second, right=second, weight=0.5),
        ),
    )
    cross = OrganizationJoint(
        left_unit=units[0].id,
        right_unit=units[1].id,
        mass=(
            JointMass(left=first, right=second, weight=0.5),
            JointMass(left=second, right=first, weight=0.5),
        ),
    )

    def read(joint):
        return organization_readout(
            source,
            recall,
            OrganizationSpec(
                rows=rows,
                order=_order(events),
                dependence="adjacent-joints",
                joints=(joint,),
                product_bounds=False,
            ),
        )

    assert read(diagonal).counts["same-target"] == 1.0
    assert read(cross).counts["same-target"] == 0.0
    assert read(cross).counts["backward"] == read(cross).counts["forward"] == 0.5
    changed_rows = (_row(units[0].id, (first, 0.75), (second, 0.25)), rows[1])
    with pytest.raises(ValueError, match="actual row marginals"):
        organization_readout(
            source,
            recall,
            OrganizationSpec(
                rows=changed_rows,
                order=_order(events),
                dependence="adjacent-joints",
                joints=(diagonal,),
                product_bounds=False,
            ),
        )


def test_unavailable_middle_is_not_spliced_and_categories_remain_explicit():
    source, recall, events, units = _accounts()
    spec = OrganizationSpec(
        rows=(
            _row(units[0].id, (_destination(events[2].id), 1.0)),
            OrganizationRow(unit=units[1].id, status="unresolved"),
            _row(units[2].id, (_destination(events[0].id), 1.0)),
        ),
        order=_order(events),
        dependence="independent-rows",
    )
    result = organization_readout(source, recall, spec)
    assert result.adjacency_count == 2 and result.counts["unresolved"] == 2.0
    assert result.counts["backward"] == 0.0 and result.bounds_status == "unavailable-rows"

    failed_precedence = organization_readout(
        source,
        recall,
        OrganizationSpec(
            rows=(
                _row(units[0].id, (_destination(events[0].id), 1.0)),
                OrganizationRow(unit=units[1].id, status="failed"),
                OrganizationRow(unit=units[2].id, status="unresolved"),
            ),
            dependence="independent-rows",
        ),
    )
    assert failed_precedence.counts["failed"] == 2.0
    assert failed_precedence.counts["unresolved"] == 0.0

    pairs = (("external", _external("association")), ("unresolved", _external("unranked")))
    for category, destination in pairs:
        readout = organization_readout(
            source,
            recall,
            OrganizationSpec(
                rows=(
                    _row(units[0].id, (_destination(events[0].id), 1.0)),
                    _row(units[1].id, (destination, 1.0)),
                ),
                order=_order(events),
                dependence="independent-rows",
            ),
        )
        assert readout.counts[category] == 1.0


def test_precedence_equal_rank_and_unranked_targets_have_distinct_categories():
    source, recall, events, units = _accounts(units=2)
    first, last = _destination(events[0].id), _destination(events[2].id)

    def category(order):
        return organization_readout(
            source,
            recall,
            OrganizationSpec(
                rows=(_row(units[0].id, (last, 1)), _row(units[1].id, (first, 1))),
                order=order,
                dependence="independent-rows",
            ),
        ).counts

    assert category(_order(events))["backward"] == 1.0
    assert category(OrganizationOrder())["incomparable"] == 1.0
    assert (
        category(OrganizationOrder(positions={events[0].id: 1, events[2].id: 1}))["incomparable"]
        == 1.0
    )


def test_zero_one_rows_and_admissible_storage_order_are_well_defined():
    source, recall, events, units = _accounts(units=1)
    empty = organization_readout(
        source, recall, OrganizationSpec(rows=(), dependence="independent-rows")
    )
    assert (
        empty.adjacency_count == empty.available_units == 0
        and empty.bounds_status == "product-space"
    )
    only = _row(units[0].id, (_destination(events[0].id), 1))
    one = organization_readout(
        source, recall, OrganizationSpec(rows=(only,), dependence="independent-rows")
    )
    assert one.adjacency_count == 0 and all(value == 0.0 for value in one.counts.values())
    reversed_admissible = only.model_copy(
        update={"admissible": tuple(reversed(only.admissible or ()))}
    )
    assert (
        organization_readout(
            source,
            recall,
            OrganizationSpec(rows=(reversed_admissible,), dependence="independent-rows"),
        )
        == one
    )


def test_contract_rejects_invalid_rows_joints_foreign_inputs_and_forged_receipts():
    source, recall, events, units = _accounts(units=2)
    target = _destination(events[0].id)
    foreign = _destination("foreign-target")
    with pytest.raises(ValueError):
        OrganizationRow(unit=units[0].id, mass=(DestinationMass(destination=target, weight=0.9),))
    with pytest.raises(ValueError):
        OrganizationRow(
            unit=units[0].id,
            mass=(
                DestinationMass(destination=target, weight=0.5),
                DestinationMass(destination=target, weight=0.5),
            ),
        )
    with pytest.raises(ValueError):
        OrganizationRow(
            unit=units[0].id,
            mass=(DestinationMass(destination=target, weight=1.0),),
            admissible=(foreign,),
        )
    with pytest.raises(ValueError):
        OrganizationSpec(rows=(), dependence="adjacent-joints")
    rows = (_row(units[0].id, (target, 1)), _row(units[1].id, (target, 1)))
    bad = OrganizationJoint(
        left_unit=units[0].id,
        right_unit=units[1].id,
        mass=(JointMass(left=target, right=target, weight=0.9),),
    )
    spec = OrganizationSpec(
        rows=rows, dependence="adjacent-joints", joints=(bad,), product_bounds=False
    )
    with pytest.raises(ValueError, match="joint"):
        organization_readout(source, recall, spec)
    with pytest.raises(ValueError, match="foreign organization"):
        organization_readout(
            source,
            recall,
            OrganizationSpec(rows=(_row("foreign", (target, 1)),), dependence="independent-rows"),
        )
    with pytest.raises(ValueError, match="foreign organization target"):
        organization_readout(
            source,
            recall,
            OrganizationSpec(
                rows=(_row(units[0].id, (foreign, 1)),), dependence="independent-rows"
            ),
        )
    with pytest.raises(ValueError, match="foreign organization order"):
        organization_readout(
            source,
            recall,
            OrganizationSpec(
                rows=(_row(units[0].id, (target, 1)),),
                order=OrganizationOrder(positions={"foreign-target": 1}),
                dependence="independent-rows",
            ),
        )
    with pytest.raises(ValueError, match="duplicate or foreign organization unit"):
        organization_readout(
            source,
            recall,
            OrganizationSpec(
                rows=(_row(units[0].id, (target, 1)), _row(units[0].id, (target, 1))),
                dependence="independent-rows",
            ),
        )
    foreign_joint = OrganizationJoint(
        left_unit=units[0].id,
        right_unit=units[1].id,
        mass=(JointMass(left=foreign, right=target, weight=1.0),),
    )
    with pytest.raises(ValueError, match="joint has duplicate or foreign"):
        organization_readout(
            source,
            recall,
            OrganizationSpec(
                rows=rows,
                dependence="adjacent-joints",
                joints=(foreign_joint,),
                product_bounds=False,
            ),
        )
    valid = OrganizationSpec(rows=rows, dependence="independent-rows")
    result = organization_readout(source, recall, valid)
    bad_numeric = rows[0].model_copy(
        update={"mass": (rows[0].mass[0].model_copy(update={"weight": True}),)}
    )
    with pytest.raises(ValueError):
        organization_readout(
            source,
            recall,
            OrganizationSpec(rows=(bad_numeric,), dependence="independent-rows"),
        )
    with pytest.raises(ValueError):
        verify_organization_readout(
            source, recall, valid, result.model_copy(update={"adjacency_count": True})
        )
    assert result.bounds is not None
    forged_bound = result.bounds["backward"].model_copy(update={"minimum": True})
    forged_result = result.model_copy(
        update={"bounds": {**result.bounds, "backward": forged_bound}}
    )
    with pytest.raises(ValueError):
        verify_organization_readout(source, recall, valid, forged_result)
    with pytest.raises(ValueError):
        organization_readout(source.model_copy(update={"unexpected": 1}), recall, valid)


def test_joint_total_check_rejects_accumulated_tolerance_slack():
    source, recall, events, units = _accounts(units=2)
    first, second = (_destination(event.id) for event in events[:2])
    rows = (
        _row(units[0].id, (first, 0.5), (second, 0.5)),
        _row(units[1].id, (first, 0.5), (second, 0.5)),
    )
    slack = 4e-10
    joint = OrganizationJoint(
        left_unit=units[0].id,
        right_unit=units[1].id,
        mass=tuple(
            JointMass(left=left, right=right, weight=0.25 + slack)
            for left in (first, second)
            for right in (first, second)
        ),
    )
    with pytest.raises(ValueError, match="joint mass must sum to one"):
        organization_readout(
            source,
            recall,
            OrganizationSpec(
                rows=rows,
                order=_order(events),
                dependence="adjacent-joints",
                joints=(joint,),
                product_bounds=False,
            ),
        )


def test_account_wrapper_retains_joint_flow_and_inference_not_measurement_claim():
    source, recall, events, units = _accounts(units=2)
    evidence = LocalEvidenceSpec(
        units=tuple(
            RecallSelection(unit=unit.id, targets=(events[index].id,))
            for index, unit in enumerate(units)
        )
    )
    result = organize_accounts(source, recall, evidence, _order(events))
    assert result.interpretation == "model-conditional-not-independent-measurement"
    assert result.organization.spec.dependence == "adjacent-joints"
    assert result.organization.spec.product_bounds is False
    assert result.organization.spec.joints
    assert verify_account_organization(source, recall, evidence, _order(events), result) == result


def test_organization_wire_schema_matches_public_models():
    path = Path(__file__).parents[1] / "schemas/organization.json"
    assert json.loads(path.read_text()) == {
        "spec": OrganizationSpec.model_json_schema(),
        "order": OrganizationOrder.model_json_schema(),
        "result": OrganizationResult.model_json_schema(),
        "account_result": AccountOrganizationResult.model_json_schema(),
    }


def test_organization_cli_matches_api_and_enforces_export_before_later_reads(tmp_path, capsys):
    source, recall, events, units = _accounts(units=2)
    rows = tuple(_row(unit.id, (_destination(event.id), 1.0)) for unit, event in zip(units, events))
    spec = OrganizationSpec(rows=rows, order=_order(events), dependence="independent-rows")
    evidence = LocalEvidenceSpec(
        units=tuple(
            RecallSelection(unit=unit.id, targets=(event.id,)) for unit, event in zip(units, events)
        )
    )
    order = _order(events)
    inference = infer_accounts(source, recall, evidence)
    source_path, recall_path = tmp_path / "source.json", tmp_path / "recall.json"
    spec_path, order_path, evidence_path = (
        tmp_path / "spec.json",
        tmp_path / "order.json",
        tmp_path / "evidence.json",
    )
    inference_path = tmp_path / "inference.json"
    organization_path, account_path = tmp_path / "organization.json", tmp_path / "account.json"
    source_path.write_text(source.model_dump_json())
    recall_path.write_text(recall.model_dump_json())
    spec_path.write_text(spec.model_dump_json())
    order_path.write_text(order.model_dump_json())
    evidence_path.write_text(evidence.model_dump_json())
    inference_path.write_text(inference.model_dump_json())

    assert (
        main(
            [
                "organization-readout",
                str(source_path),
                str(recall_path),
                str(spec_path),
                "--output",
                str(organization_path),
            ]
        )
        == 0
    )
    saved = OrganizationResult.model_validate_json(organization_path.read_text())
    assert saved == organization_readout(source, recall, spec)
    assert (
        main(
            [
                "verify-organization-readout",
                str(source_path),
                str(recall_path),
                str(spec_path),
                str(organization_path),
            ]
        )
        == 0
    )
    assert json.loads(capsys.readouterr().out)["valid"] is True

    assert (
        main(
            [
                "organize-accounts",
                str(source_path),
                str(recall_path),
                str(evidence_path),
                str(order_path),
                "--inference",
                str(inference_path),
                "--output",
                str(account_path),
            ]
        )
        == 0
    )
    account = AccountOrganizationResult.model_validate_json(account_path.read_text())
    assert account == organize_accounts(source, recall, evidence, order, inference=inference)
    assert (
        main(
            [
                "verify-account-organization",
                str(source_path),
                str(recall_path),
                str(evidence_path),
                str(order_path),
                str(account_path),
            ]
        )
        == 0
    )
    assert json.loads(capsys.readouterr().out)["valid"] is True

    denied_source = tmp_path / "denied-source.json"
    denied_source.write_text(
        source.model_copy(update={"export_permission": "denied"}).model_dump_json()
    )
    missing = tmp_path / "missing.json"
    assert (
        main(
            [
                "organization-readout",
                str(denied_source),
                str(recall_path),
                str(missing),
                "--output",
                str(tmp_path / "refused.json"),
            ]
        )
        == 2
    )
    assert "denies source organization export" in capsys.readouterr().err
    assert (
        main(
            [
                "organize-accounts",
                str(denied_source),
                str(recall_path),
                str(missing),
                str(missing),
                "--inference",
                str(missing),
                "--output",
                str(tmp_path / "refused-account.json"),
            ]
        )
        == 2
    )
    assert "denies source organization export" in capsys.readouterr().err

    denied_recall = tmp_path / "denied-recall.json"
    denied_recall.write_text(
        recall.model_copy(update={"export_permission": "denied"}).model_dump_json()
    )
    assert (
        main(
            [
                "organization-readout",
                str(source_path),
                str(denied_recall),
                str(missing),
                "--output",
                str(tmp_path / "refused-recall.json"),
            ]
        )
        == 2
    )
    assert "denies recall organization export" in capsys.readouterr().err
    denied_source_value = source.model_copy(update={"export_permission": "denied"})
    denied_result = organization_readout(denied_source_value, recall, spec)
    denied_result_path = tmp_path / "denied-result.json"
    denied_result_path.write_text(denied_result.model_dump_json())
    assert (
        main(
            [
                "verify-organization-readout",
                str(denied_source),
                str(recall_path),
                str(spec_path),
                str(denied_result_path),
            ]
        )
        == 0
    )
    assert json.loads(capsys.readouterr().out)["valid"] is True
    denied_account = organize_accounts(denied_source_value, recall, evidence, order)
    denied_account_path = tmp_path / "denied-account.json"
    denied_account_path.write_text(denied_account.model_dump_json())
    assert (
        main(
            [
                "verify-account-organization",
                str(denied_source),
                str(recall_path),
                str(evidence_path),
                str(order_path),
                str(denied_account_path),
            ]
        )
        == 0
    )
    assert json.loads(capsys.readouterr().out)["valid"] is True


def test_tiny_positive_products_survive_until_category_aggregation():
    source, recall, events, units = _accounts(units=2)
    first, second, third = (_destination(event.id) for event in events)
    tiny = float.fromhex("0x0.0000000000001p-1022")
    rows = (
        _row(units[0].id, (first, tiny), (second, 1.0)),
        _row(units[1].id, (third, 0.5), (second, 0.5)),
    )
    order = OrganizationOrder(positions={events[0].id: 3.0, events[1].id: 0.0, events[2].id: 1.0})
    result = organization_readout(
        source, recall, OrganizationSpec(rows=rows, order=order, dependence="independent-rows")
    )
    # Two products each round to zero in binary64, but their backward-category sum is tiny.
    assert tiny * 0.5 == 0
    assert result.counts["backward"] == result.backward_rate == tiny
    assert result.counts["forward"] == result.counts["same-target"] == 0.5


def test_positive_final_underflow_refuses_instead_of_reporting_zero_mass():
    source, recall, events, units = _accounts(units=2)
    first, second = (_destination(event.id) for event in events[:2])
    tiny = float.fromhex("0x0.0000000000001p-1022")
    rows = (
        _row(units[0].id, (first, tiny), (second, 1 - tiny)),
        _row(units[1].id, (first, 0.5), (second, 0.5)),
    )
    with pytest.raises(ValueError, match="positive transition category mass"):
        organization_readout(
            source,
            recall,
            OrganizationSpec(rows=rows, order=_order(events), dependence="independent-rows"),
        )


def test_candidate_storage_permutation_preserves_readout_values():
    source, recall, events, units = _accounts(units=2)
    destinations = tuple(_destination(event.id) for event in events)
    rows = tuple(
        _row(unit.id, *tuple(zip(destinations, weights)))
        for unit, weights in zip(units, ((0.2, 0.3, 0.5), (0.4, 0.4, 0.2)))
    )
    spec = OrganizationSpec(rows=rows, order=_order(events), dependence="independent-rows")
    result = organization_readout(source, recall, spec)
    permuted = tuple(
        row.model_copy(
            update={
                "mass": tuple(reversed(row.mass)),
                "admissible": tuple(reversed(row.admissible)),
            }
        )
        for row in rows
    )
    changed = organization_readout(source, recall, spec.model_copy(update={"rows": permuted}))
    assert changed.spec != result.spec
    assert changed.counts == result.counts and changed.bounds == result.bounds
    assert changed.backward_rate == result.backward_rate


def test_account_flow_treats_faithful_and_distorted_modes_as_the_same_target():
    import math

    from storymodel.model import State
    from storymodel.rich_account import upgrade_account, validate_rich_account

    source, recall, events, units = _accounts(units=2)
    anchor = State(
        side="source",
        context=source.contexts[0].id,
        predicate="AA",
        evidence=events[0].evidence,
        polarity="positive",
        modality="asserted",
    )
    source = validate_rich_account(upgrade_account(source).model_copy(update={"states": (anchor,)}))
    states = tuple(
        State(
            side="recall",
            context=recall.contexts[0].id,
            predicate="AA",
            evidence=(unit.support,),
            polarity=polarity,
            modality="asserted",
        )
        for unit, polarity in zip(units, ("positive", "negative"))
    )
    recall = validate_rich_account(upgrade_account(recall).model_copy(update={"states": states}))
    evidence = LocalEvidenceSpec(
        units=tuple(RecallSelection(unit=u.id, targets=(anchor.id,)) for u in units)
    )
    result = organize_accounts(source, recall, evidence, OrganizationOrder())
    populations = [
        {cell.state.id: cell.state for cell in row.costs}
        for row in result.inference.evidence.sequence.request.rows
    ]
    assert [
        {state.kind for state in population.values() if state.anchor == anchor.id}
        for population in populations
    ] == [{"source"}, {"distorted"}]
    expected = math.fsum(
        m.probability
        for m in result.inference.inference.flow[0].mass
        if populations[0][m.from_state].anchor == anchor.id == populations[1][m.to_state].anchor
    )
    assert expected > 0
    assert result.organization.counts["same-target"] == pytest.approx(expected, abs=1e-15)
    assert result.organization.counts["incomparable"] == 0
