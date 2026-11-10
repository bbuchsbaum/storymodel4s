#!/usr/bin/env python3
"""Describe the sampled FilmFestival frames with a vision model, blind to the film's identity.

Protocol: docs/plans/2026-11-10-filmfestival-automatic-input-protocol.md, "Frame descriptions".

Usage:
    ff_caption.py manifest OUT_DIR [NAME]          plan every batch and write hashes; no calls
    ff_caption.py run OUT_DIR MANIFEST [--jobs N]  describe the frames; writes frame-captions.json
    ff_caption.py smoke OUT_DIR                    one call on a generated solid-colour image
    ff_caption.py audit OUT_DIR                    print what a person should check; no calls

OUT_DIR holds frames.json and frames/ from ff_frames.py. MANIFEST is the tracked, frozen
manifest; `run` refuses unless what it plans equals it. `manifest` writes
OUT_DIR/caption-manifest.json, or OUT_DIR/NAME, and never overwrites.

Blind to everything we add. The frame files live in directories named after the films, so the
model is never shown them. Each batch is copied into a fresh temporary directory under neutral
names (img_01.jpg, img_02.jpg, ...), and the prompt is one fixed line followed by those paths
and nothing else. A path that contains a film's name is refused before any call. No recall, no
annotation, no film title and no film number is sent. The picture itself is not hidden: a frame
can show an on-screen title.

File access is confined and audited. The CLI runs with the Read tool only, in `--restricted`
mode (file tools confined to the working directory and `--add-dir`, both the batch's temporary
directory) and with permission prompts answered by nobody. Replies are read from the CLI's
event stream, so every tool the model used is seen: a call that used any tool but Read, or read
any path outside the batch's directory, is a protocol failure and is not recorded. The paths
read are kept in the call's record. A frame the model did not read gets no description from
that reply, whatever the reply says about it; a Read that returned an error, or has no result
in the stream, is not a read.

Calls go through `ff_map.call`, so the model check, the stripped API key, the atomic cache write
and the recorded CLI version are the mapper's. A batch is 16 frames of one film in time order.
The cache key is the SHA-256 of (model, instruction, the place in the batch and content hash of
each frame asked for, attempt number). It follows the images and not their paths; the place is
included so that two identical frames from different batches cannot share a reply that names
another file. A batch gets up to three attempts; each later attempt asks only for the frames
still lacking a usable description.

A usable description is text of at least 2 words ("Black screen.") and at most 400 characters
that does not match one of NOT_A_DESCRIPTION, a short list of phrases with which a model
declines or reports an error instead of describing ("unable to", "cannot read", "could not be
loaded", "error", ...). Anything else is asked
again, and after three attempts the frame is recorded as undescribed. If more than 2% of any
film's frames are undescribed, `run` exits non-zero and writes no captions file. A frame that
really shows the word "error" will be lost to the pattern; `audit` lists what was lost, and
every accepted description that mentions an image, a file, loading, reading or an error.
"""

import argparse
import contextlib
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_frames  # noqa: E402
import ff_map  # noqa: E402
import ff_packet  # noqa: E402

