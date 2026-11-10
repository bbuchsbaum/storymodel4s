"""Tests for the timed-source exporter, on invented films.

Run from the repository root: python3 tools/filmfestival/test_ff_export_source.py
"""

import csv
import io
import json
import os
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_export_source  # noqa: E402
import ff_gold  # noqa: E402

TITLE = "X in bold white text " + ff_gold.TITLE_MARK
COLUMNS = [
    "segment",
    "part_id",
    "run",
    "film",
    "scene_number",
    "coarse_start_s",
    "start_s",
    "end_s",
    "description",
]


def films():
    """Two runs, each a cartoon intro and five films: twelve collections, ten of them films.

    Every film opens with a title-only scene and has one content scene. The third collection's
    content scene also holds a title row (a mixed scene). The last row of each run has no end.
    Row texts carry a comma and a quote mark, which the CSV must survive.
    """
    rows, scene, segment, position = [], 0, 0, 0
    for run in (1, 2):
        clock = 0

        def add(film, text, timed=True):
            nonlocal segment, clock
            segment += 1
            end = str(clock + 5) if timed else ""
            rows.append(
                [
                    str(segment),
                    f"run-0{run}",
                    f"Run {run}",
                    film,
                    str(scene),
                    str(clock),
                    str(clock),
                    end,
                    text,
                ]
            )
            clock += 5

        position += 1
        scene += 1
        add(f"{position}. Cartoon intro {run}", 'ZZ snacks sing, "la la"')
        for n in range(5):
            position += 1
            film = f"{position}. Invented Film {position}"
            scene += 1
            add(film, TITLE)
            scene += 1
            if position == 3:
                add(film, TITLE)
            add(film, f'ZZ person {position} says "hello", then leaves')
        rows[-1][7] = ""
    return rows


def write(directory, rows):
    path = pathlib.Path(directory) / "annotation.tsv"
    with open(path, "w", newline="", encoding="utf-8") as handle:
        handle.write("\t".join(COLUMNS) + "\n")
        for row in rows:
            handle.write("\t".join(row) + "\n")
    return str(path)


