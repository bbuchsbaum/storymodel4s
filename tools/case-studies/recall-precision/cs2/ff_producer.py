"""Two-level FilmFestival producer for case study 2 (design: "Producer on FilmFestival").

PILOT tooling. It runs one development participant-session per invocation and refuses the
frozen participants through the adapter.

Units. The library segmenter (recall/segmenter.scala) needs sbt, which this harness does not
run, so units come from a documented INTERIM segmentation (`interim_units`). It approximates
RecallSegmenter.splitClauses on the unpunctuated word clock:
- a "sentence" is a run of words with inter-onset gaps below PAUSE_S (the word clock carries no
  punctuation);
- inside it, the library's connective splitters cut before "and then", "then", "but", "so" (not
  "so much/many/far/long"), "because", "while", "before/after that|this", "prior to that" and
  "and <pronoun>";
- a cut is accepted only if both sides keep at least MIN_TOKENS words (the library's
  MinClauseTokens);
- unlike the library, a consumed "and" stays with the next unit, so every word is in one unit.

Pass 1, the whole-recall film frame. One call sees the session's units in order and the films'
annotation under cover names, listed in a seeded random order with no times or ids. Per unit it
returns a kind (content, work, memory_search, gave_up, off_task, fragment), a frame (films with
self-reported weights), and any stated position ("the last movie").

Pass 2, content-local. Content units are pooled across films, shuffled with a seed and batched.
Each item shows a window of 2 preceding units, the target and its frame film. Each call shows
only its items' films, in a per-call permuted order, with per-call row numbers, opaque scene
labels and film-relative times. Item ids are salted hashes. References follow the prompt-v3
style (words, extent, occurrence, mode, alts over rows).

Declared conversion into the account (cs2.account):
- content: pass-2 references, member weight = top frame weight x alt weight; non-episodic talk
  becomes a typed state (metamemory -> memory_search, commentary, fragment);
- work: a WorkReference to the top frame film. With a stated position it is a PositionReference
  resolved by presentation order of the ten films; unresolvable positions keep no work;
- memory_search with a frame: a WorkReference (the gold codes memory search within a film as
  film-level). Without a frame it is the non-film state memory_search;
- gave_up, off_task, fragment: typed states;
- shapes: occurrence joint (two or more intervals) -> JOINT; repeated or generic -> SINGLE with one
  constituent per interval; mode gist with extent stretch -> BROAD; otherwise SINGLE;
- an anchor must occur exactly once in its unit's tokens; refused references are counted in the
  ledger, and their words stay uncovered.
"""

from __future__ import annotations

import hashlib
import json
import random
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / "tools" / "sherlock_mapper"))

from llm import CACHE, call, parse_json  # noqa: E402

from common import sha  # noqa: E402

from cs2.account import (  # noqa: E402
    Account,
    AccountError,
    Hypothesis,
    Member,
    PositionReference,
    Reference,
    RefSample,
    Shape,
    StateSample,
    Uncovered,
    WordCell,
    WorkReference,
)

PAUSE_S = 1.0
MIN_TOKENS = 3
WINDOW = 2
BATCH = 40
MODEL = "opus"
PRONOUNS = set("he she they it i we there the a an his her their my our you".split())
NORM = re.compile(r"[^a-z0-9']+")


def toks(s):
    return [t for t in NORM.sub(" ", s.lower()).split() if t]


# ---------------------------------------------------------------- interim segmentation


def _cut_before(words, i):
    """Whether a connective starts at word i (library splitters, keep-with-next form)."""
    w = [toks(x.text)[0] if toks(x.text) else "" for x in words[i : i + 3]]
    w += [""] * (3 - len(w))
    if w[0] in ("then", "but", "because", "while"):
        return True
    if w[0] == "so":
        return w[1] not in ("much", "many", "far", "long")
    if w[0] == "and" and (w[1] == "then" or w[1] in PRONOUNS):
        return True
    if w[0] in ("before", "after") and w[1] in ("that", "this"):
        return True
    return w[0] == "prior" and w[1] == "to" and w[2] == "that"


