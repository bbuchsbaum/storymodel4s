#!/usr/bin/env python3
"""The FilmFestival clip clock, and the rule that turns a time answer into a scene.

Protocol: docs/plans/2026-11-10-filmfestival-automatic-input-protocol.md, "From a time to a
scene".

In the automatic-input and no-record conditions the model answers with a film and a time in that
film's clip. The reference is scene-level, so the time is converted to a scene after the run. This
module is the only place that conversion happens. Arithmetic is exact (fractions), so a time on a
boundary is decided the same way everywhere.

Usage:
    ff_time.py check DATA_ROOT FRAMES_JSON     print each film's scene-interval summary
    ff_time.py rederive ANNOTATION FRAMES_JSON REPLIES_DIR
                                               recompute every time prediction; exit 1 if any differs

Clock. `clip = a * (annotation - C0) + b`, per film. C0 is the run-relative start of the film's
first row that is not a title card; `build` refuses if the annotation disagrees. The constants
were measured on 2026-11-09, before any model call of this condition, by matching quoted dialogue
in the annotation to the Whisper transcript and detected cuts to annotated row starts (nine films
have a = 1; Catch Me If You Can runs at a = 0.996). Estimated uncertainty is 0.5 to 1.5 seconds.

Scene intervals, on the clip clock. Only valid scene targets get an interval (a title-only scene
does not). A scene starts at its earliest row start, rows in any order, and ends where the next
scene starts. The first scene starts at 0, because the clip starts at the first content row. The
last scene ends at the end of the clip, and includes that instant.

Time strings. `m:ss`, `mm:ss` and either with a decimal fraction of a second, and nothing else:
no bare number of seconds, no hours, no surrounding space, seconds 00-59.
"""

import json
import os
import re
import statistics
import sys
from fractions import Fraction

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_gold  # noqa: E402

# Film code -> C0 (annotation seconds, run-relative), a, b (seconds). Decimal strings, so exact.
CLOCK = {
    1: {"C0": "44", "a": "0.996", "b": "0.75"},
    2: {"C0": "397", "a": "1", "b": "-0.7"},
    3: {"C0": "534", "a": "1", "b": "0.3"},
    4: {"C0": "1005", "a": "1", "b": "0.2"},
    5: {"C0": "1141", "a": "1", "b": "-0.9"},
    6: {"C0": "45", "a": "1", "b": "0.1"},
    7: {"C0": "375", "a": "1", "b": "0.6"},
    8: {"C0": "640", "a": "1", "b": "0.1"},
    9: {"C0": "787", "a": "1", "b": "0.2"},
    10: {"C0": "956", "a": "1", "b": "0.3"},
}
# A scene is near a time when its interval lies within this many seconds of it.
TOLERANCE = "2"
TIME = re.compile(r"(\d{1,2}):([0-5]\d(?:\.\d{1,3})?)")


def is_int(x):
    return isinstance(x, int) and not isinstance(x, bool)


def parse_time(text):
    """Seconds (exact) for a `m:ss` string, or None for anything else."""
    if not isinstance(text, str):
        return None
    match = TIME.fullmatch(text)
    if not match:
        return None
    return int(match.group(1)) * 60 + Fraction(match.group(2))


def to_clip(film, annotation_seconds):
    """Clip time of an annotation time in `film`."""
    k = CLOCK[film]
    return Fraction(k["a"]) * (Fraction(annotation_seconds) - Fraction(k["C0"])) + (
        Fraction(k["b"])
    )


