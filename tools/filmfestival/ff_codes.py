#!/usr/bin/env python3
"""Extract the raw movie and scene codes from the FilmFestival recall workbooks.

The derived reference file writes an utterance with a film but no scene as empty `scenes`. Two
different codes end up there: the film-only code (`movie*1000`, e.g. a title mention) and scene
`-1`, which the coding scheme defines as memory search within a film. The protocol scores them
apart, so the raw codes are needed.

Usage: ff_codes.py WORKBOOK_DIR OUTPUT_TSV
Reads `sub-NN_recall.xlsx` (session run-01) and `sub-NN_recall{1,2}.xlsx` (run-01, run-02) with
the standard library only: the first sheet's columns A (movies) and B (scenes), by Excel row.
"""

import os
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
NAME = re.compile(r"^(sub-\d+)_recall([12]?)\.xlsx$")


def cells(path):
    """{(column letters, row number): text} for the first worksheet."""
    with zipfile.ZipFile(path) as z:
        shared = []
        if "xl/sharedStrings.xml" in z.namelist():
            root = ET.fromstring(z.read("xl/sharedStrings.xml"))
            for si in root.findall("m:si", NS):
                shared.append("".join(t.text or "" for t in si.iter(f"{{{NS['m']}}}t")))
        sheet = ET.fromstring(z.read("xl/worksheets/sheet1.xml"))
    out = {}
    for c in sheet.iter(f"{{{NS['m']}}}c"):
        ref = c.get("r")
        column, row = re.match(r"([A-Z]+)(\d+)", ref).groups()
        v = c.find("m:v", NS)
        if c.get("t") == "inlineStr":
            text = "".join(t.text or "" for t in c.iter(f"{{{NS['m']}}}t"))
        elif v is None:
            continue
        elif c.get("t") == "s":
            text = shared[int(v.text)]
        else:
            text = v.text
        out[(column, int(row))] = text
    return out


def integral(text):
    """A cell holding one integer (Excel may write 3000 as 3000.0)."""
    value = float(text)
    if value != int(value):
        raise ValueError(f"not an integer: {text}")
    return int(value)


def rows(directory):
    """(participant, session, excel row, movie code, raw scenes text) for every coded utterance."""
    out = []
    for name in sorted(os.listdir(directory)):
        match = NAME.match(name)
        if not match:
            continue
        participant, which = match.groups()
        session = "run-02" if which == "2" else "run-01"
        table = cells(os.path.join(directory, name))
        if table.get(("A", 1)) != "movies" or table.get(("B", 1)) != "scenes":
            raise ValueError(f"{name}: unexpected header")
        last = max(row for column, row in table if column == "A")
        for row in range(2, last + 1):
            if ("A", row) not in table:
                continue
            out.append(
                (
                    participant,
                    session,
                    row,
                    integral(table[("A", row)]),
                    table.get(("B", row), ""),
                )
            )
    return out


def main(argv):
    if len(argv) != 2 or os.path.exists(argv[1]):
        print(__doc__, file=sys.stderr)
        return 2
    found = rows(argv[0])
    with open(argv[1], "w", encoding="utf-8") as handle:
        handle.write("participant\trun\tgold_row\tmovie\tscenes_raw\n")
        for r in found:
            handle.write("\t".join(str(x) for x in r) + "\n")
    print(f"rows={len(found)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
