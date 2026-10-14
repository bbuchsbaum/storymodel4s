#!/usr/bin/env python3
"""Exercise launcher argument/environment boundaries; optionally read back a staged distribution."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[1]
LAUNCHER = REPO / "pipeline/src/universal/bin/storymodel4s"


class LauncherCourt(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="storymodel cli ")
        self.root = Path(self.temp.name)
        (self.root / "bin").mkdir()
        (self.root / "lib").mkdir()
        shutil.copyfile(LAUNCHER, self.root / "bin/storymodel4s")
        (self.root / "lib/0000.jar").write_bytes(b"synthetic launcher fixture")
        (self.root / "classpath.txt").write_text("0000.jar\n")
        home = self.root / "fake java home"
        (home / "bin").mkdir(parents=True)
        java = home / "bin/java"
        java.write_text(
            f"#!{sys.executable}\n"
            "import json,os,sys\n"
            "print(json.dumps({'args':sys.argv[1:],'telemetry':os.environ.get('ORT_DISABLE_TELEMETRY')}))\n"
        )
        java.chmod(0o700)
        self.env = dict(os.environ, JAVA_HOME=str(home), ORT_DISABLE_TELEMETRY="0")

    def tearDown(self):
        self.temp.cleanup()

    def run_cli(self, *args):
        return subprocess.run(
            ["sh", str(self.root / "bin/storymodel4s"), *args],
            env=self.env, text=True, capture_output=True, check=False,
        )

    def test_help_and_invalid_command_exit_without_java(self):
        self.assertEqual(self.run_cli("--help").returncode, 0)
        self.assertEqual(self.run_cli().returncode, 2)
        result = self.run_cli("../../unexpected.Main")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")

    def test_spaces_and_literal_shell_syntax_are_preserved(self):
        arguments = ["input with spaces.json", "$(touch NEVER)", "`literal`", ""]
        result = self.run_cli("mappingRun", *arguments)
        self.assertEqual(result.returncode, 0, result.stderr)
        actual = json.loads(result.stdout)
        self.assertEqual(actual["args"], ["-cp", str(self.root / "lib/0000.jar"),
                                        "storymodel4s.pipeline.mappingRun", *arguments])
        self.assertEqual(actual["telemetry"], "1")
        self.assertFalse((self.root / "NEVER").exists())

    def test_ordered_classpath_and_video_alias(self):
        (self.root / "lib/0001.jar").write_bytes(b"second fixture")
        (self.root / "classpath.txt").write_text("0001.jar\n0000.jar\n")
        result = self.run_cli("videoSource", "build", "job.json", "out")
        self.assertEqual(result.returncode, 0, result.stderr)
        actual = json.loads(result.stdout)
        self.assertEqual(actual["args"][1], os.pathsep.join(
            str(self.root / "lib" / name) for name in ["0001.jar", "0000.jar"]))
        self.assertEqual(actual["args"][2], "storymodel4s.pipeline.VideoSourceCli")

    def test_missing_empty_and_foreign_classpath_entries_refuse(self):
        for listing in ["", "0001.jar\n", "../a.jar\n", "/tmp/library.jar\n"]:
            with self.subTest(listing=listing):
                (self.root / "classpath.txt").write_text(listing)
                result = self.run_cli("mappingRun", "template")
                self.assertEqual(result.returncode, 2)
                self.assertEqual(result.stdout, "")


def verify_distribution(root):
    manifest = json.loads((root / "manifest.json").read_text())
    assert manifest["schemaVersion"] == "storymodel4s-cli-distribution/v1"
    expected = {row["path"]: row for row in manifest["files"]}
    assert len(expected) == len(manifest["files"])
    actual = {p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file()}
    assert actual == set(expected) | {"manifest.json"}
    for name, row in expected.items():
        data = (root / name).read_bytes()
        assert len(data) == row["bytes"]
        assert hashlib.sha256(data).hexdigest() == row["sha256"]
    assert (root / "classpath.txt").read_text().splitlines() == manifest["classpath"]
    assert len(manifest["classpath"]) == len(set(manifest["classpath"]))
    result = subprocess.run(["sh", str(root / "bin/storymodel4s"), "mappingRun", "template"],
                            text=True, capture_output=True, check=False)
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout)["profile"] == "mapping-lexical-pair/v1"
    print(json.dumps({"source": manifest["source"], "files_verified": len(expected),
                      "classpath_entries": len(manifest["classpath"]),
                      "actual_java_command": "mappingRun template", "exit": result.returncode}))


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(LauncherCourt)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful():
        raise SystemExit(1)
    if len(sys.argv) == 2:
        verify_distribution(Path(sys.argv[1]).resolve())
    elif len(sys.argv) != 1:
        raise SystemExit("usage: test_cli_distribution.py [STAGED_DISTRIBUTION]")
