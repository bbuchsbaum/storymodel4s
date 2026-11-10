"""Tests for the FilmFestival transfer scorer on invented data.

Run from the repository root: python3 tools/filmfestival/test_ff_score.py
"""

import json
import os
import sys
import tempfile
import unittest
from fractions import Fraction

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_gold  # noqa: E402
import ff_score  # noqa: E402

TITLE = "X in bold white text fades in on a black screen"


def annotation():
    """Two runs, a cartoon, ten films of three scenes each; each film's first scene is a title.

    Film 3's first scene has a title row and a content row, so it stays a valid target.
    """
    lines = ["run\tfilm\tscene_number\tdescription"]
    scene = 0
    order = ["cartoon intro 1", "f1", "f2", "f3", "f4", "f5", "cartoon intro 2"]
    order += ["f6", "f7", "f8", "f9", "f10"]
    for i, film in enumerate(order):
        run = "Run 1" if i < 6 else "Run 2"
        if "cartoon" in film:
            scene += 1
            lines.append(f"{run}\t{film}\t{scene}\tsinging snacks")
            continue
        for part in range(3):
            scene += 1
            text = TITLE if part == 0 else f'someone says "hello" in {film} part {part}'
            lines.append(f"{run}\t{film}\t{scene}\t{text}")
            if film == "f3" and part == 0:
                lines.append(f"{run}\t{film}\t{scene}\ta kettle boils")
    return "\n".join(lines) + "\n"


# Film 1 owns scenes 2-4 (2 is its title); film 2 owns 5-7; film 3 owns 8-10; film 6 owns 18-20.
ROWS = [
    # participant, run, row, movie, scenes, raw scene code
    ("p1", "run-01", 1, 1, "3", "3"),
    ("p1", "run-01", 2, 1, "3 4", "3,4"),
    ("p1", "run-01", 3, 2, "5 7", "5,7"),
    ("p1", "run-01", 4, 1, "", "1000"),
    ("p1", "run-01", 5, -1, "", "-1"),
    ("p1", "run-01", 6, 11, "1", "1"),
    ("p1", "run-01", 7, 2, "", "-1"),
    ("p2", "run-01", 1, 2, "6", "6"),
    ("p2", "run-02", 1, 6, "19", "19"),
    ("p1", "run-02", 1, 2, "6", "6"),
    ("p3", "run-01", 1, 2, "7", "7"),
]


def scene(n):
    return {"status": "answered", "kind": "scene", "scene": n}


def film(n):
    return {"status": "answered", "kind": "film", "film": n}


NONE = {"status": "answered", "kind": "none"}