# The model that described the Sherlock frames, named exactly.
MODEL = "claude-sonnet-5-5"
# The Sherlock instruction (tools/sherlock_mapper/caption_frames.py), unchanged.
SYSTEM = """You describe film frames for a research index. For each image, write one factual \
sentence (max 30 words): who is visible (describe people by appearance, clothing, role cues; do \
not guess real identities), what they are doing, the setting, and any readable on-screen text. \
Note continuity only if clearly visible. Reply with JSON only: {"<file name>": "<sentence>", ...}."""
PROMPT = "Read and describe each of these images:\n"
BATCH = 16
ATTEMPTS = 3
MAX_UNDESCRIBED = 0.02
# A longer reply is not the one sentence asked for; the frame is asked again.
MAX_DESCRIPTION_CHARS = 400
MIN_DESCRIPTION_WORDS = 2
# Phrases of a refusal or an error report, matched as whole words in any case. A reply holding
# one is not a description.
NOT_A_DESCRIPTION = (
    "unable to",
    "not able to",
    "cannot read",
    "can't read",
    "could not read",
    "couldn't read",
    "cannot view",
    "can't view",
    "cannot see",
    "can't see",
    "cannot access",
    "can't access",
    "i don't have",
    "i do not have",
    "could not be loaded",
    "could not open",
    "not available",
    "permission denied",
    "file not found",
    "failed to",
    "error",
)
_NOT_A_DESCRIPTION = re.compile(
    "|".join(
        rf"(?<![a-z]){re.escape(phrase)}(?![a-z])" for phrase in NOT_A_DESCRIPTION
    ),
    re.IGNORECASE,
)
# What `audit` shows a person even when the description was accepted.
SUSPECT = re.compile(r"image|file|load|read|unable|cannot|error", re.IGNORECASE)
# `{frames}` is the temporary directory holding the batch. `--restricted` confines the file
# tools to it; `--permission-prompts none` denies anything that would ask; the event stream
# (`stream-json`, which needs `--verbose` with `-p`) shows every tool the model used.
CLI_FLAGS = ["--tools", "Read", "--allowedTools", "Read", "--add-dir", "{frames}"]
CLI_FLAGS += ["--restricted", "--permission-prompts", "none"]
CLI_FLAGS += ["--no-session-persistence", "--setting-sources", ""]
CLI_FLAGS += ["--strict-mcp-config", "--output-format", "stream-json", "--verbose"]
MANIFEST_SCHEMA = "storymodel4s.filmfestival.caption-manifest/v2"
CAPTIONS_SCHEMA = "storymodel4s.filmfestival.frame-captions/v1"


def neutral(position):
    """The name a frame is shown under: its place in the batch and nothing else."""
    return f"img_{position + 1:02d}.jpg"


def leak(text):
    """The film name found in `text`, or None. Names are matched with any separator."""
    squeezed = "".join(c for c in text.lower() if c.isalnum())
    for cover in ff_packet.CLIPS.values():
        if cover.replace("_", "") in squeezed:
            return cover
    return None


def key(asked, attempt):
    """The cache key of one call. `asked` is [(place in the batch, content hash), ...]."""
    return ff_packet.sha(
        json.dumps([MODEL, SYSTEM, [list(pair) for pair in asked], attempt])
    )


def plan(frames_doc):
    """Batches in film and time order: [{"film", "frames": [frame entries]}, ...]."""
    out = []
    for entry in frames_doc["films"]:
        frames = entry["frames"]
        for i in range(0, len(frames), BATCH):
            out.append({"film": entry["film"], "frames": frames[i : i + BATCH]})
    return out


@contextlib.contextmanager
def staged(root, frames, positions):
    """Copy the frames at `positions` to a temporary directory under neutral names.

    Yields (directory, {position: path}). Refuses a frame whose bytes are not the bytes
    frames.json lists, and a directory whose path would name a film.
    """
    with tempfile.TemporaryDirectory(prefix="frames-") as directory:
        directory = os.path.realpath(directory)
        if leak(directory):
            raise ff_map.ProtocolFailure("the temporary directory's path names a film")
        shown = {}
        for position in positions:
            with open(os.path.join(root, frames[position]["file"]), "rb") as handle:
                data = handle.read()
            if hashlib.sha256(data).hexdigest() != frames[position]["sha256"]:
                raise ff_map.ProtocolFailure(
                    f"{frames[position]['file']} is not the frame frames.json lists"
                )
            shown[position] = os.path.join(directory, neutral(position))
            with open(shown[position], "wb") as handle:
                handle.write(data)
        yield directory, shown


def usable(value):
    """A description as one line of text, or None when the reply's value is not one."""
    if not isinstance(value, str):
        return None
    text = " ".join(value.split())
    if len(text) > MAX_DESCRIPTION_CHARS or len(text.split()) < MIN_DESCRIPTION_WORDS:
        return None
    return None if _NOT_A_DESCRIPTION.search(text) else text


