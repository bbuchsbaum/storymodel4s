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
    extent = coordinate.get("extent", {})
    if extent.get("status") != "playback-ticks":
        fail("a temporal coordinate requires an exact playback extent")
    axis_start = integer(extent.get("start_tick"), "axis.start")
    axis_end = integer(extent.get("end_exclusive_tick"), "axis.end")
    if axis_start >= axis_end or fraction(extent.get("timebase"), "axis.timebase") <= 0:
        fail("invalid temporal coordinate")
    clock = coordinate.get("timebase", {})
    if clock.get("status") != "present" or clock.get("value") != extent["timebase"]:
        fail("inconsistent coordinate timebase")
    regions = document.get("regions")
    if not isinstance(regions, list):
        fail("regions must be an array")
    for index, region in enumerate(regions):
        if region.get("axis") != coordinate["id"]:
            fail(f"region {index} has foreign axis")
        support(region, f"region {index}")
    bins = document["request"]["bins"]
    if len(bins) != len(regions):
        fail("request and grid bin counts differ")
    for requested, region in zip(bins, regions):
        if region["intervals"] != [requested] or region["points"]:
            fail("request and grid geometry differ")
        if not axis_start <= integer(requested["start"], "start") < integer(requested["endExclusive"], "end") <= axis_end:
            fail("bin lies outside coordinate")
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
    numeric_rows(document)
    return True


# Independent arithmetic from exact supports and original row weights.
def geometry(value):
    support(value, "geometry")
    intervals = [(integer(i['start'], 'start'), integer(i['endExclusive'], 'end')) for i in value['intervals']]
    points = [integer(p, 'point') for p in value['points']]
    if intervals != sorted(intervals) or any(a[1] >= b[0] for a, b in zip(intervals, intervals[1:])):
        fail('support intervals are not canonical disjoint unions')
    if points != sorted(set(points)):
        fail('support points are not canonical')
    return intervals, points


def union_geometry(regions):
    intervals = sorted(i for region in regions for i in region[0])
    merged = []
    for start, end in intervals:
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(end, merged[-1][1]))
        else:
            merged.append((start, end))
    return merged, sorted(set(p for region in regions for p in region[1]))


def contains_point(region, point):
    return point in region[1] or any(start <= point < end for start, end in region[0])


def overlap_length(a, b):
    return sum(max(0, min(end, stop) - max(start, begin))
               for start, end in a[0] for begin, stop in b[0])


def intersects(a, b):
    return overlap_length(a, b) > 0 or any(contains_point(a, p) for p in b[1]) or any(contains_point(b, p) for p in a[1])


def contains_support(region, domain):
    return all(any(a <= lo and hi <= b for a, b in region[0]) for lo, hi in domain[0]) and all(contains_point(region, p) for p in domain[1])


def close(actual, expected, field):
    expected = float(expected)
    if not math.isfinite(actual) or not math.isfinite(expected):
        fail(field + ' is not finite')
    # Integer geometry is exact; only final binary64 products and sums receive a rounding tolerance.
    if expected == 0:
        valid = actual == 0
    else:
        valid = math.isclose(actual, expected, rel_tol=5e-14, abs_tol=0.0)
    if not valid:
        fail(f'{field}: {actual!r} differs from independent {expected!r}')


def kernel(location, region):
    policy = location['allocation']
    if 'unavailable' in policy:
        return None
    domain = geometry(location['support'])
    total = sum(end-start for start, end in domain[0])
    continuous = Fraction(overlap_length(domain, region), total) if total else None
    atomic = Fraction(sum(contains_point(region, p) for p in domain[1]), len(domain[1])) if domain[1] else None
    kind = policy['kind']
    if kind == 'UniformIntervals' and continuous is not None and not domain[1]:
        return continuous
    if kind == 'UniformPoints' and atomic is not None and not domain[0]:
        return atomic
    if kind == 'Mixture' and continuous is not None and atomic is not None:
        share = Fraction(binary64(policy['intervalShare'], 'mixture share'))
        if not 0 <= share <= 1:
            fail('invalid mixture share')
        return share*continuous + (1-share)*atomic
    fail('incompatible or unknown allocation kernel')


