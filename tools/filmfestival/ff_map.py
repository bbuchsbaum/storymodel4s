#!/usr/bin/env python3
"""Run the LLM mapper on FilmFestival under the transfer protocol.

Protocol: docs/plans/2026-11-09-filmfestival-transfer-protocol.md.

Usage:
    ff_map.py manifest DATA_ROOT OUT_DIR      render every prompt and write their hashes; no calls
    ff_map.py run DATA_ROOT OUT_DIR MANIFEST [--jobs N] [--only sub-01_run-01,...]

DATA_ROOT is the repository's data directory. OUT_DIR must be outside the repository's tracked
files (it holds recall text in the recorded prompts). MANIFEST is the tracked, frozen manifest;
`run` refuses unless what it renders equals it, so the prompts called are the prompts frozen.

Each participant-session is mapped independently: its prompts contain the film packet and that
session's own utterances only. Every exchange is recorded under OUT_DIR/cache by the SHA-256 of
(model, system, prompt) before it is parsed.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_codes  # noqa: E402
import ff_gold  # noqa: E402
import ff_packet  # noqa: E402

# The model the Sherlock development runs reported for the `opus` alias, named exactly so that
# the alias cannot move underneath the evaluation.
MODEL = "claude-opus-5-5"
CLI_FLAGS = ["--tools", "", "--no-session-persistence", "--setting-sources", ""]
CLI_FLAGS += ["--strict-mcp-config", "--output-format", "json"]
# Variables that would route a call away from the owner's subscription.
ROUTING_VARIABLES = ("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL")
ROUTING_VARIABLES += ("CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX")
CHUNK = 60
ATTEMPTS = 3
SUFFIX = "\n\nReply with strictly valid JSON only (attempt {attempt})."
# A prompt above this many characters is a protocol failure: nothing may be silently truncated.
MAX_PROMPT_CHARS = 400_000
MANIFEST_SCHEMA = "storymodel4s.filmfestival.transfer-manifest/v2"


class ProtocolFailure(RuntimeError):
    """The run cannot continue as frozen; nothing is scored from a run that raised this."""


def suffix(attempt):
    return "" if attempt == 0 else SUFFIX.format(attempt=attempt)


def parse_json(text):
    """The first JSON object (else array) in a reply, as the Sherlock harness extracted it."""
    s = text.strip()
    if s.startswith("```"):
        if "\n" not in s:
            raise ValueError("a code fence with no line break")
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


def child_environment(environ):
    """The CLI's environment: everything but the API key, so calls use the subscription."""
    return {k: v for k, v in environ.items() if k != "ANTHROPIC_API_KEY"}


def cache_key(system, prompt):
    return ff_packet.sha(json.dumps([MODEL, system, prompt]))


def cli_version():
    done = subprocess.run(
        ["claude", "--version"], capture_output=True, text=True, timeout=60
    )
    if done.returncode or not done.stdout.strip():
        raise ProtocolFailure("cannot read the CLI version")
    return done.stdout.strip()


