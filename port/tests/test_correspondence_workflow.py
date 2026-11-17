"""Read saved correspondence artifacts, independently checking scientific distinctions."""

import json
import subprocess
import sys
from pathlib import Path

import pytest

from storymodel import project, read_supported_account
from storymodel.claim_assessment import validate_claim_plan, verify_claim_assessment
from storymodel.cli import main
from storymodel.direct_workflow import read_mapping_request, replay_mapping
from storymodel.fidelity_workflow import read_fidelity_request, replay_fidelity
from storymodel.scenes import SceneGroup, SceneGroupingResult
from storymodel.serialization import canonical


@pytest.fixture(scope="module")
def workflow(tmp_path_factory):
    root = tmp_path_factory.mktemp("correspondence") / "artifacts"
    port = Path(__file__).resolve().parents[1]
    result = subprocess.run(
        [sys.executable, str(port / "examples/correspondence_workflow.py"), str(root)],
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    return root


def test_correspondence_artifacts_separate_candidates_reference_fidelity_and_absence(workflow):
    source = read_supported_account(workflow / "source/account.json")
    rows = sorted(
        (u for u in source.units if u.kind == "row"), key=lambda u: u.support.spans[0].start
    )
    assert [u.support.spans[0].start for u in rows] == [0, 12, 24, 36]
    scene = next(u for u in source.units if u.kind == "scene" and u.id == rows[0].parent)
    assert [(s.start, s.end) for s in scene.support.spans] == [(0, 11), (12, 23)]
    assert rows[0].parent == rows[1].parent and rows[2].parent == rows[3].parent != rows[0].parent
    results = {}
    for session in ("recall-a", "recall-b"):
        root = workflow / session
        account = read_supported_account(root / "account.json")
        assert tuple(u for u in account.units if u.kind != "recall") == source.units
        assert tuple(d for d in account.documents if d.side == "source") == source.documents
        bindings = {
            d.unit: next(b for b in account.bindings if b.detail == d.id) for d in account.details
        }
        summary = json.loads((workflow / "summary.json").read_text())["sessions"][session]
        units = summary["units"]
        expected = {
            "first": ("single", (rows[0].id,), "supported"),
            "second": ("single", (rows[1].id,), "supported"),
            "revisit": ("single", (rows[1].id,), "supported"),
            "swap": ("single", (rows[1].id,), "contradicted"),
            "changed": ("single", (rows[1].id,), "gist-detail-error"),
            "joint": ("joint", (rows[0].id, rows[1].id), "supported"),
            "broad": ("broad", (scene.id,), "supported"),
            "external": ("external", (), "external"),
        }
        for key, (kind, targets, grade) in expected.items():
            binding = bindings[units[key]]
            assert binding.reference.outcome == "resolved"
            assert binding.reference.hypotheses[0].kind == kind
            assert set(binding.reference.hypotheses[0].targets) == set(targets)
            assert binding.assessments[0].grade == grade
        alternatives = bindings[units["ambiguous"]]
        assert alternatives.reference.outcome == "alternative"
        assert {h.targets for h in alternatives.reference.hypotheses} == {
            (rows[0].id,),
            (rows[2].id,),
        }
        assert {a.grade for a in alternatives.assessments} == {"supported"}
        assert bindings[units["changed"]].assessments[0].severity == "minor"
        assert bindings[units["second"]].detail != bindings[units["revisit"]].detail
        assert (
            bindings[units["second"]].reference.hypotheses
            == bindings[units["revisit"]].reference.hypotheses
        )
        views = project(account)
        assert views.model_dump(mode="json") == json.loads((root / "analysis.json").read_text())
        coverage = {r.source_unit: r.coverage for r in views.source}
        assert [coverage[r.id] for r in rows] == ["present", "present", "uncertain", "absent"]
        categories = {r.unit: r.category for r in views.recall}
        assert categories[units["external"]] == "external"
        assert categories[units["revisit"]] == "excluded"
        # Remove singleton/joint supported credit without changing reference, inventory or address.
        ablated = []
        for b in account.bindings:
            if b.reference.outcome == "resolved" and b.reference.hypotheses[0].kind in (
                "single",
                "joint",
            ):
                b = b.model_copy(
                    update={
                        "assessments": tuple(
                            a.model_copy(update={"grade": "contradicted"})
                            if a.grade == "supported"
                            else a
                            for a in b.assessments
                        )
                    }
                )
            ablated.append(b)
        without_definite = account.model_copy(update={"bindings": tuple(ablated)})
        ablated_views = project(without_definite)
        ablated_coverage = {r.source_unit: r.coverage for r in ablated_views.source}
        assert [ablated_coverage[r.id] for r in rows] == [
            "uncertain",
            "uncertain",
            "uncertain",
            "absent",
        ]
        assert ablated_views.recall == views.recall
        # Incomplete inventory withholds otherwise absent/uncertain rows; no plausible zero.
        incomplete = without_definite.model_copy(
            update={
                "inventories": (
                    without_definite.inventories[0].model_copy(
                        update={"status": "not-attempted", "reason": "control"}
                    ),
                    *without_definite.inventories[1:],
                )
            }
        )
        assert {r.coverage for r in project(incomplete).source} == {"not-assessable"}
        plan = validate_claim_plan((root / "claim-plan.json").read_bytes())
        receipt = verify_claim_assessment(
            plan,
            (root / "claim-reply.json").read_bytes(),
            (root / "claim-receipt.json").read_bytes(),
        )
        assert [d.status for d in receipt.decisions] == [
            "supported",
            "contradicted",
            "reference-unresolved",
        ]
        assert (
            receipt.total_questions,
            receipt.expressed_questions,
            receipt.resolved_questions,
        ) == (3, 3, 2)
        results[session] = (account, units)
    assert results["recall-a"][1]["second"] != results["recall-b"][1]["second"]
    for case in results["recall-a"][1]:
        a, b = workflow / "recall-a" / case, workflow / "recall-b" / case
        assert (a / "mapping-prompt.json").read_bytes() == (b / "mapping-prompt.json").read_bytes()
        assert (a / "fidelity-prompt.json").read_bytes() == (
            b / "fidelity-prompt.json"
        ).read_bytes()


def test_correspondence_replays_and_cli_analysis_match_saved_artifacts(workflow, tmp_path):
    for session in ("recall-a", "recall-b"):
        case = workflow / session / "ambiguous"
        mapped = replay_mapping(
            read_mapping_request(case / "mapping-request.json"), case / "mapping-recording"
        )
        assert mapped.model_dump(mode="json") == json.loads(
            (case / "mapping-result.json").read_text()
        )
        graded = replay_fidelity(
            read_fidelity_request(case / "fidelity-request.json"), case / "fidelity-recording"
        )
        assert graded.model_dump(mode="json") == json.loads(
            (case / "fidelity-result.json").read_text()
        )
        path = tmp_path / (session + ".json")
        assert (
            main(["analyze", str(workflow / session / "account.json"), "--output", str(path)]) == 0
        )
        assert json.loads(path.read_text()) == json.loads(
            (workflow / session / "analysis.json").read_text()
        )


def test_scene_cli_identity_remapping_schema_and_grant(workflow, tmp_path, capsys):
    source_result = json.loads((workflow / "source/result.json").read_text())
    source_account = tmp_path / "input.json"
    source_account.write_text(canonical(source_result["account"]))
    output = tmp_path / "grouped.json"
    assert (
        main(
            [
                "group-source-rows",
                str(source_account),
                str(workflow / "source/groups.json"),
                "--output",
                str(output),
            ]
        )
        == 0
    )
    assert json.loads(output.read_text()) == json.loads(
        (workflow / "source/grouped.json").read_text()
    )
    assert (
        main(
            [
                "group-source-rows",
                str(source_account),
                str(workflow / "source/groups.json"),
                "--account-only",
                "--output",
                str(tmp_path / "account.json"),
            ]
        )
        == 0
    )
    assert json.loads((tmp_path / "account.json").read_text()) == json.loads(
        (workflow / "source/account.json").read_text()
    )
    denied = source_result["account"]
    denied["export_permission"] = "denied"
    source_account.write_text(canonical(denied))
    assert (
        main(
            [
                "group-source-rows",
                str(source_account),
                str(tmp_path / "missing.json"),
                "--output",
                str(tmp_path / "no-output.json"),
            ]
        )
        != 0
    )
    assert "denies" in capsys.readouterr().err
    assert not (tmp_path / "no-output.json").exists()
    port = Path(__file__).resolve().parents[1]
    assert json.loads((port / "schemas/scenes.json").read_text()) == {
        "group": SceneGroup.model_json_schema(),
        "result": SceneGroupingResult.model_json_schema(),
    }
