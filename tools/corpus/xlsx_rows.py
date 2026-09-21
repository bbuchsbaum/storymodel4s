#!/usr/bin/env python3
"""Minimal read-only xlsx row reader, standard library only.

The recall-study tools are stdlib-only on purpose: an input format reader is part of the replay
receipt, and a pinned third-party dependency would be one more identity to admit. An xlsx file is a
zip of XML, and the sheets this project reads use only shared strings, inline strings, numbers and
formula-cached values, so a small reader is enough and is exactly auditable.

Not a general xlsx implementation. It does not evaluate formulas (it returns the cached value), does
not apply number formats, and returns date-formatted cells as their raw serial number. Callers that
need a date must say so; this project's corpora encode times as text or as `min.sec` decimals, and
silently converting them is precisely the bug this reader avoids.

`sheet_rows(path, sheet)` yields one list per row, padded to the sheet's widest column, with None
for empty cells.
"""
from dataclasses import dataclass
import re
import zipfile
from xml.etree import ElementTree

_NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
_REL_NS = "{http://schemas.openxmlformats.org/package/2006/relationships}"
_CELL_RE = re.compile(r"^([A-Z]+)(\d+)$")


@dataclass(frozen=True)
class CellError:
    """A populated invalid cell, distinct from missing data in indexed reads."""

    code: str | None


def _col_index(ref):
    """'A' -> 0, 'AB' -> 27."""
    m = _CELL_RE.match(ref)
    letters = m.group(1) if m else ref
    n = 0
    for ch in letters:
        n = n * 26 + (ord(ch) - ord("A") + 1)
    return n - 1


def _shared_strings(zf):
    if "xl/sharedStrings.xml" not in zf.namelist():
        return []
    root = ElementTree.fromstring(zf.read("xl/sharedStrings.xml"))
    out = []
    for si in root.findall(f"{_NS}si"):
        # concatenate every text run, skipping phonetic guides (rPh)
        rph = {id(t) for r in si.findall(f"{_NS}rPh") for t in r.iter(f"{_NS}t")}
        parts = [t.text or "" for t in si.iter(f"{_NS}t") if id(t) not in rph]
        out.append("".join(parts))
    return out


def sheet_names(path):
    with zipfile.ZipFile(path) as zf:
        root = ElementTree.fromstring(zf.read("xl/workbook.xml"))
        return [s.get("name") for s in root.iter(f"{_NS}sheet")]


def _sheet_path(zf, sheet):
    book = ElementTree.fromstring(zf.read("xl/workbook.xml"))
    rels = ElementTree.fromstring(zf.read("xl/_rels/workbook.xml.rels"))
    target = {r.get("Id"): r.get("Target") for r in rels.iter(f"{_REL_NS}Relationship")}
    for s in book.iter(f"{_NS}sheet"):
        if s.get("name") == sheet:
            rid = s.get(
                "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"
            )
            t = target[rid]
            return t if t.startswith("xl/") else "xl/" + t.lstrip("/")
    raise KeyError(f"no sheet named {sheet!r} in {zf.filename}")


def sheet_rows(path, sheet, *, indexed=False):
    """Read padded rows; `indexed=True` retains and validates physical Excel row numbers."""
    with zipfile.ZipFile(path) as zf:
        shared = _shared_strings(zf)
        data = zf.read(_sheet_path(zf, sheet))
    root = ElementTree.fromstring(data)
    rows = []
    row_numbers = []
    width = 0
    for r in root.iter(f"{_NS}row"):
        if indexed:
            number = int(r.get("r", "0"))
            if number <= 0 or (row_numbers and number <= row_numbers[-1]):
                raise ValueError(
                    "XLSX physical row numbers must be positive and increasing"
                )
            row_numbers.append(number)
        cells = {}
        seen = set()
        for c in r.findall(f"{_NS}c"):
            ref = c.get("r") or ""
            if indexed:
                match = _CELL_RE.fullmatch(ref)
                if not match or int(match.group(2)) != number or ref in seen:
                    raise ValueError("invalid or duplicate XLSX cell coordinate")
                seen.add(ref)
            idx = _col_index(ref)
            ctype = c.get("t")
            if ctype == "inlineStr":
                node = c.find(f"{_NS}is")
                val = (
                    "".join(t.text or "" for t in node.iter(f"{_NS}t"))
                    if node is not None
                    else None
                )
            else:
                v = c.find(f"{_NS}v")
                if ctype == "e" and indexed:
                    val = CellError(v.text if v is not None else None)
                elif v is None or v.text is None:
                    val = None
                elif ctype == "s":
                    val = shared[int(v.text)]
                elif ctype == "str":
                    val = v.text
                elif ctype == "b":
                    val = v.text == "1"
                elif ctype == "e":
                    val = None  # error cell
                else:
                    txt = v.text
                    try:
                        val = float(txt)
                        if (
                            val.is_integer()
                            and "." not in txt
                            and "E" not in txt.upper()
                        ):
                            val = int(val)
                    except ValueError:
                        val = txt
            if val is not None and val != "":
                cells[idx] = val
        width = max(width, (max(cells) + 1) if cells else 0)
        rows.append(cells)
    out = [[row.get(i) for i in range(width)] for row in rows]
    while out and all(v is None for v in out[-1]):
        out.pop()
    return list(zip(row_numbers, out)) if indexed else out


def serial_time_seconds(value):
    """Seconds within a day for a date-formatted cell returned as an Excel serial number.

    An xlsx time cell is a fraction of a day. This reader deliberately does not apply number
    formats, so a caller that knows a column is a time must convert it here rather than treat the
    float as data. Returns None for a non-numeric value; raises on a negative serial.
    """
    if value is None or isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    if value < 0:
        raise ValueError(f"negative Excel serial: {value!r}")
    return round((float(value) % 1.0) * 86400.0, 3)
