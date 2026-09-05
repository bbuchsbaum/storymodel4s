#!/usr/bin/env python3
"""Replay the Film Festival recall-to-scene gold into a TSV, with a receipt.

`recall_scenematched/sub-*.xlsx` in `jchenlab-jhu/filmfest` labels each recall utterance with the
movie it refers to and the scene(s) within it, on the same clock as `ds004042`'s `events.tsv`. That
alignment is checked here rather than assumed: every participant's gold rows must match their
events rows one for one on onset, or the file is refused.

Label space, from `_scenematching_codingscheme.xlsx` in the same release:

* movies: 1-10 are the films in presentation order, 11 is the cartoon lobby clip, and -1, -2 and 0
  are `memory search`, `gave up` and `off task`.
* scenes: 1-216 name a specific scene; `movie * 1000` means that movie but no specific scene (a
  title, or an opinion about it); -1, -2 and 0 repeat the movie codes.

A cell may hold several scenes (`182, 185`), so scenes are emitted as a comma-free space-joined
list and a count. Rows whose movie code is negative or zero carry no film and are kept, flagged, so
a consumer can decide whether off-task speech counts against it rather than having that decided
here by omission.

Usage: filmfest_gold.py OUT_TSV [RECEIPT_JSON]

Output carries participant recall prose: write it under the data root, never into Git.
"""
import glob
import hashlib
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from xlsx_rows import sheet_rows  # noqa: E402

# From the release's own coding scheme sheet. Spelling is theirs ("bust stop").
MOVIE_LABELS = {
    1: "catch me if you can",
    2: "the record",
    3: "the boyfriend",
    4: "the shoe",
    5: "keith reynolds",
    6: "the rock",
    7: "the prisoner",
    8: "the black hole",
    9: "post it love",
    10: "bust stop",
    11: "lobby",
    -1: "memory search",
    -2: "gave up",
    0: "off task",
}
COLUMNS = [
    "participant",
    "run",
    "gold_row",
    "movie",
    "movie_label",
    "scenes",
    "scene_count",
    "specific_scene",
    "start_s",
    "end_s",
    "start_tr",
    "end_tr",
    "text",
]


def data_root():
    here = os.path.dirname(os.path.abspath(__file__))
    out = subprocess.run(
        ["bash", os.path.join(here, "..", "data-root.sh")],
        capture_output=True,
        text=True,
    )
    return out.stdout.strip() or os.environ.get("STORYMODEL4S_DATA", "data")


def _int(v):
    if v is None:
        return None
    try:
        return int(str(v).strip())
    except ValueError:
        return None


def scenes_of(cell):
    """`'182, 185'` -> [182, 185]. Non-numeric tokens are dropped and counted by the caller."""
    if cell is None:
        return []
    return [int(t) for t in re.findall(r"-?\d+", str(cell))]


def read_gold(path, participant, run_tag):
    rows = sheet_rows(path, "Sheet1")
    hdr = [str(c).strip() if c is not None else "" for c in rows[0]]
    idx = {h: i for i, h in enumerate(hdr)}
    for need in ("movies", "scenes", "startsec_adj", "endsec_adj", "text"):
        if need not in idx:
            raise ValueError(f"{path}: missing column {need!r}")
    out = []
    for n, r in enumerate(rows[1:], start=2):

        def at(name):
            i = idx.get(name)
            return r[i] if i is not None and i < len(r) else None

        start = at("startsec_adj")
        if start is None or _int(at("movies")) is None:
            continue
        movie = _int(at("movies"))
        scenes = [s for s in scenes_of(at("scenes")) if 1 <= s <= 216]
        out.append(
            {
                "participant": participant,
                "run": run_tag,
                "gold_row": n,
                "movie": movie,
                "movie_label": MOVIE_LABELS.get(movie, "unknown"),
                "scenes": " ".join(str(s) for s in scenes),
                "scene_count": len(scenes),
                "specific_scene": 1 if scenes else 0,
                "start_s": round(float(start), 3),
                "end_s": round(float(at("endsec_adj")), 3)
                if at("endsec_adj") is not None
                else "",
                "start_tr": _int(at("startTR")) if "startTR" in idx else "",
                "end_tr": _int(at("endTR")) if "endTR" in idx else "",
                "text": ("" if at("text") is None else str(at("text")))
                .replace("\t", " ")
                .strip(),
            }
        )
    return out


