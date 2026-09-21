#!/usr/bin/env python3
"""Write the content-free Film Festival media manifest from the git-ignored media root.

The eight short films and the cartoon live under `<data root>/filmfestival/media`, which is never
committed (the films are copyrighted and `FILMS.md` names them). What Git may carry is identity and
timing: this tool emits `docs/data/filmfestival/media-manifest.json` with, per file listed in
`SHA256SUMS.local`, its SHA-256 and size, and, per video, its container, codecs and duration; per
film, the trim, clip and scan durations by cover name; and, for the two commercial excerpts that
have no local media, their boundaries as annotation row numbers and run-relative times.

Paths are published only when their basename is a cover name (optionally with a transcriber suffix)
or a listed provenance file. Any other path is replaced by an opaque label and the SHA-256 of the
path as written in `SHA256SUMS.local`, so the checksum file stays verifiable line for line.

Every value is read from a named file or measured with ffprobe. The tool fails rather than writes
when:

* the files on disk and `SHA256SUMS.local` disagree in membership or digest, or the published file
  list, read back using only its own fields, does not match `SHA256SUMS.local` line for line;
* an annotation workbook or its derived TSV no longer matches its replay receipt;
* a quoted basis (a path or phrase this record cites from a provenance file) is absent from it;
* the excerpts' title-card annotation rows do not name the works `FILMS.md` maps to their cover
  names (compared locally; no title text is written);
* the output contains `://` or `www.`, a host or site label taken from a link in the provenance
  manifest's url column or in `FILMS.md`, a title from the provenance manifest's real_title column
  or from `FILMS.md`'s run tables, or a name token of a redacted path.

Output is deterministic: no clock, no absolute path, fixed key order. `--check` regenerates in
memory and exits 1 when the committed manifest differs.

Usage: filmfest_media_manifest.py [--data DIR] [--out FILE] [--ffprobe PATH] [--check]
"""
import argparse
import csv
import hashlib
import io
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from filmfest_annotation import replay  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO / "docs/data/filmfestival/media-manifest.json"
SOURCE_MANIFEST = REPO / "docs/data/filmfestival/source-manifest.json"
MEDIA = "filmfestival/media"
FILMS_MD = "filmfestival/FILMS.md"
CHECKSUMS = "SHA256SUMS.local"
VIDEO_SUFFIXES = (".mp4", ".mkv")
# Transcriber outputs are <cover>.json, <cover>_words.tsv and <cover>_lang.npz.
COVER_SUFFIXES = ("", "_words", "_lang")
# Content-free provenance files, published by path. Anything else not cover-named is redacted, so
# a new file fails closed into redaction rather than open into publication.
NON_FILM_FILES = frozenset(
    f"provenance/{name}"
    for name in (
        "METHOD.md",
        "README.md",
        "STATUS.md",
        "fetch.log",
        "fetch.sh",
        "make_clips.sh",
        "manifest.tsv",
        "probe.sh",
        "propose_trim.sh",
    )
)
CODERS = ("JL", "KM", "RC")
FILMS_PER_RUN = 6
# The copy itself is recorded only on the tracker; these constants cite it rather than restate it.
BEAD = "bd-01M2TA4BFRMK8WNA79PPRT8YCD"
STAGED_AT = "2026-09-18"
UPSTREAM_ROOTS = [
    "/project/rrg-brad/dsets/filmfestival/stimuli",
    "/scratch/brad/ff_stim/transcripts",
]
EXCERPTS = ("catch_me_if_you_can", "the_prisoner")
GENERIC_URL_MARKERS = ("://", "www.")
TITLE_CARD_MIN_WORDS = 2


def data_root(explicit):
    if explicit:
        return Path(explicit).resolve()
    out = subprocess.run(
        ["bash", str(REPO / "tools/data-root.sh")],
        capture_output=True,
        text=True,
        cwd=REPO,
    )
    root = out.stdout.strip()
    if out.returncode != 0 or not root:
        raise SystemExit("cannot resolve the data root; pass --data")
    return Path(root)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def text_sha256(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def rounded(x):
    return round(x, 3)


def read_tsv(path):
    with open(path, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh, delimiter="\t"))


