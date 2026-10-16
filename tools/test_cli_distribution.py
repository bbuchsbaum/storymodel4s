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
import zipfile


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
            "sys.exit(int(os.environ.get('FIXTURE_JAVA_EXIT','0')))\n"
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

    def test_child_failure_exit_is_preserved(self):
        self.env["FIXTURE_JAVA_EXIT"] = "37"
        self.assertEqual(self.run_cli("mappingRun", "template").returncode, 37)


def compare_archives(left, right):
    """Compare actual production artifacts made from the same revision in different timezones."""
    def digest(path):
        with path.open("rb") as stream:
            return hashlib.file_digest(stream, "sha256").hexdigest()
    first, second = digest(left), digest(right)
    assert first == second, f"archive bytes differ: {first} != {second}"
    print(json.dumps({"archive_bytes_equal": True, "sha256": first}))


def verify_distribution(root):
    manifest = json.loads((root / "manifest.json").read_text())
    assert manifest["schemaVersion"] == "storymodel4s-cli-distribution/v1"
    expected = {row["path"]: row for row in manifest["files"]}
    assert len(expected) == len(manifest["files"])
    actual = {p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file()}
    assert not any(p.is_symlink() for p in root.rglob("*")), "distribution contains a symlink"
    assert actual == set(expected) | {"manifest.json"}
    for name, row in expected.items():
        data = (root / name).read_bytes()
        assert len(data) == row["bytes"]
        assert hashlib.sha256(data).hexdigest() == row["sha256"]
    assert (root / "classpath.txt").read_text().splitlines() == manifest["classpath"]
    assert len(manifest["classpath"]) == len(set(manifest["classpath"]))
    verify_provenance(root, manifest)
    result = subprocess.run(["sh", str(root / "bin/storymodel4s"), "mappingRun", "template"],
                            text=True, capture_output=True, check=False)
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout)["profile"] == "mapping-lexical-pair/v1"
    print(json.dumps({"source": manifest["source"], "files_verified": len(expected),
                      "classpath_entries": len(manifest["classpath"]),
                      "actual_java_command": "mappingRun template", "exit": result.returncode}))


def legal_name(name):
    import re
    leaf = name.split("/")[-1].lower()
    return bool(re.fullmatch(r"(.*[._-])?(license|licence|notice|thirdpartynotices|copying|copyright)([._-].*)?", leaf)
                and not re.search(r"\.(class|jar|so|dylib|dll|exe)$", leaf))


