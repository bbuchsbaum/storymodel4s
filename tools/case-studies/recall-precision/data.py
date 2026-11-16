"""Loaders for the recall-precision case study (Sherlock, development participants only).

Clocks are kept explicit:
- film time: seconds within part a or part b of the film; ``film_t`` adds the part-a length
  to part-b times so the whole film sits on one axis (a display convenience, not a playback
  claim);
- recall time: the Princeton word-onset column (the clock the human scene coding and the
  frozen units use) and the OpenNeuro column (scan-relative; used only for the TR export).
"""

from __future__ import annotations

import csv
import json
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools" / "sherlock_mapper"))
import common  # noqa: E402

DATA = common.DATA
RECALL = DATA / "sherlock" / "recall"
GOLD = DATA / "sherlock" / "Sherlock_Recall_Scene_n50_Onsets.csv"
RULE = (
    ROOT
    / "embed-bench/src/main/resources/storymodel4s/bench/sherlock/scene-coding-rule.json"
)
OUT = DATA / "study" / "recall-precision"
TR = 1.5
PART_B_FIRST_ROW = 483


@dataclass
class FilmRow:
    id: int
    part: str
    start: float
    end: float
    t0: float  # global film seconds
    t1: float
    scene: int
    text: str


def film_rows(with_speech: bool = True) -> list[FilmRow]:
    rows = common.film_rows()
    if with_speech:
        common.attach_speech(rows)
    part_a_len = max(r.end for r in rows if r.id < PART_B_FIRST_ROW)
    out = []
    for r in rows:
        part = "b" if r.id >= PART_B_FIRST_ROW else "a"
        off = part_a_len if part == "b" else 0.0
        bits = [r.desc]
        if r.names:
            bits.append("People: " + r.names + ".")
        if r.location:
            bits.append("Location: " + r.location + ".")
        if with_speech and r.speech:
            bits.append("Dialogue: " + " ".join(r.speech))
        text = " ".join(b for b in bits if b).strip() or r.scene_title
        out.append(
            FilmRow(
                r.id, part, r.start, r.end, r.start + off, r.end + off, r.scene, text
            )
        )
    return out


@dataclass
class Word:
    text: str
    princeton: float | None
    openneuro: float | None


def _f(x: str) -> float | None:
    x = (x or "").strip()
    return float(x) if x else None


def participant_file(name: str) -> Path:
    hits = sorted(RECALL.glob(f"{name.split('_')[0]}_*final.csv"))
    if len(hits) != 1:
        raise SystemExit(f"expected one recall CSV for {name}, found {hits}")
    return hits[0]


def recall_words(name: str) -> list[Word]:
    common.guard(name)
    out = []
    with participant_file(name).open(encoding="utf-8-sig", newline="") as f:
        r = csv.reader(f)
        h = next(r)
        ip = h.index("Princeton Dataspace Word Onset Time (sec)")
        io = h.index("OpenNeuro Word Onset Time (sec)")
        for x in r:
            w = x[0].strip()
            if not w:
                continue
            out.append(Word(w, _f(x[ip]), _f(x[io])))
    return out


def gold_intervals(name: str) -> list[tuple[float, float, int]] | None:
    """Human recall scene coding on the Princeton clock, closed intervals, sorted."""
    num = int(name[2:4])
    rule = json.loads(RULE.read_text())
    subj = next(
        p["goldSubject"] for p in rule["participants"] if p["participant"] == num
    )
    if subj is None:
        return None
    iv = []
    with GOLD.open(encoding="utf-8-sig", newline="") as f:
        for x in csv.DictReader(f):
            if int(x["Subject"]) == subj:
                iv.append(
                    (float(x["Onset"]) * TR, float(x["Offset"]) * TR, int(x["Scene"]))
                )
    return sorted(iv)


def gold_scene_at(iv, t):
    if iv is None or t is None:
        return None
    for lo, hi, s in iv:
        if lo <= t <= hi:
            return s
    return None


def development() -> list[str]:
    return common.development()


def v3_units(name: str):
    """LLM mapper v3 records joined to unit onsets (Princeton clock)."""
    p = common.OUT / "v3-opus" / f"{name}.json"
    if not p.exists():
        return None
    rec = {u["id"]: u for u in json.loads(p.read_text())["units"]}
    out = []
    for u in common.units(name):
        out.append((u, rec.get(u.id)))
    return out
