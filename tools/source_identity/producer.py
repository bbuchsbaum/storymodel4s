#!/usr/bin/env python3
"""ADR 0027 sections 1-4 and 7: the recall-blind source-identity producer for one media part.

Why a producer outside Scala: it reads external annotation and speech-recognition files and owns
their quirks, while `sourceIdentity build` (pipeline) is the only place proposals meet the checked
factories. The producer proposes; it never licenses. It writes three things:

- `inputs.json`, the `source-identity-inputs/v1` document `sourceIdentity build` reads;
- `universe.json`, the candidate pairs fixed before any model call (section 7);
- inside `inputs.json`, a `producer` section with every producer-side refusal and the per-mention
  facts that `guard_deixis` needs when the model step (S3c-4) proposes deixis.

The only links proposed here are deterministic `NamedReference` links (owner decision N). Deixis
and hints come from the model, through `guard_deixis`.

Usage: producer.py build RUN_MANIFEST OUTPUT_DIR
"""
from __future__ import annotations

import csv
import hashlib
import io
import json
import os
from decimal import Decimal
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from typing import Callable

INPUT_SCHEMA = "source-identity-inputs/v1"
RUN_SCHEMA = "source-identity-run/v1"
LABELS_SCHEMA = "source-identity-role-labels/v1"
UNIVERSE_SCHEMA = "source-identity-universe/v1"

# Roles a recall-blind producer run may read (ADR 0027 section 1). Model recordings join in S3c-4.
ROLES = frozenset({"media", "annotation", "asr-transcript", "role-labels"})

# Edition ticks per second. 1/44100 s represents the picture end (1554.8 s), the audio end
# (68218878 samples) and the recognizer's 10 ms timings exactly, so no time is rounded.
TICKS = 44100
MAX_SEGMENT_SECONDS = Decimal(
    "30"
)  # section 2: longer decoder segments have no credible timings
MIN_PROBABILITY = 0.5  # section 2: pronouns and nouns below this yield no mention
MARGIN_SECONDS = Decimal("0.5")  # section 3: deixis needs this much room inside a row
DURATION_TOLERANCE_SECONDS = Decimal(
    "0.001"
)  # recognizer clock against the audio stream

# Section 2. Third-person singular personal pronouns and their contractions; "it" and "they" are
# not person-singular, so they are left out. Matching is after case folding and edge punctuation.
PRONOUNS = frozenset(
    {
        "he",
        "him",
        "his",
        "himself",
        "he's",
        "he'd",
        "he'll",
        "she",
        "her",
        "hers",
        "herself",
        "she's",
        "she'd",
        "she'll",
    }
)
NOUNS = frozenset({"woman", "man", "girl", "boy", "lady", "body", "victim"})
_EDGE = ".,!?;:\"'()[]{}…–—-“”‘’"


class Refusal(Exception):
    """A run-level refusal: the producer writes nothing (rule 12 at the process boundary)."""


def normalize(token: str) -> str:
    """Case-folded token with edge punctuation removed and curly apostrophes made straight."""
    t = token.replace("’", "'").strip().casefold()
    return t.strip(_EDGE)


def fold_name(name: str) -> str:
    """The core's `ProperName` folding: case and runs of whitespace."""
    return " ".join(name.split()).casefold()


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.casefold()).strip("-") or "x"


def ticks(seconds: Decimal | str) -> int:
    t = Decimal(str(seconds)) * TICKS
    if t != t.to_integral_value():
        raise Refusal(f"{seconds} s is not a whole number of 1/{TICKS} s ticks")
    return int(t)


def dec(value) -> Decimal:
    return Decimal(str(value))


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


# --- inputs ------------------------------------------------------------------------------------


