#!/usr/bin/env python3
"""Synthetic row, scoring, physical-coordinate and stratification courts. No corpus reads."""
import copy
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
from xml.sax.saxutils import escape

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE.parent / "corpus")]
import memento_task as task  # noqa: E402
import xlsx_rows as xlsx  # noqa: E402

if os.environ.get("MEMENTO_XLSX_MUTANT"):
    exec(
        compile(
            Path(os.environ["MEMENTO_XLSX_MUTANT"]).read_text(), xlsx.__file__, "exec"
        ),
        xlsx.__dict__,
    )
CellError, sheet_rows = xlsx.CellError, xlsx.sheet_rows

if os.environ.get("MEMENTO_TASK_MUTANT"):
    exec(
        compile(
            Path(os.environ["MEMENTO_TASK_MUTANT"]).read_text(), task.__file__, "exec"
        ),
        task.__dict__,
    )

HEADER = [
    "SecondsOfRecall",
    "RecallType",
    "BroadSceneNum",
    "BroadSceneNum2",
    "Transcript",
]
SHA = "a" * 64
CONTRACT = {"schema": task.SCHEMA, "workbookSha256": SHA, "headerOverlays": []}


def workbook(path, sheets):
    """Tiny real XLSX fixture, retaining arbitrary physical row numbers and synthetic text."""
    ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as out:
        out.writestr(
            "xl/workbook.xml",
            f'<workbook xmlns="{ns}" xmlns:r="{rel}"><sheets>'
            + "".join(
                f'<sheet name="{name}" r:id="r{i}"/>'
                for i, name in enumerate(sheets, 1)
            )
            + "</sheets></workbook>",
        )
        out.writestr(
            "xl/_rels/workbook.xml.rels",
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            + "".join(
                f'<Relationship Id="r{i}" Target="worksheets/s{i}.xml"/>'
                for i in range(1, len(sheets) + 1)
            )
            + "</Relationships>",
        )
        for i, rows in enumerate(sheets.values(), 1):
            xml = []
            for row_num, row in rows:
                cells = []
                for col, value in enumerate(row):
                    ref = chr(65 + col) + str(row_num)
                    if isinstance(value, CellError):
                        cells.append(
                            f'<c r="{ref}" t="e"><v>{escape(value.code or "")}</v></c>'
                        )
                    elif isinstance(value, str):
                        cells.append(
                            f'<c r="{ref}" t="inlineStr"><is><t>{escape(value)}</t></is></c>'
                        )
                    elif value is not None:
                        cells.append(f'<c r="{ref}"><v>{value}</v></c>')
                xml.append(f'<row r="{row_num}">{"".join(cells)}</row>')
            out.writestr(
                f"xl/worksheets/s{i}.xml",
                f'<worksheet xmlns="{ns}"><sheetData>{"".join(xml)}</sheetData></worksheet>',
            )
    return hashlib.sha256(path.read_bytes()).hexdigest()


