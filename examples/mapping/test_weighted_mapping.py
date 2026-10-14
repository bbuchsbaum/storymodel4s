#!/usr/bin/env python3
"""Independent weighted-sweep controls over actual public producer output."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import struct
import unittest
from read_weighted_mapping import fraction, read_sweep, selected
from read_mapping import number
from check_corruptions import repack


class WeightedReaderTests(unittest.TestCase):
    fixtures = {}

    def test_actual_sweep_preserves_endpoint_bytes_and_attempts(self):
        for name, files in self.fixtures.items():
            base, members = read_sweep(files)
            self.assertEqual(len(base["outcomes"]), 3 if name == "text" else 1)
            self.assertEqual(len(members), 6 if name == "text" else 3)
            self.assertEqual([m[0]["lambda"] for m in members], ["0/1", "1/2", "1/1"] * (2 if name == "text" else 1))
            if name == "text":
                self.assertEqual([o["execution"] for o in base["outcomes"]], ["complete", "complete", "failed"])

    def test_fraction_identity_never_uses_floating_endpoint_dispatch(self):
        self.assertEqual(fraction("9223372036854775806/9223372036854775807"), (9223372036854775806, 9223372036854775807))
        for value in ("NaN", "1.0", "2/4", "2/1", "0/2", "1/0", "01/2", "1/9223372036854775808"):
            with self.assertRaises(ValueError):
                fraction(value)

    def test_missing_or_duplicate_sweep_member_refuses(self):
        for change in (lambda m: m["members"].pop(), lambda m: m["members"].append(m["members"][0])):
            files = copy.deepcopy(self.fixtures["text"])
            manifest = json.loads(files["manifest.json"])
            change(manifest["metadata"])
            files["manifest.json"] = json.dumps(manifest)
            with self.assertRaisesRegex(ValueError, "weighted attempt denominator"):
                read_sweep(files)

    def test_changed_fraction_requires_new_bound_member(self):
        files = copy.deepcopy(self.fixtures["text"])
        manifest = json.loads(files["manifest.json"])
        member = manifest["metadata"]["members"][1]
        path = member["prefix"] + "manifest.json"
        body = json.loads(files[path])
        body["metadata"]["lambda"] = "1/3"
        files[path] = json.dumps(body)
        member["manifest"] = hashlib.sha256(files[path].encode()).hexdigest()
        descriptor = next(e for e in manifest["files"] if e["path"] == path)
        descriptor["sha256"] = member["manifest"]
        files["manifest.json"] = json.dumps(manifest)
        with self.assertRaisesRegex(ValueError, "weighted member table"):
            read_sweep(files)

    def interior(self):
        files = self.fixtures["text"]
        prefix = json.loads(files["manifest.json"])["metadata"]["members"][1]["prefix"]
        return {name[len(prefix):]: value for name, value in files.items() if name.startswith(prefix)}

    def change_record(self, change):
        files = self.interior()
        payload = {name[len("record--"):]: value for name, value in files.items() if name.startswith("record--")}
        record = json.loads(payload["mapping.json"])
        change(record)
        files.update({"record--" + name: value for name, value in repack(payload, record).items()})
        manifest = json.loads(files["manifest.json"])
        for entry in manifest["files"]:
            raw = files[entry["path"]].encode()
            entry["sha256"] = hashlib.sha256(raw).hexdigest()
            if "bytes" in entry:
                entry["bytes"] = str(len(raw))
        files["manifest.json"] = json.dumps(manifest)
        return files

    def test_interior_positive_control(self):
        self.assertEqual(selected(self.interior())[0]["measure_kind"], "ModelPosterior")

    def test_coherently_rehashed_wrong_measure_kind_refuses(self):
        def change(record):
            for outcome in record["outcomes"]:
                for row in outcome["mapping_links"]:
                    if row["measure_kind"] == "ModelPosterior":
                        row["measure_kind"] = "NormalizedScoreMass"
        with self.assertRaisesRegex(ValueError, "conflated weighted measure kind"):
            selected(self.change_record(change))

    def test_coherently_rehashed_unnormalized_mass_refuses(self):
        def change(record):
            for outcome in record["outcomes"]:
                for row in outcome["mapping_links"]:
                    if row["measure_kind"] == "ModelPosterior":
                        row["raw_value"] = "0x0000000000000000"
        with self.assertRaisesRegex(ValueError, "invalid weighted mass"):
            selected(self.change_record(change))

    def shifted_mass(self, difference):
        def change(record):
            for outcome in record["outcomes"]:
                rows = [r for r in outcome["mapping_links"] if r["measure_kind"] == "ModelPosterior"]
                if rows:
                    row = max(rows, key=lambda r: number(r["raw_value"]))
                    row["raw_value"] = "0x" + struct.pack(">d", number(row["raw_value"]) + difference).hex()
        return self.change_record(change)

    def test_contract_row_sum_roundoff_is_preserved_without_rescaling(self):
        for difference in [-5e-10, 5e-10]:
            files = self.shifted_mass(difference)
            supplied = json.loads(files["record--mapping.json"])
            self.assertEqual(selected(files)[1], supplied)

    def test_mass_outside_contract_tolerance_still_refuses(self):
        for difference in [-2e-9, 2e-9]:
            with self.assertRaisesRegex(ValueError, "invalid weighted mass"):
                selected(self.shifted_mass(difference))

    def test_removed_posterior_alternatives_refuse(self):
        def change(record):
            for outcome in record["outcomes"]:
                outcome["mapping_links"] = [r for r in outcome["mapping_links"] if r["measure_kind"] != "ModelPosterior"]
        with self.assertRaisesRegex(ValueError, "weighted measure alternatives presence"):
            selected(self.change_record(change))

    def test_absent_posterior_metadata_with_alternatives_refuses(self):
        def change(record):
            for outcome in record["outcomes"]:
                outcome["measures"]["posterior"] = {"status": "absent", "reason": "not-supplied"}
        with self.assertRaisesRegex(ValueError, "weighted measure alternatives presence"):
            selected(self.change_record(change))

    def test_coherently_rehashed_wrong_decision_basis_refuses(self):
        def change(record):
            for outcome in record["outcomes"]:
                if outcome["decision"]["status"] == "present":
                    outcome["decision"]["value"]["basis"]["measure_kind"] = "RawScore"
        with self.assertRaisesRegex(ValueError, "weighted decision basis"):
            selected(self.change_record(change))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: test_weighted_mapping.py SCALA_TEST_LOG")
    for line in Path(sys.argv[1]).read_text().splitlines():
        if line.startswith("WEIGHTED_MAPPING_FIXTURE\t"):
            _, name, value = line.split("\t", 2)
            if name in WeightedReaderTests.fixtures:
                raise ValueError("duplicate fixture")
            WeightedReaderTests.fixtures[name] = json.loads(value)
    if set(WeightedReaderTests.fixtures) != {"text", "multipart"}:
        raise ValueError("missing weighted fixture")
    unittest.main(argv=[sys.argv[0]], verbosity=2)
