"""Strict, exact replay of the bounded film-and-time reply protocol."""

from __future__ import annotations

import re
from fractions import Fraction
from typing import Any, Self

from pydantic import AliasChoices, Field, field_validator, model_validator

from .model import Model

_TIME = re.compile(r"([0-9]{1,2}):([0-5][0-9](?:\.[0-9]{1,3})?)")
_FRACTION = re.compile(r"-?[0-9]+/[1-9][0-9]*")
_FAILURES = frozenset(("omitted", "unparsable-chunk"))


def _integer(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _exact(text: str) -> Fraction:
    if _FRACTION.fullmatch(text) is None:
        raise ValueError("must be an exact fraction")
    return Fraction(text)


class Collection(Model):
    """A source film registry entry, separate from any participant reply."""

    number: int = Field(
        validation_alias=AliasChoices("number", "collection"), serialization_alias="collection"
    )
    target: bool = Field(
        validation_alias=AliasChoices("target", "isTarget"), serialization_alias="isTarget"
    )

    @model_validator(mode="after")
    def positive(self) -> Self:
        if not _integer(self.number) or self.number <= 0:
            raise ValueError("collection number must be a positive integer")
        return self


class Scene(Model):
    """A source scene registry entry with its containing film."""

    number: int = Field(
        validation_alias=AliasChoices("number", "scene"), serialization_alias="scene"
    )
    collection: int
    target: bool = Field(
        validation_alias=AliasChoices("target", "isTarget"), serialization_alias="isTarget"
    )

    @model_validator(mode="after")
    def positive(self) -> Self:
        if not _integer(self.number) or self.number <= 0:
            raise ValueError("scene number must be a positive integer")
        if not _integer(self.collection) or self.collection <= 0:
            raise ValueError("scene collection must be a positive integer")
        return self


class SceneInterval(Model):
    """One exact half-open interval on a target film's clip clock."""

    scene: int
    start: str
    end: str

    @model_validator(mode="after")
    def exact_bounds(self) -> Self:
        if not _integer(self.scene) or self.scene <= 0:
            raise ValueError("interval scene must be a positive integer")
        if _exact(self.end) <= _exact(self.start):
            raise ValueError("interval must be positive")
        return self


class Film(Model):
    """The complete contiguous clip clock for one target collection."""

    collection: int
    duration_seconds: str = Field(
        validation_alias=AliasChoices("duration_seconds", "durationSeconds"),
        serialization_alias="durationSeconds",
    )
    scenes: tuple[SceneInterval, ...]

    @field_validator("scenes", mode="before")
    @classmethod
    def tuple_scenes(cls, value: object) -> object:
        return tuple(value) if isinstance(value, list) else value

    @model_validator(mode="after")
    def contiguous(self) -> Self:
        if not _integer(self.collection) or self.collection <= 0:
            raise ValueError("film collection must be a positive integer")
        duration = _exact(self.duration_seconds)
        if duration <= 0:
            raise ValueError("film duration must be positive")
        if not self.scenes:
            raise ValueError("film must have intervals")
        previous = Fraction(0)
        seen: set[int] = set()
        for interval in self.scenes:
            if interval.scene in seen:
                raise ValueError("film repeats a scene")
            seen.add(interval.scene)
            if _exact(interval.start) != previous:
                raise ValueError("film intervals must be contiguous from zero")
            previous = _exact(interval.end)
        if previous != duration:
            raise ValueError("film intervals must end at duration")
        return self


class ClipClock(Model):
    """Exact target-scene clocks used to interpret a stated film and time."""

    tolerance_seconds: str = Field(
        validation_alias=AliasChoices("tolerance_seconds", "toleranceSeconds"),
        serialization_alias="toleranceSeconds",
    )
    films: tuple[Film, ...]

    @field_validator("films", mode="before")
    @classmethod
    def tuple_films(cls, value: object) -> object:
        return tuple(value) if isinstance(value, list) else value

    @model_validator(mode="after")
    def positive_tolerance_and_films(self) -> Self:
        if _exact(self.tolerance_seconds) < 0:
            raise ValueError("clock tolerance cannot be negative")
        if len({film.collection for film in self.films}) != len(self.films):
            raise ValueError("clock repeats a collection")
        return self


class StudySource(Model):
    """The minimal source registry required to judge a time-protocol reply."""

    collections: tuple[Collection, ...]
    scenes: tuple[Scene, ...]
    clock: ClipClock | None = None

    @field_validator("collections", "scenes", mode="before")
    @classmethod
    def tuple_registries(cls, value: object) -> object:
        return tuple(value) if isinstance(value, list) else value

    @model_validator(mode="after")
    def registry_and_clock(self) -> Self:
        collections = {entry.number: entry for entry in self.collections}
        scenes = {entry.number: entry for entry in self.scenes}
        if len(collections) != len(self.collections):
            raise ValueError("source repeats a collection number")
        if len(scenes) != len(self.scenes):
            raise ValueError("source repeats a scene number")
        if any(scene.collection not in collections for scene in self.scenes):
            raise ValueError("scene names an unknown collection")
        if self.clock is None:
            return self
        target_films = {number for number, entry in collections.items() if entry.target}
        target_scenes = {number for number, entry in scenes.items() if entry.target}
        clock_films = {film.collection: film for film in self.clock.films}
        if set(clock_films) != target_films:
            raise ValueError("clock must cover exactly target collections")
        timed_scenes = {interval.scene for film in self.clock.films for interval in film.scenes}
        if timed_scenes != target_scenes:
            raise ValueError("clock must cover exactly target scenes")
        for film in self.clock.films:
            if any(
                scenes[interval.scene].collection != film.collection for interval in film.scenes
            ):
                raise ValueError("clock scene belongs to another collection")
        return self


def _parse_time(value: object) -> tuple[Fraction, float] | None:
    if not isinstance(value, str):
        return None
    matched = _TIME.fullmatch(value)
    if matched is None:
        return None
    seconds = Fraction(int(matched.group(1)) * 60) + Fraction(matched.group(2))
    return seconds, float(seconds)


def _failed(reason: str, raw: dict[str, Any] | None = None) -> dict[str, Any]:
    result: dict[str, Any] = {"status": "failed", "reason": reason}
    if raw is not None:
        result.update(raw)
    return result


def _prediction(source: StudySource, film: object, time: object) -> dict[str, Any]:
    if time is None:
        if _integer(film):
            return {"status": "answered", "kind": "film", "film": film}
        if film is None:
            return {"status": "answered", "kind": "none"}
        return _failed("invalid-film")
    raw = {"film_stated": film, "time": time}
    parsed = _parse_time(time)
    if parsed is None:
        return _failed("invalid-time", raw)
    if film is None:
        return _failed("time-without-film", raw)
    if not _integer(film):
        return _failed("invalid-film", raw)
    if source.clock is None:
        return _failed("no-clip-clock", raw)
    table = {entry.collection: entry for entry in source.clock.films}
    if film not in table:
        return _failed("unknown-film", raw)
    seconds, display_seconds = parsed
    entry = table[film]
    tolerance = _exact(source.clock.tolerance_seconds)
    near = [
        interval.scene
        for interval in entry.scenes
        if max(_exact(interval.start) - seconds, seconds - _exact(interval.end), Fraction(0))
        <= tolerance
    ]
    rest: dict[str, Any] = {"near": near, **raw, "seconds": display_seconds}
    if seconds > _exact(entry.duration_seconds):
        return {"status": "answered", "kind": "time_outside", "film": film, **rest}
    scene = next(
        (
            interval.scene
            for interval in entry.scenes
            if _exact(interval.start) <= seconds < _exact(interval.end)
        ),
        entry.scenes[-1].scene,
    )
    return {"status": "answered", "kind": "scene", "scene": scene, **rest}


def _judge(source: StudySource, stated: dict[str, Any]) -> dict[str, Any]:
    if stated["status"] == "failed":
        reason = stated["reason"]
        return {"kind": reason} if reason in _FAILURES else {"kind": "invalid", "reason": reason}
    kind = stated["kind"]
    if kind == "none":
        return {"kind": "none"}
    collections = {entry.number: entry for entry in source.collections}
    if kind == "film":
        film = stated["film"]
        if film not in collections or not collections[film].target:
            return {"kind": "unknown-film", "stated": str(film)}
        return {"kind": "film", "collection": film}
    if kind == "time_outside":
        return {"kind": "time-outside", "collection": stated["film"]}
    scene = stated["scene"]
    scenes = {entry.number: entry for entry in source.scenes}
    entry = scenes.get(scene)
    if entry is None:
        return {"kind": "unknown-scene", "stated": str(scene)}
    if not entry.target:
        return {"kind": "invalid-scene", "scene": scene, "collection": entry.collection}
    return {"kind": "scene", "scene": scene, "collection": entry.collection}


def resolve_time_item(
    source: StudySource, item: dict[str, Any] | None, *, failure: str = "omitted"
) -> dict[str, Any]:
    """Replay and judge one strict-chunked-time reply without reading narrative source text."""
    checked = StudySource.model_validate(source.model_dump(by_alias=True))
    if item is None:
        stated = _failed(failure)
        missing = {"stated": stated, "judgement": _judge(checked, stated)}
        return {
            "referent": missing,
            "narrative": {"stated": stated.copy(), "judgement": missing["judgement"].copy()},
            "extras": {},
        }

    def resolve_slot(field: str) -> dict[str, Any]:
        if not isinstance(item, dict) or f"{field}_film" not in item or f"{field}_time" not in item:
            stated = _failed("missing-field")
        else:
            stated = _prediction(checked, item[f"{field}_film"], item[f"{field}_time"])
        return {"stated": stated, "judgement": _judge(checked, stated)}

    extras = {"conf": item["conf"]} if "conf" in item else {}
    return {
        "referent": resolve_slot("referent"),
        "narrative": resolve_slot("narrative"),
        "extras": extras,
    }
