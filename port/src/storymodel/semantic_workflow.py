"""Record supplied embedding replies before parsing; replay never calls a provider."""

from hashlib import sha256
from pathlib import Path
from typing import Literal

from .artifacts import publish_json
from .execution import RecordedRun, RunProfile
from .model import Digest, Model, Name, _validate_instance
from .semantic import (
    RECIPE,
    SemanticRequest,
    SemanticResult,
    resolve_semantic,
    semantic_prompt,
    semantic_request_digest,
    validate_semantic_request,
)
from .serialization import canonical, strict_json


class SemanticRecording(Model):
    schema_version: Literal["storymodel.semantic-recording/v1"] = "storymodel.semantic-recording/v1"
    request_sha256: Digest
    origin: Name
    reply: str


def _exportable(value: SemanticRequest) -> SemanticRequest:
    request = validate_semantic_request(value)
    if (
        request.source.export_permission != "allowed"
        or request.recall.export_permission != "allowed"
    ):
        raise PermissionError("producer denies semantic embedding export")
    return request


def _profile(request: SemanticRequest) -> RunProfile:
    return RunProfile(
        provider="imported-embeddings",
        model="supplied-surface-embeddings/v1",
        endpoint="offline:import",
        max_tokens=8192,
        recipe=RECIPE,
        input_sha256=(semantic_request_digest(request),),
    )


def read_semantic_request(path: Path) -> SemanticRequest:
    return validate_semantic_request(path.read_bytes())


def import_semantic(value: SemanticRequest, recording: SemanticRecording, root: Path) -> None:
    request = _exportable(value)
    supplied = _validate_instance(SemanticRecording, recording)
    if supplied.request_sha256 != semantic_request_digest(request):
        raise ValueError("semantic recording belongs to another request")
    root.mkdir(parents=True, exist_ok=False)
    RecordedRun(root, _profile(request)).run(
        (semantic_prompt(request),), transport=lambda _: supplied.reply, max_new_calls=1
    )
    publish_json(root / "import.json", supplied.model_dump(mode="json"))


def replay_semantic(
    value: SemanticRequest, root: Path, *, expected_import_sha256: str | None = None
) -> SemanticResult:
    request = _exportable(value)
    raw_import = (root / "import.json").read_bytes()
    if (
        expected_import_sha256 is not None
        and sha256(raw_import).hexdigest() != expected_import_sha256
    ):
        raise ValueError("semantic import differs from external SHA-256")
    recording = SemanticRecording.model_validate_json(canonical(strict_json(raw_import)))
    if recording.request_sha256 != semantic_request_digest(request):
        raise ValueError("semantic recording belongs to another request")
    raw = RecordedRun(root, _profile(request)).run((semantic_prompt(request),))[0]
    if raw != recording.reply:
        raise ValueError("cached embedding reply differs from imported exact reply")
    return resolve_semantic(request, raw)
