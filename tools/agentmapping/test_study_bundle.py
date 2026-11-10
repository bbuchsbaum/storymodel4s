"""Tests for study_bundle.py on the synthetic v2 bundle the Scala writer is pinned to.

Run from the repository root: python3 tools/agentmapping/test_study_bundle.py
"""

import copy
import json
import os
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import study_bundle  # noqa: E402

FIXTURE = (
    pathlib.Path(__file__).resolve().parents[2]
    / "pipeline/src/test/resources/agentmapping/synthetic-study.bundle.json"
)


class StudyBundle(unittest.TestCase):
    def setUp(self):
        self.bundle = study_bundle.load(FIXTURE)

    def refused(self, change, message):
        bundle = copy.deepcopy(self.bundle)
        change(bundle)
        with self.assertRaises(study_bundle.BundleError) as caught:
            study_bundle.check(bundle)
        self.assertIn(message, str(caught.exception))

    def test_summary_counts_every_kind(self):
        lines = study_bundle.summary(self.bundle)
        self.assertEqual(lines[0], "p1 s1: 6 units")
        self.assertIn("model calls 0", lines[1])
        self.assertIn(
            "referent: film=1, invalid-scene=1, none=1, omitted=1, scene=1, unknown-scene=1",
            lines,
        )
        self.assertIn(
            "narrative: invalid=1, none=1, omitted=1, scene=2, unknown-film=1", lines
        )
        self.assertEqual(lines[-1], "source: 3 films, 4 scenes, 4 rows")

    def test_a_unit_reads_in_plain_words(self):
        self.assertEqual(
            study_bundle.describe_unit(self.bundle, 2),
            [
                "unit 2 (row 11): answered",
                'narrative: scene 1 in film 1 "Harbour film"',
                'referent: scene 2 in film 1 "Harbour film" (not a target)',
                "conf (self-reported): 0.5",
            ],
        )
        self.assertEqual(
            study_bundle.describe_unit(self.bundle, 3)[1:3],
            [
                "narrative: failed: unknown-film (2)",
                'referent: film 3 "Station film", no particular scene',
            ],
        )
        self.assertEqual(
            study_bundle.describe_unit(self.bundle, 5),
            ["unit 5 (row 14): omitted", "narrative: failed: omitted", "referent: failed: omitted"],
        )

    def test_the_reply_document_is_what_a_scorer_reads(self):
        doc = study_bundle.reply_document(self.bundle)
        self.assertEqual(
            {k: v for k, v in doc.items() if k != "units"},
            {
                "participant": "p1",
                "session": "s1",
                "model": "m",
                "condition": "annotation",
                "manifestSha256": "0" * 64,
            },
        )
        self.assertEqual(
            doc["units"][0],
            {
                "u": 1,
                "row": 10,
                "referent": {"status": "answered", "kind": "scene", "scene": 1, "film_stated": 1},
                "narrative": {"status": "answered", "kind": "scene", "scene": 1, "film_stated": 1},
                "conf": 0.8,
            },
        )
        self.assertEqual(
            doc["units"][2]["referent"], {"status": "answered", "kind": "film", "film": 3}
        )
        self.assertEqual(doc["units"][3]["referent"], {"status": "answered", "kind": "none"})
        self.assertEqual(
            doc["units"][4],
            {
                "u": 5,
                "row": 14,
                "referent": {"status": "failed", "reason": "omitted"},
                "narrative": {"status": "failed", "reason": "omitted"},
            },
        )

    def test_a_judgement_changed_alone_is_refused(self):
        def change(b):
            b["units"][0]["referent"]["judgement"] = {"kind": "none"}

        self.refused(change, "does not follow from the stated answer")

    def test_a_row_changed_consistently_is_refused_by_the_record(self):
        def change(b):
            b["units"][0]["referent"]["stated"]["scene"] = 4
            b["units"][0]["referent"]["judgement"] = {"kind": "scene", "scene": 4, "collection": 3}

        self.refused(change, "row says scene, record says choice")

        def promote(b):
            b["units"][1]["referent"]["stated"] = {"status": "answered", "kind": "none"}
            b["units"][1]["referent"]["judgement"] = {"kind": "none"}

        self.refused(promote, "row says none, record says failed")

    def test_a_non_target_cannot_be_called_a_scene_choice(self):
        def change(b):
            b["units"][1]["referent"]["judgement"]["kind"] = "scene"

        self.refused(change, "does not follow from the stated answer")

    def test_a_failure_cannot_be_called_an_answer(self):
        def change(b):
            b["units"][4]["referent"]["judgement"] = {"kind": "none"}

        self.refused(change, "does not follow from the stated answer")

    def test_processing_must_match_the_judgements(self):
        self.refused(
            lambda b: b["units"][4].__setitem__("processing", "answered"),
            "processing does not match",
        )
        self.refused(
            lambda b: b["units"][0].__setitem__("processing", "omitted"),
            "processing does not match",
        )

    def test_a_dropped_or_repeated_unit_is_refused(self):
        self.refused(lambda b: b["units"].pop(), "do not match")
        self.refused(lambda b: b["units"][1].__setitem__("u", 1), "appears twice")

    def test_another_version_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "x.bundle.json")
            with open(path, "w", encoding="utf-8") as handle:
                json.dump({"schema": "storymodel4s.agentmapping.bundle/v1"}, handle)
            with self.assertRaises(study_bundle.BundleError):
                study_bundle.load(path)

    def test_a_directory_writes_one_reply_file_per_bundle_and_refuses_mixed_runs(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = os.path.join(tmp, "run")
            os.makedirs(run)
            other = copy.deepcopy(self.bundle)
            other["provenance"]["session"] = "s2"
            for name, bundle in (("p1_s1", self.bundle), ("p1_s2", other)):
                with open(os.path.join(run, name + ".bundle.json"), "w", encoding="utf-8") as h:
                    json.dump(bundle, h)
            out = os.path.join(tmp, "replies")
            self.assertEqual(study_bundle.write_replies(run, out), 2)
            self.assertEqual(sorted(os.listdir(out)), ["p1_s1.json", "p1_s2.json"])
            with self.assertRaises(study_bundle.BundleError):
                study_bundle.write_replies(run, out)
            other["provenance"]["model"] = "another"
            with open(os.path.join(run, "p1_s2.bundle.json"), "w", encoding="utf-8") as h:
                json.dump(other, h)
            with self.assertRaises(study_bundle.BundleError) as caught:
                study_bundle.load_directory(run)
            self.assertIn("one model", str(caught.exception))
            other["provenance"].update(model="m", session="s1")
            with open(os.path.join(run, "p1_s2.bundle.json"), "w", encoding="utf-8") as h:
                json.dump(other, h)
            with self.assertRaises(study_bundle.BundleError):
                study_bundle.load_directory(run)
            self.assertEqual(study_bundle.main(["replies", os.path.join(tmp, "none"), out]), 2)


if __name__ == "__main__":
    unittest.main()
