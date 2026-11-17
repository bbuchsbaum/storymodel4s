"""Synthetic Sherlock TSV/CSV contracts through the shared recorded analysis example.

Only abstract AA/BB token controls and the existing admitted synthetic clock metadata
are used. No story, participant text, media bytes or model service is opened.
"""

import csv
import io
import json
import sys
from hashlib import sha256
from pathlib import Path

from prepared_workflow import analyze_recalls, record_construction

from storymodel.artifacts import publish_json
from storymodel.prepared_construction import prepare_sherlock_construction
from storymodel.sherlock_recall import (
    SHERLOCK_RECALL_HEADER,
    prepare_sherlock_recall,
    sherlock_recall_timing,
)
from storymodel.sherlock_source import prepare_sherlock_source


def source_inputs():
    header = (
        "Segment Number\tStart Time (s) \tEnd Time (s) \tStart TR\tEnd TR\tScene Segments\t"
        "Scene Details - A Level \tSpace-In/Outdoor\tName - All\tName - Focus\tName - Speaking\t"
        "Location\tCamera Angle\tMusic Presence \tWords on Screen "
    )
    rows = [
        (1, 0, 10, 1, 7, "1. A", "AA BB"),
        (2, 10, 20, 8, 14, "", "CC DD"),
        (3, 20, 20, 14, 14, "", "EE FF"),
        (4, 20, 30, "", "", "", "GG HH"),
        (5, 0, 5, 21, 24, "2. B", "II JJ"),
        (6, 5, 12, 25, 29, "", "KK LL"),
    ]
    raw = (
        header
        + "\n"
        + "\n".join(
            "\t".join(str(cell) for cell in (*row, "Indoor", "A/B", "A", "B", "L", "", "", ""))
            for row in rows
        )
        + "\n"
    ).encode()
    repair = json.loads(
        (Path(__file__).parent / "fixtures/sherlock-synthetic-repair.json").read_bytes()
    )
    repair["inputAnnotation"]["sha256"] = sha256(raw).hexdigest()
    return raw, json.dumps(repair, ensure_ascii=False, indent=2).encode()


def recall_inputs(number):
    stream = io.StringIO(newline="")
    writer = csv.writer(stream, lineterminator="\r\n" if number == 1 else "\n")
    writer.writerow(SHERLOCK_RECALL_HEADER)
    writer.writerow(("AA", "1.25" if number == 1 else "", "1", "0", "101.25", "10", "100"))
    writer.writerow(("BB", "2.5", "2", "1.5", "102.5", "11", "101.5"))
    raw = stream.getvalue().encode()
    lineage = {
        "schema": "storymodel4s.sherlock.recall-lineage",
        "schemaVersion": 1,
        "publicRelease": {"tag": "synthetic-no-public-disclosure-claim", "commit": "a" * 40},
        "sources": [
            {
                "id": "synthetic-recall",
                "localCsvByteLength": len(raw),
                "localCsvSha256": sha256(raw).hexdigest(),
                "recordCount": 2,
            }
        ],
    }
    return raw, json.dumps(lineage, indent=2).encode()


def run(destination):
    destination.mkdir(parents=True, exist_ok=False)
    raw, repair = source_inputs()
    intake = prepare_sherlock_source("source", raw, repair)
    prepared = prepare_sherlock_construction(intake, export_permission="allowed")
    source = record_construction(destination / "source", prepared, raw)
    (destination / "source/repair.json").write_bytes(repair)
    assert [locus.part_id for locus in intake.loci] == ["media-part-a"] * 4 + ["media-part-b"] * 2
    assert intake.loci[2].kind == "instant" and intake.loci[4].start_tick == 0
    assert intake.rows[3].start_tr is None and intake.rows[3].end_tr is None
    recalls = []
    for number in (1, 2):
        raw, lineage = recall_inputs(number)
        recall = prepare_sherlock_recall("recall", raw, lineage, source_id="synthetic-recall")
        prepared = prepare_sherlock_construction(recall, export_permission="allowed")
        root = destination / f"recall-{number}"
        recalls.append(record_construction(root, prepared, raw))
        (root / "lineage.json").write_bytes(lineage)
        for clock in ("princeton", "openneuro"):
            timing = sherlock_recall_timing(recall, clock)
            publish_json(root / f"{clock}-timing.json", timing.model_dump(mode="json"))
    analyze_recalls(destination, source, recalls)


if __name__ == "__main__":
    run(Path(sys.argv[1]))
