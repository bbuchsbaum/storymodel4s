"""Tests for the FilmFestival packet, reply parsing and run logic, on invented data.

Run from the repository root: python3 tools/filmfestival/test_ff_map.py
"""

import argparse
import json
import os
import pathlib
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_codes  # noqa: E402
import ff_gold  # noqa: E402
import ff_map  # noqa: E402
import ff_packet  # noqa: E402
import test_ff_score  # noqa: E402


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    pathlib.Path(path).write_text(text, encoding="utf-8")


def transcript(segments, outcome="complete"):
    return json.dumps({"outcome": outcome, "failure": None, "segments": segments})


class Fixture(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.root = os.path.join(self.dir.name, "data")
        head = "segment\trun\tfilm\tscene_number\tstart_s\tdescription"
        lines, segment = [head], 0
        for line in test_ff_score.annotation().splitlines()[1:]:
            run, film, scene, text = line.split("\t")
            segment += 1
            name = film if "cartoon" in film else f"{film[1:]}. Film {film[1:]}"
            lines.append(f"{segment}\t{run}\t{name}\t{scene}\t{segment * 10}\t{text}")
        self.gold, self.annotation, self.transcripts = ff_map.paths(self.root)
        write(self.annotation, "\n".join(lines) + "\n")
        for film, clip in ff_packet.CLIPS.items():
            segments = []
            if film == 1:
                segments = [
                    {"text": " Hello there. ", "start_seconds": 61.4, "suspect": False},
                    {"text": "noise", "start_seconds": 70.0, "suspect": True},
                ]
            write(
                os.path.join(self.transcripts, clip, "transcript.json"),
                transcript(segments),
            )
        gold = "participant\trun\tgold_row\tmovie\tscenes\ttext\n"
        gold += "".join(
            f'p1\trun-01\t{row}\t1\t3\tsaid  "x{row}"\n' for row in (2, 3, 5, 9, 12)
        )
        gold += "p1\trun-02\t2\t1\t3\tlater text\n"
        write(self.gold, gold)
        self.codes = "participant\trun\tgold_row\tmovie\tscenes_raw\n"
        self.codes += "".join(f"p1\trun-01\t{row}\t1\t3\n" for row in (2, 3, 5, 9, 12))
        self.codes += "p1\trun-02\t2\t1\t3\n"
        self.packet = ff_packet.render(self.annotation, self.transcripts)
        self.patch(ff_map, "codes_text", lambda data_root: self.codes)
        original = ff_gold.load_gold
        self.patch(
            ff_gold,
            "load_gold",
            lambda path, codes: original(path, codes, expected_rows=6),
        )

    def patch(self, owner, name, value):
        self.addCleanup(setattr, owner, name, getattr(owner, name))
        setattr(owner, name, value)


class Packet(Fixture):
    def test_packet_has_ten_films_no_cartoon_and_marked_titles(self):
        lines = self.packet.split("\n")
        self.assertEqual(sum(line.startswith("##### FILM") for line in lines), 10)
        self.assertNotIn("singing snacks", self.packet)
        # Film 3's first scene has a content row too, so it is not marked.
        self.assertEqual(sum(line.endswith("[title card]") for line in lines), 9)
        self.assertIn("##### FILM 1: Film 1 (scenes 2-4)", lines)
        self.assertIn("=== SCENE 2 [title card]", lines)
        self.assertIn("=== SCENE 3", lines)
        self.assertIn("=== SCENE 8", lines)

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

    def test_a_transcription_that_did_not_complete_is_refused_not_rendered_as_silence(
        self,
    ):
        path = os.path.join(self.transcripts, ff_packet.CLIPS[2], "transcript.json")
        write(path, transcript([], outcome="failed"))
        with self.assertRaises(ff_gold.GoldError):
            ff_packet.render(self.annotation, self.transcripts)

    def test_units_are_one_session_only_and_every_prompt_shows_the_whole_session(self):
        units = ff_packet.units(self.gold, "p1", "run-01")
        self.assertEqual(
            [(u, row) for u, row, _ in units], [(1, 2), (2, 3), (3, 5), (4, 9), (5, 12)]
        )
        self.assertEqual(units[0][2], 'said "x2"')
        prompts = ff_packet.chunks(self.packet, units, 2)
        self.assertEqual([(lo, hi) for lo, hi, _ in prompts], [(1, 2), (3, 4), (5, 5)])
        for _, _, prompt in prompts:
            self.assertNotIn("later text", prompt)
            for u, _, text in units:
                self.assertIn(f"[{u}] {text}", prompt)
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
        def reason(item):
            return ff_map.to_prediction(item, "referent").get("reason")

        self.assertEqual(reason({"referent": None}), "missing-field")
        self.assertEqual(reason({"referent_film": 1}), "missing-field")
        self.assertEqual(reason({"referent": "3", "referent_film": 1}), "invalid-scene")
        self.assertEqual(
            reason({"referent": True, "referent_film": 1}), "invalid-scene"
        )
        self.assertEqual(
            reason({"referent": None, "referent_film": "two"}), "invalid-film"
        )
        self.assertEqual(
            reason({"referent": None, "referent_film": True}), "invalid-film"
        )

    def test_reply_extraction_matches_the_harness(self):
        self.assertEqual(
            ff_map.parse_json('```json\nHere: {"rows": [r114, r7]}\n```'),
            {"rows": [114, 7]},
        )
        self.assertEqual(ff_map.suffix(0), "")
        self.assertEqual(
            ff_map.suffix(2), "\n\nReply with strictly valid JSON only (attempt 2)."
        )

    def test_unusable_replies_raise_a_value_error_never_another_kind(self):
        for text in ("no json", '```json {"units": []} ```', "```", "{broken"):
            with self.assertRaises(ValueError):
                ff_map.parse_json(text)


class Run(Fixture):
    def run_session(self, replies):
        seen = []

        def fake(model, prompt, system, cache_dir, version):
            seen.append(prompt)
            return {"result": replies[len(seen) - 1]}

        self.patch(ff_map, "call", fake)
        self.patch(ff_map, "CHUNK", 3)
        return ff_map.map_session(
            ff_map.PRIMARY, self.packet, self.gold, "p1", "run-01", None, "cli 1.0"
        ), seen

    def test_omitted_units_and_unparsable_chunks_are_different_failures(self):
        ok = {"narrative": 3, "narrative_film": 1, "referent": 3, "referent_film": 1}
        first = json.dumps({"units": [dict(ok, u=1), dict(ok, u=3), dict(ok, u=5)]})
        result, seen = self.run_session(
            [first, "x", '```json {"units": []} ```', '{"units": 7}']
        )
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
            [
                (c["attempts"], c["accepted"], c["itemsOutsideChunk"])
                for c in result["chunks"]
            ],
            [(1, True, 1), (3, False, 0)],
        )
        self.assertTrue(seen[2].endswith("(attempt 1)."))
        self.assertTrue(seen[3].endswith("(attempt 2)."))
        self.assertEqual(
            (result["model"], result["cliVersion"]), (ff_map.PRIMARY, "cli 1.0")
        )

    def test_referent_and_narrative_are_not_swapped_and_repeats_are_counted(self):
        item = {
            "narrative": 3,
            "narrative_film": 1,
            "referent": None,
            "referent_film": 1,
        }
        reply = json.dumps(
            {"units": [dict(item, u=1), dict(item, u=1, referent=4), dict(item, u=2)]}
        )
        result, _ = self.run_session([reply, json.dumps({"units": []})])
        first = result["units"][0]
        self.assertEqual(first["narrative"]["scene"], 3)
        self.assertEqual(first["referent"]["scene"], 4)
        self.assertEqual(
            result["units"][1]["referent"],
            {"status": "answered", "kind": "film", "film": 1},
        )
        self.assertEqual(result["chunks"][0]["repeatedUnits"], 1)

    def test_manifest_counts_and_refuses_an_oversized_prompt(self):
        _, codes, doc = ff_map.manifest(self.root, ff_map.PRIMARY)
        self.assertEqual(codes, self.codes)
        self.assertEqual(doc["calls"], 2)
        self.assertEqual([s["units"] for s in doc["sessions"]], [5, 1])
        self.assertEqual(doc["validSceneTargets"], 21)
        self.assertEqual(doc["model"], "claude-opus-5-5")
        self.assertEqual(doc["codesSha256"], ff_packet.sha(self.codes))
        self.patch(ff_map, "MAX_PROMPT_CHARS", 100)
        with self.assertRaises(ff_gold.GoldError):
            ff_map.manifest(self.root, ff_map.PRIMARY)

    def test_run_refuses_without_the_frozen_manifest_or_with_rerouted_calls(self):
        _, _, doc = ff_map.manifest(self.root, ff_map.PRIMARY)
        tracked = os.path.join(self.dir.name, "tracked.json")
        write(tracked, json.dumps(doc))
        out = os.path.join(self.dir.name, "out")
        args = argparse.Namespace(manifest=tracked, out_dir=out)
        todo = [("p1", "run-01"), ("p1", "run-02")]
        self.assertIsNone(ff_map.refusal(args, doc, {}, todo))
        missing = argparse.Namespace(manifest=None, out_dir=out)
        self.assertIn("tracked manifest", ff_map.refusal(missing, doc, {}, todo))
        self.assertIn(
            "differ", ff_map.refusal(args, dict(doc, chunk=doc["chunk"] + 1), {}, todo)
        )
        routed = {"ANTHROPIC_BASE_URL": "x"}
        self.assertIn("ANTHROPIC_BASE_URL", ff_map.refusal(args, doc, routed, todo))
        self.assertIn(
            "unknown participant-session", ff_map.refusal(args, doc, {}, None)
        )

    def test_a_finished_session_is_never_overwritten_but_others_may_still_run(self):
        _, _, doc = ff_map.manifest(self.root, ff_map.PRIMARY)
        tracked = os.path.join(self.dir.name, "tracked.json")
        write(tracked, json.dumps(doc))
        out = os.path.join(self.dir.name, "out")
        args = argparse.Namespace(manifest=tracked, out_dir=out)
        write(os.path.join(out, "replies", "p1_run-01.json"), "{}")
        self.assertIn(
            "p1_run-01 already exists",
            ff_map.refusal(args, doc, {}, [("p1", "run-01")]),
        )
        self.assertIsNone(ff_map.refusal(args, doc, {}, [("p1", "run-02")]))

    def test_the_two_arms_send_identical_prompts_and_differ_only_in_the_model(self):
        opus, haiku = ff_map.ARMS
        self.assertEqual((opus, haiku), ("claude-opus-5-5", "claude-haiku-5-5"))
        _, _, a = ff_map.manifest(self.root, opus)
        _, _, b = ff_map.manifest(self.root, haiku)
        self.assertEqual((a["primaryArm"], b["primaryArm"]), (True, False))
        differing = {k for k in a if a[k] != b[k]}
        self.assertEqual(differing, {"model", "primaryArm"})

    def test_a_reply_must_come_from_the_arm_that_was_asked(self):
        def fake_run(cmd, **kwargs):
            body = {"result": "ok", "modelUsage": {"claude-opus-5-5": {}}}
            return argparse.Namespace(stdout=json.dumps(body), returncode=0)

        self.patch(ff_map.subprocess, "run", fake_run)
        cache = os.path.join(self.dir.name, "cache")
        os.makedirs(cache)
        with self.assertRaises(ff_map.ProtocolFailure):
            ff_map.call("claude-haiku-5-5", "P", "S", cache, "cli 9")
        self.assertEqual(os.listdir(cache), [])

    def test_only_selects_named_sessions_and_refuses_unknown_ones(self):
        todo = [("p1", "run-01"), ("p1", "run-02")]
        self.assertEqual(ff_map.selected(todo, None), todo)
        self.assertEqual(ff_map.selected(todo, "p1_run-02"), [("p1", "run-02")])
        self.assertIsNone(ff_map.selected(todo, "p1_run-02,p9_run-01"))

    def test_the_api_key_is_removed_and_the_cache_key_depends_on_the_prompt(self):
        env = ff_map.child_environment({"ANTHROPIC_API_KEY": "k", "HOME": "/h"})
        self.assertEqual(env, {"HOME": "/h"})
        opus, haiku = ff_map.ARMS
        self.assertNotEqual(
            ff_map.cache_key(opus, "s", "a"), ff_map.cache_key(opus, "s", "b")
        )
        self.assertNotEqual(
            ff_map.cache_key(opus, "s", "a"), ff_map.cache_key(opus, "t", "a")
        )
        self.assertNotEqual(
            ff_map.cache_key(opus, "s", "a"), ff_map.cache_key(haiku, "s", "a")
        )


