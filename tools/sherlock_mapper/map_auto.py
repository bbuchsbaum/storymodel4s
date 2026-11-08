#!/usr/bin/env python3
"""A6: automatic-evidence-only mapper. The film is represented without human annotation.

Usage: map_auto.py RUN_NAME [--participants ...] [--model sonnet] [--chunk 60] [--jobs 4]
       [--evidence asr|asr+frames]
The model returns a film part and a timestamp for each recall unit. Human scene boundaries are used
only afterwards, to convert timestamps to scenes for scoring against development gold.
"""

import argparse
import bisect
import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import common as c
from llm import call, parse_json

SYSTEM = """You are an expert annotator for a memory study. Participants watched the first \
episode of BBC's Sherlock ("A Study in Pink"), shown in two parts (a, then b), and then freely \
recalled it aloud. You map recall onto the film.

You get an automatic, time-stamped record of the film: the speech transcript (Whisper), and where \
available automatic visual descriptions of sampled frames. There are no human annotations. Times \
are minutes:seconds from the start of each part.

For every target unit, decide which moment of the film the participant is narrating at that \
point (the part of the film their retelling has reached). Commentary or explanation belongs to \
the moment currently being narrated. Use the surrounding recall for context; people mostly recall \
in order but sometimes jump. Infer visual events (who is where, what happens) from the dialogue \
around them when no visual description is given.

Reply with JSON only: {"units": [{"u": <unit number>, "part": "a" or "b" or null, \
"time": "m:ss" or null, "conf": <0-1>}, ...]} covering every target unit exactly once."""


def outline(evidence):
    if evidence == "none":
        return "(No film record is provided. Use only what you know about this episode. Part a is the first ~23 minutes, ending after the press conference / Sally Donovan and the pink lady case setup; part b is the remainder.)"
    lines = []
    frames = {}
    if "frames" in evidence:
        frames = json.loads(Path(c.OUT / "frame-captions.json").read_text())
    for part in "ab":
        lines.append(f"\n=== PART {part}")
        events = [(s, f'[{part} {c.fmt(s)}] "{t}"') for s, e, t in c.asr_segments(part)]
        for k, cap in frames.get(part, {}).items():
            events.append((float(k), f"[{part} {c.fmt(float(k))}] (frame) {cap}"))
        lines += [x for _, x in sorted(events)]
    return "\n".join(lines).strip()


def to_seconds(t):
    try:
        m, s = str(t).split(":")
        return int(m) * 60 + float(s)
    except (ValueError, AttributeError):
        return None


def scene_of_time(rows):
    starts = {p: [(r.start, r.scene) for r in rows if r.part == p] for p in "ab"}

    def f(part, sec):
        if part not in starts or sec is None:
            return None
        xs = starts[part]
        i = bisect.bisect_right([s for s, _ in xs], sec) - 1
        return xs[max(i, 0)][1]

    return f


def map_participant(name, text, model, chunk):
    us = c.units(name)
    out = {}
    for lo in range(0, len(us), chunk):
        hi = min(lo + chunk, len(us)) - 1
        rec = "\n".join(f"[{u.index}] {u.text}" for u in us)
        prompt = (
            f"FILM RECORD\n{text}\n\nPARTICIPANT RECALL (all units)\n{rec}\n\n"
            f"TARGET UNITS: map every unit numbered {us[lo].index} through {us[hi].index}."
        )
        for attempt in range(3):
            sfx = (
                ""
                if attempt == 0
                else f"\n\nReply with strictly valid JSON only ({attempt})."
            )
            try:
                for x in parse_json(call(prompt + sfx, SYSTEM, model=model))["units"]:
                    out[int(x["u"])] = x
                break
            except (ValueError, KeyError):
                continue
    return name, us, out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--participants")
    ap.add_argument("--allow-untouched", action="store_true")
    ap.add_argument("--model", default="sonnet")
    ap.add_argument("--chunk", type=int, default=60)
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--evidence", default="asr")
    a = ap.parse_args()
    names = c.development() + (sorted(c.partition()["untouchedTest"]) if a.allow_untouched else [])
    if a.participants:
        names = [n for n in names if n.split("_")[0] in a.participants.split(",")]
    for n in names:
        c.guard(n, a.allow_untouched)
    text = outline(a.evidence)
    conv = scene_of_time(c.film_rows())
    d = c.OUT / a.run
    d.mkdir(parents=True, exist_ok=True)
    preds = {}
    with ThreadPoolExecutor(a.jobs) as ex:
        for name, us, out in ex.map(
            lambda n: map_participant(n, text, a.model, a.chunk), names
        ):
            res = []
            for u in us:
                x = out.get(u.index, {})
                sec = to_seconds(x.get("time"))
                res.append(
                    dict(
                        index=u.index,
                        id=u.id,
                        part=x.get("part"),
                        time=x.get("time"),
                        conf=x.get("conf"),
                        scene=conv(x.get("part"), sec),
                    )
                )
            (d / f"{name}.json").write_text(
                json.dumps({"participant": name, "units": res}, indent=1)
            )
            preds[name] = {r["id"]: r["scene"] for r in res}
    s = c.score(preds, a.run)
    s["spanExact"] = c.score_span(preds)
    s["config"] = vars(a)
    s["outlineSha"] = c.sha(text)
    (d / "score.json").write_text(json.dumps(s, indent=1))
    print(
        json.dumps(
            {k: s[k] for k in ("participantAverage", "pooled", "spanExact", "units")}
        )
    )


if __name__ == "__main__":
    main()
