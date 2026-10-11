#!/usr/bin/env python3
"""Independent standard-library verifier for temporal-projection/v1 sparse JSON exports."""
import json
import math
import re
import struct
import sys
from fractions import Fraction
from pathlib import Path

BITS = re.compile(r"0x[0-9a-f]{16}\Z")
DECIMAL = re.compile(r"-?(0|[1-9][0-9]*)\Z")


def fail(message):
    raise ValueError(message)


def integer(value, field):
    if not isinstance(value, str) or not DECIMAL.fullmatch(value) or value == "-0":
        fail(f"{field} must be a canonical decimal integer string")
    return int(value)


def binary64(value, field):
    if not isinstance(value, str) or not BITS.fullmatch(value):
        fail(f"{field} must be canonical IEEE-754 bits")
    parsed = struct.unpack(">d", int(value[2:], 16).to_bytes(8, "big"))[0]
    if not math.isfinite(parsed):
        fail(f"{field} must be finite")
    return parsed


def fraction(value, field):
    if not isinstance(value, dict):
        fail(f"{field} must be a fraction object")
    numerator = integer(value.get("numerator"), f"{field}.numerator")
    denominator = integer(value.get("denominator"), f"{field}.denominator")
    if denominator <= 0:
        fail(f"{field}.denominator must be positive")
    result = Fraction(numerator, denominator)
    if result.numerator != numerator or result.denominator != denominator:
        fail(f"{field} must be reduced")
    return result


def support(value, field):
    if not isinstance(value, dict):
        fail(f"{field} must be support")
    for interval in value.get("intervals", []):
        start = integer(interval.get("start"), f"{field}.start")
        end = integer(interval.get("endExclusive"), f"{field}.endExclusive")
        if start >= end:
            fail(f"{field} interval must be nonempty")
    for point in value.get("points", []):
        integer(point, f"{field}.point")


def contribution(value, components, field):
    if not isinstance(value, dict):
        fail(f"{field} must be an object")
    key = value.get("alternative")
    if not isinstance(key, str):
        fail(f"{field}.alternative missing")
    binary64(value.get("weight"), f"{field}.weight")
    location = value.get("location")
    if not isinstance(location, dict):
        fail(f"{field}.location missing")
    kind = location.get("kind")
    if kind == "resolved":
        if not isinstance(location.get("target"), str):
            fail(f"{field}.location target missing")
        if "support" in location and "unavailable" not in location["support"]:
            support(location["support"], f"{field}.support")
    elif kind not in ("external", "unresolved"):
        fail(f"{field}.location kind")
    for bound in ("lower", "upper", "allocated"):
        if value.get(bound) is not None:
            binary64(value[bound], f"{field}.{bound}")
    components.add(key)


def verify(document):
    if document.get("schemaVersion") != "temporal-projection/v1":
        fail("unsupported schema")
    coordinate = document.get("coordinate")
    if not isinstance(coordinate, dict) or not isinstance(coordinate.get("id"), str):
        fail("shared coordinate missing")
    regions = document.get("regions")
    if not isinstance(regions, list):
        fail("regions must be an array")
    for index, region in enumerate(regions):
        if region.get("axis") != coordinate["id"]:
            fail(f"region {index} has foreign axis")
        support(region, f"region {index}")
    rows = document.get("rows")
    if not isinstance(rows, list):
        fail("rows must be an array")
    for row_index, row in enumerate(rows):
        grid = row.get("grid", {})
        if "unavailable" not in grid:
            components = set()
            for component in grid.get("components", []):
                contribution(component, components, f"rows[{row_index}].components")
            seen = set()
            for cell in grid.get("cells", []):
                bin_index = integer(cell.get("bin"), f"rows[{row_index}].cells.bin")
                if bin_index < 0 or bin_index >= len(regions):
                    fail("cell references absent region")
                nested = cell.get("component")
                contribution(nested, set(), f"rows[{row_index}].cells.component")
                if nested["alternative"] not in components:
                    fail("cell alternative absent from component ledger")
                key = (bin_index, nested["alternative"])
                if key in seen:
                    fail("duplicate sparse cell")
                seen.add(key)
            for value in grid.get("allocated", []):
                integer(value.get("bin"), "allocated.bin")
                binary64(value.get("mass"), "allocated.mass")
            for value in grid.get("bounds", []):
                integer(value.get("bin"), "bounds.bin")
                lower = binary64(value.get("lower"), "bounds.lower")
                upper = binary64(value.get("upper"), "bounds.upper")
                if lower > upper:
                    fail("bounds reversed")
            ledger = grid.get("ledger", {})
            for name, value in ledger.items():
                if name != "external":
                    binary64(value, f"ledger.{name}")
            for entry in ledger.get("external", []):
                if not isinstance(entry.get("state"), str):
                    fail("external state missing")
                binary64(entry.get("mass"), "external.mass")
            binary64(grid.get("allocatedOutside"), "allocatedOutside")
        exposure = row.get("exposure")
        if exposure is not None:
            fraction(exposure.get("duration"), "exposure.duration")
            for onset in exposure.get("onsetEvents", []):
                fraction(onset, "exposure.onset")
        for name in ("durationWeighted", "durationWeightedBounds"):
            value = row.get(name, {})
            if "values" in value:
                for cell in value["values"]:
                    integer(cell.get("bin"), f"{name}.bin")
                    if name == "durationWeighted":
                        binary64(cell.get("quantity"), f"{name}.quantity")
                    else:
                        binary64(cell.get("lower"), f"{name}.lower")
                        binary64(cell.get("upper"), f"{name}.upper")
    readout = document.get("exposureReadout")
    if readout is not None:
        fraction(readout.get("start"), "exposureReadout.start")
        fraction(readout.get("endExclusive"), "exposureReadout.endExclusive")
        fraction(readout.get("uncovered"), "exposureReadout.uncovered")
        fraction(readout.get("uncomputedDuration"), "exposureReadout.uncomputedDuration")
    return True


if __name__ == "__main__":
    try:
        verify(json.loads(Path(sys.argv[1]).read_text(encoding="utf-8")))
    except (IndexError, OSError, ValueError, json.JSONDecodeError) as error:
        sys.stderr.write(f"temporal projection refused: {error}\n")
        sys.exit(2)
