"""Content-addressed, cached calls to subscription CLIs (claude -p, codex exec).

The API key is removed from the child environment so calls use the owner's subscription.
Responses are cached under the data root by the SHA-256 of (model, system, prompt).
"""

import json
import os
import subprocess
import tempfile
import time
from pathlib import Path

from common import OUT, sha

CACHE = OUT / "cache"


def call(prompt, system, model="opus", timeout=1800, retries=2):
    key = sha(json.dumps([model, system, prompt]))
    path = CACHE / f"{key}.json"
    if path.exists():
        return json.loads(path.read_text())["result"]
    CACHE.mkdir(parents=True, exist_ok=True)
    env = {k: v for k, v in os.environ.items() if k != "ANTHROPIC_API_KEY"}
    cmd = [
        "claude",
        "-p",
        "--model",
        model,
        "--tools",
        "",
        "--system-prompt",
        system,
        "--no-session-persistence",
        "--setting-sources",
        "",
        "--strict-mcp-config",
        "--output-format",
        "json",
    ]
    last = None
    for attempt in range(retries + 1):
        with tempfile.TemporaryDirectory() as cwd:
            t0 = time.time()
            p = subprocess.run(
                cmd,
                input=prompt,
                capture_output=True,
                text=True,
                env=env,
                cwd=cwd,
                timeout=timeout,
            )
        try:
            d = json.loads(p.stdout)
        except json.JSONDecodeError:
            last = f"non-json stdout (exit {p.returncode}): {p.stdout[:300]} {p.stderr[:300]}"
            continue
        if d.get("is_error") or p.returncode:
            last = f"error: {str(d)[:400]}"
            time.sleep(10 * (attempt + 1))
            continue
        rec = dict(
            model=model,
            systemSha=sha(system),
            promptSha=sha(prompt),
            seconds=round(time.time() - t0, 1),
            usage=d.get("usage"),
            modelUsage=list((d.get("modelUsage") or {}).keys()),
            result=d["result"],
        )
        path.write_text(json.dumps(rec))
        return d["result"]
    raise RuntimeError(last)


def parse_json(text):
    """Extract the first JSON object or array from a model reply."""
    s = text.strip()
    if s.startswith("```"):
        s = s.split("\n", 1)[1].rsplit("```", 1)[0]
    import re

    s = re.sub(r"(?<=[\[,\s])r(\d+)(?=[\],\s])", r"\1", s)
    for opener, closer in (("{", "}"), ("[", "]")):
        i, j = s.find(opener), s.rfind(closer)
        if i != -1 and j > i:
            try:
                return json.loads(s[i : j + 1])
            except json.JSONDecodeError:
                pass
    raise ValueError("no JSON in reply: " + text[:200])
