#!/usr/bin/env python3
"""Known answers and independent-reader refusal controls; no corpus or participant input."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from read_organization import CATEGORIES, check_result, load, number, read_exchange
from organization_recovery import check_packet_inputs, integer_counts, language_diagnostics, language_true_mass


def point_result():
    ranks = [2, 7, 3, 8]
    rows = [{"unit": f"u{i}", "status": "Available", "mass": [{"destination": f"t{n}", "mass": 1.0}], "admissible": [f"t{n}"]} for i, n in enumerate(ranks)]
    transitions = []
    for i, category in enumerate(["Forward", "Backward", "Forward"]):
        counts = dict.fromkeys(CATEGORIES, 0.0)
        counts[category] = 1.0
        transitions.append({"left": f"u{i}", "right": f"u{i+1}", "counts": counts})
    totals = dict.fromkeys(CATEGORIES, 0.0)
    totals.update(Backward=1.0, Forward=2.0)
    return {"schemaVersion": "organization-readout/v1", "dependence": "DeclaredIndependentRows", "orderKind": "DeclaredPositions",
            "order": [{"target": f"t{n}", "position": n} for n in ranks], "rows": rows, "transitions": transitions, "counts": totals,
            "units": 4, "adjacencies": 3, "comparable": 3.0, "backwardRate": 1/3,
            "measuredUnits": 4, "measuredTransitions": 3,
            "bounds": {c: {"minimum": int(totals[c]), "maximum": int(totals[c])} for c in CATEGORIES}}


class OrganizationRecoveryTest(unittest.TestCase):
    def test_frozen_quality_and_omission_checked_against_rows(self):
        r = point_result()
        r.update(axis="Discourse")
        for entry in r["order"]:
            entry["target"] = f"sit:synthetic-rank-{int(entry['position'])}"
        storage = [2, 3, 7, 8]
        for row, target in zip(r["rows"], [2, 7, 3, 8]):
            row["mass"] = [{"destination": f"sit:synthetic-rank-{n}", "mass": float(n == target)} for n in storage]
            row["admissible"] = [v["destination"] for v in row["mass"]]
        for category in CATEGORIES[:3]:
            r["bounds"][category] = {"minimum": 0, "maximum": 3}
        self.assertEqual(check_result(r)["counts"]["Backward"], 1.0)
        check_packet_inputs(r, [2, 7, 3, 8], 1.0, None, storage)
        # The same self-consistent point rows cannot stand in for the ambiguous condition.
        with self.assertRaisesRegex(ValueError, "frozen row mass"):
            check_packet_inputs(r, [2, 7, 3, 8], 0.5, None, storage)
        with self.assertRaisesRegex(ValueError, "frozen unit status"):
            check_packet_inputs(r, [2, 7, 3, 8], 1.0, 1, storage)
        # A self-consistent jump cannot be relabeled as the monotone frozen path either.
        with self.assertRaisesRegex(ValueError, "frozen row mass"):
            check_packet_inputs(r, [2, 3, 7, 8], 1.0, None, storage)
        r["rows"][0]["admissible"].pop()
        with self.assertRaisesRegex(ValueError, "frozen admissible"):
            check_packet_inputs(r, [2, 7, 3, 8], 1.0, None, storage)

    def test_language_summary_must_equal_true_target_row_mass(self):
        r = point_result()
        r.update(axis="Discourse", orderKind="TextDiscourseRanks")
        for index, entry in enumerate(r["order"]):
            entry["position"] = index
        self.assertEqual(language_true_mass(r, [0, 1, 2, 3], 4, [1.0] * 4), 1.0)
        with self.assertRaisesRegex(ValueError, "true-target mass"):
            language_true_mass(r, [0, 1, 2, 3], 4, [0.5] * 4)
        with self.assertRaisesRegex(ValueError, "true-target mass"):
            language_true_mass(r, [3, 2, 1, 0], 4, [1.0] * 4)
        diagnostic = language_diagnostics(r, [0, 1, 2, 3], [1, 2, 0])
        self.assertEqual(diagnostic["unique_true_argmax_fraction"], 1.0)
        self.assertEqual(diagnostic["mean_true_vs_best_other_margin"], 1.0)
        self.assertEqual(diagnostic["admitted_source_fraction"], 0.25)

    def test_missing_language_rows_leave_scalar_means_unavailable(self):
        for missing in [[0], [0, 1, 2, 3]]:
            r = point_result()
            r.update(axis="Discourse", orderKind="TextDiscourseRanks")
            for entry in r["order"]:
                entry["position"] = {2: 0, 3: 1, 7: 2, 8: 3}[entry["position"]]
            for index in missing:
                r["rows"][index].update(status="Unresolved", mass=[], admissible=[])
            total = dict.fromkeys(CATEGORIES, 0.0)
            for i, transition in enumerate(r["transitions"]):
                counts = dict.fromkeys(CATEGORIES, 0.0)
                counts["Unresolved" if i in missing or i + 1 in missing else ["Forward", "Backward", "Forward"][i]] = 1.0
                transition["counts"] = counts
                for category in CATEGORIES:
                    total[category] += counts[category]
            r.update(counts=total, comparable=total["Backward"] + total["Forward"],
                     measuredUnits=4-len(missing), measuredTransitions=3-int(total["Unresolved"]))
            del r["bounds"]
            if r["comparable"]:
                r["backwardRate"] = total["Backward"] / r["comparable"]
            else:
                del r["backwardRate"]
            check_result(r)
            reported = [0.0 if i in missing else 1.0 for i in range(4)]
            self.assertIsNone(language_true_mass(r, [0, 2, 1, 3], 4, reported))
            diagnostic = language_diagnostics(r, [0, 2, 1, 3], [1, 2, 0])
            self.assertEqual((diagnostic["original_units"], diagnostic["measured_units"]), (4, 4-len(missing)))
            self.assertIsNone(diagnostic["unique_true_argmax_fraction"])
            self.assertIsNone(diagnostic["mean_true_vs_best_other_margin"])
            self.assertIsNone(diagnostic["truth_in_conditional_bounds"])

    def test_empty_single_refuses_failed_only_cohort_is_accounted(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "manifest.json"
            path.write_text(json.dumps({"schemaVersion": "organization-exchange/v1", "files": [], "metadata": {}}))
            with self.assertRaisesRegex(ValueError, "complete organization payload"):
                read_exchange(root)
            cohort = {"schemaVersion": "organization-cohort/v1", "files": [], "metadata": {
                "attempts": 1, "outcomes": [{"participant": "synthetic", "session": "one", "status": "failed", "reason": "refused"}]}}
            path.write_text(json.dumps(cohort))
            result = read_exchange(root)
            self.assertEqual(len(result["outcomes"]), 1)
            self.assertEqual(result["results"], [])
            cohort["metadata"]["attempts"] = 2
            path.write_text(json.dumps(cohort))
            with self.assertRaisesRegex(ValueError, "attempt accounting"):
                read_exchange(root)
            cohort["metadata"]["attempts"] = 1
            cohort["metadata"]["outcomes"][0] = {"participant": "synthetic", "session": "one", "status": "complete", "run": "a" * 64,
                "organization": "b" * 64, "metadata": {"run": "a" * 64, "organization": "b" * 64}}
            path.write_text(json.dumps(cohort))
            with self.assertRaisesRegex(ValueError, "complete organization payload"):
                read_exchange(root)

    def test_point_known_answer(self):
        r = point_result()
        self.assertEqual(check_result(r)["counts"]["Backward"], 1.0)
        self.assertEqual(integer_counts([2, 7, 3, 8]), [1, 2, 0])

    def test_wrong_direction_and_denominator_refuse(self):
        for field, value in [("comparable", 2), ("backwardRate", 0.5)]:
            mutant = point_result()
            mutant[field] = value
            with self.assertRaises(ValueError):
                check_result(mutant)
        mutant = point_result()
        mutant["transitions"][0]["counts"].update(Forward=0.0, Backward=1.0)
        with self.assertRaisesRegex(ValueError, "incorrect"):
            check_result(mutant)

    def test_gap_bridge_and_word_copy_refuse(self):
        r = point_result()
        r["transitions"][0]["right"] = "u2"
        with self.assertRaisesRegex(ValueError, "gap bridging"):
            check_result(r)
        r = point_result()
        r["rows"].append(copy.deepcopy(r["rows"][0]))
        with self.assertRaisesRegex(ValueError, "unit inventory"):
            check_result(r)

    def test_bounds_are_checked_not_only_totals(self):
        r = point_result()
        r["bounds"]["Backward"]["maximum"] = 2
        with self.assertRaisesRegex(ValueError, "incoherent"):
            check_result(r)

    def test_unavailable_and_nonfinite_do_not_get_plausible_values(self):
        for raw in [float("nan"), float("inf"), "0x7ff8000000000000"]:
            with self.assertRaises(ValueError):
                number(raw)
        r = point_result()
        r["rows"][1]["status"] = "Unresolved"
        with self.assertRaisesRegex(ValueError, "unavailable"):
            check_result(r)

    def test_duplicate_json_keys_and_unsupported_media_order_refuse(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            load('{"a":1,"a":2}')
        r = point_result()
        r["orderKind"] = "MediaPartDiscourse"
        with self.assertRaisesRegex(ValueError, "contextual reader"):
            check_result(r)
        self.assertEqual(check_result(load(json.dumps(point_result())))["bounds_check"], "exhaustive-enumeration")


if __name__ == "__main__":
    unittest.main()
