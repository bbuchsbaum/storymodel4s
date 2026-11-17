"""V4 graph preservation, relational refusals and frozen legacy contracts."""

from hashlib import sha256

import pytest

from storymodel import align_account, make_account, pair_accounts
from storymodel.extraction import resolve_extraction
from storymodel.extraction_demo import example_proposal, example_request
from storymodel.extraction_workflow import extraction_prompt
from storymodel.model import (
    Account,
    Context,
    Detail,
    Document,
    Entity,
    Event,
    Participation,
    Relation,
    Span,
    State,
    Support,
    digest,
    validate_account,
)
from storymodel.rich_account import (
    Circumstance,
    RichAccount,
    pair_rich_accounts,
    situation_table,
    upgrade_account,
    validate_rich_account,
)
from storymodel.source import compile_source
from storymodel.source_demo import example_source_proposal, example_source_request
from storymodel.source_workflow import source_prompt


def fixture(side="source", key="control"):
    doc = Document.create(key, side, "AA BB CC")
    full = Support(document=doc.id, spans=(Span(start=0, end=8),))
    place_support = Support(document=doc.id, spans=(Span(start=3, end=5),))
    root = Context(side=side, kind="narrated")
    held = Context(
        side=side,
        kind="believed",
        parent=root.id,
        evidence=(Support(document=doc.id, spans=(Span(start=0, end=2),)),),
    )
    place = Entity.create(doc.id, "BB", (place_support,))
    event = Event(side=side, context=held.id, predicate="AA", evidence=(full,))
    state = State(
        side=side,
        context=held.id,
        predicate="CC",
        evidence=(full,),
        participants=(Participation(entity=place.id, role="location", evidence=(place_support,)),),
        polarity="negative",
        modality="possible",
    )
    relation = Relation(
        kind="before", source=event.id, target=state.id, context=held.id, evidence=(full,)
    )
    circumstances = tuple(
        Circumstance(situation=state.id, kind=kind, label="BB", evidence=(place_support,))
        for kind in ("time", "manner")
    )
    return RichAccount(
        documents=(doc,),
        units=(),
        contexts=(root, held),
        entities=(place,),
        events=(event,),
        states=(state,),
        relations=(relation,),
        circumstances=circumstances,
        recipe=make_account((doc,)).recipe,
        export_permission="allowed",
    )


def test_legacy_schema_prompts_requests_and_results_are_byte_stable():
    # Captured at e2ef302de BEFORE the shared-field/validator refactor.
    r, s = example_request(), example_source_request()
    actual = {
        "account_schema": digest(Account.model_json_schema()),
        "extraction_request": digest(r.model_dump(mode="json")),
        "extraction_prompt": sha256(extraction_prompt(r).encode()).hexdigest(),
        "extraction_result": digest(
            resolve_extraction(r, example_proposal()).model_dump(mode="json")
        ),
        "source_request": digest(s.model_dump(mode="json")),
        "source_prompt": sha256(source_prompt(s).encode()).hexdigest(),
        "source_result": digest(
            compile_source(s, example_source_proposal()).model_dump(mode="json")
        ),
    }
    assert actual == {
        "account_schema": "c084379adb61cb0ece8dc8b44723bc2d24b2dcd8c6ee2df28b4b177b4dc6d685",
        "extraction_request": "5a88ef30f25c825e2ef41cb254037f0c4e9499226b0b7b9816dea4fd39e99bbd",
        "extraction_prompt": "9f1bff68ab7289c1f456b008fbb8f193d9d29af1bd33b3e386133a29443fa81c",
        "extraction_result": "2ed6dd913a0d0ed5bcf08887f3d17253b00573df2530d68181ff4f87c9439433",
        "source_request": "fb6e75f6c3b53ae6b3ade76c4040d88e06f6fc0e499a67dbe4abc4bb2434393d",
        "source_prompt": "b2d1fbba24fd655987ca469ebc0edea6fcc25791b58ac67d6242a87185670a8a",
        "source_result": "300e9e348d480c01e83bee6011754d9d5967bedfe6b715e6295ac14e496f6bea",
    }


def test_upgrade_preserves_every_existing_collection_and_id():
    legacy = compile_source(example_source_request(), example_source_proposal()).account
    before = legacy.model_dump_json()
    rich = upgrade_account(legacy)
    for name in Account.model_fields:
        if name not in ("schema_version", "recipe"):
            assert getattr(rich, name) == getattr(legacy, name)
    assert rich.states == rich.circumstances == ()
    assert legacy.model_dump_json() == before
    assert validate_rich_account(rich.model_dump_json()) == rich
    old = resolve_extraction(example_request(), example_proposal()).account
    assert old.schema_version != "storymodel.account/v3"
    with pytest.raises(ValueError, match="v3"):
        upgrade_account(old)


