#!/usr/bin/env python3
"""Write the content-free Film Festival media manifest from the git-ignored media root.

The eight short films and the cartoon live under `<data root>/filmfestival/media`, which is never
committed (the films are copyrighted and `FILMS.md` names them). What Git may carry is identity and
timing: this tool emits `docs/data/filmfestival/media-manifest.json` with, per file, its path, SHA-256
and size, and, per video, its container, codecs and duration; per film, the trim, clip and scan
durations by cover name; and, for the two commercial excerpts that have no local media, their
boundaries as annotation row numbers and run-relative times.

Every value is read from a named file or measured with ffprobe; nothing is typed in. The tool fails
rather than writes when:

* the files on disk and `SHA256SUMS.local` disagree in membership or digest, or the manifest's file
  list does not re-render `SHA256SUMS.local` byte for byte;
* a derived annotation TSV no longer matches its replay receipt;
* a quoted basis (a path or phrase this record cites from a provenance file) is absent from it;
* the output would contain a URL or a real film title from the provenance manifest.

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

REPO = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO / "docs/data/filmfestival/media-manifest.json"
SOURCE_MANIFEST = REPO / "docs/data/filmfestival/source-manifest.json"
MEDIA = "filmfestival/media"
CHECKSUMS = "SHA256SUMS.local"
VIDEO_SUFFIXES = (".mp4", ".mkv")
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
URL_MARKERS = ("://", "www.", "youtube", "youtu.be", "vimeo", "archive.org", "tubi")


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


def rounded(x):
    return round(x, 3)


def read_tsv(path):
    with open(path, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh, delimiter="\t"))


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
            # Matroska reports no per-stream duration; null means "not reported", not zero.
            "durationSeconds": float(s["duration"]) if "duration" in s else None,
        }
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


def files_section(data, binary):
    root = data / MEDIA
    listed_bytes = (root / CHECKSUMS).read_bytes()
    listed = []
    for n, line in enumerate(listed_bytes.decode("utf-8").splitlines(), start=1):
        m = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if not m:
            raise SystemExit(f"{CHECKSUMS}:{n}: not a sha256sum line")
        listed.append((m.group(2), m.group(1)))
    on_disk = sorted(
        str(p.relative_to(root))
        for p in root.rglob("*")
        if p.is_file() and p.name != CHECKSUMS
    )
    if sorted(p for p, _ in listed) != on_disk:
        extra = sorted(set(on_disk) - {p for p, _ in listed})
        missing = sorted({p for p, _ in listed} - set(on_disk))
        raise SystemExit(
            f"{CHECKSUMS} membership differs: unlisted {extra}, absent {missing}"
        )
    entries = []
    for rel, digest in listed:
        actual = sha256(root / rel)
        if actual != digest:
            raise SystemExit(f"{rel}: sha256 {actual} but {CHECKSUMS} lists {digest}")
        entry = {
            "path": rel,
            "sha256": actual,
            "byteLength": (root / rel).stat().st_size,
        }
        if rel.endswith(VIDEO_SUFFIXES):
            entry["video"] = ffprobe(binary, root / rel)
        entries.append(entry)
    rerendered = "".join(f"{e['sha256']}  {e['path']}\n" for e in entries).encode(
        "utf-8"
    )
    if rerendered != listed_bytes:
        raise SystemExit(f"file list does not re-render {CHECKSUMS} byte for byte")
    by_digest = {}
    for e in entries:
        by_digest.setdefault(e["sha256"], []).append(e["path"])
    duplicates = [
        {"sha256": d, "paths": paths}
        for d, paths in sorted(by_digest.items())
        if len(paths) > 1
    ]
    checksum_file = {
        "path": CHECKSUMS,
        "sha256": hashlib.sha256(listed_bytes).hexdigest(),
        "byteLength": len(listed_bytes),
        "lineCount": len(listed),
        "rerenderedByteForByte": True,
        "excludedFromFileList": "a checksum file cannot list its own digest",
    }
    return entries, checksum_file, duplicates


def films_section(data, files):
    probe = {e["path"]: e["video"] for e in files if "video" in e}
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
    # Only key, target_s and trim_start enter the record; refuse_leaks reads real_title solely to
    # refuse any output containing one. The url and note columns are never read.
    prov = {r["key"]: r for r in read_tsv(data / f"{MEDIA}/provenance/manifest.tsv")}
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
        clip, source = f"clips/{key}.mp4", None
        for e in files:
            if e["path"].startswith(f"source/{key}."):
                source = e["path"]
        film = {
            "coverName": key,
            "presentations": presentations[key],
            "scanDurationSeconds": scan,
        }
        if clip in probe and source is not None:
            trim = float(prov[key]["trim_start"])
            clip_s = probe[clip]["durationSeconds"]
            source_s = probe[source]["durationSeconds"]
            film.update(
                {
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
        elif clip in probe or source is not None:
            raise SystemExit(
                f"{key}: a clip without a source, or a source without a clip"
            )
        else:
            film.update(
                {
                    "sourcePath": None,
                    "trimStartSeconds": None,
                    "clipPath": None,
                    "clipDurationSeconds": None,
                    "unestablished": "no source or clip exists under the media root; the provenance "
                    "manifest's trim_start for this key was never applied to a file, so it is not "
                    "recorded as a trim",
                }
            )
        films.append(film)
    return films, prov


def excerpt_section(data, films):
    by_key = {f["coverName"]: f for f in films}
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
        receipt = json.loads(
            (data / f"filmfestival/derived/annotation-{coder}.receipt.json").read_text()
        )
        digest = sha256(data / tsv)
        if digest != receipt["output"]["sha256"]:
            raise SystemExit(
                f"{tsv}: sha256 {digest} is not the replay receipt's output"
            )
        offset = receipt["counts"]["run2SceneNumberOffset"]
        by_film = {}
        for r in read_tsv(data / tsv):
            ordinal = int(r["film"].split(".", 1)[0])
            run = "run-01" if ordinal <= FILMS_PER_RUN else "run-02"
            if r["part_id"] != run:
                raise SystemExit(
                    f"{tsv}: film ordinal {ordinal} is not in {r['part_id']}"
                )
            position = ordinal - (0 if run == "run-01" else FILMS_PER_RUN)
            by_film.setdefault(order[(run, position)], []).append(r)
        for key in EXCERPTS:
            rows = by_film[key]
            run = rows[0]["part_id"]

            def row_ref(r):
                g = int(r["scene_number"]) if r["scene_number"] else None
                return {
                    "fineRow": int(r["segment"]),
                    "coarseSceneGlobal": g,
                    "coarseSceneRunRelative": (
                        None if g is None else g - (offset if run == "run-02" else 0)
                    ),
                    "startSeconds": int(r["start_s"]),
                    "endSeconds": int(r["end_s"]) if r["end_s"] else None,
                }

            first, last = row_ref(rows[1]), row_ref(rows[-1])
            starts = [int(r["start_s"]) for r in rows]
            excerpts[key]["coders"].append(
                {
                    "coder": coder,
                    "annotation": {"path": tsv, "sha256": digest},
                    "coarseNumberingUsable": receipt["sceneNumberingUsableForGold"],
                    "run": run,
                    "fineRowCount": len(rows),
                    "titleCardRow": row_ref(rows[0]),
                    "contentFirstRow": first,
                    "contentLastRow": last,
                    "contentStartSeconds": first["startSeconds"],
                    "contentEndSeconds": last["endSeconds"],
                    "annotatedContentSeconds": (
                        None
                        if last["endSeconds"] is None
                        else last["endSeconds"] - first["startSeconds"]
                    ),
                    "fineStartsNonDecreasing": starts == sorted(starts),
                }
            )
    for ex in excerpts.values():
        bounds = {
            (c["contentStartSeconds"], c["contentEndSeconds"]) for c in ex["coders"]
        }
        ex["codersAgreeOnContentBounds"] = len(bounds) == 1
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
            "reverifiedByThisGenerator": False,
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
                "remote": None,
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


def films_note(data):
    rel = "filmfestival/FILMS.md"
    raw = (data / rel).read_bytes()
    recorded = next(
        v["sha256"]
        for v in json.loads(SOURCE_MANIFEST.read_text())["verification"][
            "notVerifiableByBlob"
        ]
        if v["id"] == "FILMS.md"
    )
    digest = hashlib.sha256(raw).hexdigest()
    return {
        "path": rel,
        "sha256": digest,
        "byteLength": len(raw),
        "sourceManifestRecordsSha256": recorded,
        "sourceManifestDigestCurrent": digest == recorded,
        "storage": "external-local-only; names commercial works and is not for Git admission",
    }


def build(data, binary):
    files, checksum_file, duplicates = files_section(data, binary)
    films, prov = films_section(data, files)
    record = {
        "schema": "storymodel4s.filmfestival.media-manifest",
        "schemaVersion": 1,
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
            "files[].video": "ffprobe format_name, format duration and per-stream codec_name",
            "films[].presentations": "filmfestival/task-movie_run-0{1,2}_events.tsv (ds004042)",
            "films[].scanDurationSeconds": "events.tsv duration; equal to provenance "
            "manifest.tsv target_s",
            "films[].trimStartSeconds": f"{MEDIA}/provenance/manifest.tsv column trim_start",
            "films[].sourceDurationSeconds": "ffprobe of the source file",
            "films[].clipDurationSeconds": "ffprobe of the clip file",
            "films[].clipShortfallSeconds": "scanDurationSeconds - clipDurationSeconds",
            "excerpts[].coders[]": "filmfestival/derived/annotation-<coder>.tsv, bound to its "
            "replay receipt; of the film label only its ordinal prefix is used, and no "
            "description text enters this record",
        },
        "checksumFile": checksum_file,
        "files": files,
        "identicalDigestGroups": duplicates,
        "films": films,
        "trimVerification": {
            "claimedBy": cite(
                data,
                f"{MEDIA}/provenance/STATUS.md",
                "trim point confirmed by matching the frame at `trim_start`",
            ),
            "reverifiedByThisGenerator": False,
        },
        "excerpts": excerpt_section(data, films),
        "excerptConventions": {
            "titleCardRow": cite(
                data,
                f"{MEDIA}/provenance/README.md",
                "the annotators' `+0` row is always the",
            ),
            "contentEndSeconds": "the replay sets a row's end to the next fine start in the "
            "same run, so the last content row ends where the following "
            "film's title-card row starts",
            "clock": "annotation times are run-relative and are not the events.tsv clock",
            "filmAssignment": "annotation film ordinal k is taken as events.tsv row k of run 1 "
            "(k <= 6) or row k-6 of run 2; the replay receipts list this as a "
            "non-claim",
            "fineRow": "the replay's 1-based annotated-row ordinal for that coder, not an "
            "upstream column",
        },
        "upstream": upstream_section(data),
        "filmsNote": films_note(data),
        "nonClaims": [
            "clip-equals-presented-edition",
            "trim-points-reverified-against-frames",
            "annotation-to-events-tsv-clock-offset-resolved",
            "excerpt-internal-cuts-known",
            "excerpt-media-located",
            "annotation-film-assignment-verified-against-presentation-schedule",
            "remote-per-file-paths-verified",
            "reference-annotator-selected",
            "media-admitted-to-git",
        ],
    }
    text = json.dumps(record, indent=2, ensure_ascii=False) + "\n"
    refuse_leaks(text, prov)
    return text


def refuse_leaks(text, prov):
    lowered = text.lower()
    for marker in URL_MARKERS:
        if marker in lowered:
            raise SystemExit(
                f"refusing to write: output contains URL marker {marker!r}"
            )
    for row in prov.values():
        title = re.sub(r"\s*\(\d{4}\)\s*$", "", row["real_title"]).strip().lower()
        if title and title in lowered:
            raise SystemExit("refusing to write: output contains a real film title")


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
