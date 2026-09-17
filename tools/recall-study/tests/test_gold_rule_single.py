#!/usr/bin/env python3
"""One gold rule: the descriptor-driven constants must equal the literals they replace.

`SherlockSceneCoding.scala:14-15` states the defect outright -- "Two implementations of one rule is
one too many, so this one cites the other". This is the differential receipt that lets the second
implementation be retired: old literals and descriptor-driven values must agree on every field
BEFORE either is removed.
"""
import json
from pathlib import Path
import sys
import tempfile

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import corpus_descriptor as cd  # noqa: E402
import gold_scene  # noqa: E402

FAILURES = []


def check(name, ok):
    if not ok:
        FAILURES.append(name)


def main():
    # the literals as they stand today, captured before anything is reconfigured
    before = {"TR": gold_scene.TR, "EXCLUDED": sorted(gold_scene.EXCLUDED),
              "EXCLUDED_NUMS": sorted(gold_scene.EXCLUDED_NUMS)}

    doc = {
        "schema": cd.SCHEMA,
        "schemaVersion": cd.SCHEMA_VERSION,
        "corpus": "sherlock",
        "goldRule": {
            "trSeconds": 1.5,
            "excludedParticipants": ["NN01"],
            "participantPattern": r"^NN(\d{2})_",
        },
    }
    handle = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
    json.dump(doc, handle)
    handle.close()

    after = gold_scene.configure(handle.name)
    check("TR agrees", after["TR"] == before["TR"] == 1.5)
    check("exclusions agree", after["EXCLUDED"] == before["EXCLUDED"] == ["NN01"])
    check("excluded numbers agree", after["EXCLUDED_NUMS"] == before["EXCLUDED_NUMS"] == [1])

    # and the alias arithmetic is unchanged by reconfiguration -- the rule itself did not move
    check("alias identity below 5", gold_scene.gold_subject_of(4) == 4)
    check("alias skip at 5", gold_scene.gold_subject_of(5) is None)
    check("alias shift above 5", gold_scene.gold_subject_of(6) == 5)
    check("excluded subject refused", gold_scene.gold_subject_of(1) is None)

    # MUTANT: a descriptor with a different TR must change the rule, or the descriptor is decoration
    doc["goldRule"]["trSeconds"] = 2.0
    h2 = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
    json.dump(doc, h2)
    h2.close()
    check("a changed TR reaches the rule", gold_scene.configure(h2.name)["TR"] == 2.0)
    gold_scene.configure(handle.name)  # restore

    if FAILURES:
        print("FAILED: " + "; ".join(FAILURES))
        return 1
    print("ok: descriptor-driven gold rule agrees with the literals it replaces, field by field")
    return 0


if __name__ == "__main__":
    sys.exit(main())
