"""Independent witnesses for retained story-world chronology.

The interval oracle below deliberately does not use chronology's constraint tables:
it classifies concrete proper intervals with ordinary numeric comparisons.
"""

from __future__ import annotations

import itertools
import json
from hashlib import sha256

import pytest
from test_attribution import control
from test_playback_construction import fixture as playback_fixture

from storymodel.artifacts import publish_json
from storymodel.chronology import RELATIONS, STATUSES, TemporalClaim, TemporalPolicy
from storymodel.chronology_workflow import (
    TemporalProposal,
    TemporalProposals,
    admit_temporal_relations,
    query_temporal_relation,
    temporal_table,
    verify_temporal_query,
    verify_temporal_relations,
)
from storymodel.cli import main
from storymodel.playback_construction import compile_playback
from storymodel.relations import RelationQuote
from storymodel.rich_account import (
    account_schema,
    upgrade_temporal_account,
    validate_rich_account,
)
from storymodel.serialization import canonical, digest


def _account(*, root_events: bool = False):
    """Three abstract situations with no inherited legacy chronology."""
    account, _ = control(labels=("AA", "BB", "CC"))
    if root_events:
        root = next(frame for frame in account.contexts if frame.kind == "narrated")
        account = account.model_copy(
            update={
                "events": tuple(
                    event.model_copy(update={"id": "", "context": root.id})
                    for event in account.events
                ),
                "states": tuple(
                    state.model_copy(update={"id": "", "context": root.id})
                    for state in account.states
                ),
                "relations": (),
            }
        )
    return upgrade_temporal_account(validate_rich_account(account))


def _claim(
    account,
    source,
    target,
    relation,
    *,
    context=None,
    status="surface-explicit",
    premises=(),
    adjudicator=None,
):
    situation = next(item for item in account._situations() if item.id == source)
    return TemporalClaim.create(
        source=source,
        target=target,
        relation=relation,
        context=context or situation.context,
        status=status,
        evidence=() if status != "surface-explicit" else situation.evidence,
        premises=premises,
        origin="abstract-control",
        procedure="chronology-test/v1",
        adjudicator=adjudicator,
    )


def _admission(account, proposals, *, adjudicator=None):
    raw = canonical(
        TemporalProposals(
            account_sha256=digest(account.model_dump(mode="json")),
            origin="abstract-control",
            procedure="chronology-test/v1",
            status="completed",
            relations=tuple(proposals),
        ).model_dump(mode="json")
    )
    return raw, admit_temporal_relations(account, raw, adjudicator=adjudicator)


def _numeric_relation(left, right):
    """Independent Allen classifier for two proper intervals."""
    a, b = left
    c, d = right
    if a == c and b == d:
        return "equal"
    if b < c:
        return "before"
    if b == c:
        return "meets"
    if a > d:
        return "after"
    if a == d:
        return "met-by"
    if a < c < b < d:
        return "overlaps"
    if c < a < d < b:
        return "overlapped-by"
    if c < a and b < d:
        return "during"
    if a < c and d < b:
        return "contains"
    if a == c and b < d:
        return "starts"
    if a == c and b > d:
        return "started-by"
    if c < a and b == d:
        return "finishes"
    if a < c and b == d:
        return "finished-by"
    raise AssertionError((left, right))


def test_allen_atomic13_consistency_matches_independent_three_interval_oracle():
    """3375 numeric assignments determine every feasible AB/BC/AC triple."""
    intervals = tuple((start, end) for start in range(6) for end in range(start + 1, 6))
    feasible = {
        (
            _numeric_relation(a, b),
            _numeric_relation(b, c),
            _numeric_relation(a, c),
        )
        for a, b, c in itertools.product(intervals, repeat=3)
    }
    assert len(intervals) == 15 and len(RELATIONS) == 13

    account = _account()
    a, b, c = (item.id for item in account._situations())
    observed = set()
    for ab, bc, ac in itertools.product(RELATIONS, repeat=3):
        claims = (
            _claim(account, a, b, ab),
            _claim(account, b, c, bc),
            _claim(account, a, c, ac),
        )
        try:
            validate_rich_account(account.model_copy(update={"temporal_relations": claims}))
            admitted = True
        except ValueError:
            admitted = False
        if admitted:
            observed.add((ab, bc, ac))
        assert admitted == ((ab, bc, ac) in feasible), (ab, bc, ac)
    assert observed == feasible


