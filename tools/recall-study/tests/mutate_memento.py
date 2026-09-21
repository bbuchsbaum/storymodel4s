#!/usr/bin/env python3
"""Named Memento falsifiers: a green baseline followed by assertion failure, never a crash."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parents[1]
TASK = HERE / "memento_task.py"
GUARD = HERE / "memento_guard.py"
XLSX = HERE.parent / "corpus/xlsx_rows.py"
# Source, unique site, replacement, named assertion court.
MUTANTS = [
    (
        TASK,
        "elif code not in (1, 2):",
        "elif False:",
        "test_invalid_and_nonaccurate_codes_never_enter_accuracy",
    ),
    (
        TASK,
        "reasons.append(Reason.MISSING_SCENE)",
        "pass",
        "test_sceneless_code2_is_accounted_without_becoming_error",
    ),
    (
        TASK,
        "reasons.append(Reason.INVALID_SCENE)",
        "pass",
        "test_invalid_second_scene_cannot_be_silently_discarded",
    ),
    (
        TASK,
        "or identity in outcomes:",
        "or False:",
        "test_duplicate_predictions_refuse_before_mapping",
    ),
    (
        TASK,
        "or set(outcomes) != set(ids):",
        "or not set(ids).issubset(outcomes):",
        "test_identity_and_prediction_support_refuse_omissions",
    ),
    (
        TASK,
        "sum(defined) / len(defined)",
        'sum(c["correct"] for c in by_participant.values()) / sum(c["eligible"] for c in by_participant.values())',
        "test_participant_equal_mean_and_zero_eligible_population",
    ),
    (
        TASK,
        'if digest(header) != rule["headerSha256"]:',
        "if False:",
        "test_header_overlay_is_exactly_bound_and_sensitivity_is_explicit",
    ),
    (
        GUARD,
        "if record.get(key) != expected[key]:",
        "if False:",
        "test_condition_and_membership_tampering_refused_even_when_committed",
    ),
    (
        GUARD,
        "def _require_unsealed():\n",
        "def _require_unsealed():\n    return\n",
        "test_sealing_forms_close_even_if_committed_split_deleted",
    ),
    (
        XLSX,
        'if ctype == "e" and indexed:',
        'if ctype == "e" and False:',
        "test_xlsx_errors_remain_invalid_populated_cells",
    ),
]


def run(test_file, suite, court, env):
    return subprocess.run(
        [sys.executable, str(test_file), f"{suite}.{court}", "-v"],
        env=env,
        text=True,
        capture_output=True,
        cwd=HERE,
    )


def main():
    results, failed = [], False
    for target, old, new, court in MUTANTS:
        guard = target == GUARD
        test_file = (
            HERE
            / "tests"
            / ("test_memento_guard.py" if guard else "test_memento_task.py")
        )
        suite = "MementoGuardSuite" if guard else "MementoTaskSuite"
        base = run(test_file, suite, court, dict(os.environ))
        if base.returncode:
            raise SystemExit(f"baseline red: {court}\n{base.stderr}")
        source = target.read_text()
        if source.count(old) != 1:
            raise SystemExit(f"mutation site is not unique: {old}")
        with tempfile.TemporaryDirectory(prefix="memento-mutant-") as directory:
            mutant = Path(directory) / target.name
            mutant.write_text(source.replace(old, new))
            env = dict(os.environ)
            env[
                {
                    TASK: "MEMENTO_TASK_MUTANT",
                    GUARD: "MEMENTO_GUARD_MUTANT",
                    XLSX: "MEMENTO_XLSX_MUTANT",
                }[target]
            ] = str(mutant)
            result = run(test_file, suite, court, env)
        killed = (
            result.returncode != 0
            and f"FAIL: {court}" in result.stderr
            and f"ERROR: {court}" not in result.stderr
        )
        results.append(
            {
                "source": target.name,
                "court": court,
                "exit": result.returncode,
                "killedByAssertion": killed,
            }
        )
        if not killed:
            failed = True
            print(result.stdout + result.stderr, file=sys.stderr)
    print(json.dumps(results, indent=2))
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
