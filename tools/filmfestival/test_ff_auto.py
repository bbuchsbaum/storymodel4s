"""Tests for the input conditions of the run script, on invented data. No model is called.

Run from the repository root: python3 tools/filmfestival/test_ff_auto.py
"""

import copy
import hashlib
import io
import json
import os
import pathlib
import re
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_auto_packet  # noqa: E402
import ff_caption  # noqa: E402
import ff_compare  # noqa: E402
import ff_frames  # noqa: E402
import ff_gold  # noqa: E402
import ff_map  # noqa: E402
import ff_packet  # noqa: E402
import ff_score  # noqa: E402
import ff_time  # noqa: E402
import test_ff_time  # noqa: E402

# Scene ids are 7001-7033 and row ids 9001-9036, so neither can be mistaken for a time.
SCENES, SEGMENTS = 7000, 9000
EVIDENCE = pathlib.Path(__file__).parents[2] / "docs/refactor/evidence"
TRACKED = EVIDENCE / "filmfestival-transfer-20261109"


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    pathlib.Path(path).write_text(text, encoding="utf-8")


class Fixture(unittest.TestCase):
    """A data root with the invented films of test_ff_time, frames, descriptions and speech."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.root = os.path.join(self.dir.name, "data")
        self.gold, self.annotation, self.transcripts = ff_map.paths(self.root)
        write(
            self.annotation,
            test_ff_time.annotation(test_ff_time.ROWS, SCENES, SEGMENTS),
        )
        self.patch(ff_time, "CLOCK", copy.deepcopy(test_ff_time.CLOCK))
        self.frames_path, self.captions_path = ff_map.auto_paths(self.root)
        films = []
        for film, cover in sorted(ff_packet.CLIPS.items()):
            count = int(test_ff_time.DURATIONS[film] // 2)
            frames = [
                {
                    "file": f"frames/{cover}/f{k + 1:04d}.jpg",
                    "seconds": 2 * k,
                    "sha256": hashlib.sha256(f"{film}-{k}".encode()).hexdigest(),
                }
                for k in range(count)
            ]
            films.append(
                {
                    "film": film,
                    "cover": cover,
                    "clipSha256": f"clip-{film}",
                    "clipSeconds": test_ff_time.DURATIONS[film],
                    "frames": frames,
                }
            )
            segments = []
            if film == 1:
                segments = [
                    {"text": " Hello there. ", "start_seconds": 3.4, "suspect": False},
                    {"text": "noise", "start_seconds": 6.0, "suspect": True},
                    {"text": 'He said "go"', "start_seconds": 4.0, "suspect": False},
                ]
            self.transcript(film, segments)
        self.frames_doc = {"schema": ff_frames.SCHEMA, "films": films}
        write(self.frames_path, json.dumps(self.frames_doc))
        self.captions = {
            "schema": ff_caption.CAPTIONS_SCHEMA,
            "model": ff_caption.MODEL,
            "instructionSha256": ff_packet.sha(ff_caption.SYSTEM),
            "framesSha256": ff_frames.file_sha(self.frames_path),
            "films": {
                str(entry["film"]): [
                    {
                        "seconds": frame["seconds"],
                        "sha256": frame["sha256"],
                        "description": f"QQ frame {entry['film']} at {frame['seconds']}",
                    }
                    for frame in entry["frames"]
                ]
                for entry in films
            },
        }
        self.write_captions()
        rows = [(2, "7003"), (3, "7004"), (5, "7003 7004"), (9, "7005"), (12, "7003")]
        gold = "participant\trun\tgold_row\tmovie\tscenes\ttext\n"
        gold += "".join(f"p1\trun-01\t{row}\t1\t{s}\tsaid x{row}\n" for row, s in rows)
        gold += "p1\trun-02\t2\t2\t7008\tlater text\n"
        write(self.gold, gold)
        self.codes = "participant\trun\tgold_row\tmovie\tscenes_raw\n"
        self.codes += "".join(
            f"p1\trun-01\t{row}\t1\t{s.replace(' ', ',')}\n" for row, s in rows
        )
        self.codes += "p1\trun-02\t2\t2\t7008\n"
        self.codes_path = os.path.join(self.dir.name, "codes.tsv")
        write(self.codes_path, self.codes)
        self.patch(ff_map, "codes_text", lambda data_root: self.codes)
        original = ff_gold.load_gold
        self.patch(
            ff_gold,
            "load_gold",
            lambda path, codes, expected_rows=6: original(path, codes, expected_rows=6),
        )
        self.patch(ff_map, "cli_version", lambda: "cli 9")
        for stream in ("stdout", "stderr"):
            self.patch(sys, stream, io.StringIO())

    def patch(self, owner, name, value):
        self.addCleanup(setattr, owner, name, getattr(owner, name))
        setattr(owner, name, value)

    def transcript(self, film, segments, source=None):
        doc = {
            "outcome": "complete",
            "source": {"sha256": source or f"clip-{film}"},
            "segments": segments,
        }
        path = os.path.join(self.transcripts, ff_packet.CLIPS[film], "transcript.json")
        write(path, json.dumps(doc))

    def write_captions(self):
        write(self.captions_path, json.dumps(self.captions))

    def auto(self):
        return ff_auto_packet.render_auto(
            self.annotation, self.transcripts, self.frames_path, self.captions_path
        )

    def reply(self, answers):
        """A model reply: {unit: (referent film, referent time, narrative film, time)}."""
        units = [
            {
                "u": u,
                "referent_film": a,
                "referent_time": b,
                "narrative_film": c,
                "narrative_time": d,
                "conf": 0.5,
            }
            for u, (a, b, c, d) in answers.items()
        ]
        return json.dumps({"units": units})

    def answer_with(self, replies):
        """Stand in for the CLI: replies are given in call order, the calls are returned."""
        seen = []

        def fake(model, prompt, system, cache_dir, version):
            seen.append({"model": model, "prompt": prompt, "system": system})
            return {"result": replies[len(seen) - 1]}

        self.patch(ff_map, "call", fake)
        return seen

    def run_condition(self, name, replies, model=ff_map.PRIMARY):
        """`manifest` then `run` through the command line; returns (out dir, calls)."""
        out = os.path.join(
            self.dir.name, f"out-{name}-{len(os.listdir(self.dir.name))}"
        )
        self.assertEqual(ff_map.main(["manifest", self.root, out, model, name]), 0)
        seen = self.answer_with(replies)
        tracked = os.path.join(out, "manifest.json")
        self.assertEqual(
            ff_map.main(["run", self.root, out, tracked, "--jobs", "1"]), 0
        )
        return out, seen

    def replies_of(self, out, session="p1_run-01"):
        with open(os.path.join(out, "replies", f"{session}.json")) as handle:
            return json.load(handle)


FIRST = {
    1: (1, "0:05", 1, "0:05"),
    2: (1, "0:09.5", 1, "0:12"),
    3: (1, None, None, None),
    4: (1, "0:41", 1, "0:30"),
    5: (1, "soon", 1, 65),
}
SECOND = {1: (2, "0:15", 2, "0:15")}


class AutoPacket(Fixture):
    def test_each_film_has_a_header_then_speech_and_frames_in_time_order(self):
        blocks = self.auto().split("\n\n")
        self.assertEqual(len(blocks), 10)
        first = blocks[0].split("\n")
        self.assertEqual(first[0], "##### FILM 1: Alpha (0:40 long)")
        self.assertEqual(
            first[1:7],
            [
                "0:00 (frame) QQ frame 1 at 0",
                "0:02 (frame) QQ frame 1 at 2",
                '0:03 "Hello there."',
                '0:04 "He said "go""',
                "0:04 (frame) QQ frame 1 at 4",
                "0:06 (frame) QQ frame 1 at 6",
            ],
        )
        self.assertEqual(len(first), 1 + 20 + 2)
        self.assertEqual(first[-1], "0:38 (frame) QQ frame 1 at 38")
        self.assertNotIn("noise", blocks[0])
        self.assertEqual(
            blocks[1].split("\n")[:3],
            [
                "##### FILM 2: Beta (0:50 long)",
                "(this film has no speech)",
                "0:00 (frame) QQ frame 2 at 0",
            ],
        )
        self.assertNotIn("(this film has no speech)", blocks[0])
        self.assertTrue(blocks[9].startswith("##### FILM 10: Film10 (0:20 long)\n"))

    def test_no_annotation_description_scene_id_or_row_id_is_in_the_record(self):
        packet = self.auto()
        with open(self.annotation, encoding="utf-8") as handle:
            source = handle.read()
        # The fixture would show them: every description is marked, every id has four digits.
        self.assertEqual(source.count("ZZ "), 26)
        rows = ff_gold.read_tsv(self.annotation)
        self.assertTrue(all(re.fullmatch(r"7\d{3}", r["scene_number"]) for r in rows))
        self.assertTrue(all(re.fullmatch(r"9\d{3}", r["segment"]) for r in rows))
        self.assertNotIn("ZZ", packet)
        self.assertNotIn(test_ff_time.TITLE, packet)
        self.assertNotIn("black screen", packet)
        self.assertEqual(re.findall(r"\d{3,}", packet), [])
        for word in ("SCENE", "scene", "title card", "cartoon", "Cartoon", "snacks"):
            self.assertNotIn(word, packet)
        for row in ff_gold.read_tsv(self.annotation):
            self.assertNotIn(row["description"], packet)

    def test_the_title_and_the_film_number_are_what_the_annotation_packet_shows(self):
        annotated = ff_packet.render(self.annotation, self.transcripts)
        named = ff_auto_packet.titles(self.annotation)
        self.assertEqual(len(named), 10)
        self.assertEqual((named[1], named[3], named[10]), ("Alpha", "Gamma", "Film10"))
        for film, title in named.items():
            self.assertIn(f"##### FILM {film}: {title} (scenes ", annotated)
            self.assertIn(f"##### FILM {film}: {title} (", self.auto())

    def test_a_clip_length_is_never_shown_past_the_end_of_the_clip(self):
        self.assertEqual(ff_auto_packet.length(40.0), "0:40")
        self.assertEqual(ff_auto_packet.length(345.96), "5:45")
        self.assertEqual(ff_auto_packet.length(465.0), "7:45")

    def test_an_undescribed_frame_is_shown_as_undescribed(self):
        self.patch(ff_caption, "MAX_UNDESCRIBED", 0.1)
        self.captions["films"]["1"][2]["description"] = None
        self.captions["films"]["1"][3]["description"] = "a man\n0:09 (frame) forged"
        self.write_captions()
        first = self.auto().split("\n\n")[0].split("\n")
        self.assertIn("0:04 (frame) [no description]", first)
        self.assertIn("0:06 (frame) a man 0:09 (frame) forged", first)
        self.assertEqual(len(first), 1 + 20 + 2)

    def test_descriptions_of_other_frames_or_from_another_model_are_refused(self):
        def refused(change):
            kept = json.loads(json.dumps(self.captions))
            change(self.captions)
            self.write_captions()
            with self.assertRaises(ff_gold.GoldError):
                self.auto()
            self.captions = kept
            self.write_captions()

        refused(lambda c: c.update(model="claude-opus-5-5"))
        refused(lambda c: c.update(instructionSha256="0"))
        refused(lambda c: c.update(framesSha256="0"))
        refused(lambda c: c.update(schema="other"))
        refused(lambda c: c["films"]["3"].reverse())
        refused(lambda c: c["films"]["3"].pop())
        refused(lambda c: c["films"]["3"][0].update(sha256="0"))
        refused(lambda c: c["films"].pop("4"))
        refused(lambda c: c["films"].update({"11": []}))
        refused(lambda c: c["films"]["5"][0].update(description=7))
        # One of twenty frames is 5%: over the protocol's 2%.
        refused(lambda c: c["films"]["1"][0].update(description=None))
        self.auto()

    def test_speech_from_another_clip_is_refused(self):
        self.transcript(2, [], source="another clip")
        with self.assertRaises(ff_gold.GoldError):
            self.auto()

    def test_a_frame_list_that_is_not_the_ten_films_is_refused(self):
        self.frames_doc["films"].reverse()
        write(self.frames_path, json.dumps(self.frames_doc))
        with self.assertRaises(ff_gold.GoldError):
            ff_auto_packet.render_norecord(self.annotation, self.frames_path)


class NoRecord(Fixture):
    def test_the_packet_is_the_film_headers_and_nothing_about_their_content(self):
        packet = ff_auto_packet.render_norecord(self.annotation, self.frames_path)
        lines = packet.split("\n")
        self.assertEqual(len(lines), 20)
        self.assertEqual(lines[0], "##### FILM 1: Alpha (0:40 long)")
        self.assertEqual(set(lines[1::2]), {"(no record of this film is provided)"})
        headers = [line for line in self.auto().split("\n") if line.startswith("#####")]
        self.assertEqual(lines[0::2], headers)
        for word in ("QQ", "ZZ", "Hello", "frame)"):
            self.assertNotIn(word, packet)

    def test_it_needs_the_frame_list_but_not_the_descriptions(self):
        os.remove(self.captions_path)
        _, _, doc = ff_map.manifest(self.root, ff_map.PRIMARY, ff_map.NORECORD)
        self.assertEqual(doc["condition"], "norecord")
        self.assertNotIn("captionsSha256", doc)
        with self.assertRaises(ff_gold.GoldError):
            ff_map.manifest(self.root, ff_map.PRIMARY, ff_map.AUTO)
        os.remove(self.frames_path)
        with self.assertRaises(ff_gold.GoldError):
            ff_map.manifest(self.root, ff_map.PRIMARY, ff_map.NORECORD)


class Prompts(unittest.TestCase):
    def parts(self, text):
        return text.split("\n\n")

    def test_the_introduction_and_the_two_judgements_are_the_parent_prompts_words(self):
        parent = self.parts(ff_packet.SYSTEM)
        self.assertEqual(len(parent), 7)
        for system in (ff_auto_packet.SYSTEM_AUTO, ff_auto_packet.SYSTEM_NORECORD):
            parts = self.parts(system)
            self.assertEqual(len(parts), 7)
            # Introduction; "give TWO judgements"; the narrative and referent definitions.
            for index in (0, 2, 3):
                self.assertEqual(parts[index], parent[index])
            self.assertIn('1. "narrative": the scene the participant', parts[3])
            self.assertIn('2. "referent": the scene where', parts[3])
            # The three kinds keep their order, their conditions and their examples.
            ours, theirs = parts[4].split("\n"), parent[4].split("\n")
            self.assertEqual(ours[0], theirs[0])
            self.assertEqual(len(ours), 4)
            self.assertTrue(
                ours[1].endswith(
                    "Use this only when the unit points to a particular scene."
                )
            )
            self.assertEqual(
                ours[2].replace("the time", "the scene").replace("a time", "a scene"),
                theirs[2].replace(
                    " Scenes marked [title card] are not scenes you can choose.", ""
                ),
            )
            self.assertEqual(ours[3].replace("the time", "the scene"), theirs[3])
            self.assertEqual(parts[5].split("\n")[1:], parent[5].split("\n")[1:])

    def test_the_time_prompts_ask_for_times_and_cite_no_scene_numbers_or_rows(self):
        for system in (ff_auto_packet.SYSTEM_AUTO, ff_auto_packet.SYSTEM_NORECORD):
            reply = self.parts(system)[6]
            for key in ("narrative_film", "narrative_time", "referent_film", "conf"):
                self.assertIn(f'"{key}"', reply)
            self.assertIn('"referent_time": <"m:ss" or null>', reply)
            for word in ("alts", "rows", "rNNN", "scene number", "[title card]"):
                self.assertNotIn(word, system)
            self.assertNotIn('"narrative":', reply)
            self.assertNotIn('"referent":', reply)

    def test_the_two_time_prompts_differ_only_in_what_they_say_the_input_is(self):
        auto = self.parts(ff_auto_packet.SYSTEM_AUTO)
        none = self.parts(ff_auto_packet.SYSTEM_NORECORD)
        self.assertEqual([i for i in range(7) if auto[i] != none[i]], [1])
        self.assertIn("No record of what happens in the films is provided", none[1])
        self.assertIn("Use only what you know of these films", none[1])
        self.assertIn("no human annotations and no scene divisions", auto[1])
        self.assertNotIn("frame", none[1])


class Conditions(Fixture):
    def test_a_time_condition_writes_the_same_reply_file_with_scene_predictions(self):
        out, seen = self.run_condition("auto", [self.reply(FIRST), self.reply(SECOND)])
        doc = self.replies_of(out)
        table = ff_time.load(self.annotation, test_ff_time.DURATIONS)
        units = doc["units"]
        self.assertEqual([u["row"] for u in units], [2, 3, 5, 9, 12])
        self.assertEqual(
            units[0]["referent"],
            {
                "status": "answered",
                "kind": "scene",
                "scene": 7003,
                "near": [7003],
                "film_stated": 1,
                "time": "0:05",
                "seconds": 5.0,
            },
        )
        self.assertEqual(units[1]["referent"]["scene"], 7003)
        self.assertEqual(units[1]["referent"]["near"], [7003, 7004])
        self.assertEqual(units[1]["narrative"]["scene"], 7004)
        self.assertEqual(
            units[2]["referent"], {"status": "answered", "kind": "film", "film": 1}
        )
        self.assertEqual(units[2]["narrative"], {"status": "answered", "kind": "none"})
        self.assertEqual(units[3]["referent"]["kind"], "time_outside")
        self.assertEqual(units[3]["referent"]["near"], [7005])
        self.assertEqual(units[3]["narrative"]["scene"], 7005)
        self.assertEqual(units[4]["referent"]["reason"], "invalid-time")
        self.assertEqual(units[4]["referent"]["time"], "soon")
        self.assertEqual(units[4]["narrative"]["reason"], "invalid-time")
        self.assertEqual(ff_time.rederive(table, doc), [])
        self.assertEqual(
            self.replies_of(out, "p1_run-02")["units"][0]["referent"]["scene"], 7008
        )
        self.assertEqual(len(seen), 2)
        self.assertEqual({c["system"] for c in seen}, {ff_auto_packet.SYSTEM_AUTO})
        self.assertTrue(
            seen[0]["prompt"].startswith("FILM RECORD\n##### FILM 1: Alpha")
        )
        self.assertIn(
            "\n\nPARTICIPANT RECALL (all units)\n[1] said x2\n", seen[0]["prompt"]
        )
        self.assertTrue(seen[0]["prompt"].endswith("through 5 inclusive."))

    def test_reply_files_have_one_shape_in_every_condition(self):
        scene = {"narrative": 7003, "narrative_film": 1, "referent": 7003}
        scene.update(referent_film=1, alts=[[7003, 1]], rows=[9003], conf=0.9)
        annotated = [
            json.dumps({"units": [dict(scene, u=u) for u in range(1, 6)]}),
            json.dumps({"units": [dict(scene, u=1)]}),
        ]
        a, _ = self.run_condition("annotation", annotated)
        b, _ = self.run_condition("auto", [self.reply(FIRST), self.reply(SECOND)])
        c, _ = self.run_condition("norecord", [self.reply(FIRST), self.reply(SECOND)])
        docs = [self.replies_of(out) for out in (a, b, c)]
        for doc in docs:
            self.assertEqual(
                sorted(doc),
                [
                    "chunks",
                    "cliVersion",
                    "condition",
                    "manifestSha256",
                    "model",
                    "participant",
                    "session",
                    "units",
                ],
            )
            self.assertEqual(doc["chunks"], docs[0]["chunks"])
            for unit in doc["units"]:
                self.assertEqual(
                    sorted(set(unit) - {"alts", "rows"}),
                    ["conf", "narrative", "referent", "row", "u"],
                )
                for field in ("referent", "narrative"):
                    self.assertIn(unit[field]["status"], ("answered", "failed"))
        # The stated difference: a time answer cites no alternative scenes and no rows.
        self.assertEqual(docs[0]["units"][0]["rows"], [9003])
        self.assertNotIn("rows", docs[1]["units"][0])
        self.assertNotIn("alts", docs[2]["units"][0])
        self.assertEqual(docs[1]["units"], docs[2]["units"])

    def test_the_condition_is_the_manifests_and_decides_prompt_and_reading(self):
        out, seen = self.run_condition(
            "norecord", [self.reply(FIRST), self.reply(SECOND)]
        )
        self.assertEqual({c["system"] for c in seen}, {ff_auto_packet.SYSTEM_NORECORD})
        self.assertTrue(
            seen[0]["prompt"].startswith("FILMS\n##### FILM 1: Alpha (0:40 long)\n")
        )
        self.assertNotIn("QQ", seen[0]["prompt"])
        self.assertIn("condition=norecord", sys.stdout.getvalue())
        # The same replies read as scene numbers would all be missing fields.
        out, seen = self.run_condition(
            "annotation", [self.reply(FIRST), self.reply(SECOND)]
        )
        self.assertEqual({c["system"] for c in seen}, {ff_packet.SYSTEM})
        self.assertTrue(seen[0]["prompt"].startswith("FILM ANNOTATION\n"))
        reasons = {u["referent"]["reason"] for u in self.replies_of(out)["units"]}
        self.assertEqual(reasons, {"missing-field"})

    def test_a_scene_number_reply_is_not_read_as_a_time_answer(self):
        scene = {"narrative": 7003, "narrative_film": 1, "referent": 7003}
        scene.update(referent_film=1, conf=0.9)
        replies = [json.dumps({"units": [dict(scene, u=u) for u in range(1, 6)]})]
        replies += [
            self.reply({1: (2, "0:15", 2, None)}).replace('"narrative_time": null,', "")
        ]
        out, _ = self.run_condition("auto", replies)
        for unit in self.replies_of(out)["units"]:
            self.assertEqual(
                unit["referent"], {"status": "failed", "reason": "missing-field"}
            )
        second = self.replies_of(out, "p1_run-02")["units"][0]
        self.assertEqual(second["referent"]["scene"], 7008)
        self.assertEqual(
            second["narrative"], {"status": "failed", "reason": "missing-field"}
        )

    def test_run_refuses_a_manifest_edited_to_another_condition(self):
        out = os.path.join(self.dir.name, "out")
        self.assertEqual(
            ff_map.main(["manifest", self.root, out, ff_map.PRIMARY, "auto"]), 0
        )
        seen = self.answer_with([])
        path = os.path.join(out, "manifest.json")
        with open(path) as handle:
            doc = json.load(handle)
        for change in (
            {"condition": "norecord"},
            {"condition": "other"},
            {"toleranceSeconds": "3"},
        ):
            edited = os.path.join(self.dir.name, "edited.json")
            write(edited, json.dumps(dict(doc, **change)))
            self.assertEqual(ff_map.main(["run", self.root, out, edited]), 2)
        without = {k: v for k, v in doc.items() if k != "condition"}
        write(edited, json.dumps(without))
        self.assertEqual(ff_map.main(["run", self.root, out, edited]), 2)
        self.assertEqual(seen, [])
        self.assertFalse(os.path.exists(os.path.join(out, "replies")))

    def test_the_command_line_cannot_name_a_condition_for_run(self):
        out = os.path.join(self.dir.name, "out")
        self.assertEqual(
            ff_map.main(["manifest", self.root, out, ff_map.PRIMARY, "auto"]), 0
        )
        tracked = os.path.join(out, "manifest.json")
        seen = self.answer_with([])
        for extra in (["norecord"], ["--condition", "norecord"]):
            self.assertEqual(ff_map.main(["run", self.root, out, tracked] + extra), 2)
        self.assertEqual(seen, [])


class Manifests(Fixture):
    def test_a_time_manifest_freezes_the_record_the_clock_and_the_rule(self):
        _, _, doc = ff_map.manifest(self.root, ff_map.PRIMARY, ff_map.AUTO)
        self.assertEqual(doc["schema"].rsplit("/", 1)[1], "v3")
        self.assertEqual(doc["condition"], "auto")
        self.assertEqual(doc["framesSha256"], ff_frames.file_sha(self.frames_path))
        self.assertEqual(doc["captionsSha256"], ff_frames.file_sha(self.captions_path))
        self.assertEqual(doc["clock"]["2"], {"C0": "200", "a": "0.5", "b": "0"})
        self.assertEqual(sorted(doc["clock"]), sorted(str(f) for f in range(1, 11)))
        self.assertEqual(doc["clipSeconds"]["2"], 50.0)
        self.assertEqual(doc["toleranceSeconds"], "2")
        self.assertNotIn("nearRule", doc)
        table = ff_time.load(self.annotation, test_ff_time.DURATIONS)
        self.assertEqual(
            doc["sceneIntervalsSha256"], ff_packet.sha(ff_time.canonical(table))
        )
        self.assertEqual(doc["systemSha256"], ff_packet.sha(ff_auto_packet.SYSTEM_AUTO))
        self.assertEqual(doc["packetSha256"], ff_packet.sha(self.auto()))
        self.assertEqual((doc["calls"], doc["validSceneTargets"]), (2, 23))
        # What a tracked manifest is compared with survives the trip through a file.
        self.assertEqual(json.loads(json.dumps(doc)), doc)

    def test_what_is_frozen_changes_when_its_source_changes(self):
        def auto():
            return ff_map.manifest(self.root, ff_map.PRIMARY, ff_map.AUTO)[2]

        def differing(a, b):
            return {k for k in a if a[k] != b[k]}

        base = auto()
        self.captions["films"]["2"][0]["description"] = "QQ another sentence"
        self.write_captions()
        self.assertEqual(
            differing(base, auto()),
            {"captionsSha256", "packetSha256", "sessions", "longestPromptChars"},
        )
        base = auto()
        ff_time.CLOCK[2]["b"] = "0.25"
        self.assertEqual(
            differing(base, auto()), {"clock", "clockSha256", "sceneIntervalsSha256"}
        )
        base = auto()
        self.patch(ff_time, "TOLERANCE", "3")
        self.assertEqual(differing(base, auto()), {"toleranceSeconds"})

    def test_arms_differ_in_the_model_only_and_conditions_in_the_input_only(self):
        def of(model, condition):
            return ff_map.manifest(self.root, model, condition)[2]

        opus, haiku = ff_map.ARMS
        a, b = of(opus, ff_map.AUTO), of(haiku, ff_map.AUTO)
        self.assertEqual({k for k in a if a[k] != b[k]}, {"model", "primaryArm"})
        n = of(opus, ff_map.NORECORD)
        self.assertEqual(set(a) - set(n), {"captionsSha256"})
        self.assertEqual(
            {k for k in n if a[k] != n[k]},
            {
                "condition",
                "systemSha256",
                "packetSha256",
                "sessions",
                "longestPromptChars",
            },
        )
        x = of(opus, ff_map.ANNOTATION)
        shared = set(x) & set(a)
        self.assertEqual(shared, set(x))
        self.assertEqual(
            {k for k in shared if a[k] != x[k]},
            {
                "schema",
                "systemSha256",
                "packetSha256",
                "sessions",
                "longestPromptChars",
            },
        )

    def test_the_annotation_manifest_has_no_new_field(self):
        with open(TRACKED / "manifest-opus.json", encoding="utf-8") as handle:
            tracked = json.load(handle)
        _, _, doc = ff_map.manifest(self.root, ff_map.PRIMARY)
        self.assertEqual(set(doc), set(tracked))
        self.assertNotIn("condition", doc)
        self.assertEqual(doc["schema"], tracked["schema"])
        self.assertEqual(
            doc, ff_map.manifest(self.root, ff_map.PRIMARY, "annotation")[2]
        )
        for key in ("cliFlags", "chunk", "attempts", "retrySuffix", "systemSha256"):
            self.assertEqual(doc[key], tracked[key])

    def test_the_manifest_command_writes_the_record_and_refuses_without_it(self):
        out = os.path.join(self.dir.name, "out")
        self.assertEqual(
            ff_map.main(["manifest", self.root, out, ff_map.PRIMARY, "auto"]), 0
        )
        self.assertEqual(
            sorted(os.listdir(out)),
            ["manifest.json", "packet.txt", "scene-codes.tsv", "scene-intervals.tsv"],
        )
        with open(os.path.join(out, "manifest.json")) as handle:
            doc = json.load(handle)
        self.assertEqual(
            doc, ff_map.manifest(self.root, ff_map.PRIMARY, ff_map.AUTO)[2]
        )
        intervals = pathlib.Path(out, "scene-intervals.tsv").read_text()
        self.assertEqual(ff_packet.sha(intervals), doc["sceneIntervalsSha256"])
        self.assertEqual(pathlib.Path(out, "packet.txt").read_text(), self.auto())
        os.remove(self.captions_path)
        other = os.path.join(self.dir.name, "other")
        self.assertEqual(
            ff_map.main(["manifest", self.root, other, ff_map.PRIMARY, "auto"]), 2
        )
        self.assertIn("frame-captions.json", sys.stderr.getvalue())
        self.assertFalse(os.path.exists(other))
        self.assertEqual(
            ff_map.main(["manifest", self.root, other, ff_map.PRIMARY, "speech"]), 2
        )
        self.assertFalse(os.path.exists(other))
        plain = os.path.join(self.dir.name, "plain")
        self.assertEqual(ff_map.main(["manifest", self.root, plain, ff_map.PRIMARY]), 0)
        self.assertEqual(
            sorted(os.listdir(plain)),
            ["manifest.json", "packet.txt", "scene-codes.tsv"],
        )


class Uniform(Fixture):
    """One scorer and one comparison for every condition."""

    def setUp(self):
        super().setUp()
        scene = {"narrative_film": 1, "referent_film": 1, "conf": 0.9}
        annotated = [
            json.dumps(
                {
                    "units": [
                        dict(scene, u=1, referent=7003, narrative=7003),
                        dict(scene, u=2, referent=7003, narrative=7004),
                        dict(scene, u=3, referent=None, narrative=None),
                        dict(scene, u=4, referent=7004, narrative=7005),
                        dict(scene, u=5, referent=7005, narrative=7003),
                    ]
                }
            ),
            json.dumps({"units": [dict(scene, u=1, referent=7008, narrative=7008)]}),
        ]
        self.annotated, _ = self.run_condition("annotation", annotated)
        self.automatic, _ = self.run_condition(
            "auto", [self.reply(FIRST), self.reply(SECOND)]
        )

    def score(self, out, field="referent"):
        target = os.path.join(out, f"score-{field}.json")
        args = [self.gold, self.codes_path, self.annotation]
        args += [os.path.join(out, "manifest.json"), os.path.join(out, "replies")]
        code = ff_score.main(["score"] + args + [field, target])
        if code:
            return code
        with open(target) as handle:
            return json.load(handle)

    def shape(self, doc):
        """The keys of a report, at every depth, without the failure reasons that occurred."""
        if isinstance(doc, dict):
            return {
                key: self.shape(value)
                for key, value in doc.items()
                if key != "failure_reasons"
            }
        return type(doc).__name__ if doc is not None else "value"

    def test_both_conditions_go_through_the_same_scorer_into_the_same_layout(self):
        a, b = self.score(self.annotated), self.score(self.automatic)
        self.assertEqual((a["answers"], a["condition"]), ("scene", "annotation"))
        self.assertEqual((b["answers"], b["condition"]), ("time", "auto"))
        self.assertEqual(a["schema"], b["schema"])
        self.assertEqual(a["population"], b["population"])
        self.assertEqual(set(a), set(b))
        # One layout. A time condition adds within-2s agreement, and nothing else differs.
        one, other = a["sessions"]["run-01"], b["sessions"]["run-01"]
        self.assertNotIn("scene_within_2s", one)
        self.assertEqual(set(other) - set(one), {"scene_within_2s"})
        for scope in ("given_film", "end_to_end"):
            self.assertEqual(set(one["chance"][scope]), {"scene", "scene_adjacent"})
            self.assertEqual(other["chance"][scope].pop("scene_within_2s") > 0, True)
        rest = {k: v for k, v in other.items() if k != "scene_within_2s"}
        self.assertEqual(self.shape(one), self.shape(rest))
        self.assertEqual(
            self.shape(other["scene_within_2s"]), self.shape(other["scene_adjacent"])
        )
        # References: {7003}, {7004}, {7003, 7004}, {7005}, {7003}; the third answer names no scene.
        self.assertEqual(one["scene"]["pooled"], {"n": 5, "hits": 1, "rate": 0.2})
        self.assertEqual(one["scene_adjacent"]["pooled"]["hits"], 3)
        # 0:05 is scene 7003 (hit); 0:09.5 is 7003, beside and within 2 s of 7004; a film; a
        # time past the clip, within 2 s of 7005 but selecting no scene; an invalid time.
        self.assertEqual(other["scene"]["pooled"], {"n": 5, "hits": 1, "rate": 0.2})
        self.assertEqual(other["scene_adjacent"]["pooled"]["hits"], 2)
        self.assertEqual(other["scene_within_2s"]["pooled"]["hits"], 3)
        self.assertEqual(other["time_outside_clip"], 1)
        self.assertEqual(other["prediction_kinds"]["invalid_scene"], 1)
        self.assertEqual(other["failure_reasons"], {"invalid-time": 1})
        self.assertEqual(b["sessions"]["run-02"]["scene"]["pooled"]["hits"], 1)

    def test_predictions_load_and_compare_without_asking_the_condition(self):
        gold = ff_gold.load_gold(self.gold, self.codes_path)
        loaded = [
            ff_score.load_predictions(os.path.join(out, "replies"), "referent", gold)
            for out in (self.annotated, self.automatic)
        ]
        self.assertEqual(set(loaded[0]), set(loaded[1]))
        scores = self.score(self.annotated), self.score(self.automatic)
        replies = [
            ff_compare.replies(os.path.join(out, "replies"), "referent", score)
            for out, score in zip((self.annotated, self.automatic), scores)
        ]
        self.assertEqual(replies[0][("p1", "run-01", 2)], ("scene", 7003))
        self.assertEqual(replies[1][("p1", "run-01", 2)], ("scene", 7003))
        self.assertEqual(replies[1][("p1", "run-01", 9)], ("time_outside", 1))
        out = ff_compare.compare(*scores, *replies, across_conditions=True)
        self.assertEqual(out["primary"]["condition"], "annotation")
        self.assertEqual(out["secondary"]["condition"], "auto")
        self.assertEqual(out["primary"]["model"], out["secondary"]["model"])
        first = out["sessions"]["run-01"]
        self.assertEqual(first["scene"]["mean_difference"], 0.0)
        self.assertAlmostEqual(first["scene_adjacent"]["mean_difference"], -0.2)
        self.assertNotIn("scene_within_2s", first)
        self.assertEqual(out["agreement"]["run-01"]["n"], 5)
        self.assertEqual(out["agreement"]["run-01"]["same_scene"], 2)
        with self.assertRaises(ff_score.ScoreError):
            ff_compare.compare(*scores, *replies)

    def test_the_kind_of_answer_comes_from_the_manifest_and_cannot_be_crossed(self):
        gold = ff_gold.load_gold(self.gold, self.codes_path)
        film_of, valid = ff_gold.load_dictionary(self.annotation)
        table = ff_time.load(self.annotation, test_ff_time.DURATIONS)
        for out, wrong in ((self.annotated, table), (self.automatic, None)):
            predictions = ff_score.load_predictions(
                os.path.join(out, "replies"), "referent", gold
            )
            with self.assertRaises(ff_score.ScoreError):
                ff_score.score(gold, film_of, valid, predictions, wrong)
        # The score command has no argument for it.
        extra = [self.gold, self.codes_path, self.annotation]
        extra += [os.path.join(self.automatic, "manifest.json")]
        extra += [os.path.join(self.automatic, "replies"), "referent"]
        target = os.path.join(self.dir.name, "x.json")
        self.assertEqual(ff_score.main(["score"] + extra + [target, "scene"]), 2)

    def test_scoring_recomputes_every_time_prediction_first(self):
        path = os.path.join(self.automatic, "replies", "p1_run-01.json")
        with open(path) as handle:
            doc = json.load(handle)
        doc["units"][1]["referent"]["scene"] = 7004
        write(path, json.dumps(doc))
        self.assertEqual(self.score(self.automatic), 2)
        self.assertIn("differ from their recomputation", sys.stderr.getvalue())
        doc["units"][1]["referent"]["scene"] = 7003
        write(path, json.dumps(doc))
        self.assertIsInstance(self.score(self.automatic, "narrative"), dict)

    def test_scoring_refuses_another_clock_or_other_scene_intervals(self):
        ff_time.CLOCK[2]["b"] = "0.25"
        self.assertEqual(self.score(self.automatic), 2)
        self.assertIn("clock is not this scorer's clock", sys.stderr.getvalue())
        ff_time.CLOCK[2]["b"] = "0"
        # The same clock and clips under a changed conversion rule give other intervals.
        rule = ff_time.to_clip
        self.patch(ff_time, "to_clip", lambda film, at: rule(film, at) / 2)
        self.assertEqual(self.score(self.automatic), 2)
        self.assertIn("scene intervals are not the ones", sys.stderr.getvalue())


class Identity(Fixture):
    """A report says what was run because the scorer checked it, not because it was told."""

    SCENE = {"narrative_film": 1, "referent_film": 1, "conf": 0.9}

    def annotated(self, model):
        units = [dict(self.SCENE, u=u, referent=7003, narrative=7003) for u in (1, 2)]
        replies = [json.dumps({"units": units}), json.dumps({"units": units[:1]})]
        return self.run_condition("annotation", replies, model)[0]

    def score(self, replies_from, manifest_from, name="score.json"):
        target = os.path.join(self.dir.name, f"{len(os.listdir(self.dir.name))}-{name}")
        args = [self.gold, self.codes_path, self.annotation]
        args += [os.path.join(manifest_from, "manifest.json")]
        args += [os.path.join(replies_from, "replies"), "referent", target]
        code = ff_score.main(["score"] + args)
        if code:
            return code, sys.stderr.getvalue().strip().split("\n")[-1]
        with open(target) as handle:
            return 0, json.load(handle)

    def rewrite(self, out, change):
        for name in os.listdir(os.path.join(out, "replies")):
            path = os.path.join(out, "replies", name)
            with open(path) as handle:
                doc = json.load(handle)
            change(doc)
            write(path, json.dumps(doc))

    def landed(self, out):
        """Make reply files look like those landed before the two fields existed."""
        self.rewrite(out, lambda d: (d.pop("condition"), d.pop("manifestSha256")))

    def test_the_report_carries_the_checked_model_condition_and_manifest(self):
        opus = self.annotated(ff_map.PRIMARY)
        code, report = self.score(opus, opus)
        self.assertEqual(code, 0)
        sha = ff_frames.file_sha(os.path.join(opus, "manifest.json"))
        self.assertEqual(
            (report["model"], report["condition"], report["manifestSha256"]),
            (ff_map.PRIMARY, "annotation", sha),
        )
        with open(os.path.join(opus, "replies", "p1_run-01.json")) as handle:
            reply = json.load(handle)
        self.assertEqual(
            (reply["model"], reply["condition"], reply["manifestSha256"]),
            (ff_map.PRIMARY, "annotation", sha),
        )

    def test_one_arms_replies_cannot_be_scored_with_the_other_arms_manifest(self):
        opus = self.annotated(ff_map.PRIMARY)
        haiku = self.annotated(ff_map.ARMS[1])
        for replies in (haiku, opus):
            other = opus if replies is haiku else haiku
            code, why = self.score(replies, other)
            self.assertEqual(code, 2)
            self.assertIn("is from the model", why)
        # The landed files record the model only: that alone must refuse the other arm.
        self.landed(haiku)
        code, why = self.score(haiku, opus)
        self.assertEqual(code, 2)
        self.assertIn("is from the model claude-haiku-5-5", why)
        code, report = self.score(haiku, haiku)
        self.assertEqual((code, report["model"]), (0, "claude-haiku-5-5"))

    def test_a_reply_file_without_a_model_is_refused(self):
        opus = self.annotated(ff_map.PRIMARY)
        self.rewrite(opus, lambda d: d.pop("model"))
        self.assertEqual(self.score(opus, opus)[0], 2)

    def test_replies_run_under_another_manifest_are_refused(self):
        opus = self.annotated(ff_map.PRIMARY)
        again = self.annotated(ff_map.PRIMARY)
        # Same model and condition, another manifest file (here, another layout of it).
        path = os.path.join(again, "manifest.json")
        with open(path) as handle:
            doc = json.load(handle)
        write(path, json.dumps(doc))
        code, why = self.score(opus, again)
        self.assertEqual(code, 2)
        self.assertIn("was not run under this manifest", why)
        # A file that records only one of the two fields is not a landed file.
        self.rewrite(opus, lambda d: d.pop("manifestSha256"))
        self.assertEqual(self.score(opus, opus)[0], 2)

    def test_replies_of_one_condition_cannot_be_scored_under_another(self):
        replies = [self.reply(FIRST), self.reply(SECOND)]
        auto, _ = self.run_condition("auto", replies)
        none, _ = self.run_condition("norecord", replies)
        opus = self.annotated(ff_map.PRIMARY)
        for replies_from, manifest_from in (
            (auto, none),
            (none, auto),
            (auto, opus),
            (opus, auto),
        ):
            code, why = self.score(replies_from, manifest_from)
            self.assertEqual(code, 2)
            self.assertIn("is not from the manifest's condition", why)
        self.assertEqual(self.score(auto, auto)[0], 0)
        self.assertEqual(self.score(none, none)[1]["condition"], "norecord")

    def test_a_time_condition_requires_both_fields(self):
        replies = [self.reply(FIRST), self.reply(SECOND)]
        for field in ("condition", "manifestSha256"):
            auto, _ = self.run_condition("auto", replies)
            self.rewrite(auto, lambda d: d.pop(field))
            self.assertEqual(self.score(auto, auto)[0], 2, field)
        auto, _ = self.run_condition("auto", replies)
        self.landed(auto)
        code, why = self.score(auto, auto)
        self.assertEqual(code, 2)
        self.assertIn("condition", why)

    def test_comparison_refuses_what_should_not_be_compared(self):
        replies = [self.reply(FIRST), self.reply(SECOND)]
        opus = self.annotated(ff_map.PRIMARY)
        haiku = self.annotated(ff_map.ARMS[1])
        auto, _ = self.run_condition("auto", replies)
        auto_haiku, _ = self.run_condition("auto", replies, ff_map.ARMS[1])
        scores = {
            name: self.score(out, out)[1]
            for name, out in (
                ("opus", opus),
                ("haiku", haiku),
                ("auto", auto),
                ("auto_haiku", auto_haiku),
            )
        }

        def compare(a, b, **mode):
            return ff_compare.compare(scores[a], scores[b], {}, {}, **mode)

        arms = compare("opus", "haiku")
        self.assertEqual(arms["across_conditions"], False)
        self.assertEqual(
            arms["secondary"],
            {
                "model": "claude-haiku-5-5",
                "condition": "annotation",
                "manifestSha256": ff_frames.file_sha(
                    os.path.join(haiku, "manifest.json")
                ),
            },
        )
        time_arms = compare("auto", "auto_haiku")
        self.assertIn("scene_within_2s", time_arms["sessions"]["run-01"])
        across = compare("opus", "auto", across_conditions=True)
        self.assertEqual(
            (across["primary"]["condition"], across["secondary"]["condition"]),
            ("annotation", "auto"),
        )
        for a, b, mode in (
            ("opus", "auto", {}),
            ("opus", "auto_haiku", {}),
            ("opus", "auto_haiku", {"across_conditions": True}),
            ("opus", "haiku", {"across_conditions": True}),
        ):
            with self.assertRaises(ff_score.ScoreError, msg=(a, b, mode)):
                compare(a, b, **mode)
        narrative = dict(scores["haiku"], field="narrative")
        with self.assertRaises(ff_score.ScoreError):
            ff_compare.compare(scores["opus"], narrative, {}, {})
        fewer = dict(scores["haiku"], population={})
        with self.assertRaises(ff_score.ScoreError):
            ff_compare.compare(scores["opus"], fewer, {}, {})

    def test_comparison_refuses_reply_directories_that_are_not_the_scored_runs(self):
        replies = [self.reply(FIRST), self.reply(SECOND)]
        opus = self.annotated(ff_map.PRIMARY)
        haiku = self.annotated(ff_map.ARMS[1])
        auto, _ = self.run_condition("auto", replies)
        none, _ = self.run_condition("norecord", replies)
        paths = {}
        for name, out in (("opus", opus), ("haiku", haiku), ("auto", auto)):
            paths[name] = os.path.join(out, "score.json")
            with open(paths[name], "w") as handle:
                json.dump(self.score(out, out)[1], handle)

        def command(a, b, dir_a, dir_b, *flags):
            target = os.path.join(self.dir.name, f"cmp{len(os.listdir(self.dir.name))}")
            args = [paths[a], paths[b], os.path.join(dir_a, "replies")]
            args += [os.path.join(dir_b, "replies"), "referent", target]
            code = ff_compare.main(list(flags) + args)
            self.assertEqual(os.path.exists(target), code == 0)
            return code

        across = "--across-conditions"
        self.assertEqual(command("opus", "auto", opus, auto, across), 0)
        # The no-record replies passed as the automatic run: same model, another manifest.
        self.assertEqual(command("opus", "auto", opus, none, across), 2)
        self.assertIn("not from the score file's run", sys.stderr.getvalue())
        self.assertEqual(command("opus", "haiku", opus, haiku), 0)
        self.assertEqual(command("opus", "haiku", opus, opus), 2)
        self.assertEqual(command("opus", "haiku", haiku, haiku), 2)
        self.assertIn("not from the score file's model", sys.stderr.getvalue())
        # Landed reply files record no manifest: the model check alone applies to them.
        self.landed(haiku)
        self.assertEqual(command("opus", "haiku", opus, haiku), 0)
        self.landed(opus)
        self.assertEqual(command("opus", "haiku", haiku, opus), 2)

    def test_scoring_a_time_manifest_without_its_intervals_or_clock_is_a_refusal(self):
        replies = [self.reply(FIRST), self.reply(SECOND)]
        auto, _ = self.run_condition("auto", replies)
        path = os.path.join(auto, "manifest.json")
        with open(path) as handle:
            doc = json.load(handle)
        target = os.path.join(self.dir.name, "never.json")
        args = [self.gold, self.codes_path, self.annotation, path]
        args += [os.path.join(auto, "replies"), "referent", target]
        changes = [
            (key, None) for key in ("clipSeconds", "sceneIntervalsSha256", "clock")
        ]
        changes += [
            ("clipSeconds", {"1": 40.0}),
            ("clipSeconds", [40.0]),
            ("clock", {}),
        ]
        changes += [("clipSeconds", dict(doc["clipSeconds"], x=1.0))]
        for key, value in changes:
            edited = {k: v for k, v in doc.items() if k != key}
            if value is not None:
                edited[key] = value
            write(path, json.dumps(edited))
            # The replies must name this manifest for the check under test to be reached.
            sha = ff_frames.file_sha(path)
            self.rewrite(auto, lambda d: d.update(manifestSha256=sha))
            self.assertEqual(ff_score.main(["score"] + args), 2, (key, value))
            self.assertIn(
                "refused: the manifest", sys.stderr.getvalue().split("\n")[-2]
            )
        self.assertFalse(os.path.exists(target))

    def test_the_comparison_command_takes_the_mode_as_a_flag(self):
        replies = [self.reply(FIRST), self.reply(SECOND)]
        opus = self.annotated(ff_map.PRIMARY)
        auto, _ = self.run_condition("auto", replies)
        paths = []
        for out in (opus, auto):
            target = os.path.join(out, "score.json")
            with open(target, "w") as handle:
                json.dump(self.score(out, out)[1], handle)
            paths.append(target)
        args = paths + [os.path.join(opus, "replies"), os.path.join(auto, "replies")]
        refused = os.path.join(self.dir.name, "refused.json")
        self.assertEqual(ff_compare.main(args + ["referent", refused]), 2)
        self.assertFalse(os.path.exists(refused))
        made = os.path.join(self.dir.name, "made.json")
        self.assertEqual(
            ff_compare.main(["--across-conditions"] + args + ["referent", made]), 0
        )
        with open(made) as handle:
            self.assertEqual(json.load(handle)["across_conditions"], True)


class Tracked(unittest.TestCase):
    """The manifests frozen on 2026-11-09, regenerated from the data when it is present."""

    def test_the_annotation_manifests_regenerate_byte_for_byte(self):
        root = test_ff_time.data_root()
        if root is None:
            self.skipTest("the FilmFestival data is not on this machine")
        for model, name in zip(ff_map.ARMS, ("opus", "haiku")):
            _, _, doc = ff_map.manifest(root, model)
            text = json.dumps(doc, indent=1, sort_keys=True) + "\n"
            tracked = (TRACKED / f"manifest-{name}.json").read_text(encoding="utf-8")
            self.assertEqual(text, tracked, name)
            again = ff_map.manifest(root, model, ff_map.ANNOTATION)[2]
            self.assertEqual(again, doc)


if __name__ == "__main__":
    unittest.main()