class Base(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.annotation = self.write("annotation.tsv", annotation())
        self.gold = self.load(ROWS)
        self.film_of, self.valid = ff_gold.load_dictionary(self.annotation)

    def write(self, name, text):
        path = os.path.join(self.dir.name, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        return path

    def files(self, rows, tag="g"):
        gold = "participant\trun\tgold_row\tmovie\tscenes\ttext\n" + "".join(
            f'{p}\t{run}\t{row}\t{movie}\t{scenes}\tsaid "x\n'
            for p, run, row, movie, scenes, _ in rows
        )
        codes = "participant\trun\tgold_row\tmovie\tscenes_raw\n" + "".join(
            f"{p}\t{run}\t{row}\t{movie}\t{raw}\n"
            for p, run, row, movie, _, raw in rows
        )
        return self.write(f"{tag}-gold.tsv", gold), self.write(
            f"{tag}-codes.tsv", codes
        )

    def load(self, rows, tag="g", expected=None):
        gold, codes = self.files(rows, tag)
        return ff_gold.load_gold(gold, codes, expected_rows=expected or len(rows))

    def session(self, predictions, name="run-01"):
        return ff_score.score(self.gold, self.film_of, self.valid, predictions)[
            "sessions"
        ][name]


class Gold(Base):
    def test_quotes_in_text_do_not_merge_rows(self):
        self.assertEqual(len(self.gold), len(ROWS))

    def test_a_wrong_row_count_is_refused(self):
        with self.assertRaises(ff_gold.GoldError):
            self.load(ROWS, "n", expected=len(ROWS) + 1)

    def test_a_repeated_utterance_key_is_refused(self):
        with self.assertRaises(ff_gold.GoldError):
            self.load(ROWS + [ROWS[0]], "dup")

    def test_classes_separate_film_only_from_in_film_memory_search(self):
        self.assertEqual(
            [
                u["cls"]
                for u in self.gold
                if u["participant"] == "p1" and u["session"] == "run-01"
            ],
            [
                "single",
                "multi_contig",
                "multi_noncontig",
                "film_only",
                "nonfilm",
                "cartoon",
                "film_search",
            ],
        )
        with self.assertRaises(ff_gold.GoldError):
            self.load([("p1", "run-01", 1, 1, "", "2000")], "bad")

    def test_raw_codes_must_describe_the_same_utterances(self):
        gold, _ = self.files(ROWS, "a")
        _, other = self.files(
            [r[:3] + (3,) + r[4:] if r[2] == 4 else r for r in ROWS], "b"
        )
        with self.assertRaises(ff_gold.GoldError):
            ff_gold.load_gold(gold, other, expected_rows=len(ROWS))

    def test_population_keeps_the_film_labelled_count_and_adds_the_reference_count(
        self,
    ):
        self.assertEqual(
            ff_gold.population(self.gold),
            {
                "run-01": {
                    "all": 9,
                    "scene_specific": 5,
                    "film_labelled": 7,
                    "film_reference": 6,
                },
                "run-02": {
                    "all": 2,
                    "scene_specific": 2,
                    "film_labelled": 2,
                    "film_reference": 2,
                },
            },
        )

    def test_a_scene_is_title_only_when_every_row_is_a_title_card(self):
        self.assertNotIn(2, self.valid)
        self.assertIn(3, self.valid)
        # Film 3's first scene mixes a title row with a content row: still a target.
        self.assertIn(8, self.valid)
        self.assertEqual(self.film_of[1], ff_gold.CARTOON)
        self.assertNotIn(1, self.valid)
        self.assertEqual(len(self.valid), 21)

    def test_a_gold_scene_in_another_film_is_refused(self):
        gold = self.load([("p1", "run-01", 1, 2, "6 19", "6,19")], "x")
        with self.assertRaises(ff_gold.GoldError):
            ff_gold.check_join(gold, self.film_of)


class Scoring(Base):
    def test_scene_hit_needs_the_selected_scene_in_the_reference_set(self):
        report = self.session(
            {
                ("p1", "run-01", 1): scene(3),
                ("p1", "run-01", 2): scene(4),
                ("p1", "run-01", 3): scene(6),
                ("p2", "run-01", 1): film(2),
                ("p3", "run-01", 1): scene(7),
            }
        )
        self.assertEqual(report["scene"]["pooled"], {"n": 5, "hits": 3, "rate": 0.6})
        self.assertEqual(report["scene"]["strata"]["multi_contig"]["pooled"]["hits"], 1)
        # Scene 6 lies between the reference's 5 and 7: right film, not a hit.
        self.assertEqual(
            report["scene"]["strata"]["multi_noncontig"]["pooled"]["hits"], 0
        )
        self.assertEqual(report["scene"]["by_participant"]["p2"]["hits"], 0)
        # p1: 2 of 3; p2: 0 of 1; p3: 1 of 1. The participant average is not the pooled rate.
        self.assertAlmostEqual(
            report["scene"]["participant_average"]["rate"], (2 / 3 + 0 + 1) / 3
        )
        self.assertEqual(report["scene"]["prediction_kinds"]["film"], 1)

    def test_strata_and_films_are_reported_per_participant_too(self):
        report = self.session(
            {("p2", "run-01", 1): scene(6), ("p3", "run-01", 1): scene(5)}
        )
        film2 = report["scene"]["by_film"]["2"]
        # Film 2: p1's {5,7} missed (no prediction), p2 hit, p3 missed.
        self.assertEqual(film2["pooled"], {"n": 3, "hits": 1, "rate": 1 / 3})
        self.assertEqual(film2["by_participant"]["p2"]["hits"], 1)
        self.assertAlmostEqual(film2["participant_average"]["rate"], 1 / 3)
        self.assertEqual(report["scene"]["by_film"]["1"]["pooled"]["n"], 2)
        self.assertEqual(report["scene"]["by_film"]["6"]["pooled"]["n"], 0)
        self.assertEqual(
            report["scene"]["strata"]["single"]["participant_average"]["participants"],
            3,
        )

    def test_film_identity_is_reported_with_and_without_in_film_memory_search(self):
        report = self.session(
            {
                ("p1", "run-01", 1): scene(4),
                ("p1", "run-01", 4): film(1),
                ("p1", "run-01", 7): NONE,
                ("p2", "run-01", 1): film(2),
            }
        )["film"]
        self.assertEqual(
            report["film_labelled"]["pooled"], {"n": 7, "hits": 3, "rate": 3 / 7}
        )
        self.assertEqual(
            report["film_reference"]["pooled"], {"n": 6, "hits": 3, "rate": 0.5}
        )

    def test_film_only_reference_accepts_a_film_answer_and_counts_invented_precision(
        self,
    ):
        def breakdown(prediction):
            return self.session({("p1", "run-01", 4): prediction})["film_only"]

        self.assertEqual(breakdown(film(1))["film_only_correct_film"], 1)
        self.assertEqual(breakdown(scene(3))["scene_in_correct_film"], 1)
        self.assertEqual(breakdown(scene(6))["scene_in_correct_film"], 0)
        self.assertEqual(breakdown(scene(6))["wrong_film"], 1)
        self.assertEqual(breakdown(film(2))["wrong_film"], 1)
        self.assertEqual(breakdown(NONE)["none"], 1)

    def test_a_title_card_answer_is_an_answer_with_its_film_not_a_processing_failure(
        self,
    ):
        report = self.session(
            {("p1", "run-01", 4): scene(2), ("p1", "run-01", 1): scene(2)}
        )
        self.assertEqual(
            report["film_only"]["title_or_invalid_scene_in_correct_film"], 1
        )
        self.assertEqual(report["film_only"]["failed"], 0)
        self.assertEqual(report["scene"]["prediction_kinds"]["invalid_scene"], 1)
        self.assertEqual(report["scene"]["pooled"]["hits"], 0)
        self.assertEqual(report["film"]["film_labelled"]["pooled"]["hits"], 2)
        # The cartoon is a real scene but no film: an answer with no film.
        cartoon = self.session({("p1", "run-01", 4): scene(1)})
        self.assertEqual(cartoon["film_only"]["wrong_film"], 1)

    def test_an_invalid_target_is_never_a_scene_hit_even_when_the_reference_lists_it(
        self,
    ):
        # Scene 5 is film 2's title card, and the reference {5, 7} lists it.
        report = self.session({("p1", "run-01", 3): scene(5)})
        self.assertEqual(report["scene"]["pooled"]["hits"], 0)
        self.assertEqual(report["scene"]["prediction_kinds"]["invalid_scene"], 1)
        self.assertEqual(report["film"]["film_reference"]["pooled"]["hits"], 1)
        self.assertEqual(
            self.session({("p1", "run-01", 3): scene(7)})["scene"]["pooled"]["hits"], 1
        )

    def test_a_title_card_of_another_film_is_a_wrong_film(self):
        report = self.session({("p1", "run-01", 4): scene(5)})["film_only"]
        self.assertEqual(report["wrong_film"], 1)
        self.assertEqual(report["title_or_invalid_scene_in_correct_film"], 0)

    def test_in_film_memory_search_is_described_separately(self):
        self.assertEqual(
            self.session({("p1", "run-01", 7): NONE})["film_search"]["none"], 1
        )
        self.assertEqual(
            self.session({("p1", "run-01", 7): film(2)})["film_search"][
                "film_only_correct_film"
            ],
            1,
        )

    def test_failure_is_not_a_non_assignment_and_keeps_its_reason(self):
        failed = self.session(
            {("p1", "run-01", 5): {"status": "failed", "reason": "omitted"}}
        )
        self.assertEqual(
            failed["nonfilm"],
            {"n": 1, "assigned_a_film": 0, "no_assignment": 0, "failed": 1},
        )
        self.assertEqual(failed["failure_reasons"], {"no-prediction": 8, "omitted": 1})
        self.assertEqual(
            self.session({("p1", "run-01", 5): NONE})["nonfilm"]["no_assignment"], 1
        )
        self.assertEqual(
            self.session({("p1", "run-01", 5): film(4)})["nonfilm"]["assigned_a_film"],
            1,
        )
        self.assertEqual(
            self.session({("p1", "run-01", 5): scene(2)})["nonfilm"]["assigned_a_film"],
            1,
        )

    def test_malformed_predictions_are_failures(self):
        report = self.session(
            {
                ("p1", "run-01", 1): {
                    "status": "answered",
                    "kind": "scene",
                    "scene": "3",
                },
                ("p1", "run-01", 2): {
                    "status": "answered",
                    "kind": "scene",
                    "scene": True,
                },
                ("p1", "run-01", 3): film(11),
                ("p2", "run-01", 1): {
                    "status": "answered",
                    "kind": "scene",
                    "scene": 999,
                },
                ("p3", "run-01", 1): {"status": "answered", "kind": "maybe"},
            }
        )
        self.assertEqual(report["scene"]["prediction_kinds"]["failed"], 5)
        self.assertEqual(report["failure_reasons"]["unknown-scene"], 3)
        self.assertEqual(report["failure_reasons"]["unknown-film"], 1)
        self.assertEqual(report["failure_reasons"]["unknown-kind"], 1)

    def test_cartoon_is_out_of_scope_and_counted_apart(self):
        report = self.session({("p1", "run-01", 6): NONE})
        self.assertEqual(
            report["cartoon"],
            {"n": 1, "assigned_a_film": 0, "no_assignment": 1, "failed": 0},
        )
        self.assertEqual(report["scene"]["pooled"]["n"], 5)

    def test_chance_counts_acceptable_scenes_over_eligible_scenes(self):
        report = self.session({})["scene"]
        # Films 1 and 2 have two valid scenes each. References (valid part): {3}, {3,4}, {7}, {6}, {7}.
        self.assertAlmostEqual(
            report["chance_given_film"], (1 / 2 + 2 / 2 + 1 / 2 + 1 / 2 + 1 / 2) / 5
        )
        self.assertAlmostEqual(
            report["chance_end_to_end"], (1 + 2 + 1 + 1 + 1) / 21 / 5
        )

    def test_sessions_are_separate_and_compared_within_participants_who_have_both(self):
        report = ff_score.score(
            self.gold,
            self.film_of,
            self.valid,
            {
                ("p1", "run-02", 1): scene(6),
                ("p2", "run-02", 1): scene(19),
                ("p3", "run-01", 1): scene(7),
            },
        )
        self.assertEqual(report["sessions"]["run-02"]["scene"]["pooled"]["rate"], 1.0)
        self.assertEqual(report["sessions"]["run-01"]["scene"]["pooled"]["hits"], 1)
        shared = report["participants_with_every_session"]
        # p3 has only a first session, so it is left out of the comparison block.
        self.assertEqual(shared["participants"], ["p1", "p2"])
        self.assertEqual(
            shared["sessions"]["run-01"]["scene"]["pooled"],
            {"n": 4, "hits": 0, "rate": 0.0},
        )

    def test_interval_resamples_participants_and_is_reproducible(self):
        rates = {f"p{i}": {"n": 10, "hits": i, "rate": i / 10} for i in range(1, 9)}
        a = ff_score.participant_average(rates)
        self.assertEqual(a, ff_score.participant_average(rates))
        lo, hi = a["interval95"]
        self.assertTrue(0.1 <= lo < a["rate"] < hi <= 0.8)
        # The interval is the central 95% of the resampled means, not a wider or narrower band.
        self.assertTrue(0.25 < lo < 0.35 and 0.55 < hi < 0.65, (lo, hi))
        same = {f"p{i}": {"n": 3, "hits": 3, "rate": 1.0} for i in range(4)}
        self.assertEqual(ff_score.participant_average(same)["interval95"], [1.0, 1.0])
        one = {
            "p1": {"n": 3, "hits": 3, "rate": 1.0},
            "p2": {"n": 0, "hits": 0, "rate": None},
        }
        self.assertEqual(
            ff_score.participant_average(one),
            {"participants": 1, "rate": 1.0, "interval95": None},
        )

    def test_a_prediction_for_an_unknown_utterance_is_refused(self):
        with self.assertRaises(ff_score.ScoreError):
            self.session({("p9", "run-01", 1): NONE})


def table_of(film_of, valid, seconds=10):
    """A scene-interval table as `ff_time` builds it: every valid scene `seconds` long."""
    table = {}
    for film in ff_gold.FILMS:
        own = sorted(s for s in valid if film_of[s] == film)
        scenes = [
            (s, Fraction(i * seconds), Fraction((i + 1) * seconds))
            for i, s in enumerate(own)
        ]
        table[film] = {"duration": Fraction(len(own) * seconds), "scenes": scenes}
    return table


class Adjacent(Base):
    """Adjacent scene agreement: the selected scene or a valid neighbour in the same film."""

    def hits(self, predictions, rows=None):
        if rows is not None:
            self.gold = self.load(rows, f"near{len(os.listdir(self.dir.name))}")
        report = ff_score.score(self.gold, self.film_of, self.valid, predictions)
        self.assertEqual(report["answers"], "scene")
        session = report["sessions"]["run-01"]
        self.assertNotIn("scene_within_2s", session)
        return session["scene"]["pooled"]["hits"], session["scene_adjacent"]["pooled"][
            "hits"
        ]

    def test_the_scene_before_or_after_the_reference_is_an_adjacent_hit_only(self):
        one = [("p1", "run-01", 1, 3, "9", "9")]
        self.assertEqual(self.hits({("p1", "run-01", 1): scene(8)}, one), (0, 1))
        self.assertEqual(self.hits({("p1", "run-01", 1): scene(10)}, one), (0, 1))
        self.assertEqual(self.hits({("p1", "run-01", 1): scene(9)}, one), (1, 1))
        two = [("p1", "run-01", 1, 3, "8", "8")]
        self.assertEqual(self.hits({("p1", "run-01", 1): scene(10)}, two), (0, 0))

    def test_a_strict_hit_is_always_an_adjacent_hit(self):
        report = self.session(
            {
                ("p1", "run-01", 1): scene(3),
                ("p1", "run-01", 2): scene(4),
                ("p1", "run-01", 3): scene(6),
                ("p3", "run-01", 1): scene(7),
            }
        )
        self.assertEqual(report["scene"]["pooled"]["hits"], 3)
        # Scene 6 lies between the reference's 5 and 7: an adjacent hit.
        self.assertEqual(report["scene_adjacent"]["pooled"]["hits"], 4)
        self.assertEqual(
            report["scene_adjacent"]["strata"]["multi_noncontig"]["pooled"]["hits"], 1
        )
        self.assertEqual(report["scene_adjacent"]["by_film"]["2"]["pooled"]["hits"], 2)
        self.assertEqual(report["scene_adjacent"]["by_participant"]["p2"]["hits"], 0)

    def test_the_next_scene_number_in_another_film_is_not_adjacent(self):
        # Scene 7 ends film 2 and scene 8 opens film 3.
        rows = [("p1", "run-01", 1, 3, "8", "8"), ("p1", "run-01", 2, 2, "7", "7")]
        predictions = {("p1", "run-01", 1): scene(7), ("p1", "run-01", 2): scene(8)}
        self.assertEqual(self.hits(predictions, rows), (0, 0))

    def test_neighbours_are_counted_among_valid_targets_only(self):
        # Film 2 is scenes 5 (title card), 6, 7. The reference lists only the title card.
        rows = [("p1", "run-01", 1, 2, "5", "5")]
        self.assertEqual(self.hits({("p1", "run-01", 1): scene(6)}, rows), (0, 0))
        # And a title-card answer has no neighbours.
        rows = [("p1", "run-01", 1, 2, "6", "6")]
        self.assertEqual(self.hits({("p1", "run-01", 1): scene(5)}, rows), (0, 0))
        self.assertEqual(ff_score.neighbours(self.film_of, self.valid)[6], [7])
        self.assertEqual(ff_score.neighbours(self.film_of, self.valid)[9], [8, 10])
        self.assertNotIn(5, ff_score.neighbours(self.film_of, self.valid))

    def test_an_answer_without_a_scene_is_never_an_adjacent_hit(self):
        rows = [("p1", "run-01", 1, 2, "6", "6")]
        for prediction in (film(2), NONE, None, {"status": "failed", "reason": "x"}):
            self.assertEqual(self.hits({("p1", "run-01", 1): prediction}, rows), (0, 0))

    def test_the_adjacent_block_is_laid_out_like_the_strict_block(self):
        report = self.session({("p1", "run-01", 1): scene(4)})
        strict, beside = report["scene"], report["scene_adjacent"]
        self.assertEqual(
            set(strict) - set(beside),
            {"prediction_kinds", "chance_given_film", "chance_end_to_end"},
        )
        self.assertEqual(set(beside) - set(strict), set())
        self.assertEqual(set(beside["strata"]), set(strict["strata"]))
        self.assertEqual(set(beside["by_film"]), set(strict["by_film"]))
        self.assertEqual(beside["pooled"], {"n": 5, "hits": 1, "rate": 0.2})
        self.assertEqual(strict["pooled"]["hits"], 0)
        self.assertEqual(beside["participant_average"]["participants"], 3)
        self.assertEqual(report["time_outside_clip"], 0)

    def test_the_schema_is_v4_and_names_the_kind_of_answer(self):
        report = ff_score.score(self.gold, self.film_of, self.valid, {})
        self.assertEqual(report["schema"].rsplit("/", 1)[1], "v4")
        self.assertEqual(
            (report["answers"], report["chance_guess"]),
            ("scene", "uniform valid scene"),
        )
        self.assertNotIn("near_rule", report)


class TimeAnswers(Base):
    """Predictions made from a time: the same kinds, plus the scenes within 2 seconds."""

    def setUp(self):
        super().setUp()
        self.table = table_of(self.film_of, self.valid)

    def timed(self, selected, near, **more):
        return dict(scene(selected), near=near, time="0:07", film_stated=1, **more)

    def run_as(self, predictions, timed=True):
        table = self.table if timed else None
        report = ff_score.score(self.gold, self.film_of, self.valid, predictions, table)
        self.assertEqual(report["answers"], "time" if timed else "scene")
        return report["sessions"]["run-01"]

    def test_the_near_list_decides_within_2s_and_the_strict_scene_decides_adjacent(
        self,
    ):
        report = self.run_as(
            {
                ("p1", "run-01", 1): self.timed(4, [3, 4]),
                ("p1", "run-01", 2): self.timed(3, [3]),
                ("p3", "run-01", 1): self.timed(6, [6]),
                ("p2", "run-01", 1): self.timed(7, [6, 7]),
            }
        )
        self.assertEqual(report["scene"]["pooled"]["hits"], 1)
        # Row 1 ({3}) by its near list, row 2 strictly, p2 ({6}) by its near list.
        self.assertEqual(report["scene_within_2s"]["pooled"]["hits"], 3)
        # Scene 6 is next to p3's reference 7, though its near list does not hold 7.
        self.assertEqual(report["scene_within_2s"]["by_participant"]["p3"]["hits"], 0)
        self.assertEqual(report["scene_adjacent"]["by_participant"]["p3"]["hits"], 1)
        self.assertEqual(report["scene_adjacent"]["pooled"]["hits"], 4)
        self.assertEqual(set(report["scene_within_2s"]), set(report["scene_adjacent"]))

    def test_a_time_outside_the_clip_keeps_its_film_and_is_counted_apart(self):
        outside = {
            "status": "answered",
            "kind": "time_outside",
            "film": 1,
            "near": [4],
            "time": "9:59",
            "film_stated": 1,
        }
        report = self.run_as(
            {
                ("p1", "run-01", 1): outside,
                ("p1", "run-01", 2): outside,
                ("p1", "run-01", 4): outside,
                ("p1", "run-01", 5): outside,
            }
        )
        self.assertEqual(report["time_outside_clip"], 4)
        self.assertEqual(report["prediction_kinds"]["invalid_scene"], 4)
        self.assertEqual(report["prediction_kinds"]["film"], 0)
        # The five failures are the utterances given no prediction; none is a time answer.
        self.assertEqual(report["failure_reasons"], {"no-prediction": 5})
        self.assertEqual(report["scene"]["pooled"]["hits"], 0)
        # It selects no scene, so it has no neighbours; its near list still counts.
        self.assertEqual(report["scene_adjacent"]["pooled"]["hits"], 0)
        # Row 2's reference is {3, 4}; row 1's is {3}.
        self.assertEqual(report["scene_within_2s"]["pooled"]["hits"], 1)
        self.assertEqual(report["film"]["film_labelled"]["pooled"]["hits"], 3)
        self.assertEqual(report["film_only"]["film_only_correct_film"], 0)
        self.assertEqual(
            report["film_only"]["title_or_invalid_scene_in_correct_film"], 1
        )
        self.assertEqual(report["nonfilm"]["assigned_a_film"], 1)
        bad = dict(outside, film=11)
        failed = self.run_as({("p1", "run-01", 1): bad})
        self.assertEqual(failed["failure_reasons"]["unknown-film"], 1)

    def test_a_reply_file_cannot_be_scored_as_the_other_kind_of_answer(self):
        key = ("p1", "run-01", 1)
        with self.assertRaises(ff_score.ScoreError):
            self.run_as({key: self.timed(4, [3, 4])}, timed=False)
        with self.assertRaises(ff_score.ScoreError):
            self.run_as({key: scene(4)})
        invalid = {"status": "failed", "reason": "invalid-time", "time": "x"}
        with self.assertRaises(ff_score.ScoreError):
            self.run_as({key: invalid}, timed=False)
        self.assertEqual(
            self.run_as({key: invalid})["failure_reasons"]["invalid-time"], 1
        )
        # Answers without a location are the same either way.
        for timed in (True, False):
            self.assertEqual(
                self.run_as({key: film(1), ("p1", "run-01", 5): NONE}, timed)[
                    "prediction_kinds"
                ]["film"],
                1,
            )

    def test_a_malformed_near_list_is_refused_not_scored(self):
        key = ("p1", "run-01", 1)
        for near in (None, "3", [3], [4, 6], [4, 2], [4, "3"], [4, 999]):
            with self.assertRaises(ff_score.ScoreError, msg=repr(near)):
                self.run_as({key: self.timed(4, near)})
        self.run_as({key: self.timed(4, [4])})

    def test_within_2s_is_reported_for_time_answers_even_with_no_scene_utterance(self):
        self.gold = self.load([("p1", "run-01", 1, -1, "", "-1")], "none")
        self.assertIn("scene_within_2s", self.run_as({}))
        self.assertNotIn("scene_within_2s", self.run_as({}, timed=False))


class Chance(Base):
    """What a uniform guess scores, computed exactly."""

    # Film 1 has valid scenes 3, 4; film 2 has 6, 7; film 3 has 8, 9, 10. 21 valid in all.
    ROWS = [
        ("p1", "run-01", 1, 3, "9", "9"),
        ("p1", "run-01", 2, 3, "8", "8"),
        ("p1", "run-01", 3, 3, "8 10", "8,10"),
        ("p1", "run-01", 4, 2, "5 6", "5,6"),
    ]

    def chance(self, table=None):
        self.gold = self.load(self.ROWS, f"chance{len(os.listdir(self.dir.name))}")
        report = ff_score.score(self.gold, self.film_of, self.valid, {}, table)
        return report["chance_guess"], report["sessions"]["run-01"]["chance"]

    def test_a_uniform_scene_guess(self):
        guess, chance = self.chance()
        self.assertEqual(guess, "uniform valid scene")
        # Strict: 1/3, 1/3, 2/3, 1/2 (the title card 5 is no target).
        self.assertAlmostEqual(
            chance["given_film"]["scene"], (1 / 3 + 1 / 3 + 2 / 3 + 1 / 2) / 4
        )
        # Adjacent: {8, 9, 10}, {8, 9}, {8, 9, 10} of three; {6, 7} of two.
        self.assertAlmostEqual(
            chance["given_film"]["scene_adjacent"], (1 + 2 / 3 + 1 + 1) / 4
        )
        self.assertAlmostEqual(chance["end_to_end"]["scene"], (1 + 1 + 2 + 1) / 21 / 4)
        self.assertAlmostEqual(
            chance["end_to_end"]["scene_adjacent"], (3 + 2 + 3 + 2) / 21 / 4
        )
        self.assertEqual(set(chance["given_film"]), {"scene", "scene_adjacent"})
        session = ff_score.score(self.gold, self.film_of, self.valid, {})["sessions"]
        self.assertAlmostEqual(
            session["run-01"]["scene"]["chance_given_film"],
            chance["given_film"]["scene"],
        )

    def test_a_uniform_time_guess_measures_intervals_not_scene_counts(self):
        table = table_of(self.film_of, self.valid)
        # Film 3: scene 8 is [0, 10), 9 is [10, 14), 10 is [14, 40). Film 1 and 2 are 20 s.
        table[3] = {
            "duration": Fraction(40),
            "scenes": [
                (8, Fraction(0), Fraction(10)),
                (9, Fraction(10), Fraction(14)),
                (10, Fraction(14), Fraction(40)),
            ],
        }
        total = sum(float(entry["duration"]) for entry in table.values())
        self.assertEqual(total, 20 + 20 + 40 + 7 * 20)
        guess, chance = self.chance(table)
        self.assertEqual(guess, "uniform time")
        given = chance["given_film"]
        self.assertEqual(set(given), {"scene", "scene_adjacent", "scene_within_2s"})
        # Strict: scene 9 is 4 s of 40; scene 8 is 10; {8, 10} is 36; scene 6 is 10 of 20.
        self.assertAlmostEqual(
            given["scene"], (4 / 40 + 10 / 40 + 36 / 40 + 10 / 20) / 4
        )
        # Adjacent: all of film 3; scenes 8, 9; all of film 3; all of film 2.
        self.assertAlmostEqual(given["scene_adjacent"], (1 + 14 / 40 + 1 + 1) / 4)
        # Within 2 s: [8, 16]; [0, 12]; [0, 12] and [12, 40], which is everything; [0, 12].
        self.assertAlmostEqual(
            given["scene_within_2s"], (8 / 40 + 12 / 40 + 1 + 12 / 20) / 4
        )
        self.assertAlmostEqual(
            chance["end_to_end"]["scene_within_2s"], (8 + 12 + 40 + 12) / total / 4
        )
        self.assertAlmostEqual(
            chance["end_to_end"]["scene"], (4 + 10 + 36 + 10) / total / 4
        )

    def test_overlapping_spans_are_counted_once_and_clipped(self):
        spans = [(-2, 5), (3, 8), (8, 9), (20, 30)]
        self.assertEqual(ff_score.covered(spans, 0, 25), 9 + 5)
        self.assertEqual(ff_score.covered([], 0, 25), 0)
        self.assertEqual(ff_score.covered([(1, 3), (1, 2), (0, 4)], 0, 25), 4)


class Files(Base):
    def reply_dir(self, drop=None, repeat=False, field="referent"):
        directory = os.path.join(
            self.dir.name, f"replies{len(os.listdir(self.dir.name))}"
        )
        sessions = {}
        for u in self.gold:
            sessions.setdefault((u["participant"], u["session"]), []).append(u["row"])
        for (participant, session), rows in sessions.items():
            if (participant, session) == drop:
                continue
            units = [{"row": row, field: NONE} for row in rows]
            if repeat:
                units.append(units[0])
            doc = {"participant": participant, "session": session, "units": units}
            self.write(
                os.path.join(directory, f"{participant}_{session}.json"),
                json.dumps(doc),
            )
        return directory

    def test_every_participant_session_needs_its_file(self):
        self.assertEqual(
            len(ff_score.load_predictions(self.reply_dir(), "referent", self.gold)),
            len(ROWS),
        )
        with self.assertRaises(ff_score.ScoreError):
            ff_score.load_predictions(
                self.reply_dir(drop=("p3", "run-01")), "referent", self.gold
            )

    def test_two_files_for_one_session_are_refused(self):
        directory = self.reply_dir()
        doc = {"participant": "p3", "session": "run-01", "units": []}
        self.write(os.path.join(directory, "zz-copy.json"), json.dumps(doc))
        with self.assertRaises(ff_score.ScoreError):
            ff_score.load_predictions(directory, "referent", self.gold)

    def test_scored_inputs_must_be_the_frozen_files(self):
        gold, codes = self.files(ROWS, "frozen")
        manifest = {
            "goldSha256": ff_score.file_sha(gold),
            "codesSha256": ff_score.file_sha(codes),
            "annotationSha256": ff_score.file_sha(self.annotation),
        }
        path = self.write("manifest.json", json.dumps(manifest))
        ff_score.check_inputs(path, gold, codes, self.annotation)
        other, _ = self.files(ROWS[:-1], "other")
        with self.assertRaises(ff_score.ScoreError):
            ff_score.check_inputs(path, other, codes, self.annotation)
        with self.assertRaises(ff_score.ScoreError):
            ff_score.check_inputs(path, gold, codes, gold)

    def test_a_repeated_row_and_a_wrong_field_are_refused(self):
        with self.assertRaises(ff_score.ScoreError):
            ff_score.load_predictions(
                self.reply_dir(repeat=True), "referent", self.gold
            )
        with self.assertRaises(ff_score.ScoreError):
            ff_score.load_predictions(self.reply_dir(), "narrative", self.gold)
        with self.assertRaises(ff_score.ScoreError):
            ff_score.load_predictions(self.reply_dir(), "scene", self.gold)


if __name__ == "__main__":
    unittest.main()
