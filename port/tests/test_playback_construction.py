"""Document-free graph construction and actual native evidence consumption."""

import json

import pytest
from test_attribution import control
from test_playback import source, support

from storymodel import align_account, pair_rich_accounts
from storymodel.analysis import project
from storymodel.artifacts import export_supported_account, read_supported_account
from storymodel.model import Account, PlaybackSupport, text_support
from storymodel.playback import MediaSource, TickInterval
from storymodel.playback_construction import (
    PlaybackObservation,
    PlaybackResult,
    compile_playback,
    prepare_playback,
    situation_playback,
    validate_playback_request,
    verify_playback,
)
from storymodel.rich_account import account_schema, situation_table, validate_rich_account
from storymodel.serialization import canonical, digest


def fixture():
    media = source()
    envelope = support(media, [(0, 10), (20, 30)], [10])
    request = prepare_playback(
        media,
        (
            PlaybackObservation(
                key="observation",
                support=envelope,
                origin="abstract-control",
                procedure="supplied-envelope/v1",
                input_sha256=("b" * 64,),
            ),
        ),
        export_permission="allowed",
    )

    def wire(intervals=(), points=()):
        return support(media, intervals, points).model_dump(mode="json")

    packet = {
        "schema_version": "storymodel.playback-proposals/v1",
        "request_sha256": digest(request.model_dump(mode="json")),
        "observations": [
            {
                "observation": "observation",
                "status": "completed",
                "contexts": [
                    {
                        "key": "held",
                        "kind": "believed",
                        "parent": "narrated",
                        "cue": wire(points=[0]),
                        "scope": wire([(2, 8), (20, 28)]),
                    }
                ],
                "entities": [{"key": "entity", "label": "AA", "evidence": wire(points=[3])}],
                "situations": [
                    {
                        "key": "event",
                        "kind": "event",
                        "context": "held",
                        "predicate": "BB",
                        "evidence": wire([(3, 5), (22, 25)], [4]),
                        "participants": [
                            {"entity": "entity", "role": "agent", "evidence": wire(points=[4])}
                        ],
                    },
                    {
                        "key": "state",
                        "kind": "state",
                        "context": "held",
                        "predicate": "CC",
                        "evidence": wire(points=[22]),
                        "polarity": "unknown",
                        "participants": [
                            {
                                "entity": "entity",
                                "role": "experiencer",
                                "evidence": wire(points=[22]),
                            }
                        ],
                    },
                ],
            }
        ],
    }
    return request, packet


def test_native_graph_needs_no_document_and_keeps_actual_support_through_exchange(tmp_path):
    request, packet = fixture()
    raw = canonical(packet) + "\n"
    result = compile_playback(request, raw)
    assert [r.status for r in result.resolutions] == ["admitted"]
    account = result.account
    assert account.schema_version == "storymodel.account/v6" and account.documents == ()
    assert (
        len(account.units)
        == len(account.entities)
        == len(account.events)
        == len(account.states)
        == 1
    )
    assert account.entities[0].origin_source == request.source.id
    assert isinstance(account.units[0].support, PlaybackSupport)
    held = next(c for c in account.contexts if c.kind == "believed")
    rows = situation_table(account)
    assert {r.context for r in rows} == {held.id}
    assert all(isinstance(e, PlaybackSupport) for r in rows for e in r.evidence)
    assert rows[0].participants[0].evidence == account.events[0].participants[0].evidence
    assert (
        verify_playback(request, raw, PlaybackResult.model_validate_json(result.model_dump_json()))
        == result
    )
    path = tmp_path / "source.json"
    export_supported_account(path, account)
    assert read_supported_account(path) == account
    bins = tuple(TickInterval(start=str(i), end=str(i + 10)) for i in (0, 10, 20))
    event = situation_playback(account, account.events[0].id, request.source.id, bins)
    assert event.projection.duration_seconds == "5/3"
    assert [c.overlap_ticks for c in event.projection.cells] == ["2", "0", "3"]
    assert [c.points for c in event.projection.cells] == [("4",), (), ()]
    state = situation_playback(account, account.states[0].id, request.source.id, bins)
    assert state.projection.duration_seconds == "0/1" and state.projection.cells[2].points == (
        "22",
    )