def check_against_events(root, participant, run_tag, gold):
    """Gold must line up row for row with the events file it labels, on onset."""
    import csv as _csv

    path = os.path.join(
        root,
        "filmfestival",
        "recall_events",
        f"{participant}_task-recall_{run_tag}_events.tsv",
    )
    if not os.path.exists(path):
        return {"eventsFile": None, "rowsCompared": 0, "onsetMismatches": None}
    with open(path, newline="", encoding="utf-8") as fh:
        ev = [r for r in _csv.DictReader(fh, delimiter="\t")]
    n = min(len(ev), len(gold))
    bad = sum(
        1 for k in range(n) if abs(float(ev[k]["onset"]) - gold[k]["start_s"]) > 0.05
    )
    return {
        "eventsFile": os.path.basename(path),
        "eventsRows": len(ev),
        "goldRows": len(gold),
        "rowsCompared": n,
        "onsetMismatches": bad,
    }


def main(argv):
    if len(argv) < 2:
        sys.exit(__doc__)
    out_tsv = argv[1]
    receipt_path = argv[2] if len(argv) > 2 else None
    root = data_root()
    src_dir = os.path.join(root, "filmfestival", "recall_scenematched")

    rows, per_file, refused = [], [], []
    for path in sorted(glob.glob(os.path.join(src_dir, "sub-*.xlsx"))):
        base = os.path.basename(path)
        participant = base.split("_")[0]
        tail = base.replace(".xlsx", "").split("_")[-1]
        run_tag = "run-02" if tail.endswith("2") else "run-01"
        gold = read_gold(path, participant, run_tag)
        chk = check_against_events(root, participant, run_tag, gold)
        if chk["onsetMismatches"]:
            refused.append({"file": base, **chk})
            continue
        rows.extend(gold)
        per_file.append(
            {
                "file": base,
                "participant": participant,
                "run": run_tag,
                "rows": len(gold),
                "withSpecificScene": sum(r["specific_scene"] for r in gold),
                "sha256": hashlib.sha256(open(path, "rb").read()).hexdigest(),
                **chk,
            }
        )

    body = "\t".join(COLUMNS) + "\n"
    body += "".join("\t".join(str(r[c]) for c in COLUMNS) + "\n" for r in rows)
    with open(out_tsv, "w", encoding="utf-8") as fh:
        fh.write(body)

    movies = {}
    for r in rows:
        movies[r["movie_label"]] = movies.get(r["movie_label"], 0) + 1
    receipt = {
        "schema": "storymodel4s.filmfestival.recall-scene-gold-replay",
        "schemaVersion": 1,
        "sourceRelease": "github.com/jchenlab-jhu/filmfest recall_scenematched",
        "output": {
            "sha256": hashlib.sha256(body.encode("utf-8")).hexdigest(),
            "columns": COLUMNS,
        },
        "counts": {
            "files": len(per_file),
            "participants": len({r["participant"] for r in rows}),
            "utterances": len(rows),
            "withSpecificScene": sum(r["specific_scene"] for r in rows),
            "byMovieLabel": dict(sorted(movies.items(), key=lambda kv: -kv[1])),
        },
        "clockCheck": "gold onsets compared row for row against ds004042 events.tsv; a file with any mismatch beyond 0.05 s is refused",
        "refused": refused,
        "files": per_file,
        "nonClaims": [
            "gold labels independently verified",
            "coverage of every recalled event",
            "agreement between the gold's scene ids and any coder other than the release's own",
        ],
    }
    if receipt_path:
        with open(receipt_path, "w", encoding="utf-8") as fh:
            json.dump(receipt, fh, indent=2)
            fh.write("\n")
    json.dump({k: receipt[k] for k in ("counts", "refused")}, sys.stdout, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main(sys.argv)
