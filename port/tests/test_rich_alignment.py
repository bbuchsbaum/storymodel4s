"""Reference localization remains separate from rich qualifier evidence and fidelity."""

import json
import subprocess
import sys
from pathlib import Path

import pytest
from test_alignment import control

from storymodel.alignment import AlignmentPolicy, AlignmentResult, align_account, verify_alignment
from storymodel.analysis import project
from storymodel.model import Span, State, Support, Unit
from storymodel.rich_account import Circumstance, upgrade_account, validate_rich_account
from storymodel.serialization import digest


def rich_control(
    source_specs=None,
    recall_specs=None,
    *,
    source_kinds=None,
    source_qualifiers=None,
    recall_qualifiers=None,
):
    source_specs = source_specs or [("P", "X", "agent")]
    recall_specs = recall_specs or [("P", "X", "agent")]
    legacy = control(source_specs, recall_specs)
    rich = upgrade_account(legacy.model_copy(update={"schema_version": "storymodel.account/v3"}))
    source_kinds = source_kinds or ["state"] * len(source_specs)
    qualifiers = {
        "source": source_qualifiers or [{} for _ in source_specs],
        "recall": recall_qualifiers or [{} for _ in recall_specs],
    }
    counts = {"source": 0, "recall": 0}
    events, states, replacements = [], [], {}
    for event in rich.events:
        index = counts[event.side]
        counts[event.side] += 1
        if event.side == "source" and source_kinds[index] == "event":
            events.append(event)
            replacements[event.id] = event.id
        else:
            state = State(
                side=event.side,
                context=event.context,
                predicate=event.predicate,
                participants=event.participants,
                evidence=event.evidence,
                **qualifiers[event.side][index],
            )
            states.append(state)
            replacements[event.id] = state.id
    return validate_rich_account(
        rich.model_copy(
            update={
                "events": tuple(events),
                "states": tuple(states),
                "details": tuple(
                    d.model_copy(update={"event": replacements[d.event]}) for d in rich.details
                ),
            }
        )
    )


def targets(result):
    return tuple(
        (d.reference.status, d.reference.outcome, tuple(h.targets for h in d.reference.hypotheses))
        for d in result.details
    )


def test_state_candidates_preserve_rich_graph_and_do_not_create_fidelity():
    account = rich_control()
    before = account.model_dump_json()
    result = align_account(account)
    assert result.schema_version == "storymodel.alignment-result/v2"
    assert result.recipe == "local-situation-predicate-role-reference/v1"
    source = next(s for s in account.states if s.side == "source")
    recall = next(s for s in account.states if s.side == "recall")
    assert result.source_universes == {"event": (), "state": (source.id,)}
    group = result.situations[0]
    assert (group.recall_situation, group.kind, group.truncated) == (recall.id, "state", False)
    candidate = group.candidates[0]
    assert candidate.source_situation == source.id and candidate.kind == "state"
    assert (
        candidate.eligible
        and candidate.polarity.comparison == candidate.modality.comparison == "unknown"
    )
    assert result.details[0].source_situations == (source.id,)
    assert result.details[0].reference.outcome == "resolved"
    for name in type(account).model_fields:
        if name not in ("bindings", "recipe"):
            assert getattr(result.account, name) == getattr(account, name), name
    assert all(not b.assessments for b in result.account.bindings)
    assert {r.coverage for r in project(result.account).source} == {"not-assessable"}
    assert account.model_dump_json() == before
    assert (
        verify_alignment(account, result)
        == verify_alignment(account, result.model_dump_json())
        == result
    )


@pytest.mark.parametrize(
    "source,recall,expected",
    [
        (
            {"polarity": "positive", "modality": "possible"},
            {"polarity": "positive", "modality": "possible"},
            ("same-declared", "same-declared"),
        ),
        (
            {"polarity": "negative", "modality": "reported"},
            {"polarity": "positive", "modality": "asserted"},
            ("different-declared", "different-declared"),
        ),
        (
            {"polarity": "unknown", "modality": "unknown"},
            {"polarity": "unknown", "modality": "unknown"},
            ("unknown", "unknown"),
        ),
        (
            {"polarity": "negative", "modality": "unknown"},
            {"polarity": "unknown", "modality": "possible"},
            ("unknown", "unknown"),
        ),
    ],
)
def test_qualifiers_are_diagnostics_not_reference_or_fidelity_gates(source, recall, expected):
    baseline = align_account(rich_control())
    result = align_account(rich_control(source_qualifiers=[source], recall_qualifiers=[recall]))
    candidate = result.situations[0].candidates[0]
    assert (candidate.polarity.comparison, candidate.modality.comparison) == expected
    assert (candidate.polarity.source, candidate.polarity.recall) == (
        source["polarity"],
        recall["polarity"],
    )
    assert (candidate.modality.source, candidate.modality.recall) == (
        source["modality"],
        recall["modality"],
    )
    assert candidate.eligible and targets(result) == targets(baseline)
    assert all(not binding.assessments for binding in result.account.bindings)


