"""Bounded, content-addressed recording for deterministic text transports.

The recording is a local safety boundary, not a claim of exactly-once remote
execution or crash-proof filesystem durability.
"""

from __future__ import annotations

import json
import os
import stat
import tempfile
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
from typing import Any

_PROFILE_SCHEMA = "storymodel.port.recorded-run-profile/v1"
_REPLY_SCHEMA = "storymodel.port.recorded-reply/v1"
_JOURNAL_SCHEMA = "storymodel.port.recorded-run-journal/v1"


class RecordedExecutionError(RuntimeError):
    """Base class for explicit recorded-execution refusals."""


class InvalidRequest(RecordedExecutionError):
    """The caller supplied a malformed profile, prompts, or budget."""


class ProfileMismatch(RecordedExecutionError):
    """A recording root belongs to a different validated run profile."""


class CacheTampered(RecordedExecutionError):
    """A profile, reply, or journal on disk is malformed or inconsistent."""


class PreviousAttemptUnknown(RecordedExecutionError):
    """A prior dispatch has no valid recorded reply and cannot be retried."""


class RecordingMissing(RecordedExecutionError):
    """Offline replay requested a reply that has not been recorded."""


class BudgetExhausted(RecordedExecutionError):
    """The supplied per-invocation allowance admits no further dispatches."""


class WriterBusy(RecordedExecutionError):
    """Another live invocation owns this recording root."""


class TransportFailure(RecordedExecutionError):
    """The transport failed after durable dispatch intent was recorded."""


@dataclass(frozen=True)
class RunProfile:
    """Credential-free identity of a recording-compatible text execution."""

    provider: str
    model: str
    endpoint: str
    max_tokens: int
    recipe: str
    input_sha256: tuple[str, ...]

    def __post_init__(self) -> None:
        for name in ("provider", "model", "endpoint", "recipe"):
            value = getattr(self, name)
            if not isinstance(value, str) or not value or len(value) > 512:
                raise InvalidRequest(f"profile {name} must be a non-empty short string")
        if isinstance(self.max_tokens, bool) or not isinstance(self.max_tokens, int):
            raise InvalidRequest("profile max_tokens must be an integer, not bool")
        if self.max_tokens <= 0:
            raise InvalidRequest("profile max_tokens must be positive")
        if not isinstance(self.input_sha256, tuple) or not self.input_sha256:
            raise InvalidRequest("profile input_sha256 must be a non-empty tuple")
        if not all(_is_digest(value) for value in self.input_sha256):
            raise InvalidRequest("profile input_sha256 must contain lowercase SHA-256 digests")
        if len(set(self.input_sha256)) != len(self.input_sha256):
            raise InvalidRequest("profile input_sha256 values must be distinct")

    def descriptor(self) -> dict[str, object]:
        """Return the persisted descriptor without retaining the endpoint text."""
        return {
            "schema": _PROFILE_SCHEMA,
            "provider": self.provider,
            "model": self.model,
            "endpointSha256": _digest(self.endpoint),
            "maxTokens": self.max_tokens,
            "recipe": self.recipe,
            "inputSha256": list(self.input_sha256),
        }

    @property
    def digest(self) -> str:
        return _digest_json(self.descriptor())


