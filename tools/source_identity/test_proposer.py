#!/usr/bin/env python3
"""Courts for the recorded model proposer (ADR 0027 section 4) on the producer's synthetic film.

No network: a fake transport stands in for the model. No story text or private data (rule 14d).
Run: python3 -m unittest discover -s tools/source_identity
"""
import json
from pathlib import Path
import tempfile
import unittest

import producer as p
import proposer as m
from test_producer import FACTS, Fixture

# What the fake model answers for each mention of the synthetic film.
ANSWERS = {
    "d-w2": {
        "decision": "deictic",
        "target": "v-r11-ana",
        "candidates": [],
        "reason": "r",
    },
    "d-w3": {
        "decision": "deictic",
        "target": "v-r11-ana",
        "candidates": [],
        "reason": "r",
    },
    "d-w4": {
        "decision": "candidates",
        "target": None,
        "candidates": ["v-r10-ana", "v-r11-ana"],
        "reason": "r",
    },
    "d-w5": {
        "decision": "deictic",
        "target": "v-r12-ben",
        "candidates": [],
        "reason": "r",
    },
    "d-w6": {
        "decision": "absent-or-unclear",
        "target": None,
        "candidates": [],
        "reason": "r",
    },
}


def reply(content, model=m.MODEL, provider=m.PROVIDER_NAME, finish="stop"):
    return {
        "id": "gen",
        "model": model,
        "provider": provider,
        "usage": {"cost": 0.0001},
        "choices": [{"finish_reason": finish, "message": {"content": content}}],
    }


def fake_transport(answers=ANSWERS, **kw):
    def transport(payload, key):
        text = payload["messages"][0]["content"]
        mention = json.loads(text.split("\nINPUT:\n", 1)[1])["mention"]
        return reply(json.dumps(answers[mention]), **kw)

    return transport


class ProposerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        fx = Fixture(root / "fixture")
        (root / "fixture").mkdir()
        run = fx.run()
        doc, uni = p.produce(run, probe=lambda _: FACTS)
        self.run_dir = root / "run"
        p.write(self.run_dir, doc, uni)
        self.transcript = Path(
            next(e["path"] for e in run["inputs"] if e["role"] == "asr-transcript")
        )
        self.exp = root / "exp"

    def tearDown(self):
        self.tmp.cleanup()

    def prepared(self, budget=1.0):
        return m.prepare(self.run_dir, self.transcript, self.exp, budget)

    def executed(self, transport=None):
        self.prepared()
        return m.execute(self.exp, "key", transport or fake_transport())

    def requests(self):
        return json.loads((self.exp / "requests.json").read_text())["requests"]

    def refused(self, call):
        with self.assertRaises(p.Refusal) as caught:
            call()
        return str(caught.exception)

    # --- prepare -----------------------------------------------------------------------------

    def test_the_model_sees_only_the_whitelisted_facts(self):
        self.prepared()
        for r in self.requests():
            self.assertEqual(
                set(r["input"]), {"mention", "class", "seconds", "text", "rows", "candidates"}
            )
            for row in r["input"]["rows"]:
                self.assertEqual(
                    set(row), {"row", "seconds", "people", "speaking", "overlapsMention"}
                )
        blob = (self.exp / "requests.json").read_text()
        self.assertNotIn("synthetic", blob)  # the fixture's scene-description column
        self.assertNotIn("p-name-", blob)  # no proposals

    def test_the_marked_text_is_the_mention_segment_only(self):
        self.prepared()
        r = next(r for r in self.requests() if r["mention"] == "d-w2")
        self.assertEqual(r["input"]["text"], "[[she's]] woman her")

    def test_the_model_is_told_when_the_mention_is_spoken_and_which_row_is_on_screen(self):
        self.prepared()
        r = next(r for r in self.requests() if r["mention"] == "d-w2")
        self.assertEqual(r["input"]["seconds"], ["4", "4.3"])
        flags = {row["row"]: row["overlapsMention"] for row in r["input"]["rows"]}
        self.assertEqual(flags, {10: False, 11: True, 12: False})

    def test_requests_are_the_frozen_universe_mentions(self):
        self.prepared()
        uni = json.loads((self.run_dir / "universe.json").read_text())
        self.assertEqual(
            [r["mention"] for r in self.requests()],
            sorted({x["mention"] for x in uni["pairs"]}),
        )

    def test_a_transcript_other_than_the_pinned_one_is_refused(self):
        self.transcript.write_text("{}")
        self.assertIn(
            "not the one the producer run pinned", self.refused(self.prepared)
        )

    def test_a_budget_above_the_owner_ceiling_is_refused(self):
        self.assertIn(
            "budget must be", self.refused(lambda: self.prepared(budget=5.01))
        )

    def test_a_cost_bound_above_the_budget_is_refused(self):
        self.assertIn(
            "exceeds budget", self.refused(lambda: self.prepared(budget=1e-6))
        )

    # --- execute -----------------------------------------------------------------------------

    def test_execution_without_a_key_is_refused(self):
        self.prepared()
        self.assertIn(
            "absent", self.refused(lambda: m.execute(self.exp, None, fake_transport()))
        )

    def test_changed_requests_are_refused(self):
        self.prepared()
        path = self.exp / "requests.json"
        path.write_text(path.read_text().replace("d-w2", "d-w9"))
        self.assertIn(
            "differ from the freeze",
            self.refused(lambda: m.execute(self.exp, "key", fake_transport())),
        )

    def test_a_second_execution_is_refused(self):
        self.executed()
        self.assertIn(
            "already attempted",
            self.refused(lambda: m.execute(self.exp, "key", fake_transport())),
        )

    def test_each_request_is_attempted_once_and_recorded(self):
        summary = self.executed()
        lines = (self.exp / "outputs.jsonl").read_text().splitlines()
        self.assertEqual(len(lines), summary["requested"])
        self.assertTrue(all(json.loads(x)["attempts"] == 1 for x in lines))
        self.assertAlmostEqual(summary["reportedCostUsd"], 0.0001 * len(lines))

    def test_a_reply_from_another_model_or_provider_is_not_used(self):
        self.executed(fake_transport(provider="Other"))
        outcomes = {
            json.loads(x)["processing"]
            for x in (self.exp / "outputs.jsonl").read_text().splitlines()
        }
        self.assertEqual(outcomes, {"foreign_provider_or_model"})

    def test_a_target_outside_the_candidates_is_an_invalid_proposal(self):
        bad = dict(ANSWERS, **{"d-w2": dict(ANSWERS["d-w2"], target="v-r99-zed")})
        self.executed(fake_transport(bad))
        rows = {
            json.loads(x)["mention"]: json.loads(x)
            for x in (self.exp / "outputs.jsonl").read_text().splitlines()
        }
        self.assertEqual(rows["d-w2"]["processing"], "invalid_proposal")

    def test_an_unpriced_reply_is_accounted_at_its_bound(self):
        def unpriced(payload, key):
            r = fake_transport()(payload, key)
            r["usage"] = {}
            return r

        summary = self.executed(unpriced)
        self.assertEqual(summary["reportedCostCount"], 0)
        self.assertAlmostEqual(
            summary["accountedUpperBoundUsd"], summary["attemptedBoundUsd"]
        )

    # --- materialize -------------------------------------------------------------------------

    def materialized(self, transport=None):
        self.executed(transport)
        m.materialize(self.run_dir, self.exp, self.exp / "out")
        return json.loads((self.exp / "out" / "inputs.json").read_text())

    def proposal(self, doc, pid):
        return next(x for x in doc["proposals"] if x["id"] == pid)

    def test_control_an_admissible_deictic_proposal_passes_the_guard(self):
        doc = self.materialized()
        self.assertEqual(
            self.proposal(doc, "p-model-d-w2")["producer"], {"admitted": True}
        )

    def test_deixis_to_the_speaker_is_refused_by_the_producer_and_recorded(self):
        doc = self.materialized()
        self.assertEqual(
            self.proposal(doc, "p-model-d-w5")["producer"],
            {"admitted": False, "reason": "the target is the speaker"},
        )

    def test_a_deictic_answer_for_a_noun_is_refused_by_the_producer(self):
        doc = self.materialized()
        self.assertEqual(
            self.proposal(doc, "p-model-d-w3")["producer"]["reason"],
            "only a third-person pronoun may be deictic",
        )

    def test_a_candidate_set_becomes_hints_only(self):
        doc = self.materialized()
        hints = sorted(x["right"] for x in doc["proposals"] if x["left"] == "d-w4")
        self.assertEqual(hints, ["v-r10-ana", "v-r11-ana"])
        self.assertTrue(
            all(
                x["kind"] == "hint" and x["basis"] == "CompatibleDescription"
                for x in doc["proposals"]
                if x["left"] == "d-w4"
            )
        )

    def test_absent_or_unclear_yields_no_proposal(self):
        doc = self.materialized()
        self.assertFalse([x for x in doc["proposals"] if x["left"] == "d-w6"])

    def test_the_recordings_enter_the_manifest(self):
        doc = self.materialized()
        roles = [e["role"] for e in doc["inputs"]]
        self.assertIn("model-recordings", roles)
        self.assertEqual(len(roles), len(set(roles)))

    def test_tampered_recordings_are_refused(self):
        self.executed()
        path = self.exp / "outputs.jsonl"
        path.write_text(path.read_text().replace("deictic", "candidates", 1))
        self.assertIn(
            "recordings differ",
            self.refused(
                lambda: m.materialize(self.run_dir, self.exp, self.exp / "out")
            ),
        )

    def test_a_recording_edited_with_its_summary_is_refused(self):
        self.executed()
        out, summary = self.exp / "outputs.jsonl", self.exp / "execution.json"
        rows = [json.loads(x) for x in out.read_text().splitlines()]
        for row in rows:
            if row["mention"] == "d-w5":
                row["proposal"]["target"] = "v-r12-2-guards-1"  # a target the reply never named
        out.write_text("".join(json.dumps(x, sort_keys=True) + "\n" for x in rows))
        s = json.loads(summary.read_text())
        s["outputsSha256"] = m.file_sha(out)
        summary.write_bytes(m.canonical(s) + b"\n")
        self.assertIn("differs from its reply", self.refused(
            lambda: m.materialize(self.run_dir, self.exp, self.exp / "out")))

    def test_requests_changed_after_execution_are_refused_by_materialize(self):
        self.executed()
        path = self.exp / "requests.json"
        path.write_text(path.read_text().replace('"reason"', '"reason" ', 1) + " ")
        self.assertIn("requests differ from the freeze", self.refused(
            lambda: m.materialize(self.run_dir, self.exp, self.exp / "out")))

    def test_materialize_replays_identically(self):
        self.executed()
        m.materialize(self.run_dir, self.exp, self.exp / "a")
        m.materialize(self.run_dir, self.exp, self.exp / "b")
        self.assertEqual(
            (self.exp / "a" / "inputs.json").read_bytes(),
            (self.exp / "b" / "inputs.json").read_bytes(),
        )


if __name__ == "__main__":
    unittest.main()
