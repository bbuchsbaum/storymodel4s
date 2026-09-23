"""Part-correct scene frame planning on synthetic extents; no media, no ffmpeg, no data root.

These tests cover generality: the frozen Sherlock manifest records no straddling scene, so none of
them reproduces a measured historical Sherlock failure. Exact seek/media correspondence is outside
their claim.
"""
from fractions import Fraction as F
import csv
import importlib.util
import io
import json
import math
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parents[1]
MODULE = Path(os.environ.get("SCENE_FRAMES_MODULE", HERE / "extract_scene_frames.py"))
spec = importlib.util.spec_from_file_location("extract_scene_frames", MODULE)
esf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(esf)

A, B = "media-part-a", "media-part-b"


def exact(x):
    """Synthetic times as the parser would produce them: exact rationals of their decimal text.

    Non-finite floats are passed through unchanged so the planner's own refusal is what is tested.
    """
    return F(repr(x)) if isinstance(x, float) and math.isfinite(x) else x


def extent(start, end, rows):
    return {"start": exact(start), "end": exact(end), "rows": list(rows)}


HEADER = ["Scene Segments", "Start Time (s) ", "End Time (s) "]


def tsv(rows):
    """Annotation-shaped TSV text read back through csv, as scenes() reads the real file."""
    buf = io.StringIO()
    csv.writer(buf, delimiter="\t").writerows([HEADER] + rows)
    return csv.reader(io.StringIO(buf.getvalue()), delimiter="\t")


def parsed_counts(rows, per, part_a_last_row):
    with patch.object(esf, "PART_A_LAST_ROW", part_a_last_row):
        order, sc = esf.parse_scenes(tsv(rows))
    return [p["count"] for p in esf.plan_scene(sc[order[0]], per)]


# A scene that crosses the part boundary: 10 s at the end of part A (2 rows) and 30 s at the start
# of part B (5 rows), so part B holds the majority of rows and the majority of time.
STRADDLE = {A: extent(100.0, 110.0, [480, 481]), B: extent(0.0, 30.0, range(482, 487))}


def centres(lo, hi, m):
    """Independent recomputation: the midpoints of m equal slices of [lo, hi]."""
    width = (hi - lo) / m
    return [lo + width * k + width / 2 for k in range(m)]