def test_meets_endpoint_equality_is_not_before_and_strict_constraints_remain_sparse():
    account = _account()
    a, b, c = (item.id for item in account._situations())
    contradictory = (
        _claim(account, a, b, "meets"),
        _claim(account, b, c, "meets"),
        _claim(account, a, c, "meets"),
    )
    with pytest.raises(ValueError, match="inconsistent"):
        validate_rich_account(account.model_copy(update={"temporal_relations": contradictory}))
    valid = (
        _claim(account, a, b, "meets"),
        _claim(account, b, c, "meets"),
        _claim(account, a, c, "before"),
    )
    assert (
        validate_rich_account(
            account.model_copy(update={"temporal_relations": valid})
        ).temporal_relations
        == valid
    )


def test_canonical_creation_rejects_raw_aliases_and_semantic_duplicates_including_during_contains():
    account = _account()
    a, b, c = (item.id for item in account._situations())
    after = _claim(account, b, a, "after")
    assert (after.source, after.target, after.relation) == (a, b, "before")
    with pytest.raises(ValueError, match="canonical"):
        TemporalClaim(
            source=b,
            target=a,
            relation="after",
            context=after.context,
            status="surface-explicit",
            evidence=after.evidence,
            origin="abstract-control",
            procedure="raw",
        )
    during = _claim(account, a, b, "during")
    contains = _claim(account, b, a, "contains")
    with pytest.raises(ValueError, match="duplicate temporal fact"):
        validate_rich_account(account.model_copy(update={"temporal_relations": (during, contains)}))


def test_epistemic_licenses_require_direct_evidence_or_scoped_upstream_premises():
    account = _account()
    a, b, c = (item.id for item in account._situations())
    direct = _claim(account, a, b, "before")
    assert direct.status == "surface-explicit" and direct.evidence
    for status in ("linguistically-entailed", "world-knowledge-inferred", "structurally-derived"):
        with pytest.raises(ValueError, match="upstream"):
            _claim(account, b, c, "before", status=status)
    linguistic = _claim(
        account, b, c, "before", status="linguistically-entailed", premises=(direct.id,)
    )
    assert validate_rich_account(
        account.model_copy(update={"temporal_relations": (direct, linguistic)})
    )
    for status in ("hypothesized", "human-adjudicated"):
        kwargs = {"adjudicator": "reviewer"} if status == "human-adjudicated" else {}
        with pytest.raises(ValueError, match="evidence or upstream"):
            _claim(account, b, c, "before", status=status, **kwargs)
        claim = TemporalClaim.create(
            source=b,
            target=c,
            relation="before",
            context=direct.context,
            status=status,
            evidence=direct.evidence,
            origin="abstract-control",
            procedure="chronology-test/v1",
            **kwargs,
        )
        assert claim.status == status
    with pytest.raises(ValueError, match="adjudicator"):
        TemporalClaim.create(
            source=a,
            target=b,
            relation="after",
            context=direct.context,
            status="human-adjudicated",
            evidence=direct.evidence,
            origin="abstract-control",
            procedure="chronology-test/v1",
        )


