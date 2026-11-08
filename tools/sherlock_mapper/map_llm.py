#!/usr/bin/env python3
"""Context-rich LLM mapper: the whole film outline plus the whole recall, per participant.

Usage: map_llm.py RUN_NAME [--participants NN03,...] [--model opus] [--chunk 60] [--jobs 4]
       [--no-speech] [--no-captions]
Writes OUT/RUN_NAME/{participant}.json and OUT/RUN_NAME/score.json (development gold).
"""

import argparse
import json
from concurrent.futures import ThreadPoolExecutor

import common as c
from llm import call, parse_json

SYSTEM = """You are an expert annotator for a memory study. Participants watched the first \
episode of BBC's Sherlock ("A Study in Pink", about 48 minutes, shown in two parts: scenes 1-27 \
are part a, scenes 28-50 are part b) and then freely recalled it aloud. You map recall onto the \
film.

You get (1) a detailed timed annotation of the film, divided into 50 numbered scenes, with \
per-shot descriptions (rows rNNN), locations, people present, automatically transcribed dialogue \
and a short visual summary per scene; and (2) one participant's recall, split into numbered units.

For every target unit, decide which film scene the participant is talking about at that moment.
- Choose the scene whose events, people, place or dialogue the unit describes.
- Use the surrounding recall for context: pronouns, "then", "after that", and the person's \
  ongoing narrative tell you where they are. People mostly recall in film order but sometimes \
  jump back, skip ahead, or summarize.
- A unit that is commentary, hesitation or evaluation ("I don't remember", "it was funny") \
  belongs to the scene the person is currently discussing.
- Only if a unit refers to no film content at all and no current scene can be inferred, use null.
- Distinguish scenes carefully: the same people and places recur (e.g. several scenes at Baker \
  Street, several crime scenes, several conversations with Lestrade). Match the specific event.
Also give up to 3 rows (integers) that best support your choice, and your confidence 0-1.

Reply with JSON only: {"units": [{"u": <unit number>, "scene": <1-50 or null>, \
"rows": [..], "conf": <0-1>}, ...]} covering every target unit exactly once."""


SYSTEM_V2 = SYSTEM.split("For every target unit")[0] + """For every target unit, give TWO scene \
judgements, because they can differ:

1. "narrative": the scene the participant's retelling is currently at, i.e. which part of the \
film they are narrating at this point. This is how memory researchers code recall: every stretch \
of speech is labelled with the scene being described at that moment. Commentary, hesitation, \
evaluation and explanations belong to the scene currently being narrated. When the participant \
briefly cites an earlier or later event to explain what is happening now ("because earlier he had \
said..."), the narrative scene is still the current one. When they genuinely jump back or \
forward and start narrating another part of the film, the narrative scene follows them.
2. "referent": the scene where the specific event, fact or line this unit mentions actually \
happens on screen (null if it mentions no specific film content). This often equals the \
narrative scene but differs for flashbacks, explanations, anticipations and summaries.

Also give "alts": up to 3 candidate narrative scenes with probabilities summing to at most 1 \
(include your chosen narrative scene), up to 3 supporting "rows" (integers), and "conf" 0-1.
Distinguish recurring people and places carefully (several Baker Street scenes, several crime \
scenes, several deduction scenes about the same victim): match the specific moment.

Reply with JSON only: {"units": [{"u": <unit number>, "narrative": <1-50 or null>, \
"referent": <1-50 or null>, "alts": [[scene, p], ...], "rows": [..], "conf": <0-1>}, ...]} \
covering every target unit exactly once."""


def prompt_for(outline, units, lo, hi):
    rec = "\n".join(f"[{u.index}] {u.text}" for u in units)
    return (
        f"FILM ANNOTATION\n{outline}\n\nPARTICIPANT RECALL (all units)\n{rec}\n\n"
        f"TARGET UNITS: map every unit numbered {lo} through {hi} inclusive."
    )


def map_participant(name, outline, model, chunk, version="v1"):
    system = SYSTEM if version == "v1" else SYSTEM_V2
    us = c.units(name)
    out = {}
    for lo in range(0, len(us), chunk):
        hi = min(lo + chunk, len(us)) - 1
        prompt = prompt_for(outline, us, us[lo].index, us[hi].index)
        items = None
        for attempt in range(3):
            suffix = "" if attempt == 0 else f"\n\nReply with strictly valid JSON only (attempt {attempt})."
            try:
                items = parse_json(call(prompt + suffix, system, model=model))["units"]
                break
            except (ValueError, KeyError):
                continue
        for x in items or []:
            out[int(x["u"])] = x
    missing = [u.index for u in us if u.index not in out]
    return {
        "participant": name,
        "missing": missing,
        "units": [
            dict(
                index=u.index,
                id=u.id,
                **{
                    k: out.get(u.index, {}).get(k)
                    for k in ("scene", "narrative", "referent", "alts", "rows", "conf")
                },
            )
            for u in us
        ],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--participants")
    ap.add_argument("--allow-untouched", action="store_true")
    ap.add_argument("--model", default="opus")
    ap.add_argument("--chunk", type=int, default=60)
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--no-speech", action="store_true")
    ap.add_argument("--no-captions", action="store_true")
    ap.add_argument("--prompt", default="v1", choices=["v1", "v2"])
    a = ap.parse_args()
    names = c.development() + (sorted(c.partition()["untouchedTest"]) if a.allow_untouched else [])
    if a.participants:
        names = [n for n in names if n.split("_")[0] in a.participants.split(",")]
    for n in names:
        c.guard(n, a.allow_untouched)
    outline, _ = c.film_outline(not a.no_speech, not a.no_captions)
    d = c.OUT / a.run
    d.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(a.jobs) as ex:
        res = list(
            ex.map(lambda n: map_participant(n, outline, a.model, a.chunk, a.prompt), names)
        )
    preds = {}
    for r in res:
        (d / f"{r['participant']}.json").write_text(json.dumps(r, indent=1))
        key = "scene" if a.prompt == "v1" else "narrative"
        preds[r["participant"]] = {u["id"]: u[key] for u in r["units"]}
        if r["missing"]:
            print(r["participant"], "missing", len(r["missing"]))
    s = c.score(preds, a.run)
    s["config"] = vars(a)
    s["outlineSha"] = c.sha(outline)
    (d / "score.json").write_text(json.dumps(s, indent=1))
    print(json.dumps({k: s[k] for k in ("participantAverage", "pooled", "units")}))
    for k, v in s["per"].items():
        print(k, v)


if __name__ == "__main__":
    main()
