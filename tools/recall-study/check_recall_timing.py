#!/usr/bin/env python3
"""Compare Scala's synthetic timing artifacts against independent exact Python answers.

This is a qualification check, not a general timing or mapping decoder. It consumes
no participant data. The prior word-clock oracle and the Scala fixture deliberately
share numeric observations, but have separate synthetic declaration provenance.
"""
import argparse
from copy import deepcopy
from fractions import Fraction
import hashlib
import json
from pathlib import Path

import recall_clock


def exact(value):
    assert set(value) == {'numerator', 'denominator'}
    n, d = value['numerator'], value['denominator']
    assert isinstance(n, str) and isinstance(d, str)
    result = Fraction(int(n), int(d))
    assert str(result.numerator) == n and str(result.denominator) == d
    assert -(2**63) <= result.numerator < 2**63 and 0 < result.denominator < 2**63
    return result


def check_onsets(value, oracle):
    assert value['schemaVersion'] == 'recall-timing/v0.1'
    assert value['inventory_digest'] == oracle['binding']['inventoryDigest']
    assert value['clock']['unit'] == 'seconds'
    assert value['clock']['recording'] == value['clock']['origin'] == {'status': 'unestablished'}
    assert value['provenance']['recording_link'] == {'status': 'unestablished'}
    assert len(value['entries']) == len(oracle['words']) == 4
    for entry, word in zip(value['entries'], oracle['words']):
        assert entry['word'] == word['wordId']
        assert entry['clock'] == value['clock']['digest']
        observation, onset = entry['observation'], word['onset']
        if onset['status'] == 'missing':
            assert observation == {'status': 'missing', 'reason': 'BlankSourceCell'}
        else:
            assert observation['status'] == 'onset-only'
            assert exact(observation['seconds']) == Fraction(onset['decimal'])
            assert observation['basis']['status'] == 'source-reported'
    assert len(value['unit_onset_diagnostics']) == len(oracle['units']) == 2
    for summary, unit in zip(value['unit_onset_diagnostics'], oracle['units']):
        assert summary['unit'] == unit['unitId']
        assert summary['clock'] == value['clock']['digest']
        assert summary['total_words'] == unit['coverage']['totalWords']
        assert summary['available_onsets'] == unit['coverage']['observedOnsets']
        assert summary['supplied_intervals'] == summary['estimated_words'] == 0
        for new, old in [('first_member', 'firstMember'), ('last_member', 'lastMember')]:
            assert summary[new] == {'status': 'present', 'value': unit[old]['wordId']}
        for new, old in [('first_available_onset', 'firstMeasuredOnset'),
                         ('last_available_onset', 'lastMeasuredOnset')]:
            assert summary[new]['status'] == 'present'
            witness = summary[new]['value']
            assert witness['word'] == unit[old]['wordId']
            assert exact(witness['seconds']) == Fraction(unit[old]['onset']['decimal'])
        assert summary['comparable_adjacent_pairs'] == unit['onsetOrder']['observedPairs']
        assert summary['unobserved_adjacent_pairs'] == unit['onsetOrder']['unobservedPairs']
        assert summary['equal_adjacent_pairs'] == unit['onsetOrder']['equalPairs']
        assert summary['backward_pairs'] == [
            {'from': p['fromWord'], 'to': p['toWord']} for p in unit['onsetOrder']['backwardPairs']]


def check_exact(value):
    observations = [e['observation'] for e in value['entries']]
    assert len(observations) == 4
    assert exact(observations[0]['seconds']) == 9007199254740993
    assert exact(observations[1]['start_seconds']) == -(2**63)
    assert exact(observations[1]['end_exclusive_seconds']) == -1
    assert exact(observations[2]['start_seconds']) == Fraction(1, 3)
    assert exact(observations[2]['end_exclusive_seconds']) == Fraction(2, 3)
    assert observations[2]['basis']['status'] == 'estimated'
    assert observations[3] == {'status': 'missing', 'reason': 'NoCorrespondence'}
    a, b = value['unit_onset_diagnostics']
    assert (a['available_onsets'], a['supplied_intervals'], a['estimated_words']) == (2, 1, 0)
    assert (b['available_onsets'], b['supplied_intervals'], b['estimated_words']) == (1, 1, 1)
    assert len(a['backward_pairs']) == 1 and not b['backward_pairs']
    assert b['unobserved_adjacent_pairs'] == 1


def check_decimals(cases):
    for case in cases:
        text, actual = case['text'], case['result']
        try:
            parsed = recall_clock.number(text, 'seconds', 1, 1)
            if parsed['status'] == 'missing':
                # The cell intake maps blanks to Missing before calling a numeric-token parser.
                expected = {'refusal': 'Malformed'}
            else:
                f = Fraction(parsed['decimal'])
                if -(2**63) <= f.numerator < 2**63 and 0 < f.denominator < 2**63:
                    expected = {'numerator': str(f.numerator), 'denominator': str(f.denominator)}
                else:
                    expected = {'refusal': 'Unrepresentable'}
        except recall_clock.IntakeError as e:
            expected = {'refusal': 'ResourceLimit' if e.detail['code'] == 'numeric-representation-limit' else 'Malformed'}
        assert actual == expected, (text, actual, expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--join-oracle', type=Path, default=Path(__file__).resolve().parents[2] /
                        'docs/refactor/evidence/recall-word-clock-join-20260922/outputs/openneuro.json')
    args = parser.parse_args()
    paths = [args.artifacts / (n + '.json') for n in ('onsets', 'exact', 'decimal-cases')]
    values = [json.loads(p.read_text()) for p in paths]
    oracle = json.loads(args.join_oracle.read_text())
    check_onsets(values[0], oracle)
    check_exact(values[1])
    check_decimals(values[2])
    # Discriminating negative controls operate on exported bytes, not Scala implementation details.
    bad_onset = deepcopy(values[0])
    bad_onset['entries'][1]['observation']['seconds']['numerator'] = '36'
    bad_summary = deepcopy(values[0])
    bad_summary['unit_onset_diagnostics'][0]['supplied_intervals'] = 2
    bad_exact = deepcopy(values[1])
    bad_exact['entries'][0]['observation']['seconds']['numerator'] = '9007199254740992'
    bad_decimal = deepcopy(values[2])
    bad_decimal[0]['result']['numerator'] = '9007199254740992'
    controls = [lambda: check_onsets(bad_onset, oracle), lambda: check_onsets(bad_summary, oracle),
                lambda: check_exact(bad_exact), lambda: check_decimals(bad_decimal)]
    for check in controls:
        try:
            check()
        except AssertionError:
            continue
        raise AssertionError('negative control survived')
    print(json.dumps({'status': 'pass', 'onset_words': 4, 'onset_units': 2, 'exact_words': 4,
                      'decimal_cases': len(values[2]), 'negative_controls_killed': len(controls),
                      'files': {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                                for p in [*paths, args.join_oracle]},
                      'scope': 'synthetic numerical and onset-diagnostic agreement; not a full decoder, '
                               'recording-identity proof, scanner transform or empirical validation'}, indent=2))


if __name__ == '__main__':
    main()
