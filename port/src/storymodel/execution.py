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

from .serialization import strict_json

_PROFILE_SCHEMA = "storymodel.port.recorded-run-profile/v1"
_REPLY_SCHEMA = "storymodel.port.recorded-reply/v1"
_JOURNAL_SCHEMA = "storymodel.port.recorded-run-journal/v1"
_IMPORT_INTENT_SCHEMA = "storymodel.port.recorded-run-import-intent/v1"


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

    def import_replies(
        self,
        prompts: Sequence[str],
        responses: Sequence[str],
        manifest: dict[str, Any],
        *,
        recover: bool = False,
    ) -> None:
        """Durably import supplied replies, optionally completing a recorded import.

        Recovery is deliberately opt-in: a pre-existing root is never adopted unless
        its exact durable import intent binds this profile, every prompt/reply pair,
        and the complete supplied manifest.
        """
        checked_prompts = self._validate_prompts(prompts)
        checked_responses = self._validate_responses(responses)
        if len(checked_prompts) != len(checked_responses):
            raise InvalidRequest("prompts and responses must have the same length")
        checked_manifest = self._validate_manifest(manifest)
        expected = self._import_intent(checked_prompts, checked_responses, checked_manifest)
        expected_replies = self._expected_replies(checked_prompts, checked_responses)

        if recover:
            if not self.root.exists():
                raise RecordingMissing("recovery requires an existing recording root")
        else:
            self.root.mkdir(parents=True, exist_ok=False)
        if not _is_directory(self.root):
            raise CacheTampered("recording root is not a directory")

        with self._writer_lock():
            intent_path = self._import_intent_path
            if recover:
                saved_intent = (
                    _read_json(intent_path, "import intent") if _path_present(intent_path) else None
                )
                if saved_intent is None:
                    raise PreviousAttemptUnknown("recovery requires a durable import intent")
                if not _same_json(saved_intent, expected):
                    raise CacheTampered("durable import intent differs from supplied import")
                self._validate_import_state(expected_replies, checked_manifest)
            else:
                _atomic_json_new(intent_path, expected)

            self._complete_import(expected_replies, checked_manifest)

    @staticmethod
    def _validate_responses(responses: Sequence[str]) -> tuple[str, ...]:
        if isinstance(responses, str):
            raise InvalidRequest("responses must be a sequence of strings, not one string")
        try:
            checked = tuple(responses)
        except TypeError as error:
            raise InvalidRequest("responses must be a finite sequence of strings") from error
        if not all(isinstance(response, str) for response in checked):
            raise InvalidRequest("each response must be a string")
        return checked

    @staticmethod
    def _validate_manifest(manifest: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(manifest, dict):
            raise InvalidRequest("manifest must be a JSON object")
        try:
            encoded = json.dumps(
                manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
            )
            parsed = json.loads(encoded)
        except (TypeError, ValueError) as error:
            raise InvalidRequest("manifest must be JSON-serializable") from error
        if not isinstance(parsed, dict) or parsed != manifest:
            raise InvalidRequest("manifest must be a JSON object")
        return parsed

    def _import_intent(
        self, prompts: tuple[str, ...], responses: tuple[str, ...], manifest: dict[str, Any]
    ) -> dict[str, Any]:
        return {
            "schema": _IMPORT_INTENT_SCHEMA,
            "profile": self.profile.descriptor(),
            "prompts": list(prompts),
            "responses": list(responses),
            "manifest": manifest,
        }

    def _expected_replies(
        self, prompts: tuple[str, ...], responses: tuple[str, ...]
    ) -> dict[str, tuple[str, str]]:
        expected: dict[str, tuple[str, str]] = {}
        for prompt, response in zip(prompts, responses, strict=True):
            key = self._key(prompt)
            prior = expected.setdefault(key, (prompt, response))
            if prior != (prompt, response):
                raise InvalidRequest("duplicate prompt has different supplied responses")
        return expected

    def _validate_import_state(
        self, expected_replies: dict[str, tuple[str, str]], manifest: dict[str, Any]
    ) -> None:
        expected_root_entries = {
            ".recorded-run.lock",
            "import-intent.json",
            "profile.json",
            "journal.json",
            "replies",
            "import.json",
        }
        stageable_root_entries = expected_root_entries - {".recorded-run.lock", "replies"}
        if any(
            path.name not in expected_root_entries
            and not _is_staging_file(path, stageable_root_entries)
            for path in self.root.iterdir()
        ):
            raise CacheTampered("recording root contains a foreign import entry")
        self._check_profile_for_import()
        journal = self._read_journal()
        if set(journal) - set(expected_replies):
            raise CacheTampered("journal contains a foreign import reply")
        for key in journal:
            if self._read_reply(key, expected_replies[key][0]) is None:
                raise PreviousAttemptUnknown(f"prior {journal[key]} attempt for {key} has no reply")
        if _path_present(self._replies):
            if not _is_directory(self._replies):
                raise CacheTampered("reply directory is not a real directory")
            children = tuple(self._replies.iterdir())
            expected_names = {f"{key}.json" for key in expected_replies}
            if any(
                (not _is_regular_file(path) or path.suffix != ".json")
                and not _is_staging_file(path, expected_names)
                for path in children
            ):
                raise CacheTampered("reply directory contains an invalid entry")
            reply_keys = {path.stem for path in children if path.suffix == ".json"}
            foreign = reply_keys - set(expected_replies)
            if foreign:
                raise CacheTampered("reply directory contains a foreign import reply")
            for key, (prompt, response) in expected_replies.items():
                recorded = self._read_reply(key, prompt)
                if recorded is not None and recorded != response:
                    raise CacheTampered(f"reply {key} differs from supplied import")
        import_path = self._import_path
        if _path_present(import_path):
            if not _same_json(_read_canonical_json(import_path, "import manifest"), manifest):
                raise CacheTampered("import manifest differs from supplied import")

    def _check_profile_for_import(self) -> None:
        if not _path_present(self._profile_path):
            return
        self._check_profile(writable=False)

    def _complete_import(
        self, expected_replies: dict[str, tuple[str, str]], manifest: dict[str, Any]
    ) -> None:
        self._check_profile(writable=True)
        # Validate all durable state before the first completion write, including after
        # a newly-created intent, so malformed state never gets partially repaired.
        self._validate_import_state(expected_replies, manifest)
        for key, (prompt, response) in expected_replies.items():
            if self._read_reply(key, prompt) is None:
                self._write_reply(key, prompt, response)
        if not _path_present(self._import_path):
            _atomic_json_new(self._import_path, manifest, newline=True)

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
    def _import_intent_path(self) -> Path:
        return self.root / "import-intent.json"

    @property
    def _import_path(self) -> Path:
        return self.root / "import.json"

    @property
    def _replies(self) -> Path:
        return self.root / "replies"

    def _check_profile(self, writable: bool) -> None:
        path = self._profile_path
        if not _path_present(path):
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
        if not _path_present(path):
            return {}
        raw = _read_json(path, "journal")
        if not isinstance(raw, dict) or set(raw) != {"schema", "entries"}:
            raise CacheTampered("journal shape is invalid")
        if raw["schema"] != _JOURNAL_SCHEMA or not isinstance(raw["entries"], dict):
            raise CacheTampered("journal schema is invalid")
        entries = raw["entries"]
        if not all(
            _is_digest(key) and isinstance(value, str) and value in {"started", "failed"}
            for key, value in entries.items()
        ):
            raise CacheTampered("journal entry is invalid")
        return dict(entries)

    def _write_journal(self, entries: dict[str, str]) -> None:
        _atomic_json(self._journal_path, {"schema": _JOURNAL_SCHEMA, "entries": entries})

    def _read_reply(self, key: str, prompt: str) -> str | None:
        path = self._replies / f"{key}.json"
        if not _path_present(path):
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
        # Import lazily so inspection-only users on unsupported platforms can still
        # import this module. POSIX advisory locks are the qualified macOS/Linux path.
        try:
            import fcntl
        except ImportError as error:
            raise RecordedExecutionError(
                "recorded execution requires POSIX advisory locks on this platform"
            ) from error
        flags = os.O_CREAT | os.O_RDWR
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        try:
            self.fd = os.open(self.path, flags, 0o600)
            if not stat.S_ISREG(os.fstat(self.fd).st_mode):
                raise CacheTampered("recording lock is not a regular file")
            try:
                fcntl.flock(self.fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise WriterBusy("another recorded execution owns this root") from error
        except BaseException:
            if self.fd is not None:
                os.close(self.fd)
                self.fd = None
            raise

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None


def _digest(value: str) -> str:
    return sha256(value.encode("utf-8")).hexdigest()


def _digest_json(value: object) -> str:
    return _digest(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False))


def _same_json(left: object, right: object) -> bool:
    """Compare JSON values without Python's bool/numeric equality coercion."""
    return json.dumps(
        left, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
    ) == json.dumps(
        right, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
    )


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


def _path_present(path: Path) -> bool:
    """Report a directory entry even when it is a dangling symlink."""
    try:
        path.lstat()
    except FileNotFoundError:
        return False
    return True


def _is_regular_file(path: Path) -> bool:
    try:
        return stat.S_ISREG(path.lstat().st_mode)
    except FileNotFoundError:
        return False


def _is_staging_file(path: Path, destinations: set[str]) -> bool:
    """Accept only crash-left regular staging files for known durable destinations."""
    return _is_regular_file(path) and any(
        path.name.startswith(f".{destination}.") and path.name.endswith(".tmp")
        for destination in destinations
    )


def _read_json(path: Path, label: str) -> Any:
    try:
        if not _is_regular_file(path):
            raise CacheTampered(f"{label} is not a regular file")
        return strict_json(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
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


def _atomic_json_new(path: Path, value: object, *, newline: bool = False) -> None:
    """Publish a new canonical JSON file without replacing completed import bytes."""
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode(
        "utf-8"
    )
    if newline:
        encoded += b"\n"
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
        try:
            os.link(temporary, path)
        except FileExistsError:
            raise CacheTampered(f"refusing to replace existing {path.name}") from None
        _fsync_directory(path.parent)
    except OSError as error:
        raise CacheTampered(f"cannot atomically persist {path.name}") from error
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def _read_canonical_json(path: Path, label: str) -> Any:
    try:
        if not _is_regular_file(path):
            raise CacheTampered(f"{label} is not a regular file")
        raw = path.read_bytes()
        value = strict_json(raw)
        canonical = (
            json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode(
                "utf-8"
            )
            + b"\n"
        )
        if raw != canonical:
            raise CacheTampered(f"{label} is not canonical JSON with a final newline")
        return value
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
        raise CacheTampered(f"{label} is unreadable or malformed") from error


def _fsync_directory(path: Path) -> None:
    try:
        fd = os.open(path, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(fd)
    finally:
        os.close(fd)
