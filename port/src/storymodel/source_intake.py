"""Compile native row timing into a checked source registry and exact clip intervals."""

from __future__ import annotations

import re
from fractions import Fraction
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from .model import Model, Name, _validate_instance, digest
from .study_time import ClipClock, Collection, Film, Scene, SceneInterval, StudySource

_INTEGER = re.compile(r"-?[0-9]+")
_EXACT = re.compile(r"-?[0-9]+(?:\.[0-9]+|/[1-9][0-9]*)?")
PositiveInt = Annotated[int, Field(gt=0)]


def tick(value: str) -> int:
    if not isinstance(value, str) or _INTEGER.fullmatch(value) is None:
        raise ValueError("native ticks must be integer strings")
    return int(value)


def exact(value: str) -> Fraction:
    """Exact decimal or fraction input; JSON floating numbers are not clock declarations."""
    if not isinstance(value, str) or _EXACT.fullmatch(value) is None:
        raise ValueError("clock quantities must be exact decimal or fraction strings")
    return Fraction(value)


def fraction_text(value: Fraction) -> str:
    return f"{value.numerator}/{value.denominator}"


class NativePart(Model):
    key: Name
    start_tick: str
    end_tick: str
    seconds_per_tick: str

    @model_validator(mode="after")
    def bounds(self) -> Self:
        if tick(self.end_tick) <= tick(self.start_tick) or exact(self.seconds_per_tick) <= 0:
            raise ValueError("part needs increasing bounds and a positive timebase")
        return self


class NativeRow(Model):
    """An annotated row; scene/film target membership will be derived from these rows."""

    number: PositiveInt
    scene: PositiveInt
    collection: PositiveInt
    part: Name
    start_tick: str
    end_tick: str | None = None
    target: bool

    @model_validator(mode="after")
    def bounds(self) -> Self:
        start = tick(self.start_tick)
        if self.end_tick is not None and tick(self.end_tick) < start:
            raise ValueError("row end precedes its start")
        return self


class ClipTransform(Model):
    """clip_seconds = rate * (source_seconds - origin_seconds) + offset_seconds."""

    collection: PositiveInt
    origin_seconds: str
    rate: str
    offset_seconds: str
    duration_seconds: str

    @model_validator(mode="after")
    def forward(self) -> Self:
        exact(self.origin_seconds)
        exact(self.offset_seconds)
        if exact(self.rate) <= 0 or exact(self.duration_seconds) <= 0:
            raise ValueError("clip rate and duration must be positive")
        return self


class NativeSource(Model):
    """Participant-independent source timing; all large integers stay lossless strings."""

    schema_version: Literal["storymodel.native-source/v1"] = "storymodel.native-source/v1"
    key: Name
    parts: Annotated[tuple[NativePart, ...], Field(min_length=1)]
    rows: Annotated[tuple[NativeRow, ...], Field(min_length=1)]
    clips: Annotated[tuple[ClipTransform, ...], Field(min_length=1)]
    tolerance_seconds: str

    @model_validator(mode="after")
    def derivable(self) -> Self:
        _derive_source(self)
        return self


def _derive_source(plan: NativeSource) -> StudySource:
    parts = {p.key: p for p in plan.parts}
    clips = {c.collection: c for c in plan.clips}
    if len(parts) != len(plan.parts) or len(clips) != len(plan.clips):
        raise ValueError("duplicate native part or clip transform")
    if len({r.number for r in plan.rows}) != len(plan.rows):
        raise ValueError("duplicate native row number")
    tolerance = exact(plan.tolerance_seconds)
    if tolerance < 0:
        raise ValueError("negative clock tolerance")
    groups: dict[int, int] = {}
    starts: dict[int, Fraction] = {}
    target_scenes: set[int] = set()
    target_films: set[int] = set()
    target_starts: dict[int, list[Fraction]] = {}
    for row in plan.rows:
        part = parts.get(row.part)
        if part is None:
            raise ValueError("row names an undeclared timebase")
        start = tick(row.start_tick)
        end = tick(row.end_tick) if row.end_tick is not None else start
        if not tick(part.start_tick) <= start <= end <= tick(part.end_tick):
            raise ValueError("row timing lies outside its declared part")
        if row.scene in groups and groups[row.scene] != row.collection:
            raise ValueError("one scene belongs to multiple collections")
        groups[row.scene] = row.collection
        at = start * exact(part.seconds_per_tick)
        starts[row.scene] = min(starts.get(row.scene, at), at)
        if row.target:
            target_scenes.add(row.scene)
            target_films.add(row.collection)
            target_starts.setdefault(row.collection, []).append(at)
    if set(clips) != target_films:
        raise ValueError("clock must cover exactly the target films")
    films: list[Film] = []
    for number, clip in sorted(clips.items()):
        origin = exact(clip.origin_seconds)
        if min(target_starts[number]) != origin:
            raise ValueError("clock origin is not the first target row")
        own = sorted(scene for scene, film in groups.items() if film == number)
        if any(scene not in target_scenes and starts[scene] >= origin for scene in own):
            raise ValueError("non-target scene lies inside the clip")
        own = [scene for scene in own if scene in target_scenes]
        begin = [
            exact(clip.rate) * (starts[scene] - origin) + exact(clip.offset_seconds)
            for scene in own
        ]
        bounds = begin + [exact(clip.duration_seconds)]
        if any(a >= b for a, b in zip(bounds, bounds[1:])):
            raise ValueError("scene starts must increase and end before the clip does")
        # Scala's first scene starts at clip zero even when a clock offset is declared.
        bounds[0] = Fraction(0)
        # Check after zero anchoring too: a large negative offset must not reverse interval 1.
        if any(a >= b for a, b in zip(bounds, bounds[1:])):
            raise ValueError("zero-anchored clip intervals must have positive duration")
        films.append(
            Film(
                collection=number,
                duration_seconds=fraction_text(bounds[-1]),
                scenes=tuple(
                    SceneInterval(
                        scene=scene,
                        start=fraction_text(bounds[i]),
                        end=fraction_text(bounds[i + 1]),
                    )
                    for i, scene in enumerate(own)
                ),
            )
        )
    return StudySource(
        collections=tuple(
            Collection(number=n, target=n in target_films) for n in sorted(set(groups.values()))
        ),
        scenes=tuple(
            Scene(number=s, collection=n, target=s in target_scenes)
            for s, n in sorted(groups.items())
        ),
        clock=ClipClock(tolerance_seconds=fraction_text(tolerance), films=tuple(films)),
    )


def validate_native_source(value: NativeSource) -> NativeSource:
    return _validate_instance(NativeSource, value)


def compile_source(value: NativeSource) -> StudySource:
    """Revalidate native inputs and derive a registry; never consult participant recall."""
    return _derive_source(validate_native_source(value))


def native_source_digest(value: NativeSource) -> str:
    return digest(validate_native_source(value).model_dump(mode="json"))
