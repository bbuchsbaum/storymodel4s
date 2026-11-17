"""FilmFestival adapter for case study 2 (CS2_API; development participants only).

Source model (design revision 2):
- eleven works: ten films and the "Let's All Go to the Lobby" cartoon, which is one work with two
  occurrences (one at the start of each run);
- two parts: the movie runs run-01 and run-02, each on its own annotation clock (run-relative
  seconds from the cartoon's first frame); no global timeline;
- twelve occurrences in presentation order; works are labelled with the cover names
  participants saw (BIDS movie_title); real titles are metadata from FILMS.md;
- two works are excerpts of longer features (catch_me_if_you_can, the_prisoner);
- source units are one annotator's fine segments (JL by default), with JL's coarse scene numbers
  (global 1-216). KM and RC segmentations load with their own scene numbering (RC) or none (KM).

Gold (derived/gold-scenematched.tsv with the raw workbook codes): one row per author utterance
with its work, scene set, class and non-film label. Recall text is never returned.

FROZEN participants (data/study/filmfestival/cs2-partition.json) are refused by every reader
unless `allow_frozen=True` is passed for the one registered final run.
"""

from __future__ import annotations

import csv
import json
import re
import sys
from functools import lru_cache
from pathlib import Path

_HERE = Path(__file__).resolve().parents[1]
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

from cs2.source import (  # noqa: E402
    Composition,
    GoldRow,
    Occurrence,
    Part,
    RecallWord,
    Segment,
    SourceError,
    SourceUnit,
    Work,
)

NAME = "filmfestival"
ROOT = Path(__file__).resolve().parents[4]
DATA = Path("/Users/bbuchsbaum/code/scala/storymodel4s/data")
FF = DATA / "filmfestival"
PARTITION = DATA / "study" / "filmfestival" / "cs2-partition.json"
GOLD = FF / "derived" / "gold-scenematched.tsv"
CODES = (
    DATA / "study" / "filmfestival" / "llm-transfer-opus55-20261109" / "scene-codes.tsv"
)
WORD_CLOCK = "filmfestival-recall-word-onset-seconds"
# The word clock's provenance: onsets are interpolated within the authors' utterances.
WORD_CLOCK_RECEIPT = dict(
    clock=WORD_CLOCK,
    wordOnsetsAreMeasured=False,
    withinUtteranceTiming="linear-interpolation",
    receipt="data/filmfestival/derived/recall.receipt.json",
)
RUNS = ("run-01", "run-02")
EXCERPTS = ("catch_me_if_you_can", "the_prisoner")
CARTOON = "cartoon_introduction"
TITLE_MARK = "fades in on a black screen"
NONFILM = {-1: "memory_search", -2: "gave_up", 0: "off_task"}
CARTOON_CODE = 11


class FrozenParticipant(PermissionError):
    pass


def _partition():
    try:
        return json.loads(PARTITION.read_text())
    except OSError as e:  # fail closed: without the record nothing is readable
        raise FrozenParticipant(f"partition record unavailable: {e}") from e


def guard(participant: str, allow_frozen: bool = False):
    """Refuse a frozen participant (and anyone, if the partition record is missing)."""
    part = _partition()
    if participant in part["frozen"] and not allow_frozen:
        raise FrozenParticipant(
            f"{participant} is frozen for case study 2; only the registered final run may read it"
        )
    if participant not in part["frozen"] and participant not in part["development"]:
        raise FrozenParticipant(f"{participant} is not in the case-study-2 partition")


def development() -> list:
    return sorted(_partition()["development"])


def _read_tsv(path):
    # Quote characters occur inside text cells: the default dialect merges rows on them.
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE))


def _read_csv(path):
    with open(path, newline="", encoding="utf-8-sig") as f:
        return list(csv.reader(f))


# ---------------------------------------------------------------- source model


def _films_md():
    """BIDS label -> (title, speech) from FILMS.md's run tables."""
    out = {}
    text = (FF / "FILMS.md").read_text()
    for line in text.splitlines():
        m = re.match(r"\|\s*`([a-z_]+)`\s*\|\s*(.*?)\s*\|.*", line)
        if not m:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        speech = {"yes": True, "no": False}.get(cells[5].lower())
        out.setdefault(m.group(1), (cells[1], speech))
    for label in EXCERPTS:
        if f"`{label}`" not in text.split("excerpts of longer works", 1)[-1][:200]:
            raise SourceError(f"FILMS.md no longer lists {label} as an excerpt")
    return out


def _events(run):
    return [
        (float(r["onset"]), float(r["duration"]), r["movie_title"])
        for r in _read_tsv(FF / f"task-movie_{run}_events.tsv")
    ]


