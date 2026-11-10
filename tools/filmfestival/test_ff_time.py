"""Tests for the clip clock and the time-to-scene rule, on invented films.

Run from the repository root: python3 tools/filmfestival/test_ff_time.py
"""

import copy
import json
import os
import pathlib
import sys
import tempfile
import unittest
from fractions import Fraction

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_gold  # noqa: E402
import ff_score  # noqa: E402
import ff_time  # noqa: E402

TITLE = "X in bold white text fades in on a black screen"

# Invented films. Scene 1 is the cartoon. Each film opens with a title card.
#   film 1  scenes 2 (title only), 3, 4, 5; scene 5's rows are out of order
#   film 2  scenes 6 (title only), 7, 8, 9; its clip runs at half the annotation's speed
#   film 3  scenes 10, 11, 12; scene 10 holds the title row and a content row
#   films 4-10  a title-only scene and two more, ten seconds apart
ROWS = [
    # run, film, scene, start, description
    ("Run 1", "1. Cartoon intro 1", 1, 0, "ZZ singing snacks"),
    ("Run 1", "2. Alpha", 2, 95, TITLE),
    ("Run 1", "2. Alpha", 3, 100, "ZZ a man waits"),
    ("Run 1", "2. Alpha", 3, 104, "ZZ he sits"),
    ("Run 1", "2. Alpha", 4, 110, "ZZ a dog arrives"),
    ("Run 1", "2. Alpha", 5, 130, "ZZ they leave"),
    ("Run 1", "2. Alpha", 5, 125, "ZZ a door opens"),
    ("Run 1", "3. Beta", 6, 190, TITLE),
    ("Run 1", "3. Beta", 7, 200, "ZZ rain"),
    ("Run 1", "3. Beta", 8, 220, "ZZ a bus"),
    ("Run 1", "3. Beta", 9, 260, "ZZ a street"),
    ("Run 1", "4. Gamma", 10, 295, TITLE),
    ("Run 1", "4. Gamma", 10, 300, "ZZ a kettle boils"),
    ("Run 1", "4. Gamma", 11, 310, "ZZ tea is poured"),
    ("Run 1", "4. Gamma", 12, 320, "ZZ a cup breaks"),
]
CLOCK = {
    1: {"C0": "100", "a": "1", "b": "0.5"},
    2: {"C0": "200", "a": "0.5", "b": "0"},
    3: {"C0": "300", "a": "1", "b": "-0.5"},
}
DURATIONS = {1: 40.0, 2: 50.0, 3: 30.0}
for _film in range(4, 11):
    _start, _scene = 1000 * _film, 13 + 3 * (_film - 4)
    _name = f"{_film + 1}. Film{_film}"
    ROWS.append(("Run 2", _name, _scene, _start - 5, TITLE))
    ROWS.append(("Run 2", _name, _scene + 1, _start, f"ZZ first part of {_film}"))
    ROWS.append(("Run 2", _name, _scene + 2, _start + 10, f"ZZ second part of {_film}"))
    CLOCK[_film] = {"C0": str(_start), "a": "1", "b": "0"}
    DURATIONS[_film] = 20.0


def annotation(rows=ROWS, scene_offset=0, segment_offset=500):
    """The annotation table. The offsets make scene and row ids easy to tell from times."""
    lines = ["segment\trun\tfilm\tscene_number\tstart_s\tdescription"]
    for segment, (run, film, scene, start, text) in enumerate(rows, start=1):
        ids = f"{segment + segment_offset}\t{run}\t{film}\t{scene + scene_offset}"
        lines.append(f"{ids}\t{start}\t{text}")
    return "\n".join(lines) + "\n"


def stamp(seconds):
    """Seconds as the m:ss string a model would give."""
    whole, rest = divmod(Fraction(str(seconds)), 1)
    text = f"{int(whole) // 60}:{int(whole) % 60:02d}"
    return text + (f"{float(rest):.3f}"[1:].rstrip("0") if rest else "")