class RecordedRun:
    """A root-bound collection of replies safe to replay without a transport."""

    def __init__(self, root: Path | str, profile: RunProfile) -> None:
        self.root = Path(root)
        self.profile = profile

    def run(
        self,
        prompts: Sequence[str],
        *,
        transport: Callable[[str], str] | None = None,
        max_new_calls: int = 0,
    ) -> tuple[str, ...]:
        """Return replies, dispatching only bounded cache misses when authorized.

        A reply is atomically recorded before this method returns it. A started or
        failed journal entry with no valid reply refuses a retry because the remote
        outcome is unknowable.
        """
        checked_prompts = self._validate_prompts(prompts)
        self._validate_budget(max_new_calls)
        writable = transport is not None
        if not self.root.exists() and not writable:
            raise RecordingMissing("recording root does not exist for offline replay")
        if writable:
            self.root.mkdir(parents=True, exist_ok=True)
        if not _is_directory(self.root):
            raise CacheTampered("recording root is not a directory")

        with self._writer_lock():
            self._check_profile(writable)
            journal = self._read_journal()
            requests = [(prompt, self._key(prompt)) for prompt in checked_prompts]
            # Validate every requested cached response and journal state before any dispatch.
            cached = {key: self._read_reply(key, prompt) for prompt, key in requests}
            for _, key in requests:
                if cached[key] is None and key in journal:
                    raise PreviousAttemptUnknown(
                        f"prior {journal[key]} attempt for {key} has no reply"
                    )
            replies: list[str] = []
            new_calls = 0
            for prompt, key in requests:
                recorded = cached[key]
                if recorded is not None:
                    replies.append(recorded)
                    continue
                if transport is None:
                    raise RecordingMissing(f"no recorded reply for {key}")
                if new_calls >= max_new_calls:
                    raise BudgetExhausted("new-call budget exhausted")
                journal[key] = "started"
                self._write_journal(journal)
                new_calls += 1
                try:
                    reply = transport(prompt)
                except Exception as error:
                    journal[key] = "failed"
                    self._write_journal(journal)
                    raise TransportFailure("transport failed after started intent") from error
                if not isinstance(reply, str):
                    journal[key] = "failed"
                    self._write_journal(journal)
                    raise TransportFailure("transport returned a non-string reply")
                self._write_reply(key, prompt, reply)
                cached[key] = reply
                replies.append(reply)
            return tuple(replies)

    def _validate_prompts(self, prompts: Sequence[str]) -> tuple[str, ...]:
        if isinstance(prompts, str):
            raise InvalidRequest("prompts must be a sequence of strings, not one string")
        try:
            checked = tuple(prompts)
        except TypeError as error:
            raise InvalidRequest("prompts must be a finite sequence of strings") from error
        for prompt in checked:
            if not isinstance(prompt, str) or not prompt:
                raise InvalidRequest("each prompt must be a non-empty string")
        return checked

    @staticmethod
    def _validate_budget(value: int) -> None:
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise InvalidRequest("max_new_calls must be a non-negative integer, not bool")

    def _key(self, prompt: str) -> str:
        return _digest_json({"profileSha256": self.profile.digest, "prompt": prompt})

    @property
    def _profile_path(self) -> Path:
        return self.root / "profile.json"

    @property
    def _journal_path(self) -> Path:
        return self.root / "journal.json"

    @property
    def _replies(self) -> Path:
        return self.root / "replies"

    def _check_profile(self, writable: bool) -> None:
        path = self._profile_path
        if not path.exists():
            if not writable:
                raise RecordingMissing("recording profile is missing")
            _atomic_json(path, self.profile.descriptor())
            return
        saved = _read_json(path, "profile")
        if not self._valid_profile_descriptor(saved):
            raise CacheTampered("recording profile has an invalid shape")
        if saved != self.profile.descriptor():
            raise ProfileMismatch("recording root has an incompatible profile")

    @staticmethod
    def _valid_profile_descriptor(value: object) -> bool:
        if not isinstance(value, dict):
            return False
        expected = {
            "schema",
            "provider",
            "model",
            "endpointSha256",
            "maxTokens",
            "recipe",
            "inputSha256",
        }
        inputs = value.get("inputSha256")
        return (
            set(value) == expected
            and value.get("schema") == _PROFILE_SCHEMA
            and all(
                isinstance(value.get(name), str) and value[name]
                for name in ("provider", "model", "recipe")
            )
            and _is_digest(value.get("endpointSha256"))
            and type(value.get("maxTokens")) is int
            and value["maxTokens"] > 0
            and isinstance(inputs, list)
            and bool(inputs)
            and all(_is_digest(item) for item in inputs)
            and len(set(inputs)) == len(inputs)
        )

    def _read_journal(self) -> dict[str, str]:
        path = self._journal_path
        if not path.exists():
            return {}
        raw = _read_json(path, "journal")
        if not isinstance(raw, dict) or set(raw) != {"schema", "entries"}:
            raise CacheTampered("journal shape is invalid")
        if raw["schema"] != _JOURNAL_SCHEMA or not isinstance(raw["entries"], dict):
            raise CacheTampered("journal schema is invalid")
        entries = raw["entries"]
        if not all(
            _is_digest(key) and value in {"started", "failed"} for key, value in entries.items()
        ):
            raise CacheTampered("journal entry is invalid")
        return dict(entries)

    def _write_journal(self, entries: dict[str, str]) -> None:
        _atomic_json(self._journal_path, {"schema": _JOURNAL_SCHEMA, "entries": entries})

    def _read_reply(self, key: str, prompt: str) -> str | None:
        path = self._replies / f"{key}.json"
        if not path.exists():
            return None
        raw = _read_json(path, "reply")
        expected = {
            "schema",
            "key",
            "profileSha256",
            "promptSha256",
            "response",
            "responseSha256",
        }
        if not isinstance(raw, dict) or set(raw) != expected:
            raise CacheTampered(f"reply {key} has an invalid shape")
        response = raw.get("response")
        if (
            raw.get("schema") != _REPLY_SCHEMA
            or raw.get("key") != key
            or raw.get("profileSha256") != self.profile.digest
            or raw.get("promptSha256") != _digest(prompt)
            or not isinstance(response, str)
            or raw.get("responseSha256") != _digest(response)
        ):
            raise CacheTampered(f"reply {key} does not bind to this request")
        return response

    def _write_reply(self, key: str, prompt: str, response: str) -> None:
        self._replies.mkdir(exist_ok=True)
        if not _is_directory(self._replies):
            raise CacheTampered("reply directory is not a real directory")
        _atomic_json(
            self._replies / f"{key}.json",
            {
                "schema": _REPLY_SCHEMA,
                "key": key,
                "profileSha256": self.profile.digest,
                "promptSha256": _digest(prompt),
                "response": response,
                "responseSha256": _digest(response),
            },
        )

    def _writer_lock(self) -> _WriterLock:
        return _WriterLock(self.root / ".recorded-run.lock")


