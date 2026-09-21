#!/usr/bin/env python3
"""Turn Film Festival recall events into the word-onset CSV the mapper reads.

`ds004042` ships recall as **utterance** rows — `onset`, `duration`, `recall_transcript` — while
`RecallWordsCsv.parse` (embed-bench) reads a CSV whose first column is literally `Words` with a word
onset in column two, the shape Sherlock's released word-timestamped exports already have.

The conversion therefore invents within-utterance timing, and says so rather than hiding it: each
utterance's words are spread evenly across its span (see `convert`). That is an approximation,
recorded in the receipt as `withinUtteranceTiming: "linear-interpolation"`. It is good enough for
the gold-free agreement scorer, which matches units by text overlap, and it is **not** good enough
for any claim about word-level timing. Real word onsets exist for this corpus in the Chen lab
release (Zenodo 10.5281/zenodo.8208709, "Film Festival Timestamps"); wiring those in needs a mapping
from three-letter participant codes to `sub-NN` numbers that the release does not document.

Words are reduced to bare tokens because the reader splits lines on commas and takes the first
field: a token carrying a comma would silently truncate the row.

Usage: filmfest_recall.py OUT_DIR [RECEIPT_JSON]
       Reads every `*_task-recall_*_events.tsv` under the data root's `filmfestival/recall_events/`.
       One CSV per participant-run; a participant scanned twice keeps the runs separate, because
       nothing in the release states an offset between them.

Output carries participant recall prose: write it under the data root, never into Git.
"""
import csv
import hashlib
import json
import os
import re
import subprocess
import sys

_TOKEN = re.compile(r"[^\W_]+(?:'[^\W_]+)*", re.UNICODE)


def data_root():
    here = os.path.dirname(os.path.abspath(__file__))
    out = subprocess.run(
        ["bash", os.path.join(here, "..", "data-root.sh")],
        capture_output=True,
        text=True,
    )
    return out.stdout.strip() or os.environ.get("STORYMODEL4S_DATA", "data")


def tokenize(text):
    """Bare word tokens: no punctuation, no commas, apostrophes kept inside a word."""
    return _TOKEN.findall(text or "")


TAIL_SECONDS = 1.0


def _number(text):
    try:
        return float(text)
    except (TypeError, ValueError):
        return None


def convert(path):
    """One events.tsv -> (rows, stats). Rows are (word, onset_seconds).

    An utterance's span is `[onset, next onset)`, shortened by `duration` when duration says so.
    Two properties of this release force that rule rather than `[onset, onset + duration)`:

    * `duration` is rounded up, so it overruns the next onset on about 41% of utterance pairs, by up
      to a second. Interpolating over it would run word onsets backwards across every boundary.
    * `duration` is the literal string `N/A` for 779 rows, all of them in the six files of subjects
      03, 04, 05, 06 and 15 — the five the release's own README reports as excluded for excessive
      motion. An earlier version of this tool coerced those to a number, failed, and dropped the
      rows silently, losing five participants entirely. Missing durations are now counted and the
      utterance is still emitted, timed by the next onset.
    """
    with open(path, newline="", encoding="utf-8") as fh:
        recs = list(csv.DictReader(fh, delimiter="\t"))

    onsets = [_number(r.get("onset")) for r in recs]
    durations = [_number(r.get("duration")) for r in recs]
    rows, empty, missing_duration, clamped = [], 0, 0, 0
    kept = 0
    for i, rec in enumerate(recs):
        onset = onsets[i]
        if onset is None:
            continue
        kept += 1
        nxt = next((o for o in onsets[i + 1 :] if o is not None), None)
        duration = durations[i]
        if duration is None:
            missing_duration += 1
            end = nxt if nxt is not None else onset + TAIL_SECONDS
        else:
            end = onset + duration
            if nxt is not None and end > nxt:
                clamped += 1
                end = nxt
        span = max(end - onset, 0.0)
        words = tokenize(rec.get("recall_transcript", ""))
        if not words:
            empty += 1
            continue
        step = span / len(words)
        for k, w in enumerate(words):
            rows.append((w, round(onset + k * step, 3)))
    return rows, {
        "utterances": kept,
        "utterancesWithoutWords": empty,
        "utterancesWithoutDuration": missing_duration,
        "utterancesClampedToNextOnset": clamped,
        "words": len(rows),
    }


def main(argv):
    if len(argv) < 2:
        sys.exit(__doc__)
    out_dir = argv[1]
    receipt_path = argv[2] if len(argv) > 2 else None
    src_dir = os.path.join(data_root(), "filmfestival", "recall_events")
    os.makedirs(out_dir, exist_ok=True)

    sources = sorted(f for f in os.listdir(src_dir) if f.endswith("_events.tsv"))
    if not sources:
        sys.exit(f"no *_events.tsv under {src_dir}")

    outputs, totals = [], {
        "utterances": 0,
        "utterancesWithoutWords": 0,
        "utterancesWithoutDuration": 0,
        "utterancesClampedToNextOnset": 0,
        "words": 0,
    }
    for name in sources:
        rows, stats = convert(os.path.join(src_dir, name))
        stem = name.replace("_events.tsv", "").replace("_task-recall", "")
        body = "Words,Onset Time (sec)\n" + "".join(f"{w},{t}\n" for w, t in rows)
        with open(os.path.join(out_dir, f"{stem}.csv"), "w", encoding="utf-8") as fh:
            fh.write(body)
        for k in totals:
            totals[k] += stats[k]
        outputs.append(
            {
                "source": name,
                "output": f"{stem}.csv",
                "sha256": hashlib.sha256(body.encode("utf-8")).hexdigest(),
                **stats,
            }
        )

    participants = sorted({o["output"].split("_")[0] for o in outputs})
    receipt = {
        "schema": "storymodel4s.filmfestival.recall-words-replay",
        "schemaVersion": 1,
        "withinUtteranceTiming": "linear-interpolation",
        "utteranceSpanRule": "[onset, next onset), shortened by duration when duration is shorter",
        "wordOnsetsAreMeasured": False,
        "sourceRelease": "openneuro ds004042 v1.0.1, task-recall events.tsv",
        "counts": {
            "sourceFiles": len(sources),
            "participants": len(participants),
            **totals,
        },
        "outputs": outputs,
        "nonClaims": [
            "word-level recall timing",
            "an offset relating a participant's two recall runs",
            "alignment of recall onsets to the annotation clock",
        ],
    }
    if receipt_path:
        with open(receipt_path, "w", encoding="utf-8") as fh:
            json.dump(receipt, fh, indent=2)
            fh.write("\n")
    json.dump(
        {k: receipt[k] for k in ("withinUtteranceTiming", "counts")},
        sys.stdout,
        indent=2,
    )
    sys.stdout.write("\n")


if __name__ == "__main__":
    main(sys.argv)
