#!/usr/bin/env python3
"""Run the LLM mapper on FilmFestival under the transfer protocol.

Protocols: docs/plans/2026-11-09-filmfestival-transfer-protocol.md and, for the input conditions
other than `annotation`, docs/plans/2026-11-10-filmfestival-automatic-input-protocol.md.

Usage:
    ff_map.py manifest DATA_ROOT OUT_DIR MODEL [CONDITION]
                                                 render every prompt and write hashes; no calls
    ff_map.py run DATA_ROOT OUT_DIR MANIFEST [--jobs N] [--only sub-01_run-01,...]
    ff_map.py smoke OUT_DIR MODEL                one call with no corpus content

MODEL is one of the two arms (see ARMS). CONDITION is what the model is given as the films:
`annotation` (the default: human scene descriptions), `auto` (frame descriptions and speech) or
`norecord` (numbers, titles and lengths only). `run` takes its arm and its condition from
MANIFEST, never from the command line.

One system, several inputs. A condition supplies the film packet, the system prompt and the rule
that reads one reply item; chunking, retries, the cache, the model check, the refusals, the
receipts and the reply-file shape are shared. `auto` and `norecord` answer with a time, which
`ff_time` converts to the same scene predictions, keeping the raw answer beside them. They read
the frame list and the frame descriptions from DATA_ROOT/study/filmfestival/auto-20261110.

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
import ff_time  # noqa: E402

# The two arms, each named exactly so that an alias cannot move underneath the evaluation. The
# primary arm is the model the Sherlock development runs reported for the `opus` alias.
PRIMARY = "claude-opus-5-5"
ARMS = (PRIMARY, "claude-haiku-5-5")
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
# A manifest that names a condition has more fields. One without the field is `annotation`: the
# manifests frozen on 2026-11-09 stay exactly as they are.
CONDITION_MANIFEST_SCHEMA = "storymodel4s.filmfestival.transfer-manifest/v3"
ANNOTATION, AUTO, NORECORD = "annotation", "auto", "norecord"
CONDITIONS = (ANNOTATION, AUTO, NORECORD)
# Where the automatic film record lives under DATA_ROOT (see ff_frames.py and ff_caption.py).
AUTO_DIR = "study/filmfestival/auto-20261110"
NEAR_RULE_TIME = "time-2s"


class ProtocolFailure(RuntimeError):
    """The run cannot continue as frozen; nothing is scored from a run that raised this."""


def suffix(attempt):
    return "" if attempt == 0 else SUFFIX.format(attempt=attempt)


def parse_json(text, bare_rows=True):
    """The first JSON object (else array) in a reply, as the Sherlock harness extracted it.

    `bare_rows` rewrites unquoted row ids (r114) as numbers, which the annotation-assisted reply
    may contain. A caller whose reply holds free text switches it off.
    """
    s = text.strip()
    if s.startswith("```"):
        if "\n" not in s:
            raise ValueError("a code fence with no line break")
        s = s.split("\n", 1)[1].rsplit("```", 1)[0]
    if bare_rows:
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


def to_time_prediction(table):
    """The reader of one reply item in a condition that answers with a time.

    The conversion itself is `ff_time.prediction`; this only finds the two values. A missing key
    is a failure, never a non-assignment.
    """

    def read(item, field):
        film_key, time_key = f"{field}_film", f"{field}_time"
        if film_key not in item or time_key not in item:
            return {"status": "failed", "reason": "missing-field"}
        return ff_time.prediction(table, item[film_key], item[time_key])

    return read


class Condition:
    """What differs between input conditions; everything else in a run is shared."""

    def __init__(self, name, system, heading, read, extras, record):
        self.name = name
        self.system = system
        # The line that introduces the film packet in the user prompt.
        self.heading = heading
        # (reply item, field) -> prediction.
        self.read = read
        # Reply-item keys copied to the reply file beside the two predictions.
        self.extras = extras
        # Manifest fields this condition adds.
        self.record = record


ANNOTATION_CONDITION = Condition(
    ANNOTATION,
    ff_packet.SYSTEM,
    "FILM ANNOTATION",
    to_prediction,
    ("alts", "rows", "conf"),
    {},
)


def auto_paths(data_root):
    """(frame list, frame descriptions) of the automatic film record."""
    base = os.path.join(data_root, AUTO_DIR)
    return os.path.join(base, "frames.json"), os.path.join(base, "frame-captions.json")


def prepare(data_root, name):
    """(condition, packet text, scene-interval text or None) for a named condition.

    The time conditions need the frame list (for the clip lengths), and `auto` needs the frame
    descriptions; without them this refuses.
    """
    _, annotation_path, transcription_root = paths(data_root)
    if name == ANNOTATION:
        packet = ff_packet.render(annotation_path, transcription_root)
        return ANNOTATION_CONDITION, packet, None
    if name not in CONDITIONS:
        raise ff_gold.GoldError(f"unknown condition {name!r}")
    # Imported here: ff_auto_packet reads the describer's constants, and the describer uses
    # this module's call helper.
    import ff_auto_packet

    frames_path, captions_path = auto_paths(data_root)
    needed = [frames_path] + ([captions_path] if name == AUTO else [])
    for path in needed:
        if not os.path.exists(path):
            raise ff_gold.GoldError(f"the {name} condition needs {path}")
    seconds = ff_time.durations(frames_path)
    table = ff_time.load(annotation_path, seconds)
    intervals = ff_time.canonical(table)
    record = {
        "condition": name,
        "framesSha256": file_sha(frames_path),
        "clipSeconds": {str(film): seconds[film] for film in sorted(seconds)},
        "clock": {str(film): ff_time.CLOCK[film] for film in sorted(ff_time.CLOCK)},
        "clockSha256": ff_packet.sha(json.dumps(ff_time.CLOCK, sort_keys=True)),
        "sceneIntervalsSha256": ff_packet.sha(intervals),
        "toleranceSeconds": ff_time.TOLERANCE,
        "nearRule": NEAR_RULE_TIME,
    }
    if name == AUTO:
        record["captionsSha256"] = file_sha(captions_path)
        packet = ff_auto_packet.render_auto(
            annotation_path, transcription_root, frames_path, captions_path
        )
    else:
        packet = ff_auto_packet.render_norecord(annotation_path, frames_path)
    condition = Condition(
        name,
        ff_auto_packet.SYSTEM[name],
        ff_auto_packet.HEADING[name],
        to_time_prediction(table),
        ("conf",),
        record,
    )
    return condition, packet, intervals


def child_environment(environ):
    """The CLI's environment: everything but the API key, so calls use the subscription."""
    return {k: v for k, v in environ.items() if k != "ANTHROPIC_API_KEY"}


