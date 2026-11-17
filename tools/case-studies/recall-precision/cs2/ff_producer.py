"""Two-level FilmFestival producer for case study 2 (design: "Producer on FilmFestival").

It runs on selected development sessions; the adapter refuses the frozen participants.

Units come from the library's own segmenter: `agentStudyPrepare` (recipe `recall-segmenter/v1`)
run on one transcript per session, the session's word cells joined by single spaces (the word
clock carries no punctuation, so no author utterance boundary enters the text). Its export holds
canonical UTF-16 spans; `library_units` maps them back to word cells exactly. Words outside every
unit (connectives the segmenter consumes as separators) belong to no unit.

Note: FilmFestival word onsets are linear interpolations inside the authors' utterances
(recall.receipt.json: wordOnsetsAreMeasured false), so the word clock itself carries the authors'
utterance timing even though the text does not.

Two conditions, recorded separately (ADR 0022 rendering identities):
- pass 1, film frame (`cs2-ff-frame/v2`): one call per session that SEES THE WHOLE SESSION IN
  SPOKEN ORDER, by design, plus the films' annotation under cover names in a per-session seeded
  order with no times or ids. Per unit: kind, frame (films with weights), stated position.
- pass 2, content-local (`cs2-ff-local/v2`): window 2; items pooled across every selected session
  and film; batches built so that no batch holds two items of one session within WINDOW units of
  each other (no context chaining); each call shows only its items' films, in a per-call order,
  with per-call row numbers, 8-hex scene labels and film-relative times; item ids are salted
  hashes; an absent context renders as an empty CONTEXT line for every item alike.

Seeds: pass-1 film order from (seed, sample, participant, session); pass-2 batch composition from
(seed, sample); per-call film order and label salt from (seed or salt, sample, call index). A new
sample takes a new seed and salt, so nothing is reused across samples.

Declared conversion into the account (cs2.account):
- content: pass-2 references with member weight = top frame weight x alt weight. Pass-2
  metamemory or commentary inside the framed film is a WorkReference to the framed work;
- work: a WorkReference to the top frame film; with a stated position, a PositionReference
  resolved by `resolve_position` (ordinals, "second to last", "next", "previous");
- memory_search with a frame: a WorkReference (the gold codes memory search within a film as
  film-level; a typed 'searching within work' state is a vocabulary-ADR gap). Without a frame:
  the non-film state memory_search. gave_up and off_task: non-film states. A pass-1 'work'
  remark with no framed work: the non-film commentary state;
- fragment (pass 1 or pass 2: no content of its own) is a ledger count (fragment_words), never
  a destination and never non-film mass; its words are uncovered;
- shapes: occurrence joint (two or more intervals) -> JOINT; repeated or generic -> SINGLE with one
  constituent per interval; mode gist with extent stretch -> BROAD; otherwise SINGLE. A BROAD
  hypothesis is located by projections.broad_case;
- a member whose rows lie outside the item's framed film is refused (typed, counted:
  rows-outside-frame); an anchor must occur exactly once in its unit's tokens.
"""

from __future__ import annotations

import hashlib
import json
import math
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

from cs2.projections import broad_case  # noqa: E402
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
    TalkSample,
    Uncovered,
    WordCell,
    WorkReference,
)

WINDOW = 2
BROAD_RULE = "scene-set"  # for the conversion ledger's broad-case counts (set by the caller)
BATCH = 40
MODEL = "opus"
SEGMENTER = (
    "recall-segmenter/v1 via agentStudyPrepare (library), words joined by single spaces"
)
NORM = re.compile(r"[^a-z0-9']+")
KINDS = ("content", "work", "memory_search", "gave_up", "off_task", "fragment")


def toks(s):
    return [t for t in NORM.sub(" ", s.lower()).split() if t]


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


# ---------------------------------------------------------------- units (library segmenter)


def transcript_of(words) -> str:
    """The transcript given to the library segmenter: word cells joined by single spaces."""
    return " ".join(w.text for w in words) + "\n"


def _utf16_len(s):
    return len(s.encode("utf-16-le")) // 2


def library_units(session_export: dict, words) -> list:
    """[(first word, last word)] per library unit, from canonical UTF-16 spans.

    Refuses an export whose canonical text is not this session's transcript, a unit whose text is
    not its slice, and a span that cuts through a word."""
    canon = session_export["transcript"]["canonicalText"]
    if canon != transcript_of(words).rstrip("\n"):
        raise ValueError("export transcript is not this session's word transcript")
    starts, ends, pos = [], [], 0
    for i, w in enumerate(words):
        if i:
            pos += 1  # the joining space
        starts.append(pos)
        pos += _utf16_len(w.text)
        ends.append(pos)
    by_start = {s: i for i, s in enumerate(starts)}
    by_end = {e: i for i, e in enumerate(ends)}
    units16 = canon.encode("utf-16-le")
    out = []
    for u in session_export["transcript"]["units"]:
        if len(u["spans"]) != 1:
            raise ValueError(f"unit {u['u']} has {len(u['spans'])} spans")
        a, b = u["spans"][0]["start"], u["spans"][0]["endExclusive"]
        if units16[2 * a : 2 * b].decode("utf-16-le") != u["text"]:
            raise ValueError(f"unit {u['u']} text is not its span")
        if a not in by_start or b not in by_end:
            raise ValueError(f"unit {u['u']} span cuts through a word")
        out.append((by_start[a], by_end[b]))
    for (_, l1), (f2, _) in zip(out, out[1:]):
        if f2 <= l1:
            raise ValueError("library units overlap or are out of order")
    return out


