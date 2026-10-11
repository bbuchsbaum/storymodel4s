#!/usr/bin/env python3
"""Independent temporal arithmetic and contextual paired-bundle verifier (standard library)."""
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
    """Verify arithmetic within one projection; use verify_pair for original-member joins."""
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


def sequence(values):
    # Mapping identities count UTF-16 code units, including non-BMP identifiers.
    return str(len(values)) + ':' + ''.join(str(len(v.encode('utf-16-be')) // 2) + ':' + v for v in values)


def alternative_key(link):
    if link['measure_kind'] != 'ModelPosterior':
        return sequence(['score' if link['measure_kind'] == 'NormalizedScoreMass' else 'transport', link['destination']])
    state = link['state']['value']
    if state['type'] == 'External':
        parts = ['external', state['state']]
    else:
        ref = state['ref']
        parts = [ref['type'].lower(), ref['id']]
        if state['type'] == 'Distorted':
            parts += ['distorted', ','.join(state['facets'])]
    return sequence(['posterior'] + parts)


def timing_context(document, timing, inventory):
    if timing is None:
        expected = 'Missing' if document['request'].get('recallWindow') is not None else 'NotRequested(false)'
        if document['timingState'] != expected:fail('timing state changed')
        if document.get('timing') is not None or document.get('exposureReadout') is not None:
            fail('projection claims absent timing')
        return
    if timing['schemaVersion'] != 'recall-timing/v0.1' or timing['inventory_digest'] != inventory['digest']:
        fail('foreign timing inventory')
    if document.get('timing') != timing['digest']:
        fail('foreign timing identity')
    clock = timing['clock']['digest']
    entries = {e['word']: e for e in timing['entries']}
    words = {w['id']: w for w in inventory['words']}
    if len(entries) != len(timing['entries']) or entries.keys() != words.keys():
        fail('timing word denominator changed')
    if any(e['clock'] != clock for e in entries.values()):
        fail('foreign word clock')
    window = document['request'].get('recallWindow')
    if document['timingState'] != ('Provided' if window is not None else 'NotRequested(true)'):fail('timing state changed')
    if window is None:
        if document.get('exposureReadout') is not None:
            fail('unrequested exposure')
        return
    if window['clock'] != clock:
        fail('foreign requested clock')
    lo, hi = fraction(window['start'], 'window.start'), fraction(window['endExclusive'], 'window.end')
    intervals, onsets, missing = [], {}, {}
    for unit in inventory['units']:
        onsets[unit['id']] = []
        missing[unit['id']] = 0
        for word in unit['words']:
            observation = entries[word]['observation']
            status = observation['status']
            if status == 'interval':
                start = fraction(observation['start_seconds'], 'interval.start')
                end = fraction(observation['end_exclusive_seconds'], 'interval.end')
                if not start < end: fail('invalid source interval')
                if max(start,lo) < min(end,hi): intervals.append((unit['id'], max(start,lo), min(end,hi)))
            elif status == 'onset-only':
                at = fraction(observation['seconds'], 'onset')
                if lo <= at < hi and at not in onsets[unit['id']]: onsets[unit['id']].append(at)
            elif status == 'missing': missing[unit['id']] += 1
            else: fail('unknown timing observation')
    boundaries = sorted({lo,hi} | {v for _,a,b in intervals for v in (a,b)})
    durations, uncovered = {}, Fraction()
    for a,b in zip(boundaries,boundaries[1:]):
        active = {unit for unit,start,end in intervals if start <= a and b <= end}
        if not active: uncovered += b-a
        else:
            if len(active) > 1 and document['request']['overlap'] == 'Refuse': fail('overlap policy violated')
            for unit in active: durations[unit] = durations.get(unit,Fraction()) + (b-a)/len(active)
    readout = document['exposureReadout']
    if readout['unassignedWords'] != [w['id'] for w in inventory['words'] if w['membership']['status'] == 'unassigned']:fail('unassigned word inventory changed')
    if fraction(readout['start'],'start') != lo or fraction(readout['endExclusive'],'end') != hi or fraction(readout['uncovered'],'uncovered') != uncovered:
        fail('exposure window differs from original timing')
    for row in document['rows']:
        exposure = row['exposure'];unit = row['unit']
        if fraction(exposure['duration'],'duration') != durations.get(unit,Fraction()): fail('duration differs from original word intervals')
        if [fraction(v,'onset') for v in exposure['onsetEvents']] != onsets[unit]:fail('onsets differ from original words')
        if integer(exposure['missingWords'],'missingWords') != missing[unit]:fail('missing-word count differs')


def verify_pair(files):
    """Check original paired artifacts and contextual joins, then independently recompute quantities.

    As with read_mapping, this verifies a supplied bundle's consistency; Scala's contextual
    decoder remains the admission authority for an independently expected execution.
    """
    import hashlib
    import importlib.util
    source = Path(__file__).resolve().parents[1] / 'examples/mapping/read_mapping.py'
    spec = importlib.util.spec_from_file_location('original_mapping_reader', source)
    mapping_reader = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mapping_reader)
    manifest = load(files['manifest.json'])
    if manifest['schemaVersion'] != 'mapping-exchange-pair-temporal/v1':fail('wrong temporal pair schema')
    descriptors = manifest['files']
    expected = {e['path'] for e in descriptors}
    if len(expected) != len(descriptors) or set(files) != expected | {'manifest.json'}:fail('pair file set mismatch')
    for entry in descriptors:
        raw = files[entry['path']].encode('utf-8')
        if len(raw) != integer(entry['bytes'],'bytes') or hashlib.sha256(raw).hexdigest() != entry['sha256']:
            fail('pair file integrity: ' + entry['path'])
    base = manifest['base']
    base_names = {e['path'] for e in base['files']}
    original = {name: files[name] for name in base_names}
    original['manifest.json'] = json.dumps(base, ensure_ascii=False, separators=(',',':'), sort_keys=True)
    metadata, members = mapping_reader.read_pair(original)
    if manifest['execution'] != metadata['execution']:fail('foreign execution')
    allowed = base_names | {'manifest.json'}
    for member, measure in [('reference','NormalizedScoreMass'),('reconstruction','ModelPosterior')]:
        name = member + '--temporal-projection.json';allowed.add(name)
        document = load(files[name]);record, tables = members[member]
        if document['mapping'] != record['record_digest'] or document['measure'] != measure:
            fail('foreign projection mapping or measure')
        if document['digest'] != manifest[member+'Projection']:fail('foreign projection identity')
        if document['coordinate'] not in record['source']['axes']:fail('foreign source coordinate')
        unit_ids = [u['id'] for u in record['inventory']['units']]
        if [r['unit'] for r in document['rows']] != unit_ids:fail('foreign projection inventory')
        for row,outcome in zip(document['rows'],record['outcomes']):
            if row['unit'] != outcome['unit']:fail('foreign outcome')
            complete = outcome['processing_status']['status'] == 'complete'
            if (row['processing'] == 'Complete') != complete:fail('processing status changed')
            grid = row['grid']
            if 'unavailable' in grid:continue
            if not complete:fail('failed unit acquired a measure')
            links = [link for link in outcome['mapping_links'] if link['measure_kind'] == measure]
            expected_components = {alternative_key(link):link['raw_value'] for link in links}
            actual = {c['alternative']:c['weight'] for c in grid['components']}
            if len(actual) != len(grid['components']) or actual != expected_components:fail('original assignment components changed')
            original_links = {alternative_key(link):link for link in links}
            for component in grid['components']:
                location = component['location'];destination = original_links[component['alternative']]['destination']
                if location['kind'] == 'external' and destination != 'ext:' + location['state']:fail('foreign external location')
                if location['kind'] == 'resolved' and destination != location['target']:fail('foreign target location')
                if location['kind'] == 'resolved' and 'unavailable' not in location['support']:
                    declarations = {d['target']:d for d in document['request']['declarations']}
                    declaration = declarations.get(destination)
                    if declaration is None:fail('resolved undeclared target')
                    if 'unavailable' not in location['allocation'] and location['allocation'] != declaration['allocation']:fail('allocation policy changed')
                    if document['request']['selection']['kind'] == 'part':
                        target = next(t for t in record['source']['targets'] if t['target_id'] == destination)
                        anchors = target['support_status']['source_support']['anchors']
                        intervals,points = [],[]
                        for anchor in anchors:
                            kind = anchor['kind']
                            if kind == 'media-point':
                                if anchor['axis'] != document['coordinate']['id']:fail('foreign support axis')
                                points.append(integer(anchor['tick'],'support.tick'))
                            else:
                                values = [anchor['interval']] if kind == 'shot' else anchor['intervals']
                                for value in values:
                                    if value['axis'] != document['coordinate']['id']:fail('foreign support axis')
                                    intervals.append((integer(value['start_tick'],'support.start'),integer(value['end_exclusive_tick'],'support.end')))
                        original_geometry = union_geometry([(intervals,points)])
                        if geometry(location['support']) != original_geometry:fail('support differs from original target')
        timing_name = member + '--recall-timing.json'
        timing = None
        if timing_name in files:
            allowed.add(timing_name);timing = load(files[timing_name])
        timing_context(document,timing,record['inventory'])
        verify(document)
    if set(files) != allowed:fail('extra temporal pair payload')
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
        if sys.argv[1] == '--pair':
            folder = Path(sys.argv[2])
            verify_pair({p.name:p.read_text(encoding='utf-8') for p in folder.iterdir() if p.is_file()})
        else:
            verify(load(Path(sys.argv[1]).read_text(encoding="utf-8")))
    except (IndexError, KeyError, TypeError, OverflowError, OSError, ValueError) as error:
        sys.stderr.write(f"temporal projection refused: {error}\n")
        sys.exit(2)
