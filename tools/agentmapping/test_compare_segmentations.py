"""Mechanical token fixtures; no narrative or participant text.

Run: python3 tools/agentmapping/test_compare_segmentations.py
"""

import itertools
import json
from pathlib import Path
import tempfile
import unittest

import compare_segmentations as c


def descriptor(raw, spans):
    text = c.canonicalize(raw)
    offsets = c.utf16_offsets(text)
    return {"recipe": "recall-segmenter/v1", "canonicalization": "story-source/v1",
            "offsetUnit": "utf-16-code-units", "rawSha256": c.sha(raw.encode()),
            "canonicalSha256": c.sha(text.encode()), "rawText": raw, "canonicalText": text,
            "units": [{"u": i + 1, "text": text[a:b],
                       "spans": [{"start": offsets[a], "endExclusive": offsets[b]}]}
                      for i, (a, b) in enumerate(spans)]}


class ComparisonTests(unittest.TestCase):
    def test_repeated_phrase_resolved_by_both_neighbors(self):
        result = c.correspond("x a y a z", ["y", "a", "z"])
        self.assertEqual([u["span"] for u in result], [[4, 5], [6, 7], [8, 9]])

    def test_repeated_phrase_remains_ambiguous(self):
        self.assertEqual(c.correspond("a x a", ["a"]),
                         [{"status": "ambiguous", "candidates": 2}])

    def test_missing_unit_does_not_become_an_ordered_match(self):
        self.assertEqual(c.correspond("a b", ["a", "x", "b"]),
                         [{"status": "unique", "span": [0, 1]},
                          {"status": "absent"}, {"status": "unique", "span": [2, 3]}])

    def test_reordered_units_refuse_global_correspondence(self):
        self.assertEqual([u["status"] for u in c.correspond("a b", ["b", "a"])],
                         ["order-conflict", "order-conflict"])

    def test_overlapping_occurrences_cannot_both_be_used(self):
        self.assertEqual([u["status"] for u in c.correspond("a b c", ["a b", "b c"])],
                         ["order-conflict", "order-conflict"])

    def test_unit_cannot_match_inside_another_word(self):
        self.assertEqual(c.correspond("ab", ["a"]), [{"status": "absent"}])

    def test_small_sequences_against_exhaustive_independent_enumeration(self):
        for chars in itertools.product("ab", repeat=5):
            text = " ".join(chars)
            for units in itertools.product(["a", "b", "a b"], repeat=3):
                occurrences = [[(i, i + len(u)) for i in range(len(text))
                                if text[i:i + len(u)] == u] for u in units]
                present = [i for i, xs in enumerate(occurrences) if xs]
                paths = [xs for xs in itertools.product(*(occurrences[i] for i in present))
                         if all(a[1] <= b[0] for a, b in zip(xs, xs[1:]))]
                actual = c.correspond(text, list(units))
                for j, i in enumerate(present):
                    choices = {xs[j] for xs in paths}
                    expected = "order-conflict" if not choices else (
                        "unique" if len(choices) == 1 else "ambiguous")
                    self.assertEqual(actual[i]["status"], expected)
                    if expected == "unique":
                        self.assertEqual(tuple(actual[i]["span"]), next(iter(choices)))

    def test_boundaries_have_independently_known_split_and_merge_counts(self):
        raw = "aa bb cc dd"
        report = c.compare(raw, ["aa bb", "cc", "dd"],
                           descriptor(raw, [(0, 2), (3, 8), (9, 11)]))
        self.assertEqual(report["boundaries"], {"matched": 1, "supplied_only": 1,
                         "automatic_only": 1, "eligible_supplied": 2,
                         "eligible_automatic": 2, "excluded_supplied": 0})
        self.assertEqual(report["mapping_error"]["status"], "not-assessed")

    def test_no_boundary_score_crosses_an_excluded_unit(self):
        raw = "a b c d e"
        report = c.compare(raw, ["a", "b", "x", "d", "e"],
                           descriptor(raw, [(0, 1), (2, 3), (4, 5), (6, 7), (8, 9)]))
        self.assertEqual(report["eligible_domains"], [[0, 3], [6, 9]])
        self.assertEqual(report["boundaries"]["matched"], 2)
        self.assertEqual(report["boundaries"]["automatic_only"], 0)
        self.assertEqual(report["boundaries"]["excluded_supplied"], 2)

    def test_source_gaps_remain_visible(self):
        raw = "a omitted b"
        report = c.compare(raw, ["a", "b"], descriptor(raw, [(0, 1), (2, 9), (10, 11)]))
        self.assertEqual(report["uncovered_source_alphanumeric_codepoints"], 7)
        self.assertEqual(report["boundaries"]["automatic_only"], 1)

    def test_utf16_non_bmp_and_split_surrogate_refusal(self):
        raw = "a 😀 b"
        desc = descriptor(raw, [(0, 1), (2, 3), (4, 5)])
        report = c.compare(raw, ["a", "😀", "b"], desc)
        self.assertEqual(report["unit_correspondence"][1]["span"], [2, 4])
        desc["units"][1]["spans"][0]["endExclusive"] = 3
        with self.assertRaises(c.Refusal):
            c.compare(raw, ["a", "😀", "b"], desc)

    def test_raw_identity_not_only_canonical_identity(self):
        raw = "a\r\nb"
        desc = descriptor(raw, [(0, 1), (2, 3)])
        with self.assertRaises(c.Refusal):
            c.compare("a\nb", ["a", "b"], desc)

    def test_recipe_surface_and_overlap_are_checked(self):
        raw = "a b"
        for alter in [lambda d: d.update(recipe="unknown"),
                      lambda d: d["units"][0].update(text="b"),
                      lambda d: d["units"][1]["spans"][0].update(start=0)]:
            desc = descriptor(raw, [(0, 1), (2, 3)])
            alter(desc)
            with self.assertRaises(c.Refusal):
                c.compare(raw, ["a", "b"], desc)

    def test_canonicalization_preserves_leading_spaces_and_unicode_separators(self):
        self.assertEqual(c.canonicalize("\r\n  a \t\r\n\r\n\r\nb\t\n"), "  a\n\nb")
        self.assertEqual(c.canonicalize("a \u2028"), "a\u2028")
        self.assertEqual(c.canonicalize("a\u2028 "), "a\u2028")
        self.assertEqual(c.canonicalize("a\n\u2028"), "a\u2028")

    def test_cli_verifies_source_and_artifact_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "source.csv"
            source.write_text('Words,other\na,0\nb,1\n')
            raw, lineage = c.replay_source(source, c.sha(source.read_bytes()),
                                           "sherlock-word-csv-replay/v1")
            self.assertEqual(raw, "a b")
            self.assertEqual(lineage["records"], 2)
            supplied, automatic = root / "supplied.json", root / "auto.json"
            supplied.write_text(json.dumps({"units": [{"key": "1", "text": "a"},
                                                        {"key": "2", "text": "b"}]}))
            automatic.write_text(json.dumps({"transcript": descriptor(raw, [(0, 1), (2, 3)])}))
            spec = {"schema": c.SCHEMA, "source": {"path": source.name,
                    "sha256": c.sha(source.read_bytes()), "recipe": "sherlock-word-csv-replay/v1"},
                    "supplied": {"path": supplied.name, "sha256": c.sha(supplied.read_bytes())},
                    "automatic": {"path": automatic.name, "sha256": c.sha(automatic.read_bytes())}}
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps(spec))
            self.assertEqual(c.main([str(manifest), "--receipt", str(root / "ok.json")]), 0)
            source.write_text('Words,other\na,0\nc,1\n')
            self.assertEqual(c.main([str(manifest), "--receipt", str(root / "bad.json")]), 2)
            self.assertFalse((root / "bad.json").exists())


if __name__ == "__main__":
    unittest.main()