def _norm(s):
    return re.sub(r"[^a-z]", "", s.lower())


def _matches(annotation_film, label):
    a = _norm(re.sub(r"^\d+\.\s*", "", annotation_film).rstrip("0123456789 "))
    b = _norm(label)
    return a.startswith(b) or b.startswith(a)


@lru_cache(maxsize=None)
def composition(coder: str = "JL") -> Composition:
    rows = _read_tsv(FF / "derived" / f"annotation-{coder}.tsv")
    films = _films_md()
    works, occurrences, units, parts = {}, [], [], []
    position = 0
    rules = dict(
        clock="annotation clock per run (run-relative seconds), not aligned to events.tsv",
        missing_end=(
            "a segment without an end ends at the next segment's start in the run when that is "
            "later, else at its coarse scene's end (next scene start), else at the run's last "
            "events.tsv offset, uncorrected for the annotation/events clock offset"
        ),
        scene=f"{coder} coarse scene_number"
        + (" (global 1-216)" if coder == "JL" else ""),
        occurrence_spans="an occurrence lies on one part here; no constituent spans parts",
    )
    for run in RUNS:
        rr = [r for r in rows if r["part_id"] == run]
        ev = _events(run)
        order = []
        for r in rr:
            if r["film"] not in order:
                order.append(r["film"])
        matched = []
        for _, _, label in ev:
            hits = [f for f in order if _matches(f, label)]
            if len(hits) > 1:
                raise SourceError(f"{coder} {run}: {label!r} matches {hits}")
            matched.append(hits[0] if hits else None)
        if sorted(f for f in matched if f) != sorted(order):
            raise SourceError(f"{coder} {run}: annotation films do not match events.tsv")
        run_end_events = ev[-1][0] + ev[-1][1]
        starts = [float(r["start_s"]) for r in rr]
        coarse = sorted({float(r["coarse_start_s"]) for r in rr if r["coarse_start_s"]})
        spans = {}
        for i, r in enumerate(rr):
            s = float(r["start_s"])
            if r["end_s"].strip():
                e = float(r["end_s"])
            else:
                later = [x for x in starts[i + 1 :] if x > s]
                nxt_scene = [c for c in coarse if c > s]
                e = (
                    min(later)
                    if later
                    else (nxt_scene[0] if nxt_scene else run_end_events)
                )
            spans[i] = (s, e)
        part_end = max(e for _, e in spans.values())
        parts.append(Part(run, 0.0, part_end))
        for film, (_, _, label) in zip(matched, ev):
            position += 1  # presentation order counts every event, annotated or not
            if film is None:
                rules.setdefault("not_annotated", []).append(f"{label}@{run}")
                continue
            if label not in works:
                title, speech = films.get(label, ("", None))
                cover = re.sub(r"^\d+\.\s*", "", film).rstrip("0123456789 ").strip()
                works[label] = Work(
                    label,
                    cover,
                    title,
                    excerpt=label in EXCERPTS,
                    speech=speech,
                    note="excerpt of a longer work" if label in EXCERPTS else "",
                )
            oid = f"{label}@{run}"
            idx = [i for i, r in enumerate(rr) if r["film"] == film]
            lo = min(spans[i][0] for i in idx)
            hi = max(spans[i][1] for i in idx)
            occurrences.append(
                Occurrence(oid, label, (Segment(run, lo, hi),), position)
            )
            for i in idx:
                r = rr[i]
                sc = r["scene_number"].strip()
                units.append(
                    SourceUnit(
                        f"{coder}:{r['segment']}",
                        oid,
                        run,
                        spans[i][0],
                        spans[i][1],
                        int(sc) if sc else None,
                        title_card=TITLE_MARK in r["description"],
                    )
                )
    return Composition(
        NAME,
        tuple(works.values()),
        tuple(parts),
        tuple(occurrences),
        tuple(units),
        rules,
    )


def unit_texts(coder: str = "JL") -> dict:
    """Unit id -> annotation description (film-side text; held in memory, never written)."""
    return {
        f"{coder}:{r['segment']}": r["description"]
        for r in _read_tsv(FF / "derived" / f"annotation-{coder}.tsv")
    }


# ---------------------------------------------------------------- recall


def sessions(participant: str, allow_frozen: bool = False) -> list:
    guard(participant, allow_frozen)
    return sorted(
        p.stem.split("_", 1)[1]
        for p in (FF / "derived" / "recall").glob(f"{participant}_run-0*.csv")
    )


