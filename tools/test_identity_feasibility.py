"""Synthetic courts for census coverage, selection boundaries and text custody."""
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest

import identity_feasibility as subject


def row(word, seconds, tr):
    return {subject.WORD: word, subject.SECONDS: str(seconds), subject.TR_INDEX: str(tr)}


def bits(value):
    return "0x" + struct.pack(">d", value).hex()


class CensusTests(unittest.TestCase):
    def test_every_word_survives_screen_and_scene_misses(self):
        rows = [row("He", 0, 1), row("saw", 1, 1), row("someone", 10, 7), row("then", "NaN", "NaN")]
        summary, ledger, windows = subject.inventory(rows, [(2, 0, 1)], window_size=3)
        self.assertEqual([x["word_row"] for x in ledger], [1, 2, 3, 4])
        self.assertEqual(summary["counts"]["no_scene_nomination_rows"], 2)
        self.assertEqual(summary["counts"]["timing_missing_rows"], 1)
        self.assertEqual(summary["counts"]["screened_word_rows"], 1)
        self.assertIsNone(summary["human_resolvable_decisions"])
        self.assertEqual([(w["first_word"], w["last_word"]) for w in windows], [(1, 3), (4, 4)])

    def test_closed_legacy_interval_and_one_based_tr_are_different(self):
        rows = [row("one", 1.5, 2), row("two", 3, 3), row("three", 4.5, 4)]
        _, ledger, _ = subject.inventory(rows, [(7, 2, 3)])
        self.assertEqual([x["legacy_seconds_scenes"] for x in ledger], [[], [7], [7]])
        self.assertEqual([x["nomination_scenes"] for x in ledger], [[7], [7], []])
        self.assertEqual([x["boundary_disagreement"] for x in ledger], [True, False, True])

    def test_overlapping_scene_nominations_are_not_arbitrated(self):
        summary, ledger, _ = subject.inventory([row("her", 3, 3)], [(7, 1, 3), (8, 2, 4)])
        self.assertEqual(ledger[0]["nomination_scenes"], [7, 8])
        self.assertEqual(summary["counts"]["multiple_scene_nomination_rows"], 1)

    def test_screen_does_not_claim_semantic_decisions(self):
        summary, _, _ = subject.inventory([row("She’s", 1, 1), row("herself", 2, 2)], [(7, 0, 3)])
        self.assertEqual(summary["counts"]["pronoun_screen_rows"], 2)
        self.assertIsNone(summary["human_resolvable_decisions"])
        self.assertEqual(summary["go_status"], "not_evaluated")

    def test_legacy_export_cannot_hide_an_omitted_word_or_duplicate(self):
        rows = [row("Alpha", 1, 1), row("Beta", 2, 2), row("Gamma", 3, 3)]
        units = [{"onset": bits(1.), "lastWordOnset": bits(2.), "text": "Alpha Beta"},
                 {"onset": bits(2.), "lastWordOnset": bits(2.), "text": "Beta"}]
        result = subject.audit_legacy_units(rows, units)
        self.assertEqual(result["exact_normalized_text_matches"], 2)
        self.assertEqual(result["word_rows_not_selected"], 1)
        self.assertEqual(result["word_rows_selected_more_than_once"], 1)

    def test_summary_and_ledger_do_not_copy_raw_text(self):
        canary = "UNIQUE-PRIVATE-CANARY-123"
        summary, ledger, _ = subject.inventory([row(canary, 1, 1)], [(7, 0, 1)])
        self.assertNotIn(canary, json.dumps([summary, ledger]))

    def test_split_is_total_and_body_cases_cannot_be_confirmation(self):
        split = subject.scene_split()
        self.assertEqual(set(split), set(range(1, 51)))
        self.assertEqual([split[29], split[30]], ["discovery", "discovery"])
        self.assertEqual(list(split.values()).count("confirmation"), 24)

    def test_duplicate_header_and_invalid_scene_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            p = Path(folder) / "bad.csv"
            p.write_text("a,a\n1,2\n")
            with self.assertRaisesRegex(ValueError, "repeats"):
                subject.read_csv(p)
        with self.assertRaises(ValueError):
            subject.scene_intervals([{"Subject": "3", "Scene": "1", "Onset": "NaN", "Offset": "3"}], "3")

    def test_private_output_requires_ignore_and_preserves_prior_runs(self):
        with tempfile.TemporaryDirectory() as folder:
            subprocess.run(["git", "init", "-q", folder], check=True)
            with self.assertRaisesRegex(ValueError, "ignored"):
                subject.private_directory(Path(folder) / "public")
            (Path(folder) / ".gitignore").write_text("private/\n")
            private = subject.private_directory(Path(folder) / "private")
            rows = [row("<synthetic>&", 1, 1)]
            _, ledger, windows = subject.inventory(rows, [(7, 0, 1)])
            manifest = subject.write_private(private, rows, ledger, windows)
            self.assertEqual(set(manifest), {"review.html", "word-census.json", "review-windows.csv", "decisions.csv"})
            self.assertIn("&lt;synthetic&gt;&amp;", (private / "review.html").read_text())
            self.assertEqual((private / "review.html").stat().st_mode & 0o777, 0o600)
            with self.assertRaisesRegex(ValueError, "new or empty"):
                subject.private_directory(private)


if __name__ == "__main__":
    unittest.main()
