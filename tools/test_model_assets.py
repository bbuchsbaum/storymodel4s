"""Data-safety and reproducibility courts; all payloads are synthetic small bytes."""
import contextlib
import hashlib
import importlib.util
import io
import json
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('model_assets', Path(__file__).with_name('model-assets.py'))
m = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(m)


class ModelAssetsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.repo = self.base / 'repo'
        self.repo.mkdir()
        self.cache = self.base / 'external'
        self.payload = b'known model bytes'
        self.record = dict(bytes=len(self.payload), sha256=hashlib.sha256(self.payload).hexdigest(),
                           weight=True, legacy_paths=['data/models/demo/weights.bin'])
        self.model = dict(id='demo', repo='example/model', revision='a'*40,
                          files={'weights.bin': self.record})

    def source(self):
        path = self.repo / self.record['legacy_paths'][0]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(self.payload)
        return path

    def migrate(self, **kwargs):
        with patch.object(m, 'volume_identity', return_value={'VolumeUUID': 'fixture'}), \
             patch.object(m, 'open_paths', return_value=set()), contextlib.redirect_stdout(io.StringIO()):
            return m.migrate([self.model], self.cache, self.repo, self.base/'receipt.json', 'fixture', **kwargs)

    def test_manifest_pins_and_traversal(self):
        manifest = json.loads(m.MANIFEST.read_text())
        self.assertEqual(len(m.read_manifest(m.MANIFEST)['models']), 6)
        for change, error in [('revision', 'immutable'), ('path', 'Unsafe relative path'), ('url', 'Unpinned')]:
            copy = json.loads(json.dumps(manifest))
            model = copy['models'][0]
            if change == 'revision': model['revision'] = 'main'
            elif change == 'path': model['files']['../escape'] = model['files'].pop(next(iter(model['files'])))
            else: next(iter(model['files'].values()))['url'] = 'https://example.com/unpinned'
            file = self.base / 'manifest.json'
            file.write_text(json.dumps(copy))
            with self.assertRaisesRegex(ValueError, error): m.read_manifest(file)

    def test_partial_and_corrupt_download_never_publish(self):
        for payload in [self.payload[:-1], b'x'*len(self.payload), self.payload+b'x']:
            target = self.cache/'weights.bin'
            with self.assertRaises(ValueError):
                m.publish_stream(target, self.record, lambda p=payload: io.BytesIO(p))
            self.assertFalse(target.exists())
            self.assertEqual(list(self.cache.iterdir()), [])

    def test_interrupted_stream_cleans_only_owned_partial(self):
        class Broken(io.BytesIO):
            def read(self, *args): raise OSError('connection lost')
        self.cache.mkdir()
        sentinel = self.cache/'unrelated'
        sentinel.write_bytes(b'preserve')
        with self.assertRaisesRegex(OSError, 'connection lost'):
            m.publish_stream(self.cache/'weights.bin', self.record, lambda: Broken(b''))
        self.assertEqual(list(self.cache.iterdir()), [sentinel])
        self.assertEqual(sentinel.read_bytes(), b'preserve')

    def test_existing_conflicting_cache_is_preserved(self):
        self.cache.mkdir()
        target = self.cache/'weights.bin'
        target.write_bytes(b'conflict')
        with self.assertRaises(ValueError):
            m.publish_stream(target, self.record, lambda: io.BytesIO(self.payload))
        self.assertEqual(target.read_bytes(), b'conflict')

    def test_migration_verified_custody_and_legacy_bytes(self):
        source = self.source()
        result = self.migrate()
        target = m.cache_path(self.cache, self.model, 'weights.bin')
        self.assertEqual(result['phase'], 'complete')
        self.assertEqual(result['relocated_bytes'], len(self.payload))
        self.assertTrue(source.is_symlink())
        self.assertEqual(source.resolve(), target.resolve())
        self.assertEqual(source.read_bytes(), self.payload)
        self.assertEqual(target.read_bytes(), self.payload)

    def test_copy_only_preserves_regular_source(self):
        source = self.source()
        result = self.migrate(copy_only=True)
        self.assertEqual(result['phase'], 'copy_verified')
        self.assertFalse(source.is_symlink())
        self.assertEqual(source.read_bytes(), self.payload)

    def test_open_weights_copy_but_do_not_evict(self):
        source = self.source()
        with patch.object(m, 'volume_identity', return_value={}), \
             patch.object(m, 'open_paths', return_value={str(source)}), \
             contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(ValueError, 'Weights are open'):
                m.migrate([self.model], self.cache, self.repo, self.base/'receipt.json', 'fixture')
        self.assertFalse(source.is_symlink())
        self.assertEqual(source.read_bytes(), self.payload)
        self.assertEqual(json.loads((self.base/'receipt.json').read_text())['phase'], 'pending_open_files')
        self.assertEqual(m.cache_path(self.cache, self.model, 'weights.bin').read_bytes(), self.payload)

    def test_reopened_weight_refuses_at_actual_replacement(self):
        source = self.source()
        with patch.object(m, 'volume_identity', return_value={}), \
             patch.object(m, 'open_paths', side_effect=[set(), {str(source)}]), \
             contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(ValueError, 'became open before replacement'):
                m.migrate([self.model], self.cache, self.repo, self.base/'receipt.json', 'fixture')
        self.assertFalse(source.is_symlink())
        self.assertEqual(source.read_bytes(), self.payload)

    def test_symlink_escapes_and_local_cache_refused(self):
        self.cache.mkdir()
        (self.cache/'example').symlink_to(self.base/'outside')
        with self.assertRaisesRegex(ValueError, 'Cache symlink'):
            m.cache_path(self.cache, self.model, 'weights.bin')
        (self.repo/'tmp').symlink_to(self.base/'outside')
        with self.assertRaisesRegex(ValueError, 'Legacy parent escapes'):
            m.legacy_path(self.repo, 'tmp/weights.bin')
        with self.assertRaisesRegex(ValueError, 'outside the checkout'):
            m.prepare_root(self.repo/'models', self.repo)

    def test_conflicting_legacy_file_never_overwritten(self):
        target = self.base/'verified'
        target.write_bytes(self.payload)
        source = self.source()
        source.write_bytes(b'unrelated')
        with self.assertRaises(ValueError): m.replace_with_link(source, target, self.record, evict=True)
        self.assertEqual(source.read_bytes(), b'unrelated')

    def test_relink_requires_explicit_flag_and_verified_new_bytes(self):
        source = self.repo/'alias'
        source.symlink_to(self.base/'unmounted-volume')
        target = self.base/'verified'
        target.write_bytes(self.payload)
        with self.assertRaisesRegex(ValueError, 'Existing link points elsewhere'):
            m.replace_with_link(source, target, self.record)
        m.replace_with_link(source, target, self.record, relink=True)
        self.assertEqual(source.read_bytes(), self.payload)

    def test_late_open_guard_mutation_is_killed(self):
        source = Path(m.__file__).read_text()
        guard = "require(not open_paths([path]), 'Weight became open before replacement: ' + str(path))"
        self.assertEqual(source.count(guard), 1)
        mutant = types.ModuleType('model_assets_mutant')
        mutant.__file__ = m.__file__
        exec(compile(source.replace(guard, "require(True, 'Weight became open before replacement: ' + str(path))"),
                     m.__file__, 'exec'), mutant.__dict__)
        names = [n for n in unittest.defaultTestLoader.getTestCaseNames(ModelAssetsTests)
                 if n != 'test_late_open_guard_mutation_is_killed']
        suite = unittest.TestSuite(ModelAssetsTests(name) for name in names)
        result = unittest.TestResult()
        with patch(__name__ + '.m', mutant):
            suite.run(result)
        self.assertEqual(result.testsRun, 11)
        self.assertEqual(len(result.errors), 0)
        self.assertEqual(len(result.failures), 1)
        self.assertEqual(result.failures[0][0]._testMethodName,
                         'test_reopened_weight_refuses_at_actual_replacement')
        self.assertIn('ValueError not raised', result.failures[0][1])


if __name__ == '__main__':
    unittest.main()
