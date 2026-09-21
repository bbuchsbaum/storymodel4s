#!/usr/bin/env python3
"""Friends test-split courts: refusal, final opening, ledger, seal record, bypass scan. No corpus."""

import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import unittest

HERE = Path(os.environ.get("FRIENDS_GUARD_HERE") or Path(__file__).resolve().parents[1])
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
import friends_guard as g  # noqa: E402
import friends_split as fs  # noqa: E402

# Mutants run as standalone copies but keep the original repository context.
if os.environ.get("FRIENDS_GUARD_MUTANT"):
    source = Path(os.environ["FRIENDS_GUARD_MUTANT"]).read_text()
    exec(compile(source, str(HERE / "friends_guard.py"), "exec"), g.__dict__)

# A file reads Friends recall if it names the recall path and touches the filesystem.
READER_LITERALS = ("FriendsRecallScoring", "data/friends", 'resolve("friends")')
ACCESS_PY = ("open(", "sheet_rows", "read_bytes", "zipfile", "Path(")
ACCESS_SCALA = ("Files.", "FileStore", "dataRoot", "Paths.get")
# Parsing recall needs its column names as string keys; a doc comment naming them is not parsing.
RECALL_PARSING = re.compile(
    r'FriendsRecallScoring|"(WhichEvent|RecallType|SecondsOfRecall)"'
)
GUARD = "tools/recall-study/friends_guard.py"
# Scala has no guard: a Scala reader is refused unless it is integrity-only, recorded here.
INTEGRITY_ONLY = {
    "corpus-intake/src/main/scala/storymodel4s/corpus/intake/FriendsIntake.scala": (
        "Verify hashes every declared artifact's bytes; it parses only the storyboard sheets "
        "FriendsNarrComb and FriendsMoreEMs, never the recall workbook"
    ),
}
SKIP_DIRS = {
    ".git",
    ".worktrees",
    "target",
    "node_modules",
    "__pycache__",
    ".metals",
    ".bloop",
}


def _sources(root):
    root = Path(root)
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        rel_base = Path(base).relative_to(root)
        for name in files:
            rel = rel_base / name
            if name.endswith(".py") and rel.parts[:1] == ("tools",):
                yield rel.as_posix(), "py"
            elif name.endswith(".scala") and "src" in rel.parts:
                yield rel.as_posix(), "scala"


def scan(root):
    """(readers, violations): every Friends recall reader, and each one that bypasses the guard."""
    readers, violations = [], []
    for rel, kind in _sources(root):
        text = (Path(root) / rel).read_text(encoding="utf-8", errors="replace")
        if not any(lit in text for lit in READER_LITERALS):
            continue
        if not any(
            tok in text for tok in (ACCESS_PY if kind == "py" else ACCESS_SCALA)
        ):
            continue
        readers.append(rel)
        if rel == GUARD:
            continue
        if kind == "py":
            if not re.search(
                r"^\s*(import friends_guard|from friends_guard import)", text, re.M
            ):
                violations.append(
                    f"{rel}: reads Friends recall without importing friends_guard"
                )
        elif rel not in INTEGRITY_ONLY:
            violations.append(f"{rel}: Scala reader of Friends recall with no guard")
        elif RECALL_PARSING.search(text):
            violations.append(
                f"{rel}: integrity-only exemption but parses recall columns"
            )
    return readers, violations


