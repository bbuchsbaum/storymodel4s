"""Shared loaders and scoring for the Sherlock mapper research harness.

Development partition only by default. Data stays under the ignored data root; nothing here
writes participant text into the repository.
"""

import csv
import hashlib
import json
import os
import re
import statistics
from dataclasses import dataclass, field
from pathlib import Path

DATA = Path(os.environ.get("SM_DATA", "/Users/bbuchsbaum/code/scala/storymodel4s/data"))
RUN = DATA / "study/recall-to-video/development-20260923"
OUT = DATA / "study/recall-to-video/llm-mapper"
SEGMENTS = DATA / "sherlock/Sherlock_Segments_1000_NN_2017.tsv"
ASR = DATA / "transcription/unified-v1/full/whisper_mlx/sherlock"
CAPTIONS = DATA / "study/recall-to-video/scene-captions.json"
PART2_FIRST_TR = 947


def partition():
    return json.loads((DATA / "study/recall-to-video/partition.json").read_text())


def development():
    return sorted(partition()["development"])


def guard(name, allow_untouched=False):
    if name in partition()["untouchedTest"] and not allow_untouched:
        raise SystemExit(
            f"{name} is untouched; pass --allow-untouched only for the registered read"
        )


@dataclass
class Row:
    id: int
    part: str  # 'a' or 'b'
    start: float
    end: float
    scene: int
    scene_title: str
    desc: str
    names: str
    speaking: str
    location: str
    space: str
    speech: list = field(default_factory=list)


def fmt(t):
    t = int(round(t))
    return f"{t // 60}:{t % 60:02d}"


def film_rows():
    rows, scene, title = [], None, ""
    with open(SEGMENTS, encoding="utf-8-sig", newline="") as f:
        r = csv.reader(f, delimiter="\t")
        h = [x.strip() for x in next(r)]
        ix = {k: h.index(k) for k in h}
        for x in r:
            x = x + [""] * (len(h) - len(x))
            m = re.match(r"\s*(\d+)\.\s*(.*)", x[ix["Scene Segments"]])
            if m:
                scene, title = int(m.group(1)), m.group(2).strip()
            tr = x[ix["Start Time (TRs, 1.5s)"]].strip()
            part = "b" if scene >= 28 else "a"
            rows.append(
                Row(
                    int(x[0]),
                    part,
                    float(x[1]),
                    float(x[2]),
                    scene,
                    title,
                    x[ix["Scene Details - A Level"]].strip(),
                    x[ix["Name - All"]].strip(),
                    x[ix["Name - Speaking"]].strip(),
                    x[ix["Location"]].strip(),
                    x[ix["Space-In/Outdoor"]].strip(),
                )
            )
    return rows


def asr_segments(part):
    name = "Sherlock_part1_imovie" if part == "a" else "Sherlock_part2_imovie"
    out = []
    with open(ASR / name / "segments.tsv", newline="") as f:
        for x in csv.DictReader(f, delimiter="\t"):
            if x["suspect"] == "True":
                continue
            out.append(
                (float(x["start_seconds"]), float(x["end_seconds"]), x["text"].strip())
            )
    return out


def attach_speech(rows):
    for part in "ab":
        prow = [r for r in rows if r.part == part]
        for s, e, text in asr_segments(part):
            mid = (s + e) / 2
            best = min(
                prow,
                key=lambda r: 0
                if r.start <= mid <= r.end
                else min(abs(mid - r.start), abs(mid - r.end)),
            )
            best.speech.append(text)
    return rows


def scene_captions():
    d = json.loads(CAPTIONS.read_text())
    return {int(k): v for k, v in d["captions"].items()}


def film_outline(with_speech=True, with_captions=True):
    rows = film_rows()
    if with_speech:
        attach_speech(rows)
    caps = scene_captions() if with_captions else {}
    lines, cur = [], None
    for r in rows:
        if r.scene != cur:
            cur = r.scene
            srows = [x for x in rows if x.scene == cur]
            lines.append(
                f"\n=== SCENE {cur}: {r.scene_title} (part {r.part}, "
                f"{fmt(srows[0].start)}-{fmt(srows[-1].end)})"
            )
            if cur in caps:
                lines.append(f"[visual summary] {caps[cur]}")
        meta = "; ".join(x for x in (r.location, r.names and "people: " + r.names) if x)
        lines.append(
            f"r{r.id} {fmt(r.start)} {r.desc}" + (f"  ({meta})" if meta else "")
        )
        if r.speech:
            lines.append("    dialogue: " + " ".join(r.speech))
    return "\n".join(lines).strip(), rows


@dataclass
class Unit:
    index: int
    id: str
    text: str
    onset: float


