#!/usr/bin/env python3
"""Independent weighted-sweep controls over actual public producer output."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import unittest
from read_weighted_mapping import fraction, read_sweep


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
