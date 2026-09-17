#!/usr/bin/env python3
"""The descriptor must refuse, never default -- and must keep the Sherlock numbers unchanged."""
import json
from pathlib import Path
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import corpus_descriptor as cd  # noqa: E402

FAILURES = []


def check(name, condition):
    if not condition:
        FAILURES.append(name)


def write(doc):
    handle = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
    json.dump(doc, handle)
    handle.close()
    return handle.name


SHERLOCK = {
    "schema": cd.SCHEMA,
    "schemaVersion": cd.SCHEMA_VERSION,
    "corpus": "sherlock",
    "partOffsets": {
        # the two senses that four files conflated under one name
        cd.ORDERING_ONLY: {"media-part-a": 0.0, "media-part-b": 100000.0},
        cd.ELAPSED_TIME: {"media-part-a": 0.0, "media-part-b": 1426.0},
    },
    "goldRule": {
        "trSeconds": 1.5,
        "excludedParticipants": ["NN01"],
        "participantPattern": r"^NN(\d{2})_",
    },
}


def main():
    path = write(SHERLOCK)
    doc = cd.load(path)

    # the literals the scorers carry today, now declared and distinguished
    ordering = cd.part_offsets(doc, cd.ORDERING_ONLY)
    elapsed = cd.part_offsets(doc, cd.ELAPSED_TIME)
    check("ordering sentinel preserved", ordering["media-part-b"] == 100000.0)
    check("elapsed offset preserved", elapsed["media-part-b"] == 1426.0)
    check("the two kinds differ", ordering != elapsed)

    rule = cd.gold_rule(doc)
    check("TR preserved", rule["trSeconds"] == 1.5)
    check("exclusion preserved", rule["excludedParticipants"] == ["NN01"])

    # a corpus that declares only ordering sentinels must REFUSE an elapsed-time request rather
    # than hand back a sentinel that would silently produce a meaningless duration
    partial = dict(SHERLOCK)
    partial["partOffsets"] = {cd.ORDERING_ONLY: {"a": 0.0, "b": 100000.0}}
    doc2 = cd.load(write(partial))
    try:
        cd.part_offsets(doc2, cd.ELAPSED_TIME)
        check("refuses an undeclared offset kind", False)
    except cd.DescriptorError:
        check("refuses an undeclared offset kind", True)

    # a missing or wrong-schema descriptor refuses; it never defaults
    for bad in ({"schema": "other", "schemaVersion": 1}, {"schema": cd.SCHEMA, "schemaVersion": 99}):
        try:
            cd.load(write(bad))
            check("refuses a bad schema", False)
        except cd.DescriptorError:
            check("refuses a bad schema", True)
    try:
        cd.load("/definitely/not/here.json")
        check("refuses a missing descriptor", False)
    except cd.DescriptorError:
        check("refuses a missing descriptor", True)

    # MUTANT: changing a descriptor value must change what a consumer reads. If it did not, the
    # descriptor would be decoration and the constants would still be effectively hardcoded.
    mutated = json.loads(json.dumps(SHERLOCK))
    mutated["partOffsets"][cd.ELAPSED_TIME]["media-part-b"] = 999.0
    moved = cd.part_offsets(cd.load(write(mutated)), cd.ELAPSED_TIME)
    check("a changed descriptor changes what is read", moved["media-part-b"] == 999.0)
    check("and differs from the original", moved != elapsed)

    if FAILURES:
        print("FAILED: " + "; ".join(FAILURES))
        return 1
    print("ok: descriptor refuses rather than defaults; Sherlock constants preserved and distinguished")
    return 0


if __name__ == "__main__":
    sys.exit(main())