class _WriterLock:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.fd: int | None = None

    def __enter__(self) -> None:
        flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        try:
            self.fd = os.open(self.path, flags, 0o600)
            os.write(self.fd, str(os.getpid()).encode("ascii"))
            os.fsync(self.fd)
        except FileExistsError as error:
            raise WriterBusy("another recorded execution owns this root") from error

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None
        try:
            self.path.unlink()
        except FileNotFoundError:
            pass


def _digest(value: str) -> str:
    return sha256(value.encode("utf-8")).hexdigest()


def _digest_json(value: object) -> str:
    return _digest(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False))


def _is_digest(value: object) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(char in "0123456789abcdef" for char in value)
    )


def _is_directory(path: Path) -> bool:
    try:
        return stat.S_ISDIR(path.lstat().st_mode)
    except FileNotFoundError:
        return False


def _read_json(path: Path, label: str) -> Any:
    try:
        if not stat.S_ISREG(path.lstat().st_mode):
            raise CacheTampered(f"{label} is not a regular file")
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CacheTampered(f"{label} is unreadable or malformed") from error


def _atomic_json(path: Path, value: object) -> None:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode(
        "utf-8"
    )
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        _fsync_directory(path.parent)
    except OSError as error:
        raise CacheTampered(f"cannot atomically persist {path.name}") from error
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def _fsync_directory(path: Path) -> None:
    try:
        fd = os.open(path, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(fd)
    finally:
        os.close(fd)