class ExportSource(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.rows = films()
        self.path = write(self.tmp.name, self.rows)

    def test_counts_and_targets_equal_the_dictionary(self):
        out, parts, facts = ff_export_source.build(self.path)
        film_of, valid = ff_gold.load_dictionary(self.path)
        self.assertEqual(facts["rows"], len(self.rows))
        self.assertEqual(facts["groups"], 22)
        self.assertEqual(facts["collections"], 12)
        self.assertEqual(facts["targetGroups"], sorted(valid))
        self.assertEqual(len(valid), 10)
        # A film's collection is its film number; the cartoon intros take 11 and 12.
        self.assertEqual(facts["targetCollections"], list(range(1, 11)))
        cartoon = sorted({int(r[9]) for r in out if r[10].startswith("Cartoon intro")})
        self.assertEqual(cartoon, [11, 12])
        self.assertEqual(
            facts["collectionOfFilmCode"],
            {str(code): c for code, c in zip(range(1, 11), facts["targetCollections"])},
        )
        self.assertEqual(len(out), len(self.rows))

    def test_cartoon_and_title_rows_are_marked_row_by_row(self):
        out, _, _ = ff_export_source.build(self.path)
        film_of, valid = ff_gold.load_dictionary(self.path)
        by_scene = {}
        for row in out:
            by_scene.setdefault(int(row[5]), []).append(row)
        for row in out:
            title = ff_gold.TITLE_MARK in row[1]
            expected = "" if int(row[5]) in valid and not title else "no"
            self.assertEqual(row[11], expected, row[0])
        cartoon = [r for r in out if r[10].startswith("Cartoon intro")]
        self.assertEqual(len(cartoon), 2)
        self.assertEqual({r[11] for r in cartoon}, {"no"})
        # One scene mixes a title row with a content row: only the title row is marked, so the
        # scene is still a target and its first target row is the content row.
        mixed = [rows for rows in by_scene.values() if len({r[11] for r in rows}) == 2]
        self.assertEqual(len(mixed), 1)
        self.assertIn(ff_gold.TITLE_MARK, mixed[0][0][1])
        self.assertEqual([r[11] for r in mixed[0]], ["no", ""])
        self.assertIn(int(mixed[0][0][5]), valid)
        # A scene is a target exactly when any of its rows is.
        targets = {
            scene for scene, rows in by_scene.items() if any(r[11] == "" for r in rows)
        }
        self.assertEqual(targets, set(valid))

    def test_each_films_first_target_row_is_its_first_row_that_is_not_a_title_card(
        self,
    ):
        out, _, _ = ff_export_source.build(self.path)
        first = {}
        for row in out:
            if row[11] == "":
                first.setdefault(row[9], row)
        for row in first.values():
            self.assertNotIn(ff_gold.TITLE_MARK, row[1])
        earlier_titles = [
            r
            for r in out
            if r[11] == "no" and r[9] in first and int(r[0]) < int(first[r[9]][0])
        ]
        self.assertTrue(earlier_titles)

    def test_groups_collections_and_labels(self):
        out, _, _ = ff_export_source.build(self.path)
        self.assertEqual(out[0][5:7], ["1", "scene 1"])
        # The first row is the first cartoon intro (collection 11); the last film in presentation
        # order is the tenth film (collection 10), whatever its position among the twelve.
        self.assertEqual(out[0][9:11], ["11", "Cartoon intro 1"])
        self.assertEqual(out[-1][9:11], ["10", "Invented Film 12"])
        self.assertEqual([r[0] for r in out], [str(n) for n in range(1, len(out) + 1)])
        self.assertEqual({r[2] for r in out}, {"run-01", "run-02"})

    def test_parts_cover_every_row_and_a_final_point_event(self):
        out, parts, _ = ff_export_source.build(self.path)
        self.assertEqual([p["part"] for p in parts], ["run-01", "run-02"])
        for part in parts:
            self.assertEqual(
                (part["start"], part["numerator"], part["denominator"]), ("0", "1", "1")
            )
            rows = [r for r in out if r[2] == part["part"]]
            last = rows[-1]
            self.assertEqual(last[4], "")
            self.assertEqual(int(part["end"]), int(last[3]) + 1)
            for r in rows:
                self.assertLess(int(r[3]), int(part["end"]))
                if r[4]:
                    self.assertLessEqual(int(r[4]), int(part["end"]))

    def test_csv_text_reads_back_cell_for_cell(self):
        out, _, _ = ff_export_source.build(self.path)
        text = ff_export_source.render(out)
        self.assertTrue(text.endswith("\n"))
        self.assertNotIn("\r", text)
        back = list(csv.reader(io.StringIO(text, newline="")))
        self.assertEqual(back[0], ff_export_source.HEADER)
        self.assertEqual(back[1:], out)
        self.assertEqual(out[0][1], 'ZZ snacks sing, "la la"')

    def test_export_writes_three_files_and_refuses_an_existing_directory(self):
        target = os.path.join(self.tmp.name, "out")
        receipt = ff_export_source.export(self.path, target)
        self.assertEqual(
            sorted(os.listdir(target)),
            ["annotations.csv", "export-receipt.json", "parts.json"],
        )
        with open(os.path.join(target, "annotations.csv"), "rb") as handle:
            data = handle.read()
        self.assertEqual(ff_export_source.sha(data), receipt["csvSha256"])
        with open(os.path.join(target, "parts.json"), encoding="utf-8") as handle:
            self.assertEqual(json.load(handle), receipt["parts"])
        with open(
            os.path.join(target, "export-receipt.json"), encoding="utf-8"
        ) as handle:
            text = handle.read()
        self.assertEqual(json.loads(text), receipt)
        self.assertNotIn("ZZ", text)
        with self.assertRaises(ff_export_source.ExportError):
            ff_export_source.export(self.path, target)
        self.assertEqual(ff_export_source.main(["x", "export", self.path, target]), 2)

    def test_check_prints_counts_without_text(self):
        lines = ff_export_source.summary(ff_export_source.build(self.path)[2])
        self.assertEqual(
            lines[:6],
            [
                f"rows={len(self.rows)}",
                f"targetRows={10}",
                "groups=22",
                "collections=12",
                "targetGroupCount=10",
                "targetCollectionCount=10",
            ],
        )
        self.assertFalse(any("ZZ" in line for line in lines))

    def refused(self, change):
        rows = films()
        change(rows)
        path = write(tempfile.mkdtemp(dir=self.tmp.name), rows)
        with self.assertRaises(ff_export_source.ExportError):
            ff_export_source.build(path)

    def test_a_fractional_time_refuses(self):
        self.refused(lambda rows: rows[3].__setitem__(6, "10.5"))

    def test_an_end_before_its_start_refuses(self):
        self.refused(lambda rows: rows[3].__setitem__(7, rows[3][6]))

    def test_a_repeated_segment_refuses(self):
        self.refused(lambda rows: rows[3].__setitem__(0, rows[2][0]))

    def test_a_film_name_without_its_position_refuses(self):
        def rename(rows):
            for row in rows:
                if row[3].startswith("2. "):
                    row[3] = "Invented Film 2"

        self.refused(rename)


if __name__ == "__main__":
    unittest.main()