def test_structural_entailment_requires_cited_exact_context_constraints_and_one_allen_atom():
    account = _account()
    a, b, c = (item.id for item in account._situations())
    ab, bc = _claim(account, a, b, "before"), _claim(account, b, c, "before")
    derived = _claim(
        account, a, c, "before", status="structurally-derived", premises=(ab.id, bc.id)
    )
    assert validate_rich_account(
        account.model_copy(update={"temporal_relations": (ab, bc, derived)})
    )

    equal, after_equal = _claim(account, a, b, "equal"), _claim(account, b, c, "before")
    equality_proof = _claim(
        account,
        a,
        c,
        "before",
        status="structurally-derived",
        premises=(equal.id, after_equal.id),
    )
    assert validate_rich_account(
        account.model_copy(update={"temporal_relations": (equal, after_equal, equality_proof)})
    )

    insufficient = _claim(account, b, c, "before", status="structurally-derived", premises=(ab.id,))
    with pytest.raises(ValueError, match="entail"):
        validate_rich_account(account.model_copy(update={"temporal_relations": (ab, insufficient)}))

    # An uncited account edge could complete the proof, but must not do so implicitly.
    uncited = _claim(account, a, c, "before", status="structurally-derived", premises=(ab.id,))
    with pytest.raises(ValueError, match="entail"):
        validate_rich_account(account.model_copy(update={"temporal_relations": (ab, bc, uncited)}))
    event_only = _claim(account, a, c, "before", status="structurally-derived", premises=(b,))
    with pytest.raises(ValueError, match="entail"):
        validate_rich_account(account.model_copy(update={"temporal_relations": (event_only,)}))
    overlaps = (_claim(account, a, b, "overlaps"), _claim(account, b, c, "overlaps"))
    nonunique = _claim(
        account,
        a,
        c,
        "before",
        status="structurally-derived",
        premises=tuple(p.id for p in overlaps),
    )
    with pytest.raises(ValueError, match="entail"):
        validate_rich_account(
            account.model_copy(update={"temporal_relations": (*overlaps, nonunique)})
        )

    root_account = _account(root_events=True)
    root = next(frame for frame in root_account.contexts if frame.kind == "narrated")
    belief = next(frame for frame in root_account.contexts if frame.kind != "narrated")
    a, b, c = (item.id for item in root_account._situations())
    root_ab, root_bc = (
        _claim(root_account, a, b, "before", context=root.id),
        _claim(root_account, b, c, "before", context=root.id),
    )
    cross_context = _claim(
        root_account,
        a,
        c,
        "before",
        context=belief.id,
        status="structurally-derived",
        premises=(root_ab.id, root_bc.id),
    )
    with pytest.raises(ValueError, match="context|scope"):
        validate_rich_account(
            root_account.model_copy(
                update={"temporal_relations": (root_ab, root_bc, cross_context)}
            )
        )


def test_belief_chronology_can_disagree_with_root_without_leaking_into_root_projection():
    account = _account(root_events=True)
    root = next(frame for frame in account.contexts if frame.kind == "narrated")
    belief = next(frame for frame in account.contexts if frame.kind != "narrated")
    a, b, _ = (item.id for item in account._situations())
    root_claim = _claim(account, a, b, "before", context=root.id)
    belief_claim = _claim(account, b, a, "before", context=belief.id)
    checked = validate_rich_account(
        account.model_copy(update={"temporal_relations": (root_claim, belief_claim)})
    )
    assert [row.id for row in temporal_table(checked, root.id)] == [root_claim.id]
    assert [row.id for row in temporal_table(checked, belief.id)] == [belief_claim.id]
    root_query = query_temporal_relation(checked, a, b, root.id)
    assert (
        root_claim.id in root_query.constraint_claims
        and belief_claim.id not in root_query.constraint_claims
    )
    from storymodel.source_transitions import SourceProjectionPolicy, project_source

    with pytest.raises(ValueError, match="projection v3"):
        project_source(checked)
    projection = project_source(checked, SourceProjectionPolicy(recipe="account-source-layers/v3"))
    assert {(e.source, e.target) for e in projection.edges if e.layer == "world-time"} == {(a, b)}


def test_legacy_before_rows_are_retained_and_new_duplicates_are_refused():
    account = _account()
    a, b, _ = (item.id for item in account._situations())
    # Existing legacy admission owns its identity/evidence. V7 must not coerce it into a claim.
    from storymodel.relations import RichRelationProposal, RichRelationProposals, admit_relations

    document = account.documents[0]
    evidence = account._situations()[0].evidence[0]
    quote = RelationQuote(
        document=document.id, span=evidence.spans[0], text=evidence.spans[0].slice(document.text)
    )
    raw = canonical(
        RichRelationProposals(
            account_sha256=digest(account.model_dump(mode="json")),
            status="completed",
            relations=(
                RichRelationProposal(
                    key="old", kind="before", source=a, target=b, evidence=(quote,)
                ),
            ),
        ).model_dump(mode="json")
    )
    legacy = upgrade_temporal_account(admit_relations(account, raw).account)
    assert len(temporal_table(legacy)) == 1 and temporal_table(legacy)[0].status is None
    duplicate = _claim(legacy, a, b, "before")
    with pytest.raises(ValueError, match="duplicate temporal fact"):
        validate_rich_account(legacy.model_copy(update={"temporal_relations": (duplicate,)}))


