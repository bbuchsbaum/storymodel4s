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

    def test_an_extra_cannot_overwrite_a_prediction(self):
        def change(b):
            b["units"][3]["extras"] = {
                "referent": {"status": "answered", "kind": "scene", "scene": 1},
                "row": 99,
            }

        self.refused(change, "an extra uses a row's own key")

    def test_an_unknown_scene_or_film_restated_as_a_known_one_is_refused(self):
        def scene(b):
            b["units"][5]["referent"]["stated"]["scene"] = 1

        self.refused(scene, "does not follow from the stated answer")

        def film(b):
            b["units"][2]["narrative"]["stated"]["film"] = 3

        self.refused(film, "does not follow from the stated answer")

    def test_a_number_must_be_an_integer(self):
        def boolean(b):
            b["units"][0]["referent"]["stated"]["scene"] = True

        self.refused(boolean, "not an integer")
        self.refused(lambda b: b["units"][0].__setitem__("u", "1"), "numbered 1 to n")

    def test_swapped_slots_are_refused(self):
        def change(b):
            i = b["interpretations"]
            i["narrative"], i["referent"] = i["referent"], i["narrative"]
            for unit in b["units"]:
                unit["narrative"], unit["referent"] = unit["referent"], unit["narrative"]

        self.refused(change, "slot does not hold")

    def test_a_relabelled_run_is_refused(self):
        self.refused(
            lambda b: b["provenance"].__setitem__("model", "another"),
            "not from the run the bundle names",
        )
        self.refused(
            lambda b: b["provenance"].__setitem__("condition", "automatic"),
            "slot does not hold",
        )

    def test_a_failure_keeps_its_own_kind(self):
        def change(b):
            b["units"][4]["referent"]["judgement"] = {"kind": "invalid", "reason": "x"}

        self.refused(change, "does not follow from the stated answer")

    def test_a_malformed_bundle_is_refused_not_crashed(self):
        with tempfile.TemporaryDirectory() as tmp:
            broken = copy.deepcopy(self.bundle)
            broken["units"][0]["referent"]["stated"] = None
            del broken["units"][1]["extras"]
            for i, doc in enumerate((broken, {"schema": study_bundle.SCHEMA, "units": 3})):
                path = os.path.join(tmp, f"{i}.bundle.json")
                with open(path, "w", encoding="utf-8") as handle:
                    json.dump(doc, handle)
                with self.assertRaises(study_bundle.BundleError):
                    study_bundle.load(path)

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
        self.refused(lambda b: b["units"][1].__setitem__("u", 1), "numbered 1 to n")

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
            wrong = os.path.join(tmp, "wrong")
            os.makedirs(wrong)
            with open(os.path.join(wrong, "p9_s1.bundle.json"), "w", encoding="utf-8") as h:
                json.dump(self.bundle, h)
            with self.assertRaises(study_bundle.BundleError) as caught:
                study_bundle.load_directory(wrong)
            self.assertIn("not named for", str(caught.exception))
            for name, bundle in (("p1_s1", self.bundle), ("p1_s2", other)):
                with open(os.path.join(run, name + ".bundle.json"), "w", encoding="utf-8") as h:
                    json.dump(bundle, h)
            out = os.path.join(tmp, "replies")
            self.assertEqual(study_bundle.write_replies(run, out), 2)
            self.assertEqual(sorted(os.listdir(out)), ["p1_s1.json", "p1_s2.json"])
            with self.assertRaises(study_bundle.BundleError):
                study_bundle.write_replies(run, out)
            other["provenance"]["manifestSha256"] = "1" * 64
            with open(os.path.join(run, "p1_s2.bundle.json"), "w", encoding="utf-8") as h:
                json.dump(other, h)
            with self.assertRaises(study_bundle.BundleError) as caught:
                study_bundle.load_directory(run)
            self.assertIn("one manifestSha256", str(caught.exception))
            other["provenance"].update(manifestSha256="0" * 64, session="s1")
            with open(os.path.join(run, "p1_s2.bundle.json"), "w", encoding="utf-8") as h:
                json.dump(other, h)
            with self.assertRaises(study_bundle.BundleError):
                study_bundle.load_directory(run)
            self.assertEqual(study_bundle.main(["replies", os.path.join(tmp, "none"), out]), 2)


TIME_FIXTURE = FIXTURE.with_name("synthetic-study-time.bundle.json")