class FriendsGuardSuite(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.split = g.load_split()
        self.dev = self.split["development"]["participants"]
        self.test_ids = self.split["test"]["participants"]

    def _ledger(self):
        path = self.root / "reads.json"
        path.write_text(
            json.dumps({"schema": g.LEDGER_SCHEMA, "readCount": 0, "reads": []})
        )
        return path

    def test_development_participants_pass(self):
        self.assertEqual(g.require_readable(self.dev), self.dev)

    def test_test_participant_refused_without_final_opening(self):
        with self.assertRaisesRegex(g.GuardRefusal, "refused outside final opening"):
            g.require_readable([self.test_ids[0]])

    def test_mixed_request_refused(self):
        with self.assertRaisesRegex(g.GuardRefusal, "refused outside final opening"):
            g.require_readable([self.dev[0], self.test_ids[0]])

    def test_unknown_and_empty_requests_refused(self):
        with self.assertRaisesRegex(g.GuardRefusal, "outside the Friends pool"):
            g.require_readable(["s17"])
        with self.assertRaisesRegex(g.GuardRefusal, "no participants"):
            g.require_readable([])

    def test_final_opening_verifies_manifest_digest_and_counts_the_read(self):
        ledger = self._ledger()
        manifest = self.root / "release-candidate.json"
        manifest.write_text('{"release": "candidate"}\n')
        digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
        kwargs = {"ledger_path": ledger, "repo": self.root}
        for opening, why in (
            (g.FinalOpening(manifest, "0" * 64, "final test"), "digest does not match"),
            (
                g.FinalOpening(self.root / "absent.json", digest, "final test"),
                "does not exist",
            ),
            (
                g.FinalOpening(REPO / "build.sbt", digest, "final test"),
                "must live in the repo",
            ),
        ):
            with self.assertRaisesRegex(g.GuardRefusal, why):
                g.require_readable(self.test_ids, final_opening=opening, **kwargs)
        self.assertEqual(json.loads(ledger.read_text())["readCount"], 0)
        opening = g.FinalOpening(manifest, digest, "final test")
        self.assertEqual(
            g.require_readable(self.test_ids, final_opening=opening, **kwargs),
            self.test_ids,
        )
        record = json.loads(ledger.read_text())
        self.assertEqual(record["readCount"], 1)
        self.assertEqual(record["reads"][0]["participants"], sorted(self.test_ids))
        self.assertEqual(record["reads"][0]["releaseCandidateManifestSha256"], digest)
        with self.assertRaisesRegex(g.GuardRefusal, "state its purpose"):
            g.FinalOpening(manifest, digest, " ")

    def test_committed_ledger_records_zero_reads(self):
        ledger = json.loads(g.LEDGER.read_text())
        self.assertEqual(ledger["schema"], g.LEDGER_SCHEMA)
        self.assertEqual((ledger["readCount"], ledger["reads"]), (0, []))

    def test_committed_split_reproduces_from_seed_without_data(self):
        record = json.loads(g.SPLIT.read_text())
        pool = fs.pool_ids()
        self.assertEqual(len(pool), 23)
        dev, test = fs.draw(pool, record["seed"])
        self.assertEqual(
            (record["development"]["participants"], record["test"]["participants"]),
            (dev, test),
        )
        self.assertEqual((len(dev), len(test)), (13, 10))
        self.assertEqual(sorted(record["pool"]["participants"]), sorted(pool))
        self.assertEqual(fs.draw(list(reversed(pool)), record["seed"]), (dev, test))
        self.assertNotEqual(fs.draw(pool, record["seed"] + 1), (dev, test))
        self.assertEqual(
            record["development"]["goldUnits"] + record["test"]["goldUnits"], 630
        )
        self.assertEqual(
            record["draw"]["scriptSha256"], fs.sha256_file(HERE / "friends_split.py")
        )
        self.assertEqual(
            record["test"]["individuallyExposedBeforeSeal"],
            [s for s in ("s6", "s26", "s21") if s in test],
        )

    def test_power_block_matches_independent_reference(self):
        # scipy.stats.nct / brentq, computed outside this stdlib-only suite: 0.996001, 0.846655.
        self.assertAlmostEqual(fs.paired_mde_effect_size(10)[0], 0.996001, places=5)
        self.assertAlmostEqual(fs.paired_mde_effect_size(13)[0], 0.846655, places=5)
        power = json.loads(g.SPLIT.read_text())["power"]
        self.assertEqual(power["testParticipants"], 10)
        for row in power["mdeBySdAssumption"]:
            self.assertAlmostEqual(
                row["mdePp"], power["mdeEffectSize"] * row["sdPp"], places=1
            )

    def test_unit_definition_breaks_runs_and_keeps_veridical_rows_only(self):
        header = ["SecondsOfRecall", "RecallType", "WhichEvent"]
        rows = [
            header,
            [0, 1, 12.0],
            [1, 1, "12"],  # one unit: "12" and 12.0 are the same event
            [2, 1, None],
            [3, 1, 12.0],  # a blank row ends the run: second unit
            [4, 2, 12.0],
            [5, 1, 12.0],  # a non-veridical row ends the run: third unit
            [6, 1, 7.0],
            [7, 3, 7.0],
        ]  # a new event: fourth; the RecallType 3 row adds none
        self.assertEqual(g._unit_count(rows), 4)
        with self.assertRaisesRegex(g.GuardRefusal, "non-numeric"):
            g._unit_count([header, [0, 1, "twelve"]])

    def test_seal_accounting_refuses_an_unadmitted_workbook(self):
        (self.root / "friends").mkdir()
        (self.root / "friends" / g.WORKBOOK).write_bytes(b"not the admitted workbook")
        with self.assertRaisesRegex(g.GuardRefusal, "admitted digest"):
            g.seal_accounting(self.root, self.split)

    def test_no_reader_bypasses_the_guard(self):
        readers, violations = scan(REPO)
        self.assertEqual(violations, [])
        # Capacity to fail: the scan must actually see the known readers.
        for known in ("tools/recall-study/friends_split.py", *INTEGRITY_ONLY):
            self.assertIn(known, readers)

    def test_bypass_scan_flags_an_unguarded_reader(self):
        tools = self.root / "tools/x"
        tools.mkdir(parents=True)
        reader = tools / "reader.py"
        reader.write_text(
            'rows = open("data/friends/FriendsRecallScoring.xlsx", "rb").read()\n'
        )
        self.assertEqual(len(scan(self.root)[1]), 1)
        reader.write_text("import friends_guard\n" + reader.read_text())
        self.assertEqual(scan(self.root)[1], [])
        scala = self.root / "mod/src/main/scala/R.scala"
        scala.parent.mkdir(parents=True)
        scala.write_text('val b = Files.readAllBytes(dataRoot.resolve("friends"))\n')
        self.assertEqual(len(scan(self.root)[1]), 1)
        exempt = self.root / next(iter(INTEGRITY_ONLY))
        exempt.parent.mkdir(parents=True)
        exempt.write_text('val c = dataRoot.resolve("friends"); row("WhichEvent")\n')
        self.assertIn("parses recall columns", " ".join(scan(self.root)[1]))


if __name__ == "__main__":
    unittest.main()
