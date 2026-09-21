#!/usr/bin/env python3
"""Sealed-split courts using temporary Git repositories and synthetic records only."""

import copy
import fcntl
import hashlib
import inspect
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

HERE = Path(os.environ.get("FRIENDS_GUARD_HERE") or Path(__file__).resolve().parents[1])
sys.path.insert(0, str(HERE))
import sealed_split as ss  # noqa: E402
import friends_guard as g  # noqa: E402
import friends_split as fs  # noqa: E402

for module, variable in ((ss, "SEALED_SPLIT_MUTANT"), (g, "FRIENDS_GUARD_MUTANT")):
    if os.environ.get(variable):
        exec(
            compile(Path(os.environ[variable]).read_text(), module.__file__, "exec"),
            module.__dict__,
        )


class SealedSplitSuite(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.addCleanup(patch.stopall)
        patch.object(ss, "REPO", self.root).start()
        patch.object(fs, "REPO", self.root).start()
        self.corpus = g.FRIENDS
        self.split = {
            "schema": self.corpus.split_schema,
            "corpus": "friends",
            "pool": {"participants": ["dev", "held"]},
            "development": {"participants": ["dev"]},
            "test": {"participants": ["held"]},
        }
        self.git("init", "-q")
        self.git("config", "user.name", "Synthetic guard test")
        self.git("config", "user.email", "synthetic@example.invalid")
        self.write(self.corpus.split, self.split)
        self.write(self.corpus.ledger, ss.empty_ledger(self.corpus))
        self.write("release.json", {"candidate": "synthetic"})
        self.commit()
        digest = hashlib.sha256((self.root / "release.json").read_bytes()).hexdigest()
        self.opening = ss.FinalOpening("release.json", digest, "synthetic final test")

    def git(self, *args):
        return subprocess.run(
            ["git", *args], cwd=self.root, check=True, capture_output=True
        )

    def write(self, rel, data):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data) + "\n")
        return path

    def commit(self):
        self.git("add", ".")
        self.git("-c", "core.hooksPath=/dev/null", "commit", "-qm", "synthetic fixture")

    def ledger(self):
        return json.loads((self.root / self.corpus.ledger).read_text())

    def test_development_passes_and_test_requires_opening(self):
        self.assertEqual(g.require_readable(["dev"]), ["dev"])
        for ids in (["held"], ["dev", "held"]):
            with self.assertRaisesRegex(ss.GuardRefusal, "outside final opening"):
                g.require_readable(ids)
        self.assertEqual(self.ledger()["finalOpeningCount"], 0)

    def test_unknown_and_empty_requests_refused(self):
        for ids in ([], ["absent"], [None]):
            with self.assertRaises(ss.GuardRefusal):
                g.require_readable(ids)

    def test_wrapper_has_no_path_override(self):
        self.assertEqual(
            set(inspect.signature(g.require_readable).parameters),
            {"participants", "final_opening"},
        )
        self.assertEqual(list(inspect.signature(g.load_split).parameters), [])
        for key in ("split_path", "ledger_path", "repo"):
            with self.assertRaises(TypeError):
                g.require_readable(["held"], **{key: self.root})

    def test_changed_split_refused_even_for_development(self):
        (
            self.split["development"]["participants"],
            self.split["test"]["participants"],
        ) = ["held"], ["dev"]
        self.write(self.corpus.split, self.split)
        with self.assertRaisesRegex(ss.GuardRefusal, "differs from HEAD"):
            g.require_readable(["held"])
        self.git("add", self.corpus.split)
        with self.assertRaisesRegex(ss.GuardRefusal, "differs from HEAD"):
            g.require_readable(["held"])

    def test_skip_worktree_does_not_hide_modified_split(self):
        self.git("update-index", "--skip-worktree", self.corpus.split)
        (
            self.split["development"]["participants"],
            self.split["test"]["participants"],
        ) = ["held"], ["dev"]
        self.write(self.corpus.split, self.split)
        with self.assertRaisesRegex(ss.GuardRefusal, "differs from HEAD"):
            g.require_readable(["held"])

    def test_staged_changes_refused_when_worktree_matches_head(self):
        for rel in (self.corpus.split, "release.json", self.corpus.ledger):
            with self.subTest(path=rel):
                path = self.root / rel
                original = path.read_bytes()
                self.write(rel, {"staged": "different bytes"})
                self.git("add", rel)
                path.write_bytes(original)
                with self.assertRaisesRegex(ss.GuardRefusal, "differs from HEAD"):
                    g.require_readable(["held"], final_opening=self.opening)
                self.git("restore", "--staged", "--worktree", "--", rel)

    def test_committed_malformed_split_refused(self):
        cases = []
        for field, value in (("schema", "unknown"), ("corpus", "other")):
            bad = copy.deepcopy(self.split)
            bad[field] = value
            cases.append(bad)
        for side, ids in (
            ("test", ["dev"]),
            ("pool", ["dev", "held", "extra"]),
            ("pool", ["dev", "held", "held"]),
            ("development", []),
        ):
            bad = copy.deepcopy(self.split)
            bad[side]["participants"] = ids
            cases.append(bad)
        for bad in cases:
            with self.subTest(split=bad):
                self.write(self.corpus.split, bad)
                self.commit()
                with self.assertRaises(ss.GuardRefusal):
                    g.load_split()

    def test_untracked_manifest_refused(self):
        (self.root / "untracked.json").write_bytes(
            (self.root / "release.json").read_bytes()
        )
        opening = ss.FinalOpening(
            "untracked.json", self.opening.manifest_sha256, "test"
        )
        with self.assertRaisesRegex(ss.GuardRefusal, "not committed"):
            g.require_readable(["held"], final_opening=opening)

    def test_modified_manifest_refused_even_with_matching_digest(self):
        path = self.write("release.json", {"changed": True})
        opening = ss.FinalOpening(
            "release.json", hashlib.sha256(path.read_bytes()).hexdigest(), "test"
        )
        with self.assertRaisesRegex(ss.GuardRefusal, "differs from HEAD"):
            g.require_readable(["held"], final_opening=opening)

    def test_manifest_digest_checked(self):
        opening = ss.FinalOpening("release.json", "0" * 64, "test")
        with self.assertRaisesRegex(ss.GuardRefusal, "digest does not match"):
            g.require_readable(["held"], final_opening=opening)
        self.assertEqual(self.ledger()["finalOpeningCount"], 0)

    def test_manifest_path_and_capability_checked(self):
        for rel in (str(self.root / "release.json"), "../release.json"):
            with self.assertRaises(ss.GuardRefusal):
                g.require_readable(
                    ["held"], final_opening=ss.FinalOpening(rel, "0" * 64, "test")
                )
        with self.assertRaises(ss.GuardRefusal):
            g.require_readable(["held"], final_opening=object())
        with self.assertRaisesRegex(ss.GuardRefusal, "state its purpose"):
            ss.FinalOpening("release.json", "0" * 64, " ")

    def test_committed_symlink_manifest_refused(self):
        (self.root / "alias.json").symlink_to("release.json")
        self.commit()
        with self.assertRaisesRegex(ss.GuardRefusal, "symlink"):
            g.require_readable(
                ["held"],
                final_opening=ss.FinalOpening(
                    "alias.json", self.opening.manifest_sha256, "test"
                ),
            )

    def test_final_opening_recorded_before_grant_and_next_read_requires_commit(self):
        self.assertEqual(
            g.require_readable(["held"], final_opening=self.opening), ["held"]
        )
        ledger = self.ledger()
        self.assertEqual(ledger["finalOpeningCount"], 1)
        self.assertEqual(ledger["finalOpenings"][0]["participants"], ["held"])
        self.assertEqual(
            ledger["finalOpenings"][0]["releaseCandidateManifestSha256"],
            self.opening.manifest_sha256,
        )
        with self.assertRaisesRegex(ss.GuardRefusal, "differs from HEAD"):
            g.require_readable(["held"], final_opening=self.opening)
        self.commit()
        g.require_readable(["held"], final_opening=self.opening)
        self.assertEqual(self.ledger()["finalOpeningCount"], 2)

    def test_ledger_checked_under_lock(self):
        original = ss._committed

        def checked(rel):
            if rel == self.corpus.ledger:
                with (self.root / ".git/sealed-split-ledger.lock").open("a") as other:
                    with self.assertRaises(BlockingIOError):
                        fcntl.flock(other, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return original(rel)

        with patch.object(ss, "_committed", side_effect=checked):
            g.require_readable(["held"], final_opening=self.opening)

    def test_malformed_ledger_cannot_grant_access(self):
        ledger = self.ledger()
        ledger["finalOpeningCount"] = 9
        self.write(self.corpus.ledger, ledger)
        self.commit()
        with self.assertRaisesRegex(ss.GuardRefusal, "count does not match"):
            g.require_readable(["held"], final_opening=self.opening)

    def test_sealing_forms_refuse_existing_and_deleted_committed_seal_before_read(self):
        for deleted in (False, True):
            if deleted:
                (self.root / self.corpus.split).unlink()
            with patch.object(
                g, "_workbook", return_value=("synthetic.xlsx", "digest")
            ) as workbook, patch.object(g, "_counts", return_value=[1]):
                for operation in (g.seal_accounting, g.development_unit_counts):
                    with self.assertRaisesRegex(
                        ss.GuardRefusal, "sealing-time reads are closed"
                    ):
                        operation(self.root, sealing_split=self.split)
                with self.assertRaisesRegex(
                    ss.GuardRefusal, "sealing-time reads are closed"
                ):
                    fs.main(["--write", "--data-root", str(self.root)])
                workbook.assert_not_called()

    def test_count_only_read_is_prelogged_and_returns_aggregate_totals(self):
        def counts(workbook, ids):
            self.assertEqual(self.ledger()["countOnlyReadCount"], 1)
            self.assertEqual(self.ledger()["countOnlyReads"][0]["status"], "started")
            return [3 if i == "dev" else 7 for i in ids]

        with patch.object(
            g, "_workbook", return_value=("synthetic.xlsx", "digest")
        ), patch.object(g, "_counts", side_effect=counts):
            result = g.seal_accounting(self.root, purpose="synthetic recount")
        expected = {
            "development": {"participants": 1, "goldUnits": 3},
            "test": {"participants": 1, "goldUnits": 7},
        }
        self.assertEqual(result, {"workbookSha256": "digest", "totals": expected})
        record = self.ledger()
        self.assertEqual(
            (record["finalOpeningCount"], record["countOnlyReadCount"]), (0, 1)
        )
        self.assertEqual(record["countOnlyReads"][0]["totals"], expected)
        self.assertEqual(record["countOnlyReads"][0]["status"], "completed")
        with patch.object(g, "_workbook") as workbook:
            with self.assertRaisesRegex(ss.GuardRefusal, "differs from HEAD"):
                g.seal_accounting(self.root, purpose="second attempt")
            workbook.assert_not_called()

    def test_failed_count_only_read_remains_counted(self):
        with patch.object(g, "_workbook", side_effect=OSError("synthetic failure")):
            with self.assertRaises(OSError):
                g.seal_accounting(self.root, purpose="failed attempt")
        self.assertEqual(self.ledger()["countOnlyReadCount"], 1)
        self.assertEqual(self.ledger()["countOnlyReads"][0]["status"], "failed")
        self.assertNotIn("totals", self.ledger()["countOnlyReads"][0])

    def test_count_only_purpose_required_before_read(self):
        with patch.object(g, "_workbook") as workbook:
            with self.assertRaisesRegex(ss.GuardRefusal, "state its purpose"):
                g.seal_accounting(self.root)
            workbook.assert_not_called()
        self.assertEqual(self.ledger()["countOnlyReadCount"], 0)

    def test_count_only_rejects_individual_or_invalid_totals(self):
        for totals in (
            {"held": 7},
            {
                "development": {"participants": 1, "goldUnits": True},
                "test": {"participants": 1, "goldUnits": 7},
            },
        ):
            with self.assertRaisesRegex(ss.GuardRefusal, "nonnegative integers"):
                with ss.count_only_read(self.corpus, "bad totals") as complete:
                    complete(totals)
            self.commit()

    def test_development_counts_use_committed_membership(self):
        with patch.object(
            g, "_workbook", return_value=("synthetic.xlsx", "digest")
        ), patch.object(g, "_counts", return_value=[3]) as counts:
            self.assertEqual(g.development_unit_counts(self.root), [3])
            counts.assert_called_once_with("synthetic.xlsx", ["dev"])
        self.assertEqual(self.ledger()["countOnlyReadCount"], 0)

    def test_synthetic_workbook_counts_before_and_after_seal(self):
        workbook = self.root / "friends" / g.WORKBOOK
        workbook.parent.mkdir()
        namespace = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        with zipfile.ZipFile(workbook, "w") as out:
            out.writestr(
                "xl/workbook.xml",
                f'<workbook xmlns="{namespace}" '
                'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
                '<sheets><sheet name="dev" r:id="r1"/><sheet name="held" r:id="r2"/></sheets></workbook>',
            )
            out.writestr(
                "xl/_rels/workbook.xml.rels",
                "<Relationships "
                'xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                '<Relationship Id="r1" Target="worksheets/s1.xml"/>'
                '<Relationship Id="r2" Target="worksheets/s2.xml"/></Relationships>',
            )
            for sheet, events in ((1, [1, 1]), (2, [1, 2])):
                rows = [
                    '<row><c r="A1" t="inlineStr"><is><t>RecallType</t></is></c>'
                    '<c r="B1" t="inlineStr"><is><t>WhichEvent</t></is></c></row>'
                ]
                rows += [
                    f'<row><c r="A{i}"><v>1</v></c><c r="B{i}"><v>{event}</v></c></row>'
                    for i, event in enumerate(events, 2)
                ]
                out.writestr(
                    f"xl/worksheets/s{sheet}.xml",
                    f'<worksheet xmlns="{namespace}"><sheetData>{"".join(rows)}</sheetData></worksheet>',
                )
        digest = hashlib.sha256(workbook.read_bytes()).hexdigest()
        self.write(
            g.SOURCE_MANIFEST, {"artifacts": [{"path": g.WORKBOOK, "sha256": digest}]}
        )
        self.commit()
        expected = {
            "workbookSha256": digest,
            "totals": {
                "development": {"participants": 1, "goldUnits": 1},
                "test": {"participants": 1, "goldUnits": 2},
            },
        }
        self.assertEqual(g.development_unit_counts(self.root), [1])
        self.assertEqual(
            g.seal_accounting(self.root, purpose="synthetic workbook"), expected
        )
        self.assertEqual(self.ledger()["countOnlyReadCount"], 1)
        # Remove only the synthetic seal from HEAD to exercise the initial sealing phase.
        self.git("rm", self.corpus.split)
        self.commit()
        self.assertEqual(
            g.seal_accounting(self.root, sealing_split=self.split), expected
        )
        self.assertEqual(
            g.development_unit_counts(self.root, sealing_split=self.split), [1]
        )
        self.assertEqual(self.ledger()["countOnlyReadCount"], 1)

    def test_workbook_digest_checked(self):
        self.write(
            g.SOURCE_MANIFEST, {"artifacts": [{"path": g.WORKBOOK, "sha256": "0" * 64}]}
        )
        self.commit()
        (self.root / "friends").mkdir()
        (self.root / "friends" / g.WORKBOOK).write_bytes(b"synthetic workbook")
        with self.assertRaisesRegex(ss.GuardRefusal, "admitted digest"):
            g.seal_accounting(self.root, purpose="digest check")
        self.assertEqual(self.ledger()["countOnlyReads"][0]["status"], "failed")


if __name__ == "__main__":
    unittest.main()