class TimeBundle(unittest.TestCase):
    """A condition that answers with a film and a time."""

    def setUp(self):
        self.bundle = study_bundle.load(TIME_FIXTURE)

    def refused(self, change, message):
        bundle = copy.deepcopy(self.bundle)
        change(bundle)
        with self.assertRaises(study_bundle.BundleError) as caught:
            study_bundle.check(bundle)
        self.assertIn(message, str(caught.exception))

    def test_kinds_and_plain_words(self):
        lines = study_bundle.summary(self.bundle)
        self.assertIn("referent: invalid=2, none=1, omitted=1, scene=1, time-outside=1", lines)
        self.assertIn("narrative: film=1, invalid=2, omitted=1, scene=2", lines)
        self.assertEqual(
            study_bundle.describe_unit(self.bundle, 2)[1:3],
            [
                'narrative: scene 6 in film 3 "Station film"',
                'referent: film 3 "Station film", at a time outside its clip',
            ],
        )

    def test_the_reply_document_keeps_the_raw_time_beside_the_scene(self):
        units = study_bundle.reply_document(self.bundle)["units"]
        self.assertEqual(
            units[0]["narrative"],
            {
                "status": "answered",
                "kind": "scene",
                "scene": 3,
                "near": [2, 3],
                "film_stated": 1,
                "time": "0:20.5",
                "seconds": 20.5,
            },
        )
        self.assertEqual(units[1]["referent"]["kind"], "time_outside")
        self.assertEqual(
            units[3]["referent"],
            {"status": "failed", "reason": "unknown-film", "film_stated": 2, "time": "0:05"},
        )

    def test_every_time_answer_is_what_its_film_and_time_give(self):
        clock = study_bundle.clock_table(self.bundle["source"])
        cases = {
            (1, "0:20.499"): ("scene", 2, [2, 3]),
            (1, "0:20.5"): ("scene", 3, [2, 3]),
            (3, "0:20"): ("scene", 6, [6]),
            (3, "0:20.001"): ("time_outside", None, [6]),
            (3, "0:22.001"): ("time_outside", None, []),
        }
        for (film, time), (kind, scene, near) in cases.items():
            made = study_bundle.time_prediction(clock, film, time)
            self.assertEqual((made["kind"], made.get("scene"), made["near"]), (kind, scene, near))
        for film, time, reason in (
            (2, "0:05", "unknown-film"),
            (1, "5", "invalid-time"),
            (1, 5, "invalid-time"),
            (None, "0:05", "time-without-film"),
            (True, "0:05", "invalid-film"),
            (None, "x", "invalid-time"),
        ):
            self.assertEqual(study_bundle.time_prediction(clock, film, time)["reason"], reason)
        self.assertEqual(study_bundle.time_prediction(clock, 3, None)["kind"], "film")
        self.assertEqual(study_bundle.time_prediction(clock, None, None)["kind"], "none")

    def test_a_scene_near_list_or_time_changed_alone_is_refused(self):
        for key, value in (("scene", 3), ("near", [2, 3]), ("seconds", 11.0), ("time", "0:30")):

            def change(b, key=key, value=value):
                b["units"][0]["referent"]["stated"][key] = value

            self.refused(change, "not what its film and time give")

    def test_a_time_and_scene_changed_together_are_refused_by_the_record(self):
        def change(b):
            b["units"][0]["referent"]["stated"].update(scene=3, near=[3], time="0:30", seconds=30.0)
            b["units"][0]["referent"]["judgement"] = {"kind": "scene", "scene": 3, "collection": 1}

        self.refused(change, "row says scene, record says choice")

    def test_a_scene_without_a_time_is_refused(self):
        def change(b):
            b["units"][0]["referent"]["stated"] = {
                "status": "answered",
                "kind": "scene",
                "scene": 2,
                "film_stated": 1,
            }

        self.refused(change, "a scene without a time")

    def test_time_outside_cannot_be_relabelled_a_film_alone(self):
        def change(b):
            b["units"][1]["referent"]["judgement"]["kind"] = "film"

        self.refused(change, "does not follow from the stated answer")

    def test_the_clock_and_the_protocol_go_together(self):
        self.refused(lambda b: b["source"].pop("clock"), "needs a clip clock")
        plain = study_bundle.load(FIXTURE)
        plain["source"]["clock"] = self.bundle["source"]["clock"]
        with self.assertRaises(study_bundle.BundleError):
            study_bundle.check(plain)

    def test_a_widened_tolerance_changes_the_near_scenes_and_is_refused(self):
        self.refused(
            lambda b: b["source"]["clock"].__setitem__("toleranceSeconds", "11/1"),
            "not what its film and time give",
        )


if __name__ == "__main__":
    unittest.main()