class SceneFramesSuite(unittest.TestCase):
    def test_straddling_scene_samples_every_part_inside_its_own_extent(self):
        plan = esf.plan_scene(STRADDLE, 8)
        self.assertEqual([p["part"] for p in plan], [A, B])
        # 8 frames: one per part, then 6 spare split 10:30 -> quotas 1.5 and 4.5, the tie on the
        # .5 remainder goes to the earlier part: A = 1+1+1 = 3, B = 1+4 = 5.
        self.assertEqual([p["count"] for p in plan], [3, 5])
        for p, (lo, hi, m) in zip(plan, [(100.0, 110.0, 3), (0.0, 30.0, 5)]):
            for got, want in zip(p["offsets"], centres(lo, hi, m)):
                self.assertAlmostEqual(got, want, places=9)
            self.assertTrue(all(lo < o < hi for o in p["offsets"]))

    def test_every_part_gets_a_frame_and_counts_sum_exactly(self):
        tiny = {A: extent(0.0, 0.1, [1]), B: extent(0.0, 100.0, [2])}
        self.assertEqual([p["count"] for p in esf.plan_scene(tiny, 8)], [1, 7])
        for per in range(2, 13):
            for da in (0.01, 0.5, 1.0, 3.0, 7.25, 50.0):
                counts = esf.allocate([exact(da), F(10)], per)
                self.assertEqual(sum(counts), per, (per, da))
                self.assertTrue(all(c >= 1 for c in counts), (per, da, counts))

    def test_remainder_tie_goes_to_the_earlier_part(self):
        self.assertEqual(esf.allocate([F(5), F(5)], 3), [2, 1])
        self.assertEqual(esf.allocate([F(5), F(5), F(5)], 5), [2, 2, 1])

    def test_decimal_tie_is_decided_on_the_annotation_decimals_not_binary_floats(self):
        # 0.2 s and 2.2 s with 6 spare frames: quotas 6*0.2/2.4 = 0.5 and 6*2.2/2.4 = 5.5 exactly,
        # a tie that goes to the earlier part -> [1+0+1, 1+5] = [2, 6]. Built from binary floats,
        # 0.2 and 2.2 do not tie and the later part wins instead ([1, 7]).
        parts = {A: extent(10.0, 10.2, [1]), B: extent(0.0, 2.2, [2])}
        self.assertEqual([p["count"] for p in esf.plan_scene(parts, 8)], [2, 6])
        self.assertEqual(esf.allocate([F("0.2"), F("2.2")], 8), [2, 6])

    def test_parser_decides_ties_on_source_decimal_text(self):
        # Through the real TSV parser, row 0 in part A and row 1 in part B, 8 frames.
        # 0.2 s and 2.2 s: exact quotas 0.5 and 5.5 tie -> earlier part -> [2, 6]; parsing via
        # float gives [1, 7].
        self.assertEqual(
            parsed_counts([["s", "10.0", "10.2"], ["", "0", "2.2"]], 8, 0), [2, 6]
        )
        # 2.20000000000000001 is not 2.2: exact quotas no longer tie -> [1, 7]; parsing via
        # float and repr collapses it to 2.2 and gives [2, 6].
        self.assertEqual(
            parsed_counts(
                [["s", "10.0", "10.2"], ["", "0", "2.20000000000000001"]], 8, 0
            ),
            [1, 7],
        )

    def test_parser_refuses_each_invalid_row_before_aggregating(self):
        # a token Fraction() would accept but the annotation grammar does not, first; then the
        # reviewer's witness: a later nan row inside a finite scene
        for bad in (
            ["", "1e1", "12"],
            ["", "nan", "nan"],
            ["", "", "3"],
            ["", "-1", "3"],
        ):
            with self.assertRaises(esf.PlanRefusal, msg=bad):
                esf.parse_scenes(tsv([["s", "0", "1"], bad]))
        with self.assertRaises(esf.PlanRefusal):
            esf.parse_scenes(tsv([["s", "0", "1"], ["", "5", "4"]]))  # end before start
        with self.assertRaises(esf.PlanRefusal):
            esf.parse_scenes(
                tsv([["s", "0", "1"], [""]])
            )  # time cells missing entirely

    def test_parser_admits_the_real_annotation_shape(self):
        # integer cells and a zero-length row (Sherlock row 12 is 45-45) inside a longer scene
        order, sc = esf.parse_scenes(
            tsv([["s", "40", "45"], ["", "45", "45"], ["t", "45", "50"]])
        )
        self.assertEqual(order, ["s", "t"])
        a = sc["s"][A]
        self.assertEqual((a["start"], a["end"], a["rows"]), (F(40), F(45), [0, 1]))
        self.assertTrue(all(isinstance(x, F) for x in (a["start"], a["end"])))

    def test_allocate_refuses_what_it_cannot_divide_exactly(self):
        for durations in ([0.2, 2.2], [F(-1), F(3)], [F(0), F(1)]):
            with self.assertRaises(esf.PlanRefusal, msg=durations):
                esf.allocate(durations, 4)

    def test_timeline_order_is_declared_not_lexical(self):
        order = ("z-opening", "a-closing")
        parts = {
            "a-closing": extent(0.0, 4.0, [9]),
            "z-opening": extent(10.0, 14.0, [8]),
        }
        self.assertEqual(
            [p["part"] for p in esf.plan_scene(parts, 3, order)],
            ["z-opening", "a-closing"],
        )
        # the tie on equal durations therefore favours the declared-first part
        self.assertEqual([p["count"] for p in esf.plan_scene(parts, 3, order)], [2, 1])
        # an undeclared part beside a declared one must refuse, not be silently dropped
        with self.assertRaises(esf.PlanRefusal):
            esf.plan_scene(
                {
                    "z-opening": extent(0.0, 1.0, [1]),
                    "undeclared": extent(0.0, 1.0, [2]),
                },
                3,
                order,
            )

    def test_single_part_offsets_equal_v1_from_half_a_second_up(self):
        for lo, hi in [(0.0, 0.5), (12.25, 40.0), (1234.5, 1300.75)]:
            per = 8
            span = max(hi - lo, 0.5)
            v1 = [lo + span * (k + 0.5) / per for k in range(per)]
            (p,) = esf.plan_scene({A: extent(lo, hi, [0])}, per)
            self.assertEqual(p["offsets"], v1)

    def test_short_extent_control_v1_sampled_outside_v2_stays_inside(self):
        lo, hi, per = 20.0, 20.2, 8
        v1 = [lo + max(hi - lo, 0.5) * (k + 0.5) / per for k in range(per)]
        self.assertTrue(any(o > hi for o in v1))  # the legacy formula leaves the extent
        try:
            (p,) = esf.plan_scene({A: extent(lo, hi, [0])}, per)
        except esf.PlanRefusal as e:
            self.fail(
                f"a 0.2 s extent is samplable and must be sampled, not refused: {e}"
            )
        self.assertEqual(
            p["offsets"], [lo + (hi - lo) * (k + 0.5) / per for k in range(per)]
        )
        self.assertTrue(all(lo < o < hi for o in p["offsets"]))

    def test_unsamplable_extents_and_budgets_refuse(self):
        bad = [  # checkable-without-crash cases first, so a skipped check fails the assertion, not by error
            (-1.0, 3.0),
            (5.0, 4.0),
            (0.0, 0.0),
            (float("nan"), 3.0),
            (0.0, float("nan")),
            (0.0, float("inf")),
            (float("-inf"), 1.0),
        ]
        for lo, hi in bad:
            with self.assertRaises(esf.PlanRefusal, msg=(lo, hi)):
                esf.plan_scene({A: extent(lo, hi, [0])}, 8)
        with self.assertRaises(esf.PlanRefusal):
            esf.plan_scene(STRADDLE, 1)

    def test_extent_below_float_resolution_refuses_rather_than_sampling_a_bound(self):
        lo, hi = 1e9, 1e9 + 1e-6
        naive = [lo + (hi - lo) * (k + 0.5) / 8 for k in range(8)]
        self.assertFalse(
            all(lo < o < hi for o in naive)
        )  # the hazard exists at this magnitude
        with self.assertRaises(esf.PlanRefusal):
            esf.plan_scene({A: extent(lo, hi, [0])}, 8)

    def run_main(self, scenes, per=8, w=4, h=2):
        calls = []

        def fake_grab(binary, src, seconds, width, height, scratch):
            calls.append((src, seconds))
            return bytes(width * height * 3)

        out = tempfile.TemporaryDirectory()
        self.addCleanup(out.cleanup)
        with patch.object(esf, "scenes", lambda: scenes), patch.object(
            esf, "grab", fake_grab
        ):
            esf.main(["x", out.name, str(per), str(w), str(h)])
        return Path(out.name), calls

    def test_refusal_happens_before_any_frame_is_extracted(self):
        order = ["good", "bad"]
        sc = {"good": {A: extent(0.0, 10.0, [0])}, "bad": {A: extent(3.0, 3.0, [1])}}
        calls = []
        out = tempfile.TemporaryDirectory()
        self.addCleanup(out.cleanup)
        with patch.object(esf, "scenes", lambda: (order, sc)), patch.object(
            esf, "grab", lambda *a: calls.append(a) or b""
        ):
            with self.assertRaises(SystemExit):
                esf.main(["x", out.name, "8", "4", "2"])
        self.assertEqual(calls, [])
        self.assertFalse((Path(out.name) / "scene-frames.bgr").exists())

    def test_manifest_v2_records_per_part_extents_and_ordinals(self):
        order = ["opening", "crossing"]
        sc = {"opening": {A: extent(0.0, 40.0, [0, 1])}, "crossing": STRADDLE}
        out, calls = self.run_main((order, sc))
        m = json.loads((out / "scene-manifest.json").read_text())
        self.assertEqual(m["schema"], "scene-frames/v2")
        self.assertEqual(m["straddlingScenes"], ["crossing"])
        self.assertEqual(m["frameCount"], 16)
        self.assertEqual((out / "scene-frames.bgr").stat().st_size, 16 * 4 * 2 * 3)
        crossing = m["scenes"][1]
        self.assertEqual(crossing["frameOrdinals"], [8, 15])
        self.assertEqual(
            [
                (
                    p["part"],
                    p["startSeconds"],
                    p["endSeconds"],
                    p["rows"],
                    p["frameOrdinals"],
                )
                for p in crossing["parts"]
            ],
            [
                (A, 100.0, 110.0, [480, 481], [8, 10]),
                (B, 0.0, 30.0, [482, 486], [11, 15]),
            ],
        )
        self.assertNotIn("part", crossing)  # no v1 single-part field left to be misread
        # frames 8-10 were read from part A's file, 11-15 from part B's
        self.assertEqual(
            [src for src, _ in calls[8:]], [esf.PARTS[A]] * 3 + [esf.PARTS[B]] * 5
        )
        self.assertTrue(all(0.0 < s < 30.0 for _, s in calls[11:]))


if __name__ == "__main__":
    unittest.main()
