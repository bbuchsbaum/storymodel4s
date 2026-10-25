#!/usr/bin/env python3
"""ADR 0028 section 2: the checked reader of `source-identity-graph/v2` for the connected assessor.

Why a reader and not a JSON load: the assessor must take identity from the recall-blind graph and
from nothing else, so every way the file could be wrong, stale or foreign is a typed refusal here,
and the reader never states a relation the graph does not. All time comparison is in integer
edition ticks, converted with exact rational arithmetic. One graph covers one media part.

Relations between classes (section 2):
- one class, uncontested: "Same";
- any pair involving a contested class: "Unresolved";
- two uncontested classes: "Distinct" when the graph lists the pair, otherwise "Unknown".
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import re

SCHEMA = "source-identity-graph/v2"
RELATIONS = frozenset({"Same", "Distinct", "Open", "Unknown"})
_JAVA_WHITESPACE = re.compile(r"[ \t\n\x0b\f\r]+")  # Java's `\s`, which is ASCII only
_TIME = re.compile(r"\d+(\.\d+)?")


class GraphRefusal(Exception):
    """The graph as a whole cannot be used; identity questions are then unresolved."""


class RecordRefusal(Exception):
    """One assessor record cannot be joined to the graph; its handles stay unbound."""


def _java_lower(ch: str) -> str:
    """`Character.toLowerCase` on one code point. U+0130 is the only code point whose Python
    lowercase (the full mapping) differs from Java's simple mapping, which gives one code point."""
    return "i" if ch == "İ" else ch.lower()


def fold_name(text: str) -> str:
    """Exactly the core's `ProperName.folded`: Java `trim`, a split on `\\s+`, a single-space join,
    then `TextNorm.lower`, so the reader and the resolver agree on every name."""
    trimmed = text.strip("".join(chr(c) for c in range(0x21)))
    joined = " ".join(_JAVA_WHITESPACE.split(trimmed))
    return "".join(_java_lower(ch) for ch in joined)


def _int(v) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def _increasing(values) -> bool:
    return all(_int(v) for v in values) and all(
        a < z for a, z in zip(values, values[1:])
    )


def _no_duplicate_keys(pairs):
    keys = [k for k, _ in pairs]
    if len(keys) != len(set(keys)):
        raise GraphRefusal("the graph repeats a JSON key")
    return dict(pairs)


@dataclass(frozen=True)
class Mention:
    id: str
    cls: str
    origin: str
    intervals: tuple[tuple[int, int], ...]
    name: str | None
    words: tuple[int, ...] | None


