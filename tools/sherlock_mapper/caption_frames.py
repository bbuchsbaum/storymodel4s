#!/usr/bin/env python3
"""Recall-blind frame captions with Claude vision (subscription CLI, Read tool only).

Usage: caption_frames.py [--model sonnet] [--batch 16] [--jobs 3]
Frames: OUT/frames/{a,b}/fNNNN.jpg sampled every 4 s (frame k at about (k-1)*4 s).
Writes OUT/frame-captions.json = {"a": {"<seconds>": caption}, "b": {...}}.
No recall text is ever sent with frames.
"""

import argparse
import json
import os
import subprocess
from concurrent.futures import ThreadPoolExecutor

import common as c
from llm import parse_json

FRAMES = c.OUT / "frames"
CACHE = c.OUT / "frame-caption-cache"
SYSTEM = """You describe film frames for a research index. For each image, write one factual \
sentence (max 30 words): who is visible (describe people by appearance, clothing, role cues; do \
not guess real identities), what they are doing, the setting, and any readable on-screen text. \
Note continuity only if clearly visible. Reply with JSON only: {"<file name>": "<sentence>", ...}."""


def caption_batch(files, model):
    key = c.sha(json.dumps([model, SYSTEM, [str(f) for f in files]]))
    path = CACHE / f"{key}.json"
    if path.exists():
        return json.loads(path.read_text())
    env = {k: v for k, v in os.environ.items() if k != "ANTHROPIC_API_KEY"}
    prompt = "Read and describe each of these images:\n" + "\n".join(
        str(f) for f in files
    )
    cmd = [
        "claude",
        "-p",
        "--model",
        model,
        "--tools",
        "Read",
        "--allowedTools",
        "Read",
        "--add-dir",
        str(FRAMES),
        "--system-prompt",
        SYSTEM,
        "--no-session-persistence",
        "--setting-sources",
        "",
        "--strict-mcp-config",
        "--output-format",
        "json",
    ]
    for _ in range(3):
        p = subprocess.run(
            cmd,
            input=prompt,
            capture_output=True,
            text=True,
            env=env,
            cwd=str(FRAMES),
            timeout=1800,
        )
        try:
            out = parse_json(json.loads(p.stdout)["result"])
            CACHE.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(out))
            return out
        except (ValueError, KeyError, json.JSONDecodeError):
            continue
    return {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="sonnet")
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--jobs", type=int, default=3)
    a = ap.parse_args()
    jobs = []
    for part in "ab":
        files = sorted((FRAMES / part).glob("f*.jpg"))
        jobs += [(part, files[i : i + a.batch]) for i in range(0, len(files), a.batch)]
    result = {"a": {}, "b": {}}
    with ThreadPoolExecutor(a.jobs) as ex:
        for (part, files), caps in zip(
            jobs, ex.map(lambda j: caption_batch(j[1], a.model), jobs)
        ):
            for f in files:
                cap = caps.get(f.name) or caps.get(str(f))
                if cap:
                    sec = (int(f.stem[1:]) - 1) * 4
                    result[part][str(sec)] = cap
    (c.OUT / "frame-captions.json").write_text(json.dumps(result, indent=1))
    print({p: len(v) for p, v in result.items()}, "of", sum(len(f) for _, f in jobs))


if __name__ == "__main__":
    main()
