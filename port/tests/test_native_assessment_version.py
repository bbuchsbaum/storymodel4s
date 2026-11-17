"""V8 admits native conditional-assessment evidence without widening prior rich wires."""

import pytest
from test_attribution import control
from test_playback import support
from test_playback_construction import fixture as playback_fixture

from storymodel.model import Assessment, Binding, Detail, Hypothesis, Reference, Span, Support, Unit
from storymodel.playback_construction import compile_playback
from storymodel.rich_account import (
    account_schema,
    pair_rich_accounts,
    upgrade_assessment_account,
    upgrade_temporal_account,
    validate_rich_account,
)
from storymodel.serialization import canonical, digest


def native_assessment_account():
    request, packet = playback_fixture()
    source = compile_playback(request, canonical(packet)).account
    recall, _ = control(side="recall")
    document = recall.documents[0]
    unit = Unit(
        kind="recall",
        support=Support(document=document.id, spans=(Span(start=0, end=len(document.text)),)),
    )
    detail = Detail(
        unit=unit.id,
        kind="event",
        content="controlled recall",
        support=unit.support,
        address="target",
    )
    recall = validate_rich_account(
        recall.model_copy(update={"units": (unit,), "details": (detail,)})
    )
    paired = upgrade_assessment_account(pair_rich_accounts(source, recall))
    target = source.units[0]
    hypothesis = Hypothesis(kind="single", targets=(target.id,))
    binding = Binding(
        detail=detail.id,
        reference=Reference(status="attempted", outcome="resolved", hypotheses=(hypothesis,)),
        # Deliberately non-substantive: v8 must still verify actual native evidence.
        assessments=(
            Assessment(
                hypothesis=hypothesis.id,
                status="unavailable",
                reason="controlled unavailable assessment",
                evidence=(target.support,),
            ),
        ),
    )
    return source, validate_rich_account(paired.model_copy(update={"bindings": (binding,)}))


def test_v8_binds_all_inputs_and_preserves_native_identity_gaps_and_points():
    source, account = native_assessment_account()
    native = account.bindings[0].assessments[0].evidence[0]
    assert account.schema_version == "storymodel.account/v8"
    assert native == source.units[0].support
    assert native.intervals[0].start == "0" and native.intervals[1].start == "20"
    assert native.points == ("10",)
    assert set(document.sha256 for document in account.documents) <= set(
        account.recipe.input_sha256
    )
    assert source.media_sources[0].sha256 in account.recipe.input_sha256


def test_v8_requires_exact_source_hypothesis_containment_for_all_native_assessments():
    source, account = native_assessment_account()
    assessment = account.bindings[0].assessments[0]
    outside_gap = support(source.media_sources[0], [(8, 22)])
    altered = assessment.model_copy(update={"evidence": (outside_gap,)})
    binding = account.bindings[0].model_copy(update={"assessments": (altered,)})
    with pytest.raises(ValueError, match="native fidelity evidence lies outside"):
        validate_rich_account(account.model_copy(update={"bindings": (binding,)}))


def test_v8_native_assessment_evidence_cannot_be_downgraded_by_copy_or_raw_wire():
    _, account = native_assessment_account()
    with pytest.raises(ValueError, match="playback assessment evidence requires account v8"):
        validate_rich_account(
            account.model_copy(update={"schema_version": "storymodel.account/v7"})
        )
    raw = account.model_dump(mode="json") | {"schema_version": "storymodel.account/v7"}
    with pytest.raises(ValueError, match="playback assessment evidence requires account v8"):
        validate_rich_account(canonical(raw))


def test_v8_temporal_validation_and_pairing_keep_the_newest_wire_version():
    source, account = native_assessment_account()
    assert upgrade_temporal_account(account) == account
    recall, _ = control(side="recall")
    recall = validate_rich_account(recall.model_copy(update={"bindings": ()}))
    paired = pair_rich_accounts(upgrade_assessment_account(source), recall)
    assert paired.schema_version == "storymodel.account/v8"
    assert paired.media_sources == source.media_sources
    assert paired.units == source.units


def test_v7_schema_is_frozen_while_v8_declares_native_assessment_evidence():
    assert digest(account_schema("v7")) == (
        "92a93c38e2379328b22fdc9d75b28a9e6fd1d35ddf0e03b5d7118d3003275759"
    )
    assert account_schema("v8")["properties"]["schema_version"]["const"] == "storymodel.account/v8"
