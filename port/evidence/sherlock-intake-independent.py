"""Independent review controls; expected relationships do not use production helpers."""

import hashlib
import json
from pathlib import Path

import pytest

from storymodel.sherlock_source import prepare_sherlock_source

HEADER = (
    "Segment Number\t"
    "Start Time (s) \t"
    "End Time (s) \t"
    "Start TR\t"
    "End TR\t"
    "Scene Segments\t"
    "Scene Details - A Level \t"
    "Space-In/Outdoor\t"
    "Name - All\t"
    "Name - Focus\t"
    "Name - Speaking\t"
    "Location\t"
    "Camera Angle\t"
    "Music Presence \t"
    "Words on Screen "
)
ROWS = (
    "1\t0\t10\t1\t7\t1. A\tAA BB\tOutdoor\tAA\tAA\t\tL\tLong\tNo\t",
    "2\t10\t20\t8\t14\t\tCC DD\tOutdoor\tBB\t\tBB\tL\tMedium\tNo\t",
    "3\t20\t20\t14\t14\t\tEE FF\tIndoor\t\t\t\t\tClose\tNo\t",
    "4\t20\t30\t\t\t\tGG HH\tIndoor\t\t\t\t\tLong\tNo\t",
    "5\t0\t5\t21\t24\t2. B\tII JJ\tIndoor\tAA / BB\tAA\tAA / BB\tL\tLong\tYes\t",
    "6\t5\t12\t25\t29\t\tKK LL\tIndoor\tBB\t\tBB\tL\tMedium\tYes\t",
)


def raw():
    return "\n".join((HEADER, *ROWS)).encode()


def repair():
    return json.loads(Path("examples/fixtures/sherlock-synthetic-repair.json").read_bytes())


def prepare(r, d):
    return prepare_sherlock_source("review", r, json.dumps(d).encode())


def test_valid_control():
    source = prepare(raw(), repair())
    assert source.loci[4].start_tick == 0
    assert source.rows[2].raw_end_seconds == source.rows[3].raw_start_seconds == 20


def test_external_raw_pin_control():
    with pytest.raises(ValueError):
        prepare(raw() + b"\n", repair())


def test_scan_break_must_start_at_ordinary_end():
    r = raw().replace(b"4\t20\t30", b"4\t21\t30")
    d = repair()
    d["inputAnnotation"]["sha256"] = hashlib.sha256(r).hexdigest()
    d["missingnessAndTails"]["scanBreakRows"][0]["startSeconds"] = 21
    with pytest.raises(ValueError):
        prepare(r, d)


def test_edition_binding_must_be_established():
    d = repair()
    d["coordinateSystems"][0]["experimentalEditionBinding"] = "unestablished"
    with pytest.raises(ValueError):
        prepare(raw(), d)


def test_notebook_provenance_cannot_be_missing_on_both_sides():
    d = repair()
    d["repair"].pop("id")
    d["coordinateSystems"][3].pop("derivationReceipt")
    with pytest.raises(ValueError):
        prepare(raw(), d)


def test_offset_must_equal_actual_last_ordinary_row_end():
    d = repair()
    d["repair"]["offsetSeconds"]["run-2"] = 19
    d["coordinateSystems"][1]["runs"][0]["ordinaryEndSeconds"] = 19
    d["coordinateSystems"][3]["endSeconds"] = 31
    d["missingnessAndTails"]["partA"]["retainedNotebookAnnotationEndSeconds"] = 19
    d["missingnessAndTails"]["partA"]["retainedNotebookAnnotationToMediaTailSeconds"] = 11.4
    for b in d["repair"]["boundaryChecks"][1:]:
        b["repairedStartSeconds"] = b["rawStartSeconds"] + 19
        b["repairedEndSeconds"] = b["rawEndSeconds"] + 19
    # Literal independently expected serialization, with offset19 and unchanged ordinary end20.
    replay = (
        "0001\trun-1\t0\t10\t0\t10\t1\t7\n"
        "0002\trun-1\t10\t20\t10\t20\t8\t14\n"
        "0003\trun-1\t20\t20\t20\t20\t14\t14\n"
        "0005\trun-2\t0\t5\t19\t24\t21\t24\n"
        "0006\trun-2\t5\t12\t24\t31\t25\t29\n"
    )
    d["repair"]["serializationReceipt"]["sha256"] = hashlib.sha256(replay.encode()).hexdigest()
    with pytest.raises(ValueError):
        prepare(raw(), d)