def read_reply(text, positions):
    """({position: description}, stray): descriptions by frame, and keys naming no asked frame.

    The model names a file by the path it was given or by its base name. A file named twice gets
    no description from this reply.
    """
    try:
        doc = ff_map.parse_json(text, bare_rows=False)
    except ValueError:
        return {}, 0
    if not isinstance(doc, dict):
        return {}, 0
    wanted = {neutral(p): p for p in positions}
    found, twice, stray = {}, set(), 0
    for name, value in doc.items():
        position = wanted.get(os.path.basename(str(name)))
        if position is None:
            stray += 1
        elif position in found or position in twice:
            twice.add(position)
            found.pop(position, None)
        else:
            found[position] = usable(value)
    return {p: d for p, d in found.items() if d is not None}, stray


def audit_reads(directory):
    """The check `ff_map.call` runs on a call's record before it is kept.

    Every tool the model used must be Read, on a path inside `directory`, whether or not the
    read succeeded. The paths of the reads that succeeded are added to the record as
    `readPaths`, and their base names as `readNames`; a Read whose result is an error, or has no
    result in the stream, opened nothing and is counted in `failedReads`.
    """

    def check(record):
        paths, failed = [], 0
        for use in record.get("toolUses") or []:
            given = (
                use["input"].get("file_path")
                if isinstance(use["input"], dict)
                else None
            )
            if use["name"] != "Read" or not isinstance(given, str):
                raise ff_map.ProtocolFailure(
                    f"the model used the tool {use['name']!r}, not Read on a file"
                )
            resolved = os.path.realpath(os.path.join(directory, given))
            if os.path.dirname(resolved) != directory:
                raise ff_map.ProtocolFailure(
                    f"the model read {given}, outside the batch's directory"
                )
            if use.get("ok"):
                paths.append(resolved)
            else:
                failed += 1
        record["failedReads"] = failed
        record["readPaths"] = paths
        record["readNames"] = sorted({os.path.basename(p) for p in paths})

    return check


def describe(root, frames, cache_dir, version):
    """Descriptions for one batch: ([description or None per frame], receipt)."""
    found, attempts, stray, unread, reads, failed = {}, 0, 0, 0, [], 0
    for attempt in range(ATTEMPTS):
        missing = [p for p in range(len(frames)) if p not in found]
        if not missing:
            break
        attempts = attempt + 1
        with staged(root, frames, missing) as (directory, shown):
            prompt = PROMPT + "\n".join(shown[p] for p in missing)
            record = ff_map.call(
                MODEL,
                prompt + ff_map.suffix(attempt),
                SYSTEM,
                cache_dir,
                version,
                flags=[f.replace("{frames}", directory) for f in CLI_FLAGS],
                cwd=directory,
                key=key([(p, frames[p]["sha256"]) for p in missing], attempt),
                check=audit_reads(directory),
            )
        got, extra = read_reply(record["result"], missing)
        # A description of a file the model did not open is not a description of the frame.
        seen = set(record.get("readNames") or [])
        unread += sum(1 for p in got if neutral(p) not in seen)
        found.update({p: d for p, d in got.items() if neutral(p) in seen})
        stray += extra
        reads.append(record.get("readNames") or [])
        failed += record.get("failedReads") or 0
    receipt = {
        "attempts": attempts,
        "strayNames": stray,
        "describedButNotRead": unread,
        "failedReads": failed,
        "eventTypes": record.get("eventTypes") if attempts else None,
        "reads": reads,
    }
    return [found.get(p) for p in range(len(frames))], receipt


