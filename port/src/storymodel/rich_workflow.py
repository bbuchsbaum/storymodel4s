"""Offline recorded construction of rich source or recall graphs using the common executor."""

from hashlib import sha256
from pathlib import Path
from typing import Literal

from .execution import RecordedRun, RunProfile
from .model import Digest, Model, Name, _validate_instance
from .rich_construction import (
    RichConstructionProposal,
    RichConstructionProposalV2,
    RichConstructionRequest,
    RichConstructionResult,
    compile_rich_construction,
    validate_rich_construction_request,
)
from .serialization import canonical, digest, strict_json


class RichRecording(Model):
    """Raw provider text is retained with its exact private construction input snapshot."""

    schema_version: Literal["storymodel.rich-recording/v1"] = "storymodel.rich-recording/v1"
    request_sha256: Digest
    origin: Name
    reply: str


def _checked(value: RichConstructionRequest) -> RichConstructionRequest:
    request = validate_rich_construction_request(value)
    if request.export_permission != "allowed":
        raise PermissionError("producer denies rich construction export")
    return request


def rich_prompt(value: RichConstructionRequest) -> str:
    """Render the explicitly versioned constructor contract without changing legacy prompts."""
    request = _checked(value)
    full_contexts = request.schema_version == "storymodel.rich-construction-request/v2"
    return canonical(
        {
            "recipe": request.recipe,
            "request": request.model_dump(mode="json"),
            "schema": (
                RichConstructionProposalV2.model_json_schema()
                if full_contexts
                else RichConstructionProposal.model_json_schema()
            ),
            "instruction": (
                (
                    "Return rich-construction-proposal/v2 with explicit schema_version and "
                    "every row. Use exact quoted UTF-16 evidence and row-local proposal keys. "
                    "Narrated is the root. Context scopes must nest strictly or be disjoint; "
                    "every situation stays in its deepest declared scope. Do not promote "
                    "attributed content to root-world fact. Additional context kinds are "
                    "desire, memory, imagination, hypothetical and counterfactual. "
                    "Hypothetical and counterfactual frames have no holder; do not treat them "
                    "as unknown speakers. States represent conditions, events occurrences; "
                    "declare state polarity and modality, using unknown when unavailable. "
                    "Location is a participant role of an existing local entity. Circumstances "
                    "are literal time/manner phrases within the situation evidence, not measured "
                    "clocks, dates or durations. No cross-document identity or recall-to-source "
                    "binding is inferred. State unsupported/failed/unattempted/search-limited "
                    "work explicitly. This profile constructs graph claims, not recall detail "
                    "inventories."
                )
                if full_contexts
                else (
                    "Return rich-construction-proposal/v1 with explicit schema_version and "
                    "every row. "
                    "Use exact quoted UTF-16 evidence, local proposal keys, and the deepest "
                    "declared "
                    "context scope. Narrated is the root; do not promote attributed content to it. "
                    "States represent conditions, events occurrences; declare state polarity "
                    "and modality, using unknown when unavailable. Location is a participant "
                    "role of "
                    "an existing local entity. Circumstances are literal time/manner phrases "
                    "within the named situation evidence; do not interpret them as measured "
                    "clocks, "
                    "dates or durations. No cross-document identity or recall-to-source binding is "
                    "inferred. State unsupported/failed/unattempted/search-limited work "
                    "explicitly. "
                    "This profile constructs graph claims, not recall detail inventories."
                )
            ),
        }
    )


def _profile(request: RichConstructionRequest) -> RunProfile:
    return RunProfile(
        provider="imported-text",
        model="supplied-rich-construction/v2"
        if request.schema_version == "storymodel.rich-construction-request/v2"
        else "supplied-rich-construction/v1",
        endpoint="offline:import",
        max_tokens=8192,
        recipe=request.recipe,
        input_sha256=(digest(request.model_dump(mode="json")),),
    )


def read_rich_request(path: Path, *, expected_sha256: str | None = None) -> RichConstructionRequest:
    """Read a checked request, optionally bound to an external byte digest."""
    raw = path.read_bytes()
    if expected_sha256 is not None and sha256(raw).hexdigest() != expected_sha256:
        raise ValueError("rich request differs from external SHA-256")
    return validate_rich_construction_request(raw)


def import_rich(
    value: RichConstructionRequest, recording: RichRecording, root: Path, *, recover: bool = False
) -> None:
    """Save the raw reply before parsing, with explicit exact-intent recovery."""
    request = _checked(value)
    supplied = _validate_instance(RichRecording, recording)
    if supplied.request_sha256 != digest(request.model_dump(mode="json")):
        raise ValueError("rich recording belongs to another request")
    RecordedRun(root, _profile(request)).import_replies(
        (rich_prompt(request),),
        (supplied.reply,),
        supplied.model_dump(mode="json"),
        recover=recover,
    )


def replay_rich(
    value: RichConstructionRequest, root: Path, *, expected_import_sha256: str | None = None
) -> RichConstructionResult:
    """Reconstruct offline after checking the recording against its exact request and cache."""
    request = _checked(value)
    imported = (root / "import.json").read_bytes()
    if (
        expected_import_sha256 is not None
        and sha256(imported).hexdigest() != expected_import_sha256
    ):
        raise ValueError("rich import differs from external SHA-256")
    recording = RichRecording.model_validate_json(canonical(strict_json(imported)))
    if recording.request_sha256 != digest(request.model_dump(mode="json")):
        raise ValueError("rich recording belongs to another request")
    raw = RecordedRun(root, _profile(request)).run((rich_prompt(request),))[0]
    if raw != recording.reply:
        raise ValueError("cached rich construction differs from imported raw reply")
    return compile_rich_construction(request, raw)
