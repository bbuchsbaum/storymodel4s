#!/usr/bin/env python3
"""Sample frames from the ten FilmFestival clips for the automatic-input condition.

Protocol: docs/plans/2026-11-10-filmfestival-automatic-input-protocol.md, "The film record".

Usage:
    ff_frames.py extract DATA_ROOT OUT_DIR    write OUT_DIR/frames/<cover>/fNNNN.jpg and frames.json
    ff_frames.py verify DATA_ROOT OUT_DIR     recompute every hash and count; exit 1 on a mismatch

One frame every 2 seconds from the start of each clip: frame k is the first video frame at or
after (k-1)*2 seconds, 512 pixels wide. The lobby cartoon is not sampled. `extract` refuses if its
output exists.

How the times are known. ffmpeg's `fps=1/2` filter does not give frames at 0, 2, 4, ... seconds:
with its default rounding it picks a frame about one second later (measured on these clips). So
frames are chosen by time with the `select` filter, and `showinfo` reports the source timestamp of
every frame written. `extract` refuses unless each timestamp is within one video frame of its
nominal time and the last frame is within one interval of the end of the clip. Both the nominal
time (`seconds`) and the source timestamp (`ptsSeconds`) are recorded.

frames.json lists, per film in film-code order: the film code, the clip's cover name, SHA-256 and
length (ffprobe), and every frame's file, time and SHA-256.
"""

import hashlib
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_packet  # noqa: E402

SCHEMA = "storymodel4s.filmfestival.frames/v1"
INTERVAL = 2
WIDTH = 512
QUALITY = 4
# A frame's source timestamp may trail its nominal time by at most one video frame (25 fps).
SLACK = 0.05
FILTER = f"select='gte(t,selected_n*{INTERVAL})',showinfo,scale={WIDTH}:-2"
PTS = re.compile(r"Parsed_showinfo.* n: *\d+ .*pts_time:([0-9.]+)")


class FrameError(RuntimeError):
    pass