def session_words(participant: str, session: str, allow_frozen: bool = False) -> list:
    """Recall word cells in file order (empty cells skipped), on the recall word clock."""
    guard(participant, allow_frozen)
    rows = _read_csv(FF / "derived" / "recall" / f"{participant}_{session}.csv")
    head = rows[0]
    iw, it = head.index("Words"), head.index("Onset Time (sec)")
    out = []
    for x in rows[1:]:
        w = x[iw].strip() if len(x) > iw else ""
        if not w:
            continue
        t = x[it].strip() if len(x) > it else ""
        out.append(RecallWord(len(out), w, float(t) if t else None))
    return out


# ---------------------------------------------------------------- gold


def _work_codes(comp) -> dict:
    """Gold movie code -> work id: films 1-10 in presentation order, 11 the cartoon."""
    codes, n = {}, 0
    for o in sorted(comp.occurrences, key=lambda o: o.position):
        if o.work == CARTOON or o.work in codes.values():
            continue
        n += 1
        codes[n] = o.work
    codes[CARTOON_CODE] = CARTOON
    if n != 10:
        raise SourceError(f"expected ten films, found {n}")
    return codes


def _participant_rows(path, participant: str) -> list:
    """Rows of one participant from a TSV whose first column is `participant`.

    Every other line (frozen participants included) is skipped on its raw first field and is
    never parsed into a record. Quoting is off (text cells contain quote characters)."""
    with open(path, newline="", encoding="utf-8") as f:
        header = next(csv.reader([f.readline()], delimiter="\t", quoting=csv.QUOTE_NONE))
        if header[0] != "participant":
            raise SourceError(f"{path.name}: the first column is not 'participant'")
        keep = [line for line in f if line.split("\t", 1)[0] == participant]
    return list(csv.DictReader(keep, fieldnames=header, delimiter="\t", quoting=csv.QUOTE_NONE))


@lru_cache(maxsize=None)
def _gold_table(participant: str):
    raw = {}
    for r in _participant_rows(CODES, participant):
        raw[(r["run"], int(r["gold_row"]))] = (int(r["movie"]), r["scenes_raw"].strip())
    out = []
    for r in _participant_rows(GOLD, participant):
        r.pop("text", None)  # recall text is dropped at once
        out.append(r)
    return out, raw


_LEDGER: dict = {}


def gold_ledger(participants) -> dict:
    """Counts the gold reader drops: positive scene tokens (derived or raw code) on non-film rows."""
    tot = dict(nonfilm_rows=0, nonfilm_rows_with_scene_tokens=0, dropped_scene_tokens=0)
    for p in participants:
        gold_rows(p)
        for k, v in _LEDGER[p].items():
            tot[k] += v
    return tot


EVENTS = FF / "recall_events"
GOLD_END_SHORTFALL_S = 1.0  # measured: every development gold row ends exactly 1.0 s early
GOLD_START_OFFSET_MAX_S = 0.05


def utterance_spans(events) -> list:
    """Each utterance's span on the recall-word clock, by the word clock's own rule
    (recall.receipt.json: '[onset, next onset), shortened by duration when duration is shorter')."""
    out = []
    for i, (onset, dur) in enumerate(events):
        nxt = events[i + 1][0] if i + 1 < len(events) else float("inf")
        hi = nxt if dur is None else min(nxt, onset + dur)
        out.append((onset, hi))
    return out


