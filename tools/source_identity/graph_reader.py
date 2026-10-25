#!/usr/bin/env python3
"""ADR 0028 section 2: the checked reader of `source-identity-graph/v2` for the connected assessor.

Why a reader and not a JSON load: the assessor must take identity from the recall-blind graph and
from nothing else, so every way the file could be wrong, stale or foreign is a typed refusal here,
and the reader never states a relation the graph does not. All time comparison is in integer
edition ticks, converted with exact rational arithmetic.

Relations between classes (section 2):
- one class, uncontested: "Same";
- any pair involving a contested class: "Unresolved";
- two uncontested classes: "Distinct" when the graph lists the pair, otherwise "Unknown".
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from fractions import Fraction
import hashlib
import json
from pathlib import Path

SCHEMA = "source-identity-graph/v2"
RELATIONS = frozenset({"Same", "Distinct", "Open", "Unknown"})


class GraphRefusal(Exception):
    """The graph as a whole cannot be used; identity questions are then unresolved."""


class RecordRefusal(Exception):
    """One assessor record cannot be joined to the graph; its handles stay unbound."""


def fold_name(text: str) -> str:
    """The core's `ProperName` folding: case and runs of whitespace."""
    return " ".join(text.split()).casefold()


@dataclass(frozen=True)
class Mention:
    id: str
    cls: str
    origin: str
    intervals: tuple[tuple[int, int], ...]
    name: str | None
    words: tuple[int, ...] | None


def _ids_increasing(values) -> bool:
    return all(isinstance(v, int) and not isinstance(v, bool) for v in values) and all(
        a < z for a, z in zip(values, values[1:])
    )


