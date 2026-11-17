"""Offline recorded graded fidelity: retain raw replies before resolving local proposals."""

from hashlib import sha256
from pathlib import Path
from typing import Literal

from .execution import RecordedRun, RunProfile
from .fidelity import (
    ContextFidelityRequest,
    FidelityInput,
    FidelityOutput,
    NativeFidelityRequest,
    fidelity_prompt,
    fidelity_recipe,
    fidelity_request_digest,
    resolve_fidelity,
    validate_fidelity_request,
)
from .model import Digest, Model, Name, _validate_instance, canonical
from .serialization import strict_json


class FidelityRecording(Model):
    """One exported raw fidelity reply, bound to the exact private fidelity request."""

    schema_version: Literal["storymodel.fidelity-recording/v1"] = "storymodel.fidelity-recording/v1"
    request_sha256: Digest
    origin: Name
    reply: str


def _exportable(request: FidelityInput) -> FidelityInput:
    checked = validate_fidelity_request(request)
    if checked.account.export_permission != "allowed":
        raise PermissionError("producer denies graded fidelity export")
    return checked


def _profile(request: FidelityInput) -> RunProfile:
    return RunProfile(
        provider="imported-text",
        model=(
            "supplied-native-conditional-fidelity/v1"
            if isinstance(request, NativeFidelityRequest)
            else "supplied-conditional-fidelity/v2"
            if isinstance(request, ContextFidelityRequest)
            else "supplied-conditional-fidelity/v1"
        ),
        endpoint="offline:import",
        max_tokens=8192,
        recipe=fidelity_recipe(request),
        input_sha256=(fidelity_request_digest(request),),
    )


def _prompt(request: FidelityInput) -> str:
    """Render a provider-facing prompt only after its account has granted export."""
    return fidelity_prompt(_exportable(request))


def read_fidelity_request(path: Path, *, expected_sha256: str | None = None) -> FidelityInput:
    """Read a strict fidelity request, optionally pinned to externally supplied bytes."""
    raw = path.read_bytes()
    if expected_sha256 is not None and sha256(raw).hexdigest() != expected_sha256:
        raise ValueError("fidelity request differs from external SHA-256")
    return validate_fidelity_request(raw)


def import_fidelity(
    value: FidelityInput, recording: FidelityRecording, root: Path, *, recover: bool = False
) -> None:
    """Store imported raw text before parse; this path never invokes a provider."""
    request = _exportable(value)
    supplied = _validate_instance(FidelityRecording, recording)
    if supplied.request_sha256 != fidelity_request_digest(request):
        raise ValueError("fidelity recording belongs to another request")
    RecordedRun(root, _profile(request)).import_replies(
        (_prompt(request),),
        (supplied.reply,),
        supplied.model_dump(mode="json"),
        recover=recover,
    )


def replay_fidelity(
    value: FidelityInput,
    root: Path,
    *,
    expected_import_sha256: str | None = None,
) -> FidelityOutput:
    """Resolve the raw response offline after checking its request and cache bindings."""
    request = _exportable(value)
    raw_import = (root / "import.json").read_bytes()
    if (
        expected_import_sha256 is not None
        and sha256(raw_import).hexdigest() != expected_import_sha256
    ):
        raise ValueError("fidelity import differs from external SHA-256")
    recording = FidelityRecording.model_validate_json(canonical(strict_json(raw_import)))
    if recording.request_sha256 != fidelity_request_digest(request):
        raise ValueError("fidelity recording belongs to another request")
    raw = RecordedRun(root, _profile(request)).run((_prompt(request),))[0]
    if raw != recording.reply:
        raise ValueError("cached fidelity differs from imported raw reply")
    return resolve_fidelity(request, raw)
