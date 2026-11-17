"""Offline recorded extraction: import raw replies before interpreting their contents."""

from hashlib import sha256
from pathlib import Path
from typing import Literal

from .artifacts import publish_json
from .execution import RecordedRun, RunProfile
from .extraction import (
    RECIPE,
    ExtractionProposal,
    ExtractionRequest,
    ExtractionResult,
    request_digest,
    resolve_extraction,
    validate_request,
)
from .model import Digest, Model, Name, canonical
from .study_workflow import strict_json


class ExtractionRecording(Model):
    """An exported raw reply bound to one independently prepared extraction request."""

    schema_version: Literal["storymodel.extraction-recording/v1"] = (
        "storymodel.extraction-recording/v1"
    )
    request_sha256: Digest
    origin: Name
    reply: str


def extraction_prompt(value: ExtractionRequest) -> str:
    """Return deterministic provider-facing inputs; source semantics are deliberately absent."""
    request = validate_request(value)
    return canonical(
        {
            "recipe": RECIPE,
            "request_sha256": request_digest(request),
            "recall": request.recall.model_dump(mode="json"),
            "schema": ExtractionProposal.model_json_schema(),
            "instruction": (
                "Return the extraction-proposal/v1 JSON envelope with explicit schema_version. "
                "Use global canonical UTF-16 offsets and exact quoted text. Keys are local to each "
                "unit; narrated is the root context. Context cues and content scopes differ. "
                "Every event names its context; all event evidence must fit that scope without "
                "crossing attributed content. Entity mentions and role evidence must coincide. "
                "Do not infer source identity, target address, fidelity, coreference, normalized "
                "predicates, or probabilities. Use unresolved claims for unsupported choices and "
                "explicit processing status for incomplete extraction."
            ),
        }
    )


def _profile(request: ExtractionRequest) -> RunProfile:
    return RunProfile(
        provider="imported-text",
        model="supplied-extraction/v1",
        endpoint="offline:import",
        max_tokens=8192,
        recipe=RECIPE,
        input_sha256=(request_digest(request),),
    )


def read_extraction_request(path: Path, *, expected_sha256: str | None = None) -> ExtractionRequest:
    raw = path.read_bytes()
    if expected_sha256 is not None and sha256(raw).hexdigest() != expected_sha256:
        raise ValueError("extraction request differs from external SHA-256")
    return validate_request(raw)


def import_extraction(value: ExtractionRequest, recording: ExtractionRecording, root: Path) -> None:
    """Record even malformed proposal text; an imported reply is never a live provider call."""
    request = validate_request(value)
    supplied = ExtractionRecording.model_validate_json(canonical(recording.model_dump(mode="json")))
    if supplied.request_sha256 != request_digest(request):
        raise ValueError("extraction recording belongs to another request")
    root.mkdir(parents=True, exist_ok=False)
    RecordedRun(root, _profile(request)).run(
        (extraction_prompt(request),),
        transport=lambda _: supplied.reply,
        max_new_calls=1,
    )
    publish_json(root / "import.json", supplied.model_dump(mode="json"))


def replay_extraction(
    value: ExtractionRequest,
    root: Path,
    *,
    expected_import_sha256: str | None = None,
) -> ExtractionResult:
    """Replay the raw response offline and deterministically construct a checked account."""
    request = validate_request(value)
    raw_import = (root / "import.json").read_bytes()
    if (
        expected_import_sha256 is not None
        and sha256(raw_import).hexdigest() != expected_import_sha256
    ):
        raise ValueError("extraction import differs from external SHA-256")
    recording = ExtractionRecording.model_validate_json(canonical(strict_json(raw_import)))
    if recording.request_sha256 != request_digest(request):
        raise ValueError("extraction recording belongs to another request")
    raw = RecordedRun(root, _profile(request)).run((extraction_prompt(request),))[0]
    if raw != recording.reply:
        raise ValueError("cached extraction differs from imported raw reply")
    return resolve_extraction(request, raw)
