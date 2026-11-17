"""V7 chronology records retain v5/v6 graph vocabulary without widening old wires."""

import pytest
from test_attribution import control
from test_playback_construction import fixture as playback_fixture

from storymodel.chronology import TemporalClaim
from storymodel.model import ExtendedContext
from storymodel.playback_construction import compile_playback
from storymodel.rich_account import (
    account_schema,
    pair_rich_accounts,
    upgrade_context_account,
    upgrade_temporal_account,
    validate_rich_account,
)
from storymodel.serialization import canonical, digest


def temporal_claim(account):
    event, state = account.events[0], account.states[0]
    return TemporalClaim.create(
        source=event.id,
        target=state.id,
        relation="before",
        context=event.context,
        status="surface-explicit",
        evidence=event.evidence,
        origin="abstract-control",
        procedure="supplied-claim/v1",
    )


@pytest.mark.parametrize("version", ("v4", "v5", "v6"))
def test_old_versions_refuse_even_an_explicit_empty_temporal_field(version):
    account, _ = control()
    value = account.model_dump(mode="json") | {"schema_version": f"storymodel.account/{version}"}
    if version == "v5":
        account = upgrade_context_account(account)
        value = account.model_dump(mode="json")
    elif version == "v6":
        request, packet = playback_fixture()
        value = compile_playback(request, canonical(packet)).account.model_dump(mode="json")
    old = validate_rich_account(canonical(value))
    assert "temporal_relations" not in old.model_dump(mode="json")
    for forged in (
        old.model_copy(update={"temporal_relations": ()}),
        canonical(value | {"temporal_relations": []}),
    ):
        with pytest.raises(ValueError, match="temporal_relations field requires account v7"):
            validate_rich_account(forged)


def test_v7_upgrade_preserves_v5_contexts_and_v6_native_graphs():
    account, _ = control()
    v5 = upgrade_context_account(account)
    root, held = v5.contexts
    extended = ExtendedContext(
        side="source", kind="hypothetical", parent=root.id, evidence=held.evidence
    )
    v5 = validate_rich_account(v5.model_copy(update={"contexts": (root, held, extended)}))
    upgraded = upgrade_temporal_account(v5)
    assert upgraded.schema_version == "storymodel.account/v7"
    assert upgraded.contexts == v5.contexts
    assert upgrade_temporal_account(upgraded) == upgraded

    request, packet = playback_fixture()
    native = compile_playback(request, canonical(packet)).account
    native_v7 = upgrade_temporal_account(native)
    assert native_v7.media_sources == native.media_sources
    assert native_v7.contexts == native.contexts
    assert native_v7.events == native.events and native_v7.states == native.states


def test_v7_pairing_selects_newest_version_and_preserves_native_and_temporal_collections():
    request, packet = playback_fixture()
    source = upgrade_temporal_account(compile_playback(request, canonical(packet)).account)
    source = validate_rich_account(
        source.model_copy(update={"temporal_relations": (temporal_claim(source),)})
    )
    recall, _ = control(side="recall")
    paired = pair_rich_accounts(source, recall)
    assert paired.schema_version == "storymodel.account/v7"
    assert paired.media_sources == source.media_sources
    assert paired.temporal_relations == source.temporal_relations
    assert paired.documents == (*source.documents, *recall.documents)


def test_v6_schema_is_the_captured_baseline_and_v7_declares_temporal_relations():
    # Canonical digest of the pre-chronology v6 schema, captured from ec1e91f45.
    # No dependency on an ignored local data directory in a fresh checkout.
    assert digest(account_schema("v6")) == (
        "10b3e2584cbfce9d9e27f546c55a556149f019e0827e0ee11faa016a7aeee67c"
    )
    schema = account_schema("v7")
    assert schema["properties"]["schema_version"]["const"] == "storymodel.account/v7"
    assert schema["properties"]["temporal_relations"]["items"]["$ref"] == "#/$defs/TemporalClaim"