class Graph:
    """A validated v2 graph. Construct it only through `load`."""

    def __init__(self, doc: dict):
        if doc.get("schemaVersion") != SCHEMA:
            raise GraphRefusal(f"schemaVersion must be {SCHEMA}")
        edition = doc.get("edition") or {}
        tb = edition.get("timebase")
        if not (
            isinstance(tb, list)
            and len(tb) == 2
            and all(
                isinstance(x, int) and not isinstance(x, bool) and x > 0 for x in tb
            )
        ):
            raise GraphRefusal("the edition timebase must be two positive integers")
        self.edition: str = edition.get("edition")
        self._tick = Fraction(tb[0], tb[1])  # seconds per tick

        classes = doc.get("classes")
        if not isinstance(classes, list):
            raise GraphRefusal("missing classes")
        self._members: dict[str, tuple[str, ...]] = {}
        self._contested: set[str] = set()
        for c in classes:
            members = c.get("members")
            if not (
                isinstance(members, list)
                and members
                and all(isinstance(m, str) for m in members)
            ):
                raise GraphRefusal("a class needs its members")
            if c.get("id") != min(members) or c["id"] in self._members:
                raise GraphRefusal(
                    f"class id {c.get('id')} is not its least member, or repeats"
                )
            if not isinstance(c.get("contested"), bool):
                raise GraphRefusal(f"class {c['id']} has no contested flag")
            self._members[c["id"]] = tuple(members)
            if c["contested"]:
                self._contested.add(c["id"])
        owner = {m: cid for cid, ms in self._members.items() for m in ms}
        if len(owner) != sum(len(ms) for ms in self._members.values()):
            raise GraphRefusal("a mention belongs to two classes")

        self._distinct: set[tuple[str, str]] = set()
        for pair in doc.get("distinct", None) or []:
            if not (isinstance(pair, list) and len(pair) == 2 and pair[0] < pair[1]):
                raise GraphRefusal("a distinct pair must be two ordered class ids")
            a, z = pair
            if a not in self._members or z not in self._members:
                raise GraphRefusal("a distinct pair names an unknown class")
            if a in self._contested or z in self._contested:
                raise GraphRefusal("a distinct pair names a contested class")
            if (a, z) in self._distinct:
                raise GraphRefusal("a distinct pair repeats")
            self._distinct.add((a, z))
        if "distinct" not in doc:
            raise GraphRefusal("missing distinct")

        for r in doc.get("relations", []):
            if r.get("relation") not in RELATIONS:
                raise GraphRefusal(f"unknown relation status {r.get('relation')}")

        self._mentions: dict[str, Mention] = {}
        for m in doc.get("mentions", []):
            if (m.get("outcome") or {}).get("status") != "admitted":
                continue
            mid, cid, origin = m.get("id"), m.get("class"), m.get("origin")
            if owner.get(mid) != cid:
                raise GraphRefusal(f"mention {mid} is not a member of its class {cid}")
            spans = m.get("intervals")
            if not (
                isinstance(spans, list)
                and spans
                and all(
                    isinstance(s, list)
                    and len(s) == 2
                    and _ids_increasing(s)
                    and s[0] >= 0
                    for s in spans
                )
            ):
                raise GraphRefusal(f"mention {mid} has malformed intervals")
            words = m.get("words")
            if origin == "dialogue":
                if not (
                    isinstance(words, list)
                    and words
                    and _ids_increasing(words)
                    and words[0] >= 0
                ):
                    raise GraphRefusal(
                        f"dialogue mention {mid} needs increasing word indices"
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
                m.get("name"),
                tuple(words) if words is not None else None,
            )

    # --- time -------------------------------------------------------------------------------

    def ticks(self, seconds) -> int:
        """Exact conversion of a record time; a time off the tick grid refuses the record."""
        try:
            t = Fraction(Decimal(str(seconds))) / self._tick
        except (InvalidOperation, ValueError, TypeError):
            raise RecordRefusal(f"record time {seconds!r} is not a number")
        if t.denominator != 1:
            raise RecordRefusal(f"record time {seconds} is not a whole number of ticks")
        return int(t)

    # --- queries ----------------------------------------------------------------------------

    def visual_mentions(self, start, end) -> list[Mention]:
        """Picture-stream mentions overlapping the record, half-open and strict, as the
        assessor's `overlaps` is."""
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

    def bind_picture(self, start, end, span_text: str) -> str | None:
        """Section 3: a picture handle binds only when its cited text equals, after name folding,
        the annotation name of an overlapping visual mention, all such mentions in one class."""
        folded = fold_name(span_text)
        if not folded:
            return None
        hits = {
            m.cls
            for m in self.visual_mentions(start, end)
            if m.name is not None and fold_name(m.name) == folded
        }
        return hits.pop() if len(hits) == 1 else None

    def bind_speech(self, words: list[dict], span: tuple[int, int]) -> str | None:
        """Section 3: a speech handle binds only when its UTF-16 span covers exactly the words of one
        dialogue mention. `words` are the record's recognizer words: {"index", "start", "end"} in
        UTF-16 offsets of the record text."""
        s, e = span
        for w in words:
            if not (
                isinstance(w.get("index"), int)
                and isinstance(w.get("start"), int)
                and isinstance(w.get("end"), int)
                and w["start"] < w["end"]
            ):
                raise RecordRefusal(
                    "a speech record word needs index and UTF-16 start and end"
                )
        # Words do not overlap, so a span that cuts a word also fails the exact-cover test below.
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


def load(path: Path, expected_sha256: str, edition: str) -> Graph:
    """The graph the run is frozen with, for the edition the assessor's records use."""
    data = Path(path).read_bytes()
    if hashlib.sha256(data).hexdigest() != expected_sha256:
        raise GraphRefusal("the graph's bytes do not match the frozen SHA-256")
    try:
        doc = json.loads(data)
    except ValueError:
        raise GraphRefusal("the graph is not JSON")
    graph = Graph(doc)
    if graph.edition != edition:
        raise GraphRefusal(f"the graph is for edition {graph.edition}, not {edition}")
    return graph