class MementoTaskSuite(unittest.TestCase):
    def units(self, rows, participant="S1"):
        return task.normalize([(1, HEADER), *rows], participant, SHA, CONTRACT)

    def test_rows_keep_physical_identity_repeats_and_optional_time(self):
        units = self.units(
            [
                (2, [0, 1, 3, None, "synthetic repeated text"]),
                (7, [None, 1, 3, None, "synthetic repeated text"]),
            ]
        )
        self.assertEqual(len(units), 2)
        self.assertNotEqual(units[0].unit_id, units[1].unit_id)
        self.assertEqual(units[0].text_sha256, units[1].text_sha256)
        self.assertEqual([u.excel_row for u in units], [2, 7])
        self.assertTrue(all(u.eligible for u in units))
        self.assertIsNone(units[1].observed_seconds)
        self.assertEqual(units[1].clock_status, task.Clock.MISSING)
        self.assertNotIn("text", units[0].metadata())

    def test_any_annotated_scene_is_one_observation(self):
        units = self.units([(2, [0, 2, 3, 7, "synthetic"])])
        for prediction, expected in (
            (3, 1.0),
            (7, 1.0),
            (4, 0.0),
            (None, 0.0),
            (True, 0.0),
            ("3", 0.0),
            (45, 0.0),
        ):
            with self.subTest(prediction=prediction):
                result = task.score(units, [(units[0].unit_id, prediction)], ["S1"])
                self.assertEqual(result["participantMean"], expected)
                self.assertEqual(result["participants"]["S1"]["eligible"], 1)

    def test_sceneless_code2_is_accounted_without_becoming_error(self):
        units = self.units([(2, [0, 2, None, None, "synthetic"])])
        self.assertEqual(units[0].reasons, (task.Reason.MISSING_SCENE,))
        result = task.score(units, [(units[0].unit_id, None)], ["S1"])
        self.assertEqual(result["participants"]["S1"]["units"], 1)
        self.assertIsNone(result["participantMean"])
        self.assertIsNone(result["participants"]["S1"]["accuracy"])

    def test_invalid_second_scene_cannot_be_silently_discarded(self):
        for invalid in (45, 0, "3?", 1.5, float("nan"), True):
            unit = self.units([(2, [0, 1, 3, invalid, "synthetic"])])[0]
            self.assertFalse(unit.eligible)
            self.assertIn(task.Reason.INVALID_SCENE, unit.reasons)

    def test_invalid_and_nonaccurate_codes_never_enter_accuracy(self):
        for code in (3, 4, 5, 22, 45, 44652, "3? 2?", None, float("nan"), True):
            self.assertFalse(
                self.units([(2, [0, code, 3, None, "synthetic"])])[0].eligible
            )
        for code in (1, 2, "1.0", "2"):
            self.assertTrue(
                self.units([(2, [0, code, 3, None, "synthetic"])])[0].eligible
            )

    def test_empty_text_annotation_rows_and_legend_only_rows(self):
        units = self.units(
            [
                (2, [0, 1, 3, None, " "]),
                (3, [None, None, None, None, None, "legend"]),
                (4, [None, None, None, None, None]),
            ]
        )
        self.assertEqual(len(units), 1)
        self.assertIn(task.Reason.MISSING_TEXT, units[0].reasons)

    def test_time_changes_do_not_change_gold_eligibility(self):
        for value in (None, "bad clock", -5, float("inf"), "0:01:45"):
            unit = self.units([(2, [value, 1, 3, None, "synthetic"])])[0]
            self.assertTrue(unit.eligible)
        self.assertEqual(task.clock_value("0:01:45"), (105, task.Clock.OBSERVED))
        self.assertEqual(task.clock_value("0:61:00"), (None, task.Clock.INVALID))

    def test_participant_equal_mean_and_zero_eligible_population(self):
        units = self.units([(2, [0, 1, 3, None, "synthetic"])], "S1") + self.units(
            [(i + 2, [i * 5, 1, 3, None, "synthetic"]) for i in range(9)], "S2"
        )
        predictions = [(u.unit_id, 3 if u.participant == "S1" else None) for u in units]
        result = task.score(units, predictions, ["S1", "S2"])
        self.assertEqual(result["participantMean"], 0.5)
        self.assertEqual(result["pooledAccuracy"], 0.1)
        self.assertIsNone(
            task.score(units, predictions, ["S1", "S2", "S3"])["participantMean"]
        )

    def test_identity_and_prediction_support_refuse_omissions(self):
        units = self.units([(2, [0, 1, 3, None, "synthetic"])])
        for predictions in ([], [("extra", 3)], [(units[0].unit_id, 3), ("extra", 3)]):
            with self.assertRaisesRegex(ValueError, "unit IDs"):
                task.score(units, predictions, ["S1"])
        with self.assertRaisesRegex(ValueError, "unit IDs"):
            task.score(units * 2, [(units[0].unit_id, 3)], ["S1"])
        changed = self.units([(2, [0, 1, 3, None, "synthetic"])], "S2")
        self.assertNotEqual(changed[0].unit_id, units[0].unit_id)

    def test_duplicate_predictions_refuse_before_mapping(self):
        units = self.units([(2, [0, 1, 3, None, "synthetic"])])
        identity = units[0].unit_id
        predictions = json.loads(json.dumps([[identity, 4], [identity, 3]]))
        with self.assertRaisesRegex(ValueError, "duplicate"):
            task.score(units, predictions, ["S1"])
        with self.assertRaisesRegex(ValueError, "collapsed mapping"):
            task.score(units, {identity: 3}, ["S1"])

    def test_header_overlay_is_exactly_bound_and_sensitivity_is_explicit(self):
        header = list(HEADER)
        header[1] = None
        contract = copy.deepcopy(CONTRACT)
        contract["headerOverlays"] = [
            {
                "participant": "S42",
                "headerSha256": task.digest(header),
                "columns": [
                    {
                        "index": 1,
                        "name": "RecallType",
                        "originalCellSha256": task.digest(None),
                    }
                ],
            }
        ]
        rows = [(1, header), (3, [0, 1, 3, None, "synthetic"])]

        def get(**kw):
            return task.normalize(rows, "S42", SHA, contract, **kw)

        self.assertTrue(get()[0].eligible)
        self.assertFalse(get(overlays=False)[0].eligible)
        self.assertEqual(get()[0].header_overlays, ("RecallType",))
        for name, sha in (("S43", SHA), ("S42", "b" * 64)):
            with self.assertRaises(ValueError):
                task.normalize(rows, name, sha, contract)
        rows[0][1][0] = "different"
        with self.assertRaisesRegex(ValueError, "signature"):
            get()

    def test_physical_xlsx_rows_do_not_collapse_gaps(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "synthetic.xlsx"
            workbook(
                path,
                {
                    "S1": [
                        (1, HEADER),
                        (4, [0, 1, 3, None, "synthetic"]),
                        (10, [5, 1, 7, None, "synthetic"]),
                    ]
                },
            )
            rows = sheet_rows(path, "S1", indexed=True)
            self.assertEqual([n for n, _ in rows], [1, 4, 10])
            self.assertEqual(sheet_rows(path, "S1"), [r for _, r in rows])
            self.assertEqual(
                [u.excel_row for u in task.normalize(rows, "S1", SHA, CONTRACT)],
                [4, 10],
            )
            workbook(path, {"S1": [(1, HEADER), (1, [0, 1, 3, None, "synthetic"])]})
            with self.assertRaisesRegex(ValueError, "increasing"):
                sheet_rows(path, "S1", indexed=True)

    def test_duplicate_transcript_heading_overlay_requires_unused_column_empty(self):
        header = HEADER + ["Transcript "]
        contract = copy.deepcopy(CONTRACT)
        contract["headerOverlays"] = [
            {
                "participant": "S72",
                "headerSha256": task.digest(header),
                "columns": [
                    {
                        "index": 4,
                        "name": "UnusedTranscriptColumn",
                        "mustBeEmpty": True,
                        "originalCellSha256": task.digest("Transcript"),
                    },
                    {
                        "index": 5,
                        "name": "Transcript",
                        "originalCellSha256": task.digest("Transcript "),
                    },
                ],
            }
        ]
        rows = [(1, header), (2, [0, 1, 3, None, None, "synthetic"])]
        units = task.normalize(rows, "S72", SHA, contract)
        self.assertTrue(units[0].eligible)
        strict = task.normalize(rows, "S72", SHA, contract, overlays=False)
        self.assertFalse(strict[0].eligible)
        self.assertEqual(units[0].unit_id, strict[0].unit_id)
        for value in ("conflicting synthetic", CellError("#VALUE!")):
            # Check all physical rows, including rows ineligible for accuracy.
            changed = rows + [(9, [None, 5, None, None, value, None])]
            with self.assertRaisesRegex(ValueError, "unused transcript column"):
                task.normalize(changed, "S72", SHA, contract)

    def test_stratified_draw_is_order_invariant_and_uses_conditions(self):
        groups = {str(c): [f"S{(c-1)*4+i}" for i in range(1, 5)] for c in range(1, 5)}
        counts = {str(c): 2 for c in range(1, 5)}
        first = task.draw(groups, 20260921, counts)
        # Independent byte assembly, not the implementation's formatted string.
        expected = set()
        for condition, names in groups.items():
            ranked = sorted(
                names,
                key=lambda name: hashlib.sha256(
                    bytes([10]).join(
                        x.encode("utf-8")
                        for x in ["memento-split/v1", "20260921", condition, name]
                    )
                ).digest(),
            )
            expected.update(ranked[:2])
        self.assertEqual(set(first[1]), expected)
        self.assertEqual(
            first,
            task.draw(
                {c: list(reversed(ids)) for c, ids in groups.items()}, 20260921, counts
            ),
        )
        self.assertEqual(
            set(first[0]) | set(first[1]), {s for ids in groups.values() for s in ids}
        )
        self.assertFalse(set(first[0]) & set(first[1]))
        for ids in groups.values():
            self.assertEqual(len(set(ids) & set(first[1])), 2)
        self.assertNotEqual(first, task.draw(groups, 20260922, counts))
        groups["2"][0] = groups["1"][0]
        with self.assertRaisesRegex(ValueError, "unique"):
            task.draw(groups, 20260921, counts)

    def test_xlsx_errors_remain_invalid_populated_cells(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "synthetic.xlsx"
            workbook(
                path,
                {
                    "S1": [
                        (1, HEADER),
                        (2, [0, 1, 3, CellError("#VALUE!"), "synthetic"]),
                        (3, [None, CellError("#N/A"), None, None, None]),
                        (4, [0, 1, 3, None, CellError("#VALUE!")]),
                    ]
                },
            )
            units = task.normalize(
                sheet_rows(path, "S1", indexed=True), "S1", SHA, CONTRACT
            )
            self.assertEqual(len(units), 3)
            self.assertTrue(all(not u.eligible for u in units))
            self.assertIn(task.Reason.INVALID_SCENE, units[0].reasons)
            self.assertIn(task.Reason.INVALID_CODE, units[1].reasons)
            self.assertIn(task.Reason.MISSING_TEXT, units[2].reasons)
            self.assertIsNone(sheet_rows(path, "S1")[1][3])

    def test_xlsx_cell_coordinates_must_agree_and_be_unique(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "synthetic.xlsx"
            for replacement in ('r="C3"', 'r="B2"'):
                workbook(path, {"S1": [(1, HEADER), (2, [0, 1, 3, None, "synthetic"])]})
                with zipfile.ZipFile(path) as original:
                    entries = {
                        name: original.read(name) for name in original.namelist()
                    }
                key = "xl/worksheets/s1.xml"
                entries[key] = entries[key].replace(b'r="C2"', replacement.encode())
                with zipfile.ZipFile(path, "w") as changed:
                    for name, content in entries.items():
                        changed.writestr(name, content)
                with self.assertRaisesRegex(ValueError, "coordinate"):
                    sheet_rows(path, "S1", indexed=True)

    def test_power_agrees_with_independent_scipy_reference(self):
        # scipy.stats.nct + brentq on [0,1.5], independently agreeing with R 4.5.1 pt + uniroot.
        from friends_split import paired_mde_effect_size

        references = {
            14: 0.8101466719187722,
            15: 0.7780225697856632,
            17: 0.7238580380076353,
        }
        for n, expected in references.items():
            self.assertAlmostEqual(paired_mde_effect_size(n)[0], expected, places=5)


if __name__ == "__main__":
    unittest.main()
