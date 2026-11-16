"""Iteration 3, content-local LLM stream, prompt v2.

Changes after critique round 2:
- opaque item ids (a salted hash), so a call cannot recover speaker or recall position;
- talk category (episodic / metamemory / commentary / fragment), so memory talk is labelled;
- each item returns REFERENCES, each anchored to the exact words that carry it, with a form
  (specific / extended / repeated / summary / joint) and its own competing alternatives;
  a repeated or joint reference holds several film intervals inside ONE alternative, so breadth,
  repetition and uncertainty between alternatives stay separate;
- a speaker-hedge flag, kept apart from the model's own unassigned weight;
- --context none gives the target-only control arm.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import sys
from concurrent.futures import ThreadPoolExecutor

import data as D
from llm_local import foreign_items, items_for

sys.path.insert(0, str(D.ROOT / "tools" / "sherlock_mapper"))
from llm import call, parse_json  # noqa: E402

SYSTEM = """You localize excerpts of people's spoken free recall of the film "Sherlock: A Study in \
Pink" within that film. You are given the film annotation: numbered rows (r1..r1000) with times, \
descriptions and dialogue, grouped into 50 scenes.

Each item has CONTEXT (what the speaker said just before; use it only to resolve pronouns and \
ellipsis; it may be absent) and TARGET (the words to analyse). Items come from different speakers \
in RANDOM order with meaningless ids: judge each TARGET only from its own words plus its CONTEXT.

For each item return:
- "talk": "episodic" (the TARGET describes film content: events, people, places, objects, \
dialogue), "metamemory" (about the speaker's remembering or forgetting, e.g. "I don't remember \
what it was called"), "commentary" (opinions, evaluations, remarks about the film as a film), or \
"fragment" (no content of its own: a connective, a quotative frame, a false start). If the TARGET \
mixes talk types, choose "episodic" when any film content is described.
- "hedge": true if the speaker marks uncertainty ("I think", "I guess", "or something", "maybe").
- "refs": for episodic items, the film references the TARGET makes; empty otherwise. Usually one; \
more when the TARGET moves between different film moments (e.g. a general frame and then a \
specific event). Each reference has:
  - "words": the exact contiguous words of the TARGET that carry this reference (copy them \
verbatim);
  - "form": "specific" (one moment or short exchange), "extended" (one continuous activity or \
stretch), "repeated" (something that happens several separate times), "summary" (condenses a \
longer stretch into gist), or "joint" (several distinct moments referred to together);
  - "alts": competing hypotheses about WHICH film content is meant, each \
{"w": probability this hypothesis is the right one, "iv": [[first_row, last_row], ...]}. A \
specific or extended reference has one interval per hypothesis. A repeated or joint reference \
lists ALL its occurrences as several intervals inside ONE hypothesis. Use several hypotheses only \
when the words genuinely fit different places in the film. Weights sum to at most 1; leave the \
remainder unassigned when the words could fit places you have not listed.

Be calibrated: vague words deserve spread or low weights; a distinctive line, object or name \
deserves concentrated weight.

Reply with JSON only: {"items": [{"id": "<id>", "talk": "...", "hedge": false, "refs": \
[{"words": "...", "form": "...", "alts": [{"w": 0.8, "iv": [[r1, r2]]}]}]}]} covering every item \
exactly once."""


def opaque(uid, salt):
    return "x" + hashlib.sha256(f"{salt}:{uid}".encode()).hexdigest()[:10]


def prompt_for(batch, outline, cast_line, context):
    lines = []
    for it in batch:
        ctx = (it["ctx"] or "(start of recall)") if context == "two" else None
        head = f"ITEM {it['oid']}\n" + (f"CONTEXT: {ctx}\n" if ctx is not None else "")
        lines.append(head + f"TARGET: {it['target']}")
    return (
        f"CAST\n{cast_line}\n\nFILM ANNOTATION\n{outline}\n\nITEMS (random order)\n\n"
        + "\n\n".join(lines)
        + f"\n\nAnalyse all {len(batch)} items."
    )


def run_batch(batch, outline, cast_line, model, context):
    got = {}
    want = {it["oid"] for it in batch}
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
    ap.add_argument("--run", default="llm-local-v2-a")
    ap.add_argument("--participants")
    ap.add_argument("--model", default="opus")
    ap.add_argument("--batch", type=int, default=40)
    ap.add_argument("--jobs", type=int, default=5)
    ap.add_argument("--seed", type=int, default=20261117)
    ap.add_argument("--context", choices=["two", "none"], default="two")
    ap.add_argument("--foreign", type=int, default=0)
    a = ap.parse_args()
    names = D.development()
    if a.participants:
        names = [n for n in names if n.split("_")[0] in a.participants.split(",")]
    items = [it for n in names for it in items_for(n)]
    if a.foreign:
        items += foreign_items(a.foreign, 20261116)
    for it in items:
        it["oid"] = opaque(it["id"], a.seed)
    random.Random(a.seed).shuffle(items)
    batches = [items[i : i + a.batch] for i in range(0, len(items), a.batch)]
    outline, _ = D.film_outline()
    cast_line = D.cast_line()
    out = D.OUT / "llm" / a.run
    out.mkdir(parents=True, exist_ok=True)
    print(f"{len(items)} items in {len(batches)} calls", flush=True)
    res = {}
    with ThreadPoolExecutor(a.jobs) as ex:
        fn = lambda b: run_batch(b, outline, cast_line, a.model, a.context)  # noqa: E731
        for k, got in enumerate(ex.map(fn, batches)):
            res |= got
            print(f"batch {k + 1}/{len(batches)}: {len(got)}", flush=True)
    by = {}
    for it in items:
        r = res.get(it["oid"], {})
        by.setdefault(it["participant"], []).append(
            dict(id=it["id"], **{k: v for k, v in r.items() if k != "id"})
        )
    for n, recs in by.items():
        missing = [r["id"] for r in recs if "talk" not in r]
        meta = dict(
            participant=n,
            model=a.model,
            seed=a.seed,
            context=a.context,
            prompt="v2",
            missing=missing,
            units=recs,
        )
        (out / f"{n}.json").write_text(json.dumps(meta, indent=1))
        print(n, "units", len(recs), "missing", len(missing))


if __name__ == "__main__":
    main()