def units(name):
    if not (RUN / "A" / f"recall-map-{name}.tsv").exists():
        return units_frozen(name)
    rep = RUN / "A" / f"recall-map-{name}.tsv"
    st = json.loads((RUN / "A" / f"recall-map-{name}.tsv.stages.json").read_text())[
        "units"
    ]
    ids = {u["unit"]: u["unitId"] for u in st}
    out = []
    with open(rep, newline="") as f:
        for x in csv.DictReader(f, delimiter="\t"):
            i = int(x["unit"])
            out.append(Unit(i, ids[i], x["recallText"], float(x["recallOnsetSeconds"])))
    return out


def baseline_scenes(name):
    st = json.loads((RUN / "A" / f"recall-map-{name}.tsv.stages.json").read_text())[
        "units"
    ]
    return {u["unitId"]: u["assignedScene"] for u in st}


def gold():
    s = json.loads((RUN / "support.json").read_text())
    return {
        r["id"]: r["goldScene"]
        for r in s["rows"]
        if r["exclusion"] is None and r["goldScene"] is not None
    }


def score(pred_by_participant, label="pred"):
    """pred: {participant: {unitId: scene or None}}; returns participant-average and pooled."""
    g = gold()
    per = {}
    for name, pred in pred_by_participant.items():
        n = exact = near = none = 0
        for uid, sc in pred.items():
            if uid not in g:
                continue
            n += 1
            if sc is None:
                none += 1
                continue
            exact += sc == g[uid]
            near += abs(sc - g[uid]) <= 1
        if n:
            per[name] = dict(n=n, exact=exact / n, within1=near / n, abstain=none / n)
    if not per:
        return dict(label=label, participants=0, units=0, participantAverage={}, pooled={}, per={})
    pa = {
        k: round(100 * statistics.mean(p[k] for p in per.values()), 2)
        for k in ("exact", "within1", "abstain")
    }
    tot = sum(p["n"] for p in per.values())
    pooled = {
        k: round(100 * sum(p[k] * p["n"] for p in per.values()) / tot, 2)
        for k in ("exact", "within1")
    }
    return dict(
        label=label,
        participants=len(per),
        units=tot,
        participantAverage=pa,
        pooled=pooled,
        per={
            k: {
                kk: round(vv, 4) if isinstance(vv, float) else vv
                for kk, vv in v.items()
            }
            for k, v in per.items()
        },
    )


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


def span_gold():
    """Secondary metric support: every gold scene coded during a unit's own span
    [onset, last-word onset], development participants only. Primary scoring stays onset-based."""
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'recall-study'))
    import gold_scene as gs

    gold = gs.load_gold(DATA / 'sherlock/Sherlock_Recall_Scene_n50_Onsets.csv')
    out = {}
    for name in development():
        alias = gs.ALIASES[gs.participant_number(name)]
        if alias['eligibility'] != 'eligible':
            continue
        iv = gold[alias['goldSubject']]
        with open(RUN / 'A' / f'recall-map-{name}.tsv', newline='') as f:
            rows = list(csv.DictReader(f, delimiter='\t'))
        ids = {u.index: u.id for u in units(name)}
        for x in rows:
            lo, hi = float(x['recallOnsetSeconds']), float(x['recallLastWordOnsetSeconds'])
            out[ids[int(x['unit'])]] = {sc for a, b, sc in iv if a <= hi and b >= lo}
    return out


def score_span(pred_by_participant):
    g, sg = gold(), span_gold()
    vals = []
    for name, pred in pred_by_participant.items():
        ks = [k for k in pred if k in g]
        if ks:
            vals.append(sum(pred[k] in sg.get(k, {g[k]}) for k in ks) / len(ks))
    return round(100 * statistics.mean(vals), 2) if vals else None


FROZEN = DATA / "study/recall-to-video/all17-monofill"
MANIFEST = DATA / "study/recall-to-video/scorer-20260919/unit-manifest.json"


def units_frozen(name):
    """Units for participants outside the development run: the frozen all17-monofill report
    (same producer and preset) joined to stable ids by ordinal from the unit manifest."""
    m = {p["participant"]: p for p in json.loads(MANIFEST.read_text())["participants"]}[name]
    out = []
    with open(FROZEN / f"recall-map-{name}.tsv", newline="") as f:
        for x in csv.DictReader(f, delimiter="	"):
            u = m["units"][int(x["unit"])]
            if float(u["onsetSeconds"]) != float(x["recallOnsetSeconds"]):
                raise SystemExit(f"{name}: unit {x['unit']} onset mismatch")
            out.append(Unit(int(x["unit"]), u["id"], x["recallText"], float(x["recallOnsetSeconds"])))
    return out
