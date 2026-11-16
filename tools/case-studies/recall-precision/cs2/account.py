"""The account at native grain: references, word cells and their relations.

Address classes of a reference (design revision 2):
- `Reference`: a moment reference. One member is a definite reference (single, joint or broad
  hypothesis, or a typed destination); two or more members are an alternative.
- `WorkReference`: the speech identifies a film without a moment. Its own class, never a broad
  reference over the film's seconds.
- `PositionReference`: the speech points at the composition's order ("the last movie"); it
  records the producer's resolution to a work, which can be wrong and is scored as such.
- Non-film states (`NonFilm`) are typed and enter through a word's sample, not as references.

Relations (`Comparison`, `Attribution`) are kept in the account for the correspondence table;
neither is an alternative and neither enters a distribution.

Weights are used as written. A weight sum above 1 is refused (beyond a declared snap tolerance,
inside which the remainder is exactly 0); the remainder is the known unassigned share.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from enum import Enum

WEIGHT_TOL = 1e-9


class AccountError(ValueError):
    """A reference or word cell that the account refuses, with a typed reason."""

    def __init__(self, reason: str, detail: str = ""):
        super().__init__(f"{reason}: {detail}" if detail else reason)
        self.reason = reason


class Shape(Enum):
    SINGLE = "single"
    JOINT = "joint"
    BROAD = "broad"


class NonFilm(Enum):
    MEMORY_SEARCH = "memory_search"
    GAVE_UP = "gave_up"
    OFF_TASK = "off_task"
    COMMENTARY = "commentary"
    FRAGMENT = "fragment"


@dataclass(frozen=True)
class Hypothesis:
    """Film material a reference may mean: one constituent per moment (joint, repeated)."""

    shape: Shape
    constituents: tuple[frozenset, ...]

    def __post_init__(self):
        if not self.constituents or any(not c for c in self.constituents):
            raise AccountError("empty-hypothesis")
        if self.shape is Shape.BROAD and len(self.constituents) != 1:
            raise AccountError("broad-has-one-constituent")
        if self.shape is Shape.JOINT and len(self.constituents) < 2:
            raise AccountError("joint-needs-two-moments")

    @property
    def support(self) -> frozenset:
        return frozenset().union(*self.constituents)


@dataclass(frozen=True)
class Destination:
    """A typed destination beside the address: 'external', or 'in_work_outside' (a work's
    material that was not in the stimulus, e.g. the rest of an excerpted feature)."""

    kind: str
    work: str | None = None

    def __post_init__(self):
        if self.kind not in ("external", "in_work_outside"):
            raise AccountError("unknown-destination", self.kind)
        if (self.kind == "in_work_outside") != (self.work is not None):
            raise AccountError("destination-work", self.kind)


@dataclass(frozen=True)
class Member:
    target: Hypothesis | Destination
    weight: float


def _check_weights(ws, what):
    for w in ws:
        if not (isinstance(w, (int, float)) and math.isfinite(w) and 0 < w <= 1):
            raise AccountError("weight-invalid", f"{what}: {w!r}")
    total = math.fsum(ws)
    if total > 1 + WEIGHT_TOL:
        raise AccountError("weight-sum-above-1", f"{what}: {total!r}")
    return 0.0 if abs(1 - total) <= WEIGHT_TOL else 1 - total


@dataclass(frozen=True)
class Reference:
    """A moment reference (definite with one member, alternative with two or more)."""

    id: str
    members: tuple[Member, ...]
    condition: str = ""
    unassigned: float = field(init=False)

    def __post_init__(self):
        if not self.members:
            raise AccountError("no-members", self.id)
        keys = [m.target for m in self.members]
        if len(set(keys)) != len(keys):
            raise AccountError("duplicate-members", self.id)
        object.__setattr__(
            self,
            "unassigned",
            _check_weights([m.weight for m in self.members], self.id),
        )

    @property
    def alternative(self) -> bool:
        return len(self.members) >= 2


@dataclass(frozen=True)
class WorkReference:
    id: str
    work: str
    weight: float = 1.0
    condition: str = ""
    unassigned: float = field(init=False)

    def __post_init__(self):
        object.__setattr__(self, "unassigned", _check_weights([self.weight], self.id))


@dataclass(frozen=True)
class PositionReference:
    id: str
    position: str  # the producer's stated position, e.g. "last"
    resolved_work: str | None
    weight: float = 1.0
    condition: str = ""
    unassigned: float = field(init=False)

    def __post_init__(self):
        object.__setattr__(self, "unassigned", _check_weights([self.weight], self.id))


@dataclass(frozen=True)
class Comparison:
    a: str
    b: str
    kind: str  # e.g. 'similar_to', 'contrast'


@dataclass(frozen=True)
class Attribution:
    """A blend or confusion: the framed work paired with where the content actually occurred."""

    framed_work: str
    content_reference: str


# ---- word samples: what a word cell holds


@dataclass(frozen=True, order=True)
class RefSample:
    ref: str


@dataclass(frozen=True, order=True)
class StateSample:
    """A not-applicable word: its unit's typed non-film state (one sample per unit)."""

    unit: str
    state: str  # a NonFilm value


@dataclass(frozen=True, order=True)
class Uncovered:
    """A word no reference covers (one sample per recall unit; unit '' is outside every unit)."""

    unit: str


@dataclass(frozen=True)
class WordCell:
    index: int
    onset: float | None
    unit: str  # recall unit id, '' when outside every unit
    sample: RefSample | StateSample | Uncovered


@dataclass
class Account:
    """References and word cells for one corpus and producer condition."""

    composition: object
    references: dict  # id -> Reference | WorkReference | PositionReference
    words: dict  # (participant, session) -> list[WordCell]
    framing_work: dict = field(
        default_factory=dict
    )  # (participant, session, unit) -> work
    relations: list = field(default_factory=list)
    condition: str = ""

    def __post_init__(self):
        comp = self.composition
        for r in self.references.values():
            if isinstance(r, Reference):
                for m in r.members:
                    t = m.target
                    if isinstance(t, Hypothesis):
                        bad = [u for u in t.support if u not in comp.unit]
                        if bad:
                            raise AccountError("unknown-unit", f"{r.id}: {bad[:3]}")
                    elif t.work is not None and t.work not in comp.work:
                        raise AccountError("unknown-work", r.id)
            elif isinstance(r, WorkReference) and r.work not in comp.work:
                raise AccountError("unknown-work", r.id)
            elif isinstance(r, PositionReference):
                if r.resolved_work is not None and r.resolved_work not in comp.work:
                    raise AccountError("unknown-work", r.id)
        valid_states = {s.value for s in NonFilm}
        for key, cells in self.words.items():
            for c in cells:
                s = c.sample
                if isinstance(s, RefSample) and s.ref not in self.references:
                    raise AccountError("unknown-reference", f"{key}: {s.ref}")
                if isinstance(s, StateSample) and s.state not in valid_states:
                    raise AccountError("unknown-state", s.state)