def interim_units(words):
    """[(first word index, last word index inclusive)] covering every word once, in order."""
    sentences, cur = [], [0]
    for i in range(1, len(words)):
        a, b = words[i - 1].onset, words[i].onset
        if a is not None and b is not None and b - a >= PAUSE_S:
            sentences.append(cur)
            cur = [i]
        else:
            cur.append(i)
    sentences.append(cur)
    units = []
    for s in sentences:
        start = s[0]
        for k, i in enumerate(s):
            if i == start or not _cut_before(words, i):
                continue
            if i - start >= MIN_TOKENS and s[-1] + 1 - i >= MIN_TOKENS:
                units.append((start, i - 1))
                start = i
        units.append((start, s[-1]))
    return units


# ---------------------------------------------------------------- film material


def opaque(salt, x, n=10):
    return "x" + hashlib.sha256(f"{salt}:{x}".encode()).hexdigest()[:n]


def films_in_order(comp):
    """The ten films (not the cartoon) in presentation order, for position resolution."""
    seen, out = set(), []
    for o in sorted(comp.occurrences, key=lambda o: o.position):
        if o.work == "cartoon_introduction" or o.work in seen:
            continue
        seen.add(o.work)
        out.append(o.work)
    return out


ORDINALS = {
    "first": 0,
    "second": 1,
    "third": 2,
    "fourth": 3,
    "fifth": 4,
    "sixth": 5,
    "seventh": 6,
    "eighth": 7,
    "ninth": 8,
    "tenth": 9,
    "last": -1,
    "final": -1,
}


def resolve_position(comp, stated):
    if not stated:
        return None
    for word, i in ORDINALS.items():
        if word in stated.lower():
            return films_in_order(comp)[i]
    return None


def _mmss(t):
    t = int(round(t))
    return f"{t // 60}:{t % 60:02d}"


def film_outline(comp, texts, works, rng, salt, with_rows):
    """Annotation text of `works` in a seeded random order under cover names. With rows: per-call
    row numbers, opaque scene labels and film-relative times; returns the row map."""
    order = list(works)
    rng.shuffle(order)
    lines, rowmap, n = [], {}, 0
    for w in order:
        lines.append(f"\n##### FILM: {comp.work[w].label}")
        units = sorted(
            (u for u in comp.units if comp.work_of_unit(u.id) == w),
            key=lambda u: (comp.occurrence[u.occurrence].position, u.start_s),
        )
        occ_start = {}
        scene = None
        for u in units:
            o0 = occ_start.setdefault(u.occurrence, u.start_s)
            if with_rows and u.scene != scene:
                scene = u.scene
                lines.append(f"=== SCENE {opaque(salt, scene, 4)}")
            if with_rows:
                n += 1
                rowmap[n] = u.id
                lines.append(f"{n} [{_mmss(u.start_s - o0)}] {texts[u.id]}")
            else:
                lines.append(f"- {texts[u.id]}")
    return "\n".join(lines).strip(), rowmap, order


# ---------------------------------------------------------------- pass 1

PASS1_SYSTEM = """You read one person's spoken free recall of a sequence of short films they \
watched, and you track WHICH FILM the speaker is talking about at each point. The films are \
described below under the names shown to viewers, listed in ARBITRARY order (not the order they \
were shown). The recall is divided into numbered units in the order spoken.

For every unit return:
- "kind": "content" (describes what happens in a film: events, people, objects, places, \
dialogue), "work" (names or comments on a film as a whole without describing a particular moment: \
a title, "the next one was weird", an evaluation of the film), "memory_search" (about \
remembering, forgetting or searching memory, with no film content), "gave_up" (the speaker says \
they cannot recall more), "off_task" (talk unrelated to the films), or "fragment" (a filler or \
connective with no content of its own);
- "frame": the film the speaker is talking about at this unit, as a list of {"film": name exactly \
as listed, "w": probability}; several entries only when genuinely uncertain; weights sum to at \
most 1; an empty list when the unit is about no film (off task, or between films with no film \
named yet);
- "position": if the unit refers to a film by its place in the viewing order ("the first one", \
"the last movie"), the speaker's ordinal word ("first", "second", "last", ...); otherwise null.

Use the whole recall: the film being talked about is usually set by earlier units and holds until \
the speaker moves on. Be calibrated.

Reply with JSON only: {"units": [{"id": "U1", "kind": "...", "frame": [{"film": "...", "w": 0.9}], \
"position": null}]} covering every unit exactly once."""


