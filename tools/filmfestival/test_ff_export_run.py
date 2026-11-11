"""Tests for the run exporter's clip-clock document.

Run from the repository root: python3 tools/filmfestival/test_ff_export_run.py
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_export_run  # noqa: E402
import ff_map  # noqa: E402


class ClockDocument(unittest.TestCase):
    def manifest(self):
        return {
            "toleranceSeconds": "2",
            "clock": {
                "10": {"C0": "956", "a": "1", "b": "0.3"},
                "2": {"C0": "397", "a": "0.996", "b": "-0.7"},
            },
            "clipSeconds": {"10": 131.5, "2": 120.04},
        }

    def test_the_clock_is_the_manifests_in_film_order_as_decimal_strings(self):
        doc = ff_export_run.clock_document(self.manifest())
        self.assertEqual(doc["schema"], "storymodel4s.agentmapping.clip-clock/v1")
        self.assertEqual(doc["toleranceSeconds"], "2")
        self.assertEqual(
            doc["collections"],
            [
                {
                    "collection": 2,
                    "origin": "397",
                    "rate": "0.996",
                    "offset": "-0.7",
                    "clipSeconds": "120.04",
                },
                {
                    "collection": 10,
                    "origin": "956",
                    "rate": "1",
                    "offset": "0.3",
                    "clipSeconds": "131.5",
                },
            ],
        )

    def test_a_clock_and_clip_lengths_for_different_films_refuse(self):
        manifest = self.manifest()
        del manifest["clipSeconds"]["2"]
        with self.assertRaises(ff_export_run.ExportError):
            ff_export_run.clock_document(manifest)

    def test_every_condition_has_a_reply_protocol(self):
        self.assertEqual(sorted(ff_export_run.REPLY_PROTOCOL), sorted(ff_map.CONDITIONS))
        self.assertEqual(
            ff_export_run.REPLY_PROTOCOL[ff_map.NORECORD], "strict-chunked-time/v1"
        )


if __name__ == "__main__":
    unittest.main()
