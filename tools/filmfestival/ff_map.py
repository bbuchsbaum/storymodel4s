#!/usr/bin/env python3
"""Run the LLM mapper on FilmFestival under the transfer protocol.

Protocol: docs/plans/2026-11-09-filmfestival-transfer-protocol.md.

Usage:
    ff_map.py manifest DATA_ROOT OUT_DIR      render every prompt and write their hashes; no calls
    ff_map.py run DATA_ROOT OUT_DIR [--jobs N] [--only sub-01_run-01,...]

DATA_ROOT is the repository's data directory. OUT_DIR must be outside the repository's tracked
files (it holds recall text in the recorded prompts). `run` refuses unless OUT_DIR/manifest.json
exists and matches what it renders, so the prompts called are the prompts frozen.

Each participant-session is mapped independently: its prompts contain the film packet and that
session's own utterances only. Every exchange is recorded under OUT_DIR/cache by the SHA-256 of
(model, system, prompt) before it is parsed.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_gold  # noqa: E402
import ff_packet  # noqa: E402

MODEL = "opus"
CHUNK = 60
ATTEMPTS = 3
# A prompt above this many characters is a protocol failure: nothing may be silently truncated.
MAX_PROMPT_CHARS = 400_000
MANIFEST_SCHEMA = "storymodel4s.filmfestival.transfer-manifest/v1"


def suffix(attempt):
    return (
        ""
        if attempt == 0
        else f"\n\nReply with strictly valid JSON only (attempt {attempt})."
    )


def parse_json(text):
    """The first JSON object (else array) in a reply, as the Sherlock harness extracted it."""
    s = text.strip()
    if s.startswith("```"):
        s = s.split("\n", 1)[1].rsplit("```", 1)[0]
    s = re.sub(r"(?<=[\[,\s])r(\d+)(?=[\],\s])", r"\1", s)
    for opener, closer in (("{", "}"), ("[", "]")):
        i, j = s.find(opener), s.rfind(closer)
        if i != -1 and j > i:
            try:
                return json.loads(s[i : j + 1])
            except json.JSONDecodeError:
                pass
    raise ValueError("no JSON in reply")


def is_int(x):
    return isinstance(x, int) and not isinstance(x, bool)


def to_prediction(item, field):
    """One judgement of one reply item as a scorer prediction.

    A scene answer keeps the film the reply stated beside it only as a diagnostic; the scorer
    takes the film from the scene dictionary. A missing key or a value of the wrong type is a
    failure, never a non-assignment.
    """
    film_key = f"{field}_film"
    if field not in item or film_key not in item:
        return {"status": "failed", "reason": "missing-field"}
    scene, film = item[field], item[film_key]
    if is_int(scene):
        return {
            "status": "answered",
            "kind": "scene",
            "scene": scene,
            "film_stated": film,
        }
    if scene is not None:
        return {"status": "failed", "reason": "invalid-scene"}
    if is_int(film):
        return {"status": "answered", "kind": "film", "film": film}
    if film is None:
        return {"status": "answered", "kind": "none"}
    return {"status": "failed", "reason": "invalid-film"}


def call(prompt, system, cache_dir, timeout=1800, retries=2):
    """One cached subscription-CLI call. The API key is removed from the child environment."""
    key = ff_packet.sha(json.dumps([MODEL, system, prompt]))
    path = os.path.join(cache_dir, f"{key}.json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)["result"]
    env = {k: v for k, v in os.environ.items() if k != "ANTHROPIC_API_KEY"}
    cmd = ["claude", "-p", "--model", MODEL, "--tools", "", "--system-prompt", system]
    cmd += ["--no-session-persistence", "--setting-sources", "", "--strict-mcp-config"]
    cmd += ["--output-format", "json"]
    last = None
    for attempt in range(retries + 1):
        with tempfile.TemporaryDirectory() as cwd:
            started = time.time()
            done = subprocess.run(
                cmd,
                input=prompt,
                capture_output=True,
                text=True,
                env=env,
                cwd=cwd,
                timeout=timeout,
            )
        try:
            doc = json.loads(done.stdout)
        except json.JSONDecodeError:
            last = f"non-json stdout (exit {done.returncode})"
            continue
        if doc.get("is_error") or done.returncode:
            last = f"error: {str(doc)[:300]}"
            time.sleep(10 * (attempt + 1))
            continue
        record = {
            "model": MODEL,
            "systemSha": ff_packet.sha(system),
            "promptSha": ff_packet.sha(prompt),
            "seconds": round(time.time() - started, 1),
            "usage": doc.get("usage"),
            "modelUsage": list((doc.get("modelUsage") or {}).keys()),
            "result": doc["result"],
        }
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(record, handle)
        return doc["result"]
    raise RuntimeError(last)


def sessions(gold_path):
    """Participant-sessions in a fixed order."""
    seen = []
    for r in ff_gold.read_tsv(gold_path):
        key = (r["participant"], r["run"])
        if key not in seen:
            seen.append(key)
    return sorted(seen)


def paths(data_root):
    return (
        os.path.join(data_root, "filmfestival/derived/gold-scenematched.tsv"),
        os.path.join(data_root, "filmfestival/derived/annotation-JL.tsv"),
        os.path.join(
            data_root, "transcription/unified-v1/full/whisper_mlx/filmfestival"
        ),
    )


def manifest(data_root):
    """Everything a run will send, by hash: the packet, the system prompt and every prompt."""
    gold_path, annotation_path, transcription_root = paths(data_root)
    gold = ff_gold.load_gold(gold_path)
    film_of, valid = ff_gold.load_dictionary(annotation_path)
    ff_gold.check_join(gold, film_of)
    packet = ff_packet.render(annotation_path, transcription_root)
    entries, longest = [], 0
    for participant, session in sessions(gold_path):
        units = ff_packet.units(gold_path, participant, session)
        prompts = ff_packet.chunks(packet, units, CHUNK)
        longest = max([longest] + [len(p) for _, _, p in prompts])
        entries.append(
            {
                "participant": participant,
                "session": session,
                "units": len(units),
                "promptSha256": [ff_packet.sha(p) for _, _, p in prompts],
            }
        )
    if longest > MAX_PROMPT_CHARS:
        raise ff_gold.GoldError(
            f"a prompt has {longest} characters; the limit is {MAX_PROMPT_CHARS}"
        )
    dictionary = json.dumps([sorted(film_of.items()), sorted(valid)])
    return packet, {
        "schema": MANIFEST_SCHEMA,
        "model": MODEL,
        "chunk": CHUNK,
        "attempts": ATTEMPTS,
        "systemSha256": ff_packet.sha(ff_packet.SYSTEM),
        "packetSha256": ff_packet.sha(packet),
        "dictionarySha256": ff_packet.sha(dictionary),
        "scenes": len(film_of),
        "validSceneTargets": len(valid),
        "population": ff_gold.population(gold),
        "longestPromptChars": longest,
        "maxPromptChars": MAX_PROMPT_CHARS,
        "calls": sum(len(e["promptSha256"]) for e in entries),
        "sessions": entries,
    }


def map_session(packet, gold_path, participant, session, cache_dir):
    units = ff_packet.units(gold_path, participant, session)
    answered, chunk_of, receipts = {}, {}, []
    for index, (lo, hi, prompt) in enumerate(ff_packet.chunks(packet, units, CHUNK)):
        items, used = None, 0
        for attempt in range(ATTEMPTS):
            used = attempt + 1
            reply = call(prompt + suffix(attempt), ff_packet.SYSTEM, cache_dir)
            try:
                parsed = parse_json(reply)["units"]
            except (ValueError, KeyError, TypeError):
                continue
            if isinstance(parsed, list):
                items = parsed
                break
        receipts.append(
            {"chunk": index, "attempts": used, "accepted": items is not None}
        )
        for u, _, _ in units:
            if lo <= u <= hi:
                chunk_of[u] = (index, items is not None)
        for item in items or []:
            if (
                isinstance(item, dict)
                and is_int(item.get("u"))
                and lo <= item["u"] <= hi
            ):
                answered[item["u"]] = item
    out = []
    for u, row, _ in units:
        item = answered.get(u)
        if item is None:
            reason = "omitted" if chunk_of[u][1] else "unparsable-chunk"
            failed = {"status": "failed", "reason": reason}
            out.append({"u": u, "row": row, "referent": failed, "narrative": failed})
            continue
        out.append(
            {
                "u": u,
                "row": row,
                "referent": to_prediction(item, "referent"),
                "narrative": to_prediction(item, "narrative"),
                "alts": item.get("alts"),
                "rows": item.get("rows"),
                "conf": item.get("conf"),
            }
        )
    return {
        "participant": participant,
        "session": session,
        "chunks": receipts,
        "units": out,
    }


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["manifest", "run"])
    ap.add_argument("data_root")
    ap.add_argument("out_dir")
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--only")
    args = ap.parse_args(argv)
    packet, doc = manifest(args.data_root)
    manifest_path = os.path.join(args.out_dir, "manifest.json")
    if args.command == "manifest":
        if os.path.exists(manifest_path):
            print("refused: manifest exists", file=sys.stderr)
            return 2
        os.makedirs(args.out_dir, exist_ok=True)
        with open(manifest_path, "w", encoding="utf-8") as handle:
            json.dump(doc, handle, indent=1, sort_keys=True)
            handle.write("\n")
        with open(
            os.path.join(args.out_dir, "packet.txt"), "w", encoding="utf-8"
        ) as handle:
            handle.write(packet)
        print(
            json.dumps({k: doc[k] for k in ("packetSha256", "systemSha256", "calls")})
        )
        return 0
    with open(manifest_path, encoding="utf-8") as handle:
        if json.load(handle) != doc:
            print(
                "refused: the rendered prompts differ from the manifest",
                file=sys.stderr,
            )
            return 2
    cache_dir = os.path.join(args.out_dir, "cache")
    replies_dir = os.path.join(args.out_dir, "replies")
    os.makedirs(cache_dir, exist_ok=True)
    os.makedirs(replies_dir, exist_ok=True)
    gold_path = paths(args.data_root)[0]
    todo = [(p, s) for p, s in sessions(gold_path)]
    if args.only:
        wanted = set(args.only.split(","))
        todo = [(p, s) for p, s in todo if f"{p}_{s}" in wanted]
    with ThreadPoolExecutor(args.jobs) as pool:
        results = list(
            pool.map(
                lambda k: map_session(packet, gold_path, k[0], k[1], cache_dir), todo
            )
        )
    for result in results:
        name = f"{result['participant']}_{result['session']}.json"
        with open(os.path.join(replies_dir, name), "w", encoding="utf-8") as handle:
            json.dump(result, handle, indent=1)
    print(f"sessions={len(results)} units={sum(len(r['units']) for r in results)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
