#!/usr/bin/env python3
"""Adversarial readback of Scala's emitted reference-only facade publication."""
import copy
import json
from pathlib import Path
import sys
import unittest
from read_mapping import parse, read_publication
from read_reference_mapping import read_reference


class ReferenceReaderTests(unittest.TestCase):
    fixture = None

    def files(self):
        return copy.deepcopy(self.fixture)

    def metadata(self, change):
        files = self.files()
        manifest = parse(files["manifest.json"])
        change(manifest["metadata"])
        files["manifest.json"] = json.dumps(manifest)
        return files

    def test_actual_reference_publication(self):
        self.assertEqual(len(read_reference(self.files())), 1)

    def test_legacy_paired_reader_refuses(self):
        with self.assertRaises(ValueError):
            read_publication(self.files())

    def test_corruption(self):
        files = self.files()
        name = next(n for n in files if n.endswith("--mapping.json"))
        files[name] += " "
        with self.assertRaises(ValueError):
            read_reference(files)

    def test_missing_completion_marker(self):
        files = self.files()
        del files["manifest.json"]
        with self.assertRaises(ValueError):
            read_reference(files)

    def test_foreign_execution(self):
        files = self.metadata(lambda m: m["outcomes"][0].update(execution="0" * 64))
        with self.assertRaises(ValueError):
            read_reference(files)

    def test_dropped_attempt(self):
        files = self.metadata(lambda m: m["summary"]["coverage"]["observations"].clear())
        with self.assertRaises(ValueError):
            read_reference(files)

    def test_foreign_source(self):
        files = self.metadata(lambda m: m.update(sourceRepresentation="0" * 64))
        with self.assertRaises(ValueError):
            read_reference(files)

    def test_incorrect_temperature(self):
        files = self.metadata(lambda m: m["summary"]["models"][0]["reference"].update(temperature=2.0))
        with self.assertRaises(ValueError):
            read_reference(files)

    def test_extra_reconstruction_payload(self):
        files = self.files()
        files["reconstruction.json"] = "{}"
        with self.assertRaises(ValueError):
            read_reference(files)


if __name__ == "__main__":
    log = Path(sys.argv.pop(1)).read_text()
    rows = [line.split("REFERENCE_MAPPING_FIXTURE\t", 1)[1] for line in log.splitlines() if "REFERENCE_MAPPING_FIXTURE\t" in line]
    if not rows:
        raise SystemExit("missing actual Scala reference fixture")
    ReferenceReaderTests.fixture = parse(rows[-1])
    unittest.main()
