#!/usr/bin/env python3
"""Courts for the v2 graph reader (ADR 0028 section 2) on a synthetic graph: Ana and Ben on screen,
a spoken name and a pronoun. No story text or private data (rule 14d).
Run: python3 -m unittest discover -s tools/source_identity
"""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import graph_reader as r

T = 44100  # ticks per second


def sec(a, z):
    return [a * T, z * T]


def doc():
    return {
        "schemaVersion": r.SCHEMA,
        "edition": {
            "edition": "synthetic",
            "timebase": [1, T],
            "start": 0,
            "end": 20 * T,
        },
        "mentions": [
            {
                "id": "v-ana",
                "outcome": {"status": "admitted"},
                "class": "d-ana",
                "origin": "visual",
                "stream": "picture",
                "intervals": [sec(10, 13)],
                "name": "Ana",
            },
            {
                "id": "v-ben",
                "outcome": {"status": "admitted"},
                "class": "v-ben",
                "origin": "visual",
                "stream": "picture",
                "intervals": [sec(10, 13)],
                "name": "Ben",
            },
            {
                "id": "v-cara",
                "outcome": {"status": "admitted"},
                "class": "v-cara",
                "origin": "visual",
                "stream": "picture",
                "intervals": [sec(15, 16)],
            },
            {
                "id": "d-ana",
                "outcome": {"status": "admitted"},
                "class": "d-ana",
                "origin": "dialogue",
                "stream": "audio",
                "intervals": [sec(11, 12)],
                "name": "Ana",
                "context": "ctx-s1",
                "words": [7],
            },
            {
                "id": "d-she",
                "outcome": {"status": "admitted"},
                "class": "d-she",
                "origin": "dialogue",
                "stream": "audio",
                "intervals": [sec(12, 13)],
                "context": "ctx-s1",
                "words": [9],
            },
            {"id": "x-bad", "outcome": {"status": "refused", "reason": "r"}},
        ],
        "classes": [
            {"id": "d-ana", "members": ["d-ana", "v-ana"], "contested": False},
            {"id": "d-she", "members": ["d-she"], "contested": False},
            {"id": "v-ben", "members": ["v-ben"], "contested": False},
            {"id": "v-cara", "members": ["v-cara"], "contested": False},
        ],
        "distinct": [["d-ana", "v-ben"]],
        "relations": [
            {"dialogue": "d-she", "class": "d-ana", "relation": "Unknown", "hints": []}
        ],
    }


class ReaderTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, d):
        path = Path(self.tmp.name) / "graph.json"
        path.write_text(json.dumps(d))
        return path, hashlib.sha256(path.read_bytes()).hexdigest()

    def graph(self, d=None):
        path, digest = self.write(d or doc())
        return r.load(path, digest, "synthetic")

    def refused(self, mutate):
        d = doc()
        mutate(d)
        with self.assertRaises(r.GraphRefusal) as caught:
            self.graph(d)
        return str(caught.exception)

    # --- whole-graph refusals ----------------------------------------------------------------

    def test_control_the_synthetic_graph_loads(self):
        self.assertEqual(self.graph().edition, "synthetic")

    def test_bytes_other_than_the_frozen_hash_are_refused(self):
        path, _ = self.write(doc())
        with self.assertRaises(r.GraphRefusal):
            r.load(path, "0" * 64, "synthetic")

    def test_another_schema_version_is_refused(self):
        self.assertIn(
            "schemaVersion", self.refused(lambda d: d.update(schemaVersion="x/v1"))
        )

    def test_another_edition_is_refused(self):
        path, digest = self.write(doc())
        with self.assertRaises(r.GraphRefusal):
            r.load(path, digest, "other-edition")

    def test_an_unknown_relation_status_is_refused(self):
        self.assertIn(
            "unknown relation",
            self.refused(lambda d: d["relations"][0].update(relation="Maybe")),
        )

    def test_a_class_not_named_by_its_least_member_is_refused(self):
        self.assertIn(
            "least member", self.refused(lambda d: d["classes"][0].update(id="v-ana"))
        )

    def test_a_mention_outside_its_stated_class_is_refused(self):
        self.assertIn(
            "not a member",
            self.refused(lambda d: d["mentions"][0].update(**{"class": "v-ben"})),
        )

    def test_a_mention_in_two_classes_is_refused(self):
        self.assertIn(
            "two classes",
            self.refused(lambda d: d["classes"][2].update(members=["v-ben", "v-cara"])),
        )

    def test_a_dialogue_mention_without_words_is_refused(self):
        self.assertIn(
            "word indices", self.refused(lambda d: d["mentions"][3].pop("words"))
        )

    def test_a_visual_mention_with_words_is_refused(self):
        self.assertIn(
            "without words", self.refused(lambda d: d["mentions"][0].update(words=[1]))
        )

    def test_a_distinct_pair_with_a_contested_class_is_refused(self):
        self.assertIn(
            "contested", self.refused(lambda d: d["classes"][2].update(contested=True))
        )

    def test_an_unordered_distinct_pair_is_refused(self):
        self.assertIn(
            "ordered", self.refused(lambda d: d.update(distinct=[["v-ben", "d-ana"]]))
        )

    # --- time ------------------------------------------------------------------------------

    def test_record_times_convert_to_ticks_exactly(self):
        g = self.graph()
        self.assertEqual(g.ticks(74.64), 3291624)
        self.assertEqual(g.ticks("10"), 10 * T)

    def test_a_time_off_the_tick_grid_refuses_the_record_not_the_graph(self):
        g = self.graph()
        with self.assertRaises(r.RecordRefusal):
            g.ticks("10.0000001")
        self.assertEqual(g.ticks(10), 10 * T)

    def test_overlap_is_half_open_and_strict(self):
        g = self.graph()
        self.assertEqual([m.id for m in g.visual_mentions(13, 14)], [])
        self.assertEqual([m.id for m in g.visual_mentions(12, 13)], ["v-ana", "v-ben"])

    # --- binding ---------------------------------------------------------------------------

    def test_a_picture_span_equal_to_a_listed_name_binds_after_folding(self):
        self.assertEqual(self.graph().bind_picture(10, 11, "  ana "), "d-ana")

    def test_a_relational_description_containing_a_name_stays_unbound(self):
        self.assertIsNone(self.graph().bind_picture(10, 11, "Ana's sister"))

    def test_an_unnamed_description_stays_unbound(self):
        self.assertIsNone(self.graph().bind_picture(15, 16, "the woman"))

    def test_the_name_of_someone_not_in_the_row_stays_unbound(self):
        self.assertIsNone(self.graph().bind_picture(15, 16, "Ana"))

    def test_one_name_on_two_classes_stays_unbound(self):
        d = doc()
        d["mentions"][1]["name"] = "Ana"
        self.assertIsNone(self.graph(d).bind_picture(10, 11, "Ana"))

    WORDS = [
        {"index": 7, "start": 0, "end": 3},
        {"index": 8, "start": 4, "end": 7},
        {"index": 9, "start": 8, "end": 11},
    ]

    def test_a_speech_span_covering_exactly_a_mention_binds_to_its_class(self):
        g = self.graph()
        self.assertEqual(g.bind_speech(self.WORDS, (0, 3)), "d-ana")
        self.assertEqual(g.bind_speech(self.WORDS, (8, 11)), "d-she")

    def test_a_speech_span_that_cuts_a_word_stays_unbound(self):
        self.assertIsNone(self.graph().bind_speech(self.WORDS, (0, 2)))

    def test_a_speech_span_with_text_beyond_the_mention_words_stays_unbound(self):
        # Word 7 is [0, 3); the span also takes the following character.
        self.assertIsNone(self.graph().bind_speech(self.WORDS, (0, 4)))

    def test_a_speech_span_wider_than_a_mention_stays_unbound(self):
        self.assertIsNone(self.graph().bind_speech(self.WORDS, (0, 7)))

    def test_a_speech_span_with_no_graph_mention_stays_unbound(self):
        self.assertIsNone(self.graph().bind_speech(self.WORDS, (4, 7)))

    def test_malformed_speech_words_refuse_the_record(self):
        with self.assertRaises(r.RecordRefusal):
            self.graph().bind_speech([{"index": 7, "start": 3, "end": 3}], (0, 3))

    # --- relations -------------------------------------------------------------------------

    def test_relations_between_classes(self):
        g = self.graph()
        self.assertEqual(g.relation("d-ana", "d-ana"), "Same")
        self.assertEqual(g.relation("v-ben", "d-ana"), "Distinct")
        self.assertEqual(g.relation("d-she", "d-ana"), "Unknown")

    def test_a_contested_class_is_unresolved_even_with_itself(self):
        d = doc()
        d["classes"][1]["contested"] = True
        g = self.graph(d)
        self.assertEqual(g.relation("d-she", "d-she"), "Unresolved")
        self.assertEqual(g.relation("d-she", "d-ana"), "Unresolved")

    def test_an_unknown_class_is_refused(self):
        with self.assertRaises(r.GraphRefusal):
            self.graph().relation("d-ana", "nobody")

    def test_the_synthetic_graph_satisfies_the_v2_schema(self):
        try:
            import jsonschema
        except ImportError:
            self.skipTest("jsonschema is not installed")
        schema_path = (
            Path(__file__).resolve().parents[2]
            / "docs/data/source-identity-graph-v2.schema.json"
        )
        d = doc()
        d.update(inputSha256="0" * 64, inputs=[], bundle="b", presence=[], proposals=[])
        d["relations"][0]["hints"] = []
        errors = list(
            jsonschema.Draft202012Validator(
                json.loads(schema_path.read_text())
            ).iter_errors(d)
        )
        self.assertEqual([e.message for e in errors], [])


if __name__ == "__main__":
    unittest.main()
