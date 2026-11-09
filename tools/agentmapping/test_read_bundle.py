"""Tests for read_bundle.py on the synthetic bundle the Scala writer is pinned to.

Run from the repository root: python3 tools/agentmapping/test_read_bundle.py
"""

import copy
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import read_bundle  # noqa: E402

FIXTURE = (
    pathlib.Path(__file__).resolve().parents[2]
    / "pipeline/src/test/resources/agentmapping/synthetic.bundle.json"
)


class ReadBundle(unittest.TestCase):
    def setUp(self):
        self.bundle = read_bundle.load(FIXTURE)

    def lines(self, index):
        return read_bundle.describe_unit(self.bundle, read_bundle.find_unit(self.bundle, index))

    def test_both_judgements_and_citations_resolve(self):
        lines = self.lines(0)
        self.assertIn('narrative: scene 1 "1. Opening"', lines)
        self.assertIn('referent: scene 2 "2. Cartoon"', lines)
        self.assertEqual(lines.count("  record: choice of scene 1"), 1)
        self.assertEqual(lines.count("  record: choice of scene 2"), 1)
        self.assertIn("confidence (self-reported): 0.8", lines)
        self.assertTrue(any(l.startswith("  r5: scene 2,") and "outside the narrative" in l for l in lines))
        self.assertTrue(any(l.startswith("  r2: scene 1,") and "outside" not in l for l in lines))
        self.assertIn("  r999: not in source", lines)

    def test_null_absent_and_invalid_stay_distinct(self):
        one = self.lines(1)
        self.assertIn("narrative: null (reported by the model)", one)
        self.assertIn("  record: no-assignment (model reported no narrative assignment)", one)
        self.assertIn("referent: unavailable (not in the reply)", one)
        self.assertIn("  record: failed (invalid-output: reply item has no referent field)", one)
        self.assertIn("confidence (self-reported): unavailable (not in the reply)", one)
        self.assertIn("narrative: invalid: 99", self.lines(4))

    def test_omitted_and_unparsable_units_are_failures(self):
        self.assertTrue(self.lines(2)[1].startswith("processing: omitted"))
        self.assertTrue(any("provider-failure" in l for l in self.lines(2)))
        self.assertTrue(self.lines(3)[1].startswith("processing: unparsable"))
        self.assertTrue(any("invalid-output" in l for l in self.lines(3)))

    def test_unit_by_id_and_summary(self):
        unit = self.bundle["units"][0]
        self.assertEqual(read_bundle.find_unit(self.bundle, unit["unit"]), unit)
        summary = read_bundle.summary(self.bundle)
        self.assertIn("processing: answered=5, omitted=1, unparsable=1", summary)
        self.assertIn("narrative and referent differ: 1", summary)

    def test_row_that_disagrees_with_its_record_is_refused(self):
        edited = copy.deepcopy(self.bundle)
        edited["units"][0]["narrative"]["value"] = 2
        with self.assertRaises(read_bundle.BundleError):
            read_bundle.summary(edited)

    def test_alternatives_are_reported_as_self_reports(self):
        self.assertIn(
            "alternatives (self-reported, narrative only, not a distribution): "
            "scene 1 p=0.6; scene 2 p=0.3",
            self.lines(0),
        )

    def write(self, bundle):
        handle = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False)
        self.addCleanup(pathlib.Path(handle.name).unlink)
        json.dump(bundle, handle)
        handle.close()
        return handle.name

    def test_missing_and_duplicated_units_are_refused(self):
        fewer = copy.deepcopy(self.bundle)
        del fewer["units"][2]
        doubled = copy.deepcopy(self.bundle)
        doubled["units"].append(doubled["units"][0])
        for edited in (fewer, doubled):
            with self.assertRaises(read_bundle.BundleError):
                read_bundle.load(self.write(edited))

    def test_swapped_records_are_refused(self):
        swapped = copy.deepcopy(self.bundle)
        slots = swapped["interpretations"]
        slots["narrative"], slots["referent"] = slots["referent"], slots["narrative"]
        with self.assertRaises(read_bundle.BundleError):
            read_bundle.summary(read_bundle.load(self.write(swapped)))

    def test_wrong_schema_is_refused(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json") as handle:
            json.dump({"schema": "something-else"}, handle)
            handle.flush()
            self.assertEqual(read_bundle.main([handle.name]), 2)


if __name__ == "__main__":
    unittest.main()
