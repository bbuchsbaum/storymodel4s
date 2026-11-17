import json
import os
import shutil
import subprocess
from pathlib import Path

base = Path(__file__).resolve().parents[1]
root = base / "data/playback-mutations"
root.mkdir(exist_ok=True)
cases = [
    (
        "nested-cue",
        "playback_construction.py",
        "not contains_playback(context_proposal.cue, enclosing)",
        "False",
        "test_playback_construction.py::test_nested_context_cue_must_belong_to_parent_scope[29-rejected]",
    ),
    (
        "organization-modality",
        "organization.py",
        "value = require_text_account(value)",
        "from .rich_account import validate_supported_account\n"
        "    value = validate_supported_account(value)",
        "test_playback_construction.py::test_numerical_consumers_refuse_native_side_hidden_by_text_documents[organization-recall]",
    ),
    (
        "reconstruction-modality",
        "reconstruction.py",
        "checked = require_text_account(account)",
        "from .rich_account import validate_supported_account\n"
        "    checked = validate_supported_account(account)",
        "test_playback_construction.py::test_numerical_consumers_refuse_native_side_hidden_by_text_documents[reconstruction-source]",
    ),
    (
        "drop-points",
        "playback.py",
        "points=tuple(p for p in checked.points if lo <= tick(p) < hi),",
        "points=(),",
        "test_playback.py::test_projection_measures_union_not_hull_and_never_gives_points_duration",
    ),
    (
        "hull-duration",
        "playback.py",
        "sum(tick(i.end) - tick(i.start) for i in checked.intervals) * timebase",
        "(tick(checked.intervals[-1].end) - tick(checked.intervals[0].start)) * timebase "
        "if checked.intervals else Fraction(0)",
        "test_playback.py::test_projection_measures_union_not_hull_and_never_gives_points_duration",
    ),
    (
        "closed-point-end",
        "playback.py",
        "not start <= tick(p) < end",
        "not start <= tick(p) <= end",
        "test_playback.py::test_annotation_timeline_and_half_open_extent_are_not_silent_media_evidence",
    ),
    (
        "root-promotion",
        "playback_construction.py",
        "if situation_proposal.context != deepest:",
        "if False and situation_proposal.context != deepest:",
        "test_playback_construction.py::test_invalid_native_claims_are_row_atomic[root]",
    ),
    (
        "request-binding",
        "playback_construction.py",
        'or supplied["request_sha256"] != request_sha',
        "or False",
        "test_playback_construction.py::test_rejected_observation_does_not_erase_valid_neighbor_or_provenance_binding",
    ),
    (
        "historical-field",
        "rich_account.py",
        'if self.schema_version != "storymodel.account/v6" and (',
        'if False and self.schema_version != "storymodel.account/v6" and (',
        "test_playback_construction.py::test_old_rich_versions_refuse_explicit_media_field_even_when_empty[v4]",
    ),
]
# Root promotion has a second crossing guard. Remove both independent admission checks
# in that mutant to model dropping scoped-placement enforcement altogether.
records = []
for name, file, old, new, test in cases:
    case = root / name
    case.mkdir(exist_ok=True)
    shutil.copytree(
        base / "src/storymodel",
        case / "storymodel",
        dirs_exist_ok=True,
        ignore=shutil.ignore_patterns("__pycache__"),
    )
    for fn in [
        "test_playback.py",
        "test_playback_construction.py",
        "test_attribution.py",
        "test_identity.py",
        "test_rich_alignment.py",
        "test_alignment.py",
        "test_organization.py",
    ]:
        shutil.copy2(base / "tests" / fn, case / fn)
    controls = [
        "test_playback.py::test_union_retains_gaps_and_explicit_points_inside_intervals",
        "test_playback.py::test_public_projection_and_containment_refuse_typed_forgeries",
    ]
    command = [str(base / ".venv/bin/python"), "-m", "pytest", "-q", test, *controls]
    env = dict(os.environ, PYTHONPATH=str(case), PYTHONDONTWRITEBYTECODE="1")
    record = dict(mutation=name, file=file, old=old, new=new, test=test, controls=controls)
    for mode in ["baseline", "mutant"]:
        if mode == "mutant":
            path = case / "storymodel" / file
            s = path.read_text()
            assert s.count(old) == 1, (name, s.count(old))
            s = s.replace(old, new)
            if name == "root-promotion":
                crossing = "if _overlap(situation_proposal.evidence, c.scope) and key not in ("
                assert s.count(crossing) == 1
                s = s.replace(
                    crossing,
                    "if False and _overlap(situation_proposal.evidence, c.scope) and key not in (",
                )
                record["additional_replacement"] = [
                    crossing,
                    "if False and _overlap(situation_proposal.evidence, c.scope) and key not in (",
                ]
            path.write_text(s)
            subprocess.run(
                [str(base / ".venv/bin/python"), "-m", "py_compile", str(path)], check=True
            )
            shutil.rmtree(case / "storymodel/__pycache__", ignore_errors=True)
        p = subprocess.run(command, cwd=case, env=env, capture_output=True, text=True)
        (case / f"{mode}.log").write_text(p.stdout + p.stderr)
        assert p.returncode == (0 if mode == "baseline" else 1), (name, mode, p.stdout, p.stderr)
        assert ("3 passed" if mode == "baseline" else "1 failed, 2 passed") in p.stdout, (
            name,
            mode,
            p.stdout,
        )
        record[mode] = p.stdout.splitlines()[-1]
    record["compiled"] = True
    records.append(record)
    print(name, record["mutant"], flush=True)
(root / "summary.json").write_text(json.dumps(records, indent=2) + "\n")