def test_same_native_source_pairs_with_two_text_recalls_without_identity_or_support_changes():
    request, packet = fixture()
    native = compile_playback(request, canonical(packet)).account
    before = native.model_dump_json()
    for label in ("CC", "DD"):
        recall, _ = control(labels=("AA", "AA", label), side="recall")
        paired = pair_rich_accounts(native, recall)
        assert paired.schema_version == "storymodel.account/v6"
        assert paired.media_sources == native.media_sources
        assert paired.documents == recall.documents
        for collection in ("units", "entities", "events", "states", "contexts"):
            assert all(v in getattr(paired, collection) for v in getattr(native, collection))
        assert (
            situation_playback(
                paired, native.events[0].id, request.source.id
            ).projection.duration_seconds
            == "5/3"
        )
        assert native.model_dump_json() == before
        with pytest.raises(ValueError, match="unsupported modality"):
            align_account(paired)
        with pytest.raises(ValueError, match="unsupported modality"):
            project(paired)


@pytest.mark.parametrize("side", ("source", "recall"))
@pytest.mark.parametrize(
    "kind",
    (
        "reported",
        "believed",
        "intended",
        "desire",
        "memory",
        "imagination",
        "hypothetical",
        "counterfactual",
    ),
)
def test_native_context_vocabulary_on_both_independent_sides(side, kind):
    request, packet = fixture()
    native = MediaSource.model_validate(request.source.model_copy(update={"id": "", "side": side}))
    # The test deliberately changes every occurrence of the actual source identity,
    # rather than assuming source and recall coordinates are interchangeable.
    raw_request = json.loads(request.model_dump_json().replace(request.source.id, native.id))
    raw_request["source"] = native.model_dump(mode="json")
    packet = json.loads(canonical(packet).replace(request.source.id, native.id))
    request = validate_playback_request(canonical(raw_request))
    packet["request_sha256"] = digest(request.model_dump(mode="json"))
    packet["observations"][0]["contexts"][0]["kind"] = kind
    result = compile_playback(request, canonical(packet))
    assert result.resolutions[0].status == "admitted"
    assert result.account.documents == ()
    assert all(c.side == side for c in result.account.contexts)
    for event in (*result.account.events, *result.account.states):
        readout = situation_playback(result.account, event.id, native.id)
        assert readout.context.kind == kind and event.side == side
    assert [i.status for i in result.account.inventories] == (
        ["not-attempted"] if side == "recall" else []
    )


@pytest.mark.parametrize(
    "mutation", ("gap", "point-duration", "role", "root", "scope", "foreign-source")
)
def test_invalid_native_claims_are_row_atomic(mutation):
    request, packet = fixture()
    row = packet["observations"][0]
    if mutation == "gap":
        row["situations"][0]["evidence"] = support(request.source, [(8, 22)]).model_dump(
            mode="json"
        )
    elif mutation == "point-duration":
        row["situations"][0]["evidence"] = support(request.source, [(10, 11)]).model_dump(
            mode="json"
        )
    elif mutation == "role":
        row["situations"][0]["participants"][0]["evidence"] = support(
            request.source, points=[7]
        ).model_dump(mode="json")
    elif mutation == "root":
        row["situations"][0]["context"] = "narrated"
    elif mutation == "scope":
        row["contexts"].append({**row["contexts"][0], "key": "competing"})
    else:
        row["entities"][0]["evidence"]["source"] = source(edition="other").id
    result = compile_playback(request, canonical(packet))
    assert result.resolutions[0].status == "rejected"
    assert not result.account.events and not result.account.states and not result.account.entities
    assert len(result.account.contexts) == 1
    assert result.account.units[0].support == request.observations[0].support


def test_rejected_observation_does_not_erase_valid_neighbor_or_provenance_binding():
    request, packet = fixture()
    other = request.observations[0].model_copy(update={"key": "other"})
    request = request.model_copy(update={"observations": (*request.observations, other)})
    packet["request_sha256"] = digest(request.model_dump(mode="json"))
    good = json.loads(json.dumps(packet["observations"][0]))
    good["observation"] = "other"
    packet["observations"][0]["situations"][0]["context"] = "narrated"
    packet["observations"].append(good)
    result = compile_playback(request, canonical(packet))
    assert [r.status for r in result.resolutions] == ["rejected", "admitted"]
    assert len(result.account.events) == len(result.account.units) == 1
    changed = request.model_copy(
        update={
            "observations": (
                request.observations[0].model_copy(update={"procedure": "changed"}),
                other,
            )
        }
    )
    with pytest.raises(ValueError, match="foreign request"):
        compile_playback(changed, canonical(packet))


