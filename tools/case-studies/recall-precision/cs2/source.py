"""The film-material address (work, occurrence, part, seconds) and a corpus composition.

- A work is the identity of a film; an occurrence is one presentation slot of a work; a part is a
  contiguous playback axis (a scan run); seconds are on the part's own axis. No global timeline.
- A part may hold several occurrences, and an occurrence may span parts (it then has one segment
  per part; the declared rule for a constituent spanning parts is that its support is kept per
  part and never joined onto one axis).
- A source unit lies inside one segment of its occurrence. Its work is derived from the
  occurrence, never asserted by the caller.

Gold rows (GoldRow) are the corpus-neutral form of a human coding of recall: one row per coded
recall stretch with its work, its scene set and its class. Recall text is never carried.
"""

from __future__ import annotations

from dataclasses import dataclass, field

TOL = 1e-6  # seconds; containment tolerance for annotation times


class SourceError(ValueError):
    """A composition that cannot be built as declared."""


@dataclass(frozen=True)
class Work:
    """A film's identity. `label` is the stimulus label participants saw (cover name)."""

    id: str
    label: str
    title: str = ""
    excerpt: bool | None = (
        None  # the stimulus is part of a longer work (None: not known)
    )
    speech: bool | None = None
    note: str = ""


@dataclass(frozen=True)
class Part:
    """A contiguous playback axis, in its own seconds."""

    id: str
    start_s: float
    end_s: float


@dataclass(frozen=True)
class Segment:
    """The stretch of one part that an occurrence occupies."""

    part: str
    start_s: float
    end_s: float


@dataclass(frozen=True)
class Occurrence:
    """One presentation slot of a work; `position` is its presentation order (1-based)."""

    id: str
    work: str
    segments: tuple[Segment, ...]
    position: int


@dataclass(frozen=True)
class SourceUnit:
    """One annotated stretch of film material (e.g. an annotation row or segment)."""

    id: str
    occurrence: str
    part: str
    start_s: float
    end_s: float
    scene: object | None = (
        None  # coarse scene id, unique within the corpus; None if uncoded
    )
    title_card: bool = False

    @property
    def duration(self) -> float:
        return self.end_s - self.start_s


@dataclass(frozen=True)
class GoldRow:
    """One human-coded recall stretch on the session's word clock.

    cls: 'specific' (scene set given), 'film_only' (a work without a moment), 'film_search'
    (memory search within a work), 'nonfilm' (memory search, gave up, off task).
    """

    participant: str
    session: str
    row: int
    lo: float
    hi: float
    closed: bool  # True: lo <= t <= hi; False: lo <= t < hi
    cls: str
    work: str | None
    scenes: frozenset = frozenset()
    nonfilm: str | None = None

    @property
    def specific(self) -> bool:
        return self.cls == "specific"


@dataclass(frozen=True)
class RecallWord:
    """One recall word cell: its index in the session's word list and its onset (seconds)."""

    index: int
    text: str
    onset: float | None


@dataclass
class Composition:
    """A corpus's works, parts, occurrences and source units, validated on construction."""

    corpus: str
    works: tuple[Work, ...]
    parts: tuple[Part, ...]
    occurrences: tuple[Occurrence, ...]
    units: tuple[SourceUnit, ...]
    rules: dict = field(
        default_factory=dict
    )  # declared construction rules, for the record

    def __post_init__(self):
        self.work = _index(self.works, "work")
        self.part = _index(self.parts, "part")
        self.occurrence = _index(self.occurrences, "occurrence")
        self.unit = _index(self.units, "unit")
        for o in self.occurrences:
            if o.work not in self.work:
                raise SourceError(f"occurrence {o.id} names unknown work {o.work}")
            if not o.segments:
                raise SourceError(f"occurrence {o.id} has no segment")
            seen = set()
            for s in o.segments:
                if s.part not in self.part:
                    raise SourceError(f"occurrence {o.id} names unknown part {s.part}")
                if s.part in seen:
                    raise SourceError(
                        f"occurrence {o.id} has two segments on part {s.part}"
                    )
                seen.add(s.part)
                p = self.part[s.part]
                if not (p.start_s - TOL <= s.start_s < s.end_s <= p.end_s + TOL):
                    raise SourceError(
                        f"occurrence {o.id} segment lies outside part {s.part}"
                    )
        positions = [o.position for o in self.occurrences]
        if len(set(positions)) != len(positions):
            raise SourceError("two occurrences share a presentation position")
        self.scene_occurrence: dict = {}
        for u in self.units:
            if u.occurrence not in self.occurrence:
                raise SourceError(
                    f"unit {u.id} names unknown occurrence {u.occurrence}"
                )
            if not u.end_s >= u.start_s:  # a zero-length annotation row keeps its identity
                raise SourceError(f"unit {u.id} ends before it starts")
            seg = next(
                (s for s in self.occurrence[u.occurrence].segments if s.part == u.part),
                None,
            )
            if seg is None:
                raise SourceError(
                    f"unit {u.id} lies on part {u.part}, which does not hold its occurrence"
                )
            if not (seg.start_s - TOL <= u.start_s and u.end_s <= seg.end_s + TOL):
                raise SourceError(f"unit {u.id} lies outside its occurrence's segment")
            if u.scene is not None:
                prev = self.scene_occurrence.setdefault(u.scene, u.occurrence)
                if prev != u.occurrence:
                    raise SourceError(f"scene {u.scene} spans occurrences")

    # ---- derived identities (never asserted by callers)

    def work_of_unit(self, uid) -> str:
        return self.occurrence[self.unit[uid].occurrence].work

    def scene_of_unit(self, uid):
        s = self.unit[uid].scene
        if s is None:
            raise SourceError(f"unit {uid} has no scene: scene grain is unavailable")
        return s

    def occurrence_of_scene(self, scene) -> str:
        return self.scene_occurrence[scene]

    def work_of_scene(self, scene) -> str:
        return self.occurrence[self.scene_occurrence[scene]].work

    def work_of_occurrence(self, oid) -> str:
        return self.occurrence[oid].work

    def parts_of_work(self, wid) -> set:
        return {s.part for o in self.occurrences if o.work == wid for s in o.segments}

    def scenes(self) -> list:
        return sorted(self.scene_occurrence, key=_sort_key)

    def scenes_of_work(self, wid) -> list:
        return [s for s in self.scenes() if self.work_of_scene(s) == wid]

    def scene_seconds(self) -> dict:
        out: dict = {}
        for u in self.units:
            if u.scene is not None:
                out[u.scene] = out.get(u.scene, 0.0) + u.duration
        return out

    def title_only_scenes(self) -> set:
        by: dict = {}
        for u in self.units:
            if u.scene is not None:
                by.setdefault(u.scene, []).append(u.title_card)
        return {s for s, flags in by.items() if all(flags)}


def _sort_key(x):
    return (0, x, "") if isinstance(x, (int, float)) else (1, 0, str(x))


def _index(items, what):
    out = {}
    for x in items:
        if x.id in out:
            raise SourceError(f"duplicate {what} id {x.id}")
        out[x.id] = x
    return out