def test_same_kind_retrieval_precedes_budget_and_echoes_both_universes():
    baseline = align_account(rich_control(), AlignmentPolicy(max_candidates=1))
    account = rich_control([("P", "X", "agent")] * 21, source_kinds=["state"] + ["event"] * 20)
    result = align_account(account, AlignmentPolicy(max_candidates=1))
    assert (
        len(result.source_universes["event"]) == 20 and len(result.source_universes["state"]) == 1
    )
    assert (
        result.situations[0].retrieved_count_lower_bound == 1 and not result.situations[0].truncated
    )
    assert (
        len(result.situations[0].candidates) == 1
        and result.details[0].reference.outcome == "resolved"
    )
    assert baseline.details[0].reference.outcome == result.details[0].reference.outcome
    # Same-kind distractors do consume budget and must withhold resolution.
    overflow = align_account(
        rich_control([("P", "X", "agent")] * 2), AlignmentPolicy(max_candidates=1)
    )
    assert (
        overflow.situations[0].truncated
        and overflow.details[0].reference.status == "search-limited"
    )
    only_wrong_kind = align_account(rich_control(source_kinds=["event"]))
    assert only_wrong_kind.source_universes["state"] == ()
    assert not only_wrong_kind.situations[0].candidates
    assert only_wrong_kind.details[0].reference.outcome == "unresolved"


def test_qualifier_agreement_does_not_select_alternatives_or_collapse_same_row_ambiguity():
    account = rich_control(
        [("P", "X", "agent")] * 2,
        source_qualifiers=[{"polarity": "positive"}, {"polarity": "negative"}],
        recall_qualifiers=[{"polarity": "positive"}],
    )
    result = align_account(account)
    assert {c.polarity.comparison for c in result.situations[0].candidates} == {
        "same-declared",
        "different-declared",
    }
    assert all(c.eligible for c in result.situations[0].candidates)
    assert result.details[0].reference.outcome == "alternative"
    assert len(result.details[0].reference.hypotheses) == 2
    document = next(d for d in account.documents if d.side == "source")
    row = Unit(
        kind="row",
        support=Support(document=document.id, spans=(Span(start=0, end=len(document.text)),)),
    )
    shared = validate_rich_account(
        account.model_copy(
            update={"units": (row,) + tuple(u for u in account.units if u.kind == "recall")}
        )
    )
    ambiguous = align_account(shared)
    assert ambiguous.details[0].reference.outcome == "unresolved"
    assert "share a row" in ambiguous.details[0].reference.reason


def test_circumstance_evidence_is_sorted_preserved_and_not_paired_or_treated_as_time():
    account = rich_control()
    circumstances = []
    for situation in account.states:
        doc = situation.evidence[0].document
        for start, end in ((0, 2), (3, 5)):
            circumstances.append(
                Circumstance(
                    situation=situation.id,
                    kind="time",
                    label="AA",
                    evidence=(Support(document=doc, spans=(Span(start=start, end=end),)),),
                )
            )
    account = validate_rich_account(
        account.model_copy(update={"circumstances": tuple(circumstances)})
    )
    result = align_account(account)
    candidate = result.situations[0].candidates[0]
    for field, owner in (
        ("source_circumstances", candidate.source_situation),
        ("recall_circumstances", result.situations[0].recall_situation),
    ):
        observed = getattr(candidate, field)
        assert observed == tuple(
            sorted((c for c in circumstances if c.situation == owner), key=lambda c: c.id)
        )
        assert len(observed) == len({c.id for c in observed}) == 2
    reordered = validate_rich_account(
        account.model_copy(update={"circumstances": tuple(reversed(circumstances))})
    )
    assert align_account(reordered).situations == result.situations
    assert targets(align_account(rich_control())) == targets(result)


def test_event_diagnostics_are_not_state_unknowns_and_existing_bindings_survive():
    legacy = control([("P", "X", "agent")], [("P", "X", "agent")])
    account = upgrade_account(legacy.model_copy(update={"schema_version": "storymodel.account/v3"}))
    result = align_account(account)
    candidate = result.situations[0].candidates[0]
    assert candidate.kind == "event"
    assert candidate.polarity.comparison == candidate.modality.comparison == "not-applicable"
    assert candidate.polarity.source is candidate.polarity.recall is None
    source = next(e for e in account.events if e.side == "source")
    distractors = tuple(
        State(
            side=source.side,
            context=source.context,
            predicate=source.predicate,
            participants=source.participants,
            evidence=source.evidence,
            polarity=p,
        )
        for p in ("positive", "negative", "unknown")
    )
    enriched = validate_rich_account(account.model_copy(update={"states": distractors}))
    event_query = align_account(enriched, AlignmentPolicy(max_candidates=1))
    assert len(event_query.source_universes["state"]) == 3
    assert not event_query.situations[0].truncated
    assert event_query.situations[0].retrieved_count_lower_bound == 1
    assert targets(event_query) == targets(result)
    rerun = align_account(result.account)
    assert rerun.account.bindings == result.account.bindings
    assert all(d.action == "preserved" for d in rerun.details) and not rerun.situations