class Graph:
    """A validated v2 graph for one media part. Construct it only through `load`."""

    def __init__(self, doc, part: str):
        try:
            self._build(doc, part)
        except (AttributeError, TypeError, KeyError, ValueError) as e:
            raise GraphRefusal(f"malformed graph: {type(e).__name__}") from None

    def _build(self, doc: dict, part: str) -> None:
        if not isinstance(doc, dict) or doc.get("schemaVersion") != SCHEMA:
            raise GraphRefusal(f"schemaVersion must be {SCHEMA}")
        for key in ("edition", "mentions", "classes", "distinct", "relations"):
            if key not in doc:
                raise GraphRefusal(f"missing {key}")
        edition = doc["edition"]
        tb = edition.get("timebase")
        if not (
            isinstance(tb, list) and len(tb) == 2 and all(_int(x) and x > 0 for x in tb)
        ):
            raise GraphRefusal("the edition timebase must be two positive integers")
        if not (
            _int(edition.get("start"))
            and _int(edition.get("end"))
            and edition["start"] < edition["end"]
        ):
            raise GraphRefusal("the edition needs an integer start before its end")
        self.edition: str = edition.get("edition")
        self.part = part
        self._tick = Fraction(tb[0], tb[1])  # seconds per tick
        self._extent = (edition["start"], edition["end"])

        self._members: dict[str, tuple[str, ...]] = {}
        self._contested: set[str] = set()
        for c in doc["classes"]:
            members = c["members"]
            if not (
                isinstance(members, list)
                and members
                and all(isinstance(m, str) for m in members)
            ):
                raise GraphRefusal("a class needs its members")
            if c.get("id") != min(members):
                raise GraphRefusal(f"class id {c.get('id')} is not its least member")
            if c["id"] in self._members:
                raise GraphRefusal(f"class {c['id']} repeats")
            if not isinstance(c.get("contested"), bool):
                raise GraphRefusal(f"class {c['id']} has no contested flag")
            self._members[c["id"]] = tuple(members)
            if c["contested"]:
                self._contested.add(c["id"])
        owner = {m: cid for cid, ms in self._members.items() for m in ms}
        if len(owner) != sum(len(ms) for ms in self._members.values()):
            raise GraphRefusal("a mention belongs to two classes")

        self._distinct: set[tuple[str, str]] = set()
        for pair in doc["distinct"]:
            if not (
                isinstance(pair, list)
                and len(pair) == 2
                and all(isinstance(x, str) for x in pair)
                and pair[0] < pair[1]
            ):
                raise GraphRefusal("a distinct pair must be two ordered class ids")
            a, z = pair
            if a not in self._members or z not in self._members:
                raise GraphRefusal("a distinct pair names an unknown class")
            if a in self._contested or z in self._contested:
                raise GraphRefusal("a distinct pair names a contested class")
            if (a, z) in self._distinct:
                raise GraphRefusal("a distinct pair repeats")
            self._distinct.add((a, z))

        for row in doc["relations"]:
            if row.get("relation") not in RELATIONS:
                raise GraphRefusal(f"unknown relation status {row.get('relation')}")

        self._mentions: dict[str, Mention] = {}
        seen: set[str] = set()
        for m in doc["mentions"]:
            mid, status = m.get("id"), (m.get("outcome") or {}).get("status")
            if not isinstance(mid, str) or mid in seen:
                raise GraphRefusal(f"mention id {mid!r} is missing or repeats")
            seen.add(mid)
            if status == "refused":
                continue
            if status != "admitted":
                raise GraphRefusal(f"mention {mid} has unknown status {status!r}")
            cid, origin = m.get("class"), m.get("origin")
            if not isinstance(cid, str) or owner.get(mid) != cid:
                raise GraphRefusal(f"mention {mid} is not a member of its class {cid}")
            spans = m.get("intervals")
            if not (
                isinstance(spans, list)
                and spans
                and all(
                    isinstance(s, list) and len(s) == 2 and _increasing(s) and s[0] >= 0
                    for s in spans
                )
            ):
                raise GraphRefusal(f"mention {mid} has malformed intervals")
            name = m.get("name")
            if name is not None and not (isinstance(name, str) and fold_name(name)):
                raise GraphRefusal(f"mention {mid} has a malformed name")
            words = m.get("words")
            if origin == "dialogue":
                if m.get("stream") != "audio" or not (
                    isinstance(words, list)
                    and words
                    and _increasing(words)
                    and words[0] >= 0
                ):
                    raise GraphRefusal(
                        f"dialogue mention {mid} must be on the audio, with increasing words"
                    )
            elif origin == "visual":
                if words is not None or m.get("stream") != "picture":
                    raise GraphRefusal(
                        f"visual mention {mid} must be on the picture, without words"
                    )
            else:
                raise GraphRefusal(f"mention {mid} has unknown origin {origin}")
            self._mentions[mid] = Mention(
                mid,
                cid,
                origin,
                tuple(tuple(s) for s in spans),
                name,
                tuple(words) if words is not None else None,
            )
        if set(owner) != set(self._mentions):
            raise GraphRefusal("a class member is not an admitted mention")

    # --- records ----------------------------------------------------------------------------

    def _check_part(self, part: str) -> None:
        if part != self.part:
            raise RecordRefusal(
                f"the record is on part {part!r}, the graph on {self.part!r}"
            )

    def ticks(self, seconds) -> int:
        """Exact conversion of a record time inside the edition; anything else refuses the
        record. A time is an int, a Decimal, or a decimal string; floats are not accepted, since
        their value is not the text the record was written with."""
        if _int(seconds):
            value = Fraction(seconds)
        elif isinstance(seconds, Decimal) and seconds.is_finite():
            value = Fraction(seconds)
        elif isinstance(seconds, str) and _TIME.fullmatch(seconds):
            value = Fraction(Decimal(seconds))
        else:
            raise RecordRefusal(
                f"record time {seconds!r} is not a decimal number of seconds"
            )
        t = value / self._tick
        if t.denominator != 1:
            raise RecordRefusal(f"record time {seconds} is not a whole number of ticks")
        if not self._extent[0] <= t <= self._extent[1]:
            raise RecordRefusal(f"record time {seconds} is outside the edition")
        return int(t)

    def visual_mentions(self, part: str, start, end) -> list[Mention]:
        """Picture-stream mentions overlapping the record, half-open and strict, as the
        assessor's `overlaps` is."""
        self._check_part(part)
        a, z = self.ticks(start), self.ticks(end)
        if not a < z:
            raise RecordRefusal("a record interval must be non-empty")
        return sorted(
            (
                m
                for m in self._mentions.values()
                if m.origin == "visual" and any(s < z and a < e for s, e in m.intervals)
            ),
            key=lambda m: m.id,
        )

    def bind_picture(self, part: str, start, end, span_text: str) -> str | None:
        """Section 3: a picture handle binds only when its cited text equals, after the core's name
        folding, the annotation name of an overlapping visual mention, all such mentions in one
        class."""
        # A blank span matches nothing: blank names are refused when the graph loads.
        folded = fold_name(span_text)
        hits = {
            m.cls
            for m in self.visual_mentions(part, start, end)
            if m.name is not None and fold_name(m.name) == folded
        }
        return hits.pop() if len(hits) == 1 else None

    def bind_speech(
        self, part: str, words: list[dict], span: tuple[int, int]
    ) -> str | None:
        """Section 3: a speech handle binds only when its UTF-16 span covers exactly the words of one
        dialogue mention. `words` are the record's recognizer words in text order, each
        {"index", "start", "end"} in UTF-16 offsets of the record text; they must not overlap."""
        self._check_part(part)
        for w in words:
            if not (
                isinstance(w, dict)
                and _int(w.get("index"))
                and w["index"] >= 0
                and _int(w.get("start"))
                and _int(w.get("end"))
                and 0 <= w["start"] < w["end"]
            ):
                raise RecordRefusal(
                    "a speech record word needs an index and UTF-16 start and end"
                )
        if any(a["end"] > z["start"] for a, z in zip(words, words[1:])):
            raise RecordRefusal(
                "speech record words must be in text order and must not overlap"
            )
        if len({w["index"] for w in words}) != len(words):
            raise RecordRefusal("a speech record repeats a word index")
        s, e = span
        # Words are ordered and disjoint, so a span that starts or ends inside a word fails the
        # exact-cover test: its first or last covered word does not begin or end on the span.
        covered = [w for w in words if s <= w["start"] and w["end"] <= e]
        if not covered or covered[0]["start"] != s or covered[-1]["end"] != e:
            return None
        indices = tuple(w["index"] for w in covered)
        hits = [
            m
            for m in self._mentions.values()
            if m.origin == "dialogue" and m.words == indices
        ]
        return hits[0].cls if len(hits) == 1 else None

    def relation(self, class_a: str, class_b: str) -> str:
        for c in (class_a, class_b):
            if c not in self._members:
                raise GraphRefusal(f"unknown class {c}")
        if class_a in self._contested or class_b in self._contested:
            return "Unresolved"
        if class_a == class_b:
            return "Same"
        pair = (class_a, class_b) if class_a < class_b else (class_b, class_a)
        return "Distinct" if pair in self._distinct else "Unknown"


def load(path: Path, expected_sha256: str, edition: str, part: str) -> Graph:
    """The graph the run is frozen with, for the edition and media part the assessor's records
    declare."""
    data = Path(path).read_bytes()
    if hashlib.sha256(data).hexdigest() != expected_sha256:
        raise GraphRefusal("the graph's bytes do not match the frozen SHA-256")
    try:
        doc = json.loads(data, object_pairs_hook=_no_duplicate_keys)
    except ValueError:
        raise GraphRefusal("the graph is not JSON")
    graph = Graph(doc, part)
    if graph.edition != edition:
        raise GraphRefusal(f"the graph is for edition {graph.edition}, not {edition}")
    return graph