def load_export(export_dir: Path) -> dict:
    """(participant, session) -> session export dict, and the export index."""
    idx = json.loads((export_dir / "index.json").read_text())
    out = {}
    for s in idx["sessions"]:
        out[(s["participant"], s["session"])] = json.loads(
            (export_dir / s["file"]).read_text()
        )
    return out, idx


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
}


def resolve_position(comp, stated, current_work=None):
    """The work a stated position names, or None when it cannot be resolved.

    Absolute: "first" .. "tenth", "last"/"final", "<ordinal> to last" / "penultimate".
    Relative to `current_work` (the frame of the preceding framed unit): "next"/"following"/
    "after", "previous"/"before"/"prior". Relative phrases without a current work are None."""
    if not stated:
        return None
    s = " ".join(toks(stated))
    order = films_in_order(comp)
    m = re.search(r"\b(" + "|".join(ORDINALS) + r") (?:to|from the) last\b", s)
    if m:
        i = len(order) - 1 - ORDINALS[m.group(1)]
        return order[i] if 0 <= i < len(order) else None
    if re.search(r"\b(penultimate|next to last)\b", s):
        return order[-2]
    if re.search(r"\b(next|following|after)\b", s) or re.search(
        r"\b(previous|before|prior)\b", s
    ):
        if current_work not in order:
            return None
        step = 1 if re.search(r"\b(next|following|after)\b", s) else -1
        i = order.index(current_work) + step
        return order[i] if 0 <= i < len(order) else None
    if re.search(r"\b(last|final)\b", s):
        return order[-1]
    for word, i in ORDINALS.items():
        if re.search(rf"\b{word}\b", s):
            return order[i] if i < len(order) else None
    return None


def _mmss(t):
    t = int(round(t))
    return f"{t // 60}:{t % 60:02d}"


OUTLINE_ORDERS = ("part-order", "start-only")


RENDERINGS = ("plain", "cs1-extras")


def film_outline(comp, texts, works, rng, salt, with_rows, order_rule="part-order",
                 extras=None):
    """`extras` (rendering 'cs1-extras'): {scene: dict(title, part, start, end, caption)}; the
    row outline's scene headers then carry the scene title with its part and time span, and a
    visual-summary caption line, as the CS1 prompt did. Opaque scene labels are kept."""
    """Annotation text of `works` in a seeded random order under cover names. With rows: per-call
    row numbers, 8-hex scene labels and film-relative times; returns the row map.

    order_rule 'part-order' (fixed 2026-11-17): units in presentation order (occurrence, then
    its segments' parts in order, then seconds), times relative to their segment, with the part
    named when an occurrence spans parts. 'start-only' (the rule the first Sherlock two-level run
    used, kept for exact replay): (occurrence, seconds), which interleaves the parts of an
    occurrence that spans parts. The two agree whenever each occurrence has one segment."""
    if order_rule not in OUTLINE_ORDERS:
        raise ValueError(f"unknown outline order {order_rule!r}")
    order = list(works)
    rng.shuffle(order)
    lines, rowmap, n = [], {}, 0
    for w in order:
        lines.append(f"\n##### FILM: {comp.work[w].label}")
        def seg_index(u):
            parts = [g.part for g in comp.occurrence[u.occurrence].segments]
            return parts.index(u.part)

        if order_rule == "part-order":
            key = lambda u: (comp.occurrence[u.occurrence].position, seg_index(u), u.start_s)  # noqa: E731
        else:
            key = lambda u: (comp.occurrence[u.occurrence].position, u.start_s)  # noqa: E731
        units = sorted((u for u in comp.units if comp.work_of_unit(u.id) == w), key=key)
        occ_start, scene = {}, None
        for u in units:
            multi = len(comp.occurrence[u.occurrence].segments) > 1
            seg = (u.occurrence, u.part) if (order_rule == "part-order" and multi) else u.occurrence
            o0 = occ_start.setdefault(seg, u.start_s)
            tag = f"part {u.part} " if (order_rule == "part-order" and multi) else ""
            if with_rows and u.scene != scene:
                scene = u.scene
                x = (extras or {}).get(scene)
                if x:
                    lines.append(f"=== SCENE {opaque(salt, scene, 8)}: {x['title']} (part "
                                 f"{x['part']}, {_mmss(x['start'])}-{_mmss(x['end'])})")
                    if x.get("caption"):
                        lines.append(f"[visual summary] {x['caption']}")
                else:
                    lines.append(f"=== SCENE {opaque(salt, scene, 8)}")
            if with_rows:
                n += 1
                rowmap[n] = u.id
                lines.append(f"{n} [{tag}{_mmss(u.start_s - o0)}] {texts[u.id]}")
            else:
                lines.append(f"- {texts[u.id]}")
    return "\n".join(lines).strip(), rowmap, order


# ---------------------------------------------------------------- prompts

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
"the second to last", "the next one"), the speaker's own words for that place; otherwise null.

Use the whole recall: the film being talked about is usually set by earlier units and holds until \
the speaker moves on. Be calibrated.

Reply with JSON only: {"units": [{"id": "U1", "kind": "...", "frame": [{"film": "...", "w": 0.9}], \
"position": null}]} covering every unit exactly once."""

PASS1_TEMPLATE = (
    "FILMS (arbitrary order)\n{outline}\n\nRECALL UNITS (in spoken order)\n{units}\n\n"
    "Return all {n} units."
)
PASS1_UNIT = "U{i}: {text}"

PASS2_SYSTEM = """You localize excerpts of people's spoken free recall of short films within the \
film they are about. You are given film annotations: each film's rows are numbered for this \
request only, grouped into scenes with meaningless labels, with times from the film's start. \
Films are listed in arbitrary order.

