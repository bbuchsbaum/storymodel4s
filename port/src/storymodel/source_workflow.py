"""Offline source compilation from one recorded, request-bound provider reply."""

from hashlib import sha256
from pathlib import Path
from typing import Literal

from .artifacts import publish_json
from .execution import RecordedRun, RunProfile
from .model import Digest, Model, Name, canonical
from .serialization import strict_json
from .source import (
    RECIPE,
    SourceProposal,
    SourceRequest,
    SourceResult,
    compile_source,
    source_request_digest,
    validate_source_request,
)


class SourceRecording(Model):
    """An exported raw reply bound to one independently prepared source request."""

    schema_version: Literal["storymodel.source-recording/v1"] = "storymodel.source-recording/v1"
    request_sha256: Digest
    origin: Name
    reply: str


def source_prompt(value: SourceRequest) -> str:
    """Return the deterministic, source-bound proposal contract for offline recording."""
    request = validate_source_request(value)
    return canonical(
        {
            "recipe": RECIPE,
            "request_sha256": source_request_digest(request),
            "source": request.model_dump(mode="json"),
            "schema": SourceProposal.model_json_schema(),
            "instruction": (
                "Return the source-proposal/v1 JSON envelope with explicit schema_version. "
                "Use the supplied row keys only, global canonical UTF-16 offsets, and exact "
                "quoted text. Every event names its context; narrated is the root context. "
                "Context cues and content scopes differ; use the deepest declared content scope. "
                "Entity mentions and role evidence must coincide. Do not infer recall identity, "
                "target address, fidelity, coreference, normalized predicates, or probabilities. "
                "Use unresolved claims for unsupported choices and explicit processing status for "
                "incomplete source compilation. Unsupported rows require a reason; completed rows "
                "must contain claims or unresolved choices."
            ),
        }
    )


def _profile(request: SourceRequest) -> RunProfile:
    return RunProfile(
        provider="imported-text",
        model="supplied-source/v1",
        endpoint="offline:import",
        max_tokens=8192,
        recipe=RECIPE,
        input_sha256=(source_request_digest(request),),
    )


def read_source_request(path: Path, *, expected_sha256: str | None = None) -> SourceRequest:
    """Read and strictly validate a request, optionally pinned to its exported bytes."""
    raw = path.read_bytes()
    if expected_sha256 is not None and sha256(raw).hexdigest() != expected_sha256:
        raise ValueError("source request differs from external SHA-256")
    return validate_source_request(raw)


def import_source(value: SourceRequest, recording: SourceRecording, root: Path) -> None:
    """Record the supplied reply before parsing it; this never makes a provider call."""
    request = validate_source_request(value)
    supplied = SourceRecording.model_validate_json(canonical(recording.model_dump(mode="json")))
    if supplied.request_sha256 != source_request_digest(request):
        raise ValueError("source recording belongs to another request")
    root.mkdir(parents=True, exist_ok=False)
    RecordedRun(root, _profile(request)).run(
        (source_prompt(request),),
        transport=lambda _: supplied.reply,
        max_new_calls=1,
    )
    publish_json(root / "import.json", supplied.model_dump(mode="json"))


def replay_source(
    value: SourceRequest,
    root: Path,
    *,
    expected_import_sha256: str | None = None,
) -> SourceResult:
    """Replay a recorded reply offline and compile its source graph deterministically."""
    request = validate_source_request(value)
    raw_import = (root / "import.json").read_bytes()
    if (
        expected_import_sha256 is not None
        and sha256(raw_import).hexdigest() != expected_import_sha256
    ):
        raise ValueError("source import differs from external SHA-256")
    recording = SourceRecording.model_validate_json(canonical(strict_json(raw_import)))
    if recording.request_sha256 != source_request_digest(request):
        raise ValueError("source recording belongs to another request")
    raw = RecordedRun(root, _profile(request)).run((source_prompt(request),))[0]
    if raw != recording.reply:
        raise ValueError("cached source differs from imported raw reply")
    return compile_source(request, raw)
