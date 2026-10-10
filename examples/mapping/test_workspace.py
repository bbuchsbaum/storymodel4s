#!/usr/bin/env python3
"""Mutation courts for a StudyWorkspaceWitness directory."""
import hashlib
import json
from pathlib import Path
import sys
import unittest

from read_workspace import fixture, inspect


def rehash(packet, role, identifier=None):
    entry = next(e for e in packet["entries"] if e["role"].get("kind") == role and
                 (identifier is None or e["role"].get("id") == identifier))
    body = next(f["utf8"] for f in packet["files"] if f["path"] == entry["path"])
    artifact = entry["disposition"]["artifact"]
    artifact["byteLength"] = len(body.encode())
    artifact["checksum"] = hashlib.sha256(body.encode()).hexdigest()


def refresh_receipt_and_capabilities(packet, mapping_id=None):
    receipt_entry = next(e for e in packet["entries"] if e["role"]["kind"] == "Receipt")
    receipt_body = next(f for f in packet["files"] if f["path"] == receipt_entry["path"])
    receipt = json.loads(receipt_body["utf8"])
    if mapping_id is not None:
        mapping_entry = next(e for e in packet["entries"] if e["role"] == {"kind": "Mapping", "id": mapping_id})
        mapping_body = next(f["utf8"] for f in packet["files"] if f["path"] == mapping_entry["path"])
        next(m for m in receipt["members"] if m["id"] == mapping_id)["bytes"] = hashlib.sha256(mapping_body.encode()).hexdigest()
    receipt_body["utf8"] = json.dumps(receipt, separators=(",", ":"))
    rehash(packet, "Receipt")
    capabilities_entry = next(e for e in packet["entries"] if e["role"]["kind"] == "Capabilities")
    capabilities_body = next(f for f in packet["files"] if f["path"] == capabilities_entry["path"])
    capabilities = json.loads(capabilities_body["utf8"])
    capabilities["declaration"] = receipt_entry["disposition"]["artifact"]["checksum"]
    capabilities_body["utf8"] = json.dumps(capabilities, separators=(",", ":"))
    rehash(packet, "Capabilities")


class WorkspaceReaderTests(unittest.TestCase):
    packets = {}

    def packet(self, name="text"):
        return json.loads(self.packets[name])

    def check(self, packet):
        return inspect(json.dumps(packet, separators=(",", ":")))

    def test_positive_controls(self):
        for text in self.packets.values():
            self.assertIn("records", inspect(text))

    def test_wrong_receipt_after_rehash_reaches_member_join(self):
        packet = self.packet()
        receipt = next(f for f in packet["files"] if f["path"] == next(e["path"] for e in packet["entries"] if e["role"]["kind"] == "Receipt"))
        value = json.loads(receipt["utf8"])
        value["members"][0]["recordClaim"] = "0" * 64
        receipt["utf8"] = json.dumps(value, separators=(",", ":"))
        refresh_receipt_and_capabilities(packet)
        with self.assertRaisesRegex(ValueError, "mapping byte identity"):
            self.check(packet)

    def test_foreign_source_after_rehash_reaches_source_join(self):
        packet = self.packet()
        mapping = next(e for e in packet["entries"] if e["role"] == {"kind": "Mapping", "id": "reference"})
        body = next(f for f in packet["files"] if f["path"] == mapping["path"])
        record = json.loads(body["utf8"])
        record["source"]["digest"] = "0" * 64
        body["utf8"] = json.dumps(record, separators=(",", ":"))
        rehash(packet, "Mapping", "reference")
        with self.assertRaisesRegex(ValueError, "foreign mapping source"):
            self.check(packet)

    def test_missing_outcome_after_rehash_reaches_denominator_join(self):
        packet = self.packet()
        for entry in packet["entries"]:
            if entry["role"] == {"kind": "Mapping", "id": "reference"}:
                body = next(f for f in packet["files"] if f["path"] == entry["path"])
                record = json.loads(body["utf8"])
                record["outcomes"].pop()
                body["utf8"] = json.dumps(record, separators=(",", ":"))
                rehash(packet, "Mapping", "reference")
        refresh_receipt_and_capabilities(packet, "reference")
        with self.assertRaisesRegex(ValueError, "missing reference outcome"):
            self.check(packet)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: test_workspace.py STUDY_WORKSPACE_WITNESS_DIRECTORY")
    WorkspaceReaderTests.packets = fixture(Path(sys.argv[1]) / "workspace-fixtures.json")
    unittest.main(argv=[sys.argv[0]], verbosity=2)
