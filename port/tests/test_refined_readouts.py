"""Refined-inference consumers retain their checked model family and readout inputs."""

from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path

import pytest

from storymodel.cli import main
from storymodel.local_evidence import infer_accounts, infer_refined_accounts
from storymodel.organization import (
    OrganizationOrder,
    RefinedAccountOrganizationResult,
    organize_accounts,
    verify_account_organization,
)
from storymodel.reconstruction import (
    ReconstructionPolicy,
    ReconstructionSpec,
    RefinedAccountReconstructionResult,
    reconstruct_accounts,
    verify_account_reconstruction,
)
from storymodel.source_transitions import TransitionWeights


@pytest.fixture
def refined_example(monkeypatch):
    monkeypatch.syspath_prepend(str(Path(__file__).resolve().parents[1] / "examples"))
    from refined_alignment import example

    return example()


def _order(source) -> OrganizationOrder:
    return OrganizationOrder(positions={state.id: rank for rank, state in enumerate(source.states)})


def _reconstruction_spec(source) -> ReconstructionSpec:
    return ReconstructionSpec(
        roots={state.id: rank for rank, state in enumerate(source.states)},
        policy=ReconstructionPolicy(order="nondecreasing"),
    )


def _destination(state):
    return state.anchor, state.external


def _category(left, right, order: OrganizationOrder) -> str:
    left_target, left_external = left
    right_target, right_external = right
    if left_external == "unranked" or right_external == "unranked":
        return "unresolved"
    if left_external is not None or right_external is not None:
        return "external"
    assert left_target is not None and right_target is not None
    if left_target == right_target:
        return "same-target"
    left_rank, right_rank = order.positions.get(left_target), order.positions.get(right_target)
    if left_rank is None or right_rank is None or left_rank == right_rank:
        return "incomparable"
    return "backward" if right_rank < left_rank else "forward"


def _marginals(inference):
    states = {
        row.unit: {cost.state.id: cost.state for cost in row.costs}
        for row in inference.evidence.sequence.request.rows
    }
    result = []
    for row in inference.inference.posterior:
        masses = defaultdict(float)
        for mass in row.mass:
            masses[_destination(states[row.unit][mass.state])] += mass.probability
        result.append(masses)
    return result


def test_refined_readouts_consume_checked_marginals_and_adjacent_joint_flow(refined_example):
    source, recall, spec = refined_example
    refined = infer_refined_accounts(source, recall, spec)
    baseline = infer_accounts(source, recall, spec.local)
    order = _order(source)
    organization = organize_accounts(source, recall, spec, order, inference=refined)
    reconstruction = reconstruct_accounts(
        source, recall, spec, _reconstruction_spec(source), inference=refined
    )

    assert isinstance(organization, RefinedAccountOrganizationResult)
    assert organization.schema_version == "storymodel.account-organization/v2"
    assert organization.inference == refined
    assert isinstance(reconstruction, RefinedAccountReconstructionResult)
    assert reconstruction.schema_version == "storymodel.account-reconstruction/v2"
    assert reconstruction.inference == refined

    # Independently collapse the sparse adjacent flow into the public category readout.
    states = {
        row.unit: {cost.state.id: cost.state for cost in row.costs}
        for row in refined.evidence.sequence.request.rows
    }
    expected = defaultdict(float)
    for flow in refined.inference.flow:
        for mass in flow.mass:
            expected[
                _category(
                    _destination(states[flow.from_unit][mass.from_state]),
                    _destination(states[flow.to_unit][mass.to_state]),
                    order,
                )
            ] += mass.probability
    assert organization.organization.counts == pytest.approx(
        {category: expected[category] for category in organization.organization.counts}
    )
    assert organization.organization.counts["forward"] == pytest.approx(0.2780056919336467)
    assert organization.organization.counts["backward"] == pytest.approx(0.044607951958500494)

    refined_marginals = _marginals(refined)
    organization_marginals = [
        {(mass.destination.target, mass.destination.external): mass.weight for mass in row.mass}
        for row in organization.organization.spec.rows
    ]
    for observed, expected_row in zip(organization_marginals, refined_marginals, strict=True):
        assert observed == pytest.approx(expected_row)

    # The same synthetic tie becomes genuinely coupled when its declared stay feature is active.
    coupled_spec = spec.model_copy(
        update={
            "local": spec.local.model_copy(
                update={"transitions": TransitionWeights(theta={"stay": 3.0})}
            )
        }
    )
    coupled = infer_refined_accounts(source, recall, coupled_spec)
    coupled_organization = organize_accounts(source, recall, coupled_spec, order, inference=coupled)
    first, last = source.states[0].id, source.states[-1].id
    left_states, right_states = (states[row.unit] for row in coupled.inference.posterior)
    joint_forward = sum(
        pair.probability
        for pair in coupled.inference.flow[0].mass
        if left_states[pair.from_state].anchor == first
        and right_states[pair.to_state].anchor == last
    )
    left, right = _marginals(coupled)
    independent_forward = left[first, None] * right[last, None]
    assert joint_forward != pytest.approx(independent_forward)
    assert coupled_organization.organization.counts["forward"] == pytest.approx(joint_forward)

    # Reconstruction rows retain the refined posterior masses, not the baseline inference.
    row_masses = [
        {mass.state.id: mass.weight for mass in row.mass}
        for row in reconstruction.reconstruction.rows
    ]
    assert row_masses == [
        {mass.state: mass.probability for mass in row.mass} for row in refined.inference.posterior
    ]
    assert [d.anchor for d in reconstruction.reconstruction.decisions] == [first, last]
    assert row_masses != [
        {mass.state: mass.probability for mass in row.mass} for row in baseline.inference.posterior
    ]
    assert verify_account_organization(source, recall, spec, order, organization) == organization
    assert (
        verify_account_reconstruction(
            source, recall, spec, _reconstruction_spec(source), reconstruction
        )
        == reconstruction
    )


