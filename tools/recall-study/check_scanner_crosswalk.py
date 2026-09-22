#!/usr/bin/env python3
"""Independent Fraction/index-map oracle for emitted synthetic scanner artifacts."""
import argparse
import copy
from fractions import Fraction
import json
from pathlib import Path


def rational(v):
    assert type(v['numerator']) is str and type(v['denominator']) is str
    return Fraction(int(v['numerator']), int(v['denominator']))


def verify(run, layout):
    assert run['authority'] == layout['authority'] == 'declared-not-independently-verified'
    assert run['schemaVersion'] == 'scanner-run/v0.1'
    assert layout['schemaVersion'] == 'scanner-layout/v0.1'
    assert run['declaration']['sample_count'] == 8
    assert [rational(v) for v in run['declaration']['sample_seconds']] == [Fraction(3 * i, 2) for i in range(8)]
    assert run['declaration']['origin']['status'] == 'first-stored-sample'
    assert run['declaration']['applied_history']['status'] == 'unknown'
    assert layout['declaration']['run_digest'] == run['digest']
    assert layout['dropped_stored_indices'] == [0, 1, 3, 5, 6]
    assert [s['analysis_index'] for s in layout['samples']] == list(range(5))
    assert [s['time']['status'] for s in layout['samples']] == ['unavailable-padding'] * 2 + ['acquired'] * 3
    assert [rational(s['time']['seconds']) for s in layout['samples'][2:]] == [Fraction(3), Fraction(6), Fraction(21, 2)]
    assert [s['slot']['stored_index'] for s in layout['samples'][2:]] == [2, 4, 7]
    assert [s['slot']['censoring']['status'] for s in layout['samples'][2:]] == ['included', 'censored', 'unknown']


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('log', type=Path)
    p.add_argument('cli_output', type=Path)
    args = p.parse_args()
    rows = {}; landmarks = []
    for raw in args.log.read_text().splitlines():
        line = raw.removeprefix('[info] ')
        if line.startswith('SCANNER_FIXTURE\t'):
            _, kind, data = line.split('\t', 2)
            assert kind not in rows
            rows[kind] = json.loads(data)
        elif line.startswith('SCANNER_LANDMARK\t'):
            _, value, n, d = line.split('\t')
            landmarks.append((Fraction(int(value)), Fraction(int(n), int(d))))
    assert set(rows) == {'run', 'layout', 'binding'}
    verify(rows['run'], rows['layout'])
    binding = rows['binding']
    assert binding['authority'] == 'declared-not-independently-verified'
    assert binding['run_digest'] == rows['run']['digest']
    assert rational(binding['scale']) == Fraction(3, 2)
    assert rational(binding['offset']) == -2
    assert landmarks == [(Fraction(v), Fraction(3 * v, 2) - 2) for v in (0, 1, 4, 19)]
    output = json.loads(args.cli_output.read_bytes())
    assert output['schemaVersion'] == 'scanner-samples/v0.1'
    verify(output['run'], output['layout'])
    assert output['scanner_binding'] == {'status': 'unestablished'}
    for kind in ('padding', 'crop', 'censor'):
        changed = copy.deepcopy(rows['layout'])
        if kind == 'padding':
            changed['samples'][0]['time'] = {'status': 'acquired', 'seconds': {'numerator': '0', 'denominator': '1'}}
        elif kind == 'crop':
            changed['samples'][2]['time']['seconds'] = {'numerator': '0', 'denominator': '1'}
        else:
            changed['samples'][3]['slot']['censoring']['status'] = 'included'
        try:
            verify(rows['run'], changed)
        except AssertionError:
            continue
        raise AssertionError('accepted corrupted ' + kind)
    print(json.dumps({'status': 'pass', 'storedSamples': 8, 'analysisSlots': 5, 'exactLandmarks': 4,
        'negativeControls': 3, 'authority': 'declared-not-independently-verified'}, sort_keys=True))


if __name__ == '__main__':
    main()
