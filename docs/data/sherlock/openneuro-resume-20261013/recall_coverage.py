#!/usr/bin/env python3
"""Check released source-03 OpenNeuro TR assignments against verified raw coverage.

This is a bounded evidence reader, not a mapping producer or physical-origin fit.
Only counts and the numeric missing-acquisition ledger are published.
"""
import argparse
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import sys


def assign(onset, tr_number, tr_onset, count):
    """Use the release's one-based TR labels with exact half-open TR bins."""
    assert tr_number.denominator == 1 and tr_number >= 1
    index = int(tr_number) - 1
    assert tr_onset == Fraction(3, 2) * index
    assert tr_onset <= onset < tr_onset + Fraction(3, 2)
    return index if index < count else None


def controls():
    assert assign(Fraction(0), Fraction(1), Fraction(0), 716) == 0
    assert assign(Fraction(3, 2), Fraction(2), Fraction(3, 2), 716) == 1
    assert assign(Fraction(2147, 2), Fraction(716), Fraction(2145, 2), 716) == 715
    assert assign(Fraction(1074), Fraction(717), Fraction(1074), 716) is None
    assert assign(Fraction(10759, 10), Fraction(718), Fraction(2151, 2), 716) is None
    refused = 0
    for onset, number, start in [(0, 0, 0), (0, 2, 0), (0, 1, 1),
                                 (Fraction(3, 2), 1, 0), (-1, 1, 0)]:
        try:
            assign(Fraction(onset), Fraction(number), Fraction(start), 716)
        except AssertionError:
            refused += 1
    assert refused == 5
    return {'acceptedBoundaryControls': 5, 'refusedContradictions': refused}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('csv', type=Path)
    p.add_argument('verification', type=Path)
    p.add_argument('output', type=Path)
    args = p.parse_args()
    repo = Path(__file__).resolve().parents[4]
    sys.path.insert(0, str(repo / 'tools/recall-study'))
    from recall_clock import intake
    lineage = (repo / 'docs/data/sherlock/recall-lineage.json').read_bytes()
    aliases = (repo / 'docs/data/sherlock/alias-map.json').read_bytes()
    clock = intake(args.csv.read_bytes(), lineage, 'openneuro',
                   expected_source='recall-source-03', alias_id='recall-alias-03',
                   alias_bytes=aliases)
    verification = json.loads(args.verification.read_text())
    assert verification['revision'] == 'fc91edbbe1b8117f96b40bb4d1f5df43aa1afdce'
    assert verification['participant'] == 'sub-03'
    runs = [r for r in verification['runs'] if r['task'] == 'freerecall']
    assert len(runs) == 1 and runs[0]['storedVolumes'] == 716
    assert runs[0]['trSeconds'] == '3/2'
    count = runs[0]['storedVolumes']
    missing = []
    acquired = 0
    indices = set()
    onsets = []
    for record in clock['records']:
        values = record['values']
        fields = [values[k] for k in ['openneuro-word-onset-seconds',
                  'openneuro-tr-number', 'openneuro-tr-onset-seconds']]
        assert all(f['status'] == 'observed' for f in fields)
        onset, number, start = [Fraction(f['decimal']) for f in fields]
        index = assign(onset, number, start, count)
        onsets.append(onset)
        if index is None:
            missing.append({'csvDataRecord': record['record'], 'onsetSeconds': fields[0]['decimal'],
                            'releasedTrNumber': int(number), 'requestedStoredIndex': int(number) - 1,
                            'status': 'outside-stored-sample-coverage'})
        else:
            acquired += 1
            indices.add(index)
    assert len(clock['records']) == acquired + len(missing) == 2495
    assert acquired == 2489 and len(missing) == 6
    result = {'schema': 'sherlock-released-recall-coverage-observation/v1',
              'sourceIdentity': clock['identity'], 'participant': 'sub-03', 'task': 'freerecall',
              'inputVerificationSha256': hashlib.sha256(args.verification.read_bytes()).hexdigest(),
              'releaseDefinitionEvidence': {
                  'url': 'https://pmc.ncbi.nlm.nih.gov/articles/PMC10460947/',
                  'locations': ['Table 1: subject mapping', 'Table 3: columns E-G',
                                'Methods: TR indices start at one; no hemodynamic shift'],
                  'authority': 'published-release-definition-not-independent-trigger-measurement'},
              'assignment': {'clock': 'released-openneuro', 'appliedOffsetSeconds': '0',
                             'indexBase': 0, 'trSeconds': '3/2',
                             'bins': '[3*i/2, 3*(i+1)/2)',
                             'binMeaning': 'declared TR support, not measured slice-exposure windows',
                             'storedVolumes': count, 'exclusiveCoverageEndSeconds': '1074'},
              'counts': {'requestedRecords': len(onsets), 'assignedRecords': acquired,
                         'outsideStoredCoverage': len(missing), 'missingOnsets': 0,
                         'occupiedStoredBins': len(indices)},
              'onsetRangeSeconds': [str(min(onsets)), str(max(onsets))],
              'outsideStoredCoverage': missing, 'controls': controls(),
              'unavailable': ['independent acquisition-trigger/audio synchronization',
                              'full-media scanner binding', 'word offsets or durations',
                              'semantic weights or canonical mapping-word join',
                              'measured slice acquisition windows'],
              'scope': 'Published OpenNeuro clock interpretation and raw coverage audit only; '
                       'no fitted offset, crop application, HRF shift, clamping or voxel analysis.'}
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(json.dumps({'status': 'pass', **result['counts'], **result['controls']}))


if __name__ == '__main__':
    main()