Each item has FILM (the film the speaker is talking about, from their whole recall), CONTEXT \
(what they said just before; use it only to resolve pronouns and ellipsis; it may be empty) and \
TARGET (the words to analyse). Items come from different speakers and points in RANDOM order with \
meaningless ids: judge each TARGET only from its own words, its CONTEXT and its FILM.

For each item return:
- "talk": "episodic" (the TARGET describes film content), "metamemory" (about remembering or \
forgetting), "commentary" (opinion or remark about a film as a film), or "fragment" (connective, \
filler or false start). Choose "episodic" when any film content is described.
- "refs": for episodic items, the film references the TARGET makes; empty otherwise. Together, \
the "words" of an item's references must cover every word of the TARGET that describes film \
content; fragments, connectives and words about remembering may stay uncovered. Each has:
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

PASS2_ARMS = {
    "coverage": PASS2_SYSTEM,
    # the pass-2 prompt before the anchor-coverage sentence was added (owner decision, A/B arm)
    "pre-coverage": PASS2_SYSTEM.replace(
        " Together, the \"words\" of an item's references must cover every word of the TARGET "
        "that describes film content; fragments, connectives and words about remembering may "
        "stay uncovered.",
        "",
    ),
}
if PASS2_ARMS["pre-coverage"] == PASS2_ARMS["coverage"]:
    raise RuntimeError("the pre-coverage arm did not remove the coverage sentence")
PASS2_TEMPLATE = "FILM ANNOTATION\n{outline}\n\nITEMS (random order)\n\n{items}\n\nAnalyse all {n} items."
PASS2_ITEM = "ITEM {oid}\nFILM: {film}\nCONTEXT: {ctx}\nTARGET: {target}"
RETRY = "\n\nReply with strictly valid JSON only ({attempt})."

# ---- layout 'prefix' (2026-11-17, token efficiency): the stable outline goes in the system
# prompt, randomised once per sample; per-call content (units, items) goes last. Anchors are
# word-number spans of the TARGET instead of quoted words.
LAYOUTS = ("per-call", "prefix")
PASS1_TEMPLATE_PREFIX = "RECALL UNITS (in spoken order)\n{units}\n\nReturn all {n} units."
PASS2_TEMPLATE_PREFIX = "ITEMS (random order)\n\n{items}\n\nAnalyse all {n} items."
PASS2_ITEM_PREFIX = "ITEM {oid}\nFILM: {film}\nCONTEXT: {ctx}\nTARGET: {target}"


def _span_version(text):
    pairs = [
        ('  - "words": the exact contiguous words of the TARGET that carry it (copy them verbatim);',
         '  - "span": [first, last], the numbers of the first and last TARGET words that carry it '
         '(TARGET words are numbered [0], [1], ...);'),
        ('"refs": [{"words": "...", ', '"refs": [{"span": [0, 3], '),
    ]
    for old, new in pairs:
        if old not in text:
            raise RuntimeError(f"span version: phrase missing: {old[:40]}")
        text = text.replace(old, new)
    return text.replace('the "words" of an item\'s references', "the spans of an item's references")


PASS2_SPAN_ARMS = {k: _span_version(v) for k, v in PASS2_ARMS.items()}


def numbered(text):
    return " ".join(f"[{i}]{w}" for i, w in enumerate(text.split(" ")))


def pass1_prompt_prefix(units_text):
    body = "\n".join(PASS1_UNIT.format(i=i + 1, text=t) for i, t in enumerate(units_text))
    return PASS1_TEMPLATE_PREFIX.format(units=body, n=len(units_text))


def pass2_prompt_prefix(batch):
    items = "\n\n".join(
        PASS2_ITEM_PREFIX.format(oid=it["oid"], film=it["film_label"], ctx=it["ctx"],
                                 target=numbered(it["target"]))
        for it in batch)
    return PASS2_TEMPLATE_PREFIX.format(items=items, n=len(batch))


def make_film_batches(items, seed, sample, batch=BATCH, window=WINDOW):
    """Batches grouped by framed film (so calls share a film's outline prefix); within each film
    the no-chaining rule of make_batches holds unchanged."""
    out = []
    for w in sorted({it["work"] for it in items}):
        out += make_batches([it for it in items if it["work"] == w], seed, f"{sample}:{w}",
                            batch, window)
    return out


def context_of(units, i, window=WINDOW):
    """CONTEXT of unit i: the texts of up to `window` preceding units joined by single spaces
    (empty for the first unit; rendered the same way for every item)."""
    return " ".join(x["text"] for x in units[max(0, i - window) : i])


def pass1_prompt(units_text, outline):
    body = "\n".join(
        PASS1_UNIT.format(i=i + 1, text=t) for i, t in enumerate(units_text)
    )
    return PASS1_TEMPLATE.format(outline=outline, units=body, n=len(units_text))


def pass2_prompt(batch, outline, cast_line=None):
    items = "\n\n".join(
        PASS2_ITEM.format(
            oid=it["oid"], film=it["film_label"], ctx=it["ctx"], target=it["target"]
        )
        for it in batch
    )
    text = PASS2_TEMPLATE.format(outline=outline, items=items, n=len(batch))
    return f"CAST\n{cast_line}\n\n{text}" if cast_line else text