def words(text):
    return re.findall(r"[a-z0-9]+", text.lower())


def cite(data, rel, needle):
    """A basis is a phrase that must still be present in the file it is attributed to."""
    text = (data / rel).read_text(encoding="utf-8", errors="replace")
    if needle not in text:
        raise SystemExit(f"cited basis missing: {needle!r} not in {rel}")
    return {"path": rel, "quote": needle}


def ffprobe(binary, path):
    out = subprocess.run(
        [
            binary,
            "-v",
            "error",
            "-show_entries",
            "format=format_name,duration:stream=index,codec_type,codec_name,duration,r_frame_rate",
            "-of",
            "json",
            str(path),
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    probe = json.loads(out.stdout)
    streams = []
    for s in sorted(probe["streams"], key=lambda s: s["index"]):
        stream = {
            "index": s["index"],
            "codecType": s["codec_type"],
            "codecName": s["codec_name"],
        }
        # Matroska reports no per-stream duration: the key is then absent, not null or zero.
        if "duration" in s:
            stream["durationSeconds"] = float(s["duration"])
        if s["codec_type"] == "video":
            stream["frameRate"] = s["r_frame_rate"]
        streams.append(stream)
    return {
        "container": probe["format"]["format_name"],
        "durationSeconds": float(probe["format"]["duration"]),
        "streams": streams,
    }


def ffprobe_version(binary):
    out = subprocess.run(
        [binary, "-version"], capture_output=True, text=True, check=True
    )
    first = out.stdout.splitlines()[0]
    m = re.match(r"ffprobe version (\S+)", first)
    if not m:
        raise SystemExit(f"unrecognised ffprobe version line: {first!r}")
    return m.group(1)


def cover_names(data):
    covers = []
    for run in ("01", "02"):
        for row in read_tsv(data / f"filmfestival/task-movie_run-{run}_events.tsv"):
            if row["movie_title"] not in covers:
                covers.append(row["movie_title"])
    return covers


def cover_of_basename(rel, covers):
    stem = rel.rpartition("/")[2].split(".", 1)[0]
    for suffix in COVER_SUFFIXES:
        if stem.endswith(suffix) and stem[: len(stem) - len(suffix)] in covers:
            return stem[: len(stem) - len(suffix)]
    return None


def redacted_cover(rel, digest, published_by_digest, covers):
    """The cover a redacted file belongs to, and how that was decided -- or why it could not be."""
    twin = published_by_digest.get(digest)
    if twin is not None and "coverName" in twin:
        return {
            "coverName": twin["coverName"],
            "coverNameBasis": f"identical bytes to {twin['path']}",
        }
    token = rel.rpartition("/")[2].split(".", 1)[0].split("_", 1)[0]
    matches = [
        c
        for c in covers
        if token in c.split("_") or c.replace("_", "").startswith(token)
    ]
    if len(matches) == 1:
        return {
            "coverName": matches[0],
            "coverNameBasis": "the file name's first token matches exactly one cover name",
        }
    return {
        "coverNameStatus": "unestablished",
        "coverNameReason": f"the file name's first token matches {len(matches)} cover names",
    }


def files_section(data, binary, covers):
    """Returns (internal rows keyed by real path, published entries, checksum record, groups)."""
    root = data / MEDIA
    listed_bytes = (root / CHECKSUMS).read_bytes()
    listed = []
    for n, line in enumerate(listed_bytes.decode("utf-8").splitlines(), start=1):
        m = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if not m:
            raise SystemExit(f"{CHECKSUMS}:{n}: not a sha256sum line")
        listed.append((m.group(2), m.group(1)))
    if "".join(f"{d}  {p}\n" for p, d in listed).encode("utf-8") != listed_bytes:
        raise SystemExit(
            f"{CHECKSUMS} does not re-render from its parsed lines byte for byte"
        )
    on_disk = sorted(
        str(p.relative_to(root))
        for p in root.rglob("*")
        if p.is_file() and p.name != CHECKSUMS
    )
    if sorted(p for p, _ in listed) != on_disk:
        extra = sorted(set(on_disk) - {p for p, _ in listed})
        missing = sorted({p for p, _ in listed} - set(on_disk))
        raise SystemExit(
            f"{CHECKSUMS} membership differs: {len(extra)} unlisted, {len(missing)} absent"
        )
    internal = {}
    for n, (rel, digest) in enumerate(listed, start=1):
        actual = sha256(root / rel)
        if actual != digest:
            raise SystemExit(
                f"{CHECKSUMS}:{n}: the listed digest does not match the file's bytes"
            )
        internal[rel] = {"sha256": actual, "byteLength": (root / rel).stat().st_size}
        if rel.endswith(VIDEO_SUFFIXES):
            internal[rel]["video"] = ffprobe(binary, root / rel)

    published_by_digest, entries, redacted_count = {}, [], {}
    for rel, _ in listed:
        cover = cover_of_basename(rel, covers)
        if cover is not None or rel in NON_FILM_FILES:
            entry = {"path": rel}
            if cover is not None:
                entry["coverName"] = cover
            published_by_digest.setdefault(internal[rel]["sha256"], entry)
            entries.append(entry)
        else:
            entries.append(None)
    for i, (rel, _) in enumerate(listed):
        if entries[i] is None:
            owner = redacted_cover(
                rel, internal[rel]["sha256"], published_by_digest, covers
            )
            directory = rel.rpartition("/")[0]
            key = (directory, owner.get("coverName", "unassigned"))
            redacted_count[key] = redacted_count.get(key, 0) + 1
            entries[i] = {
                "label": f"{key[0]}/{key[1]}#{redacted_count[key]}",
                "pathRedacted": True,
                "pathSha256": text_sha256(rel),
                **owner,
            }
        entries[i].update(internal[rel])

    verify_line_for_line(entries, listed)
    by_digest = {}
    for e in entries:
        by_digest.setdefault(e["sha256"], []).append(e.get("path", e.get("label")))
    groups = [
        {"sha256": d, "members": members}
        for d, members in sorted(by_digest.items())
        if len(members) > 1
    ]
    checksum_file = {
        "path": CHECKSUMS,
        "sha256": hashlib.sha256(listed_bytes).hexdigest(),
        "byteLength": len(listed_bytes),
        "lineCount": len(listed),
        "verifiedLineForLine": "in order, each line's digest equals the entry's sha256, and its "
        "path equals the entry's path or, for a redacted entry, hashes to its pathSha256 "
        "(SHA-256 of the path's UTF-8 bytes)",
        "excludedFromFileList": "a checksum file cannot list its own digest",
    }
    return internal, entries, checksum_file, groups


def verify_line_for_line(entries, listed):
    """Read the published entries back against the checksum file, using only published fields."""
    if len(entries) != len(listed):
        raise SystemExit("published file list and checksum file differ in length")
    for n, (e, (rel, digest)) in enumerate(zip(entries, listed), start=1):
        if e["sha256"] != digest:
            raise SystemExit(f"{CHECKSUMS}:{n}: published sha256 differs")
        if e.get("pathRedacted"):
            if e["pathSha256"] != text_sha256(rel):
                raise SystemExit(f"{CHECKSUMS}:{n}: published path digest differs")
        elif e.get("path") != rel:
            raise SystemExit(f"{CHECKSUMS}:{n}: published path differs")


def films_section(data, internal, prov):
    presentations = {}
    for run in ("01", "02"):
        for row in read_tsv(data / f"filmfestival/task-movie_run-{run}_events.tsv"):
            presentations.setdefault(row["movie_title"], []).append(
                {
                    "run": f"run-{run}",
                    "onsetSeconds": float(row["onset"]),
                    "durationSeconds": float(row["duration"]),
                }
            )
    films = []
    for key in presentations:  # presentation order: run 1 then run 2, by onset
        scan = {p["durationSeconds"] for p in presentations[key]}
        if len(scan) != 1:
            raise SystemExit(f"{key}: presentations disagree on duration")
        scan = scan.pop()
        if key not in prov or float(prov[key]["target_s"]) != scan:
            raise SystemExit(
                f"{key}: provenance target_s disagrees with events.tsv duration"
            )
        clip = f"clips/{key}.mp4"
        sources = [p for p in internal if p.startswith(f"source/{key}.")]
        film = {
            "coverName": key,
            "presentations": presentations[key],
            "scanDurationSeconds": scan,
        }
        if clip in internal and len(sources) == 1:
            source = sources[0]
            trim = float(prov[key]["trim_start"])
            clip_s = internal[clip]["video"]["durationSeconds"]
            source_s = internal[source]["video"]["durationSeconds"]
            film.update(
                {
                    "mediaStatus": "staged",
                    "sourcePath": source,
                    "sourceDurationSeconds": source_s,
                    "trimStartSeconds": trim,
                    "sourceSecondsAfterTrim": rounded(source_s - trim),
                    "clipPath": clip,
                    "clipDurationSeconds": clip_s,
                    "clipShortfallSeconds": rounded(scan - clip_s),
                    "clipMatchesScanDuration": clip_s == scan,
                }
            )
        elif clip in internal or sources:
            raise SystemExit(f"{key}: expected one source and one clip, or neither")
        else:
            film.update(
                {
                    "mediaStatus": "absent",
                    "absentReason": "no source or clip exists under the media root; the "
                    "provenance manifest's trim_start for this key was never applied to a file, "
                    "so no trim is recorded",
                }
            )
        films.append(film)
    return films


def films_md_titles(text):
    """cover name -> title cell of FILMS.md's run tables, parentheticals and markup removed."""
    titles = {}
    for line in text.splitlines():
        m = re.match(r"\|\s*`([a-z_]+)`\s*\|([^|]*)\|", line)
        if m:
            cell = re.sub(r"\([^)]*\)", " ", m.group(2))
            titles.setdefault(m.group(1), re.sub(r"[*\"]", "", cell).strip())
    return titles


def longest_common_run(a, b):
    best, prev = 0, [0] * (len(b) + 1)
    for x in a:
        cur = [0] * (len(b) + 1)
        for j, y in enumerate(b, start=1):
            if x == y:
                cur[j] = prev[j - 1] + 1
                best = max(best, cur[j])
        prev = cur
    return best


def names_work(card, title):
    return longest_common_run(words(card), words(title)) >= TITLE_CARD_MIN_WORDS


def excerpt_section(data, films, titles):
    by_key = {f["coverName"]: f for f in films}
    for key in by_key:
        if key not in titles:
            raise SystemExit(f"{FILMS_MD} run tables carry no title for {key}")
    order = {}
    for run in ("01", "02"):
        rows = read_tsv(data / f"filmfestival/task-movie_run-{run}_events.tsv")
        if len(rows) != FILMS_PER_RUN:
            raise SystemExit(f"run-{run}: expected {FILMS_PER_RUN} films")
        for pos, row in enumerate(rows, start=1):
            order[(f"run-{run}", pos)] = row["movie_title"]
    excerpts = {
        k: {
            "coverName": k,
            "scanDurationSeconds": by_key[k]["scanDurationSeconds"],
            "presentations": by_key[k]["presentations"],
            "coders": [],
        }
        for k in EXCERPTS
    }
    for coder in CODERS:
        tsv = f"filmfestival/derived/annotation-{coder}.tsv"
        xlsx = f"filmfestival/annotations/FilmFestival_movie_annotation_{coder}.xlsx"
        receipt = json.loads(
            (data / f"filmfestival/derived/annotation-{coder}.receipt.json").read_text()
        )
        digest = sha256(data / tsv)
        if digest != receipt["output"]["sha256"]:
            raise SystemExit(f"{tsv}: not the replay receipt's output")
        if sha256(data / xlsx) != receipt["input"]["sha256"]:
            raise SystemExit(f"{xlsx}: not the replay receipt's input")
        # The TSV drops coarse numbers when a coder's numbering cannot carry the global space; the
        # workbook still has them, so they are re-read from the same replay that wrote the TSV.
        replayed, _, _ = replay(str(data / xlsx))
        derived = read_tsv(data / tsv)
        if len(replayed) != len(derived) or any(
            str(a["start_s"]) != b["start_s"] for a, b in zip(replayed, derived)
        ):
            raise SystemExit(f"{xlsx}: replay does not reproduce {tsv} row for row")
        usable = receipt["sceneNumberingUsableForGold"]
        offset = receipt["counts"]["run2SceneNumberOffset"]
        if usable:
            mapping = {
                "status": "established",
                "run2Offset": offset,
                "basis": f"replay receipt: sceneNumberingUsableForGold true, "
                f"run2SceneNumberOffset {offset}",
            }
        else:
            faults = [
                a for a in receipt["anomalies"] if "coarse" in a or "numbering" in a
            ]
            mapping = {
                "status": "unestablished",
                "reason": "replay receipt: sceneNumberingUsableForGold false ("
                + "; ".join(faults)
                + "), so no run-2 offset maps this coder's numbers to the global 1..216 space",
            }
        by_film = {}
        for rep, r in zip(replayed, derived):
            ordinal = int(r["film"].split(".", 1)[0])
            run = "run-01" if ordinal <= FILMS_PER_RUN else "run-02"
            if r["part_id"] != run:
                raise SystemExit(
                    f"{tsv}: film ordinal {ordinal} is not in {r['part_id']}"
                )
            position = ordinal - (0 if run == "run-01" else FILMS_PER_RUN)
            by_film.setdefault(order[(run, position)], []).append((rep, r))
        for key in EXCERPTS:
            pairs = by_film[key]
            run = pairs[0][1]["part_id"]
            card = pairs[0][0]["description"]
            if not names_work(card, titles[key]) or any(
                names_work(card, titles[other]) for other in titles if other != key
            ):
                raise SystemExit(
                    f"{coder}: the title-card row for {key} does not name the work "
                    f"{FILMS_MD} maps to it, and only that work"
                )

            def row_ref(pair):
                rep, r = pair
                ref = {
                    "fineRow": int(r["segment"]),
                    "coarseSceneRunRelative": rep["coarse_no"],
                }
                if usable:
                    ref["coarseSceneGlobal"] = rep["coarse_no"] + (
                        offset if run == "run-02" else 0
                    )
                    if str(ref["coarseSceneGlobal"]) != r["scene_number"]:
                        raise SystemExit(
                            f"{tsv}: global scene disagrees with the replay"
                        )
                ref["startSeconds"] = int(r["start_s"])
                ref["endSeconds"] = int(r["end_s"])
                return ref

            first, last = row_ref(pairs[1]), row_ref(pairs[-1])
            starts = [int(r["start_s"]) for _, r in pairs]
            excerpts[key]["coders"].append(
                {
                    "coder": coder,
                    "annotation": {"path": tsv, "sha256": digest},
                    "run": run,
                    "coarseSceneGlobalMapping": mapping,
                    "fineRowCount": len(pairs),
                    "titleCardRow": row_ref(pairs[0]),
                    "contentFirstRow": first,
                    "contentLastRow": last,
                    "contentStartSeconds": first["startSeconds"],
                    "contentEndSeconds": last["endSeconds"],
                    "annotatedContentSeconds": last["endSeconds"]
                    - first["startSeconds"],
                    "fineStartsNonDecreasing": starts == sorted(starts),
                }
            )
    for ex in excerpts.values():
        bounds = {
            (c["contentStartSeconds"], c["contentEndSeconds"]) for c in ex["coders"]
        }
        ex["codersAgreeOnContentBounds"] = len(bounds) == 1
        ex["filmAssignment"] = {
            "status": "verified-locally",
            "method": f"for each coder, the title-card row shares a run of at least "
            f"{TITLE_CARD_MIN_WORDS} words with the title {FILMS_MD} gives this cover name, and "
            "with no other cover name's title; compared locally, text not committed",
            "coders": list(CODERS),
        }
    return [excerpts[k] for k in EXCERPTS]


def upstream_section(data):
    prov = f"{MEDIA}/provenance"
    stimuli = UPSTREAM_ROOTS[0]
    return {
        "host": "Trillium",
        "roots": UPSTREAM_ROOTS,
        "rootsBasis": f"tracker bead {BEAD}, which records the {STAGED_AT} copy",
        "remoteChecksumMatch": {
            "reported": f"{CHECKSUMS} matched the remote for all listed files",
            "reportedBy": f"tracker bead {BEAD}",
            "verifiedByThisManifest": False,
        },
        "directories": [
            {
                "local": "clips/",
                "remote": f"{stimuli}/clips/",
                "status": "inferred",
                "basis": [
                    cite(data, f"{prov}/make_clips.sh", f"D={stimuli}"),
                    cite(data, f"{prov}/make_clips.sh", "out=$D/clips/${key}.mp4"),
                ],
            },
            {
                "local": "source/",
                "remote": f"{stimuli}/video/",
                "status": "inferred",
                "basis": [
                    cite(data, f"{prov}/fetch.sh", f"D={stimuli}"),
                    cite(data, f"{prov}/fetch.sh", '-o "$D/video/${key}.%(ext)s"'),
                ],
                "discrepancy": "the download log writes the same file names under "
                "/project/rrg-brad/dsets/filmfestival_stimuli/video/, a directory name that "
                "differs from the scripts' root",
                "discrepancyBasis": cite(
                    data,
                    f"{prov}/fetch.log",
                    "/project/rrg-brad/dsets/filmfestival_stimuli/video/",
                ),
            },
            {
                "local": "cand/",
                "status": "unestablished",
                "reason": "no provenance script or note names the directory these candidate "
                "downloads were written to",
            },
            {
                "local": "provenance/",
                "remote": f"{stimuli}/scripts/",
                "status": "inferred-partial",
                "basis": [
                    cite(data, f"{prov}/fetch.sh", "$D/scripts/manifest.tsv"),
                    cite(data, f"{prov}/STATUS.md", "`scripts/propose_trim.sh`"),
                    cite(data, f"{prov}/fetch.sh", "$D/logs/fetch.log"),
                ],
                "reason": "manifest.tsv and the four scripts are addressed under scripts/, and "
                "fetch.log under logs/; no local text places README.md, STATUS.md or "
                "METHOD.md",
            },
            {
                "local": "asr/",
                "remote": UPSTREAM_ROOTS[1] + "/",
                "status": "inferred-partial",
                "basis": [
                    cite(data, f"{prov}/README.md", "`transcripts/<key>.json`"),
                    cite(data, f"{prov}/README.md", "`/scratch/brad/ff_stim`"),
                ],
                "reason": "the json and _words.tsv files match the transcriber's documented output; "
                "no local text names the producer of the _lang.npz files",
            },
        ],
    }


def films_note(data, raw):
    recorded = next(
        v["sha256"]
        for v in json.loads(SOURCE_MANIFEST.read_text())["verification"][
            "notVerifiableByBlob"
        ]
        if v["id"] == "FILMS.md"
    )
    digest = hashlib.sha256(raw).hexdigest()
    return {
        "path": FILMS_MD,
        "sha256": digest,
        "byteLength": len(raw),
        "sourceManifestRecordsSha256": recorded,
        "sourceManifestDigestCurrent": digest == recorded,
        "storage": "external-local-only; names commercial works and is not for Git admission",
    }


def clock_non_claim(data, excerpts):
    readme = f"{MEDIA}/provenance/README.md"
    observed = []
    for ex in excerpts:
        if ex["codersAgreeOnContentBounds"] and len(ex["presentations"]) == 1:
            start = ex["coders"][0]["contentStartSeconds"]
            onset = ex["presentations"][0]["onsetSeconds"]
            observed.append(
                {
                    "coverName": ex["coverName"],
                    "annotatedContentStartSeconds": start,
                    "eventsOnsetSeconds": onset,
                    "differenceSeconds": rounded(onset - start),
                }
            )
    return {
        "id": "annotation-to-events-tsv-clock-offset-resolved",
        "why": "the provenance README reports offsets between the annotation clock and "
        "events.tsv that drift within each run, and a difference depends on which annotation "
        "row is taken as a film's start; the differences below are observations, not an offset",
        "basis": [
            cite(
                data,
                readme,
                "drifts monotonically within a run (3,6,8,7,8,10 s in run 1;",
            ),
            cite(data, readme, "3,6,7,7,7,11 s in run 2)"),
        ],
        "observedDifferences": observed,
    }


def build(data, binary):
    covers = cover_names(data)
    # Only key, target_s and trim_start enter the record. real_title and url are read solely to
    # refuse output that contains them; the note column is never read.
    prov = {r["key"]: r for r in read_tsv(data / f"{MEDIA}/provenance/manifest.tsv")}
    films_raw = (data / FILMS_MD).read_bytes()
    titles = films_md_titles(films_raw.decode("utf-8"))
    internal, files, checksum_file, groups = files_section(data, binary, covers)
    films = films_section(data, internal, prov)
    excerpts = excerpt_section(data, films, titles)
    record = {
        "schema": "storymodel4s.filmfestival.media-manifest",
        "schemaVersion": 2,
        "generator": "tools/corpus/filmfest_media_manifest.py",
        "mediaRoot": f"data/{MEDIA}",
        "storage": "external-local-only",
        "contentStoredInGit": False,
        "stagedAt": STAGED_AT,
        "stagedAtBasis": f"tracker bead {BEAD}",
        "ffprobeVersion": ffprobe_version(binary),
        "fieldEvidence": {
            "files[].sha256": f"computed; equal to {CHECKSUMS}",
            "files[].byteLength": "measured",
            "files[].path": "published when the basename is a cover name, optionally with a "
            "transcriber suffix, or a listed provenance file",
            "files[].label": "opaque label for a redacted path: <directory>/<cover or "
            "unassigned>#<n>, numbered in checksum-file order",
            "files[].pathSha256": "SHA-256 of the redacted path's UTF-8 bytes as written in "
            f"{CHECKSUMS}",
            "files[].video": "ffprobe format_name, format duration and per-stream codec_name; "
            "a stream's durationSeconds is absent when the container reports none",
            "films[].presentations": "filmfestival/task-movie_run-0{1,2}_events.tsv (ds004042)",
            "films[].scanDurationSeconds": "events.tsv duration; equal to provenance "
            "manifest.tsv target_s",
            "films[].trimStartSeconds": f"{MEDIA}/provenance/manifest.tsv column trim_start",
            "films[].sourceDurationSeconds": "ffprobe of the source file",
            "films[].clipDurationSeconds": "ffprobe of the clip file",
            "films[].clipShortfallSeconds": "scanDurationSeconds - clipDurationSeconds",
            "excerpts[].coders[]": "filmfestival/derived/annotation-<coder>.tsv and the "
            "workbook it was replayed from, both bound to the replay receipt; of the film "
            "label only its ordinal prefix is used, and no description text enters this record",
            "excerpts[].coders[].*.coarseSceneRunRelative": "the coder's coarse number as "
            "written in the workbook, which restarts in run 2",
        },
        "checksumFile": checksum_file,
        "files": files,
        "identicalDigestGroups": groups,
        "films": films,
        "trimVerification": {
            "claimedBy": cite(
                data,
                f"{MEDIA}/provenance/STATUS.md",
                "trim point confirmed by matching the frame at `trim_start`",
            ),
            "status": "not verified by this manifest",
        },
        "excerpts": excerpts,
        "excerptConventions": {
            "titleCardRow": cite(
                data,
                f"{MEDIA}/provenance/README.md",
                "the annotators' `+0` row is always the",
            ),
            "contentEndSeconds": "the replay sets a row's end to the next fine start in the "
            "same run, so the last content row ends where the following film's title-card "
            "row starts",
            "clock": "annotation times are run-relative and are not the events.tsv clock",
            "filmAssignment": "annotation film ordinal k is taken as events.tsv row k of run 1 "
            "(k <= 6) or row k-6 of run 2; for the two excerpts this is checked locally "
            "(excerpts[].filmAssignment), and no other film's assignment is used here",
            "fineRow": "the replay's 1-based annotated-row ordinal for that coder, not an "
            "upstream column",
        },
        "upstream": upstream_section(data),
        "filmsNote": films_note(data, films_raw),
        "nonClaims": [
            {
                "id": "clip-equals-presented-edition",
                "why": "clips are cut from public copies; equivalence to the presented files is "
                "not established",
            },
            {
                "id": "trim-points-verified-against-frames",
                "why": "trim_start values are copied from the provenance manifest and are not "
                "verified by this manifest (see trimVerification)",
            },
            clock_non_claim(data, excerpts),
            {
                "id": "excerpt-internal-cuts-known",
                "why": "whether material inside either excerpt's annotated span was left out of "
                "the presentation is not known",
            },
            {
                "id": "excerpt-position-within-work-verified",
                "why": "where each excerpt sits in its full work is reported by the provenance "
                "notes, not verified locally",
            },
            {
                "id": "excerpt-media-located",
                "why": "no source for either excerpt is staged",
            },
            {
                "id": "remote-per-file-paths-verified",
                "why": "Trillium was not read; directory origins are inferred from provenance "
                "scripts",
            },
            {
                "id": "reference-annotator-selected",
                "why": "the three coders are reported side by side",
            },
            {
                "id": "media-admitted-to-git",
                "why": "the media stay in the git-ignored data root",
            },
        ],
    }
    text = json.dumps(record, indent=2, ensure_ascii=False) + "\n"
    refuse_leaks(text, prov, titles, films_raw.decode("utf-8"), internal, files, covers)
    return text


def refuse_leaks(text, prov, titles, films_md, internal, files, covers):
    lowered = text.lower()
    markers = set(GENERIC_URL_MARKERS)
    links = [r["url"] for r in prov.values() if r["url"]]
    links += re.findall(r"https?://\S+", films_md)
    hosts = {urlparse(u).hostname for u in links}
    hosts |= set(re.findall(r"\b((?:[a-z0-9-]+\.)+[a-z]{2,})/", films_md.lower()))
    for host in filter(None, hosts):
        labels = host.lower().removeprefix("www.").split(".")
        markers.add(".".join(labels))
        if len(labels) >= 2:
            markers.add(labels[-2])
    for marker in sorted(markers):
        if marker in lowered:
            raise SystemExit("refusing to write: output contains a URL or site marker")
    forbidden = [
        re.sub(r"\s*\(\d{4}\)\s*$", "", r["real_title"]) for r in prov.values()
    ]
    forbidden += titles.values()
    for title in forbidden:
        if title.strip() and title.strip().lower() in lowered:
            raise SystemExit("refusing to write: output contains a real film title")
    cover_tokens = {t for c in covers for t in c.split("_")}
    suffix_tokens = {t for s in COVER_SUFFIXES for t in s.split("_") if t}
    for e in files:
        if not e.get("pathRedacted"):
            # Restated as a token test, independently of cover_of_basename's suffix match.
            stem = e["path"].rpartition("/")[2].split(".", 1)[0]
            if e["path"] not in NON_FILM_FILES and not set(stem.split("_")) <= (
                cover_tokens | suffix_tokens
            ):
                raise SystemExit("refusing to write: a published path is not cover-named")
            continue
        rel = next(p for p in internal if text_sha256(p) == e["pathSha256"])
        stem = rel.rpartition("/")[2].split(".", 1)[0]
        tokens = [
            t for t in stem.lower().split("_") if len(t) >= 3 and t not in cover_tokens
        ]
        if rel.lower() in lowered or any(t in lowered for t in tokens):
            raise SystemExit(
                "refusing to write: output contains a redacted path's name"
            )


def main(argv):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--data", help="data root (default: tools/data-root.sh)")
    p.add_argument("--out", type=Path, default=DEFAULT_OUT)
    p.add_argument(
        "--ffprobe", default=os.environ.get("FFPROBE", "/opt/homebrew/bin/ffprobe")
    )
    p.add_argument("--check", action="store_true", help="exit 1 if --out differs")
    a = p.parse_args(argv)
    text = build(data_root(a.data), a.ffprobe)
    if a.check:
        current = a.out.read_text(encoding="utf-8") if a.out.exists() else None
        if current != text:
            print(f"{a.out}: differs from a fresh generation", file=sys.stderr)
            return 1
        print(f"{a.out}: current")
        return 0
    with io.open(a.out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    print(f"wrote {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
