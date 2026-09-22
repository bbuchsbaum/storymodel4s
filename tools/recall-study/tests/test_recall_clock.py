"""Independent timestamp and identity witnesses; original synthetic CSV only."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parents[1]
MODULE = Path(os.environ.get("RECALL_CLOCK_MODULE", HERE / "recall_clock.py"))
spec = importlib.util.spec_from_file_location("recall_clock", MODULE)
clock = importlib.util.module_from_spec(spec)
spec.loader.exec_module(clock)
FIXTURE = HERE / "fixtures/recall-clock"


class RecallClockSuite(unittest.TestCase):
    def setUp(self):
        self.data = (FIXTURE / "words.csv").read_bytes()
        self.lineage = json.loads((FIXTURE / "lineage.json").read_bytes())
        self.aliases = json.loads((FIXTURE / "aliases.json").read_bytes())
        self.expected = json.loads((FIXTURE / "expected.json").read_bytes())

    def run_intake(self, selection="openneuro", *, data=None, lineage=None, **kwargs):
        return clock.intake(self.data if data is None else data,
                            json.dumps(self.lineage if lineage is None else lineage).encode(),
                            selection, **kwargs)

    def changed_csv(self, data, records=3, missing=1):
        lineage = copy.deepcopy(self.lineage)
        source = next(s for s in lineage["sources"] if s["id"] == "recall-source-06")
        source.update(localCsvSha256=hashlib.sha256(data).hexdigest(),
                      localCsvByteLength=len(data), recordCount=records,
                      numericMissingCellCount=missing)
        return lineage

    def refuses(self, code, action):
        with self.assertRaises(clock.IntakeError) as ctx:
            action()
        self.assertEqual(ctx.exception.detail["code"], code)
        return ctx.exception.detail

    def test_selected_clock_uses_released_column_not_added_offset(self):
        result = self.run_intake()
        self.assertEqual([r["selectedOnset"] for r in result["records"]], self.expected["openneuro"])
        self.assertEqual(result["clock"]["appliedOffset"], "0")
        # A non-constant decimal discrepancy must remain visible rather than repaired.
        self.assertEqual(result["offsetDiagnostics"]["wordSeconds"], self.expected["wordDifferences"])

    def test_princeton_selection_keeps_missing_distinct_from_zero(self):
        result = self.run_intake("princeton")
        self.assertEqual([r["selectedOnset"] for r in result["records"]], self.expected["princeton"])
        self.assertEqual(result["counts"]["missingSelectedOnsets"], 1)

    def test_every_record_survives_quoted_csv_and_empty_word(self):
        result = self.run_intake()
        self.assertEqual([r["record"] for r in result["records"]], [1, 2, 3])
        self.assertEqual(result["counts"], {"records": 3, "emptyTextRecords": 1,
                                          "missingNumericCells": 1, "missingSelectedOnsets": 0})
        self.assertEqual(result["records"][1]["textPresence"], "empty")
        self.assertEqual(result["recordCoordinates"]["kind"], "csv-data-record-not-canonical-word-id")

    def test_clock_changes_do_not_change_source_or_record_identity(self):
        a, b = self.run_intake("princeton"), self.run_intake("openneuro")
        self.assertEqual(a["identity"], b["identity"])
        self.assertEqual([r["record"] for r in a["records"]], [r["record"] for r in b["records"]])

    def test_alias_five_is_source_six_by_digest(self):
        result = self.run_intake(alias_id="recall-alias-05", alias_bytes=json.dumps(self.aliases).encode())
        self.assertEqual(result["identity"]["sourceId"], "recall-source-06")
        self.assertEqual(result["identity"]["alias"]["sourceId"], "recall-source-06")
        self.refuses("source-identity-mismatch", lambda: self.run_intake(expected_source="recall-source-05"))

    def test_omitted_source_does_not_acquire_an_alias(self):
        data = (FIXTURE / "omitted.csv").read_bytes()
        result = self.run_intake(data=data)
        self.assertEqual(result["identity"]["sourceId"], "recall-source-05")
        self.refuses("alias-source-mismatch", lambda: self.run_intake(
            data=data, alias_id="recall-alias-05", alias_bytes=json.dumps(self.aliases).encode()))

    def test_foreign_or_inconsistent_alias_map_refuses(self):
        for key, value, code in [("sourceSetId", "foreign", "foreign-alias-set"),
                                 ("aliasCount", True, "alias-count-mismatch"),
                                 ("omittedSources", [], "alias-omission-mismatch")]:
            with self.subTest(key=key):
                aliases = copy.deepcopy(self.aliases); aliases[key] = value
                self.refuses(code, lambda: self.run_intake(alias_id="recall-alias-05", alias_bytes=json.dumps(aliases).encode()))
        aliases = copy.deepcopy(self.aliases)
        aliases["mappings"][0]["sha256"] = "0" * 64
        self.refuses("alias-digest-mismatch", lambda: self.run_intake(alias_id="recall-alias-05", alias_bytes=json.dumps(aliases).encode()))

    def test_unknown_bytes_refuse_before_csv_parse(self):
        changed = self.data.replace(b"7.5,6,7.5", b"7.6,6,7.5")
        self.assertEqual(len(changed), len(self.data))
        self.refuses("csv-not-in-declared-lineage", lambda: self.run_intake(data=changed))

    def test_duplicate_source_or_digest_refuses(self):
        for field in ("id", "localCsvSha256"):
            with self.subTest(field=field):
                lineage = copy.deepcopy(self.lineage)
                lineage["sources"][1][field] = lineage["sources"][0][field]
                self.refuses("ambiguous-source-identity", lambda: self.run_intake(lineage=lineage))

    def test_unknown_clock_and_column_contract_refuse(self):
        self.refuses("clock-required", lambda: self.run_intake("auto"))
        self.refuses("clock-required", lambda: self.run_intake(None))
        lineage = copy.deepcopy(self.lineage)
        lineage["columns"][1], lineage["columns"][4] = lineage["columns"][4], lineage["columns"][1]
        self.refuses("unsupported-column-contract", lambda: self.run_intake(lineage=lineage))

    def test_declared_counts_are_checked(self):
        for field, code in [("recordCount", "record-count-mismatch"),
                            ("numericMissingCellCount", "missing-count-mismatch"),
                            ("localCsvByteLength", "csv-length-mismatch")]:
            with self.subTest(field=field):
                lineage = copy.deepcopy(self.lineage)
                lineage["sources"][1][field] += 1
                self.refuses(code, lambda: self.run_intake(lineage=lineage))

    def test_nonfinite_malformed_and_fractional_tr_refuse_without_cell_text(self):
        for replacement in (b"NaN", b"Infinity", b"SECRET_NUMERIC_CELL"):
            data = self.data.replace(b"8.750000000000001", replacement)
            detail = self.refuses("invalid-numeric-cell", lambda: self.run_intake(data=data, lineage=self.changed_csv(data)))
            self.assertEqual(detail, {"code": "invalid-numeric-cell", "record": 2,
                                      "column": "openneuro-word-onset-seconds"})
            self.assertNotIn(replacement.decode(), json.dumps(detail))
        data = self.data.replace(b"0,1,0,7.5", b"0,1.5,0,7.5")
        self.refuses("fractional-tr-number", lambda: self.run_intake(data=data, lineage=self.changed_csv(data)))

    def test_large_exact_and_negative_onsets_are_preserved(self):
        data = self.data.replace(b"7.5,6,7.5", b"9007199254740993.125,6,-1.5")
        result = self.run_intake(data=data, lineage=self.changed_csv(data))
        self.assertEqual(result["records"][0]["selectedOnset"]["decimal"], "9007199254740993.125")
        self.assertEqual(result["records"][0]["values"]["openneuro-tr-onset-seconds"]["decimal"], "-1.5")
        self.assertIn(b'"9007199254740993.125"', clock.encode(result))

    def test_extreme_numbers_return_private_structured_cli_refusals(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for replacement in ("1e999999999999999999999", "1e5000", "1e-5000", "9" * 129, "0" * 513):
                with self.subTest(size=len(replacement)):
                    data = self.data.replace(b"8.750000000000001", replacement.encode())
                    (root / "input.csv").write_bytes(data)
                    (root / "lineage.json").write_text(json.dumps(self.changed_csv(data)))
                    result = subprocess.run([sys.executable, str(MODULE), "--csv", str(root / "input.csv"),
                                             "--lineage", str(root / "lineage.json"), "--clock", "openneuro",
                                             "--out", str(root / "output.json")], capture_output=True, text=True)
                    self.assertEqual(result.returncode, 2, result.stderr)
                    self.assertEqual(json.loads(result.stderr), {"error": {"code": "numeric-representation-limit",
                                     "record": 2, "column": "openneuro-word-onset-seconds"}})
                    self.assertFalse((root / "output.json").exists())
                    self.assertNotIn(replacement, result.stdout + result.stderr)

    def test_supported_numeric_limits_keep_exact_diagnostics(self):
        data = self.data.replace(b"7.5,6,7.5", (("9" * 128) + "e128,6,7.5").encode())
        data = data.replace(b"8.750000000000001", ("0." + "0" * 126 + "1e-128").encode())
        result = self.run_intake(data=data, lineage=self.changed_csv(data))
        self.assertEqual(result["records"][0]["selectedOnset"]["decimal"], "9" * 128 + "e128")
        json.loads(clock.encode(result))

    def test_no_duration_scanner_join_or_text_is_fabricated(self):
        result = self.run_intake()
        for capability in ("wordOffsets", "scannerAlignment", "canonicalWordJoin"):
            self.assertEqual(result["capabilities"][capability]["status"], "unavailable")
        encoded = clock.encode(result)
        self.assertNotIn(b"alpha,beta", encoded)
        self.assertNotIn(b"line\\nbreak", encoded)
        self.assertEqual(encoded, clock.encode(self.run_intake()))

    def test_duplicate_json_keys_and_versions_refuse(self):
        self.refuses("duplicate-json-key", lambda: clock.decode_json(b'{"a":1,"a":2}'))
        for version in (True, 2):
            lineage = copy.deepcopy(self.lineage); lineage["schemaVersion"] = version
            self.refuses("unsupported-lineage", lambda: self.run_intake(lineage=lineage))

    def test_malformed_csv_and_column_count_refuse(self):
        data = self.data + b'"unterminated'
        self.refuses("invalid-csv", lambda: self.run_intake(data=data, lineage=self.changed_csv(data)))
        data = self.data.replace(b"0,1,0,7.5,6,7.5", b"0,1,0,7.5,6")
        self.refuses("invalid-column-count", lambda: self.run_intake(data=data, lineage=self.changed_csv(data)))

    def test_empty_pair_diagnostic_is_unavailable(self):
        data = b"Words,pw,pn,pt,ow,on,ot\nplaceholder,,,,,,\n"
        result = self.run_intake(data=data, lineage=self.changed_csv(data, records=1, missing=6))
        self.assertEqual(result["offsetDiagnostics"]["wordSeconds"]["range"],
                         {"status": "unavailable", "reason": "no-paired-values"})

    def test_publication_is_complete_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "intake.json"
            payload = clock.encode(self.run_intake())
            clock.publish(target, payload)
            self.assertEqual(target.read_bytes(), payload)
            self.refuses("output-exists", lambda: clock.publish(target, b"changed"))
            self.assertEqual(target.read_bytes(), payload)
            self.assertEqual(list(Path(directory).iterdir()), [target])

    def test_publication_failure_leaves_no_final_or_scratch_file(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(clock.os, "link", side_effect=OSError("synthetic failure")):
                with self.assertRaises(OSError):
                    clock.publish(Path(directory) / "out.json", b"complete")
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_cli_requires_clock_and_publishes_without_prose(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "output.json"
            args = [sys.executable, str(MODULE), "--csv", str(FIXTURE / "words.csv"),
                    "--lineage", str(FIXTURE / "lineage.json"), "--out", str(target)]
            absent = subprocess.run(args, capture_output=True, text=True)
            self.assertEqual(absent.returncode, 2)
            self.assertFalse(target.exists())
            result = subprocess.run(args + ["--clock", "openneuro"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout)["sourceId"], "recall-source-06")
            self.assertNotIn("alpha,beta", result.stdout + result.stderr + target.read_text())
            before = target.read_bytes()
            repeat = subprocess.run(args + ["--clock", "princeton"], capture_output=True, text=True)
            self.assertEqual(repeat.returncode, 2)
            self.assertEqual(json.loads(repeat.stderr)["error"]["code"], "output-exists")
            self.assertEqual(target.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
