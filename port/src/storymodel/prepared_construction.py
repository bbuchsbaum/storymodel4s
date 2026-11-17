"""Bind raw study intake to existing rich construction without changing graph identity."""

from __future__ import annotations

from hashlib import sha256
from pathlib import Path
from typing import Literal, Self

from pydantic import model_validator

from .artifacts import publish_json
from .execution import RecordedRun, RunProfile
from .model import Digest, Model, Name, Recipe, Span, _validate_instance
from .recall_intake import PreparedRecall, prepare_recall_utf8, validate_prepared_recall
from .rich_account import RichAccount, validate_rich_account
from .rich_construction import (
    RichConstructionRequest,
    RichConstructionResult,
    compile_rich_construction,
    prepare_rich_construction,
)
from .rich_workflow import rich_prompt
from .serialization import canonical, digest, strict_json
from .sherlock_recall import SherlockRecall, validate_sherlock_recall
from .sherlock_source import SherlockSource, validate_sherlock_source
from .source import SourceRow
from .source_preparation import PreparedSource, prepare_source_utf8, validate_prepared_source


class PreparedConstruction(Model):
    """Actual raw intake and explicit policy determine the construction request."""

    schema_version: Literal[
        "storymodel.prepared-construction/v1", "storymodel.prepared-construction/v2"
    ] = "storymodel.prepared-construction/v1"
    intake: PreparedRecall | PreparedSource | SherlockRecall | SherlockSource
    full_contexts: bool = False
    export_permission: Literal["allowed", "denied"] = "denied"

    @model_validator(mode="after")
    def dependencies(self) -> Self:
        study_intake = isinstance(self.intake, (SherlockRecall, SherlockSource))
        if study_intake != (self.schema_version == "storymodel.prepared-construction/v2"):
            raise ValueError(
                "Sherlock intake requires prepared-construction/v2; generic intake uses v1"
            )
        if isinstance(self.intake, SherlockRecall):
            validate_sherlock_recall(self.intake)
        elif isinstance(self.intake, SherlockSource):
            validate_sherlock_source(self.intake)
        elif isinstance(self.intake, PreparedRecall):
            validate_prepared_recall(self.intake)
        else:
            validate_prepared_source(self.intake)
        return self

    @property
    def request(self) -> RichConstructionRequest:
        """Recompute covered rows; occurrence evidence remains in the intake receipt."""
        checked = validate_prepared_construction(self)
        intake = (
            checked.intake.prepared
            if isinstance(checked.intake, (SherlockRecall, SherlockSource))
            else checked.intake
        )
        document = intake.document
        if isinstance(intake, PreparedRecall):
            ends = [unit.support.start for unit in intake.units[1:]] + [
                len(document.text.encode("utf-16-le")) // 2
            ]
            rows = tuple(
                SourceRow(
                    key=f"recall-{unit.u}",
                    span=Span(start=unit.support.start if index else 0, end=end),
                )
                for index, (unit, end) in enumerate(zip(intake.units, ends, strict=True))
            )
        else:
            rows = intake.rows
        return prepare_rich_construction(
            document.key,
            document.side,
            document.text,
            rows,
            export_permission=checked.export_permission,
            full_contexts=checked.full_contexts,
        )


def validate_prepared_construction(
    value: PreparedConstruction | str | bytes,
) -> PreparedConstruction:
    """Recheck derived fields and Python types before public consumption or export."""
    if isinstance(value, PreparedConstruction):
        return _validate_instance(PreparedConstruction, value)
    return PreparedConstruction.model_validate_json(canonical(strict_json(value)))


def prepare_construction(
    key: str,
    side: Literal["source", "recall"],
    raw: bytes,
    *,
    full_contexts: bool = False,
    export_permission: Literal["allowed", "denied"] = "denied",
) -> PreparedConstruction:
    """Prepare exact source-annotation JSON or raw recall UTF-8, independently per side."""
    if side not in ("source", "recall"):
        raise ValueError("construction side must be source or recall")
    return PreparedConstruction(
        intake=prepare_source_utf8(key, raw) if side == "source" else prepare_recall_utf8(key, raw),
        full_contexts=full_contexts,
        export_permission=export_permission,
    )


def prepare_sherlock_construction(
    intake: SherlockSource | SherlockRecall,
    *,
    full_contexts: bool = False,
    export_permission: Literal["allowed", "denied"] = "denied",
) -> PreparedConstruction:
    """Bind actual study-format receipts to the existing construction/analysis workflow."""
    if not isinstance(intake, (SherlockSource, SherlockRecall)):
        raise ValueError("Sherlock construction requires checked source or recall intake")
    return PreparedConstruction(
        schema_version="storymodel.prepared-construction/v2",
        intake=intake,
        full_contexts=full_contexts,
        export_permission=export_permission,
    )


def _provenance_account(prepared_sha256: str, result: RichConstructionResult) -> RichAccount:
    account = result.account
    return validate_rich_account(
        account.model_copy(
            update={
                "recipe": Recipe(
                    name="prepared-rich-admission/v1",
                    input_sha256=(
                        *account.recipe.input_sha256,
                        prepared_sha256,
                        digest(result.model_dump(mode="json")),
                    ),
                )
            }
        )
    )


