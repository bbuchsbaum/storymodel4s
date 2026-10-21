"""Binding failures must not become caption-quality evidence."""

import copy
import hashlib
import unittest

from caption_fidelity import requests, validate_outcome


class CaptionFidelityTest(unittest.TestCase):
    def setUp(self):
        self.source = {
            "frames": {
                "sha256": "f" * 64,
                "width": 32,
                "height": 32,
                "count": 6,
                "pixelFormat": "bgr24",
            },
            "model": {"repo": "synthetic", "revision": "fixed"},
            "extents": [{"id": "extent", "ordinals": [0, 2, 5]}],
            "recipe": {
                "prompt": "Describe visible content.",
                "minPixels": 1024,
                "maxPixels": 1024,
                "maxNewTokens": 96,
                "greedy": True,
                "presentation": "image-sequence",
                "seed": 0,
            },
        }
        self.cases = [{"id": "sample", "extentId": "extent", "ordinals": [0, 2, 5]}]
        self.arms = requests(self.source, self.cases, "/synthetic/frames.bgr")
        request = self.arms["joint"]
        self.outcome = {
            "schema": "storymodel4s.media.caption-outcome",
            "schemaVersion": 1,
            "requestId": request["requestId"],
            "recipe": copy.deepcopy(request["recipe"]),
            "frames": copy.deepcopy(request["frames"]),
            "model": request["model"],
            "applied": {
                **request["recipe"],
                "promptSha256": hashlib.sha256(
                    request["recipe"]["prompt"].encode()
                ).hexdigest(),
            },
            "offline": {"HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1"},
            "extents": [
                {"id": "sample", "ordinals": [0, 2, 5], "text": "A blue cube."}
            ],
            "coverage": {"requested": 1, "observed": 1},
        }

    def test_valid_outcome_and_fixed_comparison_inputs(self):
        validate_outcome(self.arms["joint"], self.outcome)
        for arm in self.arms.values():
            self.assertEqual(arm["model"], self.source["model"])
            self.assertEqual(arm["recipe"]["maxNewTokens"], 96)
            self.assertEqual(arm["frames"]["sha256"], "f" * 64)
        self.assertEqual(len({r["requestId"] for r in self.arms.values()}), 3)

    def test_separate_calls_preserve_exact_noncontiguous_frame_ownership(self):
        self.assertEqual(
            self.arms["separate-images"]["extents"],
            [
                {"id": "sample:image-1", "ordinals": [0]},
                {"id": "sample:image-2", "ordinals": [2]},
                {"id": "sample:image-3", "ordinals": [5]},
            ],
        )
        self.assertEqual(self.source["extents"][0]["id"], "extent")

    def test_another_arms_outcome_is_refused(self):
        with self.assertRaisesRegex(ValueError, "request identity"):
            validate_outcome(self.arms["identity-aware"], self.outcome)

    def test_changed_frame_ownership_is_refused(self):
        self.outcome["extents"][0]["ordinals"] = [0, 1, 5]
        with self.assertRaisesRegex(ValueError, "extent coverage"):
            validate_outcome(self.arms["joint"], self.outcome)

    def test_missing_caption_cannot_pass_a_declared_coverage_count(self):
        self.outcome["extents"] = []
        with self.assertRaisesRegex(ValueError, "extent coverage"):
            validate_outcome(self.arms["joint"], self.outcome)

    def test_false_applied_prompt_is_refused(self):
        self.outcome["applied"]["promptSha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "applied prompt"):
            validate_outcome(self.arms["joint"], self.outcome)

    def test_case_cannot_silently_select_different_images(self):
        self.cases[0]["ordinals"] = [0, 1, 2]
        with self.assertRaisesRegex(ValueError, "case ordinals"):
            requests(self.source, self.cases, "/synthetic/frames.bgr")


if __name__ == "__main__":
    unittest.main()