def test_refined_readout_refuses_substituted_family_and_tampered_artifact(refined_example):
    source, recall, spec = refined_example
    refined = infer_refined_accounts(source, recall, spec)
    baseline = infer_accounts(source, recall, spec.local)
    order = _order(source)
    reconstruction_spec = _reconstruction_spec(source)
    organization = organize_accounts(source, recall, spec, order, inference=refined)
    reconstruction = reconstruct_accounts(
        source, recall, spec, reconstruction_spec, inference=refined
    )

    for function, arguments in (
        (organize_accounts, (source, recall, spec, order)),
        (reconstruct_accounts, (source, recall, spec, reconstruction_spec)),
    ):
        with pytest.raises(ValueError, match="family differs"):
            function(*arguments, inference=baseline)

    tampered = refined.model_copy(update={"spec_sha256": "0" * 64})
    with pytest.raises(ValueError, match="differs"):
        verify_account_organization(
            source, recall, spec, order, organization.model_copy(update={"inference": tampered})
        )
    with pytest.raises(ValueError, match="differs"):
        verify_account_reconstruction(
            source,
            recall,
            spec,
            reconstruction_spec,
            reconstruction.model_copy(update={"inference": tampered}),
        )


def test_refined_readouts_cli_api_replay_and_schemas(refined_example, tmp_path, capsys):
    source, recall, spec = refined_example
    refined = infer_refined_accounts(source, recall, spec)
    order, reconstruction_spec = _order(source), _reconstruction_spec(source)
    paths = {
        name: tmp_path / f"{name}.json"
        for name in ("source", "recall", "spec", "inference", "order", "reconstruction")
    }
    for name, value in {
        "source": source,
        "recall": recall,
        "spec": spec,
        "inference": refined,
        "order": order,
        "reconstruction": reconstruction_spec,
    }.items():
        paths[name].write_text(value.model_dump_json())
    organization_path, reconstruction_path = (
        tmp_path / "organization.json",
        tmp_path / "readout.json",
    )

    assert (
        main(
            [
                "organize-accounts",
                *(str(paths[name]) for name in ("source", "recall", "spec", "order")),
                "--inference",
                str(paths["inference"]),
                "--output",
                str(organization_path),
            ]
        )
        == 0
    )
    saved_organization = RefinedAccountOrganizationResult.model_validate_json(
        organization_path.read_bytes()
    )
    assert saved_organization == organize_accounts(source, recall, spec, order, inference=refined)
    assert (
        main(
            [
                "verify-account-organization",
                *(str(paths[name]) for name in ("source", "recall", "spec", "order")),
                str(organization_path),
            ]
        )
        == 0
    )
    assert json.loads(capsys.readouterr().out)["valid"] is True

    assert (
        main(
            [
                "reconstruct-accounts",
                *(str(paths[name]) for name in ("source", "recall", "spec", "reconstruction")),
                "--inference",
                str(paths["inference"]),
                "--output",
                str(reconstruction_path),
            ]
        )
        == 0
    )
    saved_reconstruction = RefinedAccountReconstructionResult.model_validate_json(
        reconstruction_path.read_bytes()
    )
    assert saved_reconstruction == reconstruct_accounts(
        source, recall, spec, reconstruction_spec, inference=refined
    )
    assert (
        main(
            [
                "verify-account-reconstruction",
                *(str(paths[name]) for name in ("source", "recall", "spec", "reconstruction")),
                str(reconstruction_path),
            ]
        )
        == 0
    )
    assert json.loads(capsys.readouterr().out)["valid"] is True

    schemas = Path(__file__).parents[1] / "schemas" / "refined-readouts.json"
    assert json.loads(schemas.read_bytes()) == {
        "organization": RefinedAccountOrganizationResult.model_json_schema(),
        "reconstruction": RefinedAccountReconstructionResult.model_json_schema(),
    }
