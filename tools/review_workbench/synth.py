#!/usr/bin/env python3
"""Synthetic specimen for the recall review workbench: an invented two-part film ("The Harbour
Light"), its annotation table, ASR transcript, scene onsets, one invented recall, Codex-shaped
census and templates, and a generated test film. Every word here is invented for testing (rule
14d); no story text, recall or excerpt from any real source is used. Agents develop and hill-climb
the workbench on this specimen only.

The files reuse the real input schemas (column names of the Sherlock annotation TSV, the
word-timestamped recall CSV, the scene-onset CSVs and the unified-v1 transcript JSON), so the
workbench code that reads them is the code that reads the real packet.

Usage: python3 -I synth.py OUT_DIR [--no-media]
"""
import csv
import hashlib
import json
from pathlib import Path
import subprocess
import sys

TR = 1.5
SUBJECT = "S01"

# (scene id, part, location, in/out, rows); each row is
# (seconds, Name-All, Focus, Speaking, details, words on screen, dialogue lines).
SCENES = [
    (
        1,
        1,
        "Lighthouse stairs",
        "Indoor",
        [
            (
                9,
                "Mara",
                "Mara",
                "",
                "Mara climbs the spiral stairs at dawn carrying an oil can.",
                "",
                [],
            ),
            (
                8,
                "Mara",
                "Mara",
                "Mara",
                "She stops at a window and looks down at the harbour.",
                "",
                ["the boats are late again"],
            ),
            (
                7,
                "Mara, Harbourmaster",
                "Harbourmaster",
                "Harbourmaster",
                "The Harbourmaster shouts up from the bottom of the stairs.",
                "",
                ["Mara come down there is a letter for you"],
            ),
        ],
    ),
    (
        2,
        1,
        "Harbour office",
        "Indoor",
        [
            (
                8,
                "Harbourmaster, Tobias",
                "Tobias",
                "Tobias",
                "Tobias Quill enters the office soaked with rain holding a sealed envelope.",
                "",
                ["I rode all night from the north road"],
            ),
            (
                9,
                "Harbourmaster, Tobias",
                "Harbourmaster",
                "Harbourmaster",
                "The Harbourmaster turns the envelope over and frowns at the red seal.",
                "",
                ["this seal belongs to the Vell family", "she will not like it"],
            ),
            (
                7,
                "Harbourmaster, Mara, Tobias",
                "Mara",
                "Mara",
                "Mara arrives and snatches the envelope from the desk.",
                "",
                ["give that to me"],
            ),
            (
                6,
                "Mara, Tobias",
                "Tobias",
                "Tobias",
                "Tobias follows her out into the rain.",
                "",
                ["wait you have not read it"],
            ),
        ],
    ),
    (
        3,
        1,
        "Fish market",
        "Outdoor",
        [
            (
                8,
                "Mara, 2 Fishermen",
                "2 Fishermen",
                "2 Fishermen",
                "Two fishermen argue over a torn net beside the ice tables.",
                "",
                ["that net was mine", "you left it on the rocks"],
            ),
            (
                7,
                "Mara, Girl with Lantern",
                "Girl with Lantern",
                "",
                "A girl holding an unlit lantern watches Mara buy a coil of rope.",
                "",
                [],
            ),
            (
                8,
                "Mara, Girl with Lantern",
                "Mara",
                "Mara",
                "Mara kneels and asks the girl her name; the girl runs away.",
                "",
                ["what is your name", "do you live by the water"],
            ),
        ],
    ),
    (
        4,
        1,
        "Lamp room",
        "Indoor",
        [
            (
                9,
                "Mara, Tobias",
                "Mara",
                "Tobias",
                "In the lamp room Tobias begs Mara to open the letter.",
                "",
                ["your brother wrote it himself", "he is asking you to come home"],
            ),
            (
                8,
                "Mara, Tobias",
                "Mara",
                "Mara",
                "Mara holds the letter over the lamp flame and it catches fire.",
                "",
                ["I have no brother"],
            ),
            (
                7,
                "Mara, Tobias",
                "Tobias",
                "",
                "Tobias watches the ashes fall and leaves without a word.",
                "",
                [],
            ),
        ],
    ),
    (
        5,
        1,
        "Lighthouse gallery",
        "Outdoor",
        [
            (
                8,
                "Mara",
                "Mara",
                "",
                "A storm hits at night and the great lamp flickers and goes dark.",
                "",
                [],
            ),
            (
                9,
                "Mara",
                "Mara",
                "Mara",
                "Mara climbs outside onto the gallery rail to reach the lens.",
                "",
                ["come on come on"],
            ),
            (
                6,
                "",
                "",
                "",
                "Out at sea a small boat is caught in the waves.",
                "PART ONE END",
                [],
            ),
        ],
    ),
    (
        6,
        2,
        "Rescue boat",
        "Outdoor",
        [
            (
                8,
                "Tobias, 2 Fishermen",
                "Tobias",
                "Tobias",
                "Tobias rows the rescue boat with the two fishermen toward the rocks.",
                "",
                ["pull harder the light is out"],
            ),
            (
                7,
                "Tobias, 2 Fishermen",
                "2 Fishermen",
                "2 Fishermen",
                "One fisherman points at a shape in the water.",
                "",
                ["there by the rocks"],
            ),
            (
                8,
                "Tobias",
                "Tobias",
                "",
                "A wave tips the boat and Tobias goes under.",
                "",
                [],
            ),
        ],
    ),
    (
        7,
        2,
        "The rocks",
        "Outdoor",
        [
            (
                8,
                "Girl with Lantern",
                "Girl with Lantern",
                "",
                "The girl stands on the rocks and lights her lantern.",
                "",
                [],
            ),
            (
                9,
                "Girl with Lantern, Tobias",
                "Tobias",
                "Tobias",
                "Tobias swims toward the small light and pulls himself onto the rocks.",
                "",
                ["who are you"],
            ),
            (
                7,
                "Girl with Lantern, Tobias",
                "Girl with Lantern",
                "Girl with Lantern",
                "The girl wraps her coat around him.",
                "",
                ["my mother kept the light before her"],
            ),
        ],
    ),
    (
        8,
        2,
        "Harbour office",
        "Indoor",
        [
            (
                8,
                "Harbourmaster, Mara",
                "Harbourmaster",
                "Harbourmaster",
                "In the morning the Harbourmaster blames Mara for the dark lamp.",
                "",
                ["two men nearly drowned because of you"],
            ),
            (
                8,
                "Harbourmaster, Mara",
                "Mara",
                "Mara",
                "Mara puts her keys on the desk and says she will leave.",
                "",
                ["then find another keeper"],
            ),
        ],
    ),
    (
        9,
        2,
        "Cliff path",
        "Outdoor",
        [
            (
                9,
                "Mara, Tobias",
                "Tobias",
                "Tobias",
                "Tobias finds Mara on the cliff path with her bag.",
                "",
                ["the girl saved me", "she says the light is in her family"],
            ),
            (
                9,
                "Mara, Tobias",
                "Mara",
                "Mara",
                "Mara admits the letter was from her brother who left years ago.",
                "",
                [
                    "he left me here to keep it",
                    "I burned his letter because I was afraid",
                ],
            ),
            (
                6,
                "Mara, Tobias",
                "Mara",
                "",
                "They look back at the dark lighthouse.",
                "",
                [],
            ),
        ],
    ),
    (
        10,
        2,
        "Lamp room",
        "Indoor",
        [
            (
                8,
                "Mara, Girl with Lantern",
                "Mara",
                "Mara",
                "Mara and the girl carry the lantern flame up to the great lamp.",
                "",
                ["hold it steady"],
            ),
            (
                8,
                "Mara, Girl with Lantern",
                "Girl with Lantern",
                "",
                "The lamp blazes and the girl waves to the boats below.",
                "THE END",
                [],
            ),
        ],
    ),
]