def cache_key(model, system, prompt):
    return ff_packet.sha(json.dumps([model, system, prompt]))


def cli_version():
    done = subprocess.run(
        ["claude", "--version"], capture_output=True, text=True, timeout=60
    )
    if done.returncode or not done.stdout.strip():
        raise ProtocolFailure("cannot read the CLI version")
    return done.stdout.strip()


def call(
    model,
    prompt,
    system,
    cache_dir,
    version,
    timeout=1800,
    retries=2,
    flags=None,
    cwd=None,
    key=None,
):
    """One cached subscription-CLI call; returns its record.

    A reply is recorded only if the CLI reports exactly the frozen model; anything else is a
    protocol failure. The record is written whole or not at all.

    Every caller shares this path. `flags` replaces the mapper's CLI flags (the frame describer
    needs the Read tool), `cwd` replaces the empty working directory, and `key` replaces the
    cache key of (model, system, prompt) for a caller whose prompt does not identify its input.
    """
    path = os.path.join(cache_dir, f"{key or cache_key(model, system, prompt)}.json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    cmd = ["claude", "-p", "--model", model, "--system-prompt", system]
    cmd += CLI_FLAGS if flags is None else flags
    last = None
    for attempt in range(retries + 1):
        with tempfile.TemporaryDirectory() as empty:
            started = time.time()
            done = subprocess.run(
                cmd,
                input=prompt,
                capture_output=True,
                text=True,
                env=child_environment(os.environ),
                cwd=empty if cwd is None else cwd,
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
        if reported != [model]:
            raise ProtocolFailure(f"the CLI reported models {reported}, not {model}")
        record = {
            "model": model,
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


def manifest(data_root, model, condition=ANNOTATION):
    """Everything a run will send, by hash, and the reference it will be scored against.

    Returns (packet, raw-code table text, manifest).
    """
    made = plan(data_root, model, condition)
    return made["packet"], made["codes"], made["manifest"]


def plan(data_root, model, name):
    """A run as planned: its condition, packet, raw-code table, scene intervals and manifest."""
    gold_path, annotation_path, _ = paths(data_root)
    condition, packet, intervals = prepare(data_root, name)
    codes = codes_text(data_root)
    with tempfile.NamedTemporaryFile("w", suffix=".tsv", encoding="utf-8") as handle:
        handle.write(codes)
        handle.flush()
        gold = ff_gold.load_gold(gold_path, handle.name)
    film_of, valid = ff_gold.load_dictionary(annotation_path)
    ff_gold.check_join(gold, film_of)
    entries, longest = [], 0
    for participant, session in sessions(gold_path):
        units = ff_packet.units(gold_path, participant, session)
        prompts = ff_packet.chunks(packet, units, CHUNK, condition.heading)
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
    doc = {
        "schema": MANIFEST_SCHEMA,
        "model": model,
        "primaryArm": model == PRIMARY,
        "cliFlags": CLI_FLAGS,
        "chunk": CHUNK,
        "attempts": ATTEMPTS,
        "retrySuffix": SUFFIX,
        "goldSha256": file_sha(gold_path),
        "annotationSha256": file_sha(annotation_path),
        "codesSha256": ff_packet.sha(codes),
        "systemSha256": ff_packet.sha(condition.system),
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
    }
    if condition.record:
        doc.update(condition.record, schema=CONDITION_MANIFEST_SCHEMA)
    return {
        "condition": condition,
        "packet": packet,
        "codes": codes,
        "intervals": intervals,
        "manifest": doc,
    }


def map_session(
    model,
    packet,
    gold_path,
    participant,
    session,
    cache_dir,
    version,
    condition=ANNOTATION_CONDITION,
):
    units = ff_packet.units(gold_path, participant, session)
    answered, accepted_chunk, receipts = {}, {}, []
    prompts = ff_packet.chunks(packet, units, CHUNK, condition.heading)
    for index, (lo, hi, prompt) in enumerate(prompts):
        items, used, outside, repeated = None, 0, 0, 0
        for attempt in range(ATTEMPTS):
            used = attempt + 1
            record = call(
                model, prompt + suffix(attempt), condition.system, cache_dir, version
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
        unit = {
            "u": u,
            "row": row,
            "referent": condition.read(item, "referent"),
            "narrative": condition.read(item, "narrative"),
        }
        unit.update({key: item.get(key) for key in condition.extras})
        out.append(unit)
    return {
        "participant": participant,
        "session": session,
        "model": model,
        "cliVersion": version,
        "chunks": receipts,
        "units": out,
    }


def refusal(args, doc, environ, todo):
    """Why `run` must not start, or None. `todo` is the participant-sessions it would map."""
    if not args.manifest:
        return "run needs the tracked manifest"
    with open(args.manifest, encoding="utf-8") as handle:
        if json.load(handle) != doc:
            return "the rendered prompts differ from the manifest"
    routed = [v for v in ROUTING_VARIABLES if environ.get(v)]
    if routed:
        return f"{routed[0]} is set; calls must use the subscription"
    if todo is None:
        return "--only names an unknown participant-session"
    replies_dir = os.path.join(args.out_dir, "replies")
    for participant, session in todo:
        if os.path.exists(os.path.join(replies_dir, f"{participant}_{session}.json")):
            return f"a reply file for {participant}_{session} already exists"
    return None


def selected(todo, only):
    """The participant-sessions `--only` names; None if it names an unknown one."""
    if not only:
        return todo
    wanted = set(only.split(","))
    chosen = [(p, s) for p, s in todo if f"{p}_{s}" in wanted]
    return chosen if len(chosen) == len(wanted) else None


def write_text(path, text):
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)


def main(argv):
    if len(argv) == 3 and argv[0] == "smoke" and argv[2] in ARMS:
        # No film or recall content: only whether the CLI reports exactly the named model.
        os.makedirs(argv[1], exist_ok=True)
        record = call(
            argv[2],
            "Reply with the single word: ready",
            "You are a test probe.",
            argv[1],
            cli_version(),
        )
        print(json.dumps({k: record[k] for k in ("model", "modelUsage", "cliVersion")}))
        return 0
    if len(argv) in (4, 5) and argv[0] == "manifest" and argv[3] in ARMS:
        data_root, out_dir, model = argv[1:4]
        name = argv[4] if len(argv) == 5 else ANNOTATION
        manifest_path = os.path.join(out_dir, "manifest.json")
        if os.path.exists(manifest_path):
            print("refused: manifest exists", file=sys.stderr)
            return 2
        try:
            made = plan(data_root, model, name)
        except ff_gold.GoldError as error:
            print(f"refused: {error}", file=sys.stderr)
            return 2
        doc = made["manifest"]
        os.makedirs(out_dir, exist_ok=True)
        write_text(manifest_path, json.dumps(doc, indent=1, sort_keys=True) + "\n")
        write_text(os.path.join(out_dir, "packet.txt"), made["packet"])
        write_text(os.path.join(out_dir, "scene-codes.tsv"), made["codes"])
        if made["intervals"] is not None:
            write_text(os.path.join(out_dir, "scene-intervals.tsv"), made["intervals"])
        keys = ("packetSha256", "systemSha256", "calls", "model", "primaryArm")
        print(json.dumps({"condition": name, **{k: doc[k] for k in keys}}))
        return 0
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["run"])
    ap.add_argument("data_root")
    ap.add_argument("out_dir")
    ap.add_argument("manifest")
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--only")
    try:
        args = ap.parse_args(argv)
    except SystemExit:
        print(__doc__, file=sys.stderr)
        return 2
    # The arm and the condition are whatever the tracked manifest names; neither is ever chosen
    # on the command line.
    with open(args.manifest, encoding="utf-8") as handle:
        tracked = json.load(handle)
    model, name = tracked.get("model"), tracked.get("condition", ANNOTATION)
    if model not in ARMS or name not in CONDITIONS:
        print("refused: the manifest names no known arm or condition", file=sys.stderr)
        return 2
    try:
        made = plan(args.data_root, model, name)
    except ff_gold.GoldError as error:
        print(f"refused: {error}", file=sys.stderr)
        return 2
    packet, doc, condition = made["packet"], made["manifest"], made["condition"]
    gold_path = paths(args.data_root)[0]
    todo = selected(sessions(gold_path), args.only)
    why = refusal(args, doc, os.environ, todo)
    if why:
        print(f"refused: {why}", file=sys.stderr)
        return 2
    cache_dir = os.path.join(args.out_dir, "cache")
    replies_dir = os.path.join(args.out_dir, "replies")
    os.makedirs(cache_dir, exist_ok=True)
    os.makedirs(replies_dir, exist_ok=True)
    version = cli_version()

    def one(key):
        return map_session(
            model, packet, gold_path, key[0], key[1], cache_dir, version, condition
        )

    with ThreadPoolExecutor(args.jobs) as pool:
        results = list(pool.map(one, todo))
    for result in results:
        name = f"{result['participant']}_{result['session']}.json"
        write_text(os.path.join(replies_dir, name), json.dumps(result, indent=1))
    units = sum(len(r["units"]) for r in results)
    print(f"model={model} condition={name} sessions={len(results)} units={units}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