def build(rows, film_of, valid, durations):
    """Scene intervals per film: {film: {"duration": d, "scenes": [(scene, start, end), ...]}}.

    `rows` are annotation rows, `durations` maps each film code to its clip length in seconds.
    Refuses when the annotation does not fit the clock or the clip.
    """
    starts, content = {}, {}
    for r in rows:
        scene = int(r["scene_number"])
        film = film_of[scene]
        if film not in ff_gold.FILMS:
            continue
        at = Fraction(r["start_s"])
        starts[scene] = min(at, starts.get(scene, at))
        if ff_gold.TITLE_MARK not in r["description"]:
            content[film] = min(at, content.get(film, at))
    table = {}
    for film in ff_gold.FILMS:
        if film not in content or film not in CLOCK or film not in durations:
            raise ff_gold.GoldError(f"film {film} has no content row, clock or clip")
        c0 = Fraction(CLOCK[film]["C0"])
        if content[film] != c0:
            raise ff_gold.GoldError(
                f"film {film}: the clock's C0 is {c0}, the first content row starts at "
                f"{content[film]}"
            )
        duration = Fraction(str(durations[film]))
        own = sorted(s for s in starts if film_of[s] == film)
        for scene in own:
            if scene not in valid and starts[scene] >= c0:
                raise ff_gold.GoldError(
                    f"scene {scene} is not a target but lies inside the clip"
                )
        own = [s for s in own if s in valid]
        begin = [to_clip(film, starts[s]) for s in own]
        if begin[0] > Fraction(CLOCK[film]["b"]):
            raise ff_gold.GoldError(
                f"film {film}: no scene holds the first content row"
            )
        begin[0] = Fraction(0)
        bounds = begin + [duration]
        if any(a >= b for a, b in zip(bounds, bounds[1:])):
            raise ff_gold.GoldError(
                f"film {film}: scene starts are not increasing inside the clip"
            )
        table[film] = {
            "duration": duration,
            "scenes": [(s, bounds[i], bounds[i + 1]) for i, s in enumerate(own)],
        }
    return table


def load(annotation_path, durations):
    """The scene-interval table for an annotation file and the clips' lengths."""
    film_of, valid = ff_gold.load_dictionary(annotation_path)
    return build(ff_gold.read_tsv(annotation_path), film_of, valid, durations)


def durations(frames_path):
    """{film code: clip seconds} as recorded by ffprobe in a frame list (see ff_frames.py)."""
    with open(frames_path, encoding="utf-8") as handle:
        doc = json.load(handle)
    return {entry["film"]: entry["clipSeconds"] for entry in doc["films"]}


def strict_scene(table, film, seconds):
    """The scene whose interval contains `seconds`; None before 0 or after the end of the clip."""
    entry = table[film]
    if seconds < 0 or seconds > entry["duration"]:
        return None
    for scene, start, end in entry["scenes"]:
        if start <= seconds < end:
            return scene
    return entry["scenes"][-1][0]


def near_scenes(table, film, seconds, tolerance=TOLERANCE):
    """Every scene whose interval is within `tolerance` seconds of `seconds`, in scene order.

    The distance to an interval is 0 inside it and at both its ends, so the scene that strictly
    contains the time is included, and a time on a boundary is near the scenes on both sides.
    """
    limit = Fraction(tolerance)
    out = []
    for scene, start, end in table[film]["scenes"]:
        distance = max(start - seconds, seconds - end, 0)
        if distance <= limit:
            out.append(scene)
    return out


def prediction(table, film, time):
    """One raw answer, a film and a time, as a scorer prediction.

    - no time: the film alone (kind `film`), or no assignment when the film is null too;
    - a time inside the film's clip: kind `scene`, with the strict scene, the near scenes, and
      the raw answer kept beside them;
    - a time outside the clip: kind `time_outside`. It is an answer that names the film and
      selects no scene. The scorer counts it apart; it is never a film-only answer and never a
      processing failure;
    - a time that is not a `m:ss` string, a time without a film, or a film that is not one of
      the ten: a failure with a reason, with the raw answer kept.
    """
    if time is None:
        if is_int(film):
            return {"status": "answered", "kind": "film", "film": film}
        if film is None:
            return {"status": "answered", "kind": "none"}
        return {"status": "failed", "reason": "invalid-film"}
    raw = {"film_stated": film, "time": time}
    seconds = parse_time(time)
    if seconds is None:
        return {"status": "failed", "reason": "invalid-time", **raw}
    if film is None:
        return {"status": "failed", "reason": "time-without-film", **raw}
    if not is_int(film):
        return {"status": "failed", "reason": "invalid-film", **raw}
    if film not in table:
        return {"status": "failed", "reason": "unknown-film", **raw}
    scene = strict_scene(table, film, seconds)
    rest = {
        "near": near_scenes(table, film, seconds),
        **raw,
        "seconds": float(seconds),
    }
    if scene is None:
        return {"status": "answered", "kind": "time_outside", "film": film, **rest}
    return {"status": "answered", "kind": "scene", "scene": scene, **rest}


