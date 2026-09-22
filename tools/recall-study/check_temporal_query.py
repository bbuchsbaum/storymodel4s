#!/usr/bin/env python3
"""Independent known-answer checks on the synthetic canonical temporal-query export."""
import argparse
import copy
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import struct


def number(value):
    assert isinstance(value, str) and value.startswith('0x') and len(value) == 18
    return struct.unpack('>d', bytes.fromhex(value[2:]))[0]


def sequence(values):
    return str(len(values)) + ':' + ''.join(str(len(v.encode('utf-16-be')) // 2) + ':' + v for v in values)


def identity(values):
    return hashlib.sha256(sequence(values).encode('utf-16-be').hex().encode()).hexdigest()


def verify(value):
    assert value['schemaVersion'] == 'temporal-query/v0.1'
    assert value['inputs']['measure'] == 'NormalizedScoreMass'
    assert value['inputs']['unit'] == 'p1'
    assert value['region'] == {'intervals': [{'start_tick': '10', 'end_exclusive_tick': '15'}], 'points': []}
    # Authored target support [10,20), weight 1/2; queried half its duration.
    expected = Fraction(1, 2) * Fraction(15 - 10, 20 - 10)
    assert Fraction(number(value['allocated_region_mass'])) == expected
    assert number(value['resolved_support_bounds']['lower']) == 0
    assert number(value['resolved_support_bounds']['upper']) == 0.5
    ledger = value['ledger']
    assert number(ledger['supplied_total']) == 1
    assert number(ledger['allocated_source']) == 0.5
    assert number(ledger['support_without_allocation']) == 0
    assert number(ledger['unavailable_location']) == 0.25
    assert ledger['external'][0]['state'] == 'Intrusion'
    assert number(ledger['external'][0]['mass']) == 0.25
    assert value['mapping_policies']['candidate_policy_id']['candidate_coverage']['status'] == 'unknown'
    weights = [number(c['weight']) for c in value['contributions']]
    assert sum(weights) == 1 and len(weights) == 3
    assert sum(number(c['allocated_region_mass']['value']) for c in value['contributions']
               if c['allocated_region_mass']['status'] == 'present') == 0.25


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('logs', type=Path, nargs='+')
    args = parser.parse_args()
    lines = '\n'.join(p.read_text() for p in args.logs).splitlines()
    values = [json.loads(line.split('\t', 1)[1]) for line in lines if line.startswith('TEMPORAL_QUERY_FIXTURE\t')]
    assert values
    for value in values:
        verify(value)
    assert all(value == values[0] for value in values)
    v = values[0]
    expected_digests = []
    for share in (0.0, -0.0, 0.5, 1.0):
        policy = sequence(['mixture', '0x' + struct.pack('>d', share).hex()])
        declaration = sequence(['sit:e2', 'SuppliedSupportContainsReferent', policy])
        expected_digests.append(identity(['conditional-temporal-query/v1', v['mapping_digest'], 'p1',
            'NormalizedScoreMass', sequence(['part', v['inputs']['selection']['bundle']]), sequence([declaration])]))
    witnesses = [line.split('\t')[1:] for line in lines if line.startswith('TEMPORAL_QUERY_MIXTURE_DIGESTS\t')]
    assert witnesses and all(w == expected_digests for w in witnesses)
    for key in ('allocated_region_mass', 'mapping_policies', 'ledger'):
        corrupted = copy.deepcopy(v)
        if key == 'allocated_region_mass':
            corrupted[key] = '0x3ff0000000000000'
        elif key == 'mapping_policies':
            corrupted[key]['candidate_policy_id']['candidate_coverage']['status'] = 'complete'
        else:
            corrupted[key]['unavailable_location'] = '0x0000000000000000'
        try:
            verify(corrupted)
        except AssertionError:
            continue
        raise AssertionError('accepted corrupted output ' + key)
    print(json.dumps({'status': 'pass', 'readouts': len(values), 'identityWitnesses': len(witnesses),
        'negativeControls': 3, 'mixtureDigests': expected_digests}, sort_keys=True))


if __name__ == '__main__':
    main()