def renderer_sha256():
    """Digest of the code that renders prompts: outline, context join, templates' fillers."""
    import inspect

    src = "".join(inspect.getsource(f) for f in (
        film_outline, _mmss, opaque, context_of, pass1_prompt, pass2_prompt,
        pass1_prompt_prefix, pass2_prompt_prefix, numbered, make_film_batches))
    return sha256(src)


def corpus_text(text, A, pass_no):
    """A system prompt with the adapter's declared corpus phrases (PRODUCER_PHRASES: exact
    (pass, old, new) triples). Adapters without phrases (FilmFestival) get the prompt
    byte-identical."""
    for k, old, new in getattr(A, "PRODUCER_PHRASES", ()):
        if k != pass_no:
            continue
        if old not in text:
            raise ValueError(f"declared phrase not in the prompt: {old!r}")
        text = text.replace(old, new)
    return text


def conditions(seed, salt, sample, arm="coverage", word_clock=None, systems=None,
               segmenter=None, layout="per-call"):
    """The two passes' producer conditions (rendering identities), recorded separately. Each id
    is derived from its system prompt, template and renderer digests, so arms differ at the
    reference level."""
    common = dict(model=MODEL, seed=seed, salt=salt, sample=sample,
                  segmenter=segmenter or SEGMENTER, word_clock=word_clock)
    sys1, sys2 = systems or (PASS1_SYSTEM, PASS2_ARMS[arm])
    rend = renderer_sha256()
    t1 = PASS1_TEMPLATE_PREFIX if layout == "prefix" else PASS1_TEMPLATE
    t2 = (PASS2_TEMPLATE_PREFIX + "\n" + PASS2_ITEM_PREFIX if layout == "prefix"
          else PASS2_TEMPLATE + "\n" + PASS2_ITEM)
    p1 = dict(system_sha256=sha256(sys1),
              template_sha256=sha256(t1 + "\n" + PASS1_UNIT),
              renderer_sha256=rend, retry_sha256=sha256(RETRY))
    p2 = dict(system_sha256=sha256(sys2),
              template_sha256=sha256(t2),
              renderer_sha256=rend, retry_sha256=sha256(RETRY))
    if layout == "prefix":
        p1["layout"] = p2["layout"] = "prefix"
        p2["anchors"] = "word-number spans"
    ident = lambda name, d: f"{name}:{sha256(json.dumps(d, sort_keys=True))[:16]}"  # noqa: E731
    return {
        "pass1": dict(
            id=ident("cs2-ff-frame", p1),
            exposure="whole session in spoken order (by design); cover names; films in a "
            "per-session seeded order; no times, rows or scene ids",
            **p1, **common,
        ),
        "pass2": dict(
            id=ident("cs2-ff-local", dict(p2, window=WINDOW, batch=BATCH)),
            arm=arm,
            exposure=f"content-local, window {WINDOW}; items pooled across sessions and films; no "
            "two items of one session within the window in a batch; per-call film order, row "
            "numbers and 8-hex scene labels; film-relative times; salted item ids; empty "
            "CONTEXT rendered uniformly",
            window=WINDOW, batch=BATCH, **p2, **common,
        ),
    }


# ---------------------------------------------------------------- calls with a log


CACHE_ONLY = False  # set for a rescore: a cache miss is refused instead of calling the model
_PREFETCHED_FRESH: set = set()


def prefetch(pairs, jobs):
    """Make the first-attempt calls for [(prompt, system)] in parallel; later logged calls read
    them from the cache but are still counted as fresh."""
    from concurrent.futures import ThreadPoolExecutor

    todo = []
    for prompt, system in pairs:
        key = sha(json.dumps([MODEL, system, prompt]))
        if not (CACHE / f"{key}.json").exists():
            if CACHE_ONLY:
                raise RuntimeError(f"cache miss in a cache-only run: {key}")
            todo.append((key, prompt, system))
    with ThreadPoolExecutor(max(1, jobs)) as ex:
        list(ex.map(lambda t: call(t[1], t[2], model=MODEL), todo))
    _PREFETCHED_FRESH.update(k for k, _, _ in todo)


def logged_call(prompt, system, log, label, condition):
    key = sha(json.dumps([MODEL, system, prompt]))
    cached = (CACHE / f"{key}.json").exists() and key not in _PREFETCHED_FRESH
    if CACHE_ONLY and not (CACHE / f"{key}.json").exists():
        raise RuntimeError(f"cache miss in a cache-only run: {label}")
    t0 = time.time()
    text = call(prompt, system, model=MODEL)
    rec = json.loads((CACHE / f"{key}.json").read_text())
    log.append(
        dict(
            label=label,
            condition=condition,
            key=key,
            fresh=not cached,
            rendered_prompt_sha256=sha256(prompt),
            system_sha256=sha256(system),
            wall_seconds=round(time.time() - t0, 1),
            call_seconds=rec.get("seconds"),
            usage=rec.get("usage"),
            cost_usd=rec.get("costUsd"),
            prompt_chars=len(prompt),
            reply_chars=len(text),
        )
    )
    return text


def parse_with_retry(prompt, system, log, label, key, condition):
    for attempt in range(3):
        sfx = "" if attempt == 0 else RETRY.format(attempt=attempt)
        text = logged_call(prompt + sfx, system, log, f"{label}#{attempt}", condition)
        try:
            return parse_json(text)[key], attempt
        except (ValueError, KeyError, TypeError):
            continue
    return None, 3