def test_pairing_preserves_all_collections_and_source_reuse():
    source, first, second = fixture(), fixture("recall", "first"), fixture("recall", "second")
    before = source.model_dump_json()
    for recall in (first, second):
        paired = pair_rich_accounts(source, recall)
        for name in RichAccount.model_fields:
            if name not in ("schema_version", "recipe", "export_permission"):
                assert getattr(paired, name) == (*getattr(source, name), *getattr(recall, name))
        assert validate_rich_account(paired.model_dump_json()) == paired
        assert len(situation_table(paired)) == 4
    assert source.model_dump_json() == before
    denied = first.model_copy(update={"export_permission": "denied"})
    assert pair_rich_accounts(source, denied).export_permission == "denied"
    with pytest.raises(ValueError, match="recall-only"):
        pair_rich_accounts(source, source)


def test_state_location_context_and_literal_circumstances_have_a_consumer():
    rich = fixture()
    event, state = situation_table(rich)
    assert event.kind == "event" and event.polarity is None and not event.circumstances
    assert state.kind == "state" and state.context == rich.states[0].context
    assert state.polarity == "negative" and state.modality == "possible"
    assert state.participants[0].role == "location" and state.participants[0].label == "BB"
    assert state.participants[0].evidence == rich.entities[0].evidence
    assert [c.kind for c in state.circumstances] == ["time", "manner"]
    assert all(c.label == "BB" and "clock" not in c.model_dump() for c in state.circumstances)


@pytest.mark.parametrize(
    "mutation",
    [
        "context",
        "foreign-place",
        "role-outside",
        "duplicate-role",
        "foreign-circumstance",
        "circumstance-outside",
    ],
)
def test_state_and_circumstance_relational_forgery_refuses(mutation):
    rich = fixture()
    state = rich.states[0]

    def altered_state(**changes):
        return State(**(state.model_dump(exclude={"id"}) | changes))

    # Recompute the changed ID; refusals must be relational, not simply checksum failures.
    if mutation == "context":
        changed = altered_state(context="foreign")
    elif mutation == "foreign-place":
        changed = altered_state(
            participants=(
                Participation(entity="foreign", role="location", evidence=state.evidence),
            )
        )
    elif mutation == "role-outside":
        changed = altered_state(
            evidence=(Support(document=rich.documents[0].id, spans=(Span(start=0, end=2),)),)
        )
    elif mutation == "duplicate-role":
        changed = altered_state(participants=(*state.participants, *state.participants))
    else:
        changed = state
    circumstances = ()  # Do not let stale links mask a missing state-specific guard.
    if mutation == "foreign-circumstance":
        circumstances = (
            Circumstance(situation="foreign", kind="time", label="BB", evidence=state.evidence),
        )
    if mutation == "circumstance-outside":
        changed = altered_state(
            evidence=(Support(document=rich.documents[0].id, spans=(Span(start=0, end=2),)),),
            participants=(),
        )
        circumstances = (
            Circumstance(
                situation=changed.id,
                kind="time",
                label="BB",
                evidence=rich.circumstances[0].evidence,
            ),
        )
    reason = {
        "context": "event requires a context",
        "foreign-place": "foreign event participant",
        "role-outside": "participant role evidence lies outside",
        "duplicate-role": "duplicate participant",
        "foreign-circumstance": "circumstance requires an existing situation",
        "circumstance-outside": "circumstance evidence lies outside",
    }[mutation]
    with pytest.raises(ValueError, match=reason):
        validate_rich_account(
            rich.model_copy(
                update={"states": (changed,), "relations": (), "circumstances": circumstances}
            )
        )


def test_old_consumers_refuse_v4_without_silent_state_loss():
    rich = fixture()
    for consumer in (validate_account, align_account):
        with pytest.raises(ValueError, match="different wire version"):
            consumer(rich)
    with pytest.raises(ValueError):
        validate_account(rich.model_dump_json())
    with pytest.raises(ValueError):
        pair_accounts(rich, fixture("recall"))
    # A nested legacy envelope cannot silently serialize a v4 account as a v3 prefix.
    from storymodel.source import SourceResult

    source = compile_source(example_source_request(), example_source_proposal())
    with pytest.raises(ValueError):
        SourceResult.model_validate_json(
            source.model_copy(update={"account": rich}).model_dump_json()
        )


def test_recall_detail_can_refer_to_state_and_context_remains_believed():
    from storymodel.model import Unit

    rich = fixture("recall")
    state = rich.states[0]
    unit = Unit(kind="recall", support=state.evidence[0])
    detail = Detail(
        unit=unit.id,
        kind="attribute",
        content="CC",
        support=state.evidence[0],
        address="unresolved",
        event=state.id,
    )
    checked = validate_rich_account(
        rich.model_copy(update={"units": (unit,), "details": (detail,)})
    )
    assert checked.details[0].event == checked.states[0].id
    assert next(c.kind for c in checked.contexts if c.id == checked.states[0].context) == "believed"
