#!/usr/bin/env python3
"""Replay one Film Festival annotation workbook into a TSV, with a receipt.

The Film Festival annotations ship as three coders' xlsx in `jchenlab-jhu/filmfest` (identity in
`docs/data/filmfestival/source-manifest.json`). This tool is the workbook-to-TSV replay, the same
shape Sherlock's `annotation-lineage.json` records: the TSV is derived, never authored, and the
receipt states what was read, what was written, and every anomaly found on the way.

Three things about this corpus that a naive reader gets wrong, all handled here:

* Times are **run-relative** and written as `min.sec` decimals, so `6.31` is 6:31 and arrives from
  the sheet as `6.1000000000000005`. They are formatted to two decimals and split, never treated as
  numbers.
* Coarse **segment numbers restart at 1 in run 2**, while the recall-to-scene gold
  (`recall_scenematched`) uses one global 1..216 space. The run-1 coarse count is measured from the
  file and added to every run-2 number; it is asserted to be 106 and the total asserted to be 216.
* Only rows carrying a fine-grained start are annotated segments. Some rows exist to carry a coarse
  boundary and say `not annotated`; they are counted and excluded, not silently dropped.

Usage: filmfest_annotation.py CODER OUT_TSV [RECEIPT_JSON]
       CODER is JL, KM or RC. Paths resolve under the data root (tools/data-root.sh).

The TSV carries annotation prose and is therefore local-sensitive: write it under the data root,
never into Git.
"""
import hashlib
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from xlsx_rows import sheet_rows  # noqa: E402

CODERS = ("JL", "KM", "RC")
RUN_PARTS = {"Run 1": "run-01", "Run 2": "run-02"}
EXPECTED_RUN1_COARSE = 106
EXPECTED_TOTAL_COARSE = 216


def data_root():
    here = os.path.dirname(os.path.abspath(__file__))
    out = subprocess.run(
        ["bash", os.path.join(here, "..", "data-root.sh")],
        capture_output=True,
        text=True,
    )
    return out.stdout.strip() or os.environ.get("STORYMODEL4S_DATA", "data")


def minsec_to_seconds(value, where, normalizations=None):
    """`6.31` (6 min 31 s) -> 391. Formats to two decimals first; the sheet stores dirty floats.

    A seconds field of exactly 60 is a rollover the coders actually wrote (RC records `1.6`, meaning
    1:60, between 1:55 and 2:04). It is carried to the next minute and counted, not guessed at and
    not silently accepted. Anything above 60 is an error, because nothing in these files reads as a
    coherent time.
    """
    if value is None or value == "":
        return None
    if isinstance(value, str):
        value = value.strip().replace(":", ".")
        if not value:
            return None
        value = float(value)
    text = f"{float(value):.2f}"
    minutes, _, secs = text.partition(".")
    seconds = int(secs)
    if seconds == 60:
        if normalizations is not None:
            normalizations.append(
                f"{where}: {value!r} read as {int(minutes)}:60, carried to next minute"
            )
        return (int(minutes) + 1) * 60
    if seconds > 60:
        raise ValueError(
            f"{where}: {value!r} formats to {text}, whose seconds field is > 60"
        )
    return int(minutes) * 60 + seconds


def read_workbook(path):
    rows = sheet_rows(path, "Sheet1")
    header = [str(c).strip() if c is not None else "" for c in rows[0]]
    want = ["Scanning run", "Movie title", "Segment number"]
    for i, w in enumerate(want):
        if not header[i].startswith(w):
            raise ValueError(f"unexpected column {i}: {header[i]!r}, expected {w!r}")
    return rows[1:]


