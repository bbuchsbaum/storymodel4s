"""Join independently prepared source clocks and raw recall to the existing replay path."""

from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from .model import Model, canonical, digest
from .recall_intake import PreparedRecall, prepare_recall_utf8, validate_prepared_recall
from .source_intake import NativeSource, compile_source, native_source_digest
from .study_workflow import SuppliedRecall, SuppliedUnit, TimeStudy, strict_json


class PreparedTimeStudy(Model):
    """A replayable preparation artifact; derived requests are recomputed, never trusted fields."""

    schema_version: Literal["storymodel.prepared-time-study/v1"] = (
        "storymodel.prepared-time-study/v1"
    )
    native_source: NativeSource
    recall: PreparedRecall
    chunk_size: Annotated[int, Field(gt=0)] = 50
    max_attempts: Annotated[int, Field(ge=1, le=3)] = 1
    export_permission: Literal["allowed", "denied"] = "denied"

    @model_validator(mode="after")
    def dependencies(self) -> Self:
        compile_source(self.native_source)
        validate_prepared_recall(self.recall)
        return self

    @property
    def request(self) -> TimeStudy:
        """Recheck the exact input/derivation relation at notebook and replay boundaries."""
        checked = validate_prepared_study(self)
        return TimeStudy(
            admission={
                "preparation": checked.schema_version,
                "canonical_native_source_sha256": native_source_digest(checked.native_source),
                "recall_raw_sha256": checked.recall.raw_sha256,
                "recall_preparation_sha256": digest(checked.recall.model_dump(mode="json")),
                "segmentation_recipe": checked.recall.recipe,
            },
            source_key=checked.native_source.key,
            source=compile_source(checked.native_source),
            recall=SuppliedRecall(
                key=checked.recall.key,
                units=tuple(
                    SuppliedUnit(u=u.u, row=u.u, text=u.text) for u in checked.recall.units
                ),
            ),
            chunk_size=checked.chunk_size,
            max_attempts=checked.max_attempts,
            export_permission=checked.export_permission,
        )


def validate_prepared_study(value: PreparedTimeStudy) -> PreparedTimeStudy:
    return PreparedTimeStudy.model_validate_json(canonical(value.model_dump(mode="json")))


def prepare_time_study(
    source: NativeSource,
    recall_key: str,
    raw_recall: bytes,
    *,
    chunk_size: int = 50,
    max_attempts: int = 1,
    export_permission: Literal["allowed", "denied"] = "denied",
) -> PreparedTimeStudy:
    """Prepare each side independently, retaining native timing and exact recall evidence."""
    return PreparedTimeStudy(
        native_source=source,
        recall=prepare_recall_utf8(recall_key, raw_recall),
        chunk_size=chunk_size,
        max_attempts=max_attempts,
        export_permission=export_permission,
    )


def prepare_time_study_files(
    source_path: Path,
    recall_path: Path,
    recall_key: str,
    *,
    expected_source_sha256: str | None = None,
    expected_recall_sha256: str | None = None,
    chunk_size: int = 50,
    max_attempts: int = 1,
    export_permission: Literal["allowed", "denied"] = "denied",
) -> PreparedTimeStudy:
    """Strict file intake checks independent byte pins before deriving anything."""
    source_bytes, recall_bytes = source_path.read_bytes(), recall_path.read_bytes()
    for raw, expected, label in (
        (source_bytes, expected_source_sha256, "source"),
        (recall_bytes, expected_recall_sha256, "recall"),
    ):
        if expected is not None and hashlib.sha256(raw).hexdigest() != expected:
            raise ValueError(f"{label} bytes differ from expected external SHA-256")
    source = NativeSource.model_validate_json(canonical(strict_json(source_bytes)))
    return prepare_time_study(
        source,
        recall_key,
        recall_bytes,
        chunk_size=chunk_size,
        max_attempts=max_attempts,
        export_permission=export_permission,
    )