class Base(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.addCleanup(setattr, ff_time, "CLOCK", ff_time.CLOCK)
        ff_time.CLOCK = copy.deepcopy(CLOCK)
        self.table = self.build()

    def build(self, rows=ROWS, durations=DURATIONS):
        path = os.path.join(self.dir.name, "annotation.tsv")
        pathlib.Path(path).write_text(annotation(rows), encoding="utf-8")
        return ff_time.load(path, durations)

    def scenes(self, film):
        return [(s, float(a), float(b)) for s, a, b in self.table[film]["scenes"]]

    def strict(self, film, text):
        return ff_time.strict_scene(self.table, film, ff_time.parse_time(text))

    def near(self, film, text):
        return ff_time.near_scenes(self.table, film, ff_time.parse_time(text))


class Parsing(unittest.TestCase):
    def test_minutes_and_seconds_with_an_optional_fraction(self):
        for text, seconds in (
            ("0:00", 0),
            ("1:05", 65),
            ("7:45", 465),
            ("07:45", 465),
            ("12:00", 720),
            ("0:09.5", Fraction(19, 2)),
            ("2:03.25", Fraction(493, 4)),
        ):
            self.assertEqual(ff_time.parse_time(text), seconds, text)
        self.assertIsInstance(ff_time.parse_time("0:09.5"), Fraction)

    def test_anything_else_is_not_a_time(self):
        for text in (
            "83",
            "83.5",
            83,
            83.5,
            True,
            None,
            "",
            "1:5",
            "1:60",
            "1:99",
            "1:05:00",
            "0:1:05",
            "100:00",
            "-1:05",
            "1:-5",
            " 1:05",
            "1:05 ",
            "1:05\n",
            "1m05s",
            "1.05",
            "1:05.",
            "1:05.1234",
            ":05",
            "1:",
            "a:05",
            ["1:05"],
            {"m": 1},
        ):
            self.assertIsNone(ff_time.parse_time(text), repr(text))


class Intervals(Base):
    def test_a_title_only_scene_gets_no_interval_and_the_clip_starts_at_content(self):
        # Scene 2 is the title card. Scene 3's row maps to 0.5 s; the clip is its from 0.
        self.assertEqual(
            self.scenes(1), [(3, 0.0, 10.5), (4, 10.5, 25.5), (5, 25.5, 40.0)]
        )
        self.assertEqual(self.strict(1, "0:00"), 3)
        for film in ff_gold.FILMS:
            self.assertEqual(self.table[film]["scenes"][0][1], 0)

    def test_a_scene_starts_at_its_earliest_row_whatever_the_row_order(self):
        # Scene 5's rows are listed 130 then 125.
        self.assertEqual(self.scenes(1)[2][1], 25.5)
        films = list(dict.fromkeys(r[1] for r in ROWS))
        reordered = [r for film in films for r in reversed(ROWS) if r[1] == film]
        self.assertNotEqual(reordered, ROWS)
        self.assertEqual(
            ff_time.canonical(self.build(reordered)), ff_time.canonical(self.table)
        )

    def test_a_scaled_clock_scales_the_scene_starts(self):
        # Film 2: a = 0.5. Annotation starts 200, 220, 260 become 0, 10, 30.
        self.assertEqual(
            self.scenes(2), [(7, 0.0, 10.0), (8, 10.0, 30.0), (9, 30.0, 50.0)]
        )
        self.assertEqual(self.strict(2, "0:29.9"), 8)
        self.assertEqual(self.strict(2, "0:30"), 9)

    def test_a_first_scene_that_holds_the_title_row_is_a_target_from_zero(self):
        self.assertEqual(
            self.scenes(3), [(10, 0.0, 9.5), (11, 9.5, 19.5), (12, 19.5, 30.0)]
        )

    def test_the_last_scene_ends_at_the_end_of_the_clip_and_includes_it(self):
        self.assertEqual(self.scenes(1)[-1], (5, 25.5, 40.0))
        self.assertEqual(self.strict(1, "0:39.9"), 5)
        self.assertEqual(self.strict(1, "0:40"), 5)
        self.assertIsNone(self.strict(1, "0:40.001"))
        self.assertIsNone(self.strict(1, "9:59"))
        self.assertIsNone(ff_time.strict_scene(self.table, 1, Fraction(-1, 10)))

    def test_a_clock_whose_c0_is_not_the_first_content_row_is_refused(self):
        for film, wrong in ((1, "95"), (1, "104"), (3, "295")):
            ff_time.CLOCK = copy.deepcopy(CLOCK)
            ff_time.CLOCK[film]["C0"] = wrong
            with self.assertRaises(ff_gold.GoldError):
                self.build()

    def test_an_annotation_that_does_not_fit_the_clip_is_refused(self):
        with self.assertRaises(ff_gold.GoldError):
            self.build(durations={**DURATIONS, 1: 25.5})
        with self.assertRaises(ff_gold.GoldError):
            self.build(durations={k: v for k, v in DURATIONS.items() if k != 4})
        # Two scenes starting together would leave one with no interval.
        for scene, start in ((4, 100), (4, 125)):
            tied = [r if r[2] != scene else r[:3] + (start, r[4]) for r in ROWS]
            with self.assertRaises(ff_gold.GoldError):
                self.build(tied)
        # A title-only scene before the content is skipped; one inside the clip is refused.
        self.build(ROWS + [("Run 1", "2. Alpha", 99, 97, TITLE)])
        with self.assertRaises(ff_gold.GoldError):
            self.build(ROWS + [("Run 1", "2. Alpha", 99, 112, TITLE)])

    def test_the_table_text_lists_every_film_and_scene_once(self):
        text = ff_time.canonical(self.table)
        lines = text.splitlines()
        self.assertEqual(lines[0], "film\t1\t40.000000")
        self.assertEqual(lines[1], "3\t0.000000\t10.500000")
        self.assertEqual(sum(line.startswith("film") for line in lines), 10)
        self.assertEqual(len(lines), 10 + 3 * 3 + 2 * 7)
        summary = ff_time.summary(self.table)[0]
        self.assertEqual((summary["scenes"], summary["min_seconds"]), (3, 10.5))
        self.assertEqual(summary["median_seconds"], 14.5)


class Near(Base):
    def test_a_time_on_a_boundary_belongs_to_the_scene_that_starts_there(self):
        self.assertEqual(self.strict(1, "0:10.4"), 3)
        self.assertEqual(self.strict(1, "0:10.5"), 4)
        self.assertEqual(self.near(1, "0:10.5"), [3, 4])

    def test_within_two_seconds_of_a_boundary_both_scenes_are_near(self):
        self.assertEqual(self.near(1, "0:09"), [3, 4])
        self.assertEqual(self.near(1, "0:12"), [3, 4])
        # Exactly two seconds away is within two seconds.
        self.assertEqual(self.near(1, "0:08.5"), [3, 4])
        self.assertEqual(self.near(1, "0:12.5"), [3, 4])
        self.assertEqual(self.near(1, "0:08.499"), [3])
        self.assertEqual(self.near(1, "0:12.501"), [4])
        self.assertEqual(self.near(1, "0:18"), [4])

    def test_near_scenes_include_the_strict_scene(self):
        for text in ("0:00", "0:05", "0:10.5", "0:24", "0:40"):
            self.assertIn(self.strict(1, text), self.near(1, text))

    def test_near_scenes_never_come_from_another_film(self):
        # Film 2's first scene follows film 1's last in scene number, not in time.
        self.assertEqual(self.near(2, "0:00"), [7])
        self.assertEqual(self.near(1, "0:40"), [5])
        self.assertEqual(self.near(1, "0:00"), [3])

    def test_a_short_scene_can_put_three_scenes_near(self):
        short = [r if r[3] != 125 else r[:3] + (113, r[4]) for r in ROWS]
        self.table = self.build(short)
        # Scene 4 is now [10.5, 13.5): at 12 s, scenes 3, 4 and 5 are all within two seconds.
        self.assertEqual(self.near(1, "0:12"), [3, 4, 5])
        self.assertEqual(self.strict(1, "0:12"), 4)

    def test_just_past_the_end_of_the_clip_the_last_scene_is_still_near(self):
        self.assertEqual(self.near(1, "0:41"), [5])
        self.assertEqual(self.near(1, "0:42"), [5])
        self.assertEqual(self.near(1, "0:42.1"), [])


class Prediction(Base):
    def test_a_time_inside_the_clip_is_a_scene_with_its_raw_answer_kept(self):
        self.assertEqual(
            ff_time.prediction(self.table, 1, "0:09.5"),
            {
                "status": "answered",
                "kind": "scene",
                "scene": 3,
                "near": [3, 4],
                "film_stated": 1,
                "time": "0:09.5",
                "seconds": 9.5,
            },
        )

    def test_a_film_without_a_time_and_nothing_are_the_parent_protocols_answers(self):
        self.assertEqual(
            ff_time.prediction(self.table, 2, None),
            {"status": "answered", "kind": "film", "film": 2},
        )
        self.assertEqual(
            ff_time.prediction(self.table, None, None),
            {"status": "answered", "kind": "none"},
        )

    def test_a_time_outside_the_clip_is_an_answer_of_its_own_kind(self):
        self.assertEqual(
            ff_time.prediction(self.table, 1, "0:41"),
            {
                "status": "answered",
                "kind": "time_outside",
                "film": 1,
                "near": [5],
                "film_stated": 1,
                "time": "0:41",
                "seconds": 41.0,
            },
        )
        far = ff_time.prediction(self.table, 1, "5:00")
        self.assertEqual((far["kind"], far["near"]), ("time_outside", []))

    def test_unusable_answers_fail_with_a_reason_and_keep_what_was_said(self):
        def reason(film, time):
            out = ff_time.prediction(self.table, film, time)
            self.assertEqual(out["status"], "failed")
            if time is not None:
                self.assertEqual((out["film_stated"], out["time"]), (film, time))
            return out["reason"]

        self.assertEqual(reason(1, "65"), "invalid-time")
        self.assertEqual(reason(1, 65), "invalid-time")
        self.assertEqual(reason(1, "1:5"), "invalid-time")
        self.assertEqual(reason(None, "65"), "invalid-time")
        self.assertEqual(reason(None, "0:05"), "time-without-film")
        self.assertEqual(reason("1", "0:05"), "invalid-film")
        self.assertEqual(reason(True, "0:05"), "invalid-film")
        self.assertEqual(reason(11, "0:05"), "unknown-film")
        self.assertEqual(reason(0, "0:05"), "unknown-film")
        self.assertEqual(reason("two", None), "invalid-film")

    def test_predictions_are_json_and_survive_a_round_trip(self):
        for film, time in ((1, "0:09.5"), (1, "0:41"), (1, "x"), (2, None)):
            made = ff_time.prediction(self.table, film, time)
            self.assertEqual(json.loads(json.dumps(made)), made)


class Rederive(Base):
    def reply(self):
        def unit(row, film, time):
            made = ff_time.prediction(self.table, film, time)
            return {"u": row, "row": row, "referent": made, "narrative": made}

        answers = [(1, "0:09.5"), (1, "0:41"), (2, None), (None, None), (1, "bad")]
        units = [unit(i + 1, film, time) for i, (film, time) in enumerate(answers)]
        omitted = {"status": "failed", "reason": "omitted"}
        units.append({"u": 6, "row": 6, "referent": omitted, "narrative": omitted})
        return json.loads(
            json.dumps({"participant": "p", "session": "s", "units": units})
        )

    def test_an_untouched_reply_file_recomputes_to_itself(self):
        self.assertEqual(ff_time.rederive(self.table, self.reply()), [])

    def test_any_changed_field_of_a_time_prediction_is_found(self):
        for change in (
            {"scene": 4},
            {"near": [3]},
            {"near": [3, 4, 5]},
            {"seconds": 9.0},
            {"kind": "film", "film": 1},
            {"time": "0:19"},
            {"film_stated": 2},
        ):
            reply = self.reply()
            reply["units"][0]["referent"].update(change)
            found = ff_time.rederive(self.table, reply)
            self.assertEqual([(d[0], d[1]) for d in found], [(1, "referent")], change)
        reply = self.reply()
        reply["units"][1]["narrative"]["kind"] = "scene"
        reply["units"][1]["narrative"]["scene"] = 5
        reply["units"][4]["referent"]["reason"] = "omitted"
        found = ff_time.rederive(self.table, reply)
        self.assertEqual(
            [(d[0], d[1]) for d in found], [(2, "narrative"), (5, "referent")]
        )

    def test_a_directory_is_checked_file_by_file_and_the_command_exits_one(self):
        replies = os.path.join(self.dir.name, "replies")
        os.makedirs(replies)
        good, bad = self.reply(), self.reply()
        bad["units"][0]["referent"]["scene"] = 5
        for name, doc in (("a.json", good), ("b.json", bad)):
            pathlib.Path(replies, name).write_text(json.dumps(doc))
        files, checked, found = ff_time.rederive_directory(self.table, replies)
        self.assertEqual((files, checked), (2, 12))
        self.assertEqual([d[:3] for d in found], [("b.json", 1, "referent")])


def utterance(row, film, scenes, cls):
    return {
        "participant": "p1",
        "session": "run-01",
        "row": row,
        "film": film,
        "scenes": frozenset(scenes),
        "cls": cls,
    }


class Oracle(Base):
    """Answers placed by rule around the reference scenes, scored by the shared scorer."""

    def setUp(self):
        super().setUp()
        path = os.path.join(self.dir.name, "annotation.tsv")
        self.film_of, self.valid = ff_gold.load_dictionary(path)
        # row, film, reference scenes, class, the reference scene the answers are built from.
        self.cases = [
            (1, 1, {4}, "single", 4),
            (2, 1, {4, 5}, "multi_contig", 4),
            (3, 1, {3, 5}, "multi_noncontig", 5),
            (4, 2, {8}, "single", 8),
            (5, 3, {11}, "single", 11),
            (6, 1, {4, 5}, "multi_contig", 5),
        ]
        self.gold = [utterance(r, f, s, c) for r, f, s, c, _ in self.cases]

    def interval(self, film, scene):
        return next((a, b) for s, a, b in self.table[film]["scenes"] if s == scene)

    def scored(self, place):
        predictions = {}
        for row, film, _, _, scene in self.cases:
            start, end = self.interval(film, scene)
            made = ff_time.prediction(self.table, film, stamp(place(start, end)))
            predictions[("p1", "run-01", row)] = made
        report = ff_score.score(
            self.gold, self.film_of, self.valid, predictions, ff_score.TIME_NEAR
        )
        session = report["sessions"]["run-01"]
        self.assertEqual(report["near_rule"], "time-2s")
        return session["scene"]["pooled"], session["scene_near"]["pooled"]

    def test_the_midpoint_of_a_reference_scene_is_a_strict_and_a_near_hit(self):
        strict, near = self.scored(lambda start, end: (start + end) / 2)
        self.assertEqual(strict, {"n": 6, "hits": 6, "rate": 1.0})
        self.assertEqual(near, {"n": 6, "hits": 6, "rate": 1.0})

    def test_one_second_before_the_scene_is_a_strict_miss_and_a_near_hit(self):
        strict, near = self.scored(lambda start, end: start - 1)
        # Row 6 is built from scene 5 with reference {4, 5}: one second early is scene 4, a hit.
        self.assertEqual(strict, {"n": 6, "hits": 1, "rate": 1 / 6})
        self.assertEqual(near, {"n": 6, "hits": 6, "rate": 1.0})

    def test_three_seconds_before_is_a_near_miss_unless_it_is_another_reference_scene(
        self,
    ):
        strict, near = self.scored(lambda start, end: start - 3)
        self.assertEqual(strict, {"n": 6, "hits": 1, "rate": 1 / 6})
        self.assertEqual(near, {"n": 6, "hits": 1, "rate": 1 / 6})

    def test_one_second_past_the_end_of_the_clip_is_near_the_last_scene_only(self):
        gold = [utterance(1, 1, {5}, "single"), utterance(2, 1, {4}, "single")]
        outside = ff_time.prediction(self.table, 1, "0:41")
        predictions = {("p1", "run-01", 1): outside, ("p1", "run-01", 2): outside}
        report = ff_score.score(
            gold, self.film_of, self.valid, predictions, ff_score.TIME_NEAR
        )["sessions"]["run-01"]
        self.assertEqual(report["scene"]["pooled"]["hits"], 0)
        self.assertEqual(report["scene_near"]["pooled"]["hits"], 1)
        self.assertEqual(report["time_outside_clip"], 2)
        self.assertEqual(report["prediction_kinds"]["invalid_scene"], 2)
        self.assertEqual(report["prediction_kinds"]["film"], 0)
        self.assertEqual(report["prediction_kinds"]["failed"], 0)
        self.assertEqual(report["film"]["film_reference"]["pooled"]["hits"], 2)


class Frozen(unittest.TestCase):
    """The real constants, against the tracked frame list and (when present) the annotation."""

    EVIDENCE = pathlib.Path(__file__).parents[2] / "docs/refactor/evidence"
    FRAMES = EVIDENCE / "filmfestival-auto-20261110/frames.json"

    def test_the_clock_has_ten_films_and_the_values_that_were_measured(self):
        self.assertEqual(sorted(ff_time.CLOCK), list(range(1, 11)))
        self.assertEqual(ff_time.CLOCK[1], {"C0": "44", "a": "0.996", "b": "0.75"})
        self.assertEqual([ff_time.CLOCK[f]["a"] for f in range(2, 11)], ["1"] * 9)
        self.assertEqual(
            [ff_time.CLOCK[f]["C0"] for f in range(1, 11)],
            ["44", "397", "534", "1005", "1141", "45", "375", "640", "787", "956"],
        )
        self.assertEqual(
            [ff_time.CLOCK[f]["b"] for f in range(1, 11)],
            ["0.75", "-0.7", "0.3", "0.2", "-0.9", "0.1", "0.6", "0.1", "0.2", "0.3"],
        )
        self.assertEqual(ff_time.TOLERANCE, "2")
        self.assertEqual(ff_time.to_clip(1, 47), Fraction("3.738"))

    def test_the_real_annotation_gives_203_scene_intervals(self):
        root = data_root()
        if root is None:
            self.skipTest("the FilmFestival data is not on this machine")
        annotation_path = os.path.join(root, "filmfestival/derived/annotation-JL.tsv")
        table = ff_time.load(annotation_path, ff_time.durations(self.FRAMES))
        self.assertEqual(sum(len(table[f]["scenes"]) for f in table), 203)
        _, valid = ff_gold.load_dictionary(annotation_path)
        self.assertEqual({s for f in table for s, _, _ in table[f]["scenes"]}, valid)
        self.assertEqual(
            [(s, float(a), float(b)) for s, a, b in table[1]["scenes"][:2]],
            [(4, 0.0, 3.738), (5, 3.738, 15.69)],
        )
        self.assertEqual(float(table[10]["scenes"][-1][2]), 406.0)


def data_root():
    """The data directory, when this machine has it: FF_DATA_ROOT, else the main checkout's."""
    here = pathlib.Path(__file__).resolve()
    candidates = [os.environ.get("FF_DATA_ROOT"), here.parents[2] / "data"]
    candidates += [parent / "data" for parent in here.parents[3:6]]
    for candidate in candidates:
        if candidate and os.path.exists(
            os.path.join(candidate, "filmfestival/derived/annotation-JL.tsv")
        ):
            return str(candidate)
    return None


if __name__ == "__main__":
    unittest.main()
