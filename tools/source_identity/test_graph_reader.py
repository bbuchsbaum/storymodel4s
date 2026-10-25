#!/usr/bin/env python3
"""Courts for the v2 graph reader (ADR 0028 section 2) on a synthetic graph: Ana and Ben on screen,
an unnamed extra, a spoken name and a pronoun, all on part "p". No story text or private data
(rule 14d).
Run: python3 -m unittest discover -s tools/source_identity
"""
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import graph_reader as r

T = 44100  # ticks per second
P = "p"


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


def mention(d, mid):
    return next(m for m in d["mentions"] if m["id"] == mid)


class ReaderTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, d, raw=None):
        path = Path(self.tmp.name) / "graph.json"
        path.write_text(raw if raw is not None else json.dumps(d))
        return path, hashlib.sha256(path.read_bytes()).hexdigest()

    def graph(self, d=None):
        path, digest = self.write(d or doc())
        return r.load(path, digest, "synthetic", P)

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
            r.load(path, "0" * 64, "synthetic", P)

    def test_another_schema_version_is_refused(self):
        self.assertIn(
            "schemaVersion", self.refused(lambda d: d.update(schemaVersion="x/v1"))
        )

    def test_another_edition_is_refused(self):
        path, digest = self.write(doc())
        with self.assertRaises(r.GraphRefusal):
            r.load(path, digest, "other-edition", P)

    def test_a_repeated_json_key_is_refused(self):
        raw = json.dumps(doc())[:-1] + ', "distinct": []}'
        path, digest = self.write(None, raw)
        with self.assertRaises(r.GraphRefusal) as caught:
            r.load(path, digest, "synthetic", P)
        self.assertIn("repeats a JSON key", str(caught.exception))

    def test_a_missing_section_is_refused(self):
        self.assertIn("missing distinct", self.refused(lambda d: d.pop("distinct")))
        self.assertIn("missing relations", self.refused(lambda d: d.pop("relations")))

    def test_a_malformed_shape_is_a_typed_refusal(self):
        self.assertIn(
            "malformed graph", self.refused(lambda d: d.update(classes=["x"]))
        )

    def test_a_non_positive_timebase_is_refused(self):
        self.assertIn(
            "timebase", self.refused(lambda d: d["edition"].update(timebase=[0, T]))
        )

    def test_an_edition_without_extent_is_refused(self):
        self.assertIn(
            "start before its end", self.refused(lambda d: d["edition"].update(end=0))
        )

    def test_an_unknown_relation_status_is_refused(self):
        self.assertIn(
            "unknown relation",
            self.refused(lambda d: d["relations"][0].update(relation="Maybe")),
        )

    def test_a_class_without_members_is_refused(self):
        self.assertIn("needs its members",
                      self.refused(lambda d: d["classes"][3].update(members=[])))

    def test_a_class_not_named_by_its_least_member_is_refused(self):
        self.assertIn(
            "least member", self.refused(lambda d: d["classes"][0].update(id="v-ana"))
        )

    def test_a_repeated_class_is_refused(self):
        self.assertIn(
            "repeats",
            self.refused(lambda d: d["classes"].append(dict(d["classes"][3]))),
        )

    def test_a_class_without_a_boolean_contested_flag_is_refused(self):
        self.assertIn(
            "contested flag",
            self.refused(lambda d: d["classes"][3].update(contested="no")),
        )

    def test_a_mention_outside_its_stated_class_is_refused(self):
        self.assertIn(
            "not a member",
            self.refused(lambda d: mention(d, "v-ana").update(**{"class": "v-ben"})),
        )

    def test_an_admitted_mention_without_a_class_is_refused(self):
        self.assertIn(
            "not a member",
            self.refused(
                lambda d: mention(d, "x-bad").update(
                    outcome={"status": "admitted"},
                    origin="visual",
                    stream="picture",
                    intervals=[sec(1, 2)],
                )
            ),
        )

    def test_a_mention_in_two_classes_is_refused(self):
        self.assertIn(
            "two classes",
            self.refused(lambda d: d["classes"][2].update(members=["v-ben", "v-cara"])),
        )

    def test_a_class_member_without_an_admitted_mention_is_refused(self):
        self.assertIn(
            "not an admitted mention",
            self.refused(lambda d: d["classes"][3].update(members=["v-cara", "v-zed"])),
        )

    def test_a_repeated_mention_id_is_refused(self):
        self.assertIn(
            "repeats",
            self.refused(lambda d: d["mentions"].append(dict(mention(d, "x-bad")))),
        )

    def test_an_unknown_mention_status_is_refused(self):
        self.assertIn(
            "unknown status",
            self.refused(
                lambda d: mention(d, "x-bad").update(outcome={"status": "admited"})
            ),
        )

    def test_a_negative_interval_is_refused(self):
        self.assertIn(
            "malformed intervals",
            self.refused(lambda d: mention(d, "v-cara").update(intervals=[[-1, 5]])),
        )

    def test_a_dialogue_mention_without_increasing_words_is_refused(self):
        self.assertIn(
            "increasing words", self.refused(lambda d: mention(d, "d-ana").pop("words"))
        )
        self.assertIn(
            "increasing words",
            self.refused(lambda d: mention(d, "d-ana").update(words=[7, 7])),
        )

    def test_a_dialogue_mention_off_the_audio_is_refused(self):
        self.assertIn(
            "audio",
            self.refused(lambda d: mention(d, "d-ana").update(stream="picture")),
        )

    def test_a_visual_mention_with_words_or_off_the_picture_is_refused(self):
        self.assertIn(
            "without words",
            self.refused(lambda d: mention(d, "v-ana").update(words=[1])),
        )
        self.assertIn(
            "without words",
            self.refused(lambda d: mention(d, "v-ana").update(stream="audio")),
        )

    def test_an_unknown_origin_is_refused(self):
        self.assertIn(
            "unknown origin",
            self.refused(lambda d: mention(d, "v-cara").update(origin="narration")),
        )

    def test_a_blank_name_is_refused(self):
        self.assertIn(
            "malformed name",
            self.refused(lambda d: mention(d, "v-cara").update(name="  ")),
        )

    def test_distinct_pairs_must_be_ordered_known_uncontested_and_unique(self):
        self.assertIn(
            "ordered", self.refused(lambda d: d.update(distinct=[["v-ben", "d-ana"]]))
        )
        self.assertIn(
            "unknown class",
            self.refused(lambda d: d.update(distinct=[["d-ana", "v-zed"]])),
        )
        self.assertIn(
            "contested", self.refused(lambda d: d["classes"][2].update(contested=True))
        )
        self.assertIn(
            "repeats",
            self.refused(
                lambda d: d.update(distinct=[["d-ana", "v-ben"], ["d-ana", "v-ben"]])
            ),
        )

    # --- names -----------------------------------------------------------------------------

    def test_names_fold_as_the_core_folds_them(self):
        self.assertEqual(r.fold_name("  Ana \t Ode "), "ana ode")
        self.assertEqual(r.fold_name("STRASSE"), "strasse")
        self.assertNotEqual(
            r.fold_name("straße"), r.fold_name("STRASSE")
        )  # simple mapping
        self.assertEqual(r.fold_name("İ"), "i")  # Character.toLowerCase(U+0130)
        self.assertNotEqual(
            r.fold_name("Ben Ode"), r.fold_name("Ben Ode")
        )  # NBSP is not \s

    # --- time ------------------------------------------------------------------------------

    def test_record_times_convert_to_ticks_exactly(self):
        g = self.graph()
        self.assertEqual(g.ticks("10.5"), 10 * T + T // 2)
        self.assertEqual(g.ticks(10), 10 * T)
        self.assertEqual(g.ticks(Decimal("0.01")), 441)

    def test_a_time_off_the_tick_grid_refuses_the_record_not_the_graph(self):
        g = self.graph()
        with self.assertRaises(r.RecordRefusal):
            g.ticks("10.0000001")
        self.assertEqual(g.ticks(10), 10 * T)

    def test_floats_and_malformed_times_refuse_the_record(self):
        g = self.graph()
        for bad in (10.5, "1_0", " 10", "inf", "NaN", Decimal("Infinity"), True, None):
            with self.assertRaises(r.RecordRefusal, msg=repr(bad)):
                g.ticks(bad)

    def test_a_time_outside_the_edition_refuses_the_record(self):
        with self.assertRaises(r.RecordRefusal):
            self.graph().ticks(21)

    def test_overlap_is_half_open_and_strict(self):
        g = self.graph()
        self.assertEqual([m.id for m in g.visual_mentions(P, 13, 14)], [])
        self.assertEqual(
            [m.id for m in g.visual_mentions(P, 12, 13)], ["v-ana", "v-ben"]
        )

    WORDS = [
        {"index": 7, "start": 0, "end": 3},
        {"index": 8, "start": 4, "end": 7},
        {"index": 9, "start": 8, "end": 11},
    ]

    def test_a_record_on_another_part_refuses_the_record(self):
        g = self.graph()
        with self.assertRaises(r.RecordRefusal):
            g.visual_mentions("other", 12, 13)
        with self.assertRaises(r.RecordRefusal):
            g.bind_speech("other", self.WORDS, (0, 3))

    def test_an_empty_record_interval_refuses_the_record(self):
        with self.assertRaises(r.RecordRefusal):
            self.graph().visual_mentions(P, 12, 12)

    # --- picture binding -------------------------------------------------------------------

    def test_a_picture_span_equal_to_a_listed_name_binds_after_folding(self):
        self.assertEqual(self.graph().bind_picture(P, 10, 11, "  ana "), "d-ana")

    def test_a_relational_description_containing_a_name_stays_unbound(self):
        self.assertIsNone(self.graph().bind_picture(P, 10, 11, "Ana's sister"))

    def test_an_unnamed_description_stays_unbound(self):
        self.assertIsNone(self.graph().bind_picture(P, 15, 16, "the woman"))

    def test_a_blank_span_stays_unbound(self):
        self.assertIsNone(self.graph().bind_picture(P, 15, 16, "  "))

    def test_the_name_of_someone_not_in_the_row_stays_unbound(self):
        self.assertIsNone(self.graph().bind_picture(P, 15, 16, "Ana"))

    def test_one_name_on_two_classes_stays_unbound(self):
        d = doc()
        mention(d, "v-ben")["name"] = "Ana"
        self.assertIsNone(self.graph(d).bind_picture(P, 10, 11, "Ana"))

    # --- speech binding --------------------------------------------------------------------

    def test_a_speech_span_covering_exactly_a_mention_binds_to_its_class(self):
        g = self.graph()
        self.assertEqual(g.bind_speech(P, self.WORDS, (0, 3)), "d-ana")
        self.assertEqual(g.bind_speech(P, self.WORDS, (8, 11)), "d-she")

    def test_a_speech_span_ending_inside_a_word_stays_unbound(self):
        self.assertIsNone(self.graph().bind_speech(P, self.WORDS, (0, 2)))

    def test_a_speech_span_starting_inside_a_word_stays_unbound(self):
        # (5, 11) starts inside word 8 and covers word 9 exactly.
        self.assertIsNone(self.graph().bind_speech(P, self.WORDS, (5, 11)))

    def test_a_speech_span_with_text_beyond_the_mention_words_stays_unbound(self):
        self.assertIsNone(self.graph().bind_speech(P, self.WORDS, (0, 4)))

    def test_a_speech_span_wider_than_a_mention_stays_unbound(self):
        self.assertIsNone(self.graph().bind_speech(P, self.WORDS, (0, 7)))

    def test_a_speech_span_with_no_graph_mention_stays_unbound(self):
        self.assertIsNone(self.graph().bind_speech(P, self.WORDS, (4, 7)))

    def test_two_dialogue_mentions_on_the_same_words_stay_unbound(self):
        d = doc()
        mention(d, "d-she")["words"] = [7]
        self.assertIsNone(self.graph(d).bind_speech(P, self.WORDS, (0, 3)))

    def test_malformed_speech_words_refuse_the_record(self):
        g = self.graph()
        for words in (
            [{"index": 7, "start": 3, "end": 3}],
            [{"index": -1, "start": 0, "end": 3}],
            [{"index": True, "start": 0, "end": 3}],
        ):
            with self.assertRaises(r.RecordRefusal, msg=repr(words)):
                g.bind_speech(P, words, (0, 3))

    def test_overlapping_or_unordered_speech_words_refuse_the_record(self):
        with self.assertRaises(r.RecordRefusal):
            self.graph().bind_speech(
                P,
                [
                    {"index": 7, "start": 0, "end": 3},
                    {"index": 8, "start": 2, "end": 5},
                ],
                (0, 3),
            )

    def test_a_repeated_word_index_refuses_the_record(self):
        with self.assertRaises(r.RecordRefusal):
            self.graph().bind_speech(
                P,
                [
                    {"index": 7, "start": 0, "end": 3},
                    {"index": 7, "start": 4, "end": 7},
                ],
                (4, 7),
            )

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
        errors = list(
            jsonschema.Draft202012Validator(
                json.loads(schema_path.read_text())
            ).iter_errors(d)
        )
        self.assertEqual([e.message for e in errors], [])


if __name__ == "__main__":
    unittest.main()