def test_rich_receipts_and_forged_inputs_cannot_drop_fields_or_diagnostics():
    account = rich_control()
    result = align_account(account)
    with pytest.raises(ValueError):
        align_account(account.model_copy(update={"unexpected": True}))
    with pytest.raises(ValueError):
        verify_alignment(account, result.model_copy(update={"unexpected": True}))
    group = result.situations[0]
    candidate = group.candidates[0]
    changed = candidate.model_copy(
        update={"polarity": candidate.polarity.model_copy(update={"comparison": "same-declared"})}
    )
    with pytest.raises(ValueError):
        verify_alignment(
            account,
            result.model_copy(
                update={"situations": (group.model_copy(update={"candidates": (changed,)}),)}
            ),
        )
    with pytest.raises(ValueError):
        verify_alignment(
            account, result.model_dump_json().replace("alignment-result/v2", "alignment-result/v1")
        )


def test_rich_schema_and_frozen_legacy_alignment():
    from storymodel.alignment import RichAlignmentResult

    schema = json.loads((Path(__file__).parents[1] / "schemas/rich-alignment.json").read_text())
    assert schema == {
        "policy": AlignmentPolicy.model_json_schema(),
        "result": RichAlignmentResult.model_json_schema(),
    }
    account = control([("P", "X", "agent"), ("Q", "X", "agent")], [("Q", "X", "agent")])
    assert {
        k: digest(v)
        for k, v in {
            "policy": AlignmentPolicy.model_json_schema(),
            "schema": AlignmentResult.model_json_schema(),
            "result": align_account(account).model_dump(mode="json"),
        }.items()
    } == {
        "policy": "9743b871b0ab5986c6642b3b16ab5d80839dd2ff7055e54698825efd5eb9d304",
        "schema": "f2947c7d07b78f17652ca3d17e608e56e467dc83f0a75b17a3f54713413a0291",
        "result": "0dc3577b2b9d7e01f42c71edd264650136a09c0b3ab3b6503dae637e377e69a0",
    }


def test_sparse_state_retrieval_and_prior_assessments_survive():
    sparse = rich_control(
        [(f"P{i}", f"X{i}", "agent") for i in range(100)],
        [(f"P{i}", f"X{i}", "agent") for i in (1, 40, 99)],
    )
    result = align_account(sparse)
    assert len(result.source_universes["state"]) == 100
    assert sum(len(g.candidates) for g in result.situations) == 3
    assert sum(g.retrieved_count_lower_bound for g in result.situations) == 3

    from test_rich_mapping import fixture, response

    from storymodel.direct_mapping import prepare_mapping, resolve_mapping

    _, account = fixture()
    request = prepare_mapping(account, account.details[0].unit)
    account = resolve_mapping(request, response(request)).account
    assert any(b.assessments for b in account.bindings)
    result = align_account(account)
    assert result.account.bindings == account.bindings
    assert result.account.states == account.states
    assert result.account.circumstances == account.circumstances
    assert all(d.action == "preserved" for d in result.details)
    assert project(result.account).source == project(account).source


def test_rich_alignment_workflow_cli_and_grants(tmp_path, capsys):
    from storymodel.artifacts import publish_json, read_supported_account
    from storymodel.cli import main

    output = tmp_path / "example"
    completed = subprocess.run(
        [
            sys.executable,
            str(Path(__file__).parents[1] / "examples/rich_alignment_workflow.py"),
            str(output),
        ],
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    assert completed.returncode == 0, completed.stderr
    for name in ("recall-a", "recall-b"):
        root = output / name
        assert (
            main(["align", str(root / "input-account.json"), "--output", str(root / "cli.json")])
            == 0
        )
        assert json.loads((root / "cli.json").read_text()) == json.loads(
            (root / "alignment.json").read_text()
        )
        assert (
            main(
                [
                    "align",
                    str(root / "input-account.json"),
                    "--account-only",
                    "--output",
                    str(root / "cli-account.json"),
                ]
            )
            == 0
        )
        assert read_supported_account(root / "cli-account.json") == read_supported_account(
            root / "account.json"
        )
    denied = rich_control().model_copy(update={"export_permission": "denied"})
    publish_json(tmp_path / "denied.json", denied.model_dump(mode="json"))
    assert (
        main(
            [
                "align",
                str(tmp_path / "denied.json"),
                "--policy-file",
                str(tmp_path / "missing"),
                "--output",
                str(tmp_path / "forbidden"),
            ]
        )
        == 2
    )
    assert "denies alignment export" in capsys.readouterr().err
    assert not (tmp_path / "forbidden").exists()
