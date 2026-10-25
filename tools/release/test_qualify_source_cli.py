#!/usr/bin/env python3
"""Discriminating isolation, source-pin, totals and archive courts; no sbt required."""
import copy
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).with_name("qualify-source-cli.py")
SPEC = importlib.util.spec_from_file_location("source_cli", SCRIPT)
qualifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(qualifier)


class SourceCliCourt(unittest.TestCase):
    def setUp(self):
        self.pins = qualifier.load_pins(SCRIPT.with_name("source-pins.json"))
        self.fetched = [{"url": pin["url"], "revision": pin["revision"], "clean": True}
                        for pin in self.pins]
        self.declarations = {"storymodel4s": "", "grakern": ""}
        for pin in self.pins:
            self.declarations[pin["declaredBy"]] += 'val %s = "%s"\n' % (pin["variable"], pin["revision"])

    def test_exact_fetch_and_declaration_control_passes(self):
        qualifier.validate_fetches(self.pins, self.fetched, self.declarations)

    def test_missing_and_duplicate_checkouts_refuse(self):
        for rows in (self.fetched[:-1], self.fetched + [self.fetched[0]],
                     [self.fetched[0], self.fetched[0], self.fetched[2]]):
            with self.subTest(rows=rows), self.assertRaisesRegex(ValueError, "checkout|source"):
                qualifier.validate_fetches(self.pins, rows, self.declarations)

    def test_wrong_revision_and_dirty_source_refuse(self):
        for key, value in (("revision", "f" * 40), ("clean", False)):
            rows = copy.deepcopy(self.fetched)
            rows[0][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "wrong revision or dirty source"):
                qualifier.validate_fetches(self.pins, rows, self.declarations)

    def test_correct_fetch_cannot_mask_changed_build_declaration(self):
        declarations = dict(self.declarations)
        declarations["storymodel4s"] = 'val grakernRevision = "' + "f" * 40 + '"'
        with self.assertRaisesRegex(ValueError, "source declaration differs"):
            qualifier.validate_fetches(self.pins, self.fetched, declarations)

    def test_changed_source_url_refuses_even_at_expected_sha(self):
        rows = copy.deepcopy(self.fetched)
        rows[0]["url"] = "https://example.invalid/another.git"
        with self.assertRaisesRegex(ValueError, "missing or duplicate source"):
            qualifier.validate_fetches(self.pins, rows, self.declarations)

    def test_all_ambient_override_channels_refuse(self):
        qualifier.validate_environment({})
        for key in qualifier.OVERRIDE_KEYS:
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, key):
                qualifier.validate_environment({key: "unexpected override"})

    def test_cli_override_refuses_before_output_or_build(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "must-not-exist"
            result = subprocess.run([sys.executable, str(SCRIPT), "--source", directory,
                                     "--revision", "a" * 40, "--output", str(output),
                                     "--java-home", directory], capture_output=True, text=True,
                                    env=dict(os.environ, STORYMODEL4S_GRAKERN_BUILD="/untrusted/source"))
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("ambient build overrides", result.stderr)
            self.assertFalse(output.exists())

    def test_totals_preserve_skips_and_require_actual_passes(self):
        actual = qualifier.test_totals("[info] Passed: Total 7, Failed 0, Errors 0, Passed 5, Skipped 2\n"
                                       "[info] Passed: Total 3, Failed 0, Errors 0, Passed 3\n")
        self.assertEqual(actual, {"tasks": 2, "passed": 8, "failed": 0, "errors": 0, "skipped": 2})
        for text, reason in (("[success] done", "no passing"),
                             ("Passed: Total 2, Failed 0, Errors 0, Passed 1", "inconsistent"),
                             ("Failed: Total 2, Failed 1, Errors 0, Passed 1", "failing"),
                             ("Passed: Total 2, Failed 0, Errors 0, Passed 0, Skipped 2", "no passing")):
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, reason):
                qualifier.test_totals(text)

    def test_zip_escape_and_symlink_refuse_before_extraction(self):
        for name, mode in (("../outside", None), ("/outside", None),
                           ("root\\outside", None), ("root/link", 0o120777)):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive = root / "bundle.zip"
                with zipfile.ZipFile(archive, "w") as stream:
                    entry = zipfile.ZipInfo(name)
                    if mode:
                        entry.external_attr = mode << 16
                    stream.writestr(entry, b"payload")
                output = root / "extract"
                with self.assertRaisesRegex(ValueError, "unsafe ZIP|symlink"):
                    qualifier.extract(archive, output)
                self.assertFalse(output.exists())

    def test_safe_archive_extracts_one_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "bundle.zip"
            with zipfile.ZipFile(archive, "w") as stream:
                stream.writestr("storymodel4s/lib/0000.jar", b"bytes")
            installed = qualifier.extract(archive, root / "extract")
            self.assertEqual(installed.name, "storymodel4s")
            self.assertEqual((installed / "lib/0000.jar").read_bytes(), b"bytes")

    def test_template_stdout_is_kept_separate_from_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            witness = qualifier.Witness(root)
            target = root / "mapping.json"
            log = witness.run([sys.executable, "-c", 'import sys;print("{} ");print("diagnostic",file=sys.stderr)'],
                              root, stdout_path=target)
            self.assertEqual(target.read_text().strip(), "{}")
            self.assertEqual(log.read_text().strip(), "diagnostic")
            self.assertEqual(witness.receipt["commands"][0]["exit"], 0)
            self.assertEqual(witness.receipt["commands"][0]["stdoutSha256"], qualifier.sha(target))

    def test_missing_maven_root_propagates_through_actual_internal_edges(self):
        root = "external:kernel:0.1-pin-SNAPSHOT"
        poms = {
            "own:structural:1.0.0": {"dependencies": [{"coordinate": root, "scope": "compile", "optional": False}]},
            "own:bench:1.0.0": {"dependencies": [{"coordinate": "own:structural:1.0.0", "scope": "runtime", "optional": False}]},
            "own:pipeline:1.0.0": {"dependencies": [{"coordinate": root, "scope": "test", "optional": False}]},
            "own:optional:1.0.0": {"dependencies": [{"coordinate": root, "scope": "compile", "optional": True}]},
        }
        self.assertEqual(qualifier.affected_by_missing(poms, {root}),
                         {"own:structural:1.0.0": [root], "own:bench:1.0.0": [root]})

    def test_pom_inventory_retains_namespace_scope_optional_and_exact_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "candidate.pom"
            path.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'
                            '<groupId>own</groupId><artifactId>module</artifactId><version>1.0.0</version>'
                            '<dependencies><dependency><groupId>external</groupId><artifactId>kernel</artifactId>'
                            '<version>pin-SNAPSHOT</version><scope>runtime</scope><optional>true</optional>'
                            '</dependency></dependencies></project>')
            actual = qualifier.pom_inventory([path])
            self.assertEqual(actual, {"own:module:1.0.0": {"sha256": qualifier.sha(path),
                             "dependencies": [{"coordinate": "external:kernel:pin-SNAPSHOT",
                                               "scope": "runtime", "optional": True}]}})
            with self.assertRaisesRegex(ValueError, "duplicate candidate POM"):
                qualifier.pom_inventory([path, path])


if __name__ == "__main__":
    unittest.main(verbosity=2)