def call_ledger(log):
    """Fresh calls (model calls made now) and cached calls, counted and costed separately."""

    def tot(xs):
        usage = {}
        for c in xs:
            for k, v in (c["usage"] or {}).items():
                if isinstance(v, (int, float)):
                    usage[k] = usage.get(k, 0) + v
        return dict(
            n=len(xs), cost_usd=math.fsum(c["cost_usd"] or 0 for c in xs), usage=usage
        )

    return dict(
        model_calls=sum(c["fresh"] for c in log),
        fresh=tot([c for c in log if c["fresh"]]),
        cached=tot([c for c in log if not c["fresh"]]),
    )


# ---------------------------------------------------------------- batching


def make_batches(items, seed, sample, batch=BATCH, window=WINDOW):
    """Pool items of all sessions and films into batches. An item of session s at unit k goes to
    batch (k + offset_s) mod nb with nb >= window + 1, so two items of one session in a batch are
    at least nb > window units apart and no item's context is another item's target."""
    if not items:
        return []
    rng = random.Random(f"{seed}:{sample}:pass2-batches")
    nb = max(math.ceil(len(items) / batch), window + 1)  # empty batches are dropped below
    sessions = sorted({it["session_key"] for it in items})
    offset = {s: rng.randrange(nb) for s in sessions}
    out = [[] for _ in range(nb)]
    for it in sorted(items, key=lambda x: (x["session_key"], x["k"])):
        out[(it["k"] + offset[it["session_key"]]) % nb].append(it)
    for b in out:
        rng.shuffle(b)
    return [b for b in out if b]


def batch_report(batches, window=WINDOW):
    """Realized exposure: sessions per batch and the closest two items of one session in a batch."""
    min_gap, per = None, []
    for b in batches:
        by = {}
        for it in b:
            by.setdefault(it["session_key"], []).append(it["k"])
        per.append(len(by))
        for ks in by.values():
            ks.sort()
            for a, c in zip(ks, ks[1:]):
                min_gap = c - a if min_gap is None else min(min_gap, c - a)
    return dict(
        batches=len(batches),
        sizes=[len(b) for b in batches],
        min_sessions_per_batch=min(per) if per else 0,
        min_same_session_unit_gap=min_gap,
        window=window,
        chaining_possible=min_gap is not None and min_gap <= window,
    )


# ---------------------------------------------------------------- conversion


def match_anchor(utoks, anchor):
    """Index of the unique occurrence of `anchor` (token list) in `utoks`, or a refusal reason."""
    if not anchor:
        return None, "anchor-empty"
    hits = [
        j
        for j in range(len(utoks) - len(anchor) + 1)
        if utoks[j : j + len(anchor)] == anchor
    ]
    if len(hits) == 1:
        return hits[0], None
    return None, "anchor-not-found" if not hits else "anchor-ambiguous"


def members(r, rowmap, frame_work, frame_w, comp, refuse, conv):
    """Members of one pass-2 reference, or None (refused, counted in conv['refused'])."""
    occ, extent, mode = r.get("occurrence"), r.get("extent"), r.get("mode")
    out = []
    for a in r.get("alts") or []:
        try:
            w = float(a["w"])
            ivs = [tuple(sorted((int(x), int(y)))) for x, y in a["iv"]]
        except (KeyError, TypeError, ValueError):
            refuse("alts-invalid")
            return None
        if not (w > 0) or not ivs:
            refuse("alts-empty")
            return None
        rows = []
        for lo, hi in ivs:
            ids = [rowmap.get(n) for n in range(lo, hi + 1)]
            if any(x is None for x in ids):
                refuse("unknown-row")
                return None
            rows.append(frozenset(ids))
        if any(comp.work_of_unit(u) != frame_work for c in rows for u in c):
            refuse("rows-outside-frame")
            return None
        if occ == "joint" and len(rows) >= 2:
            h = Hypothesis(Shape.JOINT, tuple(rows))
        elif occ in ("repeated", "generic"):
            h = Hypothesis(Shape.SINGLE, tuple(rows))
        elif mode == "gist" and extent == "stretch":
            h = Hypothesis(Shape.BROAD, (frozenset().union(*rows),))
        else:
            h = Hypothesis(Shape.SINGLE, (frozenset().union(*rows),))
        out.append(Member(h, w * frame_w))
    if len({m.target for m in out}) != len(out):
        refuse("duplicate-members")
        return None
    for m in out:
        conv["shapes"][m.target.shape.value] = (
            conv["shapes"].get(m.target.shape.value, 0) + 1
        )
        if m.target.shape is Shape.BROAD:
            case = broad_case(m.target, comp, BROAD_RULE)
            conv["broad_cases"][case] = conv["broad_cases"].get(case, 0) + 1
    return tuple(out)


def parse_frames(p1, units, comp):
    label_to_work = {w.label: w.id for w in comp.works}
    by_id = {str(x.get("id")): x for x in p1 or []}
    frames, bad = [], 0
    for i in range(len(units)):
        x = by_id.get(f"U{i + 1}")
        if x is None or x.get("kind") not in KINDS:
            bad += 1
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
                fr.append((w, min(wt, 1.0)))
        frames.append(
            dict(
                kind=x["kind"],
                frame=sorted(fr, key=lambda t: -t[1]),
                position=x.get("position"),
            )
        )
    return frames, bad


def new_conv():
    return dict(references=0, refused={}, frame_mismatch=0, pass2_missing=0, shapes={},
                broad_cases={}, positions=dict(resolved=0, unresolved=0), fragment_words=0,
                non_addressed_words={}, anchored_non_narration_units=0, modes={})


