#!/usr/bin/env python3
"""Corruption witnesses over the public RecallVolumeFixture output; no participant data."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import unittest
from read_recall_volumes import read

ROOT = Path(sys.argv.pop(1))
FILES = {p.name: p.read_text() for p in (ROOT / 'output').iterdir()}
CSV = (ROOT / 'clock.csv').read_bytes()


def edited(name, change):
    files = dict(FILES)
    value = json.loads(files[name])
    change(value)
    files[name] = json.dumps(value, sort_keys=True, separators=(',', ':'))
    if name != 'manifest.json':
        manifest = json.loads(files['manifest.json'])
        entry = next(e for e in manifest['files'] if e['path'] == name)
        raw = files[name].encode()
        entry['sha256'] = hashlib.sha256(raw).hexdigest()
        entry['bytes'] = str(len(raw))
        files['manifest.json'] = json.dumps(manifest)
    return files


class Reader(unittest.TestCase):
    def test_control(self):
        result = read(FILES, CSV)
        self.assertEqual(result['summary']['timingDispositions'], {'assigned': 1, 'outside-stored-coverage': 2})
        self.assertEqual(result['summary']['originalUnits'], 1)
        self.assertEqual(len(result['exchange']['pairs']), 1)

    def test_final_boundary(self):
        with self.assertRaisesRegex(ValueError, 'attachment reconstruction'):
            read(edited('onset-attachments.json', lambda a: a['records'][1].update(volume=1, timing_status='assigned')), CSV)

    def test_dropped_outside_record(self):
        with self.assertRaisesRegex(ValueError, 'attachment reconstruction'):
            read(edited('onset-attachments.json', lambda a: a['records'].pop()), CSV)

    def test_duplicate_unit_link(self):
        with self.assertRaisesRegex(ValueError, 'attachment reconstruction'):
            read(edited('onset-attachments.json', lambda a: a['volume_units'].append(copy.deepcopy(a['volume_units'][0]))), CSV)

    def test_invented_mass(self):
        with self.assertRaisesRegex(ValueError, 'attachment reconstruction'):
            read(edited('onset-attachments.json', lambda a: a.update(temporal_mass_allocation='uniform-words')), CSV)

    def test_shift(self):
        with self.assertRaisesRegex(ValueError, 'undeclared shift'):
            read(edited('scanner-binding.json', lambda a: a.update(offset={'numerator': '9', 'denominator': '1'})), CSV)

    def test_foreign_clock(self):
        with self.assertRaisesRegex(ValueError, 'clock selection'):
            read(edited('recall-timing.json', lambda a: a['clock'].update(key='foreign')), CSV)

    def test_repinned_same_length_foreign_text(self):
        changed_csv = CSV.replace(b'Anna,0', b'Zzzz,0')
        old_hash, new_hash = hashlib.sha256(CSV).hexdigest(), hashlib.sha256(changed_csv).hexdigest()
        files = {name: text.replace(old_hash, new_hash) for name, text in FILES.items()}
        manifest = json.loads(files['manifest.json'])
        binding = json.loads(files['scanner-binding.json'])
        declaration = manifest['metadata']['recording']
        binding['evidence']['declaration'] = hashlib.sha256(json.dumps(
            declaration, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()
        files['scanner-binding.json'] = json.dumps(binding)
        for entry in manifest['files']:
            raw = files[entry['path']].encode()
            entry.update(sha256=hashlib.sha256(raw).hexdigest(), bytes=str(len(raw)))
        files['manifest.json'] = json.dumps(manifest)
        with self.assertRaisesRegex(ValueError, 'transcript replay'):
            read(files, changed_csv)

    def test_changed_csv(self):
        with self.assertRaisesRegex(ValueError, 'CSV identity'):
            read(FILES, CSV.replace(b'Anna,0', b'Anna,.1'))

    def test_promoted_authority(self):
        with self.assertRaisesRegex(ValueError, 'authority promotion'):
            read(edited('scanner-run.json', lambda a: a.update(authority='verified')), CSV)

    def test_changed_declaration(self):
        with self.assertRaisesRegex(ValueError, 'declaration identity'):
            read(edited('manifest.json', lambda a: a['metadata']['recording'].update(basis='published-release-definition')), CSV)


if __name__ == '__main__':
    unittest.main()
