"""Tests for the FilmFestival transfer scorer on invented data.

Run from the repository root: python3 tools/filmfestival/test_ff_score.py
"""

import json
import os
import sys
import tempfile
import unittest

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
