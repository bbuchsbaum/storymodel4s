#!/usr/bin/env python3
"""Each scorer declares WHICH KIND of part offset it needs, and gets that kind or an error.

Four maps, two names, two incompatible meanings, nothing declaring which is which:
  score.py     100000.0  ordering sentinel   (feeds kendall_tau_b only)
  matched.py   100000.0  the same sentinel   (feeds tau_b only)
  agreement.py   1426.0  a real elapsed time (measures gaps)
The sentinels are correct for rank statistics and wrong for durations. This test is the thing that
makes swapping one for the other impossible rather than merely inadvisable.
"""
import json
from pathlib import Path
import sys
import tempfile

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import corpus_descriptor as cd  # noqa: E402
import agreement, matched, score  # noqa: E402

FAILURES = []


def check(name, ok):
    if not ok:
        FAILURES.append(name)


def write(doc):
    h = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
    json.dump(doc, h)
    h.close()
    return h.name


BOTH = {
    "schema": cd.SCHEMA, "schemaVersion": cd.SCHEMA_VERSION, "corpus": "sherlock",
    "partOffsets": {
        cd.ORDERING_ONLY: {"media-part-a": 0.0, "media-part-b": 100000.0},
        cd.ELAPSED_TIME: {"media-part-a": 0.0, "media-part-b": 1426.0},
    },
}


def main():
    # the literals as they stand, before anything is reconfigured
    check("score keeps its sentinel", score.PART_OFFSET["media-part-b"] == 100000.0)
    check("matched keeps its sentinel", matched.PART["media-part-b"] == 100000.0)
    check("agreement keeps its real offset", agreement.PART["media-part-b"] == 1426.0)

    path = write(BOTH)
    check("score reads the ordering kind", score.configure(path)["media-part-b"] == 100000.0)
    check("matched reads the ordering kind", matched.configure(path)["media-part-b"] == 100000.0)
    check("agreement reads the elapsed kind", agreement.configure(path)["media-part-b"] == 1426.0)

    # A corpus that declares only ordering sentinels must REFUSE agreement.py, because a gap
    # measured against a sentinel is meaningless. This is the swap that was previously silent.
    ordering_only = {**BOTH, "partOffsets": {cd.ORDERING_ONLY: {"a": 0.0, "b": 9.0}}}
    p2 = write(ordering_only)
    check("score still works", score.configure(p2)["b"] == 9.0)
    try:
        agreement.configure(p2)
        check("agreement refuses a sentinel-only corpus", False)
    except cd.DescriptorError:
        check("agreement refuses a sentinel-only corpus", True)

    # and the converse: a corpus with only real offsets refuses the rank-statistic scorers
    elapsed_only = {**BOTH, "partOffsets": {cd.ELAPSED_TIME: {"a": 0.0, "b": 7.0}}}
    p3 = write(elapsed_only)
    check("agreement works", agreement.configure(p3)["b"] == 7.0)
    try:
        score.configure(p3)
        check("score refuses an elapsed-only corpus", False)
    except cd.DescriptorError:
        check("score refuses an elapsed-only corpus", True)

    score.configure(path); matched.configure(path); agreement.configure(path)
    if FAILURES:
        print("FAILED: " + "; ".join(FAILURES))
        return 1
    print("ok: each scorer declares its offset kind and cannot be handed the other")
    return 0


if __name__ == "__main__":
    sys.exit(main())