def replay(path):
    out, anomalies, normalizations = [], [], []
    run = film = None
    coarse_no = coarse_start = None
    coarse_seen = {"Run 1": set(), "Run 2": set()}
    coarse_order = {"Run 1": [], "Run 2": []}
    not_annotated = 0
    for i, r in enumerate(read_workbook(path), start=2):
        cell = lambda j: r[j] if j < len(r) else None  # noqa: E731
        if cell(0):
            run = str(cell(0)).strip()
            if run not in RUN_PARTS:
                raise ValueError(f"row {i}: unknown scanning run {run!r}")
        if cell(1):
            film = str(cell(1)).strip()
        if cell(2) is not None:
            coarse_no = int(cell(2))
            coarse_start = minsec_to_seconds(
                cell(3), f"row {i} coarse start", normalizations
            )
            coarse_seen[run].add(coarse_no)
            coarse_order[run].append(coarse_no)
        fine = cell(4)
        desc = cell(5)
        if fine is None or fine == "":
            not_annotated += 1
            continue
        out.append(
            {
                "run": run,
                "part_id": RUN_PARTS[run],
                "film": film,
                "coarse_no": coarse_no,
                "coarse_start_s": coarse_start,
                "start_s": minsec_to_seconds(
                    fine, f"row {i} fine start", normalizations
                ),
                "description": ("" if desc is None else str(desc))
                .replace("\t", " ")
                .strip(),
                "source_row": i,
            }
        )
    run1 = len(coarse_seen["Run 1"])
    total = run1 + len(coarse_seen["Run 2"])

    # Does this coder's numbering carry the gold's 1..216 space? Three ways it can fail: a wrong
    # total, a run-1 count that would give the wrong run-2 offset, or numbering that is not a
    # gap-free ascending run within each scanning run.
    faults = []
    if total != EXPECTED_TOTAL_COARSE:
        faults.append(f"total coarse count {total}, expected {EXPECTED_TOTAL_COARSE}")
    if run1 != EXPECTED_RUN1_COARSE:
        faults.append(f"run-1 coarse count {run1}, expected {EXPECTED_RUN1_COARSE}")
    for rn, seq in coarse_order.items():
        if seq != list(range(1, len(seq) + 1)):
            drops = [b for a, b in zip(seq, seq[1:]) if b <= a]
            gaps = [(a, b) for a, b in zip(seq, seq[1:]) if b > a + 1]
            faults.append(
                f"{rn} numbering is not 1..n ascending: "
                f"{len(drops)} restart(s), {len(gaps)} gap(s)"
                + (f", first restart at {drops[0]}" if drops else "")
                + (f", first gap {gaps[0][0]}->{gaps[0][1]}" if gaps else "")
            )
    numbering_usable = not faults
    anomalies.extend(faults)

    # global scene id, then end times from the next fine start within the same run
    for seg in out:
        seg["scene_number"] = (
            seg["coarse_no"] + (run1 if seg["run"] == "Run 2" else 0)
            if numbering_usable
            else None
        )
    for a, b in zip(out, out[1:]):
        a["end_s"] = b["start_s"] if a["run"] == b["run"] else None
    out[-1]["end_s"] = None

    for a, b in zip(out, out[1:]):
        if a["run"] == b["run"] and b["start_s"] < a["start_s"]:
            anomalies.append(
                f"non-monotonic fine start: source row {b['source_row']} "
                f"({b['start_s']}s) precedes row {a['source_row']} ({a['start_s']}s)"
            )
    for seg in out:
        if seg["end_s"] is not None and seg["end_s"] < seg["start_s"]:
            seg["end_s"] = None
    return (
        out,
        anomalies,
        {
            "run1Coarse": run1,
            "totalCoarse": total,
            "notAnnotatedRows": not_annotated,
            "numberingUsableForGold": numbering_usable,
            "normalizations": normalizations,
        },
    )


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


def main(argv):
    if len(argv) < 3 or argv[1] not in CODERS:
        sys.exit(__doc__)
    coder, out_tsv = argv[1], argv[2]
    receipt_path = argv[3] if len(argv) > 3 else None
    src = os.path.join(
        data_root(),
        "filmfestival",
        "annotations",
        f"FilmFestival_movie_annotation_{coder}.xlsx",
    )
    raw = open(src, "rb").read()
    segments, anomalies, counts = replay(src)

    lines = ["\t".join(COLUMNS)]
    for n, s in enumerate(segments, start=1):
        s["segment"] = n
        lines.append("\t".join("" if s[c] is None else str(s[c]) for c in COLUMNS))
    body = "\n".join(lines) + "\n"
    with open(out_tsv, "w", encoding="utf-8") as fh:
        fh.write(body)

    receipt = {
        "schema": "storymodel4s.filmfestival.annotation-replay",
        "schemaVersion": 1,
        "coder": coder,
        "input": {"sha256": hashlib.sha256(raw).hexdigest(), "byteLength": len(raw)},
        "output": {
            "sha256": hashlib.sha256(body.encode("utf-8")).hexdigest(),
            "byteLength": len(body.encode("utf-8")),
            "columns": COLUMNS,
        },
        "sceneNumberingUsableForGold": counts["numberingUsableForGold"],
        "normalizations": counts["normalizations"],
        "counts": {
            "fineSegments": len(segments),
            "coarseScenes": counts["totalCoarse"],
            "run1CoarseScenes": counts["run1Coarse"],
            "run2SceneNumberOffset": counts["run1Coarse"]
            if counts["numberingUsableForGold"]
            else None,
            "notAnnotatedRows": counts["notAnnotatedRows"],
            "films": len({s["film"] for s in segments if s["film"]}),
            "segmentsWithoutEnd": sum(1 for s in segments if s["end_s"] is None),
        },
        "anomalies": anomalies,
        "nonClaims": [
            "reference-annotator-selected",
            "annotation-clock-aligned-to-events-tsv",
            "film-assignment-verified-against-presentation-schedule",
        ],
    }
    if receipt_path:
        with open(receipt_path, "w", encoding="utf-8") as fh:
            json.dump(receipt, fh, indent=2)
            fh.write("\n")
    summary = (
        "coder",
        "sceneNumberingUsableForGold",
        "counts",
        "normalizations",
        "anomalies",
    )
    json.dump({k: receipt[k] for k in summary}, sys.stdout, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main(sys.argv)
