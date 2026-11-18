"""Synthetic tests for the fresh-only provider boundary. No subscription calls."""
import json
from types import SimpleNamespace
from unittest.mock import patch

import tempfile
import unittest
from pathlib import Path

from cs2 import ff_producer
import llm


class FreshCallsTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp_path = Path(tmp.name)

    def test_fresh_transport_refuses_cache_before_provider(self):
        tmp_path = self.tmp_path
        prompt, system = "example", "synthetic"
        cache_key = llm.sha(json.dumps(["opus", system, prompt]))
        (tmp_path / f"{cache_key}.json").write_text(json.dumps(dict(result="cached")))
        with patch.object(llm, "CACHE", tmp_path), patch.object(
            llm.subprocess, "run"
        ) as run:
            with self.assertRaisesRegex(RuntimeError, "existing cache"):
                llm.call(prompt, system, fresh_model="pinned")
            assert llm.call(prompt, system) == "cached"
            run.assert_not_called()

    def test_fresh_transport_rejects_model_drift_and_refuses_retry(self):
        tmp_path = self.tmp_path
        wrong = dict(modelUsage={"different": {}}, result="synthetic")
        with patch.object(llm, "CACHE", tmp_path), patch.object(
            llm.subprocess,
            "run",
            return_value=SimpleNamespace(returncode=0, stdout=json.dumps(wrong)),
        ) as run:
            with self.assertRaisesRegex(RuntimeError, "unexpected model"):
                llm.call("one", "two", fresh_model="pinned")
            with self.assertRaises(FileExistsError):
                llm.call("one", "two", fresh_model="pinned")
            assert run.call_count == 1
            assert not list(tmp_path.glob("*.json"))

    def test_fresh_parse_retry_gets_a_distinct_key(self):
        tmp_path = self.tmp_path
        replies = [
            SimpleNamespace(
                returncode=0,
                stdout=json.dumps(dict(modelUsage={"pinned": {}}, result=answer)),
            )
            for answer in ('{"items":[3]}', '{"items":[{"id":"a"}]}')
        ]
        with patch.object(llm, "CACHE", tmp_path), patch.object(
            ff_producer, "CACHE", tmp_path
        ), patch.object(llm.subprocess, "run", side_effect=replies) as run:
            log = []
            values, attempt = ff_producer.parse_with_retry(
                "one",
                "two",
                log,
                "label",
                "items",
                "example",
                fresh_model="pinned",
                expected_ids={"a"},
            )
            assert (values, attempt) == ([{"id": "a"}], 1)
            assert run.call_count == 2
            assert all(c["fresh"] for c in log)
            assert log[0]["key"] != log[1]["key"]

    def test_memo_control_cannot_pass_without_eighty_valid_answers(self):
        from cs2 import sealed_production as run
        from cs2.source import GoldRow

        gold = {
            "synthetic": [
                GoldRow(
                    "synthetic",
                    "s",
                    i,
                    i,
                    i + 1,
                    False,
                    "specific",
                    "work",
                    frozenset({1}),
                    None,
                )
                for i in range(80)
            ]
        }

        def fake(*args, **kwargs):
            return [
                {"id": k, "movie": 100, "scene": 0} for k in kwargs["expected_ids"]
            ], 0

        with patch.object(
            run.filmfestival, "_work_codes", return_value={1: "work"}
        ), patch.object(run.ff_producer, "parse_with_retry", side_effect=fake):
            result = run.memorization(gold, None, {"work": [1, 2]})
        assert not result["complete"] and result["answered"] == 0
        assert result["movie"]["passed"] is None and result["scene"]["passed"] is None

    def test_malformed_nested_pass1_frame_is_counted(self):
        comp = SimpleNamespace(works=[])
        frames, bad = ff_producer.parse_frames(
            [{"id": "U1", "kind": "content", "frame": [0]}], [{}], comp
        )
        assert bad == 1 and frames[0]["kind"] is None