def admit_inputs(run: dict) -> dict[str, tuple[Path, str]]:
    """The closed input list of section 1: each admitted role once, a recall path never, and every
    file's bytes equal to its pinned SHA-256. Paths are checked before any byte is read."""
    if run.get("schemaVersion") != RUN_SCHEMA:
        raise Refusal(f"schemaVersion must be {RUN_SCHEMA}")
    entries = run.get("inputs")
    if not isinstance(entries, list):
        raise Refusal("missing inputs")
    admitted: dict[str, tuple[Path, str]] = {}
    for e in entries:
        role, path, sha = e.get("role"), e.get("path"), e.get("sha256")
        if not (
            isinstance(role, str) and isinstance(path, str) and isinstance(sha, str)
        ):
            raise Refusal("each input needs role, path and sha256")
        if any("recall" in part.casefold() for part in Path(path).parts):
            raise Refusal(f"recall path refused: {path}")
        if role not in ROLES:
            raise Refusal(f"input role {role} is not admitted")
        if role in admitted:
            raise Refusal(f"input role {role} is listed twice")
        admitted[role] = (Path(path), sha)
    missing = sorted(ROLES - admitted.keys())
    if missing:
        raise Refusal(f"missing input roles: {', '.join(missing)}")
    for role, (path, sha) in sorted(admitted.items()):
        actual = sha256_file(path)
        if actual != sha:
            raise Refusal(f"{role} sha256 is {actual}, pinned {sha}")
    return admitted


