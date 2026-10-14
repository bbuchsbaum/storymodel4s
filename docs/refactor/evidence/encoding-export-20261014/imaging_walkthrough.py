#!/usr/bin/env python3
"""Read original-unit uncertainty and illustrate clock sensitivity without fitting a shift."""
from collections import Counter, defaultdict
from fractions import Fraction
import hashlib
import json
import math
from pathlib import Path
import struct
import sys

REPO = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(REPO / 'examples/mapping'))
from read_recall_volumes import read, rational


def destination_mass(outcome, kind):
    totals = defaultdict(Fraction)
    for link in outcome['mapping_links']:
        if link['measure_kind'] != kind:
            continue
        value = struct.unpack('>d', bytes.fromhex(link['raw_value'].removeprefix('0x')))[0]
        if not math.isfinite(value) or value < 0:
            raise ValueError('nonfinite or negative supplied assignment')
        totals[link['destination']] += Fraction.from_float(value)
    return dict(totals)


def kept(values, k):
    # Marginalize supplied state assignments only within the same destination and unit.
    return sum(sorted(values.values(), reverse=True)[:k], Fraction())


def compare(outcomes, kind):
    rows = [destination_mass(o, kind) for o in outcomes]
    supplied = [r for r in rows if r]
    for row in supplied:
        if abs(float(sum(row.values(), Fraction())) - 1) > 1e-8:
            raise ValueError('unexpected original-unit normalization domain')
    total = sum((sum(r.values(), Fraction()) for r in supplied), Fraction())
    top = {}
    for k in (1, 3):
        retained = sum((kept(r, k) for r in supplied), Fraction())
        top[str(k)] = {'retained_original_quantity': float(retained),
                      'omitted_original_quantity': float(total-retained),
                      'retained_share_of_supplied_quantity': float(retained/total) if total else None}
    return {'measure_kind': kind, 'original_units': len(rows), 'units_with_measure': len(supplied),
            'units_without_measure': len(rows)-len(supplied), 'supplied_quantity': float(total),
            'top_k_destinations': top,
            'normalization': 'original unit only; omitted quantity not redistributed',
            'aggregation': 'sum across measured original units; each unit occurs once'}


def main():
    directory, csv_file, output = map(Path, sys.argv[1:])
    if output.exists():
        raise ValueError('destination exists')
    files = {p.name: p.read_text() for p in directory.iterdir() if p.is_file()}
    decoded = read(files, csv_file.read_bytes())
    attachments = decoded['attachments']
    pair = decoded['exchange']['pairs'][0]
    comparisons = {member: compare(pair[member][0]['outcomes'], kind) for member, kind in
                   [('reference', 'NormalizedScoreMass'), ('reconstruction', 'ModelPosterior')]}
    period = rational(attachments['period'])
    count = attachments['sample_count']
    sensitivity = []
    for shift in (-period, Fraction(), period):
        dispositions = Counter()
        moved = 0
        for row in attachments['records']:
            if 'onset' not in row:
                dispositions['missing'] += 1
                continue
            value = rational(row['onset']) + shift
            volume = int(value // period) if 0 <= value < count*period else None
            dispositions['inside' if volume is not None else 'outside'] += 1
            moved += volume != row.get('volume')
        sensitivity.append({'assumed_shift_seconds': str(shift), 'dispositions': dict(dispositions),
                            'changed_volume_or_coverage': moved})
    # Small independent known answers discriminate truncation from renormalization.
    control = {'a': Fraction(3,5), 'b': Fraction(3,10), 'c': Fraction(1,10)}
    assert kept(control,1) == Fraction(3,5) and kept(control,3) == 1
    assert sum(control.values())-kept(control,1) == Fraction(2,5)
    assert all(sum(s['dispositions'].values()) == len(attachments['records']) for s in sensitivity)
    assert sensitivity[1]['changed_volume_or_coverage'] == 0
    receipt = {'schema': 'published-index-imaging-walkthrough/v1',
               'recall_export_manifest_sha256': hashlib.sha256((directory/'manifest.json').read_bytes()).hexdigest(),
               'csv_sha256': hashlib.sha256(csv_file.read_bytes()).hexdigest(),
               'independent_recall_readback': decoded['summary'], 'original_unit_comparison': comparisons,
               'clock_sensitivity': sensitivity,
               'sensitivity_basis': 'illustrative minus/plus one TR; no fitted or applied shift',
               'temporal_mass_allocation': 'unavailable-onset-only',
               'physical_synchronization': 'unestablished', 'scientific_accuracy': 'not estimated'}
    output.write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt,indent=2))


if __name__ == '__main__':
    main()
