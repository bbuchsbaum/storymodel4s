import copy
import csv
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
MODULE = Path(os.environ.get("RECALL_WORD_CLOCK_MODULE", HERE / "recall_word_clock.py"))
spec = importlib.util.spec_from_file_location("recall_word_clock", MODULE)
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)
FIXTURE = HERE / "fixtures/word-clock-join"
PRODUCER_SHA = "acea0831a78b03f5b7cfa548e81f037aa997d37dbdae8b6cdfd4b24ac82f59c7"


def encoded(value):
    return json.dumps(value, ensure_ascii=True).encode()


class WordClockSuite(unittest.TestCase):
    def setUp(self):
        self.csv = (FIXTURE / "words.csv").read_bytes()
        self.lineage = (FIXTURE / "lineage.json").read_bytes()
        self.mapping = (FIXTURE / "g1-historical.json").read_bytes()
        self.expected = json.loads((FIXTURE / "expected.json").read_text())

    def call(self, selection="openneuro", *, csv_bytes=None, lineage=None, mapping=None, pin=None, recipe=reader.RECIPE):
        wire = self.mapping if mapping is None else mapping
        return reader.join(self.csv if csv_bytes is None else csv_bytes,
                           self.lineage if lineage is None else lineage, selection,
                           wire, hashlib.sha256(wire).hexdigest() if pin is None else pin, recipe=recipe)

    def joined(self, **kwargs):
        try:
            return self.call(**kwargs)
        except reader.clock.IntakeError as error:
            self.fail("valid authored join refused: " + str(error.detail))

    def refuses(self, code, **kwargs):
        with self.assertRaises(reader.clock.IntakeError) as error:
            self.call(**kwargs)
        self.assertEqual(error.exception.detail["code"], code)

    def changed_rows(self, change):
        rows = list(csv.reader(io.StringIO(self.csv.decode(), newline="")))
        change(rows)
        stream = io.StringIO(newline="")
        csv.writer(stream, lineterminator="\n").writerows(rows)
        data = stream.getvalue().encode()
        lineage = json.loads(self.lineage)
        lineage["sources"][0].update(localCsvSha256=hashlib.sha256(data).hexdigest(),
                                      localCsvByteLength=len(data), recordCount=len(rows) - 1,
                                      numericMissingCellCount=sum(not x.strip() for row in rows[1:] for x in row[1:]))
        return {"csv_bytes": data, "lineage": encoded(lineage)}

    def changed_mapping(self, change):
        wire = json.loads(self.mapping)
        change(wire)
        return encoded(wire)

    def unicode_join(self):
        return self.joined(csv_bytes=(FIXTURE / "unicode.csv").read_bytes(),
                           lineage=(FIXTURE / "unicode-lineage.json").read_bytes(),
                           mapping=(FIXTURE / "unicode-inventory-envelope.json").read_bytes())

    def test_actual_g1_producer_bytes_and_digests(self):
        self.assertEqual(hashlib.sha256(self.mapping).hexdigest(), PRODUCER_SHA)
        result = self.joined(pin=PRODUCER_SHA)
        self.assertEqual(result["binding"]["inventoryDigest"], "1a4915caa8d45c063ec7d5bcd452e0e684eae24ea08056335a82545028fe71b3")
        self.assertEqual(result["binding"]["segmentationId"], "4ee23d2170137829779e39ef9d83f60246d1189adfad082310c0a584be29b8c7")
        self.assertEqual(result["counts"], self.expected["counts"])

    def test_clock_switch_changes_timing_not_inventory_identity(self):
        a, b = self.joined(), self.joined(selection="princeton")
        self.assertEqual(a["binding"], b["binding"])
        for result, clock in [(a, "openneuro"), (b, "princeton")]:
            self.assertEqual([w["onset"].get("decimal") for w in result["words"]], self.expected[clock])
        self.assertEqual([w["wordId"] for w in a["words"]], [w["wordId"] for w in b["words"]])

    def test_reunitization_preserves_words_but_changes_segmentation_binding(self):
        a = self.joined()
        b = self.joined(mapping=(FIXTURE / "regrouped-inventory-envelope.json").read_bytes())
        self.assertEqual([w["wordId"] for w in a["words"]], [w["wordId"] for w in b["words"]])
        self.assertEqual([w["onset"] for w in a["words"]], [w["onset"] for w in b["words"]])
        self.assertNotEqual(a["binding"]["segmentationId"], b["binding"]["segmentationId"])
        self.assertNotEqual(a["binding"]["inventoryDigest"], b["binding"]["inventoryDigest"])
        self.assertEqual(len(b["units"]), 1)
        self.assertEqual(b["units"][0]["wordIds"], [w["wordId"] for w in a["words"]])

    def test_empty_record_is_retained_without_shifting_word_indices(self):
        result = self.joined()
        self.assertEqual([w["record"] for w in result["words"]], [1, 3, 4, 5])
        self.assertEqual([r["record"] for r in result["records"]], [1, 2, 3, 4, 5])
        empty = result["records"][1]
        self.assertEqual(empty["correspondence"], {"status": "excluded", "reason": "empty-after-java-trim"})
        self.assertEqual(empty["selectedOnset"]["decimal"], "8.5")
        self.assertEqual(result["words"][1]["onset"]["decimal"], "8.75")

    def test_missing_boundary_is_not_a_measured_unit_start(self):
        unit = self.joined()["units"][0]
        self.assertEqual(unit["coverage"], {"status": "partial", "totalWords": 2, "observedOnsets": 1, "missingOnsets": 1})
        self.assertEqual(unit["firstMember"]["onset"]["status"], "missing")
        self.assertEqual(unit["firstMember"]["record"], 1)
        self.assertEqual(unit["firstMeasuredOnset"]["record"], 3)
        self.assertEqual(unit["firstMeasuredOnset"]["onset"]["decimal"], "8.75")
        self.assertEqual(unit["onsetOrder"]["unobservedPairs"], 1)

    def test_backward_and_equal_onsets_are_diagnostics_not_repaired(self):
        unit = self.joined()["units"][1]
        self.assertEqual(unit["firstMeasuredOnset"]["onset"]["decimal"], "10")
        self.assertEqual(unit["lastMeasuredOnset"]["onset"]["decimal"], "9")
        self.assertEqual(len(unit["onsetOrder"]["backwardPairs"]), 1)
        equal = self.joined(**self.changed_rows(lambda rows: rows[5].__setitem__(4, "10")))
        self.assertEqual(equal["units"][1]["onsetOrder"]["equalPairs"], 1)
        self.assertEqual(equal["units"][1]["onsetOrder"]["backwardPairs"], [])

    def test_missing_all_onsets_and_no_words_are_distinct(self):
        missing = self.joined(**self.changed_rows(lambda rows: rows[3].__setitem__(4, "")))["units"][0]
        empty = self.unicode_join()["units"][2]
        self.assertEqual(missing["firstMeasuredOnset"]["reason"], "no-measured-member-onsets")
        self.assertEqual(empty["firstMeasuredOnset"]["reason"], "no-member-words")
        self.assertEqual(missing["coverage"]["missingOnsets"], 2)
        self.assertEqual(empty["coverage"]["missingOnsets"], 0)

    def test_utf16_nonbmp_ids_repeated_words_and_quoted_newline(self):
        result = self.unicode_join()
        self.assertEqual(result["binding"]["inventoryDigest"], "ac32bb362e5d392f875c4615da10e5d1a755c511aedbc85f2c42e3b1fad24dbb")
        self.assertEqual([(w["startUtf16"], w["endExclusiveUtf16"]) for w in result["words"]],
                         [(0, 2), (3, 7), (8, 12), (13, 16), (17, 18)])
        self.assertEqual([w["record"] for w in result["words"]], [1, 3, 4, 5, 6])
        self.assertNotEqual(result["words"][1]["wordId"], result["words"][2]["wordId"])
        self.assertEqual(result["words"][1]["onset"]["decimal"], "3")
        self.assertEqual(result["words"][2]["onset"]["decimal"], "4")
        # NBSP is retained by Java trim, unlike Python str.strip().
        self.assertEqual(result["counts"]["words"], 5)

    def test_membership_uses_components_and_overlap_not_hull_or_containment(self):
        result = self.unicode_join()
        self.assertEqual(result["counts"]["unassignedWords"], 2)
        self.assertEqual(result["words"][1]["membership"]["status"], "unassigned")
        self.assertEqual(result["words"][3]["membership"], {"status": "member", "unit": "partial"})
        self.assertEqual(result["units"][0]["wordIds"], [result["words"][0]["wordId"], result["words"][2]["wordId"]])

    def test_interior_empty_span_cannot_own_a_word(self):
        result = self.unicode_join()
        self.assertEqual(result["units"][2]["wordIds"], [])
        self.assertEqual(result["words"][1]["membership"]["status"], "unassigned")
        wire = json.loads((FIXTURE / "unicode-inventory-envelope.json").read_bytes())
        wire["inventory"]["words"][1]["membership"] = {"status": "member", "unit": "empty"}
        wire["inventory"]["units"][2]["words"] = [wire["inventory"]["words"][1]["id"]]
        self.refuses("word-membership-mismatch", mapping=encoded(wire),
                     csv_bytes=(FIXTURE / "unicode.csv").read_bytes(),
                     lineage=(FIXTURE / "unicode-lineage.json").read_bytes())

    def test_identifier_rules_match_g1_utf16_and_java_whitespace(self):
        for bad in ["u 0", "u\t0", "u\x000", "u\x850", "u\u16800", "u\u20080", "u\u30000", "x" * 257, "x" + "😀" * 128]:
            for target in ["unit", "surface"]:
                def change(wire):
                    if target == "unit":
                        wire["inventory"]["units"][0]["id"] = bad
                    else:
                        wire["inventory"]["units"][0]["spans"][0]["unit"]["value"] = bad
                self.refuses("invalid-identifier", mapping=self.changed_mapping(change))
        for valid in ["😀" * 128, "u\u00a00", "u\u20070", "u\u202f0", "u\u200b0", "u\ud800"]:
            reader.identifier(valid)

    def test_identical_text_different_recording_clocks_keep_distinct_provenance(self):
        a = self.joined()
        b = self.joined(**self.changed_rows(lambda rows: rows[4].__setitem__(4, "999")))
        self.assertEqual(a["binding"]["inventoryDigest"], b["binding"]["inventoryDigest"])
        self.assertNotEqual(a["binding"]["clockSource"]["csvSha256"], b["binding"]["clockSource"]["csvSha256"])
        self.assertNotEqual(a["words"][2]["onset"], b["words"][2]["onset"])
        for result in [a, b]:
            self.assertEqual(result["validation"]["recordingIdentityBinding"], "not-verified-by-this-reader")
            self.assertFalse(result["validation"]["sameInputArtifact"])

    def test_no_mapping_context_scanner_duration_or_interpolation_authority(self):
        result = self.joined()
        self.assertEqual(result["validation"]["fullMappingContextValidation"], "not-performed")
        self.assertTrue(all(v["status"] == "unavailable" for v in result["capabilities"].values()))
        self.assertTrue(all(u["duration"]["status"] == "unavailable" for u in result["units"]))
        encoded_result = json.dumps(result)
        for text in ["Anna", "arrived.", "Bob", "left."]:
            self.assertNotIn(text, encoded_result)
        self.assertEqual(result, self.joined())

    def test_explicit_recipe_and_external_mapping_pin_are_required(self):
        self.refuses("unsupported-replay-recipe", recipe="guess")
        self.refuses("mapping-file-digest-mismatch", pin="0" * 64)
        self.refuses("mapping-file-digest-mismatch", mapping=b'{"prose":"private"}', pin=PRODUCER_SHA)

    def test_input_shape_and_unknown_schema_refuse(self):
        for schema in [None, [], {"schema": "wrong", "schemaVersion": "mapping-record/v0.1"}]:
            self.refuses("unsupported-mapping-schema", mapping=encoded(schema))
        for key, code in [("inventory", "invalid-inventory"), ("record_digest", "invalid-checksum")]:
            self.refuses(code, mapping=self.changed_mapping(lambda x: x.__setitem__(key, None)))

    def test_transcript_and_word_policy_mismatch_refuse(self):
        self.refuses("transcript-mismatch", **self.changed_rows(lambda rows: rows[1].__setitem__(0, "Anne")))
        self.refuses("unsupported-word-policy", mapping=self.changed_mapping(
            lambda x: x["inventory"]["word_id_policy"].__setitem__("name", "by-word-text")))

    def test_canonicalization_that_moves_characters_requires_a_map(self):
        self.refuses("replay-needs-character-map", **self.changed_rows(lambda rows: rows[1].__setitem__(0, "A\r\nB")))
        self.refuses("replay-needs-character-map", **self.changed_rows(lambda rows: rows[1].__setitem__(0, "A \nB")))

    def test_merges_splits_and_changed_spans_refuse(self):
        def merge(rows):
            rows[1][0] = "Anna arrived."
            rows[3][0] = ""
        self.refuses("word-record-bijection-mismatch", **self.changed_rows(merge))
        def split(rows):
            rows[3][0] = "arriv"
            rows.insert(4, ["ed.", "2", "2", "2", "3", "3", "3"])
        self.refuses("transcript-mismatch", **self.changed_rows(split))
        self.refuses("word-span-replay-mismatch", mapping=self.changed_mapping(
            lambda x: x["inventory"]["words"][0].__setitem__("end_exclusive", 3)))

    def test_inventory_and_segmentation_digests_are_independently_checked(self):
        for key, code in [("digest", "inventory-digest-mismatch"), ("segmentation_id", "segmentation-digest-mismatch")]:
            self.refuses(code, mapping=self.changed_mapping(lambda x: x["inventory"].__setitem__(key, "0" * 64)))

    def test_word_ids_and_indices_are_not_csv_records_or_caller_assertions(self):
        for key, value, code in [("id", "word-1", "word-id-mismatch"),
                                 ("index", 1, "invalid-word-order"), ("index", False, "invalid-word-order")]:
            self.refuses(code, mapping=self.changed_mapping(lambda x: x["inventory"]["words"][0].__setitem__(key, value)))

    def test_membership_must_be_reciprocal_and_unambiguous(self):
        self.refuses("word-membership-mismatch", mapping=self.changed_mapping(
            lambda x: x["inventory"]["words"][0].__setitem__("membership", {"status": "member", "unit": "u1"})))
        self.refuses("unit-membership-mismatch", mapping=self.changed_mapping(
            lambda x: x["inventory"]["units"][0].__setitem__("words", [])))
        self.refuses("ambiguous-word-membership", mapping=self.changed_mapping(
            lambda x: x["inventory"]["units"][1].__setitem__("spans", copy.deepcopy(x["inventory"]["units"][0]["spans"]))))

    def test_unit_order_duplicate_unknown_fields_and_bad_spans_refuse(self):
        mutations = [
            (lambda x: x["inventory"]["units"][1].__setitem__("id", "u0"), "duplicate-unit"),
            (lambda x: x["inventory"]["units"][0].__setitem__("ordinal", False), "invalid-unit-order"),
            (lambda x: x["inventory"]["units"][0]["spans"][0].__setitem__("start", -1), "invalid-utf16-span"),
            (lambda x: x["inventory"]["units"][0]["spans"].append(copy.deepcopy(x["inventory"]["units"][0]["spans"][0])), "noncanonical-unit-spans"),
            (lambda x: x["inventory"].__setitem__("unknown", None), "invalid-inventory")]
        for change, code in mutations:
            self.refuses(code, mapping=self.changed_mapping(change))

    def test_duplicate_json_keys_and_malformed_json_refuse(self):
        self.refuses("duplicate-json-key", mapping=b'{"schema":1,"schema":2}')
        self.refuses("invalid-json", mapping=b'{"private text"')

    def test_oversized_integer_refuses_privately_without_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = b'{"PRIVATE_SENTINEL":' + b'9' * 5000 + b'}'
            source = root / "mapping.json"
            source.write_bytes(payload)
            out = root / "result.json"
            result = subprocess.run([sys.executable, str(MODULE), "--csv", str(FIXTURE / "words.csv"),
                                     "--lineage", str(FIXTURE / "lineage.json"), "--clock", "openneuro",
                                     "--mapping", str(source), "--mapping-sha256", hashlib.sha256(payload).hexdigest(),
                                     "--recipe", reader.RECIPE, "--out", str(out)],
                                    capture_output=True, env={**os.environ, "PYTHONPATH": str(HERE)})
            self.assertEqual(result.returncode, 2)
            self.assertEqual(json.loads(result.stderr), {"error": {"code": "json-integer-representation-limit"}})
            self.assertEqual(result.stdout, b"")
            self.assertNotIn(b"PRIVATE_SENTINEL", result.stderr)
            self.assertFalse(out.exists())

    def test_cli_publishes_pinned_producer_join_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "result.json"
            args = [sys.executable, str(MODULE), "--csv", str(FIXTURE / "words.csv"),
                    "--lineage", str(FIXTURE / "lineage.json"), "--clock", "openneuro",
                    "--mapping", str(FIXTURE / "g1-historical.json"), "--mapping-sha256", PRODUCER_SHA,
                    "--recipe", reader.RECIPE, "--out", str(out)]
            env = {**os.environ, "PYTHONPATH": str(HERE)}
            first = subprocess.run(args, capture_output=True, env=env)
            self.assertEqual(first.returncode, 0, first.stderr)
            data = out.read_bytes()
            self.assertEqual(json.loads(first.stdout)["outputSha256"], hashlib.sha256(data).hexdigest())
            producer = json.loads(data)["producer"]
            self.assertEqual(producer["readerSha256"], hashlib.sha256(MODULE.read_bytes()).hexdigest())
            again = subprocess.run(args, capture_output=True, env=env)
            self.assertEqual(again.returncode, 2)
            self.assertEqual(json.loads(again.stderr)["error"]["code"], "output-exists")
            self.assertEqual(out.read_bytes(), data)
            self.assertEqual(len(list(Path(directory).iterdir())), 1)


if __name__ == "__main__":
    unittest.main()