def workbook(path, rows):
    """A minimal xlsx whose first sheet holds `rows` of shared-string cells."""
    strings = []

    def ref(text):
        if text not in strings:
            strings.append(text)
        return strings.index(text)

    ns = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'
    body = ""
    for n, cells in enumerate(rows, start=1):
        body += f'<row r="{n}">'
        for letter, text in zip("ABC", cells):
            if text is not None:
                body += f'<c r="{letter}{n}" t="s"><v>{ref(text)}</v></c>'
        body += "</row>"
    shared = "".join(f"<si><t>{t}</t></si>" for t in strings)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(
            "xl/worksheets/sheet1.xml",
            f"<worksheet {ns}><sheetData>{body}</sheetData></worksheet>",
        )
        z.writestr("xl/sharedStrings.xml", f"<sst {ns}>{shared}</sst>")


class Codes(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        head = ("movies", "scenes", "text")
        workbook(
            os.path.join(self.dir.name, "sub-01_recall.xlsx"),
            [head, ("3", "3000", "a"), ("3", "-1", "b")],
        )
        workbook(
            os.path.join(self.dir.name, "sub-02_recall1.xlsx"),
            [head, ("11", "1, 107", "c")],
        )
        workbook(
            os.path.join(self.dir.name, "sub-02_recall2.xlsx"),
            [head, ("-1", "-1", "d"), ("5", "81,82", "e")],
        )
        workbook(
            os.path.join(self.dir.name, "_scenematching_codingscheme.xlsx"), [("x",)]
        )

    def test_rows_carry_participant_session_excel_row_and_raw_codes(self):
        self.assertEqual(
            ff_codes.rows(self.dir.name),
            [
                ("sub-01", "run-01", 2, 3, "3000"),
                ("sub-01", "run-01", 3, 3, "-1"),
                ("sub-02", "run-01", 2, 11, "1, 107"),
                ("sub-02", "run-02", 2, -1, "-1"),
                ("sub-02", "run-02", 3, 5, "81,82"),
            ],
        )

    def test_an_unexpected_header_is_refused(self):
        workbook(
            os.path.join(self.dir.name, "sub-03_recall.xlsx"),
            [("scenes", "movies"), ("1", "2")],
        )
        with self.assertRaises(ValueError):
            ff_codes.rows(self.dir.name)

    def test_a_film_code_with_a_blank_scene_cell_cannot_be_classified(self):
        workbook(
            os.path.join(self.dir.name, "sub-04_recall.xlsx"),
            [("movies", "scenes"), ("3", None)],
        )
        blank = [r for r in ff_codes.rows(self.dir.name) if r[0] == "sub-04"]
        self.assertEqual(blank, [("sub-04", "run-01", 2, 3, "")])
        with self.assertRaises(ff_gold.GoldError):
            ff_gold.classify(3, [], "")


class Call(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def cli(self, model_usage, result="ok"):
        commands = []

        def fake_run(cmd, **kwargs):
            commands.append((cmd, kwargs))
            body = {
                "result": result,
                "modelUsage": model_usage,
                "usage": {"input_tokens": 1},
            }
            return argparse.Namespace(stdout=json.dumps(body), returncode=0)

        self.addCleanup(setattr, ff_map.subprocess, "run", ff_map.subprocess.run)
        ff_map.subprocess.run = fake_run
        return commands

    def test_a_reply_is_recorded_whole_with_the_cli_version_and_reused(self):
        commands = self.cli({ff_map.PRIMARY: {}})
        os.environ["ANTHROPIC_API_KEY"] = "k"
        self.addCleanup(os.environ.pop, "ANTHROPIC_API_KEY", None)
        record = ff_map.call(ff_map.PRIMARY, "P", "S", self.dir.name, "cli 9")
        self.assertEqual((record["result"], record["cliVersion"]), ("ok", "cli 9"))
        self.assertEqual(record["modelUsage"], [ff_map.PRIMARY])
        self.assertEqual(
            os.listdir(self.dir.name),
            [ff_map.cache_key(ff_map.PRIMARY, "S", "P") + ".json"],
        )
        cmd, kwargs = commands[0]
        self.assertEqual(cmd[:4], ["claude", "-p", "--model", ff_map.PRIMARY])
        self.assertNotIn("ANTHROPIC_API_KEY", kwargs["env"])
        self.assertEqual(kwargs["input"], "P")
        self.assertEqual(
            ff_map.call(ff_map.PRIMARY, "P", "S", self.dir.name, "cli 9"), record
        )
        self.assertEqual(len(commands), 1)

    def test_a_reply_from_another_model_is_a_protocol_failure_and_is_not_recorded(self):
        for usage in (
            {"claude-haiku-5-5": {}},
            {ff_map.PRIMARY: {}, "claude-haiku-5-5": {}},
            {},
        ):
            self.cli(usage)
            with self.assertRaises(ff_map.ProtocolFailure):
                ff_map.call(ff_map.PRIMARY, "P", "S", self.dir.name, "cli 9")
            self.assertEqual(os.listdir(self.dir.name), [])


if __name__ == "__main__":
    unittest.main()
