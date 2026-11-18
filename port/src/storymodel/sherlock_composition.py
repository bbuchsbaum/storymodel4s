"""Retain Sherlock's native row evidence while composing its declared presentation order."""

from typing import Literal, Self

from pydantic import Field, model_validator

from ._base import Digest, Model, Name, _validate_instance
from .composition import (
    CompositionPart,
    CompositionReadout,
    OccurrenceSupport,
    PlaybackComposition,
    compose_support,
    concatenate_playback,
    project_composition,
)
from .playback import MediaSource, PlaybackSupport, TickInterval, union_playback
from .serialization import digest
from .sherlock_source import SherlockSource, validate_sherlock_source


class ComposedSourceUnit(Model):
    """A row or scene retains its occurrence-qualified native and composed supports."""

    key: Name
    anchors: tuple[OccurrenceSupport, ...] = Field(min_length=1)
    support: PlaybackSupport


def _native_row_supports(
    source: SherlockSource,
) -> tuple[tuple[MediaSource, ...], dict[str, PlaybackSupport]]:
    """Project checked annotation rows on their declared physical part clocks."""
    axes = {
        run["partId"]: run["axisId"]
        for run in source.repair["annotationToPlaybackCrosswalk"]["runs"]
    }
    ordered = sorted(source.parts, key=lambda part: part.ordinal)
    media = tuple(
        MediaSource(
            key=part.part_id,
            side="source",
            sha256=part.sha256,
            edition=source.repair["presentationEditionIdentity"]["id"],
            stream=f"{part.part_id}/video",
            clock=axes[part.part_id],
            stream_kind="picture",
            start_tick="0",
            end_tick=str(part.duration_ticks),
            seconds_per_tick=f"1/{part.ticks_per_second}",
        )
        for part in ordered
    )
    by_part = {part.key: part.id for part in media}
    native_rows = {
        f"sherlock:row:{row.row:04d}": PlaybackSupport(
            source=by_part[locus.part_id],
            intervals=(TickInterval(start=str(locus.start_tick), end=str(locus.end_tick)),)
            if locus.kind == "extent"
            else (),
            points=(str(locus.start_tick),) if locus.kind == "instant" else (),
        )
        for row, locus in zip(source.rows, source.loci, strict=True)
    }
    return media, native_rows


def _derive(
    source: SherlockSource,
) -> tuple[PlaybackComposition, tuple[ComposedSourceUnit, ...], tuple[ComposedSourceUnit, ...]]:
    """Derive geometry only from an already checked intake, never notebook offsets."""
    media, native_rows = _native_row_supports(source)
    composition = concatenate_playback(
        source.key,
        media,
        tuple(
            CompositionPart(
                source=part.id,
                native=TickInterval(start=part.start_tick, end=part.end_tick),
            )
            for part in media
        ),
    )
    occurrences = {segment.source: segment.occurrence for segment in composition.segments}
    rows: list[ComposedSourceUnit] = []
    for key, native in native_rows.items():
        anchor = compose_support(composition, occurrences[native.source], native)
        rows.append(ComposedSourceUnit(key=key, anchors=(anchor,), support=anchor.composed))
    scenes = tuple(
        ComposedSourceUnit(
            key=f"sherlock:scene:{scene.ordinal:03d}",
            anchors=tuple(
                anchor
                for row in rows[scene.first_row - 1 : scene.last_row]
                for anchor in row.anchors
            ),
            support=union_playback(
                row.support for row in rows[scene.first_row - 1 : scene.last_row]
            ),
        )
        for scene in source.scenes
    )
    return composition, tuple(rows), scenes


class SherlockComposition(Model):
    """A replayable composition receipt binds raw annotations, repair, media and every unit."""

    schema_version: Literal["storymodel.sherlock-composition/v1"] = (
        "storymodel.sherlock-composition/v1"
    )
    source: SherlockSource
    export_permission: Literal["allowed", "denied"] = "denied"
    composition: PlaybackComposition
    rows: tuple[ComposedSourceUnit, ...] = Field(min_length=1)
    scenes: tuple[ComposedSourceUnit, ...] = Field(min_length=1)

    @model_validator(mode="after")
    def derived(self) -> Self:
        composition, rows, scenes = _derive(self.source)
        if (self.composition, self.rows, self.scenes) != (composition, rows, scenes):
            raise ValueError("Sherlock composition differs from its actual source intake")
        return self


def compose_sherlock_source(
    value: SherlockSource, *, export_permission: Literal["allowed", "denied"] = "denied"
) -> SherlockComposition:
    """Concatenate complete native parts, preserving unannotated tails and row points."""
    source = validate_sherlock_source(value)
    composition, rows, scenes = _derive(source)
    return SherlockComposition(
        source=source,
        export_permission=export_permission,
        composition=composition,
        rows=rows,
        scenes=scenes,
    )


def validate_sherlock_composition(value: SherlockComposition) -> SherlockComposition:
    """Rebuild all native/composed correspondences from the actual retained raw receipts."""
    return _validate_instance(SherlockComposition, value)


class SherlockCompositionReadout(Model):
    """Selected row/scene exposure is a union, with separate explicit point observations."""

    schema_version: Literal["storymodel.sherlock-composition-readout/v1"] = (
        "storymodel.sherlock-composition-readout/v1"
    )
    input_sha256: Digest
    units: tuple[Name, ...] = Field(min_length=1)
    playback: CompositionReadout


def project_sherlock_composition(
    value: SherlockComposition,
    units: tuple[str, ...] = (),
    bins: tuple[TickInterval, ...] = (),
) -> SherlockCompositionReadout:
    """Measure selected rows/scenes on the composed axis; an empty selection means all rows."""
    checked = validate_sherlock_composition(value)
    if checked.export_permission != "allowed":
        raise PermissionError("producer denies Sherlock composition readout export")
    if not isinstance(units, tuple) or not all(isinstance(key, str) for key in units):
        raise ValueError("unit selection must be a tuple of row or scene keys")
    if len(set(units)) != len(units):
        raise ValueError("unit selection contains duplicates")
    selected = tuple(sorted(units)) if units else tuple(row.key for row in checked.rows)
    lookup = {unit.key: unit for unit in (*checked.rows, *checked.scenes)}
    if any(key not in lookup for key in selected):
        raise ValueError("unit selection names an unknown row or scene")
    support = union_playback(lookup[key].support for key in selected)
    return SherlockCompositionReadout(
        input_sha256=digest(checked.model_dump(mode="json")),
        units=selected,
        playback=project_composition(checked.composition, support, bins),
    )


def verify_sherlock_composition_readout(
    value: SherlockComposition,
    units: tuple[str, ...],
    bins: tuple[TickInterval, ...],
    saved: SherlockCompositionReadout,
) -> SherlockCompositionReadout:
    """Recompute from the original intake and selection, not only a copied input hash."""
    expected = project_sherlock_composition(value, units, bins)
    if _validate_instance(SherlockCompositionReadout, saved) != expected:
        raise ValueError("Sherlock composition readout differs from actual selected evidence")
    return expected