def align_gold(gold, events):
    """Declared rule: a gold row's bin is its source utterance's span on the word clock.

    `gold` is [(start_s, end_s)] in row order and `events` [(onset, duration or None)] from the
    session's recall events.tsv. The rule is refused unless the rows and utterances correspond
    one to one: start_s - onset is one constant c per session (within 1 ms, |c| <= 0.05 s), and
    end_s = start_s + duration - 1.0 s wherever a duration is given. Returns (spans, receipt)."""
    if len(gold) != len(events):
        raise SourceError(f"{len(gold)} gold rows vs {len(events)} utterances")
    offs = [g[0] - e[0] for g, e in zip(gold, events)]
    c = sorted(offs)[len(offs) // 2]
    if abs(c) > GOLD_START_OFFSET_MAX_S or any(abs(o - c) > 1e-3 for o in offs):
        raise SourceError("gold starts are not one constant offset from the utterance onsets")
    checked = 0
    for (gs, ge), (_, dur) in zip(gold, events):
        if dur is None:
            continue
        checked += 1
        if abs((ge - gs) - (dur - GOLD_END_SHORTFALL_S)) > 1e-6:
            raise SourceError("a gold end is not its utterance end minus 1.0 s")
    return utterance_spans(events), dict(
        rows=len(gold), start_offset_s=round(c, 6), end_shortfall_s=GOLD_END_SHORTFALL_S,
        ends_checked=checked, rule="bin = source utterance span on the recall-word clock")


_CLOCK: dict = {}


def _recall_events(participant, session):
    rows = _read_tsv(EVENTS / f"{participant}_task-recall_{session}_events.tsv")
    return [(float(r["onset"]), None if r["duration"] in ("", "n/a") else float(r["duration"]))
            for r in rows]


def gold_clock_receipt(participants) -> dict:
    """Per development session: the measured gold/utterance offset and the checks passed."""
    out = {}
    for p in participants:
        gold_rows(p)
        out.update({f"{p}:{s}": v for (q, s), v in _CLOCK.items() if q == p})
    return out


def gold_rows(participant: str, session: str | None = None, allow_frozen: bool = False):
    """Author-utterance rows (no text) for one participant, in session and row order."""
    guard(participant, allow_frozen)
    comp = composition("JL")
    codes = _work_codes(comp)
    table, raw = _gold_table(participant)
    out = []
    led = dict(nonfilm_rows=0, nonfilm_rows_with_scene_tokens=0, dropped_scene_tokens=0)
    for r in table:
        if session is not None and r["run"] != session:
            continue
        movie = int(r["movie"])
        scenes = frozenset(int(s) for s in r["scenes"].split())
        key = (r["run"], int(r["gold_row"]))
        if key not in raw or raw[key][0] != movie:
            raise SourceError(
                f"raw codes do not match the gold file at {participant} {key}"
            )
        code = raw[key][1]
        if movie in NONFILM:
            cls, work, nf = "nonfilm", None, NONFILM[movie]
            led["nonfilm_rows"] += 1
            pos = set(scenes) | {
                int(t) for t in code.replace(",", " ").split() if t.lstrip("-").isdigit() and int(t) > 0
            }
            if pos:  # declared: a non-film row carries no scene; positive tokens are counted
                led["nonfilm_rows_with_scene_tokens"] += 1
                led["dropped_scene_tokens"] += len(pos)
            scenes = frozenset()
        elif movie in codes:
            work, nf = codes[movie], None
            if scenes:
                cls = "specific"
            elif code == "-1":
                cls = "film_search"
            elif code == str(movie * 1000):
                cls = "film_only"
            else:
                raise SourceError(
                    f"film {movie} without a scene has the unknown code {code!r}"
                )
        else:
            raise SourceError(f"unknown movie code {movie}")
        out.append(
            GoldRow(
                participant,
                r["run"],
                int(r["gold_row"]),
                float(r["start_s"]),
                float(r["end_s"]),
                False,
                cls,
                work,
                scenes,
                nf,
            )
        )
    out.sort(key=lambda g: (g.session, g.row))
    aligned = []
    for sess in sorted({g.session for g in out}):
        rows = [g for g in out if g.session == sess]
        spans, receipt = align_gold([(g.lo, g.hi) for g in rows],
                                    _recall_events(participant, sess))
        _CLOCK[(participant, sess)] = receipt
        aligned += [GoldRow(g.participant, g.session, g.row, lo, hi, False, g.cls, g.work,
                            g.scenes, g.nonfilm) for g, (lo, hi) in zip(rows, spans)]
    out = aligned
    if session is None:
        _LEDGER[participant] = led
    return out


def check_gold_numbering(participants=None) -> dict:
    """Gold scene ids against JL: every id exists, belongs to the coded work, and is not a
    title-only scene; counts of run-1 and run-2 scene ids by recall session."""
    comp = composition("JL")
    titles = comp.title_only_scenes()
    rep = dict(rows=0, scene_refs=0, unknown=0, wrong_work=0, title_only=0, by=dict())
    for p in participants or development():
        try:
            rows = gold_rows(p)
        except FrozenParticipant:
            raise
        for g in rows:
            rep["rows"] += 1
            for s in g.scenes:
                rep["scene_refs"] += 1
                if s not in comp.scene_occurrence:
                    rep["unknown"] += 1
                    continue
                if comp.work_of_scene(s) != g.work:
                    rep["wrong_work"] += 1
                if s in titles:
                    rep["title_only"] += 1
                run = comp.unit[next(u.id for u in comp.units if u.scene == s)].part
                k = f"{g.session}->{run}"
                rep["by"][k] = rep["by"].get(k, 0) + 1
    rep["jl_run2_scene_range"] = [
        min(
            s
            for s in comp.scenes()
            if comp.occurrence[comp.occurrence_of_scene(s)].segments[0].part == "run-02"
        ),
        max(comp.scenes()),
    ]
    return rep


# ---------------------------------------------------------------- producer facade


def producer_composition():
    """The source model the two-level producer localizes against (JL segments)."""
    return composition("JL")


def producer_unit_texts():
    return unit_texts("JL")
