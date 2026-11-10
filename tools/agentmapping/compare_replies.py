#!/usr/bin/env python3
"""Compare two directories of reply files unit by unit.

Usage: compare_replies.py RECORDED_DIR REPLAYED_DIR

Each directory holds <participant>_<session>.json files with `chunks` and `units`. The
comparison is of parsed values: every unit row (all its keys) and every chunk receipt must be
equal. It prints counts and the first differences, and exits 1 if anything differs.
"""

import collections
import json
import os
import sys


def load(directory):
    out = {}
    for name in sorted(os.listdir(directory)):
        if name.endswith(".json"):
            with open(os.path.join(directory, name), encoding="utf-8") as handle:
                out[name] = json.load(handle)
    return out


def kind(prediction):
    if prediction.get("status") != "answered":
        return "failed:" + str(prediction.get("reason"))
    return prediction["kind"]


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    recorded, replayed = load(argv[0]), load(argv[1])
    differences, units = [], 0
    kinds = collections.Counter()
    receipts = collections.Counter()
    if sorted(recorded) != sorted(replayed):
        differences.append(("files", sorted(set(recorded) ^ set(replayed))))
    for name in sorted(set(recorded) & set(replayed)):
        a, b = recorded[name], replayed[name]
        for key in ("participant", "session", "model"):
            if a.get(key) != b.get(key):
                differences.append((name, key))
        if a["chunks"] != b["chunks"]:
            differences.append((name, "chunks"))
        for r in a["chunks"]:
            receipts["chunks"] += 1
            receipts["attempts"] += r["attempts"]
            receipts["itemsOutsideChunk"] += r.get("itemsOutsideChunk", 0)
            receipts["repeatedUnits"] += r.get("repeatedUnits", 0)
            receipts["rejectedChunks"] += not r["accepted"]
        if len(a["units"]) != len(b["units"]):
            differences.append((name, "unit count"))
        for x, y in zip(a["units"], b["units"]):
            units += 1
            for field in ("referent", "narrative"):
                kinds[f"{field}:{kind(x[field])}"] += 1
            if x != y:
                differences.append((name, x.get("u")))
    print(json.dumps({"files": len(recorded), "units": units, "differences": len(differences)}))
    print(json.dumps(dict(sorted(receipts.items()))))
    print(json.dumps(dict(sorted(kinds.items()))))
    for d in differences[:10]:
        print("DIFF", d)
    return 1 if differences else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
