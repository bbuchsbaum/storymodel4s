import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("weights", Path(__file__).with_name("model_weights.py"))
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)


class Downloads(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.source = self.root / "source"
        self.source.write_bytes(b"pinned model fixture")
        self.entry = {"url": self.source.as_uri(), "bytes": self.source.stat().st_size,
                      "sha256": hashlib.sha256(self.source.read_bytes()).hexdigest()}
        self.dest = self.root / "cache" / "model"

    def test_download_and_idempotence(self):
        w.fetch(self.dest, self.entry)
        self.source.unlink()
        w.fetch(self.dest, self.entry)
        self.assertEqual(self.dest.read_bytes(), b"pinned model fixture")

    def test_wrong_hash_never_published(self):
        with self.assertRaises(ValueError):
            w.fetch(self.dest, dict(self.entry, sha256="0" * 64))
        self.assertFalse(self.dest.exists())
        self.assertEqual(list(self.dest.parent.iterdir()), [])

    def test_conflict_preserved(self):
        self.dest.parent.mkdir()
        self.dest.write_bytes(b"keep me")
        with self.assertRaises(ValueError):
            w.fetch(self.dest, self.entry)
        self.assertEqual(self.dest.read_bytes(), b"keep me")

    def test_dangling_link_preserved(self):
        self.dest.parent.mkdir()
        self.dest.symlink_to(self.root / "absent")
        with self.assertRaises(ValueError):
            w.fetch(self.dest, self.entry)
        self.assertTrue(self.dest.is_symlink())
        self.assertFalse((self.root / "absent").exists())

    def test_symlink_parent_refused(self):
        real = self.root / "real"
        real.mkdir()
        self.dest.parent.symlink_to(real, target_is_directory=True)
        with self.assertRaises(ValueError):
            w.fetch(self.dest, self.entry)
        self.assertEqual(list(real.iterdir()), [])

    def test_path_traversal_refused(self):
        for name in ("../escape", "/absolute"):
            with self.assertRaises(ValueError):
                w.target(self.root, name)

    def test_wrong_size_never_published(self):
        with self.assertRaises(ValueError):
            w.fetch(self.dest, dict(self.entry, bytes=1))
        self.assertFalse(self.dest.exists())


if __name__ == "__main__":
    unittest.main()