def verify_provenance(root, manifest):
    import re
    provenance = json.loads((root / "dependency-provenance.json").read_text())
    assert provenance["schemaVersion"] == "storymodel4s-cli-dependencies/v1"
    assert provenance["source"] == manifest["source"], "provenance source differs"
    assert provenance["scalaVersion"] == manifest["scalaVersion"], "provenance Scala version differs"
    entries = provenance["classpath"]
    assert [row["path"] for row in entries] == ["lib/" + name for name in manifest["classpath"]], "provenance classpath differs"
    files = {row["path"]: row for row in manifest["files"]}
    for ordinal, row in enumerate(entries):
        assert row["sha256"] == files[row["path"]]["sha256"], "provenance JAR hash differs"
        origin = row["origin"]
        assert origin["kind"] in {"source-project", "maven-artifact"}, "unknown JAR origin"
        assert all(isinstance(origin[key], str) and origin[key] for key in ("group", "artifact", "version")), "incomplete JAR origin"
        if origin["kind"] == "source-project":
            assert origin["project"] and re.fullmatch(r"[0-9a-f]{40}", origin["revision"]), "invalid source project"
            assert origin["repository"].startswith("https://github.com/"), "invalid source repository"
            if origin["repository"].rstrip("/") == "https://github.com/bbuchsbaum/storymodel4s":
                assert origin["revision"] == manifest["source"], "StoryModel product source differs"
            assert origin["scalaVersion"], "missing source Scala version"
        else:
            assert all(origin[key] for key in ("artifactName", "type", "extension")), "incomplete Maven artifact"
        licenses = {entry["name"] for entry in row["licenses"]}
        assert licenses and all(licenses), "missing declared licenses"
        assert row["notices"], "missing dependency notices"
        covered = set()
        for notice in row["notices"]:
            name = notice["path"]
            assert name.startswith(f"notices/{ordinal:04d}/") and name in files, "notice reference differs"
            payload = (root / name).read_bytes()
            assert payload and len(payload) == notice["bytes"], "notice length differs"
            assert hashlib.sha256(payload).hexdigest() == notice["sha256"], "notice hash differs"
            proof = notice["origin"]
            if proof["kind"] == "jar-entry":
                assert legal_name(proof["entry"]), "non-text file offered as a legal notice"
                with zipfile.ZipFile(root / row["path"]) as jar:
                    assert jar.namelist().count(proof["entry"]) == 1, "missing or duplicate embedded notice"
                    assert jar.read(proof["entry"]) == payload, "embedded notice bytes differ"
            elif proof["kind"] == "supplement":
                assert origin["kind"] == "maven-artifact" and proof["license"] in licenses, "supplement license differs"
                assert proof["artifactSha256"] == row["sha256"], "supplement artifact differs"
                assert proof["url"].startswith("https://"), "missing supplemental source URL"
                covered.add(proof["license"])
            elif proof["kind"] == "source-file":
                assert origin["kind"] == "source-project" and proof["revision"] == origin["revision"], "source notice revision differs"
                assert legal_name(proof["path"]), "invalid source legal file"
                if proof["path"].startswith("LICENSE"):
                    covered.update(licenses)
            else:
                raise AssertionError("unknown notice origin")
        assert licenses <= covered, "declared license lacks evidence"