def ffmpeg_probe(media: Path) -> dict:
    """Stream facts from the media bytes: each stream's end in edition ticks and the SHA-256 of
    its packets, so the picture and audio streams have distinct content identities."""
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_streams", "-of", "json", str(media)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    streams = json.loads(out)["streams"]
    facts = {}
    for kind, codec_type, spec in (
        ("picture", "video", "0:v:0"),
        ("audio", "audio", "0:a:0"),
    ):
        s = [x for x in streams if x["codec_type"] == codec_type]
        if len(s) != 1:
            raise Refusal(f"expected one {codec_type} stream, found {len(s)}")
        num, den = (int(x) for x in s[0]["time_base"].split("/"))
        if int(s[0].get("start_pts", 0)) != 0:
            raise Refusal(f"{codec_type} stream does not start at 0")
        end = Decimal(int(s[0]["duration_ts"])) * num / den
        hashed = subprocess.run(
            [
                "ffmpeg",
                "-v",
                "error",
                "-i",
                str(media),
                "-map",
                spec,
                "-c",
                "copy",
                "-f",
                "streamhash",
                "-hash",
                "sha256",
                "-",
            ],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        m = re.fullmatch(r"\d+,\w,SHA256=([0-9a-f]{64})", hashed)
        if not m:
            raise Refusal(f"unexpected streamhash output for {codec_type}")
        facts[kind] = {"end": ticks(end), "sha256": m.group(1)}
    return facts


def check_asr(transcript: dict, media_sha: str, audio_end: int) -> None:
    """The recognizer read these bytes, on their own clock from zero, for the audio's length."""
    for field in ("source", "input"):
        if transcript.get(field, {}).get("sha256") != media_sha:
            raise Refusal(f"recognizer {field} sha256 is not the media part's")
    clock = transcript.get("clock", {})
    if (
        clock.get("origin") != "source_media"
        or dec(clock.get("input_start_seconds")) != 0
    ):
        raise Refusal("recognizer clock is not source-media seconds from zero")
    gap = abs(dec(clock.get("input_duration_seconds")) - Decimal(audio_end) / TICKS)
    if gap > DURATION_TOLERANCE_SECONDS:
        raise Refusal(f"recognizer duration differs from the audio stream by {gap} s")


def read_rows(text: str, first: int, last: int) -> list[dict]:
    """Annotation rows in scope, by stripped header name."""
    reader = csv.DictReader(io.StringIO(text), delimiter="\t")
    out = []
    for raw in reader:
        r = {(k or "").strip(): (v or "") for k, v in raw.items()}
        n = int(r["Segment Number"])
        if first <= n <= last:
            split = lambda s: [x.strip() for x in s.split(",") if x.strip()]
            out.append(
                {
                    "row": n,
                    "start": dec(r["Start Time (s)"]),
                    "end": dec(r["End Time (s)"]),
                    "all": split(r["Name - All"]),
                    "speaking": split(r["Name - Speaking"]),
                }
            )
    out.sort(key=lambda r: r["row"])
    if [r["row"] for r in out] != list(range(first, last + 1)):
        raise Refusal(f"annotation rows {first}-{last} are not all present")
    return out


def read_labels(labels: dict) -> tuple[list[str], dict[str, int]]:
    """The reviewed vocabulary of the run: characters by name, role labels with their counts."""
    if labels.get("schemaVersion") != LABELS_SCHEMA:
        raise Refusal(f"role labels schemaVersion must be {LABELS_SCHEMA}")
    characters, roles = labels.get("characters"), labels.get("roleLabels")
    if not isinstance(characters, list) or not isinstance(roles, dict):
        raise Refusal("role labels need characters and roleLabels")
    if any(
        not isinstance(n, int) or isinstance(n, bool) or n < 1 for n in roles.values()
    ):
        raise Refusal("a role label count must be a positive integer")
    if set(characters) & roles.keys():
        raise Refusal("an entry is both a character and a role label")
    return characters, roles


# --- mentions, presence, proposals --------------------------------------------------------------


def visual_mentions(rows, characters, roles, annotation_sha):
    """Section 2 visual mentions and section 3 presence claims, one row at a time."""
    known = set(characters)
    mentions, presence, refusals = [], [], []
    for r in rows:
        span = [ticks(r["start"]), ticks(r["end"])]
        withheld, seen, ids = None, set(), []
        for entry in r["all"]:
            if entry in seen:
                withheld = f"entry {entry} is listed twice"
                continue
            seen.add(entry)
            if entry in known:
                m = {
                    "id": f"v-r{r['row']}-{slug(entry)}",
                    "origin": "visual",
                    "stream": "picture",
                    "intervals": [span],
                    "name": entry,
                }
                mentions.append(m)
                ids.append(m["id"])
            elif entry in roles:
                # A role label is not a name, so its mentions are unnamed and never join by name.
                for k in range(1, roles[entry] + 1):
                    m = {
                        "id": f"v-r{r['row']}-{slug(entry)}-{k}",
                        "origin": "visual",
                        "stream": "picture",
                        "intervals": [span],
                    }
                    mentions.append(m)
                    ids.append(m["id"])
            else:
                withheld = (
                    f"entry {entry} is neither a listed character nor a role label"
                )
        r["visual"] = ids
        r["presence"] = "withheld" if withheld else "admitted"
        if withheld:
            refusals.append(
                {"kind": "presence-withheld", "row": r["row"], "reason": withheld}
            )
        else:
            presence.append(
                {
                    "basis": "human-reviewed",
                    "stream": "picture",
                    "intervals": [span],
                    "receipt": {
                        "algorithm": "annotation-presence/v1",
                        "parameters": f"row:{r['row']};column:Name - All",
                        "inputs": [annotation_sha],
                    },
                }
            )
    return mentions, presence, refusals


def dialogue_mentions(transcript, characters, scope):
    """Section 2 dialogue mentions on the audio stream, with the refused-speech guards."""
    lo, hi = scope
    segments = {s["index"]: s for s in transcript["segments"]}
    names = sorted(
        ((tuple(normalize(t) for t in c.split()), c) for c in characters),
        key=lambda x: -len(x[0]),
    )
    words = sorted(
        (
            w
            for w in transcript["words"]
            if dec(w["start_seconds"]) >= lo and dec(w["end_seconds"]) <= hi
        ),
        key=lambda w: w["index"],
    )
    mentions, meta, refusals = [], [], []
    refused_segments = set()
    for i in sorted({w["segment_index"] for w in words}):
        s = segments[i]
        length = dec(s["end_seconds"]) - dec(s["start_seconds"])
        if length > MAX_SEGMENT_SECONDS:
            refused_segments.add(i)
            refusals.append(
                {"kind": "segment-too-long", "segment": i, "seconds": str(length)}
            )
    k = 0
    while k < len(words):
        w = words[k]
        if w["segment_index"] in refused_segments:
            k += 1
            continue
        matched = None
        for tokens, name in names:
            run = words[k : k + len(tokens)]
            if (
                len(run) == len(tokens)
                and all(x["segment_index"] == w["segment_index"] for x in run)
                and tuple(normalize(x["text"]) for x in run) == tokens
            ):
                matched = (run, name)
                break
        token = normalize(w["text"])
        if matched:
            run, name = matched
            kind = "name"
        elif token in PRONOUNS or token in NOUNS:
            run, name = [w], None
            kind = "pronoun" if token in PRONOUNS else "noun"
            if w["probability"] < MIN_PROBABILITY:
                refusals.append({"kind": "low-probability", "word": w["index"]})
                k += 1
                continue
        else:
            k += 1
            continue
        span = [ticks(dec(run[0]["start_seconds"])), ticks(dec(run[-1]["end_seconds"]))]
        if span[1] <= span[0]:
            refusals.append({"kind": "empty-timing", "word": w["index"]})
        else:
            m = {
                "id": f"d-w{w['index']}",
                "origin": "dialogue",
                "stream": "audio",
                "intervals": [span],
                "context": f"ctx-s{w['segment_index']}",
            }
            if name:
                m["name"] = name
            mentions.append(m)
            meta.append(
                {
                    "id": m["id"],
                    "class": kind,
                    "words": [x["index"] for x in run],
                    "segment": w["segment_index"],
                }
            )
        k += len(run)
    return mentions, meta, refusals


def named_proposals(mentions):
    """Owner decision N: each later mention of a name is proposed as the same as its first."""
    by_name: dict[str, list[dict]] = {}
    for m in mentions:
        if "name" in m:
            by_name.setdefault(fold_name(m["name"]), []).append(m)
    proposals = []
    for folded in sorted(by_name):
        ms = sorted(by_name[folded], key=lambda m: (m["intervals"][0][0], m["id"]))
        for k, m in enumerate(ms[1:], start=1):
            proposals.append(
                {
                    "id": f"p-name-{slug(folded)}-{k}",
                    "kind": "named",
                    "left": ms[0]["id"],
                    "right": m["id"],
                    "name": ms[0]["name"],
                    "producer": {"admitted": True},
                }
            )
    return proposals


def overlapping(rows, span):
    return [
        r for r in rows if ticks(r["start"]) < span[1] and span[0] < ticks(r["end"])
    ]


def universe(rows, dialogue, meta):
    """Section 7: every admitted pronoun or noun mention against every visual mention of its
    overlapping and adjacent rows, fixed before any model call."""
    index = {r["row"]: k for k, r in enumerate(rows)}
    classes = {m["id"]: m["class"] for m in meta}
    pairs = []
    for d in dialogue:
        if classes[d["id"]] not in ("pronoun", "noun"):
            continue
        span = d["intervals"][0]
        hit = [index[r["row"]] for r in overlapping(rows, span)]
        if hit:
            near = {j for k in hit for j in (k - 1, k, k + 1) if 0 <= j < len(rows)}
        else:
            before = [k for k, r in enumerate(rows) if ticks(r["end"]) <= span[0]]
            after = [k for k, r in enumerate(rows) if ticks(r["start"]) >= span[1]]
            near = set(before[-1:] + after[:1])
        for k in sorted(near):
            for v in rows[k]["visual"]:
                pairs.append({"mention": d["id"], "visual": v})
    return sorted(pairs, key=lambda p: (p["mention"], p["visual"]))


# --- the deixis guard, applied to model proposals before the resolver (S3c-4) -------------------


def certified_row(rows, span):
    """The row whose admitted presence claim contains the span with the section 3 margin."""
    margin = ticks(MARGIN_SECONDS)
    for r in rows:
        if (
            r["presence"] == "admitted"
            and span[0] >= ticks(r["start"]) + margin
            and span[1] <= ticks(r["end"]) - margin
        ):
            return r
    return None


def guard_deixis(
    producer: dict, mention_id: str, target_id: str
) -> tuple[bool, str | None]:
    """Section 4 producer guards on a deictic proposal, in order. Returns (admitted, reason)."""
    meta = {m["id"]: m for m in producer["dialogue"]}[mention_id]
    if meta["class"] != "pronoun":
        return False, "only a third-person pronoun may be deictic"
    row = certified_row(producer["rows"], meta["span"])
    if row is None:
        return False, "the mention is outside every certified row"
    names = producer["visualNames"]
    target = names.get(target_id)
    if not row["speaking"]:
        return False, "the row lists no speaker"
    if target is not None and target in row["speaking"]:
        if len(row["speaking"]) > 1:
            return False, "the target is among several listed speakers"
        return False, "the target is the speaker"
    return True, None


# --- the run ------------------------------------------------------------------------------------


def produce(
    run: dict, probe: Callable[[Path], dict] = ffmpeg_probe
) -> tuple[dict, dict]:
    """Pure apart from reading the admitted inputs and probing the media."""
    inputs = admit_inputs(run)
    first, last = run.get("rows", [None, None])
    if not (isinstance(first, int) and isinstance(last, int) and first <= last):
        raise Refusal("rows must be [first, last]")
    edition = run.get("edition")
    if not isinstance(edition, str) or not edition or any(c.isspace() for c in edition):
        raise Refusal("edition must be a non-blank identifier")
    media_path, media_sha = inputs["media"]
    facts = probe(media_path)
    transcript = json.loads(inputs["asr-transcript"][0].read_text(encoding="utf-8"))
    check_asr(transcript, media_sha, facts["audio"]["end"])
    rows = read_rows(inputs["annotation"][0].read_text(encoding="utf-8"), first, last)
    characters, roles = read_labels(
        json.loads(inputs["role-labels"][0].read_text("utf-8"))
    )
    visual, presence, refused_rows = visual_mentions(
        rows, characters, roles, inputs["annotation"][1]
    )
    scope = (rows[0]["start"], rows[-1]["end"])
    dialogue, meta, refused_speech = dialogue_mentions(transcript, characters, scope)
    spans = {m["id"]: m["intervals"][0] for m in dialogue}
    producer = {
        "constants": {
            "ticksPerSecond": TICKS,
            "maxSegmentSeconds": str(MAX_SEGMENT_SECONDS),
            "minProbability": MIN_PROBABILITY,
            "marginSeconds": str(MARGIN_SECONDS),
        },
        "rows": [
            {
                "row": r["row"],
                "start": str(r["start"]),
                "end": str(r["end"]),
                "speaking": r["speaking"],
                "visual": r["visual"],
                "presence": r["presence"],
            }
            for r in rows
        ],
        "dialogue": [dict(m, span=spans[m["id"]]) for m in meta],
        "visualNames": {m["id"]: m["name"] for m in visual if "name" in m},
        "refusals": refused_rows + refused_speech,
    }
    doc = {
        "schemaVersion": INPUT_SCHEMA,
        "inputs": [
            {"role": role, "sha256": sha} for role, (_, sha) in sorted(inputs.items())
        ],
        "edition": {
            "edition": edition,
            "timebase": [1, TICKS],
            "start": 0,
            "end": facts["picture"]["end"],
            "streams": [
                {"kind": k, "checksum": facts[k]["sha256"], "end": facts[k]["end"]}
                for k in ("picture", "audio")
            ],
        },
        "mentions": sorted(visual + dialogue, key=lambda m: m["id"]),
        "presence": presence,
        "proposals": named_proposals(visual + dialogue),
        "producer": producer,
    }
    pairs = universe(rows, dialogue, meta)
    return doc, {"schemaVersion": UNIVERSE_SCHEMA, "pairs": pairs}


def write(out: Path, doc: dict, universe_doc: dict) -> None:
    """Written through a temporary directory and a rename; an existing output is refused."""
    if out.exists():
        raise Refusal("output already exists")
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp(prefix=f".{out.name}-", dir=out.parent))
    try:
        for name, body in (("inputs.json", doc), ("universe.json", universe_doc)):
            (tmp / name).write_text(
                json.dumps(body, indent=2, sort_keys=True) + "\n", "utf-8"
            )
        os.rename(tmp, out)
    except BaseException:
        for f in tmp.glob("*"):
            f.unlink()
        tmp.rmdir()
        raise


def main(argv: list[str]) -> int:
    if len(argv) != 3 or argv[0] != "build":
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    try:
        run = json.loads(Path(argv[1]).read_text(encoding="utf-8"))
        doc, universe_doc = produce(run)
        write(Path(argv[2]), doc, universe_doc)
    except Refusal as e:
        print(f"refused: {e}", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
