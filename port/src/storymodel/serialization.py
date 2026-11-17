"""One strict JSON boundary and canonical identity encoding for every workflow."""

import hashlib
import json
import math
from typing import Any


def canonical(value: Any) -> str:
    """Canonical pilot JSON; this is not the legacy Scala digest encoding."""
    return json.dumps(
        value, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")
    )


def digest(value: Any) -> str:
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def strict_json(raw: str | bytes) -> Any:
    """Reject duplicate keys and nonfinite constants before schema/algorithm admission."""

    def unique(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON field: {key}")
            result[key] = value
        return result

    def invalid(value: str) -> None:
        raise ValueError(f"nonfinite JSON constant: {value}")

    def finite_float(value: str) -> float:
        parsed = float(value)
        if not math.isfinite(parsed):
            raise ValueError("nonfinite JSON number")
        return parsed

    return json.loads(
        raw, object_pairs_hook=unique, parse_constant=invalid, parse_float=finite_float
    )
