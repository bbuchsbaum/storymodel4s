"""Sherlock adapter for the case-study harness (development participants only).

The harness talks to a dataset only through the names listed in data.ADAPTER_API; a new dataset
is a new module in this package that provides them (owner rule: uniform outputs across datasets).

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

ROOT = Path(__file__).resolve().parents[4]
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


# ---------------------------------------------------------------- adapter API additions

NAME = "sherlock"


def units(name: str):
    """Frozen recall units (id, text, onset on the primary recall clock)."""
    return common.units(name)


def film_outline():
    """Prompt-ready film annotation text and its rows."""
    return common.film_outline()


def film_speech_rows():
    """Annotation rows with attached dialogue (row.id, row.speech list)."""
    return common.attach_speech(common.film_rows())


def cast_line() -> str:
    from map_v3 import cast

    return cast()


def analyst_dir() -> Path:
    """Where the whole-recall analyst stream (v3 mapper) keeps one JSON per participant."""
    return common.OUT


def guard(name: str):
    common.guard(name)


# ---------------------------------------------------------------- case study 2 (CS2_API)
#
# The (work, occurrence, part, seconds) address: one work, one occurrence spanning two parts
# (the two scan runs a and b), seconds on each part's own axis. No global timeline: `film_t`
# above is a display convenience that case study 2 never uses.

WORD_CLOCK = "princeton-word-onset-seconds"
WORK = "sherlock"


_COMP = None


def composition():
    """The case-study-2 source model (cached)."""
    global _COMP
    if _COMP is not None:
        return _COMP
    from cs2.source import Composition, Occurrence, Part, Segment, SourceUnit, Work

    rows = common.film_rows()
    units, ends = [], {"a": 0.0, "b": 0.0}
    for r in rows:
        part = "b" if r.id >= PART_B_FIRST_ROW else "a"
        ends[part] = max(ends[part], r.end)
        units.append(SourceUnit(f"r{r.id}", "sherlock@ab", part, r.start, r.end, r.scene))
    parts = tuple(Part(p, 0.0, ends[p]) for p in "ab")
    occ = Occurrence("sherlock@ab", WORK, tuple(Segment(p, 0.0, ends[p]) for p in "ab"), 1)
    work = Work(
        WORK,
        "Sherlock",
        "Sherlock, 'A Study in Pink' (BBC, 2010)",
        excerpt=None,
        speech=True,
        note="the stimulus is reported as the episode's first ~48 min; not verified locally",
    )
    _COMP = Composition(
        "sherlock",
        (work,),
        parts,
        (occ,),
        tuple(units),
        dict(
            clock="run-local seconds of each part (annotation rows)",
            occurrence_spans="one occurrence spans parts a and b; a constituent spanning both "
            "keeps per-part support and has no single-axis extent",
        ),
    )
    return _COMP


def unit_texts() -> dict:
    """Unit id -> annotation text with attached dialogue (in memory only, never written)."""
    return {f"r{r.id}": r.text for r in film_rows(with_speech=True)}


def sessions(name: str) -> list:
    guard(name)
    return ["recall"]


def session_words(name: str, session: str = "recall") -> list:
    """Recall word cells on the Princeton clock (the clock of the human scene coding)."""
    from cs2.source import RecallWord

    if session != "recall":
        raise ValueError(f"Sherlock has one recall session, not {session!r}")
    return [RecallWord(i, w.text, w.princeton) for i, w in enumerate(recall_words(name))]


def gold_rows(name: str, session: str = "recall") -> list:
    """Human recall scene coding as gold rows: each coded interval names one scene (closed
    interval on the Princeton clock, as case study 1 used it)."""
    from cs2.source import GoldRow

    guard(name)
    iv = gold_intervals(name)
    if iv is None:
        return []
    return [
        GoldRow(name, "recall", i, lo, hi, True, "specific", WORK, frozenset({s}), None)
        for i, (lo, hi, s) in enumerate(iv)
    ]


# ---------------------------------------------------------------- producer facade
#
# The two-level producer (cs2/ff_producer.py) runs here through the same facade. Units are the
# CS1 frozen library units (D.units), bound to word cells by the landed library parity words
# ledger (parity/library-v4/<NN>_desc-libraryparity_words.tsv: word index -> unit index).

PRODUCER_SEGMENTER = "CS1 frozen library units (stage record) bound by parity/library-v4 words"
PRODUCER_UNITS_RECEIPT = dict(source="data/study/recall-precision/parity/library-v4",
                              rule="word_index -> unit_index from the library words ledger")
PRODUCER_PHRASES = (
    (1, "a sequence of short films they watched", "a film they watched, shown in two parts"),
    (2, "recall of short films within the film they are about", "recall of a film within that film"),
)
WORD_CLOCK_RECEIPT = dict(
    clock=WORD_CLOCK,
    wordOnsetsAreMeasured=None,
    note="Princeton dataspace word onsets; how they were measured is not recorded here",
)


def producer_composition():
    return composition()


def producer_unit_texts():
    return unit_texts()


def producer_units(name, session, words):
    """[(first word, last word)] per unit, from the library words ledger (development only)."""
    guard(name)
    short = name.split("_")[0]
    path = OUT / "parity" / "library-v4" / f"{short}_desc-libraryparity_words.tsv"
    rows = list(csv.DictReader(open(path), delimiter="\t"))
    if len(rows) != len(words):
        raise ValueError(f"{name}: {len(rows)} ledger words vs {len(words)} recall words")
    spans = {}
    for i, r in enumerate(rows):
        u = int(r["unit_index"])
        if u < 0:
            continue
        a, b = spans.get(u, (i, i))
        spans[u] = (min(a, i), max(b, i))
    out = [spans[u] for u in sorted(spans)]
    for (_, l1), (f2, _) in zip(out, out[1:]):
        if f2 <= l1:
            raise ValueError(f"{name}: library units overlap or are out of order")
    return out


def producer_scene_extras():
    """Scene title, part, time span (part-local seconds) and visual-summary caption per scene,
    as the CS1 prompt showed them (rendering 'cs1-extras')."""
    rows = common.film_rows()
    caps = common.scene_captions()
    out = {}
    for r in rows:
        x = out.setdefault(r.scene, dict(title=r.scene_title, part=r.part, start=r.start,
                                         end=r.end, caption=caps.get(r.scene)))
        x["end"] = max(x["end"], r.end)
    return out


def producer_cast_line():
    """The CS1 prompt's cast line (names by frequency in the annotation)."""
    return cast_line()
