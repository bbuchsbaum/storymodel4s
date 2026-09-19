#!/usr/bin/env python3
"""Preregistered numeric oracle; --scala-witness adds actual cross-language agreement."""
import argparse
import json
from pathlib import Path
import sys
import tempfile
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import gold_scene as gs

# Independent transcription of the committed preregistration, including both exclusions.
EXPECTED = [None, None, 2, 3, 4, None, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, None]


def observations():
    return {'ruleSha256': gs.sha(gs.RULE_PATH), 'trSeconds': gs.TR,
            'participants': [{'participant': n, 'parsed': gs.participant_number(f'NN{n:02d}_synthetic.csv'),
                              'goldSubject': gs.gold_subject_of(n)} for n in range(19)]}


def validate_scala(path):
    actual = gs.load(path)
    assert actual == observations(), 'PYTHON_SCALA_RULE_DISAGREEMENT'
    return actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scala-witness', type=Path)
    args = parser.parse_args()
    assert gs.TR == 1.5
    assert [gs.gold_subject_of(n) for n in range(19)] == EXPECTED
    assert gs.EXCLUDED == {'NN01'}
    for n in range(1, 18):
        assert gs.participant_number(f'NN{n:02d}_synthetic.csv') == n
        assert gs.participant_number(f'NN{n:02d}') == n
    for name in ('NN03foreign', 'NN00_invalid', 'NN18_invalid', 'README.md'):
        assert gs.participant_number(name) is None
    if args.scala_witness:
        validate_scala(args.scala_witness)
        print('PASS actual Python/Scala agreement: TR, parsed IDs, all 17 aliases and boundary refusals')
    else:
        print('PASS Python preregistration oracle; cross-language check requires --scala-witness')
    # A descriptor cannot supply a second authority; absent or changed values refuse.
    with tempfile.TemporaryDirectory() as temp:
        path = Path(temp) / 'descriptor.json'
        doc = {'schema': 'storymodel4s.corpus.descriptor', 'schemaVersion': 1,
               'goldRule': {'trSeconds': gs.TR, 'excludedParticipants': sorted(gs.EXCLUDED),
                            'participantPattern': gs.PARTICIPANT_PATTERN}}
        path.write_text(json.dumps(doc))
        assert gs.configure(path)['TR'] == 1.5
        doc['goldRule']['trSeconds'] = 2.0
        path.write_text(json.dumps(doc))
        try:
            gs.configure(path)
            raise AssertionError('changed descriptor admitted')
        except ValueError as error:
            assert 'DESCRIPTOR_RULE_DISAGREEMENT' in str(error)
    return 0


if __name__ == '__main__':
    sys.exit(main())
