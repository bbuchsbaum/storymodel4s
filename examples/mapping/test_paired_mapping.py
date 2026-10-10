#!/usr/bin/env python3
"""Independent reader controls for actual PairedExchangeSuite publication bytes."""
import copy
import hashlib
import json
import math
from pathlib import Path
import sys
import unittest

from read_mapping import number, parse, read_publication


class PairedReaderTests(unittest.TestCase):
    fixtures = {}

    def files(self, name="text"):
        return copy.deepcopy(self.fixtures[name])

    def metadata(self, change, name="text"):
        files = self.files(name)
        manifest = parse(files["manifest.json"])
        change(manifest["metadata"])
        files["manifest.json"] = json.dumps(manifest)
        return files

    def rehash(self, files, path):
        manifest = parse(files["manifest.json"])
        raw = files[path].encode("utf-8")
        entry = next(e for e in manifest["files"] if e["path"] == path)
        entry["sha256"] = hashlib.sha256(raw).hexdigest()
        if "bytes" in entry:
            entry["bytes"] = str(len(raw))
        files["manifest.json"] = json.dumps(manifest)

    def test_publication_accounts_for_two_recalls_and_one_failed_attempt(self):
        value = read_publication(self.files())
        self.assertEqual(len(value["pairs"]), 2)
        self.assertEqual([o["execution"] for o in value["outcomes"]], ["complete", "complete", "failed"])
        inventories = []
        for pair in value["pairs"]:
            for name in ("reference", "reconstruction"):
                record, tables = pair[name]
                self.assertEqual(len(tables["units"]), 1)
                self.assertEqual([(w["start"], w["end_exclusive"]) for w in tables["words"]], [(0, 4), (5, 12)])
                self.assertEqual(len(tables["decisions"]), 1)
                self.assertEqual(len(tables["targets"]), 2)
            inventories.append(pair["reference"][0]["inventory"]["digest"])
        self.assertEqual(len(set(inventories)), 2)

    def test_one_unit_mass_matches_independent_analytic_answer(self):
        # Independent reviewer derived costs before seeing these tests or producer output:
        # A has cost0; B and each of five external states have cost1. One unit has no transitions.
        for pair in read_publication(self.files())["pairs"]:
            for name, temperature in (("reference", 1.0), ("reconstruction", 0.15)):
                _, tables = pair[name]
                by_span = {}
                for row in tables["target-support"]:
                    support = row["support_status"]["source_support"]
                    self.assertEqual(support["status"], "text")
                    spans = tuple((s["start"], s["end_exclusive"]) for s in support["spans"])
                    by_span[spans] = row["target_id"]
                self.assertEqual(set(by_span), {((0, 13),), ((14, 23),)})
                a, b = by_span[((0, 13),)], by_span[((14, 23),)]
                q = math.exp(-1.0 / temperature)
                z = 1.0 + 6.0 * q
                expected = {a: 1.0 / z, b: q / z, **{
                    "ext:" + key: q / z for key in (
                        "Association", "Commentary", "SourceConsistentInference", "Intrusion", "Uninterpretable"
                    )}}
                actual = {r["destination"]: number(r["raw_value"]) for r in tables["alternatives"]
                          if r["measure_kind"] == ("NormalizedScoreMass" if name == "reference" else "ModelPosterior")}
                self.assertEqual(set(actual), set(expected))
                for key, value in expected.items():
                    self.assertTrue(math.isclose(actual[key], value, rel_tol=1e-12, abs_tol=1e-14), (name, key, actual[key], value))

    def test_partial_and_extra_publications_refuse(self):
        files = self.files()
        del files["manifest.json"]
        with self.assertRaises(ValueError):
            read_publication(files)
        files = self.files()
        files["extra.json"] = "{}"
        with self.assertRaises(ValueError):
            read_publication(files)

    def test_changed_payload_bytes_refuse(self):
        files = self.files()
        path = next(p for p in files if p.endswith("reference--alternatives.tsv"))
        files[path] += "\n"
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            read_publication(files)

    def test_dropped_failed_attempt_refuses(self):
        files = self.metadata(lambda m: m["outcomes"].pop())
        with self.assertRaisesRegex(ValueError, "attempt denominator"):
            read_publication(files)

    def test_foreign_source_edition_refuses(self):
        files = self.metadata(lambda m: m.update(sourceEdition="0" * 64))
        with self.assertRaisesRegex(ValueError, "source edition"):
            read_publication(files)

    def test_foreign_execution_refuses(self):
        files = self.metadata(lambda m: m["outcomes"][0]["mapping"].update(execution="0" * 64))
        with self.assertRaisesRegex(ValueError, "run execution"):
            read_publication(files)

    def test_foreign_profile_refuses(self):
        files = self.metadata(lambda m: m["outcomes"][0]["mapping"]["members"][0].update(profile="0" * 64))
        with self.assertRaisesRegex(ValueError, "member join"):
            read_publication(files)

    def test_duplicate_members_refuse(self):
        files = self.files()
        path = next(p for p in files if p.endswith("--manifest.json") and "reference--" not in p and "reconstruction--" not in p)
        pair = parse(files[path])
        pair["metadata"]["members"][1] = pair["metadata"]["members"][0]
        files[path] = json.dumps(pair)
        self.rehash(files, path)
        with self.assertRaisesRegex(ValueError, "pair members"):
            read_publication(files)

    def test_failure_cannot_claim_a_completed_mapping(self):
        files = self.metadata(lambda m: m["outcomes"][-1].update(structuralValidity="checked"))
        with self.assertRaisesRegex(ValueError, "masquerades"):
            read_publication(files)

    def test_completed_unit_summary_must_match_member_tables(self):
        files = self.metadata(lambda m: m["outcomes"][0]["units"][0].update(reference="Failed", reconstruction="Excluded"))
        with self.assertRaisesRegex(ValueError, "unit outcome status"):
            read_publication(files)

    def test_failed_attempt_cannot_invent_units_or_execution(self):
        for field, value in (("units", [{"unit": "invented", "reference": "Failed", "reconstruction": "Failed"}]),
                             ("registration", "0" * 64), ("readiness", "Ready")):
            files = self.metadata(lambda m: m["outcomes"][-1].update({field: value}))
            with self.assertRaises(ValueError):
                read_publication(files)

    def test_prepared_but_failed_attempt_preserves_every_unit(self):
        files = self.files()
        manifest = parse(files["manifest.json"])
        first = manifest["metadata"]["outcomes"][0]
        run_prefix = first["run"] + "--"
        for name in list(files):
            if name.startswith(run_prefix):
                del files[name]
        manifest["files"] = [e for e in manifest["files"] if not e["path"].startswith(run_prefix)]
        first.update(execution="failed", structuralValidity="not-produced", failure="synthetic-failure-control",
                     mapping=None, run=None, registration=None, readiness="not-executed")
        for unit in first["units"]:
            unit.update(reference="Failed", reconstruction="Failed")
        files["manifest.json"] = json.dumps(manifest)
        # Consistent failed-attempt control still accounts for the imported recall's unit.
        self.assertEqual(len(read_publication(files)["pairs"]), 1)
        for replacement in ([], [{**first["units"][0], "reference": "Complete"}]):
            altered = copy.deepcopy(manifest)
            altered["metadata"]["outcomes"][0]["units"] = replacement
            bad = dict(files, **{"manifest.json": json.dumps(altered)})
            with self.assertRaisesRegex(ValueError, "failed unit denominator or status"):
                read_publication(bad)

    def test_duplicate_json_keys_refuse(self):
        files = self.files()
        files["manifest.json"] = files["manifest.json"].replace('{', '{"schemaVersion":"foreign",', 1)
        with self.assertRaisesRegex(ValueError, "duplicate JSON key"):
            read_publication(files)

    def test_annotation_exact_support(self):
        pair = read_publication(self.files("annotation"))["pairs"][0]
        tables = pair["reference"][1]
        self.assertEqual(len(tables["targets"]), 5)
        self.assertEqual(len(tables["units"]), 1)
        tick = 9007199254740993
        supports = {r["target_id"]: r["support_status"] for r in tables["target-support"]}
        group = next(t for t in tables["targets"] if t["level"] == 1)
        self.assertEqual(group["support_coverage"]["status"], "partial")
        self.assertEqual(len(group["support_coverage"]["missing"]), 1)
        self.assertEqual(sum(s["status"] == "unlocated" for s in supports.values()), 1)
        anchors = supports[group["target_id"]]["source_support"]["anchors"]
        intervals = sorted((int(i["start_tick"]), int(i["end_exclusive_tick"]))
                           for a in anchors if a["kind"] == "media-time" for i in a["intervals"])
        points = [int(a["tick"]) for a in anchors if a["kind"] == "media-point"]
        self.assertEqual(intervals, [(tick + 10, tick + 20), (tick + 30, tick + 40)])
        self.assertEqual(points, [tick + 25])
        # Query the exported geometry using exact integer comparisons, including its gap.
        self.assertTrue(any(a <= tick + 10 < b for a, b in intervals))
        self.assertFalse(any(a <= tick + 25 < b for a, b in intervals))
        self.assertFalse(any(a <= tick + 40 < b for a, b in intervals))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: test_paired_mapping.py PAIRED_EXCHANGE_SUITE_LOG")
    for line in Path(sys.argv[1]).read_text().splitlines():
        if line.startswith("PAIRED_EXCHANGE_FIXTURE\t"):
            _, name, content = line.split("\t", 2)
            if name in PairedReaderTests.fixtures:
                raise ValueError("duplicate fixture")
            PairedReaderTests.fixtures[name] = parse(content)
    if set(PairedReaderTests.fixtures) != {"text", "annotation"}:
        raise ValueError("missing fixture")
    unittest.main(argv=[sys.argv[0]], verbosity=2)
