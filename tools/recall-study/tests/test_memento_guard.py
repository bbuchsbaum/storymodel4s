#!/usr/bin/env python3
"""Temporary-Git, synthetic-XLSX courts for the Memento seal and reader tripwire."""
import ast
import hashlib
import inspect
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parents[1]
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
import memento_guard as g  # noqa: E402
import memento_split as split  # noqa: E402
from test_memento_task import HEADER, workbook  # noqa: E402
from test_friends_guard import _sources, ACCESS_PY, ACCESS_SCALA  # noqa: E402

if os.environ.get("MEMENTO_GUARD_MUTANT"):
    exec(
        compile(
            Path(os.environ["MEMENTO_GUARD_MUTANT"]).read_text(), g.__file__, "exec"
        ),
        g.__dict__,
    )


def scan(root):
    readers, violations = [], []
    for rel, kind in _sources(root):
        text = (Path(root) / rel).read_text()
        if not any(
            lit in text
            for lit in ("Subjects.xlsx", "data/memento", 'resolve("memento")')
        ):
            continue
        if not any(
            token in text for token in (ACCESS_PY if kind == "py" else ACCESS_SCALA)
        ):
            continue
        readers.append(rel)
        if rel in (
            "tools/recall-study/memento_guard.py",
            "tools/recall-study/tests/test_memento_guard.py",
        ):
            continue
        if kind == "scala":
            if (
                rel
                == "corpus-intake/src/main/scala/storymodel4s/corpus/intake/FriendsIntake.scala"
                and not any(
                    token in text for token in ("Subjects.xlsx", '"RecallType"')
                )
            ):
                continue  # hashes all artifacts and parses storyboard; no recall interpretation
            violations.append(rel)
            continue
        tree = ast.parse(text)
        modules = set()
        functions = set()
        allowed = {
            "require_readable",
            "read_units",
            "survey_population",
            "admission_audit",
            "seal_accounting",
        }
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                modules.update(
                    n.asname or n.name for n in node.names if n.name == "memento_guard"
                )
            elif isinstance(node, ast.ImportFrom) and node.module == "memento_guard":
                functions.update(
                    n.asname or n.name for n in node.names if n.name in allowed
                )
        guarded = any(
            isinstance(node, ast.Call)
            and (
                isinstance(node.func, ast.Name)
                and node.func.id in functions
                or isinstance(node.func, ast.Attribute)
                and node.func.attr in allowed
                and isinstance(node.func.value, ast.Name)
                and node.func.value.id in modules
            )
            for node in ast.walk(tree)
        )
        if not guarded:
            violations.append(rel)
    return readers, violations


