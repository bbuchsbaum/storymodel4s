import copy
import itertools
import math
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from organization_diagnosis import (closed_form_pair, conditional_counts, cost_readback,
                                     generative_control, packet_diagnosis)


class OrganizationDiagnosisTests(unittest.TestCase):
    def test_closed_form_matches_explicit_pair_enumeration_for_every_slot(self):
        for k in range(2, 7):
            for p in (0, .25, .5, .75, 1):
                for t, u in itertools.product(range(k), repeat=2):
                    rows = [[p if i == actual else (1 - p) / (k - 1) for i in range(k)] for actual in (t, u)]
                    expected = conditional_counts(rows)
                    for a, b in zip(closed_form_pair(t, u, k, p), expected):
                        self.assertAlmostEqual(a, b, places=13)

    def test_perfect_missing_row_has_only_coverage_deficit(self):
        case = {'path': [0, 1, 2, 3], 'omitted': 1, 'correct_mass': 1.0,
                'storage': [3, 2, 1, 0], 'counts': [0, 1, 0],
                'truth_counts': [0, 3, 0], 'bias': [0, -2, 0]}
        result = packet_diagnosis({'experiments': [case]}, 1e-10)['cases'][0]
        self.assertEqual(result['assignment_discrepancy'], [0, 0, 0])
        self.assertEqual(result['coverage_deficit'], [0, -2, 0])
        self.assertEqual(result['measurable_truth_counts'], [0, 1, 0])

    def test_packet_oracle_rejects_wrong_counts(self):
        case = {'path': [0, 1], 'omitted': None, 'correct_mass': 1.,
                'storage': [0, 1], 'counts': [1, 0, 0],
                'truth_counts': [0, 1, 0], 'bias': [1, -1, 0]}
        with self.assertRaisesRegex(ValueError, 'closed-form'):
            packet_diagnosis({'experiments': [case]}, 1e-10)

    def test_generative_perfect_and_uninformative_limits(self):
        perfect = generative_control(4, 3, 1)
        self.assertEqual(perfect['maximum_path_conditional_discrepancy'], 0)
        self.assertEqual(perfect['observation_controls'], 64)
        unknown = generative_control(4, 3, .25)
        for observed, expected in zip(unknown['population_posterior_expected_counts'], [.75, .75, .5]):
            self.assertAlmostEqual(observed, expected, places=13)
        self.assertGreater(unknown['maximum_path_conditional_discrepancy'], 0)

    def test_informative_bayes_control_rejects_ignore_observations_mutant(self):
        def mutant(observed, channel):
            return [[1 / len(channel)] * len(channel) for _ in observed]
        for p in (.5, .75, 1):
            with self.assertRaisesRegex(ValueError, 'observation-conditional Bayes marginals'):
                generative_control(4, 3, p, infer=mutant)
        # Same mutant passes the population-only check and the no-information condition.
        result = generative_control(4, 3, .25, infer=mutant)
        self.assertEqual(result['population_posterior_expected_counts'], [.75, .75, .5])

    def test_informative_bayes_population_equality_despite_path_shrinkage(self):
        for p in (.5, .75):
            result = generative_control(4, 3, p)
            self.assertGreater(result['maximum_path_conditional_discrepancy'], 0)
            for a, b in zip(result['population_truth'], result['population_posterior_expected_counts']):
                self.assertAlmostEqual(a, b, places=11)

    def cost_artifact(self):
        weights = {'sit:a': 2 / (3 + math.exp(-1)), 'sit:b': 1 / (3 + math.exp(-1)), 'ext:Extra': math.exp(-1) / (3 + math.exp(-1))}
        states = [{'state': key, 'destination': destination, 'cost': cost, 'excluded': False}
                  for key, destination, cost in [('a', 'sit:a', 0), ('ad', 'sit:a', 0), ('b', 'sit:b', 0), ('e', 'ext:Extra', 1)]]
        return {'temperature': 1.0,
                'result': {'rows': [{'unit': str(i), 'status': 'Available', 'mass': [{'destination': k, 'mass': v} for k, v in weights.items()]} for i in range(2)],
                           'order': [{'target': 'sit:a', 'position': 0}, {'target': 'sit:b', 'position': 1}]},
                'costs': [{'unit': str(i), 'states': copy.deepcopy(states)} for i in range(2)]}

    def test_cost_readback_preserves_tied_state_multiplicity_and_external_mass(self):
        result = cost_readback(self.cost_artifact(), 1e-10)
        self.assertEqual(result['zero_temperature_limit_rows'], [{'sit:a': 2/3, 'sit:b': 1/3}] * 2)
        self.assertAlmostEqual(result['zero_temperature_limit_counts']['SameTarget'], 5/9)
        self.assertEqual(result['zero_temperature_limit_counts']['External'], 0)

    def test_cost_readback_rejects_substituted_score(self):
        artifact = self.cost_artifact()
        artifact['costs'][0]['states'][0]['cost'] = 1
        with self.assertRaisesRegex(ValueError, 'do not reproduce'):
            cost_readback(artifact, 1e-10)

    def test_cost_readback_rejects_duplicate_state(self):
        artifact = self.cost_artifact()
        artifact['costs'][0]['states'].append(artifact['costs'][0]['states'][0])
        with self.assertRaisesRegex(ValueError, 'duplicate priced state'):
            cost_readback(artifact, 1e-10)


if __name__ == '__main__':
    unittest.main()
