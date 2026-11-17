"""Construction-link admission resolves verified row-local situation names only."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.construction_links import (
    ConstructionLinks,
    connect_rich_construction,
    construction_link_prompt,
    verify_connected_construction,
)
from storymodel.model import Span
from storymodel.rich_construction import compile_rich_construction, prepare_rich_construction
from storymodel.serialization import canonical, digest
from storymodel.source import SourceRow
from storymodel.source_transitions import project_source

PILOT = Path(__file__).resolve().parents[1]


def quote(text: str, start: int, end: int) -> dict[str, object]:
    return {"span": {"start": start, "end": end}, "text": Span(start=start, end=end).slice(text)}


def fixture(*, side: str = "source"):
    """Use identical local names in separate rows to make occurrence selection observable."""
    text = "AA BB AA BB"
    request = prepare_rich_construction(
        "AA",
        side,
        text,
        (
            SourceRow(key="AA", span=Span(start=0, end=5)),
            SourceRow(key="BB", span=Span(start=6, end=11)),
        ),
        export_permission="allowed",
    )
    construction = canonical(
        {
            "schema_version": "storymodel.rich-construction-proposal/v1",
            "rows": [
                {
                    "row": "AA",
                    "status": "completed",
                    "events": [
                        {
                            "key": "AA",
                            "context": "narrated",
                            "evidence": quote(text, 0, 5),
                            "predicate": quote(text, 0, 2),
                        }
                    ],
                    "entities": [{"key": "BB", "mention": quote(text, 3, 5)}],
                },
                {
                    "row": "BB",
                    "status": "completed",
                    "states": [
                        {
                            "key": "AA",
                            "context": "narrated",
                            "evidence": quote(text, 6, 11),
                            "predicate": quote(text, 6, 8),
                        }
                    ],
                },
            ],
        }
    )
    return request, construction


def links(request, construction: str, *, status="completed", reason=None, relations=()) -> str:
    result = compile_rich_construction(request, construction)
    return canonical(
        {
            "schema_version": "storymodel.construction-links/v1",
            "construction_sha256": digest(result.model_dump(mode="json")),
            "status": status,
            **({"reason": reason} if reason is not None else {}),
            "relations": relations,
        }
    )


def relation(
    key="AA", kind="before", source=("AA", "AA"), target=("BB", "AA"), evidence=("AA", 0, 2)
):
    row, start, end = evidence
    text = "AA BB AA BB"
    return {
        "key": key,
        "kind": kind,
        "source": {"row": source[0], "key": source[1]},
        "target": {"row": target[0], "key": target[1]},
        "evidence": [{"row": row, "quote": quote(text, start, end)}],
    }


def test_row_local_names_select_distinct_source_and_recall_occurrences_and_preserve_graph():
    for side in ("source", "recall"):
        request, construction = fixture(side=side)
        result = connect_rich_construction(
            request,
            construction,
            links(
                request,
                construction,
                relations=(
                    relation("AA", "before"),
                    relation("BB", "causes", evidence=("BB", 6, 8)),
                ),
            ),
        )
        original = compile_rich_construction(request, construction).account
        assert result.status == "admitted"
        assert len(result.account.events) == len(result.account.states) == 1
        assert {edge.kind for edge in result.account.relations} == {"before", "causes"}
        for name in type(original).model_fields:
            if name not in {"relations", "recipe"}:
                assert getattr(result.account, name) == getattr(original, name), name
        endpoints = {(edge.source, edge.target) for edge in result.account.relations}
        assert endpoints == {(original.events[0].id, original.states[0].id)}
        if side == "source":
            edges = {
                (edge.layer, edge.source, edge.target)
                for edge in project_source(result.account).edges
            }
            assert ("world-time", original.events[0].id, original.states[0].id) in edges
            assert ("causal", original.events[0].id, original.states[0].id) in edges


@pytest.mark.parametrize("kind", ("part_of", "references"))
def test_remaining_link_kinds_do_not_invent_primary_hierarchy(kind):
    request, construction = fixture()
    result = connect_rich_construction(
        request, construction, links(request, construction, relations=(relation(kind=kind),))
    )
    assert result.status == "admitted" and result.account.relations[0].kind == kind
    projection = project_source(result.account)
    assert not any(edge.layer == "hierarchy" for edge in projection.edges)
    assert any(edge.layer == "reference" for edge in projection.edges) == (kind == "references")


@pytest.mark.parametrize("bad", ["entity", "context", "missing", "rejected"])
def test_non_situation_and_unadmitted_row_references_are_rejected_without_graph_mutation(bad):
    request, construction = fixture()
    raw = relation()
    if bad == "entity":
        raw["source"] = {"row": "AA", "key": "BB"}
    elif bad == "context":
        raw["source"] = {"row": "AA", "key": "narrated"}
    elif bad == "missing":
        raw["target"] = {"row": "BB", "key": "BB"}
    else:
        packet = {
            "schema_version": "storymodel.rich-construction-proposal/v1",
            "rows": [
                {"row": "AA", "status": "failed", "reason": "AA"},
                {"row": "BB", "status": "completed", "states": []},
            ],
        }
        construction = canonical(packet)
    result = connect_rich_construction(
        request, construction, links(request, construction, relations=(raw,))
    )
    assert result.status == "rejected"
    assert not result.accepted and not result.account.relations


@pytest.mark.parametrize("kind", ["causes", "before"])
def test_relation_checks_contexts_self_edges_cycles_and_endpoint_row_evidence(kind):
    request, construction = fixture()
    bad_evidence = relation(kind=kind, evidence=("BB", 6, 8))
    # The BB row is an endpoint, so this is valid; an evidence row outside both endpoints is not.
    bad_evidence["evidence"][0]["row"] = "CC"
    result = connect_rich_construction(
        request, construction, links(request, construction, relations=(bad_evidence,))
    )
    assert result.status == "rejected" and not result.account.relations

    self_edge = relation(kind=kind, target=("AA", "AA"))
    result = connect_rich_construction(
        request, construction, links(request, construction, relations=(self_edge,))
    )
    assert result.status == "rejected" and not result.account.relations

    forward = relation("AA", "before")
    backward = relation(
        "BB", "before", source=("BB", "AA"), target=("AA", "AA"), evidence=("BB", 6, 8)
    )
    result = connect_rich_construction(
        request, construction, links(request, construction, relations=(forward, backward))
    )
    assert result.status == "rejected" and not result.account.relations


def test_context_crossing_nonreference_refuses_but_reference_is_explicitly_allowed():
    text = "AA BB AA BB"
    request = prepare_rich_construction(
        "BB",
        "source",
        text,
        (
            SourceRow(key="AA", span=Span(start=0, end=5)),
            SourceRow(key="BB", span=Span(start=6, end=11)),
        ),
        export_permission="allowed",
    )
    construction = canonical(
        {
            "schema_version": "storymodel.rich-construction-proposal/v1",
            "rows": [
                {
                    "row": "AA",
                    "status": "completed",
                    "events": [
                        {
                            "key": "AA",
                            "context": "narrated",
                            "evidence": quote(text, 0, 2),
                            "predicate": quote(text, 0, 2),
                        }
                    ],
                },
                {
                    "row": "BB",
                    "status": "completed",
                    "contexts": [
                        {
                            "key": "BB",
                            "kind": "reported",
                            "parent": "narrated",
                            "cue": quote(text, 6, 8),
                            "scope": quote(text, 6, 11),
                        }
                    ],
                    "states": [
                        {
                            "key": "AA",
                            "context": "BB",
                            "evidence": quote(text, 9, 11),
                            "predicate": quote(text, 9, 11),
                        }
                    ],
                },
            ],
        }
    )
    causal = relation("AA", "causes", evidence=("AA", 0, 2))
    assert (
        connect_rich_construction(
            request, construction, links(request, construction, relations=(causal,))
        ).status
        == "rejected"
    )
    reference = relation("AA", "references", evidence=("AA", 0, 2))
    assert (
        connect_rich_construction(
            request, construction, links(request, construction, relations=(reference,))
        ).status
        == "admitted"
    )


def test_receipts_bind_exact_construction_and_link_bytes_and_revalidate_typed_values():
    request, construction = fixture()
    raw = links(request, construction, relations=(relation(),))
    result = connect_rich_construction(request, construction, raw)
    assert verify_connected_construction(request, construction, raw, result) == result
    with pytest.raises(ValueError, match="another construction"):
        connect_rich_construction(
            request,
            construction,
            raw.replace(
                digest(compile_rich_construction(request, construction).model_dump(mode="json")),
                "0" * 64,
            ),
        )
    with pytest.raises(ValueError, match="differs from original"):
        verify_connected_construction(request, construction, raw + "\n", result)
    with pytest.raises(ValueError):
        verify_connected_construction(
            request, construction, raw, result.model_copy(update={"status": "rejected"})
        )
    forged_data = result.model_dump()
    forged_data["accepted"] = {"AA": 1}
    forged = type(result).model_construct(**forged_data)
    with pytest.raises(ValueError):
        verify_connected_construction(request, construction, raw, forged)


def test_link_processing_outcomes_and_prompt_export_denial_are_explicit():
    request, construction = fixture()
    for status, expected in (
        ("search-limited", "partial"),
        ("not-attempted", "not-attempted"),
        ("failed", "failed"),
    ):
        result = connect_rich_construction(
            request, construction, links(request, construction, status=status, reason="AA")
        )
        assert result.status == expected and not result.account.relations
    duplicate = relation()
    with pytest.raises(ValueError, match="duplicate construction link key"):
        connect_rich_construction(
            request, construction, links(request, construction, relations=(duplicate, duplicate))
        )
    denied = request.model_copy(update={"export_permission": "denied"})
    with pytest.raises(PermissionError, match="denies construction links export"):
        construction_link_prompt(denied, construction)


def test_row_escaped_and_utf16_split_quotes_are_rejected():
    request, construction = fixture()
    escaped = relation()
    escaped["evidence"][0]["quote"] = quote("AA BB AA BB", 0, 6)
    result = connect_rich_construction(
        request, construction, links(request, construction, relations=(escaped,))
    )
    assert result.status == "rejected"

    text = "😀 AA BB"
    unicode_request = prepare_rich_construction(
        "AA",
        "source",
        text,
        (SourceRow(key="AA", span=Span(start=0, end=8)),),
        export_permission="allowed",
    )
    unicode_construction = canonical(
        {
            "schema_version": "storymodel.rich-construction-proposal/v1",
            "rows": [
                {
                    "row": "AA",
                    "status": "completed",
                    "events": [
                        {
                            "key": "AA",
                            "context": "narrated",
                            "evidence": quote(text, 3, 5),
                            "predicate": quote(text, 3, 5),
                        }
                    ],
                    "states": [
                        {
                            "key": "BB",
                            "context": "narrated",
                            "evidence": quote(text, 6, 8),
                            "predicate": quote(text, 6, 8),
                        }
                    ],
                }
            ],
        }
    )
    unicode_link = relation(source=("AA", "AA"), target=("AA", "BB"))
    unicode_link["evidence"] = [
        {"row": "AA", "quote": {"span": {"start": 0, "end": 1}, "text": "AA"}}
    ]
    result = connect_rich_construction(
        unicode_request,
        unicode_construction,
        links(unicode_request, unicode_construction, relations=(unicode_link,)),
    )
    assert result.status == "rejected"


def test_cli_and_committed_schema_bind_the_same_public_contract(tmp_path, capsys):
    request, construction = fixture()
    request_path = tmp_path / "request.json"
    construction_path = tmp_path / "construction.json"
    links_path = tmp_path / "links.json"
    result_path = tmp_path / "result.json"
    publish_json(request_path, request.model_dump(mode="json"))
    construction_path.write_text(construction, encoding="utf-8")
    links_path.write_text(links(request, construction, relations=(relation(),)), encoding="utf-8")
    assert main(["construction-link-prompt", str(request_path), str(construction_path)]) == 0
    assert json.loads(capsys.readouterr().out) == json.loads(
        construction_link_prompt(request, construction)
    )
    assert (
        main(
            [
                "connect-rich",
                str(request_path),
                str(construction_path),
                str(links_path),
                "--output",
                str(result_path),
            ]
        )
        == 0
    )
    expected = connect_rich_construction(request, construction, links_path.read_text())
    assert json.loads(result_path.read_text()) == expected.model_dump(mode="json")
    account_path = tmp_path / "account.json"
    assert (
        main(
            [
                "connect-rich",
                str(request_path),
                str(construction_path),
                str(links_path),
                "--account-only",
                "--output",
                str(account_path),
            ]
        )
        == 0
    )
    assert json.loads(account_path.read_text()) == expected.account.model_dump(mode="json")
    verified = tmp_path / "verified.json"
    assert (
        main(
            [
                "verify-connected-rich",
                str(request_path),
                str(construction_path),
                str(links_path),
                str(result_path),
                "--output",
                str(verified),
            ]
        )
        == 0
    )
    assert json.loads(verified.read_text())["verified"] is True

    limited_path = tmp_path / "limited.json"
    limited_path.write_text(
        links(request, construction, status="search-limited", reason="AA"), encoding="utf-8"
    )
    refused = tmp_path / "partial-account.json"
    assert (
        main(
            [
                "connect-rich",
                str(request_path),
                str(construction_path),
                str(limited_path),
                "--account-only",
                "--output",
                str(refused),
            ]
        )
        == 2
    )
    assert "hide incomplete connected construction" in capsys.readouterr().err
    assert not refused.exists()

    incomplete = json.loads(construction)
    incomplete["rows"][1] = {"row": "BB", "status": "failed", "reason": "AA"}
    incomplete_raw = canonical(incomplete)
    incomplete_path = tmp_path / "incomplete.json"
    incomplete_path.write_text(incomplete_raw, encoding="utf-8")
    incomplete_links = tmp_path / "incomplete-links.json"
    incomplete_links.write_text(
        links(request, incomplete_raw, status="not-attempted", reason="AA"), encoding="utf-8"
    )
    assert (
        main(
            [
                "connect-rich",
                str(request_path),
                str(incomplete_path),
                str(incomplete_links),
                "--account-only",
                "--output",
                str(refused),
            ]
        )
        == 2
    )
    assert "hide incomplete connected construction" in capsys.readouterr().err
    assert not refused.exists()

    denied_path = tmp_path / "denied.json"
    publish_json(
        denied_path,
        request.model_copy(update={"export_permission": "denied"}).model_dump(mode="json"),
    )
    output = tmp_path / "refused.json"
    assert (
        main(
            [
                "construction-link-prompt",
                str(denied_path),
                str(tmp_path / "missing"),
                "--output",
                str(output),
            ]
        )
        == 2
    )
    assert "denies construction links export" in capsys.readouterr().err
    assert not output.exists()

    assert json.loads((PILOT / "schemas" / "construction-links.json").read_text()) == {
        "links": ConstructionLinks.model_json_schema(),
        "result": type(expected).model_json_schema(),
    }