def numeric_rows(document):
    regions = [geometry(region) for region in document['regions']]
    for i, region in enumerate(regions):
        if any(intersects(region, other) for other in regions[:i]):
            fail('overlapping grid bins')
    union = union_geometry(regions)
    for row in document['rows']:
        grid = row['grid']
        if 'unavailable' in grid:
            continue
        components = {c['alternative']: c for c in grid['components']}
        if len(components) != len(grid['components']):
            fail('duplicate component')
        quantities, bounds, expected_cells = {}, {}, {}
        ledger = dict(supplied=Fraction(), allocatedSource=Fraction(), supportWithoutAllocation=Fraction(), unavailableLocation=Fraction())
        external, outside = {}, Fraction()
        for key, component in components.items():
            weight = Fraction(binary64(component['weight'], 'component weight'))
            if weight < 0:
                fail('negative component weight')
            ledger['supplied'] += weight
            location = component['location']
            if location['kind'] == 'external':
                state = location['state'];external[state] = external.get(state, Fraction()) + weight
                continue
            if location['kind'] == 'unresolved' or 'unavailable' in location.get('support', {}):
                ledger['unavailableLocation'] += weight
                continue
            domain = geometry(location['support'])
            allocated = kernel(location, union)
            if allocated is None:
                ledger['supportWithoutAllocation'] += weight
            else:
                ledger['allocatedSource'] += weight
                outside += weight * (1-allocated)
            for index, region in enumerate(regions):
                lower = weight if contains_support(region, domain) else Fraction()
                upper = weight if intersects(region, domain) else Fraction()
                proportion = kernel(location, region)
                quantity = None if proportion is None else weight*proportion
                old_lo, old_hi = bounds.get(index, (Fraction(), Fraction()))
                bounds[index] = old_lo+lower, old_hi+upper
                if quantity is not None:
                    quantities[index] = quantities.get(index, Fraction()) + quantity
                if upper > 0 or (quantity is not None and quantity > 0):
                    expected_cells[(index, key)] = (lower, upper, quantity)
        actual_cells = {}
        for entry in grid['cells']:
            index = integer(entry['bin'], 'cell.bin');cell = entry['component'];key = cell['alternative']
            if key not in components or any(cell.get(k) != v for k,v in components[key].items()):
                fail('cell changed its original component')
            cell_key = index, key
            if cell_key in actual_cells or cell_key not in expected_cells:
                fail('duplicate or unexpected sparse cell')
            expected = expected_cells[cell_key]
            for field, value in zip(('lower','upper','allocated'), expected):
                if value is None:
                    if cell.get(field) is not None: fail('support-only cell acquired allocation')
                else:
                    close(binary64(cell.get(field), field), value, 'cell.'+field)
            actual_cells[cell_key] = True
        if actual_cells.keys() != expected_cells.keys():
            fail('missing sparse cell')
        expected_quantities = {i:v for i,v in quantities.items() if v != 0}
        expected_bounds = {i:v for i,v in bounds.items() if v[1] != 0}
        def sparse(values, fields, expected, label):
            actual = {}
            for entry in values:
                i = integer(entry['bin'], label+'.bin')
                if i in actual or i not in expected: fail(label+' duplicate or extra bin')
                observed = tuple(binary64(entry[f], label+'.'+f) for f in fields)
                target = expected[i] if isinstance(expected[i], tuple) else (expected[i],)
                for a,b in zip(observed,target): close(a,b,label)
                actual[i] = observed
            if actual.keys() != expected.keys(): fail(label+' missing bin')
        sparse(grid['allocated'], ('mass',), expected_quantities, 'allocated')
        sparse(grid['bounds'], ('lower','upper'), expected_bounds, 'bounds')
        for name, expected in ledger.items(): close(binary64(grid['ledger'][name],name),expected,'ledger.'+name)
        observed_external = {v['state']:binary64(v['mass'],'external') for v in grid['ledger']['external']}
        if observed_external.keys() != external.keys() or len(observed_external) != len(grid['ledger']['external']): fail('external ledger lost states')
        for key in external:close(observed_external[key], external[key], 'external.'+key)
        budget = binary64(grid['ledger']['rowBudget'], 'rowBudget')
        if budget < 0: fail('negative row budget')
        observed_inside = sum(binary64(v['mass'], 'mass') for v in sorted(grid['allocated'], key=lambda v: integer(v['bin'], 'bin')))
        discrepancy = observed_inside + binary64(grid['allocatedOutside'], 'outside') - binary64(grid['ledger']['allocatedSource'], 'allocatedSource')
        close(binary64(grid['ledger']['discrepancy'], 'discrepancy'), discrepancy, 'allocation discrepancy')
        close(binary64(grid['allocatedOutside'],'allocatedOutside'), outside, 'allocatedOutside')
        exposure = row.get('exposure')
        for name, values, fields in [('durationWeighted',expected_quantities,('quantity',)),('durationWeightedBounds',expected_bounds,('lower','upper'))]:
            encoded = row[name]
            if exposure is None:
                if encoded.get('unavailable') != 'ExposureUnavailable':fail('missing exposure was imputed')
                continue
            duration = fraction(exposure['duration'], 'duration')
            weighted = {i:tuple(v*duration for v in value) if isinstance(value,tuple) else value*duration for i,value in values.items()}
            weighted = {i:v for i,v in weighted.items() if (v[-1] if isinstance(v,tuple) else v) != 0}
            try:
                finite = all(math.isfinite(float(v)) for value in weighted.values() for v in (value if isinstance(value,tuple) else (value,)))
            except OverflowError:
                finite = False
            if not finite:
                if encoded.get('unavailable') != 'NonFiniteExposureQuantity': fail('overflow was not refused')
            else:
                if 'unavailable' in encoded:fail('finite weighted quantity unavailable')
                sparse(encoded['values'],fields,weighted,name)
    readout = document.get('exposureReadout')
    if readout is not None:
        duration = fraction(readout['endExclusive'],'end') - fraction(readout['start'],'start')
        exposed = sum((fraction(row['exposure']['duration'],'duration') for row in document['rows']), Fraction())
        if exposed + fraction(readout['uncovered'],'uncovered') != duration:fail('exposure conservation')
        uncomputed = sum((fraction(row['exposure']['duration'],'duration') for row in document['rows'] if row['processing'] != 'Complete'), Fraction())
        if uncomputed != fraction(readout['uncomputedDuration'],'uncomputed'):fail('uncomputed duration lost')
    return True


def load(text):
    """Refuse duplicate keys before a JSON parser can discard contradictory values."""
    def object_pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                fail("duplicate JSON key: " + key)
            result[key] = value
        return result
    return json.loads(text, object_pairs_hook=object_pairs)


if __name__ == "__main__":
    try:
        verify(load(Path(sys.argv[1]).read_text(encoding="utf-8")))
    except (IndexError, KeyError, TypeError, OverflowError, OSError, ValueError) as error:
        sys.stderr.write(f"temporal projection refused: {error}\n")
        sys.exit(2)
