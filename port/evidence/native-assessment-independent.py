"""Retained cold-review support oracle and adversarial consumers.

Run from port with PYTHONPATH=src:tests .venv/bin/python evidence/native-assessment-independent.py.
The reviewer originally used an invalid external destination in three cases. This
retained probe corrects it to `general` and adds positive controls, proving those
refusals reach native evidence admission. This is not an audiovisual accuracy test.
"""

import json
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory

from storymodel.analysis import project
from storymodel.direct_mapping import prepare_mapping, resolve_mapping
from storymodel.fidelity import (
    fidelity_prompt,
    fidelity_request_digest,
    prepare_fidelity,
    resolve_fidelity,
)
from storymodel.fidelity_workflow import FidelityRecording, import_fidelity, replay_fidelity
from storymodel.model import Assessment, Binding, Detail, Recipe, Reference
from storymodel.native_assessment import NativeCitation, native_citation_supports
from storymodel.playback import MediaSource
from storymodel.rich_account import account_schema, validate_rich_account
from storymodel.serialization import canonical, digest


def controls():
    # Reuse admitted abstract fixtures, not production acceptance predicates.
    from test_native_assessment import citation, fidelity_reply, fixture, mapped, mapping_reply
    from test_playback import source, support

    return citation, fidelity_reply, fixture, mapped, mapping_reply, source, support


citation, fidelity_reply, fixture, mapped, mapping_reply, media_source, support = controls()
source, recall, account = fixture()
mapped_account = mapped(account).account
request = prepare_fidelity(mapped_account, mapped_account.details[0].unit)
hypothesis = mapped_account.bindings[0].reference.hypotheses[0]
event = source.events[0]
counts = {}
cases = 0
for lo in range(31):
    candidate = NativeCitation(
        situation=event.id, support=support(source.media_sources[0], points=[lo])
    )
    expected = lo in {3, 4, 22, 23, 24}
    try:
        native_citation_supports(account, hypothesis, (candidate,))
        actual = True
    except ValueError:
        actual = False
    assert actual == expected, ("point", lo, actual)
    cases += 1
for lo in range(31):
    for hi in range(lo + 1, 32):
        candidate = NativeCitation(
            situation=event.id, support=support(source.media_sources[0], [(lo, hi)])
        )
        expected = (3 <= lo < hi <= 5) or (22 <= lo < hi <= 25)
        try:
            native_citation_supports(account, hypothesis, (candidate,))
            actual = True
        except ValueError:
            actual = False
        assert actual == expected, ("interval", lo, hi, actual)
        cases += 1
counts["support_oracle_cases"] = cases

for field, new in (
    ("edition", "B"),
    ("stream", "B"),
    ("clock", "B"),
    ("seconds_per_tick", "1/4"),
    ("sha256", "c" * 64),
):
    original = source.media_sources[0]
    changed = MediaSource.model_validate(
        original.model_copy(update={"id": "", "key": "other", field: new})
    )
    altered = validate_rich_account(
        mapped_account.model_copy(
            update={
                "media_sources": (*mapped_account.media_sources, changed),
                "recipe": Recipe(
                    name="probe", input_sha256=(*mapped_account.recipe.input_sha256, changed.sha256)
                ),
            }
        )
    )
    selected = prepare_fidelity(altered, altered.details[0].unit)
    cite = citation(account)
    cite["support"]["source"] = changed.id
    result = resolve_fidelity(selected, fidelity_reply(selected, [cite]))
    assert result.decisions[0].status == "rejected"
    assert result.account.bindings == altered.bindings
counts["registered_foreign_sources"] = 5

detail = mapped_account.details[0]
other = Detail(
    unit=detail.unit,
    kind="mental",
    content="independent controlled claim",
    support=detail.support,
    address="target",
)
for status in ("completed", "unavailable", "refused"):
    fixed = Assessment(
        hypothesis=hypothesis.id,
        status=status,
        grade="supported" if status == "completed" else None,
        reason="protected",
        evidence=(event.evidence[0],) if status == "completed" else (),
    )
    protected = Binding(
        detail=other.id, reference=mapped_account.bindings[0].reference, assessments=(fixed,)
    )
    narrative = Binding(
        detail=detail.id,
        reference=Reference(
            status="attempted",
            interpretation="narrative",
            outcome="resolved",
            hypotheses=(hypothesis,),
            reason="narrative",
        ),
        assessments=(fixed,),
    )
    altered = validate_rich_account(
        mapped_account.model_copy(
            update={
                "details": (*mapped_account.details, other),
                "bindings": (*mapped_account.bindings, protected, narrative),
            }
        )
    )
    selected = prepare_fidelity(altered, detail.unit)
    result = resolve_fidelity(selected, fidelity_reply(selected, [citation(account)]))
    assert result.account.bindings[1:] == altered.bindings[1:]
    assert result.account.bindings[0].assessments[0].grade == "supported"
    assert fidelity_prompt(selected) == fidelity_prompt(request)
    assert fidelity_request_digest(selected) != fidelity_request_digest(request)