def rederive(table, reply):
    """Every time prediction of one reply file that differs from its recomputation.

    A prediction made from a time keeps its raw `film_stated` and `time`; converting those again
    must give the stored prediction exactly. Returns [(row, field, stored, recomputed), ...].
    """
    out = []
    for unit in reply["units"]:
        for field in ("referent", "narrative"):
            stored = unit.get(field)
            if not isinstance(stored, dict) or "time" not in stored:
                continue
            again = prediction(table, stored.get("film_stated"), stored["time"])
            if again != stored:
                out.append((unit.get("row"), field, stored, again))
    return out


def rederive_directory(table, directory):
    """`rederive` over every reply file of a directory: (files, time predictions, differences)."""
    files, checked, differences = 0, 0, []
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(directory, name), encoding="utf-8") as handle:
            reply = json.load(handle)
        files += 1
        for unit in reply["units"]:
            for field in ("referent", "narrative"):
                stored = unit.get(field)
                checked += isinstance(stored, dict) and "time" in stored
        differences += [(name,) + d for d in rederive(table, reply)]
    return files, checked, differences


def canonical(table):
    """The table as text, for hashing: one line per film and per scene, six decimals."""

    def number(x):
        return f"{float(x):.6f}"

    lines = []
    for film in sorted(table):
        lines.append(f"film\t{film}\t{number(table[film]['duration'])}")
        for scene, start, end in table[film]["scenes"]:
            lines.append(f"{scene}\t{number(start)}\t{number(end)}")
    return "\n".join(lines) + "\n"


def summary(table):
    """Per film: scene count, first start, last end, shortest and median scene length."""
    out = []
    for film in sorted(table):
        scenes = table[film]["scenes"]
        lengths = [float(end - start) for _, start, end in scenes]
        out.append(
            {
                "film": film,
                "scenes": len(scenes),
                "first_scene": scenes[0][0],
                "last_scene": scenes[-1][0],
                "first_start": float(scenes[0][1]),
                "last_end": float(scenes[-1][2]),
                "min_seconds": min(lengths),
                "median_seconds": statistics.median(lengths),
            }
        )
    return out


def main(argv):
    if len(argv) == 3 and argv[0] == "check":
        annotation = os.path.join(argv[1], "filmfestival/derived/annotation-JL.tsv")
        table = load(annotation, durations(argv[2]))
        print("film\tscenes\tfirst\tlast\tfirst_start\tlast_end\tmin_s\tmedian_s")
        for row in summary(table):
            print(
                "{film}\t{scenes}\t{first_scene}\t{last_scene}\t{first_start:.2f}\t"
                "{last_end:.2f}\t{min_seconds:.2f}\t{median_seconds:.2f}".format(**row)
            )
        return 0
    if len(argv) == 4 and argv[0] == "rederive":
        table = load(argv[1], durations(argv[2]))
        files, checked, differences = rederive_directory(table, argv[3])
        for name, row, field, _, _ in differences:
            print(f"differs: {name} row {row} {field}", file=sys.stderr)
        print(f"files={files} time_predictions={checked} differ={len(differences)}")
        return 1 if differences else 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
