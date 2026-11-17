"""Offline native-observation construction uses the existing receipted execution engine."""

from hashlib import sha256
from pathlib import Path
from typing import Literal

from .artifacts import publish_json
from .execution import RecordedRun, RunProfile
from .model import Digest, Model, Name, _validate_instance
from .playback_construction import (
    RECIPE,
    PlaybackProposals,
    PlaybackRequest,
    PlaybackResult,
    compile_playback,
    validate_playback_request,
)
from .serialization import canonical, digest, strict_json


class PlaybackRecording(Model):
    """Exact supplied reply text remains bound to source identity and observation provenance."""

    schema_version: Literal["storymodel.playback-recording/v1"] = "storymodel.playback-recording/v1"
    request_sha256: Digest
    origin: Name
    reply: str


def _exportable(value: PlaybackRequest) -> PlaybackRequest:
    request = validate_playback_request(value)
    if request.export_permission != "allowed":
        raise PermissionError("producer denies playback construction export")
    return request


def playback_prompt(value: PlaybackRequest) -> str:
    """Describe supplied physical envelopes without pretending the executor inspected media."""
    request = _exportable(value)
    return canonical(
        {
            "recipe": RECIPE,
            "request": request.model_dump(mode="json"),
            "request_sha256": digest(request.model_dump(mode="json")),
            "schema": PlaybackProposals.model_json_schema(),
            "instruction": (
                "Return playback-proposals/v1 with the exact request_sha256. Account for every "
                "observation. Use row-local graph keys and support inside the observation's "
                "declared interval/point union. Gaps are unsupported; points have no duration. "
                "Predicate and entity labels are semantic descriptions, not source quotations. "
                "Context scopes must be disjoint or explicitly nested; situations use the deepest "
                "applicable scope. Never promote scoped content to the narrated world. Every role "
                "cites support inside its situation. Return unsupported, failed, not-attempted or "
                "search-limited outcomes explicitly. No source text, clock conversion, persistent "
                "entity identity, holder inference or audiovisual truth is supplied by this schema."
            ),
        }
    )


def _profile(request: PlaybackRequest) -> RunProfile:
    return RunProfile(
        provider="imported-text",
        model="supplied-playback-construction/v1",
        endpoint="offline:import",
        max_tokens=8192,
        recipe=RECIPE,
        input_sha256=(digest(request.model_dump(mode="json")),),
    )


def read_playback_request(path: Path, *, expected_sha256: str | None = None) -> PlaybackRequest:
    """Read a native request, optionally pinned by a caller-owned file digest."""
    raw = path.read_bytes()
    if expected_sha256 is not None and sha256(raw).hexdigest() != expected_sha256:
        raise ValueError("playback request differs from external SHA-256")
    return validate_playback_request(raw)


def import_playback(value: PlaybackRequest, recording: PlaybackRecording, root: Path) -> None:
    """Record exact raw proposals before parse, without invoking a media or language provider."""
    request = _exportable(value)
    supplied = _validate_instance(PlaybackRecording, recording)
    if supplied.request_sha256 != digest(request.model_dump(mode="json")):
        raise ValueError("playback recording belongs to another request")
    root.mkdir(parents=True, exist_ok=False)
    RecordedRun(root, _profile(request)).run(
        (playback_prompt(request),),
        transport=lambda _: supplied.reply,
        max_new_calls=1,
    )
    publish_json(root / "import.json", supplied.model_dump(mode="json"))


def replay_playback(
    value: PlaybackRequest,
    root: Path,
    *,
    expected_import_sha256: str | None = None,
) -> PlaybackResult:
    """Reproduce the native graph only after checking its full recording and input binding."""
    request = _exportable(value)
    imported = (root / "import.json").read_bytes()
    if (
        expected_import_sha256 is not None
        and sha256(imported).hexdigest() != expected_import_sha256
    ):
        raise ValueError("playback import differs from external SHA-256")
    recording = PlaybackRecording.model_validate_json(canonical(strict_json(imported)))
    if recording.request_sha256 != digest(request.model_dump(mode="json")):
        raise ValueError("playback recording belongs to another request")
    raw = RecordedRun(root, _profile(request)).run((playback_prompt(request),))[0]
    if raw != recording.reply:
        raise ValueError("cached playback construction differs from imported raw reply")
    return compile_playback(request, raw)
