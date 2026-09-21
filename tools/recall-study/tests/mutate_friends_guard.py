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
SHARED = HERE / "sealed_split.py"
SHARED_TEST = HERE / "tests/test_sealed_split.py"
# name, source, old, replacement, test module, named court
MUTANTS = [
    (
        "ignore-staged-only-changes",
        SHARED,
        'or _git("diff", "--cached", "--quiet", "HEAD", "--", rel.as_posix()).returncode\n        != 0',
        "or False",
        SHARED_TEST,
        "test_staged_changes_refused_when_worktree_matches_head",
    ),
    (
        "admit-test-without-opening",
        SHARED,
        "    if final_opening is None:\n        raise GuardRefusal(",
        "    if final_opening is None:\n        return requested\n        raise GuardRefusal(",
        SHARED_TEST,
        "test_development_passes_and_test_requires_opening",
    ),
    (
        "skip-manifest-digest",
        SHARED,
        "    if digest != final_opening.manifest_sha256:",
        "    if False:",
        SHARED_TEST,
        "test_manifest_digest_checked",
    ),
    (
        "uncounted-final-read",
        SHARED,
        "        _append(\n            path,",
        "        (lambda *args: None)(\n            path,",
        SHARED_TEST,
        "test_final_opening_recorded_before_grant_and_next_read_requires_commit",
    ),
    (
        "trust-skip-worktree",
        SHARED,
        "or path.read_bytes() != head.stdout",
        "or False",
        SHARED_TEST,
        "test_skip_worktree_does_not_hide_modified_split",
    ),
    (
        "accept-untracked-manifest",
        SHARED,
        '    if head.returncode != 0:\n        raise GuardRefusal(f"{rel}: not committed in HEAD")',
        "    if head.returncode != 0:\n        return path",
        SHARED_TEST,
        "test_untracked_manifest_refused",
    ),
    (
        "ignore-dirty-ledger",
        SHARED,
        "        path = _committed(corpus.ledger)",
        "        path = REPO / corpus.ledger",
        SHARED_TEST,
        "test_final_opening_recorded_before_grant_and_next_read_requires_commit",
    ),
    (
        "skip-ledger-lock",
        SHARED,
        "        fcntl.flock(lock, fcntl.LOCK_EX)",
        "        pass",
        SHARED_TEST,
        "test_ledger_checked_under_lock",
    ),
    (
        "uncounted-count-only-read",
        SHARED,
        "        record = _append(\n",
        '        record = (lambda *args: {"status": "started"})(\n',
        SHARED_TEST,
        "test_count_only_read_is_prelogged_and_returns_aggregate_totals",
    ),
    (
        "reopen-sealing-forms",
        GUARD,
        "def _require_unsealed():\n",
        "def _require_unsealed():\n    return\n",
        SHARED_TEST,
        "test_sealing_forms_refuse_existing_and_deleted_committed_seal_before_read",
    ),
    (
        "blank-merges-runs",
        GUARD,
        "        previous = value\n    return units",
        "        previous = value if value is not None else previous\n    return units",
        TEST,
        "test_unit_definition_breaks_runs_and_keeps_veridical_rows_only",
    ),
    (
        "scan-ignores-missing-call",
        TEST,
        "            if not guarded:",
        "            if False:",
        TEST,
        "test_bypass_scan_flags_an_unguarded_reader",
    ),
]


def run(script, test, env, suite):
    return subprocess.run(
        [sys.executable, str(script), f"{suite}.{test}", "-v"],
        env=env,
        text=True,
        capture_output=True,
        cwd=HERE,
    )


results, failed = [], False
for name, target, old, new, court, test in MUTANTS:
    suite = "SealedSplitSuite" if court == SHARED_TEST else "FriendsGuardSuite"
    base = run(court, test, dict(os.environ), suite)
    if base.returncode != 0:
        raise SystemExit(f"{name}: court {test} is red before mutation\n{base.stderr}")
    source = target.read_text()
    if source.count(old) != 1:
        raise SystemExit(f"{name}: mutation site count {source.count(old)}")
    with tempfile.TemporaryDirectory(prefix="friends-guard-mutant-") as d:
        mutant = Path(d) / target.name
        mutant.write_text(source.replace(old, new))
        env = dict(os.environ)
        if target in (GUARD, SHARED):
            env[
                "FRIENDS_GUARD_MUTANT" if target == GUARD else "SEALED_SPLIT_MUTANT"
            ] = str(mutant)
            outcome = run(court, test, env, suite)
        else:
            env["FRIENDS_GUARD_HERE"] = str(HERE)
            outcome = run(mutant, test, env, suite)
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