def pass1_prompt(units_text, outline):
    body = "\n".join(f"U{i + 1}: {t}" for i, t in enumerate(units_text))
    return (
        f"FILMS (arbitrary order)\n{outline}\n\nRECALL UNITS (in spoken order)\n{body}\n\n"
        f"Return all {len(units_text)} units."
    )


# ---------------------------------------------------------------- pass 2

PASS2_SYSTEM = """You localize excerpts of people's spoken free recall of short films within the \
film they are about. You are given film annotations: each film's rows are numbered for this \
request only, grouped into scenes with meaningless labels, with times from the film's start. \
Films are listed in arbitrary order.

Each item has FILM (the film the speaker is talking about, from their whole recall), CONTEXT \
(what they said just before; use it only to resolve pronouns and ellipsis) and TARGET (the words to \
analyse). Items come from different points in RANDOM order with meaningless ids: judge each \
TARGET only from its own words, its CONTEXT and its FILM.

For each item return:
- "talk": "episodic" (the TARGET describes film content), "metamemory" (about remembering or \
forgetting), "commentary" (opinion or remark about a film as a film), or "fragment" (connective, \
filler or false start). Choose "episodic" when any film content is described.
- "refs": for episodic items, the film references the TARGET makes; empty otherwise. Each has:
  - "words": the exact contiguous words of the TARGET that carry it (copy them verbatim);
  - "extent": "moment" (an instant or short exchange) or "stretch" (a continuous activity or longer \
stretch);
  - "occurrence": "once", "repeated" (something that happens several separate times), or "joint" \
(several distinct moments referred to together);
  - "mode": "detailed" (particulars) or "gist" (condenses or summarizes);
  - "alts": competing hypotheses about WHICH rows are meant, each {"w": probability, "iv": \
[[first_row, last_row], ...]}, rows from the item's FILM. A repeated or joint reference lists all \
its moments as several intervals inside ONE hypothesis. Use several hypotheses only when the \
words genuinely fit different places. Weights sum to at most 1; leave the remainder unassigned \
when the words could fit places you have not listed.
- "frame_mismatch": true if the TARGET clearly describes a different film than FILM; else false.

Be calibrated: vague words deserve spread or low weights; a distinctive detail deserves \
concentrated weight.

Reply with JSON only: {"items": [{"id": "<id>", "talk": "...", "refs": [{"words": "...", \
"extent": "...", "occurrence": "...", "mode": "...", "alts": [{"w": 0.8, "iv": [[3, 5]]}]}], \
"frame_mismatch": false}]} covering every item exactly once."""


def pass2_prompt(batch, outline):
    lines = [
        f"ITEM {it['oid']}\nFILM: {it['film_label']}\nCONTEXT: {it['ctx'] or '(start of recall)'}"
        f"\nTARGET: {it['target']}"
        for it in batch
    ]
    return (
        f"FILM ANNOTATION\n{outline}\n\nITEMS (random order)\n\n"
        + "\n\n".join(lines)
        + f"\n\nAnalyse all {len(batch)} items."
    )


# ---------------------------------------------------------------- calls with a log


def logged_call(prompt, system, log, label):
    key = sha(json.dumps([MODEL, system, prompt]))
    cached = (CACHE / f"{key}.json").exists()
    t0 = time.time()
    text = call(prompt, system, model=MODEL)
    rec = json.loads((CACHE / f"{key}.json").read_text())
    log.append(
        dict(
            label=label,
            key=key,
            cached_before=cached,
            wall_seconds=round(time.time() - t0, 1),
            call_seconds=rec.get("seconds"),
            usage=rec.get("usage"),
            cost_usd=rec.get("costUsd"),
            prompt_chars=len(prompt),
            reply_chars=len(text),
        )
    )
    return text


def parse_with_retry(prompt, system, log, label, key):
    for attempt in range(3):
        sfx = (
            ""
            if attempt == 0
            else f"\n\nReply with strictly valid JSON only ({attempt})."
        )
        text = logged_call(prompt + sfx, system, log, f"{label}#{attempt}")
        try:
            return parse_json(text)[key], attempt
        except (ValueError, KeyError, TypeError):
            continue
    return None, 3


# ---------------------------------------------------------------- run


