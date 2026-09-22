#!/usr/bin/env python3
"""Check exported synthetic support fixtures using independent integer/Fraction arithmetic."""
import argparse
import copy
from fractions import Fraction
import json
from pathlib import Path


def selected(value):
    assert value['schemaVersion'] == 'temporal-support/v0.1'
    node = next(n for n in value['nodes'] if n['target'] == value['target'])
    assert node['state'] == 'Available'
    assert node['included']['status'] == 'present'
    geometry = node['included']['value']
    for interval in geometry['intervals']:
        assert type(interval['start_tick']) is str
        assert type(interval['end_exclusive_tick']) is str
        assert int(interval['start_tick']) < int(interval['end_exclusive_tick'])
    assert all(type(p) is str for p in geometry['points'])
    return geometry


def verify(rows):
    group = rows['group']
    g = selected(group)
    assert [(int(i['start_tick']), int(i['end_exclusive_tick'])) for i in g['intervals']] == [(10, 20)]
    assert not g['points']
    assert group['unlocated_descendants'] == group['unavailable_descendants'] == ['sit:e4']
    missing = next(n for n in group['nodes'] if n['target'] == 'sit:e4')
    assert missing['state'] == 'Unlocated' and missing['included']['status'] == 'absent'
    point = rows['point']
    g = selected(point)
    assert g['points'] == ['70'] and not g['intervals']
    segment = point['coordinate']['occurrence']['value']
    assert segment['occurrence'] == 'part-1'
    assert (int(segment['source']['start_tick']), int(segment['source']['end_exclusive_tick'])) == (0, 40)
    assert (int(segment['target']['start_tick']), int(segment['target']['end_exclusive_tick'])) == (40, 80)
    large = rows['large']
    g = selected(large)
    assert g['points'] == ['9007199254740993'] and not g['intervals']
    scale = large['coordinate']['axis']['timebase']['value']
    seconds = int(g['points'][0]) * Fraction(int(scale['numerator']), int(scale['denominator']))
    assert seconds == 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('export_log', type=Path)
    args = parser.parse_args()
    rows = {}
    for line in args.export_log.read_text().splitlines():
        if line.startswith('TEMPORAL_SUPPORT_FIXTURE\t'):
            _, name, encoded = line.split('\t', 2)
            assert name not in rows
            rows[name] = json.loads(encoded)
    assert set(rows) == {'group', 'point', 'large'}
    verify(rows)
    changed = copy.deepcopy(rows)
    target = next(n for n in changed['large']['nodes'] if n['target'] == changed['large']['target'])
    target['included']['value']['points'] = ['9007199254740992']
    try:
        verify(changed)
    except AssertionError:
        pass
    else:
        raise AssertionError('accepted rounded coordinate')
    print(json.dumps({'status': 'pass', 'fixtures': len(rows), 'negativeControls': 1,
        'checks': ['transitive-missingness', 'occurrence-extents', 'point-not-interval', 'exact-large-tick-times-timebase']}, sort_keys=True))


if __name__ == '__main__':
    main()
