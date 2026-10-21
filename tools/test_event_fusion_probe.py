"""Synthetic engineering controls, with no film or participant excerpts."""

import copy
import math
import unittest

import event_fusion_probe as probe


class IntervalEvidenceTest(unittest.TestCase):
    def test_touching_and_instant_intervals_cannot_borrow_audio(self):
        segment = {"start_seconds": 10, "end_seconds": 20}
        self.assertFalse(probe.overlaps(0, 10, segment))
        self.assertFalse(probe.overlaps(20, 30, segment))
        self.assertFalse(probe.overlaps(12, 12, segment))
        self.assertTrue(probe.overlaps(12, 13, segment))

    def test_nonfinite_and_reversed_times_refuse(self):
        for segment in [
            {"start_seconds": math.nan, "end_seconds": 20},
            {"start_seconds": 20, "end_seconds": 10},
        ]:
            with self.assertRaises(ValueError):
                probe.overlaps(0, 30, segment)

    def test_complete_sentence_and_central_word_ownership_stay_distinct(self):
        transcript = {
            "outcome": "complete",
            "clock": {"origin": "source_media"},
            "segments": [
                {
                    "index": 0,
                    "text": "invented early late",
                    "start_seconds": 0,
                    "end_seconds": 20,
                }
            ],
            "words": [
                {"text": "early", "start_seconds": 1, "end_seconds": 2},
                {"text": "late", "start_seconds": 18, "end_seconds": 19},
            ],
        }
        selected = probe.select_audio(17, 20, transcript)
        self.assertEqual(selected["text"], "invented early late")
        self.assertEqual([w["text"] for w in selected["centralWords"]], ["late"])
        self.assertEqual(probe.select_audio(25, 30, transcript)["text"], "")

    def test_failed_audio_is_not_successful_silence(self):
        with self.assertRaises(ValueError):
            probe.select_audio(0, 1, {"outcome": "failed"})


class RankingTest(unittest.TestCase):
    def test_complementary_channels_retain_each_first_choice(self):
        a = probe.rank_channel(1, {1: 0.9, 2: 0.8, 3: 0.2, 4: None})
        v = probe.rank_channel(1, {1: 0.1, 2: 0.2, 3: 0.9, 4: 0.8})
        expected = sorted([1, 3], key=lambda row: probe.tie(1, row))
        self.assertEqual(probe.combine(1, [a, v])["top"][:2], expected)

    def test_duplicate_agreement_gets_no_extra_vote(self):
        a = probe.rank_channel(2, {1: 0.9, 2: 0.2, 3: 0.1})
        v = probe.rank_channel(2, {1: 0.1, 2: 0.2, 3: 0.9})
        self.assertEqual(
            probe.combine(2, [a, v]), probe.combine(2, [a, v, copy.deepcopy(a)])
        )

    def test_flat_and_missing_channels_abstain(self):
        flat = probe.rank_channel(0, {1: 0.5, 2: 0.5, 3: None})
        missing = probe.rank_channel(0, {1: None})
        self.assertEqual(flat["status"], "flat")
        self.assertEqual(missing["status"], "unavailable")
        self.assertEqual(probe.combine(0, [flat, missing])["top"], [])
        self.assertEqual(probe.combine(0, [flat, missing])["status"], "abstained")

    def test_similarity_ties_share_rank_and_ignore_insertion_order(self):
        a = probe.rank_channel(5, {1: 0.9, 2: 0.9, 3: 0.2})
        b = probe.rank_channel(5, {3: 0.2, 2: 0.9, 1: 0.9})
        self.assertEqual(a, b)
        self.assertEqual(a["ranks"], {**{r: 1 for r in [1, 2]}, 3: 3})
        self.assertEqual(a["bestTieCount"], 2)

    def test_nan_is_not_a_rankable_observation(self):
        with self.assertRaises(ValueError):
            probe.rank_channel(0, {1: math.nan, 2: 0.4})

    def test_independent_orthogonal_vector_controls(self):
        self.assertEqual(probe.cosine([3.0, 0.0], [0.0, 4.0]), 0.0)
        self.assertEqual(probe.cosine([3.0, 0.0], [4.0, 0.0]), 1.0)
        for b in [[0.0, 0.0], [1.0], [math.nan, 0.0]]:
            with self.assertRaises(ValueError):
                probe.cosine([3.0, 0.0], b)

    def test_negative_contrasts_are_characterization_not_assumed_success(self):
        self.assertEqual(
            set(probe.controls()["documents"]),
            {
                "paraphrase",
                "wrong-person",
                "sleeping",
                "negated",
                "earlier-reference",
                "hypothetical",
                "unrelated",
            },
        )


if __name__ == "__main__":
    unittest.main()