def run(A, participant, session, out: Path, seed=20261116, salt="cs2-ff-pilot-a"):
    comp = A.composition("JL")
    texts = A.unit_texts("JL")
    words = A.session_words(participant, session)  # refuses frozen participants
    spans = interim_units(words)
    units = [
        dict(
            k=k,
            first=a,
            last=b,
            onset=words[a].onset,
            text=" ".join(w.text for w in words[a : b + 1]),
        )
        for k, (a, b) in enumerate(spans)
    ]
    log, ledger = [], dict(pass1_units=len(units))
    works = [w.id for w in comp.works]
    label_to_work = {comp.work[w].label: w for w in works}

    # pass 1
    rng = random.Random(f"{seed}:pass1:{participant}:{session}")
    outline1, _, order1 = film_outline(comp, texts, works, rng, salt, with_rows=False)
    p1, retries1 = parse_with_retry(
        pass1_prompt([u["text"] for u in units], outline1),
        PASS1_SYSTEM,
        log,
        "pass1",
        "units",
    )
    if p1 is None:
        raise SystemExit("pass 1 returned no parseable JSON")
    by_id = {str(x.get("id")): x for x in p1}
    frames, p1_bad = [], 0
    for i, u in enumerate(units):
        x = by_id.get(f"U{i + 1}")
        if x is None or x.get("kind") not in (
            "content",
            "work",
            "memory_search",
            "gave_up",
            "off_task",
            "fragment",
        ):
            p1_bad += 1
            frames.append(dict(kind=None, frame=[], position=None))
            continue
        fr = []
        for f in x.get("frame") or []:
            w = label_to_work.get(str(f.get("film")))
            try:
                wt = float(f.get("w"))
            except (TypeError, ValueError):
                wt = None
            if w is not None and wt and wt > 0:
                fr.append((w, wt))
        frames.append(
            dict(
                kind=x["kind"],
                frame=sorted(fr, key=lambda t: -t[1]),
                position=x.get("position"),
            )
        )
    ledger.update(
        pass1_noncompliant_units=p1_bad, pass1_retries=retries1, pass1_film_order=order1
    )

    # pass 2 items: content units with a frame
    items = []
    for i, (u, f) in enumerate(zip(units, frames)):
        if f["kind"] == "content" and f["frame"]:
            ctx = " ".join(x["text"] for x in units[max(0, i - WINDOW) : i])
            items.append(
                dict(
                    k=i,
                    oid=opaque(salt, f"{participant}:{session}:{i}"),
                    work=f["frame"][0][0],
                    film_label=comp.work[f["frame"][0][0]].label,
                    ctx=ctx,
                    target=u["text"],
                )
            )
    random.Random(f"{seed}:pass2").shuffle(items)
    batches = [items[j : j + BATCH] for j in range(0, len(items), BATCH)]
    answers, p2_calls = {}, []
    for bi, batch in enumerate(batches):
        rng = random.Random(f"{seed}:pass2:call{bi}")
        bworks = sorted({it["work"] for it in batch})
        outline2, rowmap, order2 = film_outline(
            comp, texts, bworks, rng, f"{salt}:call{bi}", True
        )
        got, retries = parse_with_retry(
            pass2_prompt(batch, outline2), PASS2_SYSTEM, log, f"pass2-call{bi}", "items"
        )
        p2_calls.append(
            dict(
                call=bi,
                items=len(batch),
                films=order2,
                retries=retries,
                returned=len(got or []),
            )
        )
        for x in got or []:
            answers[str(x.get("id"))] = (x, rowmap)
    ledger["pass2_calls"] = p2_calls

    # conversion
    refs, cells, conv = (
        {},
        [],
        dict(references=0, refused={}, frame_mismatch=0, pass2_missing=0, shapes={}),
    )

    def refuse(why):
        conv["refused"][why] = conv["refused"].get(why, 0) + 1

    item_of = {it["k"]: it for it in items}
    for i, (u, f) in enumerate(zip(units, frames)):
        uid = f"u{i}"
        wids = list(range(u["first"], u["last"] + 1))
        sample_of = {w: Uncovered(uid) for w in wids}
        top = f["frame"][0] if f["frame"] else None
        kind = f["kind"]
        if kind in ("work", "memory_search") and top is not None:
            rid = f"{uid}:work"
            if kind == "work" and f["position"]:
                refs[rid] = PositionReference(
                    rid,
                    str(f["position"]),
                    resolve_position(comp, f["position"]),
                    min(top[1], 1.0),
                )
            else:
                refs[rid] = WorkReference(rid, top[0], min(top[1], 1.0))
            sample_of = {w: RefSample(rid) for w in wids}
        elif kind in ("memory_search", "gave_up", "off_task", "fragment"):
            sample_of = {w: StateSample(uid, kind) for w in wids}
        elif kind == "content" and i in item_of:
            got = answers.get(item_of[i]["oid"])
            if got is None:
                conv["pass2_missing"] += 1
            else:
                x, rowmap = got
                if x.get("frame_mismatch"):
                    conv["frame_mismatch"] += 1
                talk = x.get("talk")
                if talk in ("metamemory", "commentary", "fragment"):
                    st = "memory_search" if talk == "metamemory" else talk
                    sample_of = {w: StateSample(uid, st) for w in wids}
                else:
                    utoks = [(w, t) for w in wids for t in toks(words[w].text)]
                    best = {}
                    for ri, r in enumerate(x.get("refs") or []):
                        anchor = toks(r.get("words") or "")
                        hits = [
                            j
                            for j in range(len(utoks) - len(anchor) + 1)
                            if anchor
                            and [t for _, t in utoks[j : j + len(anchor)]] == anchor
                        ]
                        if len(hits) != 1:
                            refuse(
                                "anchor-not-found" if not hits else "anchor-ambiguous"
                            )
                            continue
                        members = _members(r, rowmap, top[1], refuse, conv)
                        if not members:
                            continue
                        rid = f"{uid}:r{ri}"
                        try:
                            refs[rid] = Reference(
                                rid, members, condition="cs2-ff-two-level"
                            )
                        except AccountError as e:
                            refuse(e.reason)
                            continue
                        conv["references"] += 1
                        covered = {
                            utoks[j][0] for j in range(hits[0], hits[0] + len(anchor))
                        }
                        for w in covered:  # innermost (shortest anchor) wins
                            if w not in best or len(anchor) < best[w][0]:
                                best[w] = (len(anchor), rid)
                    for w, (_, rid) in best.items():
                        sample_of[w] = RefSample(rid)
        for w in wids:
            cells.append(WordCell(w, words[w].onset, uid, sample_of[w]))
    acc = Account(
        comp,
        refs,
        {(participant, session): cells},
        condition=f"cs2-ff-two-level pilot ({MODEL}; seed {seed}; salt {salt})",
    )
    ledger["conversion"] = conv
    out.mkdir(parents=True, exist_ok=True)
    (out / "units.json").write_text(
        json.dumps(units, indent=1)
    )  # recall text: ignored data dir
    (out / "pass1.json").write_text(json.dumps(dict(raw=p1, frames=frames), indent=1))
    (out / "pass2.json").write_text(
        json.dumps({k: v[0] for k, v in answers.items()}, indent=1)
    )
    (out / "calls.json").write_text(json.dumps(log, indent=1))
    return acc, ledger, log