def file_sha(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def tool(cmd):
    done = subprocess.run(cmd, capture_output=True, text=True, timeout=3600)
    if done.returncode:
        raise FrameError(f"{cmd[0]} failed: {done.stderr[-300:]}")
    return done


def clip_seconds(clip):
    cmd = ["ffprobe", "-v", "error", "-show_entries", "format=duration"]
    return float(tool(cmd + ["-of", "default=nw=1:nk=1", clip]).stdout.strip())


def ffmpeg_version():
    return tool(["ffmpeg", "-version"]).stdout.splitlines()[0].strip()


def sample(clip, directory):
    """Write the frames of one clip into `directory`; returns their source timestamps."""
    cmd = ["ffmpeg", "-nostdin", "-v", "info", "-i", clip, "-vf", FILTER]
    cmd += ["-fps_mode", "passthrough", "-start_number", "1", "-q:v", str(QUALITY)]
    done = tool(cmd + [os.path.join(directory, "f%04d.jpg")])
    return [float(m.group(1)) for m in PTS.finditer(done.stderr)]


def check_times(cover, stamps, names, seconds):
    """Refuse unless the frames written are the frames at 0, 2, 4, ... to the end of the clip."""
    if names != [f"f{k:04d}.jpg" for k in range(1, len(stamps) + 1)]:
        raise FrameError(f"{cover}: {len(names)} files for {len(stamps)} timestamps")
    if not stamps:
        raise FrameError(f"{cover}: no frame")
    for k, at in enumerate(stamps):
        if not 0 <= at - k * INTERVAL <= SLACK:
            raise FrameError(f"{cover}: frame {k + 1} is at {at} s")
    last = (len(stamps) - 1) * INTERVAL
    if not seconds - INTERVAL - SLACK <= last < seconds:
        raise FrameError(f"{cover}: last frame at {last} s of {seconds} s")


def clip_path(data_root, cover):
    return os.path.join(data_root, "filmfestival/media/clips", f"{cover}.mp4")


def extract(data_root, out_dir):
    """Sample every clip and return the frame list document."""
    films = []
    for film, cover in sorted(ff_packet.CLIPS.items()):
        clip = clip_path(data_root, cover)
        directory = os.path.join(out_dir, "frames", cover)
        os.makedirs(directory)
        seconds = clip_seconds(clip)
        stamps = sample(clip, directory)
        names = sorted(os.listdir(directory))
        check_times(cover, stamps, names, seconds)
        films.append(
            {
                "film": film,
                "cover": cover,
                "clipSha256": file_sha(clip),
                "clipSeconds": seconds,
                "frames": [
                    {
                        "file": f"frames/{cover}/{name}",
                        "seconds": k * INTERVAL,
                        "ptsSeconds": stamps[k],
                        "sha256": file_sha(os.path.join(directory, name)),
                    }
                    for k, name in enumerate(names)
                ],
            }
        )
    return {
        "schema": SCHEMA,
        "intervalSeconds": INTERVAL,
        "width": WIDTH,
        "quality": QUALITY,
        "filter": FILTER,
        "ffmpeg": ffmpeg_version(),
        "totalFrames": sum(len(f["frames"]) for f in films),
        "films": films,
    }


def load(out_dir):
    with open(os.path.join(out_dir, "frames.json"), encoding="utf-8") as handle:
        doc = json.load(handle)
    if doc.get("schema") != SCHEMA:
        raise FrameError("frames.json has an unknown schema")
    return doc


def verify(data_root, out_dir):
    """Every way the files on disk differ from frames.json; an empty list when they agree."""
    doc = load(out_dir)
    problems = []
    if [f["film"] for f in doc["films"]] != sorted(ff_packet.CLIPS):
        problems.append("the films listed are not the ten films in order")
    total = 0
    for entry in doc["films"]:
        cover = entry["cover"]
        if ff_packet.CLIPS.get(entry["film"]) != cover:
            problems.append(f"film {entry['film']} is listed with the clip {cover}")
            continue
        clip = clip_path(data_root, cover)
        if file_sha(clip) != entry["clipSha256"]:
            problems.append(f"{cover}: the clip has changed")
        directory = os.path.join(out_dir, "frames", cover)
        listed = [frame["file"] for frame in entry["frames"]]
        on_disk = [f"frames/{cover}/{name}" for name in sorted(os.listdir(directory))]
        if listed != on_disk:
            problems.append(f"{cover}: {len(on_disk)} files, {len(listed)} listed")
            continue
        for k, frame in enumerate(entry["frames"]):
            total += 1
            if frame["seconds"] != k * INTERVAL:
                problems.append(f"{frame['file']}: listed at {frame['seconds']} s")
            if file_sha(os.path.join(out_dir, frame["file"])) != frame["sha256"]:
                problems.append(f"{frame['file']}: the file has changed")
        last = (len(listed) - 1) * INTERVAL
        if not entry["clipSeconds"] - INTERVAL - SLACK <= last < entry["clipSeconds"]:
            problems.append(f"{cover}: the frames do not reach the end of the clip")
    if total != doc["totalFrames"]:
        problems.append(f"{total} frames, {doc['totalFrames']} recorded")
    return problems


def main(argv):
    if len(argv) == 3 and argv[0] == "extract":
        data_root, out_dir = argv[1:]
        target = os.path.join(out_dir, "frames.json")
        if os.path.exists(target) or os.path.exists(os.path.join(out_dir, "frames")):
            print("refused: the output exists", file=sys.stderr)
            return 2
        try:
            doc = extract(data_root, out_dir)
        except FrameError as error:
            print(f"refused: {error}", file=sys.stderr)
            return 2
        with open(target, "w", encoding="utf-8") as handle:
            json.dump(doc, handle, indent=1, sort_keys=True)
            handle.write("\n")
        for entry in doc["films"]:
            print(f"{entry['film']}\t{entry['cover']}\t{len(entry['frames'])}")
        print(f"total\t{doc['totalFrames']}\tframes.json sha256 {file_sha(target)}")
        return 0
    if len(argv) == 3 and argv[0] == "verify":
        try:
            problems = verify(argv[1], argv[2])
        except (FrameError, OSError) as error:
            problems = [str(error)]
        for problem in problems:
            print(f"mismatch: {problem}", file=sys.stderr)
        if problems:
            return 1
        print(
            f"verified frames.json sha256 {file_sha(os.path.join(argv[2], 'frames.json'))}"
        )
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