class DependencyCourt(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="storymodel provenance ")
        self.root = Path(self.temp.name)
        (self.root / "lib").mkdir()
        self.source = "a" * 40
        for ordinal in range(2):
            with zipfile.ZipFile(self.root / f"lib/{ordinal:04d}.jar", "w") as jar:
                jar.writestr("Fixture.txt", str(ordinal))
                jar.writestr("META-INF/NOTICE.txt", b"synthetic embedded notice")
                jar.writestr("ai/djl/repository/License.class", b"synthetic bytecode")
        self.provenance = {"schemaVersion": "storymodel4s-cli-dependencies/v1", "source": self.source,
                           "scalaVersion": "3.7.4", "classpath": []}
        for ordinal in range(2):
            origin = {"kind": "source-project" if ordinal == 0 else "maven-artifact",
                      "group": "fixture", "artifact": "fixture-" + str(ordinal), "version": "1.0.0"}
            if ordinal == 0:
                origin.update(project="pipeline", repository="https://github.com/bbuchsbaum/storymodel4s",
                              revision=self.source, scalaVersion="3.7.4")
            else:
                origin.update(artifactName="fixture-1", type="jar", extension="jar", classifier=None, url=None)
            row = {"path": f"lib/{ordinal:04d}.jar", "sha256": self.digest(self.root / f"lib/{ordinal:04d}.jar"),
                   "origin": origin, "licenses": [{"name": "fixture-license", "url": None}], "notices": []}
            if ordinal == 0:
                proof = {"kind": "source-file", "path": "LICENSE", "revision": self.source}
            else:
                proof = {"kind": "supplement", "license": "fixture-license", "artifactSha256": row["sha256"],
                         "url": "https://example.invalid/fixture/LICENSE"}
            self.add_notice(row, ordinal, "LICENSE", b"synthetic license evidence", proof)
            self.add_notice(row, ordinal, "NOTICE.txt", b"synthetic embedded notice",
                            {"kind": "jar-entry", "entry": "META-INF/NOTICE.txt"})
            self.provenance["classpath"].append(row)

    def tearDown(self):
        self.temp.cleanup()

    @staticmethod
    def digest(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def add_notice(self, row, ordinal, leaf, payload, proof):
        name = f"notices/{ordinal:04d}/{len(row['notices']):04d}/{leaf}"
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(payload)
        row["notices"].append({"path": name, "bytes": len(payload),
                               "sha256": hashlib.sha256(payload).hexdigest(), "origin": proof})

    def manifest(self):
        (self.root / "dependency-provenance.json").write_text(json.dumps(self.provenance))
        # Rebind the outer inventory so mutation failures exercise semantic checks.
        return {"source": self.source, "scalaVersion": "3.7.4", "classpath": ["0000.jar", "0001.jar"],
                "files": [{"path": p.relative_to(self.root).as_posix(), "sha256": self.digest(p)}
                          for p in sorted(self.root.rglob("*")) if p.is_file()]}

    def check(self):
        verify_provenance(self.root, self.manifest())

    def test_complete_origin_and_notice_control_passes(self):
        self.check()

    def test_vendored_component_notice_names_are_retained(self):
        for name in ("META-INF/FastDoubleParser-LICENSE", "META-INF/thirdparty-LICENSE",
                     "META-INF/FastDoubleParser-NOTICE", "ThirdPartyNotices.txt"):
            with self.subTest(name=name):
                self.assertTrue(legal_name(name))

    def test_missing_duplicate_and_swapped_jar_associations_refuse(self):
        rows = self.provenance["classpath"]
        for replacement in (rows[:1], rows + rows[:1], list(reversed(rows))):
            with self.subTest(length=len(replacement)):
                self.provenance["classpath"] = replacement
                with self.assertRaisesRegex(AssertionError, "provenance classpath differs"):
                    self.check()

    def test_wrong_source_and_unknown_origin_refuse(self):
        row = self.provenance["classpath"][0]
        row["origin"]["revision"] = "b" * 40
        with self.assertRaisesRegex(AssertionError, "StoryModel product source differs"):
            self.check()
        row["origin"]["revision"] = self.source
        row["origin"]["kind"] = "unverified"
        with self.assertRaisesRegex(AssertionError, "unknown JAR origin"):
            self.check()

    def test_notice_tamper_refuses_after_outer_manifest_is_rebound(self):
        notice = self.provenance["classpath"][1]["notices"][0]
        path = self.root / notice["path"]
        path.write_bytes(b"x" * notice["bytes"])
        with self.assertRaisesRegex(AssertionError, "notice hash differs"):
            self.check()

    def test_supplement_is_bound_to_exact_dependency_bytes(self):
        row = self.provenance["classpath"][1]
        row["notices"][0]["origin"]["artifactSha256"] = self.provenance["classpath"][0]["sha256"]
        with self.assertRaisesRegex(AssertionError, "supplement artifact differs"):
            self.check()

    def test_nonexistent_reference_and_uncovered_license_refuse(self):
        row = self.provenance["classpath"][1]
        original = row["notices"][0]["path"]
        row["notices"][0]["path"] = "notices/0001/missing.txt"
        with self.assertRaisesRegex(AssertionError, "notice reference differs"):
            self.check()
        row["notices"][0]["path"] = original
        row["licenses"].append({"name": "uncovered-license", "url": None})
        with self.assertRaisesRegex(AssertionError, "declared license lacks evidence"):
            self.check()

    def test_bytecode_cannot_count_as_license_text(self):
        row = self.provenance["classpath"][1]
        row["notices"].pop()
        self.add_notice(row, 1, "License.class", b"synthetic bytecode",
                        {"kind": "jar-entry", "entry": "ai/djl/repository/License.class"})
        with self.assertRaisesRegex(AssertionError, "non-text file offered"):
            self.check()


if __name__ == "__main__":
    suite = unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromTestCase(court)
                               for court in (LauncherCourt, DependencyCourt))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful():
        raise SystemExit(1)
    if len(sys.argv) == 4 and sys.argv[1] == "--compare-archives":
        compare_archives(Path(sys.argv[2]), Path(sys.argv[3]))
    elif len(sys.argv) == 2:
        verify_distribution(Path(sys.argv[1]).resolve())
    elif len(sys.argv) != 1:
        raise SystemExit("usage: test_cli_distribution.py [STAGED_DISTRIBUTION | --compare-archives LEFT RIGHT]")
