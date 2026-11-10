"""Tests for the frame sampler, on invented clips.

Run from the repository root: python3 tools/filmfestival/test_ff_frames.py
"""

import io
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_frames  # noqa: E402
import ff_packet  # noqa: E402

SHOWINFO = """[Parsed_showinfo_1 @ 0x79e903cf00] config in time_base: 1/12800, frame_rate: 25/1
[Parsed_showinfo_1 @ 0x79e903cf00] n:   0 pts:      0 pts_time:0       duration:    512 s:480x360
[Parsed_showinfo_1 @ 0x79e903cf00]   side data - H.26[45] User Data Unregistered SEI message
[Parsed_showinfo_1 @ 0x79e903cf00] n:   1 pts:  25600 pts_time:2       duration:    512 s:480x360
[Parsed_showinfo_1 @ 0x79e903cf00] n:   2 pts:  51712 pts_time:4.04    duration:    512 s:480x360
frame=    3 fps=0.0 q=4.0 Lsize=N/A time=00:00:04.04 bitrate=N/A speed= 100x
"""


class Base(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.root = os.path.join(self.dir.name, "data")
        self.out = os.path.join(self.dir.name, "out")
        for cover in ff_packet.CLIPS.values():
            path = pathlib.Path(ff_frames.clip_path(self.root, cover))
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(f"clip {cover}".encode())
        # Film n's clip is 2n + 3 seconds long, so it has n + 2 frames (0, 2, ..., 2n + 2).
        self.seconds = {
            ff_frames.clip_path(self.root, cover): 2 * film + 3.0
            for film, cover in ff_packet.CLIPS.items()
        }
        self.patch("clip_seconds", lambda clip: self.seconds[clip])
        self.patch("ffmpeg_version", lambda: "ffmpeg test")
        self.patch("sample", self.sample)
        self.shift = 0.0
        for stream in ("stdout", "stderr"):
            self.addCleanup(setattr, sys, stream, getattr(sys, stream))
            setattr(sys, stream, io.StringIO())

    def patch(self, name, value):
        self.addCleanup(setattr, ff_frames, name, getattr(ff_frames, name))
        setattr(ff_frames, name, value)

    def sample(self, clip, directory):
        count = int(self.seconds[clip] // 2) + 1
        for k in range(1, count + 1):
            name = os.path.join(directory, f"f{k:04d}.jpg")
            pathlib.Path(name).write_bytes(f"{clip} frame {k}".encode())
        return [k * 2 + self.shift for k in range(count)]

    def extract(self):
        self.assertEqual(ff_frames.main(["extract", self.root, self.out]), 0)
        return ff_frames.load(self.out)


class Extract(Base):
    def test_the_list_names_ten_films_in_order_with_times_and_hashes(self):
        doc = self.extract()
        self.assertEqual([f["film"] for f in doc["films"]], list(range(1, 11)))
        self.assertEqual(
            [f["cover"] for f in doc["films"]],
            [ff_packet.CLIPS[n] for n in range(1, 11)],
        )
        self.assertNotIn("cartoon", json.dumps(doc))
        self.assertEqual(doc["totalFrames"], sum(n + 2 for n in range(1, 11)))
        third = doc["films"][2]
        self.assertEqual(third["clipSeconds"], 9.0)
        self.assertEqual([f["seconds"] for f in third["frames"]], [0, 2, 4, 6, 8])
        self.assertEqual(third["frames"][1]["file"], "frames/the_boyfriend/f0002.jpg")
        on_disk = os.path.join(self.out, third["frames"][1]["file"])
        self.assertEqual(third["frames"][1]["sha256"], ff_frames.file_sha(on_disk))
        clip = ff_frames.clip_path(self.root, "the_boyfriend")
        self.assertEqual(third["clipSha256"], ff_frames.file_sha(clip))
        self.assertEqual((doc["intervalSeconds"], doc["width"]), (2, 512))

    def test_the_file_is_written_with_sorted_keys_and_is_reproducible(self):
        self.extract()
        first = pathlib.Path(self.out, "frames.json").read_text()
        self.assertEqual(
            first, json.dumps(json.loads(first), indent=1, sort_keys=True) + "\n"
        )
        self.out = os.path.join(self.dir.name, "again")
        self.extract()
        self.assertEqual(pathlib.Path(self.out, "frames.json").read_text(), first)

    def test_existing_output_is_refused(self):
        self.extract()
        self.assertEqual(ff_frames.main(["extract", self.root, self.out]), 2)
        other = os.path.join(self.dir.name, "other")
        os.makedirs(os.path.join(other, "frames"))
        self.assertEqual(ff_frames.main(["extract", self.root, other]), 2)
        self.assertFalse(os.path.exists(os.path.join(other, "frames.json")))

    def test_frames_one_second_late_are_refused_and_no_list_is_written(self):
        # What ffmpeg's fps=1/2 filter does with its default rounding.
        self.shift = 1.0
        self.assertEqual(ff_frames.main(["extract", self.root, self.out]), 2)
        self.assertFalse(os.path.exists(os.path.join(self.out, "frames.json")))


class Times(unittest.TestCase):
    def names(self, n):
        return [f"f{k:04d}.jpg" for k in range(1, n + 1)]

    def test_frames_at_even_seconds_to_the_end_of_the_clip_pass(self):
        ff_frames.check_times("x", [0, 2, 4.04], self.names(3), 5.0)
        ff_frames.check_times("x", [0, 2, 4], self.names(3), 6.0)

    def test_a_late_early_missing_or_extra_frame_is_refused(self):
        for stamps, names, seconds in (
            ([0, 2.06, 4], self.names(3), 5.0),
            ([0, 1.96, 4], self.names(3), 5.0),
            ([0, 2], self.names(2), 6.1),
            ([0, 2, 4], self.names(3), 4.0),
            ([0, 2, 4], self.names(2), 5.0),
            ([0, 2, 4], ["f0001.jpg", "f0002.jpg", "f0004.jpg"], 5.0),
            ([], [], 5.0),
        ):
            with self.assertRaises(ff_frames.FrameError):
                ff_frames.check_times("x", stamps, names, seconds)

    def test_source_timestamps_are_read_from_showinfo_frame_lines_only(self):
        found = [float(m.group(1)) for m in ff_frames.PTS.finditer(SHOWINFO)]
        self.assertEqual(found, [0.0, 2.0, 4.04])

    @unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg is not installed")
    def test_ffmpeg_writes_the_frames_at_0_2_4_seconds(self):
        with tempfile.TemporaryDirectory() as directory:
            clip = os.path.join(directory, "clip.mp4")
            cmd = ["ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi", "-i"]
            cmd += ["testsrc=duration=7:size=128x72:rate=25", "-pix_fmt", "yuv420p"]
            subprocess.run(cmd + [clip], check=True, timeout=120)
            frames = os.path.join(directory, "frames")
            os.makedirs(frames)
            stamps = ff_frames.sample(clip, frames)
            self.assertEqual(stamps, [0.0, 2.0, 4.0, 6.0])
            ff_frames.check_times("x", stamps, sorted(os.listdir(frames)), 7.0)
            self.assertEqual(ff_frames.clip_seconds(clip), 7.0)


class Verify(Base):
    def setUp(self):
        super().setUp()
        self.doc = self.extract()
        self.frame = os.path.join(self.out, "frames/the_shoe/f0002.jpg")

    def test_untouched_output_verifies(self):
        self.assertEqual(ff_frames.verify(self.root, self.out), [])
        self.assertEqual(ff_frames.main(["verify", self.root, self.out]), 0)

    def test_a_changed_frame_is_reported(self):
        pathlib.Path(self.frame).write_bytes(b"other")
        problems = ff_frames.verify(self.root, self.out)
        self.assertEqual(problems, ["frames/the_shoe/f0002.jpg: the file has changed"])
        self.assertEqual(ff_frames.main(["verify", self.root, self.out]), 1)

    def test_a_missing_or_extra_frame_is_reported(self):
        os.rename(self.frame, self.frame + ".moved")
        self.assertEqual(len(ff_frames.verify(self.root, self.out)), 2)
        os.rename(self.frame + ".moved", self.frame)
        pathlib.Path(self.out, "frames/bus_stop/f0099.jpg").write_bytes(b"x")
        problems = ff_frames.verify(self.root, self.out)
        self.assertEqual(len(problems), 2)
        self.assertIn("bus_stop: 13 files, 12 listed", problems)

    def test_a_changed_clip_is_reported(self):
        pathlib.Path(ff_frames.clip_path(self.root, "the_rock")).write_bytes(b"new")
        self.assertEqual(
            ff_frames.verify(self.root, self.out), ["the_rock: the clip has changed"]
        )

    def test_an_edited_list_is_reported(self):
        def rewrite(change):
            doc = json.loads(json.dumps(self.doc))
            change(doc)
            with open(os.path.join(self.out, "frames.json"), "w") as handle:
                json.dump(doc, handle)
            return ff_frames.verify(self.root, self.out)

        def later(doc):
            doc["films"][0]["frames"][1]["seconds"] = 3

        def swapped(doc):
            doc["films"][0]["cover"] = "the_record"

        def fewer(doc):
            del doc["films"][9]

        def schema(doc):
            doc["schema"] = "other"

        self.assertEqual(len(rewrite(later)), 1)
        self.assertIn("film 1 is listed with the clip the_record", rewrite(swapped))
        self.assertIn("the films listed are not the ten films in order", rewrite(fewer))
        rewrite(lambda doc: None)
        self.assertEqual(ff_frames.verify(self.root, self.out), [])
        with self.assertRaises(ff_frames.FrameError):
            rewrite(schema)
        self.assertEqual(ff_frames.main(["verify", self.root, self.out]), 1)


if __name__ == "__main__":
    unittest.main()
