"""Tests for the offline Sherlock v1-to-v2 comparison.

Run from the repository root: python3 tools/agentmapping/test_compare_sherlock.py
"""

import copy
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import compare_sherlock  # noqa: E402

V1_FIXTURE = (
    pathlib.Path(__file__).resolve().parents[2]
    / "pipeline/src/test/resources/agentmapping/synthetic.bundle.json"
)
V2_FIXTURE = V1_FIXTURE.with_name("synthetic-study.bundle.json")


def legacy_unit(index=7, narrative=3, referent=4, alternatives=None, rows=None, confidence=None):
    def field(value):
        return {"status": "null"} if value is None else {"status": "value", "value": value}

    return {
        "index": index,
        "processing": {"status": "answered"},
        "narrative": field(narrative),
        "referent": field(referent),
        "alternatives": field([[narrative, 0.8]]) if alternatives is None else field(alternatives),
        "rows": field([11]) if rows is None else field(rows),
        "confidence": field(0.9) if confidence is None else field(confidence),
    }


def v2_unit(u=7, narrative=3, referent=4, alts=None, rows=None, conf=None):
    def stated(value):
        return (
            {"status": "answered", "kind": "none"}
            if value is None
            else {"status": "answered", "kind": "scene", "scene": value}
        )

    extras = {
        "alts": [[narrative, 0.8]] if alts is None else alts,
        "rows": [11] if rows is None else rows,
        "conf": 0.9 if conf is None else conf,
    }
    return {
        "u": u,
        "row": u,
        "narrative": {"stated": stated(narrative)},
        "referent": {"stated": stated(referent)},
        "extras": extras,
    }


def bundle(unit, digest_prefix):
    return {
        "units": [unit],
        "interpretations": {
            "narrative": {"recordDigest": digest_prefix + "-narrative"},
            "referent": {"recordDigest": digest_prefix + "-referent"},
        },
    }


class CompareSherlock(unittest.TestCase):
    def setUp(self):
        self.legacy = {"P01": bundle(legacy_unit(), "legacy")}
        self.v2 = {"P01": bundle(v2_unit(), "v2")}

    def receipt(self, legacy=None, v2=None):
        return compare_sherlock.compare(
            self.legacy if legacy is None else legacy,
            self.v2 if v2 is None else v2,
            {"P01.bundle.json": "old-hash"},
            {"P01_recall.bundle.json": "new-hash"},
        )

    def fields(self, receipt):
        return {entry["field"] for entry in receipt["differences"]["diagnostics"]}

    def test_equal_projection_records_changed_digests_and_file_hashes(self):
        receipt = self.receipt()
        self.assertEqual(receipt["differences"]["count"], 0)
        self.assertEqual(receipt["counts"]["units"], 1)
        self.assertEqual(receipt["file_sha256"]["legacy"]["P01.bundle.json"], "old-hash")
        self.assertEqual(receipt["record_digests"]["P01"]["old"]["narrative"], "legacy-narrative")
        self.assertEqual(receipt["record_digests"]["P01"]["new"]["narrative"], "v2-narrative")

    def test_missing_unit_is_not_joined(self):
        v2 = copy.deepcopy(self.v2)
        v2["P01"]["units"] = []
        self.assertIn("unit-set", self.fields(self.receipt(v2=v2)))

    def test_swapped_fields_are_detected(self):
        v2 = copy.deepcopy(self.v2)
        v2["P01"]["units"][0]["narrative"], v2["P01"]["units"][0]["referent"] = (
            v2["P01"]["units"][0]["referent"],
            v2["P01"]["units"][0]["narrative"],
        )
        fields = self.fields(self.receipt(v2=v2))
        self.assertIn("narrative", fields)
        self.assertIn("referent", fields)

    def test_edited_prediction_is_detected(self):
        v2 = copy.deepcopy(self.v2)
        v2["P01"]["units"][0]["narrative"]["stated"]["scene"] = 99
        self.assertIn("narrative", self.fields(self.receipt(v2=v2)))

    def test_altered_extras_are_detected(self):
        v2 = copy.deepcopy(self.v2)
        v2["P01"]["units"][0]["extras"]["conf"] = 0.1
        self.assertIn("extra:conf", self.fields(self.receipt(v2=v2)))

    def test_absent_null_and_invalid_are_not_equated(self):
        legacy = copy.deepcopy(self.legacy)
        legacy["P01"]["units"][0]["narrative"] = {"status": "absent"}
        self.assertIn("narrative", self.fields(self.receipt(legacy=legacy)))

    def test_legacy_missing_field_equals_v2_explicit_missing_field_failure(self):
        legacy = copy.deepcopy(self.legacy)
        v2 = copy.deepcopy(self.v2)
        legacy["P01"]["units"][0]["narrative"] = {"status": "absent"}
        v2["P01"]["units"][0]["narrative"]["stated"] = {
            "status": "failed",
            "reason": "missing-field",
        }
        self.assertEqual(self.receipt(legacy=legacy, v2=v2)["differences"]["count"], 0)

    def test_legacy_unparsable_equals_v2_unparsable_chunk(self):
        legacy = copy.deepcopy(self.legacy)
        v2 = copy.deepcopy(self.v2)
        legacy["P01"]["units"][0]["processing"] = {"status": "unparsable"}
        for field in ("narrative", "referent"):
            v2["P01"]["units"][0][field]["stated"] = {
                "status": "failed",
                "reason": "unparsable-chunk",
            }
        self.assertEqual(self.receipt(legacy=legacy, v2=v2)["differences"]["count"], 0)
        legacy["P01"]["units"][0]["narrative"] = {"status": "invalid", "raw": "private text"}
        self.assertIn("narrative", self.fields(self.receipt(legacy=legacy)))

    def test_v2_row_must_equal_u_and_legacy_index(self):
        v2 = copy.deepcopy(self.v2)
        v2["P01"]["units"][0]["row"] = 8
        self.assertIn("v2-u-row-join", self.fields(self.receipt(v2=v2)))

    def test_legacy_loader_ignores_receipt_and_checks_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            bundle_path = root / "P00.bundle.json"
            bundle_path.write_bytes(V1_FIXTURE.read_bytes())
            (root / "bundle-receipt.json").write_text("{}", encoding="utf-8")
            bundles, hashes = compare_sherlock.load_legacy_directory(root)
            self.assertEqual(set(bundles), {"P00"})
            self.assertEqual(set(hashes), {"P00.bundle.json"})

            malformed = json.loads(V1_FIXTURE.read_text(encoding="utf-8"))
            malformed["units"][1]["index"] = malformed["units"][0]["index"]
            bundle_path.write_text(json.dumps(malformed), encoding="utf-8")
            with self.assertRaisesRegex(compare_sherlock.read_bundle.BundleError, "repeated legacy index"):
                compare_sherlock.load_legacy_directory(root)

    def test_v2_loader_refuses_an_unexpected_session(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            bundle = json.loads(V2_FIXTURE.read_text(encoding="utf-8"))
            # The committed fixture is valid but deliberately has session s1,
            # which must not be silently excluded from a Sherlock comparison.
            (root / "p1_s1.bundle.json").write_text(json.dumps(bundle), encoding="utf-8")
            with self.assertRaisesRegex(compare_sherlock.study_bundle.BundleError, "recall-session"):
                compare_sherlock.load_v2_directory(root)


if __name__ == "__main__":
    unittest.main()
