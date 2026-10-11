#!/usr/bin/env python3
"""Independent standard-library reader for temporal-projection/v1 sparse JSON exports."""
import json
import math
import sys
from fractions import Fraction
from pathlib import Path


def fail(message):
    raise ValueError(message)


def integer(value, field):
    if not isinstance(value, str) or not value or value.startswith('+'):
        fail(f"{field} must be a decimal integer string")
    try:
        return int(value)
    except ValueError as error:
        raise ValueError(f"{field} must be a decimal integer string") from error


def finite(value, field):
    if not isinstance(value, str):
        fail(f"{field} must be a canonical binary64 string")
    parsed = float(value)
    if not math.isfinite(parsed):
        fail(f"{field} must be finite")
    return parsed


def verify(document):
    if document.get("schemaVersion") != "temporal-projection/v1":
        fail("unsupported schema")
    if not isinstance(document.get("rows"), list):
        fail("rows must be an array")
    for row in document["rows"]:
        grid = row.get("grid", {})
        if "unavailable" in grid:
            continue
        for value in grid.get("allocated", []):
            integer(value.get("bin"), "allocated.bin")
            finite(value.get("mass"), "allocated.mass")
        for value in grid.get("bounds", []):
            integer(value.get("bin"), "bounds.bin")
            finite(value.get("lower"), "bounds.lower")
            finite(value.get("upper"), "bounds.upper")
        for name, value in grid.get("ledger", {}).items():
            finite(value, f"ledger.{name}")
        finite(grid.get("allocatedOutside"), "allocatedOutside")
        exposure = row.get("exposure")
        if exposure is not None:
            denominator = integer(exposure.get("denominator"), "exposure.denominator")
            if denominator <= 0:
                fail("exposure.denominator must be positive")
            Fraction(integer(exposure.get("numerator"), "exposure.numerator"), denominator)
    return True


if __name__ == "__main__":
    try:
        verify(json.loads(Path(sys.argv[1]).read_text(encoding="utf-8")))
    except (IndexError, OSError, ValueError, json.JSONDecodeError) as error:
        sys.stderr.write(f"temporal projection refused: {error}\n")
        sys.exit(2)
