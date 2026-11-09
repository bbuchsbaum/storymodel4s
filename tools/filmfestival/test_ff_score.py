"""Tests for the FilmFestival transfer scorer on invented data.

Run from the repository root: python3 tools/filmfestival/test_ff_score.py
"""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_gold  # noqa: E402
import ff_score  # noqa: E402

TITLE = "X in bold white text fades in on a black screen"


def annotation():
    """Two runs, a cartoon, ten films of three scenes each; each film's first scene is a title."""
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
    return "\n".join(lines) + "\n"


# Film 1 owns scenes 2-4 (2 is its title); film 2 owns 5-7; film 6 owns 18-20.
GOLD_ROWS = [
    # participant, run, row, movie, scenes
    ("p1", "run-01", 1, 1, "3"),
    ("p1", "run-01", 2, 1, "3 4"),
    ("p1", "run-01", 3, 2, "6 7 19"),  # replaced below: must stay within one film
    ("p1", "run-01", 4, 1, ""),
    ("p1", "run-01", 5, -1, ""),
    ("p1", "run-01", 6, 11, "1"),
    ("p2", "run-01", 1, 2, "6"),
    ("p2", "run-02", 1, 6, "19"),
    ("p1", "run-02", 1, 2, "6"),
]


def gold_text(rows):
    head = "participant\trun\tgold_row\tmovie\tscenes\ttext"
    body = [
        f'{p}\t{run}\t{row}\t{movie}\t{scenes}\tsaid "x'
        for p, run, row, movie, scenes in rows
    ]
    return "\n".join([head] + body) + "\n"


