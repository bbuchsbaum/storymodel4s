"""Offline recorded detail admission: retain raw replies before resolving local proposals."""

from hashlib import sha256
from pathlib import Path
from typing import Literal

from .artifacts import publish_json
from .detail_admission import (
    RECIPE,
    DetailAdmissionResult,
    DetailRequest,
    admit_details,
    detail_prompt,
    detail_request_digest,
    validate_detail_request,
)
from .execution import RecordedRun, RunProfile
from .model import Digest, Model, Name, canonical
from .serialization import strict_json


class DetailRecording(Model):
    """One exported raw detail reply, bound to the exact private detail request."""

    schema_version: Literal["storymodel.detail-recording/v1"] = "storymodel.detail-recording/v1"
    request_sha256: Digest
    origin: Name
    reply: str


def _exportable(request: DetailRequest) -> DetailRequest:
    checked = validate_detail_request(request)
    if checked.account.export_permission != "allowed":
        raise PermissionError("producer denies detail admission export")
    return checked


def _profile(request: DetailRequest) -> RunProfile:
    return RunProfile(
        provider="imported-text",
        model="supplied-detail-admission/v1",
        endpoint="offline:import",
        max_tokens=8192,
        recipe=RECIPE,
        input_sha256=(detail_request_digest(request),),
    )


def _prompt(request: DetailRequest) -> str:
    """Render a provider-facing prompt only after its account has granted export."""
    return detail_prompt(_exportable(request))


def read_detail_request(path: Path, *, expected_sha256: str | None = None) -> DetailRequest:
    """Read a strict detail request, optionally pinned to externally supplied bytes."""
    raw = path.read_bytes()
    if expected_sha256 is not None and sha256(raw).hexdigest() != expected_sha256:
        raise ValueError("detail request differs from external SHA-256")
    return validate_detail_request(raw)


def import_details(value: DetailRequest, recording: DetailRecording, root: Path) -> None:
    """Store imported raw text before parse; this path never invokes a provider."""
    request = _exportable(value)
    supplied = DetailRecording.model_validate(recording)
    if supplied.request_sha256 != detail_request_digest(request):
        raise ValueError("detail recording belongs to another request")
    root.mkdir(parents=True, exist_ok=False)
    RecordedRun(root, _profile(request)).run(
        (_prompt(request),), transport=lambda _: supplied.reply, max_new_calls=1
    )
    publish_json(root / "import.json", supplied.model_dump(mode="json"))


def replay_details(
    value: DetailRequest,
    root: Path,
    *,
    expected_import_sha256: str | None = None,
) -> DetailAdmissionResult:
    """Resolve the raw response offline after checking its request and cache bindings."""
    request = _exportable(value)
    raw_import = (root / "import.json").read_bytes()
    if (
        expected_import_sha256 is not None
        and sha256(raw_import).hexdigest() != expected_import_sha256
    ):
        raise ValueError("detail import differs from external SHA-256")
    recording = DetailRecording.model_validate_json(canonical(strict_json(raw_import)))
    if recording.request_sha256 != detail_request_digest(request):
        raise ValueError("detail recording belongs to another request")
    raw = RecordedRun(root, _profile(request)).run((_prompt(request),))[0]
    if raw != recording.reply:
        raise ValueError("cached detail differs from imported raw reply")
    return admit_details(request, raw)
