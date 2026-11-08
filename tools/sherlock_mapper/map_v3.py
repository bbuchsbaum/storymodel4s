#!/usr/bin/env python3
"""v3: structured symbolic recall-to-film mapping.

Per recall unit: narrative and referent scene (with alternatives), discourse function, and typed
claims (who, resolved to the film's cast inventory; what; where), each linked to supporting film
rows with a relation type and a fidelity judgement.

Usage: map_v3.py RUN_NAME [--participants NN03,NN04] [--model opus] [--chunk 30] [--jobs 4]
"""

import argparse
import json
import re
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

import common as c
from llm import call, parse_json
from map_llm import SYSTEM_V2

CORE = SYSTEM_V2.split("Reply with JSON only")[0]

SYSTEM_V3 = (
    CORE
    + """Then decompose each unit into the claims it makes about the film.

- "function": one of narration (tells what happened), commentary (opinion or interpretation), \
metamemory (remembering or forgetting, e.g. "I don't remember"), evaluation (liking or judging the \
film), other.
- "claims": a list, possibly empty (for pure commentary or metamemory). Each claim has:
  - "who": the characters involved, using the exact names from the CAST list; for a mention you \
cannot resolve, write "?" followed by the participant's words, e.g. "?the old lady".
  - "what": a short canonical predicate, e.g. "lies dead face down", "texts the number".
  - "where": a place name from the annotation, or null.
  - "rows": up to 3 supporting film rows (integers).
  - "relation": depicts (the event is shown), dialogue (it is said on screen), summary (several \
moments condensed), or inference (the participant's conclusion, not shown or said).
  - "fidelity": supported (the film supports the claim as stated), contradicted (the film shows \
otherwise; add "note" with what the film shows), not-in-film (no support anywhere in the film), \
or unclear.

Reply with JSON only: {"units": [{"u": <n>, "narrative": <1-50|null>, "referent": <1-50|null>, \
"alts": [[scene, p], ...], "function": "...", "claims": [{"who": [...], "what": "...", \
"where": ..., "rows": [...], "relation": "...", "fidelity": "...", "note": ...}], "conf": <0-1>}]} \
covering every target unit exactly once."""
)


def cast():
    cnt = Counter()
    for r in c.film_rows():
        for n in re.split(r",\s*", r.names):
            if n.strip():
                cnt[n.strip()] += 1
    return ", ".join(n for n, _ in cnt.most_common())


def map_participant(name, outline, cast_line, model, chunk):
    us = c.units(name)
    rec = "\n".join(f"[{u.index}] {u.text}" for u in us)
    out = {}
    for lo in range(0, len(us), chunk):
        hi = min(lo + chunk, len(us)) - 1
        prompt = (
            f"CAST\n{cast_line}\n\nFILM ANNOTATION\n{outline}\n\n"
            f"PARTICIPANT RECALL (all units)\n{rec}\n\n"
            f"TARGET UNITS: map every unit numbered {us[lo].index} through {us[hi].index}."
        )
        for attempt in range(3):
            sfx = (
                ""
                if attempt == 0
                else f"\n\nReply with strictly valid JSON only ({attempt})."
            )
            try:
                for x in parse_json(call(prompt + sfx, SYSTEM_V3, model=model))[
                    "units"
                ]:
                    out[int(x["u"])] = x
                break
            except (ValueError, KeyError, TypeError):
                continue
    units = [
        dict(
            index=u.index,
            id=u.id,
            **{k: v for k, v in out.get(u.index, {}).items() if k != "u"},
        )
        for u in us
    ]
    return {
        "participant": name,
        "missing": [u.index for u in us if u.index not in out],
        "units": units,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--participants")
    ap.add_argument("--allow-untouched", action="store_true")
    ap.add_argument("--model", default="opus")
    ap.add_argument("--chunk", type=int, default=30)
    ap.add_argument("--jobs", type=int, default=4)
    a = ap.parse_args()
    names = c.development() + (sorted(c.partition()["untouchedTest"]) if a.allow_untouched else [])
    if a.participants:
        names = [n for n in names if n.split("_")[0] in a.participants.split(",")]
    for n in names:
        c.guard(n, a.allow_untouched)
    outline, _ = c.film_outline()
    cast_line = cast()
    d = c.OUT / a.run
    d.mkdir(parents=True, exist_ok=True)
    preds = {}
    with ThreadPoolExecutor(a.jobs) as ex:
        for r in ex.map(
            lambda n: map_participant(n, outline, cast_line, a.model, a.chunk), names
        ):
            (d / f"{r['participant']}.json").write_text(json.dumps(r, indent=1))
            preds[r["participant"]] = {u["id"]: u.get("narrative") for u in r["units"]}
            if r["missing"]:
                print(r["participant"], "missing", len(r["missing"]))
    s = c.score(preds, a.run)
    s["spanExact"] = c.score_span(preds)
    s["config"] = vars(a)
    (d / "score.json").write_text(json.dumps(s, indent=1))
    print(
        json.dumps(
            {k: s[k] for k in ("participantAverage", "pooled", "spanExact", "units")}
        )
    )


if __name__ == "__main__":
    main()
