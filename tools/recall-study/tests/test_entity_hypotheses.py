import copy
import itertools
import json
import math
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import entity_hypotheses as e
from read_organization import CATEGORIES as NATIVE_CATEGORIES


def point(key):
    return {k: float(k == str(key)) for k in e.KEYS}


def exhaustive(rows):
    totals = dict.fromkeys(e.CATEGORIES, 0.0)
    for path in itertools.product(e.KEYS, repeat=len(rows)):
        weight = math.prod(row[k] for row,k in zip(rows,path))
        for a,b in zip(path,path[1:]):
            kind = ('External' if a == 'external' or b == 'external' else 'SameTarget' if a == b
                    else 'Backward' if int(a) > int(b) else 'Forward')
            totals[kind] += weight
    return totals


class EntityHypothesisTests(unittest.TestCase):
    source = ['Anna', 'Anya', 'Anna', 'Anya']

    def test_distinct_and_unresolved_survive_unique_retrieval(self):
        options=e.hypotheses(self.source,'Ann')
        self.assertEqual([h['kind'] for h in options],['unresolved','distinct','known'])
        self.assertEqual(options[-1]['name'],'Anna')
        self.assertEqual(options[0]['rendered'],'Ann')

    def test_multiple_matches_and_failed_retrieval_are_observable(self):
        self.assertEqual([h['name'] for h in e.hypotheses(self.source,'An')],[None,None,'Anna','Anya'])
        self.assertEqual([h['kind'] for h in e.hypotheses(['Clara','Clive'],'Cl')],['unresolved','distinct'])
        self.assertEqual(e.hypotheses(self.source,'Anna'),[{'kind':'known','name':'Anna','rendered':'Anna'}])

    def test_unknown_weights_never_become_uniform_or_a_point_distribution(self):
        summary=e.robust_summary([point(0),point('external')])
        self.assertIsNone(summary['interpretation_weights'])
        self.assertIsNone(summary['point_distribution'])
        self.assertIsNone(summary['robust_winner'])
        self.assertEqual(summary['mass_ranges']['0'],[0,1])
        self.assertEqual(e.robust_summary([point(0),point(0)])['robust_winner'],'0')

    def test_source_order_does_not_select_identity(self):
        self.assertEqual(e.hypotheses(self.source,'An'),e.hypotheses(list(reversed(self.source)),'An'))

    def test_evaluation_labels_and_condition_names_do_not_enter_variants(self):
        path=Path(__file__).resolve().parents[3]/e.MANIFEST
        manifest=e.load(path.read_text())
        changed=copy.deepcopy(manifest)
        for group in changed['groups']:
            for case in group['cases']:
                case['condition']='novel-name'
                case['labels']=[{'kind':'point','target':'external'}]*4
        before=[v for _,_,v in e.inventory(manifest)]
        after=[v for _,_,v in e.inventory(changed)]
        self.assertEqual(before,after)
        self.assertGreater(len(before),28)

    def states(self):
        return ([{'state':f'{i}-{mode}','destination':str(i),'cost':0,'excluded':False}
                 for i,mode in itertools.product(range(4),['faithful','distorted'])]+
                [{'state':d,'destination':d,'cost':0,'excluded':False} for d in sorted(e.EXTERNALS)])

    def test_identity_condition_preserves_state_multiplicity_and_external_states(self):
        states=self.states();positions={str(i):i for i in range(4)}
        known=e.masses(e.condition(states,positions,self.source,{'kind':'known','name':'Anna'},.5))
        self.assertAlmostEqual(known['0'],2/9)
        self.assertAlmostEqual(known['2'],2/9)
        self.assertEqual(known['1'],0)
        self.assertEqual(known['3'],0)
        self.assertAlmostEqual(known['external'],5/9)
        distinct=e.masses(e.condition(states,positions,self.source,{'kind':'distinct','name':None},.5))
        self.assertEqual(distinct,point('external'))
        original=e.masses(e.condition(states,positions,self.source,{'kind':'unresolved','name':None},.5))
        self.assertAlmostEqual(original['0'],2/13)
        self.assertAlmostEqual(original['external'],5/13)

    def test_nonfinite_inputs_and_missing_identity_refuse(self):
        states=self.states();positions={str(i):i for i in range(4)}
        for value in [float('nan'),float('inf'),0,-1]:
            with self.assertRaises(ValueError):e.condition(states,positions,self.source,{'kind':'unresolved'},value)
        bad=copy.deepcopy(states);bad[0]['cost']=float('nan')
        with self.assertRaises(ValueError):e.condition(bad,positions,self.source,{'kind':'distinct'},.5)
        with self.assertRaises(ValueError):e.condition(states,positions,self.source,{'kind':'known','name':'Clara'},.5)

    def test_complete_assignment_ranges_match_exhaustive_destination_paths(self):
        diffuse={'0':.15,'1':.2,'2':.25,'3':.1,'external':.3}
        options=[[point(0),diffuse],[point(1),point('external')],[point(2),diffuse],[point(3)]]
        actual=e.count_ranges(options)
        refs=[exhaustive(rows) for rows in itertools.product(*options)]
        self.assertEqual(actual['assignment_count'],8)
        for k in e.CATEGORIES:
            for a,b in zip(actual['ranges'][k],[min(r[k] for r in refs),max(r[k] for r in refs)]):
                self.assertAlmostEqual(a,b,places=13)
        self.assertIsNone(actual['joint_interpretation_weights'])
        self.assertIsNone(actual['point_counts'])

    def test_unknown_joint_dependence_is_not_product_of_averaged_rows(self):
        options=[[point(0),point(1)],[point(0),point(1)]]
        self.assertEqual(e.count_ranges(options)['ranges']['SameTarget'],[0,1])
        mean={'0':.5,'1':.5,'2':0,'3':0,'external':0}
        self.assertEqual(e.counts([mean,mean])['counts']['SameTarget'],.5)
        # Identical marginals also permit shared draws (1) and opposite draws (0).
        self.assertEqual((e.counts([point(0),point(0)])['counts']['SameTarget']+
                          e.counts([point(1),point(1)])['counts']['SameTarget'])/2,1)

    def test_unavailable_interior_row_does_not_bridge_gap(self):
        result=e.counts([point(0),None,point(2),point(3)])
        self.assertEqual(result['counts'],{'Backward':0,'Forward':1,'SameTarget':0,'External':0})
        self.assertEqual(result['measured_adjacencies'],1)
        self.assertEqual(result['unavailable_adjacencies'],2)
        ranges=e.count_ranges([[point(0)],[None],[point(2)],[point(3)]])
        self.assertFalse(ranges['complete'])
        self.assertIsNone(ranges['ranges'])

    def test_identical_observation_keeps_hypotheses_when_truth_changes(self):
        h=e.hypotheses(self.source,'Ann')
        observed=e.robust_summary([point(0),point('external')])
        for label in [{'kind':'point','target':0},{'kind':'point','target':'external'}]:
            e.score({'0':math.log(.6),'1':-math.inf,'2':-math.inf,'3':-math.inf,'external':math.log(.4)},label)
            self.assertEqual(e.hypotheses(self.source,'Ann'),h)
            self.assertEqual(e.robust_summary([point(0),point('external')]),observed)

    def read_failure_inventory(self, mode, corrupt=False):
        repo=Path(__file__).resolve().parents[3]
        mb,pb=(repo/e.MANIFEST).read_bytes(),(repo/e.PLAN).read_bytes()
        expected=e.inventory(e.load(mb.decode()))
        entries=[]
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            for i,(group,case,processed) in enumerate(expected):
                item={'group':group['id'],'condition':case['condition'],
                      'sourceSha256':e.sha(' '.join(group['source']).encode()),
                      'originalSha256':e.sha(' '.join(case['recall']).encode()),
                      'processedSha256':e.sha(' '.join(processed).encode()),
                      'processedClauseSha256':[e.sha(s.encode()) for s in processed]}
                if mode=='failed':
                    item.update(status='failed',reasonSha256='controlled-failure')
                else:
                    rows=[{'unit':f'u{j}','status':'Unresolved','mass':[],'admissible':[]} for j in range(4)]
                    costs=[{'unit':f'u{j}','states':[]} for j in range(4)]
                    if mode=='partial':
                        rows[0].update(status='Available',mass=[{'destination':'s0','mass':1}],admissible=['s0'])
                        costs[0]['states']=[{'state':'a','destination':'s0','cost':0,'excluded':False}]
                        if corrupt:
                            costs[0]['states'].append({'state':'b','destination':'s1','cost':0,'excluded':False})
                    per_transition={k:int(k=='Unresolved') for k in NATIVE_CATEGORIES}
                    result={'schemaVersion':'organization-readout/v1','dependence':'DeclaredIndependentRows',
                            'orderKind':'TextDiscourseRanks','axis':'Discourse',
                            'order':[{'target':f's{j}','position':j} for j in range(4)],'rows':rows,'units':4,
                            'transitions':[{'left':f'u{j}','right':f'u{j+1}','counts':per_transition} for j in range(3)],
                            'adjacencies':3,'counts':{k:3*v for k,v in per_transition.items()},'comparable':0,
                            'measuredUnits':int(mode=='partial'),'measuredTransitions':0}
                    item.update(status='complete',nativeResult=result,costs=costs,
                                unitCorrespondence=[{'index':j,'unit':f'u{j}',
                                  'originalClauseSha256':e.sha(case['recall'][j].encode()),
                                  'processedClauseSha256':e.sha(s.encode()),
                                  'unitClauseSha256':e.sha(s.strip().removesuffix('.').encode())} for j,s in enumerate(processed)])
                data=json.dumps(item).encode();name=f'variant-{i}.json'
                (root/name).write_bytes(data);entries.append({'path':name,'sha256':e.sha(data)})
            (root/'manifest.json').write_text(json.dumps({'schemaVersion':'entity-hypothesis-native/v1','files':entries,
              'metadata':{'producerCommit':'producer','planCommit':'plan','planSha256':e.sha(pb),
                          'manifestSha256':e.sha(mb),'modelSha256':e.MODEL,'tokenizerSha256':e.TOKENIZER,
                          'cases':len(expected)}}))
            # Mock only Git transport. Byte inventory, native counts, cost readback,
            # clause correspondence and unchanged-row factorization checks are real.
            with patch.object(e,'ancestor'),patch.object(e,'committed',side_effect=lambda _,p:pb if p==e.PLAN else mb):
                return e.read_native(root,repo/e.MANIFEST,repo/e.PLAN)[-1]

    def test_all_failed_reader_has_no_measured_error(self):
        result=self.read_failure_inventory('failed')
        self.assertEqual(len(result['failures']),result['native_variants'])
        self.assertEqual(result['verified_rows'],0)
        self.assertIsNone(result['maximum_mass_error'])

    def test_all_unavailable_reader_has_no_measured_error(self):
        result=self.read_failure_inventory('unavailable')
        self.assertEqual(result['failures'],[])
        self.assertEqual(result['verified_rows'],0)
        self.assertIsNone(result['maximum_mass_error'])

    def test_partial_reader_measures_only_available_rows(self):
        result=self.read_failure_inventory('partial')
        self.assertEqual(result['verified_rows'],result['native_variants'])
        self.assertEqual(result['maximum_mass_error'],0)

    def test_partial_reader_still_refuses_false_costs(self):
        with self.assertRaisesRegex(ValueError,'emitted costs do not reproduce destination masses'):
            self.read_failure_inventory('partial',corrupt=True)


if __name__=='__main__':
    unittest.main()