class MementoGuardSuite(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.addCleanup(patch.stopall)
        patch.object(g.ss, "REPO", self.root).start()
        self.git("init", "-q")
        self.git("config", "user.name", "Synthetic Memento test")
        self.git("config", "user.email", "synthetic@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        (self.root / ".gitignore").write_text("/data/\n")
        self.groups = {
            str(c): [f"S{(c-1)*4+i}" for i in range(1, 5)] for c in range(1, 5)
        }
        index = [(1, ["id", "Condition"])] + [
            (i + 2, [int(name[1:]), int(c)])
            for i, (name, c) in enumerate(
                (name, c) for c, ids in self.groups.items() for name in ids
            )
        ]
        sheets = {"subs": index}
        for ids in self.groups.values():
            for name in ids:
                sheets[name] = [
                    (1, HEADER),
                    (2, [0, 1, 3, None, "synthetic recall"]),
                    (4, [None, 1, 7, None, "synthetic repeated recall"]),
                    (5, [15, 2, None, None, "synthetic gist"]),
                ]
        self.book = self.root / "data/memento" / g.WORKBOOK
        sha = workbook(self.book, sheets)
        self.definition = {
            "schema": g.task.SCHEMA,
            "workbookSha256": sha,
            "headerOverlays": [],
            "split": {
                "seed": 20260921,
                "testByCondition": {c: 2 for c in self.groups},
                "algorithm": "synthetic SHA draw",
            },
        }
        population = {
            "participantsByCondition": self.groups,
            "reconciliation": {
                "byCondition": [
                    {"condition": int(c), "remaining": len(ids)}
                    for c, ids in self.groups.items()
                ]
            },
            "workbook": {"inSubsWithoutSheet": [], "sheetNotInSubs": []},
            "identifiableEventRule": {"sheetsBelowTwo": []},
        }
        self.write(g.SOURCE, {"artifacts": [{"id": g.WORKBOOK, "sha256": sha}]})
        self.write(g.TASK, self.definition)
        self.write(g.POPULATION, population)
        for rel in ("tools/recall-study/memento_task.py", "tools/corpus/xlsx_rows.py"):
            path = self.root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((REPO / rel).read_bytes())
        self.write("release.json", {"synthetic": "release"})
        self.commit()
        self.audit = {"synthetic": True}
        self.write(split.AUDIT, self.audit)
        record = g.plan()
        record.update(power={"synthetic": True}, auditSha256=g.task.digest(self.audit))
        self.write(g.MEMENTO.split, record)
        self.write(g.MEMENTO.ledger, g.ss.empty_ledger(g.MEMENTO))
        self.commit()
        self.record = record
        self.dev = record["development"]["participants"]
        self.test = record["test"]["participants"]

    def git(self, *args):
        return subprocess.run(
            ["git", *args], cwd=self.root, check=True, capture_output=True
        )

    def commit(self):
        self.git("add", ".")
        self.git("-c", "core.hooksPath=/dev/null", "commit", "-qm", "synthetic fixture")

    def write(self, rel, data):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data) + "\n")
        return path

    def ledger(self):
        return json.loads((self.root / g.MEMENTO.ledger).read_text())

    def test_development_units_and_test_refusal_before_workbook(self):
        units = g.read_units(self.root / "data", [self.dev[0]])
        self.assertEqual(len(units), 3)
        self.assertEqual(sum(u.eligible for u in units), 2)
        with patch.object(g, "_book") as book:
            with self.assertRaisesRegex(g.GuardRefusal, "outside final opening"):
                g.read_units(self.root / "data", [self.test[0]])
            book.assert_not_called()
        self.assertEqual(
            set(inspect.signature(g.require_readable).parameters),
            {"participants", "final_opening"},
        )

    def test_final_opening_reads_and_records_real_synthetic_units(self):
        opening = g.FinalOpening(
            "release.json",
            hashlib.sha256((self.root / "release.json").read_bytes()).hexdigest(),
            "synthetic final test",
        )
        self.assertEqual(
            len(
                g.read_units(self.root / "data", [self.test[0]], final_opening=opening)
            ),
            3,
        )
        self.assertEqual(self.ledger()["finalOpeningCount"], 1)
        with self.assertRaisesRegex(g.GuardRefusal, "differs from HEAD"):
            g.read_units(self.root / "data", [self.test[0]], final_opening=opening)

    def test_condition_and_membership_tampering_refused_even_when_committed(self):
        self.record["testByCondition"]["1"] = 3
        self.write(g.MEMENTO.split, self.record)
        self.commit()
        with self.assertRaisesRegex(g.GuardRefusal, "testByCondition"):
            g.load_split()
        self.record["testByCondition"]["1"] = 2
        (
            self.record["development"]["participants"][0],
            self.record["test"]["participants"][0],
        ) = self.test[0], self.dev[0]
        self.write(g.MEMENTO.split, self.record)
        self.commit()
        with self.assertRaisesRegex(g.GuardRefusal, "membership"):
            g.load_split()

    def test_task_and_implementation_identity_pinned(self):
        self.definition["changedMeaning"] = True
        self.write(g.TASK, self.definition)
        self.commit()
        with self.assertRaisesRegex(g.GuardRefusal, "taskDefinitionSha256"):
            g.load_split()
        del self.definition["changedMeaning"]
        self.write(g.TASK, self.definition)
        self.commit()
        p = self.root / "tools/corpus/xlsx_rows.py"
        p.write_text(p.read_text() + "\n# changed parser\n")
        self.commit()
        with self.assertRaisesRegex(g.GuardRefusal, "xlsxReaderSha256"):
            g.load_split()

    def test_count_only_attempt_logged_before_read_and_aggregate_only(self):
        original = g._book

        def checked(root):
            self.assertEqual(self.ledger()["countOnlyReadCount"], 1)
            self.assertEqual(self.ledger()["countOnlyReads"][0]["status"], "started")
            return original(root)

        with patch.object(g, "_book", side_effect=checked):
            result = g.seal_accounting(self.root / "data", purpose="synthetic recount")
        self.assertEqual(
            result["totals"],
            {
                "development": {"participants": 8, "goldUnits": 16},
                "test": {"participants": 8, "goldUnits": 16},
            },
        )
        self.assertNotIn("synthetic recall", json.dumps(result))
        self.assertEqual(self.ledger()["countOnlyReads"][0]["status"], "completed")
        with patch.object(g, "_book") as book:
            with self.assertRaisesRegex(g.GuardRefusal, "differs from HEAD"):
                g.seal_accounting(self.root / "data", purpose="again")
            book.assert_not_called()

    def test_sealing_forms_close_even_if_committed_split_deleted(self):
        for deleted in (False, True):
            if deleted:
                (self.root / g.MEMENTO.split).unlink()
            with self.assertRaisesRegex(
                g.GuardRefusal, "sealing-time reads are closed"
            ):
                g._require_unsealed()
            with patch.object(g, "_book") as book:
                for operation in (g.survey_population, g.admission_audit):
                    with self.assertRaisesRegex(
                        g.GuardRefusal, "sealing-time reads are closed"
                    ):
                        operation(self.root / "data")
                with self.assertRaisesRegex(
                    g.GuardRefusal, "sealing-time reads are closed"
                ):
                    g.seal_accounting(self.root / "data", sealing_split=self.record)
                with self.assertRaisesRegex(
                    g.GuardRefusal, "sealing-time reads are closed"
                ):
                    split.main(["--write", "--data-root", str(self.root / "data")])
                book.assert_not_called()

    def test_check_uses_no_workbook(self):
        with patch.object(g, "_book") as book, patch.object(
            split, "power_block", return_value={"synthetic": True}
        ):
            self.assertEqual(split.main(["--check", "--data-root", "/nonexistent"]), 0)
            book.assert_not_called()

    def test_population_audit_and_seal_round_trip(self):
        self.git("rm", g.MEMENTO.split, g.MEMENTO.ledger, split.AUDIT)
        self.commit()
        population = g.survey_population(self.root / "data")
        self.assertEqual(population["participantsByCondition"], self.groups)
        self.assertEqual(
            split.main(["--audit", "--data-root", str(self.root / "data")]), 0
        )
        self.commit()
        audit = g._record(split.AUDIT)
        self.assertEqual(audit["zeroEligibleParticipants"], [])
        self.assertEqual(audit["byCondition"]["1"]["goldUnits"], 8)
        self.assertEqual(audit["byCondition"]["1"]["code2MissingScene"], 4)
        with patch.object(split, "power_block", return_value={"synthetic": True}):
            self.assertEqual(
                split.main(["--write", "--data-root", str(self.root / "data")]), 0
            )
            self.commit()
            self.assertEqual(split.main(["--check"]), 0)
        self.assertEqual(g.load_split()["test"]["participants"], self.test)
        self.assertEqual(self.ledger(), g.ss.empty_ledger(g.MEMENTO))

    def test_reader_scan_sees_real_readers_and_refuses_import_only(self):
        readers, violations = scan(REPO)
        self.assertEqual(violations, [])
        for known in (
            "tools/recall-study/memento_guard.py",
            "tools/recall-study/memento_split.py",
            "corpus-intake/src/main/scala/storymodel4s/corpus/intake/FriendsIntake.scala",
        ):
            self.assertIn(known, readers)
        path = self.root / "tools/bypass.py"
        path.parent.mkdir(exist_ok=True)
        path.write_text('import memento_guard\nopen("data/memento/Subjects.xlsx")\n')
        self.assertIn("tools/bypass.py", scan(self.root)[1])
        path.write_text(
            'import memento_guard\nmemento_guard.require_readable(["S1"])\nopen("data/memento/Subjects.xlsx")\n'
        )
        self.assertEqual(scan(self.root)[1], [])


if __name__ == "__main__":
    unittest.main()