class PreparedConstructionResult(Model):
    """Retain the untouched inner receipt alongside the intake-bound downstream account."""

    schema_version: Literal["storymodel.prepared-construction-result/v1"] = (
        "storymodel.prepared-construction-result/v1"
    )
    prepared_sha256: Digest
    construction: RichConstructionResult
    account: RichAccount

    @model_validator(mode="after")
    def provenance(self) -> Self:
        if self.account != _provenance_account(self.prepared_sha256, self.construction):
            raise ValueError("prepared account differs from construction and intake provenance")
        return self


def compile_prepared_construction(
    value: PreparedConstruction, raw: str
) -> PreparedConstructionResult:
    """Use the existing deterministic graph resolver, then bind the original intake."""
    prepared = validate_prepared_construction(value)
    result = compile_rich_construction(prepared.request, raw)
    prepared_sha = digest(prepared.model_dump(mode="json"))
    return PreparedConstructionResult(
        prepared_sha256=prepared_sha,
        construction=result,
        account=_provenance_account(prepared_sha, result),
    )


def verify_prepared_construction(
    value: PreparedConstruction, raw: str, receipt: PreparedConstructionResult
) -> PreparedConstructionResult:
    """Verify both graph admission and raw-intake provenance from their actual inputs."""
    checked = _validate_instance(PreparedConstructionResult, receipt)
    expected = compile_prepared_construction(value, raw)
    if checked != expected:
        raise ValueError("prepared construction differs from original intake or reply")
    return expected


class PreparedConstructionRecording(Model):
    """A supplied reply belongs to the entire prepared input, not only canonical text."""

    schema_version: Literal["storymodel.prepared-construction-recording/v1"] = (
        "storymodel.prepared-construction-recording/v1"
    )
    prepared_sha256: Digest
    origin: Name
    reply: str


def _checked(value: PreparedConstruction) -> PreparedConstruction:
    prepared = validate_prepared_construction(value)
    if prepared.export_permission != "allowed":
        raise PermissionError("producer denies prepared construction export")
    return prepared


def prepared_construction_prompt(value: PreparedConstruction) -> str:
    """Render the existing graph contract with an explicit full-intake recording key."""
    prepared = _checked(value)
    return canonical(
        {
            "prepared_sha256": digest(prepared.model_dump(mode="json")),
            "construction": strict_json(rich_prompt(prepared.request)),
        }
    )


def _profile(value: PreparedConstruction) -> RunProfile:
    return RunProfile(
        provider="imported-text",
        model="supplied-prepared-construction/v1",
        endpoint="offline:import",
        max_tokens=8192,
        recipe="prepared-rich-admission/v1",
        input_sha256=(digest(value.model_dump(mode="json")),),
    )


def read_prepared_construction(
    path: Path, *, expected_sha256: str | None = None
) -> PreparedConstruction:
    """Read exact bytes, optionally pinned by an independent file checksum."""
    raw = path.read_bytes()
    if expected_sha256 is not None and sha256(raw).hexdigest() != expected_sha256:
        raise ValueError("prepared construction differs from external SHA-256")
    return validate_prepared_construction(raw)


def import_prepared_construction(
    value: PreparedConstruction, recording: PreparedConstructionRecording, root: Path
) -> None:
    """Persist raw replies before parsing; refuse existing or interrupted directories."""
    prepared = _checked(value)
    supplied = _validate_instance(PreparedConstructionRecording, recording)
    if supplied.prepared_sha256 != digest(prepared.model_dump(mode="json")):
        raise ValueError("recording belongs to another prepared input")
    root.mkdir(parents=True, exist_ok=False)
    RecordedRun(root, _profile(prepared)).run(
        (prepared_construction_prompt(prepared),),
        transport=lambda _: supplied.reply,
        max_new_calls=1,
    )
    publish_json(root / "import.json", supplied.model_dump(mode="json"))


def replay_prepared_construction(
    value: PreparedConstruction, root: Path, *, expected_import_sha256: str | None = None
) -> PreparedConstructionResult:
    """Recheck the raw-input recording and replay graph admission without provider calls."""
    prepared = _checked(value)
    imported = (root / "import.json").read_bytes()
    if (
        expected_import_sha256 is not None
        and sha256(imported).hexdigest() != expected_import_sha256
    ):
        raise ValueError("prepared construction import differs from external SHA-256")
    recording = PreparedConstructionRecording.model_validate_json(canonical(strict_json(imported)))
    if recording.prepared_sha256 != digest(prepared.model_dump(mode="json")):
        raise ValueError("recording belongs to another prepared input")
    raw = RecordedRun(root, _profile(prepared)).run((prepared_construction_prompt(prepared),))[0]
    if raw != recording.reply:
        raise ValueError("cached prepared construction differs from imported raw reply")
    return compile_prepared_construction(prepared, raw)
