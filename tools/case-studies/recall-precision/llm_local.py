"""Iteration 2, content-local LLM stream.

Each development recall unit (~12 words) is shown with only its two preceding units as context.
Units from all participants are pooled and shuffled (fixed seed) before batching, so a call sees
no recall order and no participant's whole recall. The model returns weighted film-row ranges:
a single moment is a short range, a summary of a stretch is a long range (broad reference), and
competing hypotheses are separate ranges (alternatives). Unassigned weight is "cannot localize".

Calls go through tools/sherlock_mapper/llm.py (subscription CLI, content-addressed cache).
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import data as D

sys.path.insert(0, str(D.ROOT / "tools" / "sherlock_mapper"))
from llm import call, parse_json  # noqa: E402

SYSTEM = """You localize excerpts of people's spoken free recall of the film "Sherlock: A Study in \
Pink" within that film. You are given the film annotation: numbered rows (r1..r1000) with times, \
descriptions and dialogue, grouped into 50 scenes.

Each item has CONTEXT (what the speaker said just before; use it only to resolve pronouns and \
ellipsis) and TARGET (the words to localize). Items come from different speakers and are in \
RANDOM order: never use an item's position, neighbouring items, or your knowledge of where \
speakers usually are in their recall. Judge each TARGET only from its own words plus its CONTEXT.

For each item return:
- "kind": "episodic" (describes film content: events, people, places, dialogue, objects), or \
"non_episodic" (comments on the film or on remembering, evaluations, filler with no film content).
- "reference": "specific" (a particular moment or short exchange), "broad" (summarizes or \
condenses a longer stretch, e.g. "they investigate the murders"), or "none" (non-episodic).
- "ranges": a list of [first_row, last_row, weight]. Each range is one hypothesis about the film \
content the TARGET refers to. A specific moment is a short range (often a single row, first = \
last). A broad reference is ONE long range covering the whole stretch it summarizes; do not split \
it into many short ranges. Use several ranges only for genuinely competing hypotheses (the words \
fit different places in the film). Weights are your probability that each hypothesis is the \
right one; they must sum to at most 1; leave the remainder unassigned when the words could fit \
places you have not listed. Return an empty list for non-episodic items.

Be calibrated: a vague target ("then he goes somewhere") deserves low or spread weights; a target \
quoting a distinctive line or object deserves concentrated weight.

Reply with JSON only: {"items": [{"id": "<id>", "kind": "...", "reference": "...", \
"ranges": [[r1, r2, w], ...]}]} covering every item exactly once."""


def items_for(name):
    us = D.units(name)
    out = []
    for k, u in enumerate(us):
        ctx = " ".join(x.text for x in us[max(0, k - 2) : k])
        out.append(dict(id=u.id, participant=name, ctx=ctx, target=u.text))
    return out


def foreign_items(n, seed):
    """Different-film null: FilmFestival recall units (odd subjects, as in iteration 1)."""
    import csv
    import glob
    import re

    pool = []
    for f in sorted(glob.glob(str(D.DATA / "study/filmfestival/jl-onnx-dev/recall-map-sub-*.tsv"))):
        k = int(re.search(r"sub-(\d+)", f).group(1))
        if k % 2 == 0:
            continue
        with open(f, newline="") as fh:
            us = [x["recallText"] for x in csv.DictReader(fh, delimiter="\t")]
        for j, txt in enumerate(us):
            pool.append(dict(id=f"ff:sub-{k:02d}:u{j}", participant="FOREIGN-filmfestival",
                             ctx=" ".join(us[max(0, j - 2) : j]), target=txt))
    random.Random(seed + 1).shuffle(pool)
    return pool[:n]


def prompt_for(batch, outline, cast_line):
    lines = [
        f"ITEM {it['id']}\nCONTEXT: {it['ctx'] or '(start of recall)'}\nTARGET: {it['target']}"
        for it in batch
    ]
    return (
        f"CAST\n{cast_line}\n\nFILM ANNOTATION\n{outline}\n\nITEMS (random order)\n\n"
        + "\n\n".join(lines)
        + f"\n\nLocalize all {len(batch)} items."
    )


def run_batch(batch, outline, cast_line, model):
    got = {}
    for attempt in range(3):
        sfx = (
            ""
            if attempt == 0
            else f"\n\nReply with strictly valid JSON only ({attempt})."
        )
        try:
            for x in parse_json(
                call(prompt_for(batch, outline, cast_line) + sfx, SYSTEM, model=model)
            )["items"]:
                got[str(x["id"])] = x
            break
        except (ValueError, KeyError, TypeError):
            continue
    return got


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default="llm-local-v1")
    ap.add_argument("--participants")
    ap.add_argument("--model", default="opus")
    ap.add_argument("--batch", type=int, default=40)
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--seed", type=int, default=20261116)
    ap.add_argument("--foreign", type=int, default=0, help="FilmFestival null units to mix in")
    a = ap.parse_args()
    names = D.development()
    if a.participants:
        names = [n for n in names if n.split("_")[0] in a.participants.split(",")]
    items = [it for n in names for it in items_for(n)]
    if a.foreign:
        items += foreign_items(a.foreign, a.seed)
    random.Random(a.seed).shuffle(items)
    batches = [items[i : i + a.batch] for i in range(0, len(items), a.batch)]
    outline, _ = D.film_outline()
    cast_line = D.cast_line()
    out = D.OUT / "llm" / a.run
    out.mkdir(parents=True, exist_ok=True)
    print(f"{len(items)} items in {len(batches)} calls", flush=True)
    res = {}
    with ThreadPoolExecutor(a.jobs) as ex:
        for k, got in enumerate(
            ex.map(lambda b: run_batch(b, outline, cast_line, a.model), batches)
        ):
            res |= got
            print(f"batch {k + 1}/{len(batches)}: {len(got)}", flush=True)
    by = {}
    for it in items:
        by.setdefault(it["participant"], []).append(
            dict(
                id=it["id"],
                **{k: v for k, v in res.get(it["id"], {}).items() if k != "id"},
            )
        )
    for n, recs in by.items():
        missing = [r["id"] for r in recs if "ranges" not in r]
        (out / f"{n}.json").write_text(
            json.dumps(
                dict(
                    participant=n,
                    model=a.model,
                    seed=a.seed,
                    missing=missing,
                    units=recs,
                ),
                indent=1,
            )
        )
        print(n, "units", len(recs), "missing", len(missing))


if __name__ == "__main__":
    main()