def manifest(out_dir):
    """Everything a run will ask, by hash."""
    frames_doc = ff_frames.load(out_dir)
    batches = plan(frames_doc)
    return {
        "schema": MANIFEST_SCHEMA,
        "model": MODEL,
        "instructionSha256": ff_packet.sha(SYSTEM),
        "promptSha256": ff_packet.sha(PROMPT),
        "cliFlags": CLI_FLAGS,
        "batch": BATCH,
        "attempts": ATTEMPTS,
        "retrySuffix": ff_map.SUFFIX,
        "maxUndescribedFraction": MAX_UNDESCRIBED,
        "maxDescriptionChars": MAX_DESCRIPTION_CHARS,
        "minDescriptionWords": MIN_DESCRIPTION_WORDS,
        "notADescription": list(NOT_A_DESCRIPTION),
        "framesSha256": ff_frames.file_sha(os.path.join(out_dir, "frames.json")),
        "frames": sum(len(b["frames"]) for b in batches),
        "batches": [
            {
                "film": b["film"],
                "frames": len(b["frames"]),
                "key": key([(p, f["sha256"]) for p, f in enumerate(b["frames"])], 0),
            }
            for b in batches
        ],
    }


def refusal(out_dir, tracked_path, doc, environ):
    """Why `run` must not start, or None."""
    with open(tracked_path, encoding="utf-8") as handle:
        if json.load(handle) != doc:
            return "the planned batches differ from the manifest"
    routed = [v for v in ff_map.ROUTING_VARIABLES if environ.get(v)]
    if routed:
        return f"{routed[0]} is set; calls must use the subscription"
    if os.path.exists(os.path.join(out_dir, "frame-captions.json")):
        return "frame-captions.json already exists"
    return None


def run(out_dir, doc, version, jobs):
    """Describe every frame. Returns (captions document, films over the undescribed limit)."""
    frames_doc = ff_frames.load(out_dir)
    batches = plan(frames_doc)
    cache_dir = os.path.join(out_dir, "caption-cache")
    os.makedirs(cache_dir, exist_ok=True)

    def one(batch):
        return describe(out_dir, batch["frames"], cache_dir, version)

    with ThreadPoolExecutor(jobs) as pool:
        results = list(pool.map(one, batches))
    films, counts, receipts = {}, {}, []
    for batch, (descriptions, receipt) in zip(batches, results):
        film = str(batch["film"])
        receipts.append({"film": batch["film"], **receipt})
        for frame, description in zip(batch["frames"], descriptions):
            films.setdefault(film, []).append(
                {
                    "seconds": frame["seconds"],
                    "sha256": frame["sha256"],
                    "description": description,
                }
            )
            count = counts.setdefault(film, {"frames": 0, "undescribed": 0})
            count["frames"] += 1
            count["undescribed"] += description is None
    over = [
        film
        for film, c in counts.items()
        if c["undescribed"] > MAX_UNDESCRIBED * c["frames"]
    ]
    captions = {
        "schema": CAPTIONS_SCHEMA,
        "model": MODEL,
        "cliVersion": version,
        "instructionSha256": doc["instructionSha256"],
        "framesSha256": doc["framesSha256"],
        "counts": counts,
        "undescribed": sum(c["undescribed"] for c in counts.values()),
        "batches": receipts,
        "films": films,
    }
    return captions, over


def write_json(path, doc):
    partial = f"{path}.{os.getpid()}.part"
    with open(partial, "w", encoding="utf-8") as handle:
        json.dump(doc, handle, indent=1, sort_keys=True)
        handle.write("\n")
    os.replace(partial, path)


def smoke(out_dir):
    """One call on a generated grey image, through the same path as a real batch.

    Returns (what was seen, problems). It shows whether the CLI accepts the flags, reports
    exactly the frozen model, and emits the events the read audit depends on.
    """
    os.makedirs(out_dir, exist_ok=True)
    image = os.path.join(out_dir, "smoke.jpg")
    cmd = ["ffmpeg", "-nostdin", "-v", "error", "-y", "-f", "lavfi", "-i"]
    cmd += ["color=c=gray:s=512x288", "-frames:v", "1", image]
    subprocess.run(cmd, check=True, timeout=60)
    frame = {"file": "smoke.jpg", "sha256": ff_frames.file_sha(image), "seconds": 0}
    descriptions, receipt = describe(out_dir, [frame], out_dir, ff_map.cli_version())
    events = receipt["eventTypes"] or {}
    problems = []
    if not events.get("assistant"):
        problems.append("the stream held no assistant event")
    if receipt["reads"][0] != [neutral(0)]:
        problems.append(
            "no tool-use event with a successful result shows the model reading the image"
        )
    if descriptions[0] is None:
        problems.append("the image got no usable description")
    seen = {
        "model": MODEL,
        "description": descriptions[0],
        "readMatchedToItsResult": receipt["reads"][0] == [neutral(0)],
        **receipt,
    }
    return seen, problems


