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
            for answer in ("bad", '{"items": [3]}')
        ]
        with patch.object(llm, "CACHE", tmp_path), patch.object(
            ff_producer, "CACHE", tmp_path
        ), patch.object(llm.subprocess, "run", side_effect=replies) as run:
            log = []
            values, attempt = ff_producer.parse_with_retry(
                "one", "two", log, "label", "items", "example", fresh_model="pinned"
            )
            assert (values, attempt) == ([3], 1)
            assert run.call_count == 2
            assert all(c["fresh"] for c in log)
            assert log[0]["key"] != log[1]["key"]
