#!/usr/bin/env python3
"""Extract evenly spaced frames for each Sherlock scene and write one caption request.

Three quarters of the distant recall-anchor confusions measured on development recalls cross a
scene boundary, so the scene level is where visual discrimination is worth adding first: 50 scenes
rather than 1000 microsegments, at a twentieth of the cost.

Scene bounds come from the annotation table's own start and end seconds. Rows 0-481 are part A and
rows 482-999 are part B, each timed from its own part's start; a scene that straddles the boundary
is split and only its majority part is used, which is recorded in the manifest.

All frames go into one raw bgr24 file so the captioning worker loads the model once and answers all
scenes in a single run. Nothing here is committed: the frames are derived from film bytes that stay
external.

Usage: extract_scene_frames.py OUT_DIR [FRAMES_PER_SCENE] [WIDTH] [HEIGHT]
"""

import csv
import hashlib
import json
import os
import subprocess
import sys


def data_root():
    """$STORYMODEL4S_DATA, else <main checkout>/data; the same rule as tools/data-root.sh."""
    env = os.environ.get("STORYMODEL4S_DATA")
    if env:
        return env
    common = subprocess.check_output(
        ["git", "rev-parse", "--git-common-dir"], text=True
    ).strip()
    return os.path.normpath(os.path.join(common, "..", "data"))


DATA = data_root()
ANNOTATION = os.path.join(DATA, "sherlock", "Sherlock_Segments_1000_NN_2017.tsv")
PARTS = {
    "media-part-a": os.path.join(
        DATA, "sherlock", "media", "Sherlock_part1_imovie.m4v"
    ),
    "media-part-b": os.path.join(
        DATA, "sherlock", "media", "Sherlock_part2_imovie.m4v"
    ),
}
PART_A_LAST_ROW = 481
REALIZED_FFMPEG = (
    "/Users/bbuchsbaum/code/scala/storymodel4s/.worktrees/perception-first-court"
    "/tmp/ffmpeg-9.0.1/install2/bin/ffmpeg"
)


def ffmpeg_bin():
    return REALIZED_FFMPEG if os.path.isfile(REALIZED_FFMPEG) else "ffmpeg"


def scenes():
    with open(ANNOTATION, encoding="utf-8", errors="replace") as fh:
        rd = csv.reader(fh, delimiter="\t")
        hdr = next(rd)
        si, st, en = (
            hdr.index("Scene Segments"),
            hdr.index("Start Time (s) "),
            hdr.index("End Time (s) "),
        )
        out, order, cur = {}, [], None
        for i, r in enumerate(rd):
            v = r[si].strip() if len(r) > si else ""
            if v:
                cur = v
                if v not in order:
                    order.append(v)
            if cur is None:
                continue
            try:
                a, b = float(r[st]), float(r[en])
            except (ValueError, IndexError):
                continue
            part = "media-part-a" if i <= PART_A_LAST_ROW else "media-part-b"
            d = out.setdefault(cur, {})
            p = d.setdefault(part, {"rows": [], "start": a, "end": b})
            p["rows"].append(i)
            p["start"], p["end"] = min(p["start"], a), max(p["end"], b)
    return order, out


def grab(binary, src, seconds, width, height, scratch):
    """One frame at `seconds`, scaled, as raw bgr24 bytes. Input seek keeps it fast.

    The frame goes to a file rather than a pipe: the realized LGPL FFmpeg build enables only the
    `file` protocol, so `pipe:` is unavailable to it by construction.
    """
    cmd = [
        binary,
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-protocol_whitelist",
        "file",
        "-ss",
        f"{seconds:.3f}",
        "-i",
        src,
        "-frames:v",
        "1",
        "-vf",
        f"scale={width}:{height}:in_color_matrix=bt601:in_range=tv:out_range=pc",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "bgr24",
        scratch,
    ]
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(
            f"frame at {seconds:.1f}s: rc={r.returncode} {r.stderr[:200]!r}"
        )
    data = open(scratch, "rb").read()
    if len(data) != width * height * 3:
        raise RuntimeError(
            f"frame at {seconds:.1f}s: {len(data)} bytes, expected {width*height*3}"
        )
    return data


def main(argv):
    out_dir = (
        argv[1]
        if len(argv) > 1
        else os.path.join(DATA, "study", "recall-to-video", "scene-frames")
    )
    per = int(argv[2]) if len(argv) > 2 else 8
    width = int(argv[3]) if len(argv) > 3 else 384
    height = int(argv[4]) if len(argv) > 4 else 216
    os.makedirs(out_dir, exist_ok=True)
    binary = ffmpeg_bin()

    order, sc = scenes()
    raw_path = os.path.join(out_dir, "scene-frames.bgr")
    scratch = os.path.join(out_dir, ".frame.bgr")
    extents, manifest, ordinal, straddled = [], [], 0, []
    with open(raw_path, "wb") as raw:
        for label in order:
            parts = sc[label]
            if len(parts) > 1:
                straddled.append(label)
            part = max(parts, key=lambda k: len(parts[k]["rows"]))
            d = parts[part]
            start, end = d["start"], d["end"]
            span = max(end - start, 0.5)
            offsets = [start + span * (k + 0.5) / per for k in range(per)]
            base = ordinal * per
            for off in offsets:
                raw.write(grab(binary, PARTS[part], off, width, height, scratch))
            extents.append(
                {
                    "id": f"scene-{ordinal:02d}",
                    "ordinals": list(range(base, base + per)),
                }
            )
            manifest.append(
                {
                    "sceneOrdinal": ordinal,
                    "label": label,
                    "part": part,
                    "startSeconds": start,
                    "endSeconds": end,
                    "rows": [min(d["rows"]), max(d["rows"])],
                    "frameOrdinals": [base, base + per - 1],
                }
            )
            ordinal += 1
            print(
                f"  scene {ordinal:02d}/{len(order)} {label[:34]:34s} {part} {start:7.1f}-{end:7.1f}s"
            )

    total = ordinal * per
    digest = hashlib.sha256(open(raw_path, "rb").read()).hexdigest()
    json.dump(
        {
            "scenes": manifest,
            "framesPerScene": per,
            "width": width,
            "height": height,
            "framesSha256": digest,
            "frameCount": total,
            "straddlingScenes": straddled,
            "ffmpeg": binary,
        },
        open(os.path.join(out_dir, "scene-manifest.json"), "w"),
        indent=2,
        sort_keys=True,
    )
    print(
        f"\n{total} frames, {ordinal} scenes, sha256 {digest[:16]}, straddling {len(straddled)}"
    )
    if os.path.exists(scratch):
        os.remove(scratch)
    print(f"wrote {raw_path} and scene-manifest.json")


if __name__ == "__main__":
    main(sys.argv)
