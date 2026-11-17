"""Group declared source rows into exact scene containers before pairing or mapping."""

from typing import Literal

from pydantic import Field

from .model import Digest, Model, Name, Recipe, Span, Support, Unit, _validate_instance
from .rich_account import SupportedAccount, validate_supported_account
from .serialization import digest


class SceneGroup(Model):
    """A local label selects existing unparented rows; it does not assert a trusted identity."""

    key: Name
    rows: tuple[Name, ...] = Field(min_length=1)


class SceneGroupingResult(Model):
    """Reparenting changes row identity, so the receipt exposes every old-to-new row ID."""

    schema_version: Literal["storymodel.scene-grouping/v1"] = "storymodel.scene-grouping/v1"
    input_sha256: Digest
    groups_sha256: Digest
    scenes: dict[Name, Name]
    rows: dict[Name, Name]
    account: SupportedAccount


def group_source_rows(
    account: SupportedAccount, groups: tuple[SceneGroup, ...]
) -> SceneGroupingResult:
    """Add scenes to a source-only snapshot, preserving evidence gaps and all non-unit graph data.

    A row may be selected once and must not already have a parent. Existing scenes and
    unselected rows remain untouched. Recall/mapping snapshots cannot be silently reidentified.
    """
    checked = validate_supported_account(account)
    if not checked.documents or any(d.side != "source" for d in checked.documents):
        raise ValueError("scene grouping requires a source-only account before pairing")
    if not isinstance(groups, tuple) or not groups:
        raise ValueError("scene grouping requires a nonempty tuple of groups")
    selected = tuple(_validate_instance(SceneGroup, group) for group in groups)
    if len({group.key for group in selected}) != len(selected):
        raise ValueError("duplicate scene group key")
    units = {unit.id: unit for unit in checked.units}
    changed: dict[str, Unit] = {}
    scenes = {}
    containers = []
    for group in selected:
        members = []
        seen = set()
        for row_id in group.rows:
            row = units.get(row_id)
            if row is None or row.kind != "row" or row.parent is not None:
                raise ValueError("scene members must be existing unparented source rows")
            if row_id in seen or row_id in changed:
                raise ValueError("a row can belong to only one new scene")
            seen.add(row_id)
            members.append(row)
        documents = {row.support.document for row in members}
        if len(documents) != 1:
            raise ValueError("one scene must lie in one source document")
        # Exact union, not a hull: unobserved gaps do not become source evidence.
        spans: list[Span] = []
        for span in sorted(
            (s for row in members for s in row.support.spans), key=lambda s: (s.start, s.end)
        ):
            if spans and span.start <= spans[-1].end:
                spans[-1] = Span(start=spans[-1].start, end=max(spans[-1].end, span.end))
            else:
                spans.append(span)
        scene = Unit(
            kind="scene", support=Support(document=next(iter(documents)), spans=tuple(spans))
        )
        containers.append(scene)
        scenes[group.key] = scene.id
        for row in members:
            changed[row.id] = Unit(kind="row", support=row.support, parent=scene.id)
    input_sha = digest(checked.model_dump(mode="json"))
    groups_sha = digest([group.model_dump(mode="json") for group in selected])
    output = validate_supported_account(
        checked.model_copy(
            update={
                "units": (*[changed.get(unit.id, unit) for unit in checked.units], *containers),
                "recipe": Recipe(
                    name="source-row-scene-grouping/v1",
                    input_sha256=tuple(
                        dict.fromkeys(
                            [
                                *(d.sha256 for d in checked.documents),
                                input_sha,
                                groups_sha,
                            ]
                        )
                    ),
                ),
            }
        )
    )
    return SceneGroupingResult(
        input_sha256=input_sha,
        groups_sha256=groups_sha,
        scenes=scenes,
        rows={old: new.id for old, new in changed.items()},
        account=output,
    )


def verify_scene_grouping(
    account: SupportedAccount, groups: tuple[SceneGroup, ...], result: SceneGroupingResult
) -> SceneGroupingResult:
    """Recompute scene supports, membership and every identity change against the original input."""
    checked = _validate_instance(SceneGroupingResult, result)
    expected = group_source_rows(account, groups)
    if checked != expected:
        raise ValueError("scene grouping differs from its original inputs")
    return expected