# The recall: scene id, then words. It reorders scenes 3 and 4, merges 6 and 7, omits 8, uses
# pronouns with shifting referents, and includes an external comment (scene 0).
RECALL = [
    (
        1,
        "so it starts with this woman Mara she lives in a lighthouse and she is climbing the "
        "stairs and someone yells up that she has a letter",
    ),
    (
        2,
        "then this guy comes in all wet he is a messenger I think and he has the letter and the "
        "old man the harbour guy says it has her family seal on it and she grabs it",
    ),
    (
        4,
        "and then they are up at the top and he wants her to read it and she just burns it she "
        "says I have no brother",
    ),
    (
        3,
        "oh before that there was a market scene with two fishermen fighting about a net and a "
        "little girl was watching her",
    ),
    (0, "I did not really get why the girl was there at first"),
    (
        5,
        "then there is a storm and the light goes out and she climbs outside to fix it",
    ),
    (
        6,
        "and he goes out in a boat with the fishermen and he falls in and the girl is on the rocks "
        "with a lantern and she saves him",
    ),
    (
        9,
        "and at the end she tells him it was her brother who wrote it and he left her there",
    ),
    (10, "and she and the girl light the lamp again and it is really bright"),
]

ANNOT_COLS = [
    "Segment Number",
    "Start Time (s) ",
    "End Time (s) ",
    "Start Time (TRs, 1.5s)",
    "End Time (TRs, 1.5s)",
    "Scene Segments",
    "Scene Details - A Level ",
    "Space-In/Outdoor",
    "Name - All",
    "Name - Focus",
    "Name - Speaking",
    "Location",
    "Camera Angle",
    "Music Presence ",
    "Words on Screen ",
    "Arousal - Rater 1",
    "Valence - Rater 1",
]
RECALL_COLS = [
    "Words",
    "Princeton Dataspace Word Onset Time (sec)",
    "Princeton Dataspace TR Number",
    "Princeton Dataspace TR Onset (sec)",
    "OpenNeuro Word Onset Time (sec)",
    "OpenNeuro TR Number",
    "OpenNeuro TR Onset (sec)",
]
DECISION_COLS = (
    "decision_id,first_word,last_word,other_coref_spans,reference_form,claim_role,"
    "occurrence_group,source_scene_ids,source_anchor_ids,person_reference,source_resolvable,"
    "reference_outcome,ambiguity_reason,reviewer,reviewed_at"
).split(",")
WINDOW_COLS = ["window", "first_word", "last_word", "reviewed", "reviewer"]
WINDOW_WORDS = 25
FPS = 12


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def build(out: Path, media: bool = True) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    # Annotation rows, part clocks (seconds restart per part, as in the real table) and TR numbers
    # continuing across parts with a declared shift.
    rows, parts, transcript = [], {1: [], 2: []}, {1: [], 2: []}
    clock, n, scene_tr = {1: 0.0, 2: 0.0}, 0, {}
    for sid, part, loc, inout, srows in SCENES:
        t0 = clock[part]
        for sec, all_, focus, speak, details, onscreen, lines in srows:
            n += 1
            s, e = clock[part], clock[part] + sec
            rows.append(
                {
                    "n": n,
                    "part": part,
                    "s": s,
                    "e": e,
                    "scene": sid,
                    "all": all_,
                    "focus": focus,
                    "speak": speak,
                    "details": details,
                    "loc": loc,
                    "inout": inout,
                    "onscreen": onscreen,
                    "lines": lines,
                }
            )
            clock[part] = e
        scene_tr[sid] = (part, int(t0 // TR), int((clock[part] - TR / 2) // TR))
    shift = {1: 0, 2: int(clock[1] // TR) + 1}
    with open(out / "annotation.tsv", "w", newline="") as f:
        w = csv.writer(f, delimiter="\t")
        w.writerow(ANNOT_COLS)
        for r in rows:
            w.writerow(
                [
                    r["n"],
                    f"{r['s']:.2f}",
                    f"{r['e']:.2f}",
                    int(r["s"] // TR) + shift[r["part"]],
                    int((r["e"] - 0.01) // TR) + shift[r["part"]],
                    r["scene"],
                    r["details"],
                    r["inout"],
                    r["all"],
                    r["focus"],
                    r["speak"],
                    r["loc"],
                    "medium",
                    "No",
                    r["onscreen"],
                    3,
                    4,
                ]
            )
    with open(out / "watch-onsets.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["Scan", "Scene", "Onset", "Offset"])
        for sid, (part, a, b) in sorted(scene_tr.items()):
            local = sid if part == 1 else sid - 5
            w.writerow([f"Part{part}", local, a, b])
    # Transcript: each dialogue line is one segment; words spread evenly across the middle of the
    # row. One low-probability word exercises the producer guard.
    for p in (1, 2):
        words, segs = [], []
        for r in (x for x in rows if x["part"] == p):
            for li, line in enumerate(r["lines"]):
                toks = line.split()
                span = (r["e"] - r["s"]) / max(1, len(r["lines"]))
                a = r["s"] + li * span + 0.4
                step = (span - 0.8) / len(toks)
                si = len(segs)
                segs.append(
                    {
                        "index": si,
                        "text": line,
                        "start_seconds": round(a, 2),  # 0.01 s is a whole number of ticks
                        "end_seconds": round(a + step * len(toks), 2),
                        "kind": "speech",
                        "suspect": False,
                        "diagnostics": {},
                    }
                )
                for k, t in enumerate(toks):
                    words.append(
                        {
                            "index": len(words),
                            "text": t,
                            "start_seconds": round(a + k * step, 2),
                            "end_seconds": round(a + (k + 0.8) * step, 2),
                            "segment_index": si,
                            "probability": 0.3 if t == "himself" else 0.95,
                        }
                    )
        transcript[p] = {
            "schema": "synthetic-transcript",
            "words": words,
            "segments": segs,
            "text": " ".join(s["text"] for s in segs),
        }
        (out / f"transcript-part{p}.json").write_text(
            json.dumps(transcript[p], indent=1) + "\n"
        )
    # Recall: 2.4 words per second, one TR every 1.5 s; Princeton and OpenNeuro clocks differ by
    # one TR so the three nomination conventions can disagree, as in the real census.
    recall, rscenes, t = [], [], 3.0
    for sid, text in RECALL:
        first = len(recall)
        for tok in text.split():
            recall.append({"w": tok, "t": round(t, 2), "scene": sid})
            t += 1 / 2.4
        rscenes.append((sid, first, len(recall) - 1))
        t += 1.2
    for x in recall:
        x["tr"] = int(x["t"] // TR) + 1
    with open(out / "recall.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(RECALL_COLS)
        for i, x in enumerate(recall):
            missing = i in (57, 58)  # two words with no timing, as in real recalls
            w.writerow(
                [
                    x["w"],
                    "" if missing else x["t"],
                    "" if missing else x["tr"],
                    "" if missing else round((x["tr"] - 1) * TR, 2),
                    "" if missing else round(x["t"] + 0.7, 2),
                    "" if missing else int((x["t"] + 0.7) // TR),
                    "" if missing else round(int((x["t"] + 0.7) // TR) * TR, 2),
                ]
            )
    with open(out / "recall-onsets.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["Subject", "Scene", "Onset", "Offset"])
        for sid, a, b in rscenes:
            if sid:
                w.writerow([1, sid, recall[a]["tr"], recall[b]["tr"]])

    # Codex-shaped census, windows and templates.
    def nominate(tr):
        return [
            sid
            for sid, a, b in rscenes
            if sid and recall[a]["tr"] <= tr <= recall[b]["tr"]
        ]

    census = []
    for i, x in enumerate(recall):
        missing = i in (57, 58)
        one = [] if missing else nominate(x["tr"])
        zero = [] if missing else nominate(x["tr"] - 1)
        legacy = [] if missing else nominate(int((x["t"] + 0.7) // TR))
        census.append(
            {
                "word_row": i + 1,
                "window": i // WINDOW_WORDS + 1,
                "screen": x["w"].lower()
                in {
                    "she",
                    "he",
                    "her",
                    "him",
                    "they",
                    "guy",
                    "woman",
                    "girl",
                    "man",
                    "someone",
                    "mara",
                },
                "nomination_scenes": one,
                "zero_based_tr_scenes": zero,
                "legacy_seconds_scenes": legacy,
                "boundary_disagreement": one != zero,
                "timing_missing": missing,
            }
        )
    (out / "word-census.json").write_text(json.dumps(census, indent=1) + "\n")
    nwin = census[-1]["window"]
    with open(out / "review-windows.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(WINDOW_COLS)
        for k in range(1, nwin + 1):
            ws = [c["word_row"] for c in census if c["window"] == k]
            w.writerow([k, ws[0], ws[-1], "", ""])
    with open(out / "decisions.csv", "w", newline="") as f:
        csv.writer(f).writerow(DECISION_COLS)
    if media:
        for p in (1, 2):
            film(
                out / f"film-part{p}.webm",
                [r for r in rows if r["part"] == p],
                clock[p],
            )
    manifest = {
        "schema": "review-workbench-manifest/v0",
        "subject": SUBJECT,
        "synthetic": True,
        "tr": TR,
        "parts": {
            str(p): {
                "trShift": shift[p],
                "seconds": clock[p],
                "rows": [
                    min(r["n"] for r in rows if r["part"] == p),
                    max(r["n"] for r in rows if r["part"] == p),
                ],
                "firstScene": min(s[0] for s in SCENES if s[1] == p),
            }
            for p in (1, 2)
        },
        "inputs": {
            k: {"path": v, "sha256": sha(out / v)}
            for k, v in {
                "annotation": "annotation.tsv",
                "watchOnsets": "watch-onsets.csv",
                "recall": "recall.csv",
                "recallOnsets": "recall-onsets.csv",
                "census": "word-census.json",
                "windows": "review-windows.csv",
                "decisions": "decisions.csv",
                "transcript1": "transcript-part1.json",
                "transcript2": "transcript-part2.json",
            }.items()
        },
        "media": {str(p): f"film-part{p}.webm" for p in (1, 2)},
        "gate": {"mode": "complete-all", "decisionOp": "synthetic"},
        "referentEncoding": None,
        "reviewer": "synthetic-reviewer",
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=1) + "\n")
    return manifest


def film(path: Path, rows, seconds):
    """A test pattern with each row's scene, row number and part clock burned in, a colour band
    per scene, and a tone whose pitch changes per scene, so frames and audio are distinguishable."""
    font = "/System/Library/Fonts/Supplemental/Arial.ttf"
    hues = ["0x3b6ea5", "0xa55b3b", "0x4f8a4b", "0x8a4b85", "0xa5933b", "0x3b9aa5"]
    vf = []
    for r in rows:
        en = f"between(t,{r['s']},{r['e'] - 0.001})"
        vf.append(
            f"drawbox=x=0:y=ih-54:w=iw:h=54:color={hues[r['scene'] % 6]}@0.85:t=fill:"
            f"enable='{en}'"
        )
        label = f"scene {r['scene']}   row {r['n']}"
        vf.append(
            f"drawtext=fontfile={font}:text='{label}':x=16:y=h-40:fontsize=24:"
            f"fontcolor=white:enable='{en}'"
        )
    vf.append(
        f"drawtext=fontfile={font}:text='%{{pts\\:hms}}':x=w-170:y=16:fontsize=22:"
        "fontcolor=white:box=1:boxcolor=black@0.5"
    )
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            f"testsrc2=size=480x270:rate={FPS}:duration={seconds}",
            "-f",
            "lavfi",
            "-i",
            f"sine=frequency=330:sample_rate=22050:duration={seconds}",
            "-vf",
            ",".join(vf),
            "-c:v",
            "libvpx-vp9",
            "-b:v",
            "250k",
            "-g",
            str(FPS),
            "-c:a",
            "libopus",
            "-b:a",
            "32k",
            "-shortest",
            str(path),
        ],
        check=True,
    )


if __name__ == "__main__":
    m = build(Path(sys.argv[1]), media="--no-media" not in sys.argv)
    print(json.dumps({"subject": m["subject"], "inputs": len(m["inputs"])}))