class Scorer(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.annotation = os.path.join(self.dir.name, "annotation.tsv")
        with open(self.annotation, "w", encoding="utf-8") as handle:
            handle.write(annotation())
        rows = [r for r in GOLD_ROWS if r[2] != 3 or r[0] != "p1" or r[1] != "run-01"]
        rows.insert(2, ("p1", "run-01", 3, 2, "5 7"))
        self.gold = self.load(rows)
        self.film_of, self.valid = ff_gold.load_dictionary(self.annotation)

    def load(self, rows):
        path = os.path.join(self.dir.name, f"gold{len(os.listdir(self.dir.name))}.tsv")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(gold_text(rows))
        return ff_gold.load_gold(path, expected_rows=len(rows))

    def run_score(self, predictions):
        return ff_score.score(self.gold, self.film_of, self.valid, predictions)

    def test_quotes_in_text_do_not_merge_rows(self):
        self.assertEqual(len(self.gold), 9)

    def test_classes_and_population(self):
        self.assertEqual(
            [u["cls"] for u in self.gold if u["session"] == "run-01"],
            [
                "single",
                "multi_contig",
                "multi_noncontig",
                "film_noscene",
                "nonfilm",
                "cartoon",
                "single",
            ],
        )
        self.assertEqual(
            ff_gold.population(self.gold),
            {
                "run-01": {"all": 7, "scene_specific": 4, "film_labelled": 5},
                "run-02": {"all": 2, "scene_specific": 2, "film_labelled": 2},
            },
        )

    def test_title_only_scenes_are_not_targets_and_cartoon_is_not_a_film(self):
        self.assertNotIn(2, self.valid)
        self.assertIn(3, self.valid)
        self.assertEqual(self.film_of[1], ff_gold.CARTOON)
        self.assertNotIn(1, self.valid)
        self.assertEqual(len(self.valid), 20)

    def test_a_gold_scene_in_another_film_is_refused(self):
        gold = self.load(GOLD_ROWS)
        with self.assertRaises(ff_gold.GoldError):
            ff_gold.check_join(gold, self.film_of)

    def test_scene_hit_needs_the_selected_scene_in_the_reference_set(self):
        report = self.run_score(
            {
                ("p1", "run-01", 1): {
                    "status": "answered",
                    "kind": "scene",
                    "scene": 3,
                },
                ("p1", "run-01", 2): {
                    "status": "answered",
                    "kind": "scene",
                    "scene": 4,
                },
                ("p1", "run-01", 3): {
                    "status": "answered",
                    "kind": "scene",
                    "scene": 6,
                },
                ("p2", "run-01", 1): {"status": "answered", "kind": "film", "film": 2},
            }
        )["sessions"]["run-01"]
        self.assertEqual(report["scene"]["pooled"], {"n": 4, "hits": 2, "rate": 0.5})
        self.assertEqual(report["scene"]["strata"]["multi_contig"]["hits"], 1)
        # Scene 6 lies between the reference's 5 and 7: right film, not a hit.
        self.assertEqual(report["scene"]["strata"]["multi_noncontig"]["hits"], 0)
        # A film-only answer to a scene-specific utterance is a film hit and a scene miss.
        self.assertEqual(report["film"]["pooled"]["hits"], 4)
        self.assertEqual(report["scene"]["by_participant"]["p2"]["hits"], 0)
        self.assertEqual(
            report["scene"]["prediction_kinds"],
            {"scene": 3, "film": 1, "none": 0, "failed": 0},
        )
        # p1: 2 of 3; p2: 0 of 1. The participant average is not the pooled rate.
        self.assertAlmostEqual(
            report["scene"]["participant_average"]["rate"], (2 / 3 + 0) / 2
        )

    def test_film_only_reference_accepts_a_film_answer_and_flags_invented_precision(
        self,
    ):
        def noscene(prediction):
            return self.run_score({("p1", "run-01", 4): prediction})["sessions"][
                "run-01"
            ]["film_noscene"]

        self.assertEqual(
            noscene({"status": "answered", "kind": "film", "film": 1})[
                "film_only_correct_film"
            ],
            1,
        )
        self.assertEqual(
            noscene({"status": "answered", "kind": "scene", "scene": 3})[
                "scene_in_correct_film"
            ],
            1,
        )
        self.assertEqual(
            noscene({"status": "answered", "kind": "film", "film": 2})["wrong_film"], 1
        )
        self.assertEqual(noscene({"status": "answered", "kind": "none"})["none"], 1)

    def test_failure_is_not_a_non_assignment(self):
        report = self.run_score(
            {
                ("p1", "run-01", 5): {"status": "failed", "reason": "no reply"},
            }
        )["sessions"]["run-01"]
        self.assertEqual(
            report["nonfilm"],
            {"n": 1, "assigned_a_film": 0, "no_assignment": 0, "failed": 1},
        )
        explicit = self.run_score(
            {("p1", "run-01", 5): {"status": "answered", "kind": "none"}}
        )
        self.assertEqual(explicit["sessions"]["run-01"]["nonfilm"]["no_assignment"], 1)
        assigned = self.run_score(
            {("p1", "run-01", 5): {"status": "answered", "kind": "film", "film": 4}}
        )
        self.assertEqual(
            assigned["sessions"]["run-01"]["nonfilm"]["assigned_a_film"], 1
        )

    def test_missing_and_malformed_predictions_are_failures(self):
        report = self.run_score(
            {
                ("p1", "run-01", 1): {
                    "status": "answered",
                    "kind": "scene",
                    "scene": 2,
                },  # title
                ("p1", "run-01", 2): {
                    "status": "answered",
                    "kind": "scene",
                    "scene": "3",
                },
                ("p1", "run-01", 3): {"status": "answered", "kind": "film", "film": 11},
            }
        )["sessions"]["run-01"]
        self.assertEqual(report["scene"]["prediction_kinds"]["failed"], 4)
        self.assertEqual(report["scene"]["pooled"]["hits"], 0)

    def test_cartoon_is_out_of_scope_and_counted_apart(self):
        report = self.run_score(
            {("p1", "run-01", 6): {"status": "answered", "kind": "none"}}
        )["sessions"]["run-01"]
        self.assertEqual(report["cartoon"]["n"], 1)
        self.assertEqual(report["scene"]["pooled"]["n"], 4)
        self.assertEqual(report["film"]["pooled"]["n"], 5)

    def test_chance_counts_acceptable_scenes_over_eligible_scenes(self):
        report = self.run_score({})["sessions"]["run-01"]["scene"]
        # Each film has two valid scenes. References: {3}, {3,4}, {5,7}->{7} valid, {6}.
        self.assertAlmostEqual(
            report["chance_given_film"], (1 / 2 + 2 / 2 + 1 / 2 + 1 / 2) / 4
        )
        self.assertAlmostEqual(report["chance_end_to_end"], (1 + 2 + 1 + 1) / 20 / 4)

    def test_sessions_are_separate_and_compared_within_shared_participants(self):
        report = self.run_score(
            {
                ("p1", "run-02", 1): {
                    "status": "answered",
                    "kind": "scene",
                    "scene": 6,
                },
                ("p2", "run-02", 1): {
                    "status": "answered",
                    "kind": "scene",
                    "scene": 19,
                },
            }
        )
        self.assertEqual(report["sessions"]["run-02"]["scene"]["pooled"]["rate"], 1.0)
        self.assertEqual(report["sessions"]["run-01"]["scene"]["pooled"]["hits"], 0)
        self.assertEqual(
            report["participants_with_every_session"]["participants"], ["p1", "p2"]
        )

    def test_a_prediction_for_an_unknown_utterance_is_refused(self):
        with self.assertRaises(ff_score.ScoreError):
            self.run_score(
                {("p9", "run-01", 1): {"status": "answered", "kind": "none"}}
            )


if __name__ == "__main__":
    unittest.main()