def test_scope_premise_cannot_leak_from_a_descendant_context_to_root():
    """Mutation witness: reversing the premise-scope check admits this invalid inference."""
    account = _account(root_events=True)
    root = next(frame for frame in account.contexts if frame.kind == "narrated")
    belief = next(frame for frame in account.contexts if frame.kind != "narrated")
    a, b, c = (item.id for item in account._situations())
    local = _claim(account, b, a, "before", context=belief.id)
    root_inference = _claim(
        account,
        b,
        c,
        "before",
        context=root.id,
        status="linguistically-entailed",
        premises=(local.id,),
    )
    with pytest.raises(ValueError, match="premise.*scope"):
        validate_rich_account(
            account.model_copy(update={"temporal_relations": (local, root_inference)})
        )


def test_policy_excludes_status_and_legacy_dependent_conclusions_transitively():
    account = _account()
    a, b, c = (item.id for item in account._situations())
    direct = _claim(account, a, b, "before")
    hypothesized = TemporalClaim.create(
        source=b,
        target=c,
        relation="before",
        context=direct.context,
        status="hypothesized",
        evidence=direct.evidence,
        origin="abstract-control",
        procedure="chronology-test/v1",
    )
    derived = _claim(
        account,
        a,
        c,
        "before",
        status="structurally-derived",
        premises=(direct.id, hypothesized.id),
    )
    checked = validate_rich_account(
        account.model_copy(update={"temporal_relations": (direct, hypothesized, derived)})
    )
    policy = TemporalPolicy(statuses=("surface-explicit", "structurally-derived"))
    assert [row.id for row in temporal_table(checked, policy=policy)] == [direct.id]
    query = query_temporal_relation(checked, a, c, direct.context, policy=policy)
    assert (
        derived.id not in query.constraint_claims and hypothesized.id not in query.constraint_claims
    )

    from storymodel.relations import RichRelationProposal, RichRelationProposals, admit_relations

    document, support = account.documents[0], account._situations()[0].evidence[0]
    span = support.spans[0]
    quote = RelationQuote(document=document.id, span=span, text=span.slice(document.text))
    raw = canonical(
        RichRelationProposals(
            account_sha256=digest(account.model_dump(mode="json")),
            status="completed",
            relations=(
                RichRelationProposal(
                    key="legacy", kind="before", source=a, target=b, evidence=(quote,)
                ),
            ),
        ).model_dump(mode="json")
    )
    legacy = upgrade_temporal_account(admit_relations(account, raw).account)
    legacy_row = temporal_table(legacy)[0]
    bc = _claim(legacy, b, c, "before")
    legacy_derived = _claim(
        legacy,
        a,
        c,
        "before",
        status="structurally-derived",
        premises=(legacy_row.id, bc.id),
    )
    legacy_checked = validate_rich_account(
        legacy.model_copy(update={"temporal_relations": (bc, legacy_derived)})
    )
    no_legacy = TemporalPolicy(include_legacy=False)
    assert [row.id for row in temporal_table(legacy_checked, policy=no_legacy)] == [bc.id]
    legacy_query = query_temporal_relation(legacy_checked, a, c, bc.context, policy=no_legacy)
    assert legacy_derived.id not in legacy_query.constraint_claims


def test_policy_exclusion_propagates_through_multiple_derivation_levels():
    account, _ = control(labels=("AA", "BB", "CC", "DD"))
    account = upgrade_temporal_account(account)
    a, b, c, d = (item.id for item in account._situations())
    ab = _claim(account, a, b, "before")
    bc = TemporalClaim.create(
        source=b,
        target=c,
        relation="before",
        context=ab.context,
        status="hypothesized",
        evidence=ab.evidence,
        origin="abstract-control",
        procedure="chronology-test/v1",
    )
    cd = _claim(account, c, d, "before")
    ac = _claim(account, a, c, "before", status="structurally-derived", premises=(ab.id, bc.id))
    ad = _claim(account, a, d, "before", status="structurally-derived", premises=(ac.id, cd.id))
    checked = validate_rich_account(
        account.model_copy(update={"temporal_relations": (ab, bc, cd, ac, ad)})
    )
    policy = TemporalPolicy(statuses=("surface-explicit", "structurally-derived"))
    assert {row.id for row in temporal_table(checked, policy=policy)} == {ab.id, cd.id}
    assert len(query_temporal_relation(checked, a, d, ab.context, policy=policy).possible) == 13


