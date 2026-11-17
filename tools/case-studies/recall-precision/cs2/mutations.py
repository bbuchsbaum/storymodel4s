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
        test="test_unit_on_foreign_part_refused",
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
            ("    samples = {c.sample for c in inside if not isinstance(c.sample, (Uncovered, TalkSample))}\n",
             "    samples = {c.sample for c in inside if not isinstance(c.sample, TalkSample)}\n"),
            ('        raise ProjectionError("not-a-sample", "uncovered and non-addressed words are ledger counts")\n',
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
                "        return (1 - self.eps) * (own + self.spread.get(w, 0.0) * f_set + set_part) + self.u * (\n"
                "            self.fallback.p_work(w) * f_set\n        )\n",
                "        return (1 - self.eps) * (own + self.spread.get(w, 0.0) * f_set + set_part) + self.u * (\n"
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
    dict(
        id="M23",
        guard="a broad reference is located at the smallest grain holding its span (ADR 0030)",
        file="cs2/projections.py",
        edits=[(
            "    if target.shape is Shape.BROAD:\n        case = broad_case(target, comp, broad_rule)\n",
            "    if target.shape is Shape.BROAD:\n"
            "        return [((\"held_out\", \"broad\", w), f) for w, f in _work_split(target.support, comp)]\n"
            "        case = broad_case(target, comp, broad_rule)\n",
        )],
        test="test_broad_within_one_scene_is_located_at_the_scene",
    ),
    dict(
        id="M24",
        guard="pass-2 batches number at least window + 1, so no context chains across items",
        file="cs2/ff_producer.py",
        edits=[("    nb = max(math.ceil(len(items) / batch), window + 1)  # empty batches are dropped below\n",
                "    nb = math.ceil(len(items) / batch)\n")],
        test="test_batches_hold_no_window_neighbours",
    ),
    dict(
        id="M25",
        guard="members with rows outside the framed film are refused",
        file="cs2/ff_producer.py",
        edits=[("        if any(comp.work_of_unit(u) != frame_work for c in rows for u in c):\n",
                "        if False:\n")],
        test="test_members_shapes_and_frame_check",
    ),
    dict(
        id="M26",
        guard="'<ordinal> to last' counts from the end",
        file="cs2/ff_producer.py",
        edits=[('    m = re.search(r"\\b(" + "|".join(ORDINALS) + r") (?:to|from the) last\\b", s)\n',
                "    m = None\n")],
        test="test_resolve_position_phrases",
    ),
    dict(
        id="M27",
        guard="a library unit span must start and end on word boundaries",
        file="cs2/ff_producer.py",
        edits=[("        if a not in by_start or b not in by_end:\n", "        if False:\n")],
        test="test_library_units_map_spans_to_words",
    ),
    dict(
        id="M28",
        guard="a gold row's end must be its utterance end minus 1.0 s (alignment refused otherwise)",
        file="adapters/filmfestival.py",
        edits=[('            raise SourceError("a gold end is not its utterance end minus 1.0 s")\n',
                "            pass\n")],
        test="test_gold_bins_are_utterance_spans",
    ),
    dict(
        id="M29",
        guard="small pools still use at least window + 1 batches",
        file="cs2/ff_producer.py",
        edits=[("    nb = max(math.ceil(len(items) / batch), window + 1)  # empty batches are dropped below\n",
                "    nb = min(max(math.ceil(len(items) / batch), window + 1), len(items))\n")],
        test="test_small_batches_respect_the_window",
    ),
    dict(
        id="M32",
        guard="gold alignment refuses non-monotone utterance onsets",
        file="adapters/filmfestival.py",
        edits=[("    if any(b < a for a, b in zip(onsets, onsets[1:])):\n",
                "    if False:\n")],
        test="test_gold_alignment_refuses_non_monotone_onsets",
    ),
    dict(
        id="M33",
        guard="rule fallback 'frame-film': unassigned mass goes to the row's frame film first",
        file="cs2/baselines.py",
        edits=[("    if not mix:\n        return dur\n", "    return dur\n")],
        test="test_frame_fallback_spreads_unassigned_over_the_frame_film",
    ),
    dict(
        id="M34",
        guard="rule talk 'orthogonal': memory search (even framed) is non-addressed talk",
        file="cs2/ff_producer.py",
        edits=[('            elif kind == "memory_search" and top is not None:\n',
                '            elif kind == "memory_search" and False:\n')],
        test="test_talk_is_orthogonal_and_non_addressed_talk_has_no_mass",
    ),
    dict(
        id="M35",
        guard="rule cross_scene_broad 'scene-set': a multi-scene span goes to its scene set",
        file="cs2/projections.py",
        edits=[('            return [(("scene_set", w, scenes), 1.0)]\n',
                '            return [(("work_node", "broad", w), 1.0)]\n')],
        test="test_broad_across_scenes_goes_to_its_scene_set",
    ),
    dict(
        id="M36",
        guard="rule film_level_baseline 'lopo': the scored participant is left out of the rate",
        file="cs2/baselines.py",
        edits=[("        if p == participant:\n            continue\n        for r in rows:\n            n += 1\n",
                "        for r in rows:\n            n += 1\n")],
        test="test_lopo_nonfilm_rate_excludes_scored_participant",
    ),
    dict(
        id="M37",
        guard="the HMM baseline applies its fitted stay and next transitions",
        file="cs2/baselines.py",
        edits=[("            alpha = ps * alpha + pn * shifted + pj * (1 - alpha) / (n - 1)\n",
                "            alpha = (1 - alpha) / (n - 1)\n")],
        test="test_hmm_tfidf_filters_on_recall_words",
    ),
    dict(
        id="M38",
        guard="the fitted gold-context forecast applies its stay rate",
        file="cs2/baselines.py",
        edits=[('            work[prev.work] += rates["stay"]\n', "            work[prev.work] += 0.0\n")],
        test="test_gold_context_fitted_known_answer",
    ),
    dict(
        id="M39",
        guard="correction: unframed memory search and giving up are non-film states",
        file="cs2/ff_producer.py",
        edits=[('            elif kind in ("memory_search", "gave_up", "off_task"):\n                # correction',
                '            elif kind in ("off_task",):\n                # correction')],
        test="test_non_film_states_are_destinations_not_talk",
    ),
    dict(
        id="M40",
        guard="frame weights summing above 1 are normalised",
        file="cs2/baselines.py",
        edits=[("    if total > 1:  # declared: frame weights summing above 1 are normalised to sum to 1\n",
                "    if False:\n")],
        test="test_frame_weights_above_one_are_normalised",
    ),
    dict(
        id="M41",
        guard="a title-only scene set spreads as the work node",
        file="cs2/scorer.py",
        edits=[("                             if fq > 0 else f_set)\n",
                "                             if fq > 0 else 0.0)\n")],
        test="test_title_only_scene_set_is_the_work_node",
    ),
    dict(
        id="M42",
        guard="film-grouped batches keep the no-chaining window rule",
        file="cs2/ff_producer.py",
        edits=[('                            batch, window)\n', '                            batch, 0)\n')],
        test="test_film_batches_share_a_film_and_keep_the_window_rule",
    ),
    dict(
        id="M43",
        guard="a span [a, b] covers word cells a..b inclusive",
        file="cs2/ff_producer.py",
        edits=[("                            covered_w = wids[a_:b_ + 1]\n",
                "                            covered_w = wids[a_:b_]\n")],
        test="test_span_anchors_convert_to_word_cells",
    ),
    dict(
        id="M44",
        guard="pass 1 v2: searching for the next film has an empty topic frame",
        file="cs2/ff_producer.py",
        edits=[("            fr = []  # declared: searching for the next film has an empty topic frame\n",
                "            pass\n")],
        test="test_pass1_v2_talk_frame_and_address",
    ),
    dict(
        id="M45",
        guard="composition remarks are film-level mass over all works",
        file="cs2/scorer.py",
        edits=[("        claimed += self.composition * self.fallback.p_work(w)\n", "")],
        test="test_composition_spreads_over_all_works",
    ),
    dict(
        id="M46",
        guard="mismatch rule incoming-frame gives the unit the next film's frame",
        file="cs2/ff_producer.py",
        edits=[("                framing[(p, s, uid)] = tuple(nxt)\n", "                pass\n")],
        test="test_frame_mismatch_switch_rules",
    ),
    dict(
        id="M47",
        guard="switch timing: a predicted switch before the gold one is early",
        file="cs2/scorer.py",
        edits=[('                    tol["early"] = tol.get("early", 0) + (i < hit)\n',
                '                    tol["early"] = tol.get("early", 0) + (i > hit)\n')],
        test="test_switch_timing_early_and_late",
    ),
    dict(
        id="M48",
        guard="a 'recall' position reading resolves in the speaker's recall order",
        file="cs2/ff_producer.py",
        edits=[('                order = recall_order if reading == "recall" else None\n',
                "                order = None\n")],
        test="test_pass1_v2_talk_frame_and_address",
    ),
    dict(
        id="M49",
        guard="the frame fallback keeps a duration floor on every work",
        file="cs2/baselines.py",
        edits=[("    work = {w: (1 - floor) * (mix.get(w, 0.0) + rest * p) + floor * p\n",
                "    work = {w: (mix.get(w, 0.0) + rest * p)\n")],
        test="test_frame_fallback_floor_keeps_every_work_positive",
    ),
    dict(
        id="M16",
        guard="the part marginal places a unit's mass on the unit's own part",
        file="cs2/projections.py",
        edits=[("            out[(comp.work_of_unit(u.id), u.part)] += v\n",
                "            out[(comp.work_of_unit(u.id), comp.occurrence[u.occurrence].segments[0].part)] += v\n")],
        test="test_work_mass_only_on_its_parts",
    ),
    dict(
        id="M17",
        guard="the film term charges non-film and external mass",
        file="cs2/scorer.py",
        edits=[("        return (1 - self.eps) * self.nonwork + self.u * self.fallback.p_nonfilm()\n",
                "        return self.u * self.fallback.p_nonfilm()\n")],
        test="test_film_term_closes_nonfilm_hedge",
    ),
    dict(
        id="M18",
        guard="the order oracle splits between the previous top scene and the next one",
        file="cs2/baselines.py",
        edits=[("                nxt = ss[i + 1] if i + 1 < len(ss) else prev_top\n",
                "                nxt = prev_top\n")],
        test="test_order_oracle_known_answer",
    ),
    dict(
        id="M19",
        guard="TF-IDF spreads within a work by cosine",
        file="cs2/baselines.py",
        edits=[("                scene[w] = {s: cos.get(s, 0.0) / m for s in sw}\n",
                "                scene[w] = {s: 1 / len(sw) for s in sw}\n")],
        test="test_tfidf_forecast_known_answer",
    ),
    dict(
        id="M20",
        guard="a title-only gold scene is dropped from the scored set",
        file="cs2/scorer.py",
        edits=[("                gs = frozenset(s for s in g.scenes if s in set(valid[g.work]))\n",
                "                gs = frozenset(g.scenes)\n")],
        test="test_title_only_gold_scene_dropped",
    ),
    dict(
        id="M21",
        guard="title-only scenes are never targets of a reference forecast",
        file="cs2/baselines.py",
        edits=[('    drop = comp.title_only_scenes() if cfg["exclude_title_only_scenes"] else set()\n',
                "    drop = set()\n")],
        test="test_hierarchical_log_loss_known_answer",
    ),
    dict(
        id="M22",
        guard="epsilon moves claimed mass to the fallback",
        file="cs2/scorer.py",
        edits=[("        return 1 - (1 - self.eps) * (1 - self.abstain)\n",
                "        return self.abstain\n")],
        test="test_hierarchical_log_loss_known_answer",
    ),
]


def classify(named, failed, errors):
    """EVIDENCE.md: a mutant discriminates only when its named assertion fails and its siblings
    pass. One caught only by a raised guard, or with siblings failing, is a regression guard."""
    if not failed or named not in failed:
        return dict(classification="not caught", reason="the named test passed")
    reasons = []
    if errors.get(named) != "AssertionError":
        reasons.append(f"caught by a raised {errors.get(named)}, not by the test's assertion")
    if len(failed) > 1:
        reasons.append(f"{len(failed) - 1} sibling test(s) also fail")
    if reasons:
        return dict(classification="regression guard", reason="; ".join(reasons))
    return dict(classification="discriminating", reason="only the named assertion fails")


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
                **classify(m["test"], failed, r.get("errors") or {}),
                failed=failed,
                errors=r.get("errors"),
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
