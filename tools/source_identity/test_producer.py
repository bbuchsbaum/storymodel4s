#!/usr/bin/env python3
"""Courts for the source-identity producer (ADR 0027 sections 1-4, 7) on a synthetic film.

Two people, Ana and Ben, and one role label. No story text, recall or private data enters these
fixtures (rule 14d); the media probe is replaced by fixed stream facts.
Run: python3 -m unittest discover -s tools/source_identity
"""
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest

import producer as p

HEADER = [
    "Segment Number",
    "Start Time (s) ",
    "End Time (s) ",
    "Scene Details - A Level ",
    "Name - All",
    "Name - Focus",
    "Name - Speaking",
]
# Rows 10-13 over 0-12 s. Row 11 is one person, Ana, with Ben speaking; row 12 has a role label.
ROWS = [
    (10, 0, 3, "Ana, Ben", "Ben"),
    (11, 3, 6, "Ana", "Ben"),
    (12, 6, 9, "Ben, 2 Guards", "Ben"),
    (13, 9, 12, "Ana", ""),
]
AUDIO_END = 12 * p.TICKS
FACTS = {
    "picture": {"end": 12 * p.TICKS, "sha256": "a" * 64},
    "audio": {"end": AUDIO_END, "sha256": "b" * 64},
}


def word(i, text, start, end, segment, probability=0.9):
    return {
        "index": i,
        "text": text,
        "start_seconds": start,
        "end_seconds": end,
        "segment_index": segment,
        "probability": probability,
    }


SEGMENTS = [
    {
        "index": 0,
        "text": "",
        "start_seconds": 0.0,
        "end_seconds": 2.9,
        "kind": "decoder",
        "suspect": False,
        "diagnostics": {},
    },
    {
        "index": 1,
        "text": "",
        "start_seconds": 3.0,
        "end_seconds": 5.9,
        "kind": "decoder",
        "suspect": False,
        "diagnostics": {},
    },
    {
        "index": 2,
        "text": "",
        "start_seconds": 6.0,
        "end_seconds": 8.9,
        "kind": "decoder",
        "suspect": False,
        "diagnostics": {},
    },
    {
        "index": 3,
        "text": "",
        "start_seconds": 9.0,
        "end_seconds": 11.9,
        "kind": "decoder",
        "suspect": False,
        "diagnostics": {},
    },
]
WORDS = [
    word(0, " Ana", 0.5, 0.9, 0),
    word(1, " Ben,", 1.0, 1.4, 0),
    word(
        2, " she's", 4.0, 4.3, 1
    ),  # inside row 11 with margin, Ana alone, Ben speaking
    word(3, " woman", 4.5, 4.9, 1),
    word(4, " her", 3.1, 3.3, 1),  # within 0.5 s of row 11's start
    word(5, " he", 7.0, 7.2, 2),  # row 12: Ben speaking
    word(6, " she", 10.0, 10.2, 3),  # row 13: no speaker listed
    word(
        7, " Ana", 10.4, 10.8, 3, probability=0.2
    ),  # a name, admitted despite probability
    word(8, " him", 10.9, 11.1, 3, probability=0.3),  # a pronoun below the guard
]


class Fixture:
    def __init__(self, root: Path):
        self.root = root
        self.rows = list(ROWS)
        self.words = copy.deepcopy(WORDS)
        self.segments = copy.deepcopy(SEGMENTS)
        self.labels = {
            "schemaVersion": p.LABELS_SCHEMA,
            "characters": ["Ana", "Ben"],
            "roleLabels": {"2 Guards": 2},
        }
        self.media = b"synthetic media bytes"
        self.extra = []
        self.annotation_dir = root

    def file(self, name, data, where=None):
        path = (where or self.root) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data if isinstance(data, bytes) else data.encode("utf-8"))
        return path

    def run(self):
        media_sha = hashlib.sha256(self.media).hexdigest()
        tsv = (
            "\t".join(HEADER)
            + "\n"
            + "".join(
                f"{n}\t{a}\t{z}\tsynthetic\t{names}\t\t{speaking}\n"
                for n, a, z, names, speaking in self.rows
            )
        )
        transcript = {
            "source": {"sha256": media_sha},
            "input": {"sha256": media_sha},
            "clock": {
                "origin": "source_media",
                "input_start_seconds": 0,
                "input_duration_seconds": AUDIO_END / p.TICKS,
            },
            "words": self.words,
            "segments": self.segments,
        }
        files = {
            "media": self.file("media.m4v", self.media),
            "annotation": self.file("annotation.tsv", tsv, self.annotation_dir),
            "asr-transcript": self.file("transcript.json", json.dumps(transcript)),
            "role-labels": self.file("labels.json", json.dumps(self.labels)),
        }
        inputs = [
            {
                "role": r,
                "path": str(f),
                "sha256": hashlib.sha256(f.read_bytes()).hexdigest(),
            }
            for r, f in files.items()
        ] + self.extra
        return {
            "schemaVersion": p.RUN_SCHEMA,
            "edition": "synthetic-edition",
            "rows": [10, 13],
            "inputs": inputs,
        }


class ProducerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.fx = Fixture(Path(self.tmp.name))

    def tearDown(self):
        self.tmp.cleanup()

    def produce(self):
        return p.produce(self.fx.run(), probe=lambda _: FACTS)

    def refusal(self):
        with self.assertRaises(p.Refusal) as caught:
            self.produce()
        return str(caught.exception)

    def mention_ids(self, doc):
        return {m["id"] for m in doc["mentions"]}

    def guard(self, doc, mention, target):
        return p.guard_deixis(doc["producer"], mention, target)

    # --- section 1 ---------------------------------------------------------------------------

    def test_a_recall_path_is_refused_before_any_byte_is_read(self):
        self.fx.annotation_dir = self.fx.root / "Recall"
        self.assertIn("recall path refused", self.refusal())

    def test_control_the_same_file_outside_a_recall_path_is_admitted(self):
        self.fx.annotation_dir = self.fx.root / "annotations"
        self.produce()

    def test_an_input_role_outside_the_list_is_refused(self):
        self.fx.extra = [
            {"role": "notes", "path": str(self.fx.root / "x"), "sha256": "0" * 64}
        ]
        self.assertIn("input role notes is not admitted", self.refusal())

    def test_a_role_listed_twice_is_refused(self):
        run = self.fx.run()
        run["inputs"].append(dict(run["inputs"][0]))
        with self.assertRaises(p.Refusal) as caught:
            p.produce(run, probe=lambda _: FACTS)
        self.assertIn("listed twice", str(caught.exception))

    def test_bytes_that_differ_from_the_pin_are_refused(self):
        run = self.fx.run()
        run["inputs"][0]["sha256"] = "0" * 64
        with self.assertRaises(p.Refusal) as caught:
            p.produce(run, probe=lambda _: FACTS)
        self.assertIn("pinned", str(caught.exception))

    def test_a_recognizer_run_over_other_bytes_is_refused(self):
        run = self.fx.run()
        entry = next(e for e in run["inputs"] if e["role"] == "asr-transcript")
        path = Path(entry["path"])
        transcript = json.loads(path.read_text())
        transcript["source"]["sha256"] = "c" * 64
        path.write_text(json.dumps(transcript))
        entry["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        with self.assertRaises(p.Refusal) as caught:
            p.produce(run, probe=lambda _: FACTS)
        self.assertIn("recognizer source sha256", str(caught.exception))

    def test_a_recognizer_duration_off_the_audio_stream_is_refused(self):
        facts = copy.deepcopy(FACTS)
        facts["audio"]["end"] = AUDIO_END + p.TICKS
        with self.assertRaises(p.Refusal) as caught:
            p.produce(self.fx.run(), probe=lambda _: facts)
        self.assertIn("duration differs", str(caught.exception))

    # --- section 2 ---------------------------------------------------------------------------

    def test_a_decoder_segment_over_30_seconds_yields_no_mention(self):
        self.fx.segments[1]["end_seconds"] = 33.1
        doc, _ = self.produce()
        self.assertFalse({"d-w2", "d-w3", "d-w4"} & self.mention_ids(doc))
        self.assertIn(
            {"kind": "segment-too-long", "segment": 1, "seconds": "30.1"},
            doc["producer"]["refusals"],
        )

    def test_control_a_29_second_segment_yields_its_mentions(self):
        self.fx.segments[1]["end_seconds"] = 32.0
        doc, _ = self.produce()
        self.assertTrue({"d-w2", "d-w3"} <= self.mention_ids(doc))

    def test_a_pronoun_below_the_probability_guard_yields_no_mention(self):
        doc, _ = self.produce()
        self.assertNotIn("d-w8", self.mention_ids(doc))
        self.assertIn(
            {"kind": "low-probability", "word": 8}, doc["producer"]["refusals"]
        )

    def test_a_name_is_admitted_whatever_its_probability(self):
        doc, _ = self.produce()
        named = {m["id"]: m.get("name") for m in doc["mentions"]}
        self.assertEqual(named["d-w7"], "Ana")

    def test_a_contraction_with_punctuation_is_a_pronoun(self):
        doc, _ = self.produce()
        classes = {m["id"]: m["class"] for m in doc["producer"]["dialogue"]}
        self.assertEqual(classes["d-w2"], "pronoun")
        self.assertEqual(classes["d-w1"], "name")

    def test_a_role_label_yields_unnamed_mentions_by_its_count(self):
        doc, _ = self.produce()
        guards = [m for m in doc["mentions"] if m["id"].startswith("v-r12-2-guards")]
        self.assertEqual(
            [m["id"] for m in guards], ["v-r12-2-guards-1", "v-r12-2-guards-2"]
        )
        self.assertTrue(all("name" not in m for m in guards))

    def test_an_unlisted_entry_withholds_the_row_presence_claim(self):
        self.fx.rows[1] = (11, 3, 6, "Ana, Stranger", "Ben")
        doc, _ = self.produce()
        rows = {r["row"]: r for r in doc["producer"]["rows"]}
        self.assertEqual(rows[11]["presence"], "withheld")
        self.assertEqual(len(doc["presence"]), 3)
        self.assertEqual(
            self.guard(doc, "d-w2", "v-r11-ana"),
            (False, "the mention is outside every certified row"),
        )

    # --- section 3 and 4 ---------------------------------------------------------------------

    def test_control_a_pronoun_inside_a_one_person_row_with_another_speaker_passes(
        self,
    ):
        doc, _ = self.produce()
        self.assertEqual(self.guard(doc, "d-w2", "v-r11-ana"), (True, None))

    def test_a_pronoun_within_the_margin_of_a_row_edge_is_not_deictic(self):
        doc, _ = self.produce()
        self.assertEqual(
            self.guard(doc, "d-w4", "v-r11-ana"),
            (False, "the mention is outside every certified row"),
        )

    def test_a_description_noun_is_never_deictic(self):
        doc, _ = self.produce()
        self.assertEqual(
            self.guard(doc, "d-w3", "v-r11-ana"),
            (False, "only a third-person pronoun may be deictic"),
        )

    def test_deixis_to_the_speaker_is_refused(self):
        doc, _ = self.produce()
        self.assertEqual(
            self.guard(doc, "d-w5", "v-r12-ben"), (False, "the target is the speaker")
        )

    def test_deixis_in_a_row_without_a_listed_speaker_is_refused(self):
        doc, _ = self.produce()
        self.assertEqual(
            self.guard(doc, "d-w6", "v-r13-ana"), (False, "the row lists no speaker")
        )

    def test_deixis_to_one_of_several_speakers_is_refused(self):
        self.fx.rows[2] = (12, 6, 9, "Ben, Ana", "Ben, Ana")
        doc, _ = self.produce()
        self.assertEqual(
            self.guard(doc, "d-w5", "v-r12-ana"),
            (False, "the target is among several listed speakers"),
        )

    def test_control_deixis_to_a_role_label_in_a_speaking_row_passes(self):
        doc, _ = self.produce()
        self.assertEqual(self.guard(doc, "d-w5", "v-r12-2-guards-1"), (True, None))

    def test_each_later_mention_of_a_name_is_proposed_as_its_first(self):
        doc, _ = self.produce()
        ana = [(x["left"], x["right"]) for x in doc["proposals"] if x["name"] == "Ana"]
        # Row 10's visual mention starts at 0 s, before the spoken name at 0.5 s.
        self.assertEqual(
            ana,
            [("v-r10-ana", "d-w0"), ("v-r10-ana", "v-r11-ana"), ("v-r10-ana", "v-r13-ana"),
             ("v-r10-ana", "d-w7")],
        )

    # --- section 7 ---------------------------------------------------------------------------

    def test_the_universe_pairs_pronouns_and_nouns_with_overlapping_and_adjacent_rows(
        self,
    ):
        _, uni = self.produce()
        mine = sorted(x["visual"] for x in uni["pairs"] if x["mention"] == "d-w3")
        self.assertEqual(
            mine,
            [
                "v-r10-ana",
                "v-r10-ben",
                "v-r11-ana",
                "v-r12-2-guards-1",
                "v-r12-2-guards-2",
                "v-r12-ben",
            ],
        )
        self.assertFalse(any(x["mention"] in ("d-w0", "d-w7") for x in uni["pairs"]))

    def test_the_output_does_not_depend_on_word_order(self):
        doc, uni = self.produce()
        self.fx.words.reverse()
        again, uni2 = self.produce()
        doc["inputs"] = again["inputs"] = None
        self.assertEqual((doc, uni), (again, uni2))

    def test_write_refuses_an_existing_output(self):
        doc, uni = self.produce()
        out = self.fx.root / "out"
        p.write(out, doc, uni)
        self.assertTrue((out / "inputs.json").exists())
        with self.assertRaises(p.Refusal):
            p.write(out, doc, uni)


if __name__ == "__main__":
    unittest.main()
