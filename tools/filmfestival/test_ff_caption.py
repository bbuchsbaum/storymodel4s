"""Tests for the frame describer, with a stand-in for the CLI. No model is called.

Run from the repository root: python3 tools/filmfestival/test_ff_caption.py
"""

import argparse
import ast
import io
import json
import os
import pathlib
import re
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_caption  # noqa: E402
import ff_frames  # noqa: E402
import ff_map  # noqa: E402
import ff_packet  # noqa: E402

TITLES = ("prisoner", "bus stop", "bus_stop", "the_prisoner", "FILM", "film")


class Base(unittest.TestCase):
    """Two films: film 7 with 20 frames and film 10 with 50, each frame a few known bytes."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.out = os.path.join(self.dir.name, "out")
        films = []
        for film, count in ((7, 20), (10, 50)):
            cover, frames = ff_packet.CLIPS[film], []
            for k in range(count):
                name = f"frames/{cover}/f{k + 1:04d}.jpg"
                data = f"picture {film}-{k}".encode()
                path = pathlib.Path(self.out, name)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
                frames.append(
                    {
                        "file": name,
                        "seconds": 2 * k,
                        "sha256": ff_caption.hashlib.sha256(data).hexdigest(),
                    }
                )
            films.append({"film": film, "cover": cover, "frames": frames})
        self.frames_doc = {"schema": ff_frames.SCHEMA, "films": films}
        self.write_frames()
        self.calls = []
        self.model = ff_caption.MODEL
        self.silent = set()
        self.reply = None
        self.addCleanup(setattr, ff_map.subprocess, "run", ff_map.subprocess.run)
        ff_map.subprocess.run = self.cli
        self.addCleanup(setattr, ff_map, "cli_version", ff_map.cli_version)
        ff_map.cli_version = lambda: "cli 9"
        for stream in ("stdout", "stderr"):
            self.addCleanup(setattr, sys, stream, getattr(sys, stream))
            setattr(sys, stream, io.StringIO())

    def write_frames(self):
        with open(os.path.join(self.out, "frames.json"), "w") as handle:
            json.dump(self.frames_doc, handle)

    def cli(self, cmd, **kwargs):
        """Stand-in for the CLI: describes each file in its working directory by its bytes."""
        cwd = kwargs["cwd"]
        seen = {n: pathlib.Path(cwd, n).read_bytes().decode() for n in os.listdir(cwd)}
        self.calls.append({"cmd": cmd, "prompt": kwargs["input"], "cwd": cwd, **kwargs})
        self.calls[-1]["seen"] = seen
        if self.reply is not None:
            result = self.reply(seen)
        else:
            answer = {
                os.path.join(cwd, name): f"saw {content}"
                for name, content in seen.items()
                if content not in self.silent
            }
            result = json.dumps(answer)
        body = {"result": result, "modelUsage": {self.model: {}}}
        return argparse.Namespace(stdout=json.dumps(body), returncode=0)

    def tracked(self):
        path = os.path.join(self.dir.name, "tracked.json")
        with open(path, "w") as handle:
            json.dump(ff_caption.manifest(self.out), handle)
        return path

    def run_main(self):
        return ff_caption.main(["run", self.out, self.tracked(), "--jobs", "2"])

    def captions(self):
        with open(os.path.join(self.out, "frame-captions.json")) as handle:
            return json.load(handle)


class Blind(Base):
    def test_a_prompt_is_the_fixed_line_and_neutral_paths_and_nothing_else(self):
        self.assertEqual(self.run_main(), 0)
        self.assertEqual(len(self.calls), 2 + 4)
        for call in self.calls:
            lines = call["prompt"].split("\n")
            self.assertEqual(lines[0] + "\n", ff_caption.PROMPT)
            for line in lines[1:]:
                self.assertEqual(os.path.dirname(line), call["cwd"])
                self.assertRegex(os.path.basename(line), r"^img_\d\d\.jpg$")
            self.assertEqual(
                sorted(os.path.basename(line) for line in lines[1:]),
                sorted(call["seen"]),
            )

    def test_no_film_name_title_or_number_reaches_the_model(self):
        self.assertEqual(self.run_main(), 0)
        for call in self.calls:
            command = [c for c in call["cmd"] if c != ff_caption.SYSTEM]
            for text in [call["prompt"], call["cwd"], *command, *call["seen"]]:
                self.assertIsNone(ff_caption.leak(text), text)
            for text in [call["prompt"].replace(call["cwd"], ""), *call["seen"]]:
                for word in TITLES:
                    self.assertNotIn(word, text)
                # The only digits are a frame's place in its batch: no film code, no time.
                self.assertEqual(
                    re.sub(r"img_\d\d\.jpg", "", text).strip("/\n"),
                    re.sub(r"img_\d\d\.jpg|\d", "", text).strip("/\n"),
                )
            self.assertEqual(
                sorted(call["seen"]),
                [ff_caption.neutral(i) for i in range(len(call["seen"]))],
            )
            shown = call["cmd"][call["cmd"].index("--add-dir") + 1]
            self.assertEqual(shown, call["cwd"])
            # The model's directory is outside the frame store and is gone afterwards.
            self.assertNotEqual(os.path.commonpath([call["cwd"], self.out]), self.out)
            self.assertFalse(os.path.exists(call["cwd"]))

    def test_the_call_is_the_mappers_with_the_read_tool_and_the_frozen_model(self):
        os.environ["ANTHROPIC_API_KEY"] = "k"
        self.addCleanup(os.environ.pop, "ANTHROPIC_API_KEY", None)
        self.assertEqual(self.run_main(), 0)
        cmd = self.calls[0]["cmd"]
        self.assertEqual(cmd[:4], ["claude", "-p", "--model", "claude-sonnet-5-5"])
        self.assertEqual(cmd[cmd.index("--system-prompt") + 1], ff_caption.SYSTEM)
        self.assertEqual(cmd[cmd.index("--tools") + 1], "Read")
        self.assertEqual(cmd[cmd.index("--allowedTools") + 1], "Read")
        self.assertNotIn("ANTHROPIC_API_KEY", self.calls[0]["env"])
        record = os.listdir(os.path.join(self.out, "caption-cache"))[0]
        with open(os.path.join(self.out, "caption-cache", record)) as handle:
            self.assertEqual(json.load(handle)["cliVersion"], "cli 9")

    def test_film_names_are_found_whatever_separates_their_words(self):
        for text in (
            "/x/the_prisoner/f1.jpg",
            "The Prisoner",
            "post-it love",
            "/tmp/Bus Stop/a",
            "catchmeifyoucan",
        ):
            self.assertIsNotNone(ff_caption.leak(text), text)
        self.assertIsNone(ff_caption.leak("/tmp/frames-ab12/img_03.jpg"))

    def test_a_temporary_directory_that_names_a_film_is_refused(self):
        parent = os.path.join(self.dir.name, "the rock")
        os.makedirs(parent)
        self.addCleanup(setattr, tempfile, "tempdir", tempfile.tempdir)
        tempfile.tempdir = parent
        with self.assertRaises(ff_map.ProtocolFailure):
            self.run_main()
        self.assertEqual(self.calls, [])

    def test_the_instruction_is_the_sherlock_instruction_unchanged(self):
        source = pathlib.Path(__file__).parent.parent / "sherlock_mapper"
        tree = ast.parse((source / "caption_frames.py").read_text())
        found = [
            ast.literal_eval(node.value)
            for node in tree.body
            if isinstance(node, ast.Assign) and node.targets[0].id == "SYSTEM"
        ]
        self.assertEqual(found, [ff_caption.SYSTEM])


class Batches(Base):
    def test_batches_are_sixteen_frames_of_one_film_in_time_order(self):
        plan = ff_caption.plan(self.frames_doc)
        self.assertEqual([len(b["frames"]) for b in plan], [16, 4, 16, 16, 16, 2])
        self.assertEqual([b["film"] for b in plan], [7, 7, 10, 10, 10, 10])
        for batch in plan:
            seconds = [f["seconds"] for f in batch["frames"]]
            self.assertEqual(seconds, sorted(seconds))
        self.assertEqual(plan[1]["frames"][0]["seconds"], 32)

    def test_descriptions_return_to_the_frames_they_describe(self):
        self.assertEqual(self.run_main(), 0)
        doc = self.captions()
        self.assertEqual(sorted(doc["films"]), ["10", "7"])
        for film, count in (("7", 20), ("10", 50)):
            rows = doc["films"][film]
            self.assertEqual(
                [r["seconds"] for r in rows], [2 * k for k in range(count)]
            )
            for k, row in enumerate(rows):
                self.assertEqual(row["description"], f"saw picture {film}-{k}")
                listed = self.frames_doc["films"][0 if film == "7" else 1]["frames"][k]
                self.assertEqual(row["sha256"], listed["sha256"])
        self.assertEqual(doc["counts"]["7"], {"frames": 20, "undescribed": 0})
        self.assertEqual(doc["undescribed"], 0)
        self.assertEqual((doc["model"], doc["cliVersion"]), (ff_caption.MODEL, "cli 9"))
        self.assertEqual(
            doc["framesSha256"],
            ff_frames.file_sha(os.path.join(self.out, "frames.json")),
        )


class Cache(Base):
    def test_the_key_follows_the_images_not_their_paths(self):
        before = ff_caption.manifest(self.out)["batches"]
        self.assertEqual(self.run_main(), 0)
        made = len(self.calls)
        os.remove(os.path.join(self.out, "frame-captions.json"))
        # Move film 7's frames to other file names: same bytes, other paths.
        for frame in self.frames_doc["films"][0]["frames"]:
            moved = frame["file"].replace("/f", "/moved")
            os.rename(
                os.path.join(self.out, frame["file"]), os.path.join(self.out, moved)
            )
            frame["file"] = moved
        self.write_frames()
        self.assertEqual(
            [b["key"] for b in ff_caption.manifest(self.out)["batches"]],
            [b["key"] for b in before],
        )
        self.assertEqual(self.run_main(), 0)
        self.assertEqual(len(self.calls), made)
        self.assertEqual(
            self.captions()["films"]["7"][3]["description"], "saw picture 7-3"
        )

    def test_the_key_depends_on_content_order_model_instruction_and_attempt(self):
        base = ff_caption.key(["a", "b"], 0)
        self.assertNotEqual(base, ff_caption.key(["a", "c"], 0))
        self.assertNotEqual(base, ff_caption.key(["b", "a"], 0))
        self.assertNotEqual(base, ff_caption.key(["a", "b"], 1))
        for name in ("MODEL", "SYSTEM"):
            kept = getattr(ff_caption, name)
            setattr(ff_caption, name, "other")
            try:
                self.assertNotEqual(base, ff_caption.key(["a", "b"], 0))
            finally:
                setattr(ff_caption, name, kept)
        self.assertEqual(base, ff_caption.key(["a", "b"], 0))

    def test_a_frame_whose_bytes_changed_is_refused_before_any_call(self):
        frame = self.frames_doc["films"][0]["frames"][17]
        pathlib.Path(self.out, frame["file"]).write_bytes(b"swapped")
        tracked = self.tracked()
        with self.assertRaises(ff_map.ProtocolFailure):
            ff_caption.main(["run", self.out, tracked, "--jobs", "1"])
        self.assertFalse(os.path.exists(os.path.join(self.out, "frame-captions.json")))
        self.assertTrue(all("7-17" not in str(c["seen"]) for c in self.calls))


class Model(Base):
    def test_a_reply_from_another_model_raises_and_writes_nothing(self):
        self.model = "claude-haiku-5-5"
        with self.assertRaises(ff_map.ProtocolFailure):
            self.run_main()
        self.assertFalse(os.path.exists(os.path.join(self.out, "frame-captions.json")))
        self.assertEqual(os.listdir(os.path.join(self.out, "caption-cache")), [])


class Undescribed(Base):
    def test_only_missing_frames_are_asked_again_and_three_attempts_are_the_limit(self):
        self.silent = {"picture 10-20"}
        self.assertEqual(self.run_main(), 0)
        again = [c for c in self.calls if "(attempt" in c["prompt"]]
        self.assertEqual(len(again), 2)
        for attempt, call in enumerate(again, start=1):
            self.assertEqual(call["seen"], {"img_05.jpg": "picture 10-20"})
            self.assertTrue(call["prompt"].endswith(f"(attempt {attempt})."))
            self.assertEqual(call["prompt"].count("img_"), 1)
        doc = self.captions()
        self.assertIsNone(doc["films"]["10"][20]["description"])
        self.assertEqual(doc["films"]["10"][21]["description"], "saw picture 10-21")
        # 1 of 50 is exactly 2%: within the limit.
        self.assertEqual(doc["counts"]["10"], {"frames": 50, "undescribed": 1})
        self.assertEqual(doc["undescribed"], 1)
        receipts = [b for b in doc["batches"] if b["film"] == 10]
        self.assertEqual([b["attempts"] for b in receipts], [1, 3, 1, 1])

    def test_a_film_over_two_percent_undescribed_stops_the_run_without_a_file(self):
        self.silent = {"picture 7-3"}
        self.assertEqual(self.run_main(), 1)
        self.assertFalse(os.path.exists(os.path.join(self.out, "frame-captions.json")))
        self.assertIn("film 7", sys.stderr.getvalue())

    def test_two_of_fifty_is_over_the_limit(self):
        self.silent = {"picture 10-20", "picture 10-41"}
        self.assertEqual(self.run_main(), 1)
        self.assertFalse(os.path.exists(os.path.join(self.out, "frame-captions.json")))

    def test_an_unreadable_reply_is_asked_again_in_full(self):
        replies = iter(["no json here"])

        def reply(seen):
            try:
                return next(replies)
            except StopIteration:
                return json.dumps({name: f"saw {text}" for name, text in seen.items()})

        self.reply = reply
        self.assertEqual(
            ff_caption.main(["run", self.out, self.tracked(), "--jobs", "1"]), 0
        )
        self.assertEqual(len(self.calls), 7)
        self.assertEqual(len(self.calls[1]["seen"]), 16)
        self.assertEqual(self.captions()["undescribed"], 0)


class Replies(unittest.TestCase):
    def read(self, doc, positions=(0, 1, 2)):
        return ff_caption.read_reply(json.dumps(doc), list(positions))

    def test_base_names_and_paths_both_name_a_frame(self):
        found, stray = self.read({"img_01.jpg": "one", "/any/dir/img_03.jpg": "three"})
        self.assertEqual((found, stray), ({0: "one", 2: "three"}, 0))

    def test_names_of_files_that_were_not_asked_are_counted_and_dropped(self):
        found, stray = self.read(
            {"img_01.jpg": "one", "img_09.jpg": "nine", "f0001.jpg": "x", "1": "y"},
        )
        self.assertEqual((found, stray), ({0: "one"}, 3))
        found, stray = self.read({"img_01.jpg": "one"}, positions=(1, 2))
        self.assertEqual((found, stray), ({}, 1))

    def test_a_file_named_twice_gets_no_description(self):
        found, _ = self.read(
            {
                "img_02.jpg": "a",
                "/d/img_02.jpg": "b",
                "/e/img_02.jpg": "c",
                "img_01.jpg": "x",
            }
        )
        self.assertEqual(found, {0: "x"})

    def test_unusable_values_are_no_description(self):
        long = "word " * 100
        found, stray = self.read(
            {"img_01.jpg": "", "img_02.jpg": 7, "img_03.jpg": long}
        )
        self.assertEqual((found, stray), ({}, 0))
        found, _ = self.read({"img_01.jpg": " a man\n in  a hat "})
        self.assertEqual(found, {0: "a man in a hat"})
        self.assertEqual(ff_caption.read_reply("nothing", [0]), ({}, 0))
        self.assertEqual(ff_caption.read_reply('["img_01.jpg"]', [0]), ({}, 0))

    def test_text_that_looks_like_a_row_id_is_left_alone(self):
        found, _ = self.read({"img_01.jpg": "a door marked , r12 , opens"})
        self.assertEqual(found, {0: "a door marked , r12 , opens"})


class Manifest(Base):
    def test_the_manifest_records_what_will_be_asked_and_makes_no_call(self):
        self.assertEqual(ff_caption.main(["manifest", self.out]), 0)
        with open(os.path.join(self.out, "caption-manifest.json")) as handle:
            doc = json.load(handle)
        self.assertEqual(self.calls, [])
        self.assertEqual(doc, ff_caption.manifest(self.out))
        self.assertEqual(doc["model"], "claude-sonnet-5-5")
        self.assertEqual(doc["instructionSha256"], ff_packet.sha(ff_caption.SYSTEM))
        self.assertEqual((doc["batch"], doc["attempts"], doc["frames"]), (16, 3, 70))
        self.assertEqual(doc["maxUndescribedFraction"], 0.02)
        self.assertEqual(len(doc["batches"]), 6)
        self.assertTrue(
            all(re.fullmatch(r"[0-9a-f]{64}", b["key"]) for b in doc["batches"])
        )
        self.assertEqual(ff_caption.main(["manifest", self.out]), 2)

    def test_run_refuses_unless_its_plan_equals_the_tracked_manifest(self):
        doc = ff_caption.manifest(self.out)
        tracked = self.tracked()
        self.assertIsNone(ff_caption.refusal(self.out, tracked, doc, {}))
        other = dict(doc, batch=8)
        self.assertIn("differ", ff_caption.refusal(self.out, tracked, other, {}))
        routed = {"ANTHROPIC_BASE_URL": "x"}
        self.assertIn(
            "ANTHROPIC_BASE_URL", ff_caption.refusal(self.out, tracked, doc, routed)
        )
        # A frame list that changed after the freeze changes the plan.
        self.frames_doc["films"][1]["frames"].pop()
        self.write_frames()
        self.assertEqual(ff_caption.main(["run", self.out, tracked]), 2)
        self.assertEqual(self.calls, [])

    def test_finished_captions_are_never_overwritten(self):
        self.assertEqual(self.run_main(), 0)
        made = len(self.calls)
        self.assertEqual(self.run_main(), 2)
        self.assertEqual(len(self.calls), made)


if __name__ == "__main__":
    unittest.main()
