"""Offline recorded direct mapping: retain raw replies before resolving local proposals."""

from hashlib import sha256
from pathlib import Path
from typing import Literal

from .direct_mapping import (
    MappingInput,
    MappingOutput,
    NativeMappingRequest,
    mapping_prompt,
    mapping_recipe,
    mapping_request_digest,
    resolve_mapping,
    validate_mapping_request,
)
from .execution import RecordedRun, RunProfile
from .model import Digest, Model, Name, _validate_instance, canonical
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
        model="supplied-native-direct-mapping/v1"
        if isinstance(request, NativeMappingRequest)
        else "supplied-direct-mapping/v1",
        endpoint="offline:import",
        max_tokens=8192,
        recipe=mapping_recipe(request),
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


def import_mapping(
    value: MappingInput, recording: MappingRecording, root: Path, *, recover: bool = False
) -> None:
    """Store imported raw text before parse; this path never invokes a provider."""
    request = _exportable(value)
    supplied = _validate_instance(MappingRecording, recording)
    if supplied.request_sha256 != mapping_request_digest(request):
        raise ValueError("mapping recording belongs to another request")
    RecordedRun(root, _profile(request)).import_replies(
        (_prompt(request),),
        (supplied.reply,),
        supplied.model_dump(mode="json"),
        recover=recover,
    )


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