def test_proposals_pin_raw_account_and_adjudication_and_replay_atomically():
    account = _account()
    a, b, _ = (item.id for item in account._situations())
    document, support = account.documents[0], account._situations()[0].evidence[0]
    span = support.spans[0]
    proposal = TemporalProposal(
        key="claim",
        source=a,
        target=b,
        relation="before",
        context=account._situations()[0].context,
        status="surface-explicit",
        evidence=(RelationQuote(document=document.id, span=span, text=span.slice(document.text)),),
    )
    raw, result = _admission(account, (proposal,))
    assert (
        result.status == "admitted"
        and result.response_sha256 == sha256(raw.encode("utf-8")).hexdigest()
    )
    assert verify_temporal_relations(account, raw, result) == result
    with pytest.raises(ValueError, match="another account"):
        admit_temporal_relations(_account(root_events=True), raw)
    tampered = result.model_copy(update={"accepted": {}})
    with pytest.raises(ValueError, match="differs"):
        verify_temporal_relations(account, raw, tampered)
    human = proposal.model_copy(update={"key": "human", "status": "human-adjudicated"})
    _, refused = _admission(account, (human,))
    assert refused.status == "rejected"
    _, admitted = _admission(account, (human,), adjudicator="reviewer")
    assert admitted.status == "admitted" and admitted.adjudicator == "reviewer"


def test_native_temporal_evidence_queries_without_fabricating_text_or_numerical_projection():
    request, packet = playback_fixture()
    account = upgrade_temporal_account(compile_playback(request, canonical(packet)).account)
    event, state = account.events[0], account.states[0]
    proposal = TemporalProposal(
        key="native",
        source=event.id,
        target=state.id,
        relation="before",
        context=event.context,
        status="surface-explicit",
        evidence=(event.evidence[0],),
    )
    raw, result = _admission(account, (proposal,))
    assert result.status == "admitted" and not result.account.documents
    query = query_temporal_relation(result.account, event.id, state.id, event.context)
    assert (
        "before" in query.possible
        and query.interpretation == "interval-consistency-not-measured-time"
    )
    assert verify_temporal_query(result.account, event.id, state.id, event.context, query) == query


def test_temporal_cli_admit_verify_table_query_and_verify_query_round_trip(tmp_path):
    account = validate_rich_account(_account().model_copy(update={"export_permission": "allowed"}))
    a, b, _ = (item.id for item in account._situations())
    document, support = account.documents[0], account._situations()[0].evidence[0]
    span = support.spans[0]
    proposal = TemporalProposal(
        key="cli",
        source=a,
        target=b,
        relation="before",
        context=account._situations()[0].context,
        status="surface-explicit",
        evidence=(RelationQuote(document=document.id, span=span, text=span.slice(document.text)),),
    )
    raw, expected = _admission(account, (proposal,))
    account_path, proposals_path = tmp_path / "account.json", tmp_path / "proposals.json"
    result_path, admitted_path, table_path, query_path = (
        tmp_path / "result.json",
        tmp_path / "admitted.json",
        tmp_path / "table.json",
        tmp_path / "query.json",
    )
    publish_json(account_path, account.model_dump(mode="json"))
    proposals_path.write_text(raw, encoding="utf-8")
    assert (
        main(
            ["admit-temporal", str(account_path), str(proposals_path), "--output", str(result_path)]
        )
        == 0
    )
    assert json.loads(result_path.read_text(encoding="utf-8")) == expected.model_dump(mode="json")
    assert main(["verify-temporal", str(account_path), str(proposals_path), str(result_path)]) == 0
    assert (
        main(
            [
                "admit-temporal",
                str(account_path),
                str(proposals_path),
                "--account-only",
                "--output",
                str(admitted_path),
            ]
        )
        == 0
    )
    assert main(["temporal-table", str(admitted_path), "--output", str(table_path)]) == 0
    assert (
        main(
            [
                "query-temporal",
                str(admitted_path),
                a,
                b,
                account._situations()[0].context,
                "--output",
                str(query_path),
            ]
        )
        == 0
    )
    assert (
        main(
            [
                "verify-temporal-query",
                str(admitted_path),
                a,
                b,
                account._situations()[0].context,
                str(query_path),
            ]
        )
        == 0
    )


def test_v7_schema_and_policy_preserve_all_statuses_without_numeric_meaning():
    schema = account_schema("v7")
    assert schema["properties"]["temporal_relations"]["items"]["$ref"] == "#/$defs/TemporalClaim"
    assert TemporalPolicy(statuses=STATUSES).statuses == STATUSES
    with pytest.raises(ValueError, match="duplicate"):
        TemporalPolicy(statuses=("surface-explicit", "surface-explicit"))
