#!/usr/bin/env python3
"""Known answers and independent-reader refusal controls; no corpus or participant input."""
import copy
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from read_organization import CATEGORIES, check_result, load, number
from organization_recovery import integer_counts


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
