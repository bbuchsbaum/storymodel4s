import copy
import json
import math
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from organization_challenge import (CHANNELS, aggregate, edit_distance, fit_temperatures,
                                    log_distribution, normalized, summarize, preflight_fit, sha, verify_native_rows)


class OrganizationChallengeTests(unittest.TestCase):
    def observation(self, channel, target=0, split='development'):
        states = [{'state':'a', 'destination':'a', 'cost':0., 'excluded':False},
                  {'state':'b', 'destination':'b', 'cost':1., 'excluded':False}]
        return {'split':split,'channel':channel,'group':'g','condition':'exact','status':'complete',
                'labels':[{'kind':'point','target':target}],
                'edits':[],'normalizationReasons':['unprocessed'],
                'costs':[{'states':states}],
                'result':{'rows':[{'status':'Available'}], 'order':[{'target':'a','position':0},{'target':'b','position':1}],
                          'counts':{'Backward':0,'Forward':0,'SameTarget':0}}}

    def test_native_state_multiplicity_and_external_pool_are_preserved(self):
        states = [{'destination':d,'cost':c,'excluded':False} for d,c in [('a',0),('a',0),('b',0),('ext:Intrusion',1),('ext:Commentary',1)]]
        row=log_distribution(states,{'a':0,'b':1},1)
        den=3+2/math.e
        self.assertAlmostEqual(math.exp(row['0']),2/den)
        self.assertAlmostEqual(math.exp(row['external']),2/math.e/den)
        self.assertEqual(row['2'],-math.inf)

    def test_extreme_cost_difference_retains_finite_log_loss_without_clipping(self):
        row=log_distribution([{'destination':'a','cost':0,'excluded':False},
                              {'destination':'b','cost':100,'excluded':False}],{'a':0,'b':1},.01)
        self.assertEqual(row['1'],-10000)

    def test_unranked_is_unavailable_not_an_external_success(self):
        self.assertIsNone(log_distribution([{'destination':'ext:Unranked','cost':0,'excluded':False}],{},1))

    def test_fit_rejects_heldout_injection(self):
        observations=[self.observation(ch) for ch in CHANNELS]
        observations.append(self.observation('lexical',split='heldout'))
        with self.assertRaisesRegex(ValueError,'development only'):
            fit_temperatures(observations,[.1,1])

    def test_fit_has_independent_known_limits_and_deterministic_tie_break(self):
        rows=[self.observation(ch) for ch in CHANNELS]
        self.assertEqual(fit_temperatures(rows,[1,.1])[0],dict.fromkeys(CHANNELS,.1))
        for row in rows:
            row['costs'][0]['states'][1]['cost']=0
        self.assertEqual(fit_temperatures(rows,[1,.1])[0],dict.fromkeys(CHANNELS,.1))
        for row in rows:
            row['costs'][0]['states'][1]['cost']=1
            row['labels'][0]['target']=1
        self.assertEqual(fit_temperatures(rows,[1,.1])[0],dict.fromkeys(CHANNELS,1))

    def test_collision_labels_cannot_affect_selected_temperature(self):
        rows=[self.observation(ch) for ch in CHANNELS]
        extra=copy.deepcopy(rows)
        for row in extra:
            row['labels']=[{'kind':'set','targets':[1]}]
        self.assertEqual(fit_temperatures(rows,[.1,1]),fit_temperatures(rows+extra,[.1,1]))

    def test_absent_truth_refuses_fit_instead_of_choosing_default(self):
        with self.assertRaisesRegex(ValueError,'nonfinite'):
            fit_temperatures([self.observation(ch,target=3) for ch in CHANNELS],[.1,1])

    def test_unavailable_failure_prevents_development_fit(self):
        rows=[self.observation(ch) for ch in CHANNELS]
        rows[0]['status']='failed'
        with self.assertRaisesRegex(ValueError,'unavailable'):
            fit_temperatures(rows,[.1,1])
        summary=aggregate([summarize(rows[0],1)])
        self.assertEqual((summary['expected_rows'],summary['available_rows']),(1,0))
        self.assertIsNone(summary['nll'])

    def test_preflight_rejects_substituted_fit_before_native_launch(self):
        plan=b'{}'
        fit=json.dumps({'producer_commit':'producer','plan_sha256':sha(plan)}).encode()
        with patch('organization_challenge.ancestor'), patch('organization_challenge.committed',return_value=fit):
            preflight_fit(fit,'fit-commit','producer',plan)
        with patch('organization_challenge.ancestor'), patch('organization_challenge.committed',return_value=fit+b'\n'):
            with self.assertRaisesRegex(ValueError,'already be committed'):
                preflight_fit(fit,'fit-commit','producer',plan)

    def test_partial_result_still_checks_available_native_mass(self):
        item={'temperature':1,'result':{'order':[{'target':'a','position':0}],
              'rows':[{'unit':'u0','status':'Unresolved','mass':[]},
                      {'unit':'u1','status':'Available','mass':[{'destination':'a','mass':1}]}]},
              'costs':[{'unit':'u0','states':[]}, {'unit':'u1','states':[
                  {'state':'a','destination':'a','cost':0,'excluded':False}]}]}
        verify_native_rows(item,1e-10)
        self.assertEqual(item['verified_rows'],1)
        self.assertEqual(item['maximum_mass_readback_error'],0)
        item['result']['rows'][1]['mass'][0]['mass']=.5
        with self.assertRaisesRegex(ValueError,'do not reproduce'):
            verify_native_rows(item,1e-10)
        item['result']['rows'][1]['status']='Unresolved'
        verify_native_rows(item,1e-10)
        self.assertEqual(item['verified_rows'],0)
        self.assertIsNone(item['maximum_mass_readback_error'])


    def test_admitted_challenge_split_and_collision_guards(self):
        manifest=json.loads((Path(__file__).parents[1]/'fixtures/organization-challenge/manifest.json').read_text())
        observed={}
        for split in ['development','heldout']:
            groups=[g for g in manifest['groups'] if g['split']==split]
            self.assertEqual(len(groups),4)
            observed[split]={s for g in groups for c in g['cases'] for s in c['recall']}
            for g in groups:
                self.assertEqual(len(g['source']),4)
                for c in g['cases']:
                    transformed,reasons,edits=normalized(g['source'],c['recall'],'repaired-minilm')
                    self.assertEqual(len(transformed),4)
                    if c['condition'] in ['exact','collision']:
                        self.assertEqual(transformed,c['recall'])
                        self.assertEqual(edits,[])
        self.assertFalse(observed['development'] & observed['heldout'])

    def test_transposition_is_two_edits_and_candidate_is_source_order_invariant(self):
        self.assertEqual(edit_distance('Nora','Nroa'),2)
        self.assertEqual(normalized(['Nora','Noah'],['Nroa'],'repaired-minilm'),
                         normalized(['Noah','Nora'],['Nroa'],'repaired-minilm'))
        self.assertEqual(normalized(['Anna','Anya'],['An'],'repaired-minilm')[1],['ambiguous-match'])


if __name__=='__main__':
    unittest.main()
