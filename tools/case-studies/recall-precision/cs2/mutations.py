"""Mutation runs for the case-study-2 invariants: remove each guard, record which named test fails.

Each mutant is applied to a fresh copy of the harness in a scratch directory (never to the
working tree), then `python -m cs2.test_cs2 --json` runs there. A mutant counts when it imports
(the suite runs) and its named test fails; the full failure list is recorded so sibling failures
are visible.

Usage (from tools/case-studies/recall-precision):
  python -m cs2.mutations SCRATCH_DIR OUT_JSON
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]  # tools/case-studies/recall-precision
ROOT = HERE.parents[2]

MUTANTS = [
    dict(
        id="M1",
        guard="the declared remainder goes to 'unassigned'",
        file="cs2/projections.py",
        edits=[
            (
                '    if ref.unassigned > 0:\n        out.append((("unassigned",), ref.unassigned))\n',
                "",
            )
        ],
        test="test_row_sums_to_one",
    ),
    dict(
        id="M1b",
        guard="remainder AND the row-sum check removed (the test's own assertion must catch it)",
        file="cs2/projections.py",
        edits=[
            (
                '    if ref.unassigned > 0:\n        out.append((("unassigned",), ref.unassigned))\n',
                "",
            ),
            (
                '    if abs(total - 1) > ROW_TOL:\n        raise ProjectionError("row-sum", repr(total))\n',
                "",
            ),
        ],
        test="test_row_sums_to_one",
    ),
    dict(
        id="M2",
        guard="a joint hypothesis is refused by location_contribution",
        file="cs2/projections.py",
        edits=[
            (
                "    if target.shape is Shape.JOINT:\n        raise ProjectionError(\n"
                '            "joint-refused", "a joint reference cannot enter a distribution"\n'
                "        )\n",
                "",
            )
        ],
        test="test_joint_refused_from_distribution",
    ),
    dict(
        id="M3",
        guard="side states are carried unchanged through coarsening",
        file="cs2/projections.py",
        edits=[
            (
                '                nk = ("at", grain, f(key[2])) if is_film(key) else key\n',
                "                if not is_film(key):\n                    continue\n"
                '                nk = ("at", grain, f(key[2]))\n',
            )
        ],
        test="test_coarsening_commutes_side_states_unchanged",
    ),
    dict(
        id="M3b",
        guard="unit -> occurrence coarsening uses the unit's occurrence (not its part)",
        file="cs2/projections.py",
        edits=[
            (
                "            return lambda u: comp.unit[u].occurrence\n",
                "            return lambda u: comp.unit[u].part\n",
            )
        ],
        test="test_coarsening_commutes_side_states_unchanged",
    ),
    dict(
        id="M4",
        guard="a unit on a part that does not hold its occurrence is refused",
        file="cs2/source.py",
        edits=[
            (
                "            if seg is None:\n                raise SourceError(\n"
                '                    f"unit {u.id} lies on part {u.part}, which does not hold its '
                'occurrence"\n                )\n',
                "            if seg is None:\n                seg = self.occurrence[u.occurrence].segments[0]\n",
            )
        ],
        test="test_work_mass_only_on_its_parts",
    ),
    dict(
        id="M5",
        guard="bin masses are exact sums (math.fsum), independent of sample order",
        file="cs2/projections.py",
        edits=[
            (
                "            mass = {key: math.fsum(vs) for key, vs in sorted(",
                '            mass = {key: __import__("functools").reduce(lambda x, y: x + y, vs) '
                "for key, vs in sorted(",
            )
        ],
        test="test_relabel_and_reorder_invariance",
    ),
    dict(
        id="M6",
        guard="a weight sum above 1 is refused",
        file="cs2/account.py",
        edits=[
            (
                '    if total > 1 + WEIGHT_TOL:\n        raise AccountError("weight-sum-above-1", f"{what}: {total!r}")\n',
                "",
            )
        ],
        test="test_weight_sum_above_one_refused",
    ),
    dict(
        id="M7",
        guard="each mass entry records the samples that put mass there",
        file="cs2/projections.py",
        edits=[("                    trace[key].add(s)\n", "")],
        test="test_every_row_traces_to_a_reference",
    ),
    dict(
        id="M8",
        guard="not-applicable words count as samples in their bin",
        file="cs2/projections.py",
        edits=[
            (
                "    inside = [c for c in cells if b.contains(c.onset)]\n",
                "    inside = [c for c in cells if b.contains(c.onset) and not isinstance(c.sample, StateSample)]\n",
            )
        ],
        test="test_not_applicable_word_stays_in_denominators",
    ),
    dict(
        id="M15",
        guard="uncovered words are never samples (old rule: one unassigned sample per unit)",
        file="cs2/projections.py",
        edits=[
            ("    samples = {c.sample for c in inside if not isinstance(c.sample, Uncovered)}\n",
             "    samples = {c.sample for c in inside}\n"),
            ('        raise ProjectionError("uncovered-is-not-a-sample", "uncovered words are ledger counts")\n',
             '        return [(("unassigned",), 1.0)]\n'),
        ],
        test="test_uncovered_words_never_create_unassigned_mass",
    ),
    dict(
        id="M9",
        guard="unassigned mass is redistributed by the fallback forecast",
        file="cs2/scorer.py",
        edits=[
            (
                "        return (1 - self.eps) * (own + self.spread.get(w, 0.0) * f_set) + self.u * (\n"
                "            self.fallback.p_work(w) * f_set\n        )\n",
                "        return (1 - self.eps) * (own + self.spread.get(w, 0.0) * f_set) + self.u * (\n"
                "            self.fallback.p_work(w) * len(scenes) / max(1, len(fs))\n        )\n",
            )
        ],
        test="test_abstain_everything_scores_the_baseline",
    ),
    dict(
        id="M10",
        guard="the scored participant is left out of its own LOPO forecast",
        file="cs2/baselines.py",
        edits=[
            (
                "        if p == participant:\n"
                "            continue  # the scored participant never enters its own forecast\n",
                "",
            )
        ],
        test="test_lopo_excludes_scored_participant",
    ),
    dict(
        id="M11",
        guard="frozen FilmFestival participants are refused before any read",
        file="adapters/filmfestival.py",
        edits=[
            (
                '    if participant in part["frozen"] and not allow_frozen:\n',
                "    if False:\n",
            )
        ],
        test="test_frozen_participants_refused",
    ),
    dict(
        id="M12",
        guard="metrics the gold cannot validate are refused",
        file="cs2/scorer.py",
        edits=[("    if name in REFUSED:\n", "    if False:\n")],
        test="test_unvalidatable_metrics_refused",
    ),
    dict(
        id="M13",
        guard="work term is n/a (not computed) when work identity is not applicable",
        file="cs2/scorer.py",
        edits=[
            (
                "            if work_applicable:\n                rs.work_bits",
                "            if True:\n                rs.work_bits",
            )
        ],
        test="test_hierarchical_log_loss_known_answer",
    ),
    dict(
        id="M14",
        guard="bootstrap clusters are participants (sessions pooled)",
        file="cs2/scorer.py",
        edits=[
            (
                "            by.setdefault(s.participant, []).append(v)\n",
                "            by.setdefault((s.participant, s.session), []).append(v)\n",
            )
        ],
        test="test_bootstrap_clusters_are_participants",
    ),
]


def copy_tree(dst: Path):
    if dst.exists():
        shutil.rmtree(dst)
    rp = dst / "tools" / "case-studies" / "recall-precision"
    rp.mkdir(parents=True)
    for name in ("cs2", "adapters"):
        shutil.copytree(
            HERE / name, rp / name, ignore=shutil.ignore_patterns("__pycache__")
        )
    for name in ("metrics.py", "data.py"):
        shutil.copy2(HERE / name, rp / name)
    (dst / "tools" / "sherlock_mapper").symlink_to(ROOT / "tools" / "sherlock_mapper")
    return rp


def run_suite(rp: Path):
    p = subprocess.run(
        [sys.executable, "-m", "cs2.test_cs2", "--json"],
        cwd=rp,
        capture_output=True,
        text=True,
        timeout=600,
    )
    try:
        return json.loads(p.stdout.strip().splitlines()[-1])
    except (IndexError, json.JSONDecodeError):
        return dict(ran=0, failed=None, error=p.stderr[-2000:])


def main(argv):
    scratch, out = Path(argv[0]), Path(argv[1])
    rp = copy_tree(scratch / "clean")
    clean = run_suite(rp)
    sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=HERE, capture_output=True,
                         text=True).stdout.strip()
    dirty = subprocess.run(["git", "status", "--porcelain", "--untracked-files=no", "--", "."],
                           cwd=HERE, capture_output=True, text=True).stdout.strip()
    results = dict(commit=sha, harness_dirty=bool(dirty), clean=clean, mutants=[])
    for m in MUTANTS:
        rp = copy_tree(scratch / m["id"])
        path = rp / m["file"]
        text = path.read_text()
        for old, new in m["edits"]:
            n = text.count(old)
            if n != 1:
                raise SystemExit(
                    f"{m['id']}: expected one match in {m['file']}, found {n}"
                )
            text = text.replace(old, new)
        path.write_text(text)
        r = run_suite(rp)
        failed = r.get("failed")
        results["mutants"].append(
            dict(
                id=m["id"],
                guard=m["guard"],
                file=m["file"],
                named_test=m["test"],
                compiled=failed is not None and r.get("ran", 0) > 0,
                named_test_failed=bool(failed) and m["test"] in failed,
                failed=failed,
                error=r.get("error"),
            )
        )
        shutil.rmtree(scratch / m["id"])
        print(
            m["id"],
            "named test failed"
            if results["mutants"][-1]["named_test_failed"]
            else "NOT CAUGHT",
            failed,
        )
    shutil.rmtree(scratch / "clean")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(results, indent=1) + "\n")
    ok = clean.get("failed") == [] and all(
        x["named_test_failed"] for x in results["mutants"]
    )
    print("clean suite:", clean, "| all mutants caught:", ok)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
