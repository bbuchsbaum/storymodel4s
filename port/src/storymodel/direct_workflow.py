"""Offline recorded direct mapping: retain raw replies before resolving local proposals."""

from hashlib import sha256
from pathlib import Path
from typing import Literal

from .artifacts import publish_json
from .direct_mapping import (
    RECIPE,
    MappingInput,
    MappingOutput,
    mapping_prompt,
    mapping_request_digest,
    resolve_mapping,
    validate_mapping_request,
)
from .execution import RecordedRun, RunProfile
from .model import Digest, Model, Name, canonical
from .serialization import strict_json


class MappingRecording(Model):
    """One exported raw mapping reply, bound to the exact private mapping request."""

    schema_version: Literal["storymodel.mapping-recording/v1"] = "storymodel.mapping-recording/v1"
    request_sha256: Digest
    origin: Name
    reply: str


def _exportable(request: MappingInput) -> MappingInput:
    checked = validate_mapping_request(request)
    if checked.account.export_permission != "allowed":
        raise PermissionError("producer denies direct mapping export")
    return checked


def _profile(request: MappingInput) -> RunProfile:
    return RunProfile(
        provider="imported-text",
        model="supplied-direct-mapping/v1",
        endpoint="offline:import",
        max_tokens=8192,
        recipe=RECIPE,
        input_sha256=(mapping_request_digest(request),),
    )


def _prompt(request: MappingInput) -> str:
    """Render a provider-facing prompt only after its account has granted export."""
    return mapping_prompt(_exportable(request))


def read_mapping_request(path: Path, *, expected_sha256: str | None = None) -> MappingInput:
    """Read a strict mapping request, optionally pinned to externally supplied bytes."""
    raw = path.read_bytes()
    if expected_sha256 is not None and sha256(raw).hexdigest() != expected_sha256:
        raise ValueError("mapping request differs from external SHA-256")
    return validate_mapping_request(raw)


def import_mapping(value: MappingInput, recording: MappingRecording, root: Path) -> None:
    """Store imported raw text before parse; this path never invokes a provider."""
    request = _exportable(value)
    supplied = MappingRecording.model_validate(recording)
    if supplied.request_sha256 != mapping_request_digest(request):
        raise ValueError("mapping recording belongs to another request")
    root.mkdir(parents=True, exist_ok=False)
    RecordedRun(root, _profile(request)).run(
        (_prompt(request),), transport=lambda _: supplied.reply, max_new_calls=1
    )
    publish_json(root / "import.json", supplied.model_dump(mode="json"))


def replay_mapping(
    value: MappingInput,
    root: Path,
    *,
    expected_import_sha256: str | None = None,
) -> MappingOutput:
    """Resolve the raw response offline after checking its request and cache bindings."""
    request = _exportable(value)
    raw_import = (root / "import.json").read_bytes()
    if (
        expected_import_sha256 is not None
        and sha256(raw_import).hexdigest() != expected_import_sha256
    ):
        raise ValueError("mapping import differs from external SHA-256")
    recording = MappingRecording.model_validate_json(canonical(strict_json(raw_import)))
    if recording.request_sha256 != mapping_request_digest(request):
        raise ValueError("mapping recording belongs to another request")
    raw = RecordedRun(root, _profile(request)).run((_prompt(request),))[0]
    if raw != recording.reply:
        raise ValueError("cached mapping differs from imported raw reply")
    return resolve_mapping(request, raw)