@pytest.mark.parametrize("cue,expected", ((3, "admitted"), (29, "rejected")))
def test_nested_context_cue_must_belong_to_parent_scope(cue, expected):
    request, packet = fixture()
    row = packet["observations"][0]
    row["contexts"].append(
        {
            "key": "child",
            "kind": "memory",
            "parent": "held",
            "cue": support(request.source, points=[cue]).model_dump(mode="json"),
            "scope": support(request.source, [(3, 5), (22, 25)]).model_dump(mode="json"),
        }
    )
    for situation in row["situations"]:
        situation["context"] = "child"
    result = compile_playback(request, canonical(packet))
    assert result.resolutions[0].status == expected
    if expected == "rejected":
        assert len(result.account.contexts) == 1 and not result.account.events
    else:
        assert (
            situation_playback(
                result.account, result.account.events[0].id, request.source.id
            ).context.kind
            == "memory"
        )


@pytest.mark.parametrize("hidden_side", ("source", "recall"))
@pytest.mark.parametrize("consumer", ("organization", "reconstruction"))
def test_numerical_consumers_refuse_native_side_hidden_by_text_documents(hidden_side, consumer):
    from test_organization import _accounts

    from storymodel.organization import OrganizationSpec, organization_readout
    from storymodel.reconstruction import ReconstructionSpec, reconstruct_rows
    from storymodel.rich_account import upgrade_account

    text_source, text_recall, _, _ = _accounts()
    media = source(side=hidden_side)
    request = prepare_playback(
        media,
        (
            PlaybackObservation(
                key="media",
                support=support(media, [(0, 10)]),
                origin="abstract-control",
                procedure="supplied/v1",
                input_sha256=("b" * 64,),
            ),
        ),
    )
    native = compile_playback(
        request,
        canonical(
            {
                "schema_version": "storymodel.playback-proposals/v1",
                "request_sha256": digest(request.model_dump(mode="json")),
                "observations": [],
            }
        ),
    ).account
    if hidden_side == "source":
        source_input = text_source
        recall_input = pair_rich_accounts(native, upgrade_account(text_recall))
    else:
        source_input = pair_rich_accounts(upgrade_account(text_source), native)
        recall_input = text_recall

    def consume(source_account, recall_account):
        if consumer == "organization":
            return organization_readout(
                source_account,
                recall_account,
                OrganizationSpec(rows=(), dependence="independent-rows"),
            )
        return reconstruct_rows(source_account, recall_account, (), ReconstructionSpec(roots={}))

    consume(text_source, text_recall)  # Positive: the supported input remains usable.
    with pytest.raises(ValueError, match="unsupported modality"):
        consume(source_input, recall_input)


@pytest.mark.parametrize("version", ("v4", "v5"))
def test_old_rich_versions_refuse_explicit_media_field_even_when_empty(version):
    account, _ = control()
    value = account.model_dump(mode="json") | {"schema_version": f"storymodel.account/{version}"}
    old = validate_rich_account(canonical(value))
    assert "media_sources" not in old.model_dump(mode="json")
    for forged in (
        old.model_copy(update={"media_sources": ()}),
        canonical(value | {"media_sources": []}),
    ):
        with pytest.raises(ValueError, match="media_sources field requires account v6"):
            validate_rich_account(forged)
    assert "media_sources" not in account_schema(version)["properties"]


def test_native_evidence_cannot_be_smuggled_under_old_tags_or_through_copied_objects(tmp_path):
    request, packet = fixture()
    result = compile_playback(request, canonical(packet))
    account = result.account
    for version in ("v4", "v5"):
        with pytest.raises(ValueError):
            validate_rich_account(
                account.model_copy(update={"schema_version": f"storymodel.account/{version}"})
            )
    # A legacy Account cannot carry a native unit either, even though Unit is shared.
    from storymodel.demo import example_account

    legacy = example_account()
    with pytest.raises(ValueError):
        Account.model_validate(legacy.model_copy(update={"units": account.units}))
    corrupted = account.model_copy(
        update={
            "events": (
                account.events[0].model_copy(
                    update={"id": "", "evidence": (support(request.source, [(90, 101)]),)}
                ),
            )
        }
    )
    for consume in (
        lambda: situation_table(corrupted),
        lambda: export_supported_account(tmp_path / "bad.json", corrupted),
        lambda: pair_rich_accounts(corrupted, control(side="recall")[0]),
    ):
        with pytest.raises(ValueError):
            consume()
    assert not (tmp_path / "bad.json").exists()
    with pytest.raises(ValueError, match="unsupported modality"):
        text_support(account.units[0].support)
