#!/usr/bin/env python3
"""Named Friends-guard mutants: each must turn its named court red by assertion, not by crash.

Every named court is first run unmutated and must pass; a mutant counts as killed only when that
court then reports FAIL (an assertion), never ERROR. Guard mutants are injected through
FRIENDS_GUARD_MUTANT so the court keeps its real repository context; the scanner lives in the test
file, so its mutant runs a mutated copy of the test with FRIENDS_GUARD_HERE pointing home.
"""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parents[1]
GUARD = HERE / "friends_guard.py"
TEST = HERE / "tests/test_friends_guard.py"
MUTANTS = [
    (
        "admit-test-without-opening",
        GUARD,
        "    if final_opening is None:\n        raise GuardRefusal(",
        "    if final_opening is None:\n        return requested\n        raise GuardRefusal(",
        "test_test_participant_refused_without_final_opening",
    ),
    (
        "skip-manifest-digest",
        GUARD,
        "    if actual != opening.manifest_sha256:",
        "    if False:",
        "test_final_opening_verifies_manifest_digest_and_counts_the_read",
    ),
    (
        "uncounted-final-read",
        GUARD,
        "    _append_read(\n        ledger_path,",
        "    (lambda *_: None)(\n        ledger_path,",
        "test_final_opening_verifies_manifest_digest_and_counts_the_read",
    ),
    (
        "blank-merges-runs",
        GUARD,
        "        previous = value\n    return units",
        "        previous = value if value is not None else previous\n    return units",
        "test_unit_definition_breaks_runs_and_keeps_veridical_rows_only",
    ),
    (
        "scan-ignores-missing-import",
        TEST,
        "            if not re.search(",
        "            if False and not re.search(",
        "test_bypass_scan_flags_an_unguarded_reader",
    ),
]


def run(script, test, env):
    return subprocess.run(
        [sys.executable, str(script), f"FriendsGuardSuite.{test}", "-v"],
        env=env,
        text=True,
        capture_output=True,
        cwd=HERE,
    )


results, failed = [], False
for name, target, old, new, test in MUTANTS:
    base = run(TEST, test, dict(os.environ))
    if base.returncode != 0:
        raise SystemExit(f"{name}: court {test} is red before mutation\n{base.stderr}")
    source = target.read_text()
    if source.count(old) != 1:
        raise SystemExit(f"{name}: mutation site count {source.count(old)}")
    with tempfile.TemporaryDirectory(prefix="friends-guard-mutant-") as d:
        mutant = Path(d) / target.name
        mutant.write_text(source.replace(old, new))
        env = dict(os.environ)
        if target == GUARD:
            env["FRIENDS_GUARD_MUTANT"] = str(mutant)
            outcome = run(TEST, test, env)
        else:
            env["FRIENDS_GUARD_HERE"] = str(HERE)
            outcome = run(mutant, test, env)
    killed = outcome.returncode != 0 and f"FAIL: {test}" in outcome.stderr
    errored = f"ERROR: {test}" in outcome.stderr
    results.append(
        {
            "mutant": name,
            "court": test,
            "exit": outcome.returncode,
            "killedByAssertion": killed,
        }
    )
    if not killed or errored:
        failed = True
        print(outcome.stdout + outcome.stderr, file=sys.stderr)
print(json.dumps(results, indent=2))
sys.exit(1 if failed else 0)