def call(prompt, system, cache_dir, version, timeout=1800, retries=2):
    """One cached subscription-CLI call; returns its record.

    A reply is recorded only if the CLI reports exactly the frozen model; anything else is a
    protocol failure. The record is written whole or not at all.
    """
    path = os.path.join(cache_dir, f"{cache_key(system, prompt)}.json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    cmd = ["claude", "-p", "--model", MODEL, "--system-prompt", system] + CLI_FLAGS
    last = None
    for attempt in range(retries + 1):
        with tempfile.TemporaryDirectory() as cwd:
            started = time.time()
            done = subprocess.run(
                cmd,
                input=prompt,
                capture_output=True,
                text=True,
                env=child_environment(os.environ),
                cwd=cwd,
                timeout=timeout,
            )
        try:
            doc = json.loads(done.stdout)
        except json.JSONDecodeError:
            last = f"non-json stdout (exit {done.returncode})"
            continue
        if (
            doc.get("is_error")
            or done.returncode
            or not isinstance(doc.get("result"), str)
        ):
            last = f"error: {str(doc)[:300]}"
            time.sleep(10 * (attempt + 1))
            continue
        reported = sorted((doc.get("modelUsage") or {}).keys())
        if reported != [MODEL]:
            raise ProtocolFailure(f"the CLI reported models {reported}, not {MODEL}")
        record = {
            "model": MODEL,
            "cliVersion": version,
            "systemSha": ff_packet.sha(system),
            "promptSha": ff_packet.sha(prompt),
            "seconds": round(time.time() - started, 1),
            "usage": doc.get("usage"),
            "modelUsage": reported,
            "result": doc["result"],
        }
        partial = f"{path}.{os.getpid()}.{time.time_ns()}.part"
        with open(partial, "w", encoding="utf-8") as handle:
            json.dump(record, handle)
        os.replace(partial, path)
        return record
    raise ProtocolFailure(f"a call failed {retries + 1} times: {last}")


def sessions(gold_path):
    """Participant-sessions in a fixed order."""
    return sorted({(r["participant"], r["run"]) for r in ff_gold.read_tsv(gold_path)})


def paths(data_root):
    return (
        os.path.join(data_root, "filmfestival/derived/gold-scenematched.tsv"),
        os.path.join(data_root, "filmfestival/derived/annotation-JL.tsv"),
        os.path.join(
            data_root, "transcription/unified-v1/full/whisper_mlx/filmfestival"
        ),
    )


def file_sha(path):
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def codes_text(data_root):
    """The raw-code table (see ff_codes.py), rebuilt from the recall workbooks."""
    found = ff_codes.rows(os.path.join(data_root, "filmfestival/recall_scenematched"))
    lines = ["participant\trun\tgold_row\tmovie\tscenes_raw"]
    lines += ["\t".join(str(x) for x in r) for r in found]
    return "\n".join(lines) + "\n"


def manifest(data_root):
    """Everything a run will send, by hash, and the reference it will be scored against.

    Returns (packet, raw-code table text, manifest).
    """
    gold_path, annotation_path, transcription_root = paths(data_root)
    codes = codes_text(data_root)
    with tempfile.NamedTemporaryFile("w", suffix=".tsv", encoding="utf-8") as handle:
        handle.write(codes)
        handle.flush()
        gold = ff_gold.load_gold(gold_path, handle.name)
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
    return (
        packet,
        codes,
        {
            "schema": MANIFEST_SCHEMA,
            "model": MODEL,
            "cliFlags": CLI_FLAGS,
            "chunk": CHUNK,
            "attempts": ATTEMPTS,
            "retrySuffix": SUFFIX,
            "goldSha256": file_sha(gold_path),
            "annotationSha256": file_sha(annotation_path),
            "codesSha256": ff_packet.sha(codes),
            "systemSha256": ff_packet.sha(ff_packet.SYSTEM),
            "packetSha256": ff_packet.sha(packet),
            "dictionarySha256": ff_packet.sha(dictionary),
            "scenes": len(film_of),
            "validSceneTargets": len(valid),
            "population": ff_gold.population(gold),
            "classes": ff_gold.classes(gold),
            "longestPromptChars": longest,
            "maxPromptChars": MAX_PROMPT_CHARS,
            "calls": sum(len(e["promptSha256"]) for e in entries),
            "sessions": entries,
        },
    )


def map_session(packet, gold_path, participant, session, cache_dir, version):
    units = ff_packet.units(gold_path, participant, session)
    answered, accepted_chunk, receipts = {}, {}, []
    for index, (lo, hi, prompt) in enumerate(ff_packet.chunks(packet, units, CHUNK)):
        items, used, outside, repeated = None, 0, 0, 0
        for attempt in range(ATTEMPTS):
            used = attempt + 1
            record = call(
                prompt + suffix(attempt), ff_packet.SYSTEM, cache_dir, version
            )
            try:
                parsed = parse_json(record["result"])["units"]
            except (ValueError, LookupError, TypeError):
                continue
            if isinstance(parsed, list):
                items = parsed
                break
        for u, _, _ in units:
            if lo <= u <= hi:
                accepted_chunk[u] = items is not None
        for item in items or []:
            if not (
                isinstance(item, dict)
                and is_int(item.get("u"))
                and lo <= item["u"] <= hi
            ):
                outside += 1
                continue
            repeated += item["u"] in answered
            answered[item["u"]] = item
        receipts.append(
            {
                "chunk": index,
                "attempts": used,
                "accepted": items is not None,
                "itemsOutsideChunk": outside,
                "repeatedUnits": repeated,
            }
        )
    out = []
    for u, row, _ in units:
        item = answered.get(u)
        if item is None:
            reason = "omitted" if accepted_chunk[u] else "unparsable-chunk"
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
        "model": MODEL,
        "cliVersion": version,
        "chunks": receipts,
        "units": out,
    }


def refusal(args, doc, environ):
    """Why `run` must not start, or None."""
    if not args.manifest:
        return "run needs the tracked manifest"
    with open(args.manifest, encoding="utf-8") as handle:
        if json.load(handle) != doc:
            return "the rendered prompts differ from the manifest"
    routed = [v for v in ROUTING_VARIABLES if environ.get(v)]
    if routed:
        return f"{routed[0]} is set; calls must use the subscription"
    replies_dir = os.path.join(args.out_dir, "replies")
    if os.path.exists(replies_dir) and os.listdir(replies_dir):
        return "replies already exist"
    return None


def selected(todo, only):
    """The participant-sessions `--only` names; None if it names an unknown one."""
    if not only:
        return todo
    wanted = set(only.split(","))
    chosen = [(p, s) for p, s in todo if f"{p}_{s}" in wanted]
    return chosen if len(chosen) == len(wanted) else None


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["manifest", "run"])
    ap.add_argument("data_root")
    ap.add_argument("out_dir")
    ap.add_argument("manifest", nargs="?")
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--only")
    args = ap.parse_args(argv)
    packet, codes, doc = manifest(args.data_root)
    if args.command == "manifest":
        manifest_path = os.path.join(args.out_dir, "manifest.json")
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
        with open(
            os.path.join(args.out_dir, "scene-codes.tsv"), "w", encoding="utf-8"
        ) as handle:
            handle.write(codes)
        print(
            json.dumps(
                {k: doc[k] for k in ("packetSha256", "systemSha256", "calls", "model")}
            )
        )
        return 0
    why = refusal(args, doc, os.environ)
    gold_path = paths(args.data_root)[0]
    todo = selected(sessions(gold_path), args.only)
    if why is None and todo is None:
        why = "--only names an unknown participant-session"
    if why:
        print(f"refused: {why}", file=sys.stderr)
        return 2
    cache_dir = os.path.join(args.out_dir, "cache")
    replies_dir = os.path.join(args.out_dir, "replies")
    os.makedirs(cache_dir, exist_ok=True)
    os.makedirs(replies_dir, exist_ok=True)
    version = cli_version()
    with ThreadPoolExecutor(args.jobs) as pool:
        results = list(
            pool.map(
                lambda k: map_session(
                    packet, gold_path, k[0], k[1], cache_dir, version
                ),
                todo,
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