counts["protected_slot_states"] = 3

for version in ("v6", "v7"):
    for status, grade in (
        ("pending", None),
        ("unavailable", None),
        ("refused", None),
        ("completed", "unverifiable"),
        ("completed", "supported"),
    ):
        assessment = Assessment(
            hypothesis=hypothesis.id,
            status=status,
            grade=grade,
            reason="probe",
            evidence=(event.evidence[0],),
        )
        binding = mapped_account.bindings[0].model_copy(update={"assessments": (assessment,)})
        raw = mapped_account.model_dump(mode="json") | {
            "schema_version": "storymodel.account/" + version,
            "bindings": [binding.model_dump(mode="json")],
        }
        if version == "v6":
            raw.pop("temporal_relations", None)
        try:
            validate_rich_account(canonical(raw))
        except ValueError as error:
            assert "playback assessment evidence requires account v8" in str(error)
        else:
            raise AssertionError((version, status))
counts["old_version_status_cases"] = 10

for status, grade in (
    ("unavailable", None),
    ("completed", "unverifiable"),
    ("completed", "external"),
):
    selected = prepare_mapping(account, detail.unit)
    raw = json.loads(mapping_reply(selected))
    entry = raw["details"][0]
    entry["hypotheses"] = [{"key": "h0", "kind": "external", "external": "general"}]
    entry["assessments"] = [
        {
            "hypothesis": "h0",
            "status": status,
            "grade": grade,
            "reason": "probe",
            "playback_evidence": [citation(account)],
        }
    ]
    result = resolve_mapping(selected, canonical(raw))
    assert result.decisions[0].status == "rejected"
    assert (
        "native cited situation extends outside its source hypothesis" in result.decisions[0].reason
    )
    entry["assessments"][0]["playback_evidence"] = []
    result = resolve_mapping(selected, canonical(raw))
    assert result.decisions[0].status == "admitted"
counts["external_citation_refusals"] = 3
counts["external_positive_controls"] = 3

reply = fidelity_reply(request, [citation(account)]) + "\r\n"
with TemporaryDirectory(prefix="native-independent-") as tmp:
    root = Path(tmp) / "recording"
    import_fidelity(
        request,
        FidelityRecording(
            request_sha256=fidelity_request_digest(request), origin="independent probe", reply=reply
        ),
        root,
    )
    result = replay_fidelity(request, root)
    assert result.decisions[0].status == "admitted"
    altered = validate_rich_account(
        mapped_account.model_copy(
            update={
                "recipe": Recipe(
                    name="different-provenance", input_sha256=mapped_account.recipe.input_sha256
                )
            }
        )
    )
    selected = prepare_fidelity(altered, detail.unit)
    try:
        replay_fidelity(selected, root)
    except ValueError as error:
        assert "another request" in str(error)
    else:
        raise AssertionError("replay did not bind private recipe")
    denied = validate_rich_account(
        mapped_account.model_copy(update={"export_permission": "denied"})
    )
    try:
        replay_fidelity(prepare_fidelity(denied, detail.unit), Path(tmp) / "missing")
    except PermissionError:
        pass
    else:
        raise AssertionError("denied export read recording")
    hidden = media_source(key="hidden", side="recall")
    hybrid = validate_rich_account(
        mapped_account.model_copy(
            update={
                "media_sources": (*mapped_account.media_sources, hidden),
                "recipe": Recipe(
                    name="hybrid", input_sha256=(*mapped_account.recipe.input_sha256, hidden.sha256)
                ),
            }
        )
    )
    try:
        prepare_fidelity(hybrid, detail.unit)
    except ValueError as error:
        assert "source-only media" in str(error)
    else:
        raise AssertionError("hidden recall media admitted")
counts["workflow_checks"] = 4

# The original review's conditional schema comparison did not execute. This one does.
base = json.loads(
    subprocess.check_output(["git", "show", "3e06be43e:port/schemas/rich-account.json"])
)["account"]
base["properties"]["schema_version"] = {
    "const": "storymodel.account/v7",
    "default": "storymodel.account/v7",
    "title": "Schema Version",
    "type": "string",
}
assert base == account_schema("v7")
counts["frozen_schema_comparison"] = 1
assert project(mapped_account).source[0].coverage == "not-assessable"
record = {
    "reviewed_sha": "8fdd57013a050454722b710835c2063b600c27ec",
    "checks": counts,
    "total": sum(counts.values()),
    "frozen_v7_digest": digest(account_schema("v7")),
    "result": "pass",
    "retention_changes": (
        "Corrected external destination, added external positive controls, "
        "made skipped schema comparison unconditional."
    ),
}
Path("data/native-assessment-independent.json").write_text(json.dumps(record, indent=2) + "\n")
print(json.dumps(record, indent=2))