def convert_session(
    key, words, units, frames, answers, items_by_k, comp, conds, refs, conv,
    rules=None, framing=None, unit_talk=None,
):
    """Word cells of one session; adds its references to `refs`.

    rules['talk']: 'orthogonal' (owner, 2026-11-17; default) or 'state-map-v1' (previous).
    Under 'orthogonal' talk type is a field, not an address: commentary, metamemory inside a
    framed film and fragments are non-addressed talk (TalkSample: a ledger count) unless the
    reply anchors a reference to film content; memory search between films (no frame), giving
    up and off-task speech are non-film states (destinations); a pass-1 'work' unit is identification (rules['talk_work_variant'] =
    'identifying': a framed or positioned unit is a work-level reference) or, under
    'position-only', only a positioned unit is. `framing` receives each framed unit's pass-1
    frame and `unit_talk` each unit's talk type."""
    rules = rules or dict(talk="orthogonal", talk_work_variant="identifying")
    talk_rule = rules.get("talk", "orthogonal")
    variant = rules.get("talk_work_variant", "identifying")
    framing = {} if framing is None else framing
    unit_talk = {} if unit_talk is None else unit_talk
    p, s = key
    refuse = lambda why: conv["refused"].__setitem__(  # noqa: E731
        why, conv["refused"].get(why, 0) + 1
    )
    in_unit = {}
    cells_samples = {}
    current = None

    def talk_only(uid, wids, talk):
        conv["non_addressed_words"][talk] = conv["non_addressed_words"].get(talk, 0) + len(wids)
        return {w: TalkSample(uid, talk) for w in wids}

    def work_ref(uid, top, f, cond, talk="identification"):
        rid = f"{uid}:work"
        if f["position"]:
            resolved = resolve_position(comp, f["position"], current)
            conv["positions"]["resolved" if resolved else "unresolved"] += 1
            w = top[1] if top else 1.0
            refs[rid] = PositionReference(rid, str(f["position"]), resolved, w,
                                          condition=cond, talk=talk)
        else:
            refs[rid] = WorkReference(rid, top[0], top[1], condition=cond, talk=talk)
        return rid

    for i, (u, f) in enumerate(zip(units, frames)):
        uid = f"{p}:{s}:u{i}"
        wids = list(range(u["first"], u["last"] + 1))
        for w in wids:
            in_unit[w] = uid
        sample_of = {w: Uncovered(uid) for w in wids}
        top = f["frame"][0] if f["frame"] else None
        if f["frame"]:
            framing[(p, s, uid)] = tuple(f["frame"])
        kind = f["kind"]
        talk = {"content": "narration", "work": "identification", "memory_search": "metamemory",
                "gave_up": "metamemory", "off_task": "off_task",
                "fragment": "fragment"}.get(kind, "unknown")
        if talk_rule == "state-map-v1":
            if kind in ("work", "memory_search") and top is not None:
                rid = f"{uid}:work"
                if kind == "work" and f["position"]:
                    resolved = resolve_position(comp, f["position"], current)
                    conv["positions"]["resolved" if resolved else "unresolved"] += 1
                    refs[rid] = PositionReference(rid, str(f["position"]), resolved, top[1],
                                                  condition=conds["pass1"]["id"])
                else:
                    refs[rid] = WorkReference(rid, top[0], top[1], condition=conds["pass1"]["id"])
                sample_of = {w: RefSample(rid) for w in wids}
            elif kind == "fragment":
                conv["fragment_words"] += len(wids)
            elif kind == "work":
                sample_of = {w: StateSample(uid, "commentary") for w in wids}
            elif kind in ("memory_search", "gave_up", "off_task"):
                sample_of = {w: StateSample(uid, kind) for w in wids}
        elif talk_rule == "orthogonal":
            if kind == "work" and (f["position"] or (top is not None and variant == "identifying")):
                rid = work_ref(uid, top, f, conds["pass1"]["id"])
                sample_of = {w: RefSample(rid) for w in wids}
            elif kind == "work":
                talk = "commentary"  # names no work it can be resolved to
                sample_of = talk_only(uid, wids, talk)
            elif kind == "memory_search" and top is not None:
                sample_of = talk_only(uid, wids, talk)  # metamemory inside a framed film
            elif kind == "fragment":
                conv["fragment_words"] += len(wids)
                sample_of = talk_only(uid, wids, talk)
            elif kind in ("memory_search", "gave_up", "off_task"):
                # correction 2026-11-17: memory search between films (no frame), giving up and
                # off-task speech are non-film STATES, typed destinations of the plan's address
                # classes, not talk about a film
                sample_of = {w: StateSample(uid, kind) for w in wids}
        else:
            raise ValueError(f"unknown talk rule {talk_rule!r}")
        if kind == "content" and i in items_by_k:
            got = answers.get(items_by_k[i]["oid"])
            if got is None:
                conv["pass2_missing"] += 1
            else:
                x, rowmap = got
                conv["frame_mismatch"] += bool(x.get("frame_mismatch"))
                t2 = x.get("talk")
                anchored = bool(x.get("refs"))
                if t2 == "fragment":
                    talk = "fragment"
                    conv["fragment_words"] += len(wids)
                    if talk_rule == "orthogonal":
                        sample_of = talk_only(uid, wids, "fragment")
                elif t2 in ("metamemory", "commentary") and (talk_rule == "state-map-v1"
                                                             or not anchored):
                    talk = t2
                    if talk_rule == "state-map-v1":
                        rid = f"{uid}:work"
                        refs[rid] = WorkReference(rid, top[0], top[1],
                                                  condition=conds["pass2"]["id"])
                        sample_of = {w: RefSample(rid) for w in wids}
                    else:
                        sample_of = talk_only(uid, wids, t2)
                else:
                    if t2 in ("metamemory", "commentary"):
                        talk = t2  # anchored to film content: its references carry address
                        conv["anchored_non_narration_units"] += 1
                    pairs = [(w, t) for w in wids for t in toks(words[w].text)]
                    utoks = [t for _, t in pairs]
                    best = {}
                    for ri, r in enumerate(x.get("refs") or []):
                        if "span" in r:  # layout 'prefix': word numbers of the TARGET
                            try:
                                a_, b_ = (int(v) for v in r["span"])
                            except (TypeError, ValueError):
                                refuse("span-invalid")
                                continue
                            if not (0 <= a_ <= b_ < len(wids)):
                                refuse("span-invalid")
                                continue
                            covered_w = wids[a_:b_ + 1]
                        else:
                            anchor = toks(r.get("words") or "")
                            j, why = match_anchor(utoks, anchor)
                            if why:
                                refuse(why)
                                continue
                            covered_w = sorted({pairs[t][0] for t in range(j, j + len(anchor))})
                        mem = members(r, rowmap, top[0], top[1], comp, refuse, conv)
                        if not mem:
                            continue
                        md = r.get("mode") or "unstated"
                        conv["modes"][md] = conv["modes"].get(md, 0) + 1
                        rid = f"{uid}:r{ri}"
                        try:
                            refs[rid] = Reference(rid, mem, condition=conds["pass2"]["id"],
                                                  talk=talk if talk != "content" else "narration")
                        except AccountError as e:
                            refuse(e.reason)
                            continue
                        conv["references"] += 1
                        for w in covered_w:
                            if w not in best or len(covered_w) < best[w][0]:  # innermost wins
                                best[w] = (len(covered_w), rid)
                    for w, (_, rid) in best.items():
                        sample_of[w] = RefSample(rid)
        unit_talk[(p, s, uid)] = talk
        if top is not None:
            current = top[0]
        cells_samples.update(sample_of)
    cells = []
    for w in range(len(words)):
        uid = in_unit.get(w, "")
        cells.append(WordCell(w, words[w].onset, uid, cells_samples.get(w, Uncovered(""))))
    return cells


