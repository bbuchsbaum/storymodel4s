"""Tests for the FilmFestival packet, reply parsing and run logic, on invented data.

Run from the repository root: python3 tools/filmfestival/test_ff_map.py
"""

import json
import os
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_map  # noqa: E402
import ff_packet  # noqa: E402
import test_ff_score  # noqa: E402


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)


class Fixture(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        root = self.dir.name
        head = "segment\trun\tfilm\tscene_number\tstart_s\tdescription"
        lines, segment = [head], 0
        for line in test_ff_score.annotation().splitlines()[1:]:
            run, film, scene, text = line.split("\t")
            segment += 1
            name = film if "cartoon" in film else f"{film[1:]}. Film {film[1:]}"
            lines.append(f"{segment}\t{run}\t{name}\t{scene}\t{segment * 10}\t{text}")
        self.annotation = os.path.join(root, "annotation.tsv")
        write(self.annotation, "\n".join(lines) + "\n")
        self.transcripts = os.path.join(root, "asr")
        for film, clip in ff_packet.CLIPS.items():
            segments = []
            if film == 1:
                segments = [
                    {"text": " Hello there. ", "start_seconds": 61.4, "suspect": False},
                    {"text": "noise", "start_seconds": 70.0, "suspect": True},
                ]
            write(
                os.path.join(self.transcripts, clip, "transcript.json"),
                json.dumps({"segments": segments}),
            )
        gold = "participant\trun\tgold_row\tmovie\tscenes\ttext\n"
        gold += "".join(
            f'p1\trun-01\t{row}\t1\t3\tsaid  "x{row}"\n' for row in (2, 3, 5, 9, 12)
        )
        gold += "p1\trun-02\t2\t1\t3\tlater text\n"
        self.gold = os.path.join(root, "gold.tsv")
        write(self.gold, gold)
        self.packet = ff_packet.render(self.annotation, self.transcripts)


class Packet(Fixture):
    def test_packet_has_ten_films_no_cartoon_and_marked_titles(self):
        lines = self.packet.split("\n")
        self.assertEqual(sum(line.startswith("##### FILM") for line in lines), 10)
        self.assertNotIn("singing snacks", self.packet)
        self.assertEqual(sum(line.endswith("[title card]") for line in lines), 10)
        self.assertIn("##### FILM 1: Film 1 (scenes 2-4)", lines)
        self.assertIn("=== SCENE 2 [title card]", lines)
        self.assertIn("=== SCENE 3", lines)

    def test_row_times_are_from_the_start_of_the_film(self):
        self.assertIn(
            "r2 0:00 X in bold white text fades in on a black screen", self.packet
        )
        self.assertIn('r3 0:10 someone says "hello" in f1 part 1', self.packet)

    def test_speech_is_listed_apart_from_rows_and_silence_is_stated(self):
        lines = self.packet.split("\n")
        at = lines.index(
            "--- FILM 1 dialogue (automatic transcription, not aligned to rows)"
        )
        self.assertEqual(lines[at + 1], "1:01 Hello there.")
        self.assertNotIn("noise", self.packet)
        self.assertIn("--- FILM 2 has no speech", lines)

    def test_units_are_one_session_only_in_row_order(self):
        units = ff_packet.units(self.gold, "p1", "run-01")
        self.assertEqual(
            [(u, row) for u, row, _ in units], [(1, 2), (2, 3), (3, 5), (4, 9), (5, 12)]
        )
        self.assertEqual(units[0][2], 'said "x2"')
        prompts = ff_packet.chunks(self.packet, units, 2)
        self.assertEqual([(lo, hi) for lo, hi, _ in prompts], [(1, 2), (3, 4), (5, 5)])
        self.assertNotIn("later text", prompts[0][2])
        self.assertTrue(
            prompts[2][2].endswith("map every unit numbered 5 through 5 inclusive.")
        )


class Replies(unittest.TestCase):
    def test_three_kinds_of_answer(self):
        item = {
            "narrative": 3,
            "narrative_film": 1,
            "referent": None,
            "referent_film": 2,
        }
        self.assertEqual(
            ff_map.to_prediction(item, "narrative"),
            {"status": "answered", "kind": "scene", "scene": 3, "film_stated": 1},
        )
        self.assertEqual(
            ff_map.to_prediction(item, "referent"),
            {"status": "answered", "kind": "film", "film": 2},
        )
        none = {"referent": None, "referent_film": None}
        self.assertEqual(
            ff_map.to_prediction(none, "referent"),
            {"status": "answered", "kind": "none"},
        )

    def test_a_missing_key_or_wrong_type_is_a_failure_not_a_non_assignment(self):
        self.assertEqual(
            ff_map.to_prediction({"referent": None}, "referent")["reason"],
            "missing-field",
        )
        self.assertEqual(
            ff_map.to_prediction({"referent_film": 1}, "referent")["reason"],
            "missing-field",
        )
        self.assertEqual(
            ff_map.to_prediction({"referent": "3", "referent_film": 1}, "referent")[
                "reason"
            ],
            "invalid-scene",
        )
        self.assertEqual(
            ff_map.to_prediction({"referent": True, "referent_film": 1}, "referent")[
                "reason"
            ],
            "invalid-scene",
        )
        self.assertEqual(
            ff_map.to_prediction(
                {"referent": None, "referent_film": "two"}, "referent"
            )["reason"],
            "invalid-film",
        )

    def test_reply_extraction_matches_the_harness(self):
        self.assertEqual(
            ff_map.parse_json('```json\nHere: {"rows": [r114, r7]}\n```'),
            {"rows": [114, 7]},
        )
        with self.assertRaises(ValueError):
            ff_map.parse_json("no json")
        self.assertEqual(ff_map.suffix(0), "")
        self.assertEqual(
            ff_map.suffix(2), "\n\nReply with strictly valid JSON only (attempt 2)."
        )


class Run(Fixture):
    def run_session(self, replies):
        seen = []

        def fake(prompt, system, cache_dir):
            seen.append(prompt)
            return replies[len(seen) - 1]

        original, ff_map.call = ff_map.call, fake
        self.addCleanup(setattr, ff_map, "call", original)
        chunk, ff_map.CHUNK = ff_map.CHUNK, 3
        self.addCleanup(setattr, ff_map, "CHUNK", chunk)
        return ff_map.map_session(self.packet, self.gold, "p1", "run-01", None), seen

    def test_omitted_units_and_unparsable_chunks_are_different_failures(self):
        ok = {"narrative": 3, "narrative_film": 1, "referent": 3, "referent_film": 1}
        first = json.dumps({"units": [dict(ok, u=1), dict(ok, u=3), dict(ok, u=5)]})
        result, seen = self.run_session([first, "x", "y", "z"])
        units = result["units"]
        self.assertEqual([u["row"] for u in units], [2, 3, 5, 9, 12])
        self.assertEqual(units[0]["referent"]["kind"], "scene")
        self.assertEqual(
            units[1]["referent"], {"status": "failed", "reason": "omitted"}
        )
        self.assertEqual(
            units[3]["referent"], {"status": "failed", "reason": "unparsable-chunk"}
        )
        # Unit 5 was answered by the first chunk's reply, which it does not belong to: dropped.
        self.assertEqual(
            units[4]["referent"], {"status": "failed", "reason": "unparsable-chunk"}
        )
        self.assertEqual(
            result["chunks"],
            [
                {"chunk": 0, "attempts": 1, "accepted": True},
                {"chunk": 1, "attempts": 3, "accepted": False},
            ],
        )
        self.assertTrue(seen[2].endswith("(attempt 1)."))
        self.assertTrue(seen[3].endswith("(attempt 2)."))

    def test_manifest_counts_and_refuses_an_oversized_prompt(self):
        root = os.path.join(self.dir.name, "data")
        write(
            os.path.join(root, "filmfestival/derived/gold-scenematched.tsv"),
            pathlib.Path(self.gold).read_text(),
        )
        write(
            os.path.join(root, "filmfestival/derived/annotation-JL.tsv"),
            pathlib.Path(self.annotation).read_text(),
        )
        base = os.path.join(
            root, "transcription/unified-v1/full/whisper_mlx/filmfestival"
        )
        for clip in ff_packet.CLIPS.values():
            write(
                os.path.join(base, clip, "transcript.json"),
                json.dumps({"segments": []}),
            )
        import ff_gold

        expected, ff_gold.EXPECTED_ROWS = ff_gold.EXPECTED_ROWS, 6
        self.addCleanup(setattr, ff_gold, "EXPECTED_ROWS", expected)
        original = ff_gold.load_gold
        ff_gold.load_gold = lambda path, expected_rows=6: original(path, expected_rows)
        self.addCleanup(setattr, ff_gold, "load_gold", original)
        _, doc = ff_map.manifest(root)
        self.assertEqual(doc["calls"], 2)
        self.assertEqual([s["units"] for s in doc["sessions"]], [5, 1])
        self.assertEqual(doc["validSceneTargets"], 20)
        limit, ff_map.MAX_PROMPT_CHARS = ff_map.MAX_PROMPT_CHARS, 100
        self.addCleanup(setattr, ff_map, "MAX_PROMPT_CHARS", limit)
        with self.assertRaises(ff_gold.GoldError):
            ff_map.manifest(root)


if __name__ == "__main__":
    unittest.main()
