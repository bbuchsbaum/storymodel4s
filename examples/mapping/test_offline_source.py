#!/usr/bin/env python3
"""Independent reader for an actual StudyPreparation surface-atlas exchange."""

import csv
import hashlib
import io
import json
from pathlib import Path
import sys
import unittest


def parse(text):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON key: {key}")
            result[key] = value
        return result

    return json.loads(text, object_pairs_hook=unique)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def utf16_slice(text, start, end):
    raw = text.encode("utf-16-le")
    return raw[2 * start:2 * end].decode("utf-16-le")


class OfflineSourceReaderTests(unittest.TestCase):
    exchange = None

    def test_checked_exchange_preserves_unicode_coordinates_and_capabilities(self):
        paths = {path.name for path in self.exchange.iterdir()}
        self.assertEqual(paths, {"source.json", "source-segments.tsv", "source-manifest.json", "manifest.json"})
        completion = parse((self.exchange / "manifest.json").read_text())
        self.assertEqual(completion["schemaVersion"], "prepared-study/v1")
        self.assertFalse(completion["metadata"]["narrativeModel"])
        self.assertTrue(completion["metadata"]["textSourceExchange"])
        for entry in completion["files"]:
            self.assertEqual(sha((self.exchange / entry["path"]).read_bytes()), entry["sha256"])
        manifest_bytes = (self.exchange / "source-manifest.json").read_bytes()
        manifest = parse(manifest_bytes.decode("utf-8"))
        self.assertEqual(manifest["schemaVersion"], "text-source-exchange/v1")
        payloads = {}
        for entry in manifest["files"]:
            name = entry["name"]
            body = (self.exchange / (name if name == "source.json" else "source-" + name)).read_bytes()
            self.assertEqual(sha(body), entry["sha256"])
            self.assertEqual(len(body), entry["bytes"])
            payloads[entry["name"]] = body.decode("utf-8")
        source = parse(payloads["source.json"])
        self.assertEqual(source["profile"], "supplied-atlas/v1")
        self.assertEqual(source["source"]["rawText"], "\ufeffA\u0301😀; B.\r\nC.")
        self.assertEqual(source["offset_unit"], "utf-16-code-units")
        self.assertEqual(
            source["capabilities"],
            {
                "character_offsets": {"status": "available"},
                "discourse_ordinals": {"status": "available"},
                "narrative_model": {"status": "unavailable", "reason": "not-supplied"},
                "encoding_seconds": {"status": "unavailable", "reason": "no-presentation-schedule"},
            },
        )
        canonical = source["source"]["canonicalText"]
        self.assertTrue(canonical.startswith("\ufeffA\u0301😀"))
        self.assertIn("\n", canonical)
        self.assertEqual(source["atlas"]["canonicalSourceChecksum"], sha(canonical.encode("utf-8")))
        rows = list(csv.DictReader(io.StringIO(payloads["segments.tsv"]), delimiter="\t"))
        self.assertEqual(len(rows), len(source["segments"]))
        for row, segment in zip(rows, source["segments"]):
            start, end = int(row["start"]), int(row["end_exclusive"])
            self.assertEqual(row["text"], utf16_slice(canonical, start, end))
            self.assertEqual(row["target_id"], segment["target_id"])
            self.assertEqual(row["offset_unit"], "utf-16-code-units")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: test_offline_source.py EXCHANGE_DIRECTORY")
    OfflineSourceReaderTests.exchange = Path(sys.argv[1])
    unittest.main(argv=[sys.argv[0]], verbosity=2)