# ---------------------------------------------------------------- run


def run(
    A,
    sessions,
    export_dir: Path,
    out: Path,
    seed: int,
    salt: str,
    sample: int,
    dry: bool = False,
    arm: str = "coverage",
    jobs: int = 1,
    rules: dict | None = None,
    outline_order: str = "part-order",
    rendering: str = "plain",
    layout: str = "per-call",
):
    """Both passes over `sessions` (development only), pass 2 pooled across them.

    With dry=True no model is called: prompts, batches and digests are built and reported."""
    comp = A.producer_composition()
    texts = A.producer_unit_texts()
    if hasattr(A, "producer_units"):  # an adapter that binds its own library units
        export, index = None, A.PRODUCER_UNITS_RECEIPT
    else:
        export, index = load_export(export_dir)
    if layout not in LAYOUTS:
        raise ValueError(f"unknown layout {layout!r}")
    arms = PASS2_SPAN_ARMS if layout == "prefix" else PASS2_ARMS
    sys1, sys2 = corpus_text(PASS1_SYSTEM, A, 1), corpus_text(arms[arm], A, 2)
    conds = conditions(seed, salt, sample, arm, A.WORD_CLOCK_RECEIPT, (sys1, sys2),
                       getattr(A, "PRODUCER_SEGMENTER", None), layout)
    if rendering not in RENDERINGS:
        raise ValueError(f"unknown rendering {rendering!r}")
    extras = A.producer_scene_extras() if rendering == "cs1-extras" else None
    cast_line = A.producer_cast_line() if rendering == "cs1-extras" else None
    for c in conds.values():
        c["outline_order"] = outline_order
    # declared: the pass-2 rendering condition carries its own id when it is not 'plain'
    conds["pass2"]["rendering"] = rendering
    if rendering != "plain":
        conds["pass2"]["id"] = conds["pass2"]["id"] + f"+{rendering}"
    log, ledger = (
        [],
        dict(
            sessions={}, export_index_sha256=sha256(json.dumps(index, sort_keys=True))
        ),
    )
    works = [w.id for w in comp.works]
    data = {}
    if layout == "prefix":  # one film order and label salt per sample, in the system prompt
        outline1, _, order1s = film_outline(comp, texts, works,
                                            random.Random(f"{seed}:{sample}:films"),
                                            f"{salt}:{sample}", False, outline_order)
        sys1_full = sys1 + "\n\nFILMS (arbitrary order)\n" + outline1
        ledger["sample_film_order"] = order1s
    for p, s in sessions:
        words = A.session_words(p, s)  # refuses frozen participants
        spans = (A.producer_units(p, s, words) if export is None
                 else library_units(export[(p, s)], words))
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
        if layout == "prefix":
            order1 = order1s
            prompt1 = pass1_prompt_prefix([u["text"] for u in units])
        else:
            rng = random.Random(f"{seed}:{sample}:pass1:{p}:{s}")
            outline1, _, order1 = film_outline(
                comp, texts, works, rng, f"{salt}:{sample}", False, outline_order
            )
            prompt1 = pass1_prompt([u["text"] for u in units], outline1)
        rec = dict(
            units=len(units),
            words=len(words),
            words_outside_units=len(words) - sum(b - a + 1 for a, b in spans),
            pass1_film_order=order1,
            pass1_prompt_sha256=sha256(prompt1),
            pass1_prompt_chars=len(prompt1),
        )
        data[(p, s)] = dict(words=words, units=units, prompt1=prompt1, rec=rec)
    if not dry:
        s1 = sys1_full if layout == "prefix" else sys1
        prefetch([(d["prompt1"], s1) for d in data.values()], jobs)
    for (p, s), d in data.items():
        words, units, prompt1, rec = d["words"], d["units"], d["prompt1"], d["rec"]
        if dry:
            p1, retries = [], 0
        else:
            p1, retries = parse_with_retry(
                prompt1,
                sys1_full if layout == "prefix" else sys1,
                log,
                f"pass1:{p}:{s}",
                "units",
                conds["pass1"]["id"],
            )
            if p1 is None:
                raise SystemExit(f"pass 1 returned no parseable JSON for {p} {s}")
        frames, bad = parse_frames(p1, units, comp)
        rec.update(
            pass1_noncompliant_units=bad if not dry else None, pass1_retries=retries
        )
        ledger["sessions"][f"{p}:{s}"] = rec
        data[(p, s)] = dict(words=words, units=units, frames=frames, p1=p1)

    items = []
    for key, d in data.items():
        units, frames = d["units"], d["frames"]
        for i, (u, f) in enumerate(zip(units, frames)):
            if dry or (f["kind"] == "content" and f["frame"]):
                ctx = context_of(units, i)
                work = (
                    f["frame"][0][0] if f["frame"] else works[-1]
                )  # dry: placeholder film
                items.append(
                    dict(
                        session_key=key,
                        k=i,
                        work=work,
                        ctx=ctx,
                        target=u["text"],
                        film_label=comp.work[work].label,
                        oid=opaque(f"{salt}:{sample}", f"{key[0]}:{key[1]}:{i}"),
                    )
                )
    batches = (make_film_batches(items, seed, sample) if layout == "prefix"
               else make_batches(items, seed, sample))
    ledger["batches"] = batch_report(batches)
    answers, p2_calls, rendered = {}, [], []
    film_prefix = {}
    for bi, batch in enumerate(batches):
        if layout == "prefix":
            w = batch[0]["work"]
            if w not in film_prefix:
                o2, rmap, _ = film_outline(comp, texts, [w], random.Random(0), f"{salt}:{sample}",
                                           True, outline_order, extras)
                head = f"CAST\n{cast_line}\n\n" if cast_line else ""
                film_prefix[w] = (sys2 + "\n\nFILM ANNOTATION\n" + head + o2, rmap)
            sysw, rmap = film_prefix[w]
            rendered.append((pass2_prompt_prefix(batch), rmap, [w], sysw))
            continue
        rng = random.Random(f"{seed}:{sample}:pass2:call{bi}")
        bworks = sorted({it["work"] for it in batch})
        outline2, rowmap, order2 = film_outline(
            comp, texts, bworks, rng, f"{salt}:{sample}:call{bi}", True, outline_order, extras
        )
        rendered.append((pass2_prompt(batch, outline2, cast_line), rowmap, order2, sys2))
    if not dry:
        prefetch([(r[0], r[3]) for r in rendered], jobs)
    for bi, (batch, (prompt2, rowmap, order2, sysb)) in enumerate(zip(batches, rendered)):
        rec = dict(
            call=bi,
            items=len(batch),
            films=order2,
            prompt_sha256=sha256(prompt2),
            prompt_chars=len(prompt2),
            system_sha256=sha256(sysb),
            system_chars=len(sysb),
            sessions=len({it["session_key"] for it in batch}),
        )
        if not dry:
            got, retries = parse_with_retry(
                prompt2,
                sysb,
                log,
                f"pass2:call{bi}",
                "items",
                conds["pass2"]["id"],
            )
            rec.update(retries=retries, returned=len(got or []))
            for x in got or []:
                answers[str(x.get("id"))] = (x, rowmap)
        p2_calls.append(rec)
    ledger["pass2_calls"] = p2_calls
    ledger["calls"] = call_ledger(log)
    out.mkdir(parents=True, exist_ok=True)
    (out / "conditions.json").write_text(json.dumps(conds, indent=1))
    (out / "calls.json").write_text(json.dumps(log, indent=1))
    if dry:
        (out / "dry-ledger.json").write_text(json.dumps(ledger, indent=1))
        return None, ledger, log, conds

    refs, cells, framing, unit_talk = {}, {}, {}, {}
    conv = new_conv()
    for key, d in data.items():
        by_k = {it["k"]: it for it in items if it["session_key"] == key}
        cells[key] = convert_session(key, d["words"], d["units"], d["frames"], answers, by_k,
                                     comp, conds, refs, conv, rules, framing, unit_talk)
    acc = Account(comp, refs, cells, framing_work=framing, unit_talk=unit_talk,
                  condition=json.dumps(dict(conds, rules=rules), sort_keys=True))
    ledger["conversion"] = conv
    (out / "units.json").write_text(
        json.dumps(  # recall text: ignored data directory
            {f"{p}:{s}": d["units"] for (p, s), d in data.items()}, indent=1
        )
    )
    (out / "pass1.json").write_text(
        json.dumps(
            {
                f"{p}:{s}": dict(raw=d["p1"], frames=d["frames"])
                for (p, s), d in data.items()
            },
            indent=1,
        )
    )
    (out / "pass2.json").write_text(
        json.dumps({k: v[0] for k, v in answers.items()}, indent=1)
    )
    return acc, ledger, log, conds
