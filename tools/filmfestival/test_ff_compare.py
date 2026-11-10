"""Tests for the arm comparison, on invented numbers.

Run from the repository root: python3 tools/filmfestival/test_ff_compare.py
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_compare  # noqa: E402
import ff_score  # noqa: E402


def rates(**values):
    return {p: {"n": 10, "hits": int(v * 10), "rate": v} for p, v in values.items()}


class Compare(unittest.TestCase):
    def test_difference_is_secondary_minus_primary_within_participant(self):
        out = ff_compare.paired(rates(a=0.5, b=0.8, c=0.2), rates(a=0.7, b=0.6, c=0.2))
        self.assertAlmostEqual(out["by_participant"]["a"], 0.2)
        self.assertAlmostEqual(out["by_participant"]["b"], -0.2)
        self.assertAlmostEqual(out["mean_difference"], 0.0)
        self.assertEqual((out["secondary_higher"], out["secondary_lower"]), (1, 1))
        lo, hi = out["interval95"]
        self.assertTrue(lo < 0 < hi)
        self.assertEqual(
            out,
            ff_compare.paired(rates(a=0.5, b=0.8, c=0.2), rates(a=0.7, b=0.6, c=0.2)),
        )

    def test_a_constant_shift_has_a_degenerate_interval(self):
        out = ff_compare.paired(rates(a=0.5, b=0.25), rates(a=0.75, b=0.5))
        self.assertEqual(out["interval95"], [0.25, 0.25])

    def test_participants_without_eligible_utterances_are_left_out_of_both(self):
        primary = dict(rates(a=0.5), z={"n": 0, "hits": 0, "rate": None})
        secondary = dict(rates(a=0.6), z={"n": 0, "hits": 0, "rate": None})
        out = ff_compare.paired(primary, secondary)
        self.assertEqual(out["participants"], 1)
        self.assertIsNone(out["interval95"])

    def test_arms_scored_on_different_participants_are_refused(self):
        with self.assertRaises(ff_score.ScoreError):
            ff_compare.paired(rates(a=0.5), rates(b=0.5))

    def test_answers_agree_only_when_kind_and_target_match(self):
        scene = {"status": "answered", "kind": "scene", "scene": 5, "film_stated": 1}
        other = {"status": "answered", "kind": "scene", "scene": 6, "film_stated": 9}
        film = {"status": "answered", "kind": "film", "film": 2}
        none = {"status": "answered", "kind": "none"}
        failed = {"status": "failed", "reason": "omitted"}
        self.assertEqual(ff_compare.answer(scene), ("scene", 5))
        self.assertEqual(ff_compare.answer(other), ("scene", 6))
        self.assertEqual(ff_compare.answer(film), ("film", 2))
        self.assertEqual(ff_compare.answer(none), ("none", None))
        self.assertEqual(ff_compare.answer(failed), ("failed",))
        a = {
            ("p", "s1", 1): ("scene", 5),
            ("p", "s1", 2): ("film", 2),
            ("p", "s2", 1): ("scene", 5),
        }
        b = {
            ("p", "s1", 1): ("scene", 6),
            ("p", "s1", 2): ("film", 2),
            ("p", "s2", 1): ("scene", 5),
        }
        self.assertEqual(
            ff_compare.agreement(a, b),
            {
                "s1": {"n": 2, "same": 1, "both_scene": 1, "same_scene": 0},
                "s2": {"n": 1, "same": 1, "both_scene": 1, "same_scene": 1},
            },
        )
        with self.assertRaises(ff_score.ScoreError):
            ff_compare.agreement(a, {("p", "s1", 1): ("scene", 5)})

    def test_score_files_must_match_in_field_and_population(self):
        def score(field, population):
            block = {"by_participant": rates(a=0.5)}
            session = {"scene": block, "film": {"film_reference": block}}
            return {
                "field": field,
                "population": population,
                "sessions": {"s1": session},
            }

        ok = ff_compare.compare(score("referent", 1), score("referent", 1), {}, {})
        self.assertEqual(ok["sessions"]["s1"]["scene"]["mean_difference"], 0.0)
        with self.assertRaises(ff_score.ScoreError):
            ff_compare.compare(score("referent", 1), score("narrative", 1), {}, {})
        with self.assertRaises(ff_score.ScoreError):
            ff_compare.compare(score("referent", 1), score("referent", 2), {}, {})

    def score(self, adjacent=None, within=None, **identity):
        block = {"by_participant": rates(a=0.5, b=0.5)}
        session = {"scene": block, "film": {"film_reference": block}}
        if adjacent:
            session["scene_adjacent"] = {"by_participant": adjacent}
        if within:
            session["scene_within_2s"] = {"by_participant": within}
        out = {"field": "referent", "population": 1, "sessions": {"s1": session}}
        return dict(out, **identity)

    def test_adjacent_agreement_is_compared_and_within_2s_only_when_both_have_it(self):
        scenes = self.score(rates(a=0.9, b=0.8), model="m", condition="annotation")
        times = self.score(
            rates(a=0.7, b=0.8), rates(a=0.6, b=0.6), model="m", condition="auto"
        )
        out = ff_compare.compare(scenes, times, {}, {}, across_conditions=True)
        row = out["sessions"]["s1"]
        self.assertAlmostEqual(row["scene_adjacent"]["by_participant"]["a"], -0.2)
        self.assertAlmostEqual(row["scene_adjacent"]["mean_difference"], -0.1)
        self.assertEqual(row["scene"]["mean_difference"], 0.0)
        self.assertNotIn("scene_within_2s", row)
        other = self.score(
            rates(a=0.7, b=0.8), rates(a=0.8, b=0.6), model="n", condition="auto"
        )
        both = ff_compare.compare(times, other, {}, {})["sessions"]["s1"]
        self.assertAlmostEqual(both["scene_within_2s"]["mean_difference"], 0.1)
        old = ff_compare.compare(self.score(), self.score(), {}, {})
        self.assertEqual(set(old["sessions"]["s1"]), {"scene", "film_reference"})
        with self.assertRaises(ff_score.ScoreError):
            ff_compare.compare(self.score(rates(a=0.9, b=0.8)), self.score(), {}, {})

    def test_each_sides_identity_is_recorded_and_conditions_are_not_mixed_by_default(
        self,
    ):
        a = self.score(model="m", condition="annotation", manifestSha256="1")
        b = self.score(model="n", condition="annotation", manifestSha256="2")
        c = self.score(model="m", condition="auto", manifestSha256="3")
        d = self.score(model="n", condition="auto", manifestSha256="4")
        out = ff_compare.compare(a, b, {}, {})
        self.assertEqual(
            out["primary"],
            {"model": "m", "condition": "annotation", "manifestSha256": "1"},
        )
        self.assertEqual(out["secondary"]["model"], "n")
        self.assertEqual(out["across_conditions"], False)
        across = ff_compare.compare(a, c, {}, {}, across_conditions=True)
        self.assertEqual(across["secondary"]["condition"], "auto")
        for first, second, mode in (
            (a, c, False),
            (a, d, False),
            (a, d, True),
            (a, b, True),
        ):
            with self.assertRaises(ff_score.ScoreError):
                ff_compare.compare(first, second, {}, {}, across_conditions=mode)


if __name__ == "__main__":
    unittest.main()