def words(text):
    return len(text.split())


def audit(captions):
    """Lines a person should read before the descriptions are frozen into a film record."""
    lines = [
        "film\tdescribed\tundescribed\tmean_chars\tmax_chars\tmean_words\tmax_words\tover_30_words"
    ]
    texts = [
        (film, row["seconds"], row["description"])
        for film in sorted(captions["films"], key=int)
        for row in captions["films"][film]
    ]
    for film in sorted(captions["films"], key=int):
        own = [t for f, _, t in texts if f == film]
        said = [t for t in own if t is not None]
        chars, count = [len(t) for t in said] or [0], [words(t) for t in said] or [0]
        lines.append(
            f"{film}\t{len(said)}\t{len(own) - len(said)}\t"
            f"{sum(chars) / len(chars):.1f}\t{max(chars)}\t"
            f"{sum(count) / len(count):.1f}\t{max(count)}\t"
            f"{sum(1 for c in count if c > 30)}"
        )
    for film, seconds, text in texts:
        if text is None:
            lines.append(f"undescribed\tfilm {film}\t{seconds} s")
        else:
            if words(text) < 5:
                lines.append(f"short\tfilm {film}\t{seconds} s\t{text}")
            if SUSPECT.search(text):
                lines.append(f"suspect\tfilm {film}\t{seconds} s\t{text}")
    repeats = {}
    for film, seconds, text in texts:
        if text is not None:
            repeats.setdefault(text, []).append(f"{film}@{seconds}")
    for text, where in repeats.items():
        if len(where) > 10:
            lines.append(f"repeated {len(where)} times\t{' '.join(where)}\t{text}")
    return lines


def main(argv):
    if len(argv) == 2 and argv[0] == "smoke":
        seen, problems = smoke(argv[1])
        print(json.dumps(seen, indent=1))
        for problem in problems:
            print(f"FAILED: {problem}", file=sys.stderr)
        return 1 if problems else 0
    if len(argv) == 2 and argv[0] == "audit":
        with open(
            os.path.join(argv[1], "frame-captions.json"), encoding="utf-8"
        ) as handle:
            print("\n".join(audit(json.load(handle))))
        return 0
    if len(argv) in (2, 3) and argv[0] == "manifest":
        name = argv[2] if len(argv) == 3 else "caption-manifest.json"
        path = os.path.join(argv[1], os.path.basename(name))
        if os.path.exists(path):
            print("refused: manifest exists", file=sys.stderr)
            return 2
        doc = manifest(argv[1])
        write_json(path, doc)
        keys = ("model", "instructionSha256", "framesSha256", "frames")
        print(json.dumps({**{k: doc[k] for k in keys}, "calls": len(doc["batches"])}))
        return 0
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["run"])
    ap.add_argument("out_dir")
    ap.add_argument("manifest")
    ap.add_argument("--jobs", type=int, default=3)
    try:
        args = ap.parse_args(argv)
    except SystemExit:
        print(__doc__, file=sys.stderr)
        return 2
    doc = manifest(args.out_dir)
    why = refusal(args.out_dir, args.manifest, doc, os.environ)
    if why:
        print(f"refused: {why}", file=sys.stderr)
        return 2
    captions, over = run(args.out_dir, doc, ff_map.cli_version(), args.jobs)
    print(json.dumps({"counts": captions["counts"]}))
    if over:
        print(
            f"refused: more than {MAX_UNDESCRIBED:.0%} of the frames of film "
            f"{', '.join(over)} are undescribed; no captions file written",
            file=sys.stderr,
        )
        return 1
    write_json(os.path.join(args.out_dir, "frame-captions.json"), captions)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
