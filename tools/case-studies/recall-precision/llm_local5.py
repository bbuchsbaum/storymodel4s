"""Iteration 5, content-local LLM stream, prompt v3 (critique round 3).

Changes from v2:
- talk type is labelled per SPAN of the target (episodic / metamemory / commentary / fragment /
  repair), so a remembering clause inside an episodic unit is recorded instead of swallowed;
- hedge has a scope: about the film content, or about the speaker's own memory;
- each reference separates EXTENT (moment / stretch) from OCCURRENCE (once / repeated / generic)
  and MODE (detailed / gist), instead of one overloaded "form";
- each reference lists its DISTINGUISHING DETAILS by type (who, action, place, time, object,
  dialogue, perceptual) with the exact words, so specificity (amount of distinguishing detail)
  is measured separately from breadth (film extent);
- --manip names: character names in the TARGET are replaced with "someone" (a deterministic
  manipulation test; context is untouched). Expected: who-details and localization fall,
  competing hypotheses rise, breadth of the surviving references does not change.
"""

from __future__ import annotations

import argparse
import json
import random
import re
import sys
from concurrent.futures import ThreadPoolExecutor

import data as D
from llm_local import items_for
from llm_local3 import opaque

sys.path.insert(0, str(D.ROOT / "tools" / "sherlock_mapper"))
from llm import call, parse_json  # noqa: E402
from map_v3 import cast  # noqa: E402

SYSTEM = """You analyse excerpts of people's spoken free recall of the film "Sherlock: A Study in \
Pink" against that film. You are given the film annotation: numbered rows (r1..r1000) with times, \
descriptions and dialogue, grouped into 50 scenes.

Each item has CONTEXT (what the speaker said just before; use it only to resolve pronouns and \
ellipsis; it may be absent) and TARGET (the words to analyse). Items come from different speakers \
in RANDOM order with meaningless ids: judge each TARGET only from its own words plus its CONTEXT.

For each item return:
- "spans": the TARGET divided into consecutive pieces covering all of its words, each \
{"words": exact words, "talk": "episodic" (describes film content) | "metamemory" (about the \
speaker's remembering or forgetting) | "commentary" (opinion or remark about the film as a film) | \
"fragment" (connective, quotative frame or false start with no content) | "repair" (the speaker \
correcting something they just said)}.
- "hedge": null, or "content" (unsure what happened in the film) or "memory" (unsure whether they \
remember), with the exact hedging words in "hedge_words".
- "refs": the film references made by the episodic spans. Each reference has:
  - "words": the exact contiguous words of the TARGET that carry it;
  - "extent": "moment" (an instant or short exchange) or "stretch" (a continuous activity or a \
longer stretch of film);
  - "occurrence": "once", "repeated" (several separate times in the film), or "generic" (a \
general statement about many occurrences);
  - "mode": "detailed" (describes particulars) or "gist" (condenses or summarizes);
  - "details": the distinguishing details the words give, each {"type": "who" | "action" | \
"place" | "time" | "object" | "dialogue" | "perceptual", "words": exact words}. A detail is \
distinguishing if it helps pick out this film content rather than any other; generic words \
("then", "he", "goes") are not details;
  - "alts": competing hypotheses about WHICH film content is meant, each {"w": probability this \
hypothesis is right, "iv": [[first_row, last_row], ...]}. A repeated or generic reference lists \
all its occurrences as several intervals inside ONE hypothesis. Use several hypotheses only when \
the words genuinely fit different places. Weights sum to at most 1; leave the remainder \
unassigned when the words could fit places you have not listed.

Be calibrated: vague words deserve spread or low weights; a distinctive line, object or name \
deserves concentrated weight.

Reply with JSON only: {"items": [{"id": "<id>", "spans": [...], "hedge": null, "hedge_words": \
null, "refs": [{"words": "...", "extent": "...", "occurrence": "...", "mode": "...", "details": \
[...], "alts": [{"w": 0.8, "iv": [[r1, r2]]}]}]}]} covering every item exactly once."""


