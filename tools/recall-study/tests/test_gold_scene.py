#!/usr/bin/env python3
"""Synthetic fixed-support courts; narrative strings reuse admitted baseline-miniatures.json."""
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import gold_scene as gs

# Mutants run as standalone modules but retain the original resource-location context.
if os.environ.get('SCORER_MUTANT'):
    source = Path(os.environ['SCORER_MUTANT']).read_text()
    exec(compile(source, str(HERE / 'gold_scene.py'), 'exec'), gs.__dict__)


class ScorerSuite(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        texts = gs.load(HERE / 'fixtures/baseline-miniatures.json')['recallPackets']
        self.names = ['NN02_synthetic', 'NN03_synthetic']
        self.manifest = {'schema': gs.MANIFEST_SCHEMA, 'sourceInputSha256': 'a' * 64,
                         'partitionSha256': 'b' * 64, 'ruleSha256': gs.sha(gs.RULE_PATH), 'participants': []}
        for name, count in zip(self.names, (3, 1)):
            units = []
            for i in range(count):
                text = texts[i]['text']
                units.append({'id': name + ':u' + str(i), 'ordinal': i, 'textSha256': gs.text_sha(text),
                              'reportTextSha256': gs.text_sha(text), 'onsetSeconds': None if i == 2 else str(i * 1.5),
                              'sourceInputSha256': 'a' * 64})
            self.manifest['participants'].append({'participant': name, 'partition': 'development',
                                                   'recallSha256': 'c' * 64, 'units': units})
        self.manifest_path = self.root / 'manifest.json'
        self.save_manifest()
        self.gold_path = self.root / 'gold.csv'
        self.gold_path.write_text('Subject,Scene,Onset,Offset\n2,2,0,1\n3,3,0,1\n')
        self.support = gs.freeze_support(self.manifest_path, self.gold_path, 'development')
        self.pin = gs.support_digest(self.support)
        self.a = {'schema': gs.ARM_SCHEMA, 'unitManifestSha256': gs.sha(self.manifest_path),
                  'sourceInputSha256': 'a' * 64, 'participants': []}
        for p, scenes in zip(self.manifest['participants'], ([2, 1, 8], [3])):
            self.a['participants'].append({'participant': p['participant'], 'units': [
                {**{k: u[k] for k in ('id', 'ordinal', 'textSha256', 'onsetSeconds')},
                 'outcome': {'kind': 'label', 'scene': scene}} for u, scene in zip(p['units'], scenes)]})
        self.b = copy.deepcopy(self.a)
        self.b['participants'][0]['units'][1]['outcome'] = {'kind': 'abstention'}
        self.b['participants'][1]['units'][0]['outcome'] = {'kind': 'label', 'scene': 1}
        self.arm_dir = self.root / 'arm'
        self.arm_dir.mkdir()
        for p, scenes in zip(self.manifest['participants'], ([2, 1, 8], [3])):
            lines = ['unit\trecallText\trecallOnsetSeconds\tgroup']
            for u, scene in zip(p['units'], scenes):
                lines.append(f"{u['ordinal']}\t{texts[u['ordinal']]['text']}\t{u['onsetSeconds'] or ''}\t{scene}. Synthetic")
            (self.arm_dir / ('recall-map-' + p['participant'] + '.tsv')).write_text('\n'.join(lines) + '\n')

    def save_manifest(self):
        self.manifest_path.write_text(json.dumps(self.manifest))

    def compare(self, a=None, b=None):
        return gs.compare(self.manifest_path, self.support, self.a if a is None else a,
                          self.b if b is None else b, support_sha256=self.pin)

    def test_accepting_identical_complete_arms(self):
        result = self.compare(b=self.a)
        self.assertEqual(result['series']['sceneExact']['primaryParticipantAverageDifferencePoints'], 0)
        self.assertEqual(result['participants'][0]['arms'][0]['sceneExact'], 50)
        self.assertEqual(result['participants'][0]['inputUnits'], 3)

    def test_two_unit_wrong_blank_invalid_and_failure_keep_denominator(self):
        for outcome in [{'kind': 'label', 'scene': 1}, {'kind': 'abstention'},
                        {'kind': 'invalid', 'reason': 'synthetic invalid response'},
                        {'kind': 'provider-failure', 'reason': 'synthetic timeout'}]:
            with self.subTest(outcome=outcome):
                self.b['participants'][0]['units'][1]['outcome'] = outcome
                result = self.compare()
                self.assertEqual(result['participants'][0]['goldEligibleUnits'], 2)
                self.assertEqual(result['participants'][0]['arms'][1]['sceneExact'], 50)

    def test_primary_pooled_ci_and_leave_one_out_independent_answers(self):
        result = self.compare()['series']['sceneExact']
        self.assertEqual(result['primaryParticipantAverageDifferencePoints'], -50)
        self.assertAlmostEqual(result['secondaryPooledUnitWeightedDifferencePoints'], -100 / 3)
        self.assertEqual(result['primaryCI95'], [-100, 0])
        self.assertEqual(result['perParticipantDifferences'], dict(zip(self.names, (0, -100))))
        self.assertEqual([r['primaryDifferencePoints'] for r in result['leaveOneParticipantOut']], [-100, 0])
        self.assertEqual(self.compare()['series'], self.compare()['series'])

    def test_no_gold_and_timing_are_accounted_separately_from_order(self):
        p = self.compare()['participants'][0]
        self.assertEqual((p['inputUnits'], p['goldEligibleUnits'], p['timingEligibleUnits'], p['transcriptOrderEligibleUnits']), (3, 2, 2, 3))
        self.assertEqual(p['exclusions'], {'missing-timing': 1})
        self.assertEqual(p['transitionCoverage'], {'adjacentInputPairs': 2, 'timedPairs': 1, 'goldEligiblePairs': 1})
        self.assertEqual(p['arms'][1]['labelledAdjacentPairs'], 0)  # no bridge over the abstention
        before = self.compare()['series']
        self.b['participants'][0]['units'][2]['outcome'] = {'kind': 'provider-failure', 'reason': 'synthetic timeout'}
        self.assertEqual(self.compare()['series'], before)
        self.assertEqual(self.compare()['participants'][0]['arms'][1]['outcomes']['provider-failure'], 1)

    def test_drop_duplicate_extra_or_changed_unit_id_refused(self):
        for mutation in ('drop', 'duplicate', 'extra', 'changed'):
            b = copy.deepcopy(self.b)
            units = b['participants'][0]['units']
            if mutation == 'drop': units.pop()
            if mutation == 'duplicate': units[1] = copy.deepcopy(units[0])
            if mutation == 'extra': units.append(copy.deepcopy(units[0]))
            if mutation == 'changed': units[0]['id'] = 'foreign'
            with self.subTest(mutation=mutation), self.assertRaisesRegex(ValueError, 'ARM_UNIT_SET'):
                self.compare(b=b)

    def test_text_onset_and_ordinal_identity_mutations_refused(self):
        for field, value in [('textSha256', 'd' * 64), ('onsetSeconds', '0.01'), ('ordinal', 99), ('ordinal', False)]:
            b = copy.deepcopy(self.b)
            b['participants'][0]['units'][0][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'ARM_UNIT_IDENTITY'):
                self.compare(b=b)

    def test_participant_set_and_input_receipt_mutations_refused(self):
        for mutation in ('duplicate', 'drop', 'extra'):
            b = copy.deepcopy(self.b)
            if mutation == 'duplicate': b['participants'][1] = copy.deepcopy(b['participants'][0])
            if mutation == 'drop': b['participants'].pop()
            if mutation == 'extra': b['participants'].append(copy.deepcopy(b['participants'][0]))
            with self.subTest(mutation=mutation), self.assertRaisesRegex(ValueError, 'ARM_PARTICIPANT_SET'):
                self.compare(b=b)
        self.b['sourceInputSha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'ARM_INPUT_IDENTITY'): self.compare()

    def test_frozen_support_mutation_refused_before_scoring(self):
        row = self.support['rows'][1]
        row.update(goldScene=None, exclusion='outside-coded-intervals')
        with self.assertRaisesRegex(ValueError, 'FROZEN_SUPPORT_CHANGED'): self.compare()

    def test_extra_identical_unit_refused(self):
        self.b['participants'][0]['units'].append(copy.deepcopy(self.b['participants'][0]['units'][0]))
        with self.assertRaisesRegex(ValueError, 'ARM_UNIT_SET'): self.compare()

    def test_extra_identical_participant_refused(self):
        self.b['participants'].append(copy.deepcopy(self.b['participants'][0]))
        with self.assertRaisesRegex(ValueError, 'ARM_PARTICIPANT_SET'): self.compare()

    def test_foreign_source_receipt_refused(self):
        self.b['sourceInputSha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'ARM_INPUT_IDENTITY'): self.compare()

    def test_unexpected_tsv_file_refused(self):
        (self.arm_dir / 'misnamed.tsv').write_text('')
        with self.assertRaisesRegex(ValueError, 'UNEXPECTED_PARTICIPANT_FILE'):
            gs.load_arm(self.arm_dir, self.manifest_path, 'development')

    def test_impossible_exclusion_refused_even_under_refreshed_synthetic_pin(self):
        for reason in ('missing-timing', 'excluded-inconsistent-clock', 'no-gold-source'):
            self.support['rows'][1].update(goldScene=None, exclusion=reason)
            self.pin = gs.support_digest(self.support)
            with self.subTest(reason=reason), self.assertRaisesRegex(ValueError, 'SUPPORT_EXCLUSION'):
                self.compare()

    def test_tsv_import_retains_three_rows_and_blank_prediction(self):
        path = self.arm_dir / ('recall-map-' + self.names[0] + '.tsv')
        lines = path.read_text().splitlines()
        lines[2] = '\t'.join(lines[2].split('\t')[:-1]) + '\t'
        path.write_text('\n'.join(lines) + '\n')
        arm = gs.load_arm(self.arm_dir, self.manifest_path, 'development')
        self.assertEqual(len(arm['participants'][0]['units']), 3)
        self.assertEqual(arm['participants'][0]['units'][1]['outcome'], {'kind': 'abstention'})
        self.assertEqual(self.compare(b=arm)['participants'][0]['arms'][1]['sceneExact'], 50)

    def test_tsv_missing_duplicate_text_onset_and_unexpected_file_mutations(self):
        path = self.arm_dir / ('recall-map-' + self.names[0] + '.tsv')
        original = path.read_text().splitlines()
        for field, value in [('drop', None), ('duplicate', None), ('text', 'foreign'), ('time', '9.0')]:
            lines = original.copy()
            if field == 'drop': lines.pop()
            elif field == 'duplicate': lines[2] = lines[1]
            else:
                cells = lines[1].split('\t');cells[1 if field == 'text' else 2] = value;lines[1] = '\t'.join(cells)
            path.write_text('\n'.join(lines) + '\n')
            with self.subTest(field=field), self.assertRaises(ValueError):
                gs.load_arm(self.arm_dir, self.manifest_path, 'development')
        path.write_text('\n'.join(original) + '\n')
        (self.arm_dir / 'misnamed.tsv').write_text('')
        with self.assertRaisesRegex(ValueError, 'UNEXPECTED_PARTICIPANT_FILE'):
            gs.load_arm(self.arm_dir, self.manifest_path, 'development')

    def test_empty_and_singleton_populations_have_explicit_undefined_cis(self):
        for row in self.support['rows']:
            if row['participant'] == self.names[1]: row.update(goldScene=None, exclusion='outside-coded-intervals')
        self.pin = gs.support_digest(self.support)
        result = self.compare()
        self.assertEqual(result['eligibleParticipants'], 1)
        self.assertEqual(result['series']['sceneExact']['primaryCI95'], [None, None])
        self.assertIsNone(result['series']['sceneExact']['leaveOneParticipantOut'][0]['primaryDifferencePoints'])
        for row in self.support['rows']:
            if row['exclusion'] is None: row.update(goldScene=None, exclusion='outside-coded-intervals')
        self.pin = gs.support_digest(self.support)
        result = self.compare()
        self.assertEqual(result['inputParticipants'], 2)
        self.assertIsNone(result['series']['sceneExact']['primaryParticipantAverageDifferencePoints'])

    def test_gold_boundaries_overlap_and_nonfinite_refusal(self):
        self.assertEqual(gs.scene_at([(0, 1.5, 2), (1.5, 3, 3)], 1.5), 2)
        self.assertIsNone(gs.scene_at([(0, 1.5, 2)], 1.50001))
        for bad in ['NaN', 'Infinity', '-1', '1e999']:
            with self.subTest(bad=bad), self.assertRaisesRegex(ValueError, 'INVALID_ONSET'):
                gs.onset(bad)

    def test_malformed_outcomes_do_not_acquire_a_label(self):
        for value in [{'kind': 'label', 'scene': True}, {'kind': 'label', 'scene': 51},
                      {'kind': 'abstention', 'scene': 2}, {'kind': 'provider-failure'}, {'kind': 'invented'}]:
            with self.subTest(value=value), self.assertRaises(ValueError): gs.Outcome.read(value)

    def test_cli_exits_nonzero_and_creates_no_output_on_malformed_arm(self):
        self.b['participants'][0]['units'].pop()
        paths = []
        for name, value in [('support', self.support), ('a', self.a), ('b', self.b)]:
            path = self.root / (name + '.json');path.write_text(json.dumps(value));paths.append(path)
        out = self.root / 'result.json'
        run = subprocess.run([sys.executable, str(HERE / 'gold_scene.py'), 'compare', str(self.manifest_path),
                              *map(str, paths), str(out), '--support-sha256', self.pin], capture_output=True, text=True)
        self.assertNotEqual(run.returncode, 0)
        self.assertIn('ARM_UNIT_SET', run.stderr)
        self.assertFalse(out.exists())


if __name__ == '__main__':
    unittest.main()