def _members(r, rowmap, frame_w, refuse, conv):
    occ, extent, mode = r.get("occurrence"), r.get("extent"), r.get("mode")
    out = []
    for a in r.get("alts") or []:
        try:
            w = float(a["w"])
            ivs = [tuple(sorted((int(x), int(y)))) for x, y in a["iv"]]
        except (KeyError, TypeError, ValueError):
            refuse("alts-invalid")
            return None
        if w <= 0 or not ivs:
            refuse("alts-empty")
            return None
        rows = []
        for lo, hi in ivs:
            ids = [rowmap.get(n) for n in range(lo, hi + 1)]
            if any(x is None for x in ids):
                refuse("unknown-row")
                return None
            rows.append(frozenset(ids))
        if occ == "joint" and len(rows) >= 2:
            h = Hypothesis(Shape.JOINT, tuple(rows))
        elif occ in ("repeated", "generic") and len(rows) >= 1:
            h = Hypothesis(Shape.SINGLE, tuple(rows))
        elif mode == "gist" and extent == "stretch":
            h = Hypothesis(Shape.BROAD, (frozenset().union(*rows),))
        else:
            h = Hypothesis(Shape.SINGLE, (frozenset().union(*rows),))
        conv["shapes"][h.shape.value] = conv["shapes"].get(h.shape.value, 0) + 1
        out.append(Member(h, w * frame_w))
    seen = []
    for m in out:
        if m.target in [x.target for x in seen]:
            refuse("duplicate-members")
            return None
        seen.append(m)
    return tuple(out)