CHARACTER_NAMES = [
    "Mrs Hudson", "Mrs. Hudson", "Sherlock Holmes", "John Watson", "Molly Hooper", "Mike Stamford",
    "Sally Donovan", "Jennifer Wilson", "Jeff Hope", "Sherlock", "Holmes", "John", "Watson", "Lestrade",
    "Mycroft", "Moriarty", "Hudson", "Donovan", "Anderson", "Molly", "Hooper", "Angelo", "Jennifer",
    "Wilson", "Jeff", "Jeffrey", "Hope", "Gary", "Beth", "Davenport", "Helen", "Jimmy", "Anthea",
    "Ella", "Stamford", "Sally", "Rachel",
]


def cast_names():
    """Curated character names (multi-word names first so they become one 'someone')."""
    return CHARACTER_NAMES


def manipulate(text, names):
    pat = re.compile(
        r"\b("
        + "|".join(sorted(map(re.escape, names), key=len, reverse=True))
        + r")('s)?\b"
    )
    return pat.sub(lambda m: "someone" + (m.group(2) or ""), text)


def prompt_for(batch, outline, cast_line, context):
    lines = []
    for it in batch:
        head = f"ITEM {it['oid']}\n"
        if context == "two":
            head += f"CONTEXT: {it['ctx'] or '(start of recall)'}\n"
        lines.append(head + f"TARGET: {it['target']}")
    return (
        f"CAST\n{cast_line}\n\nFILM ANNOTATION\n{outline}\n\nITEMS (random order)\n\n"
        + "\n\n".join(lines)
        + f"\n\nAnalyse all {len(batch)} items."
    )


def run_batch(batch, outline, cast_line, model, context):
    got, want = {}, {it["oid"] for it in batch}
    for attempt in range(3):
        sfx = (
            ""
            if attempt == 0
            else f"\n\nReply with strictly valid JSON only ({attempt})."
        )
        try:
            reply = call(
                prompt_for(batch, outline, cast_line, context) + sfx,
                SYSTEM,
                model=model,
            )
            for x in parse_json(reply)["items"]:
                if str(x.get("id")) in want:
                    got[str(x["id"])] = x
            break
        except (ValueError, KeyError, TypeError):
            continue
    return got


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default="llm-local-v3-a")
    ap.add_argument("--participants")
    ap.add_argument("--model", default="opus")
    ap.add_argument("--batch", type=int, default=35)
    ap.add_argument("--jobs", type=int, default=5)
    ap.add_argument("--seed", type=int, default=20261120)
    ap.add_argument("--manip", choices=["none", "names"], default="none")
    a = ap.parse_args()
    names = D.development()
    if a.participants:
        names = [n for n in names if n.split("_")[0] in a.participants.split(",")]
    items = [it for n in names for it in items_for(n)]
    if a.manip == "names":
        cn = cast_names()
        kept = []
        for it in items:
            new = manipulate(it["target"], cn)
            if new != it["target"]:
                kept.append({**it, "target": new, "original": it["target"]})
        items = kept
    for it in items:
        it["oid"] = opaque(it["id"], a.seed)
    random.Random(a.seed).shuffle(items)
    batches = [items[i : i + a.batch] for i in range(0, len(items), a.batch)]
    outline, _ = D.common.film_outline()
    cast_line = cast()
    out = D.OUT / "llm" / a.run
    out.mkdir(parents=True, exist_ok=True)
    print(f"{len(items)} items in {len(batches)} calls", flush=True)
    res = {}
    with ThreadPoolExecutor(a.jobs) as ex:
        fn = lambda b: run_batch(b, outline, cast_line, a.model, "two")  # noqa: E731
        for k, got in enumerate(ex.map(fn, batches)):
            res |= got
            print(f"batch {k + 1}/{len(batches)}: {len(got)}", flush=True)
    by = {}
    for it in items:
        r = res.get(it["oid"], {})
        by.setdefault(it["participant"], []).append(
            dict(
                id=it["id"],
                manipulated=a.manip != "none",
                **{k: v for k, v in r.items() if k != "id"},
            )
        )
    for n, recs in by.items():
        missing = [r["id"] for r in recs if "spans" not in r]
        meta = dict(
            participant=n,
            model=a.model,
            seed=a.seed,
            context="two",
            prompt="v3",
            manip=a.manip,
            missing=missing,
            units=recs,
        )
        (out / f"{n}.json").write_text(json.dumps(meta, indent=1))
        print(n, "units", len(recs), "missing", len(missing))


if __name__ == "__main__":
    main()
