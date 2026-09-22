#!/usr/bin/env python3
"""Check synthetic production intake outputs against the independent Python reader.

This is an acceptance oracle for the committed fixture, not a production decoder.
"""
import argparse
import copy
from fractions import Fraction
import hashlib
import json
from pathlib import Path

import recall_word_clock as reader


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify(timing, receipt, oracle):
    assert timing['schemaVersion'] == 'recall-timing/v0.1'
    assert receipt['schemaVersion'] == 'recall-timing-intake-receipt/v0.1'
    assert timing['inventory_digest'] == oracle['binding']['inventoryDigest']
    assert receipt['inventoryDigest'] == timing['inventory_digest']
    assert receipt['timingDigest'] == timing['digest']
    assert receipt['correspondenceDigest'] == timing['provenance']['correspondence']
    assert timing['clock']['key'] == 'openneuro-word-onset-seconds'
    assert timing['clock']['recording']['status'] == 'unestablished'
    assert timing['clock']['origin']['status'] == 'unestablished'
    assert timing['provenance']['recording_link']['status'] == 'unestablished'
    assert receipt['recordingIdentityBinding'] == 'not-established'
    assert receipt['scannerAlignment'] == 'not-established'
    assert receipt['csvSha256'] == oracle['binding']['clockSource']['csvSha256']
    assert receipt['parserArtifact'] == oracle['binding']['wordIdPolicy']['input_artifact']
    assert len(timing['entries']) == len(oracle['words']) == 4
    assert len(receipt['records']) == len(oracle['records']) == 5

    def onset(actual, expected):
        if expected['status'] == 'missing':
            assert actual['status'] == 'missing'
            assert actual['reason'] == 'BlankSourceCell'
        else:
            assert actual['status'] == 'onset-only'
            value = actual['seconds']
            assert type(value['numerator']) is str and type(value['denominator']) is str
            assert Fraction(int(value['numerator']), int(value['denominator'])) == Fraction(expected['decimal'])

    for actual, expected in zip(timing['entries'], oracle['words']):
        assert actual['word'] == expected['wordId']
        onset(actual['observation'], expected['onset'])
    for actual, expected in zip(receipt['records'], oracle['records']):
        assert actual['record'] == expected['record']
        assert actual['binding']['status'] == expected['correspondence']['status']
        if actual['binding']['status'] == 'matched':
            assert actual['binding']['word'] == expected['correspondence']['wordId']
        onset(actual['observation'], expected['selectedOnset'])
    summaries = timing['unit_onset_diagnostics']
    assert [s['available_onsets'] for s in summaries] == [1, 2]
    assert [s['total_words'] for s in summaries] == [2, 2]
    assert summaries[1]['backward_pairs'] == [{'from': oracle['words'][2]['wordId'], 'to': oracle['words'][3]['wordId']}]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('bundle', type=Path)
    args = p.parse_args()
    fixture = Path(__file__).parent / 'fixtures' / 'word-clock-join'
    oracle = reader.join((fixture / 'words.csv').read_bytes(), (fixture / 'lineage.json').read_bytes(),
        'openneuro', (fixture / 'g1-historical.json').read_bytes(),
        'acea0831a78b03f5b7cfa548e81f037aa997d37dbdae8b6cdfd4b24ac82f59c7',
        recipe='external-clock-exact-replay/v1')
    manifest = json.loads((args.bundle / 'manifest.json').read_bytes())
    assert manifest['schemaVersion'] == 'recall-timing-intake-bundle/v0.1'
    assert {e['path'] for e in manifest['files']} == {'recall-timing.json', 'intake-receipt.json'}
    for entry in manifest['files']:
        assert digest((args.bundle / entry['path']).read_bytes()) == entry['sha256']
    timing = json.loads((args.bundle / 'recall-timing.json').read_bytes())
    receipt = json.loads((args.bundle / 'intake-receipt.json').read_bytes())
    verify(timing, receipt, oracle)
    corruptions = []
    wrong = copy.deepcopy(timing)
    wrong['entries'][1]['observation']['seconds']['numerator'] = '34'
    corruptions.append((wrong, receipt))
    wrong = copy.deepcopy(timing)
    wrong['entries'][0]['observation'] = {'status': 'onset-only', 'seconds': {'numerator': '0', 'denominator': '1'}}
    corruptions.append((wrong, receipt))
    wrong = copy.deepcopy(receipt)
    wrong['records'].pop(1)
    corruptions.append((timing, wrong))
    wrong = copy.deepcopy(receipt)
    wrong['scannerAlignment'] = 'established'
    corruptions.append((timing, wrong))
    for changed, ledger in corruptions:
        try:
            verify(changed, ledger, oracle)
        except AssertionError:
            continue
        raise AssertionError('oracle accepted a corrupted output')
    print(json.dumps({'status': 'pass', 'words': 4, 'records': 5, 'units': 2,
        'negativeControls': len(corruptions), 'oracle': 'independent-reader-and-Fraction',
        'manifestSha256': digest((args.bundle / 'manifest.json').read_bytes())}, sort_keys=True))


if __name__ == '__main__':
    main()
