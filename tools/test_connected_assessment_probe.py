"""Original synthetic protocol fixtures only; no narrative, media or participant excerpts."""
import copy
import json
import unittest

import connected_assessment_probe as p


def fixture():
    claim = 'A woman is on the floor and is dead.'
    roles = [dict(id='r1', kind='entity', quote='A woman', argument='depicted person')]
    components = [dict(id=key, text=text, quote=quote, facet='attribute', roles=copy.deepcopy(roles),
                       assessment=dict(core=core, polarity='positive', scopeNote='Complete predicate in stated scope.',
                                       mode='world', exclusivity=None)) for key, text, quote, core in (
        ('c1', 'A person is on the floor.', 'on the floor', 'A person is on the floor.'),
        ('c2', 'A person is dead.', 'is dead', 'A person is dead.'))]
    r = dict(question='synthetic-only', claim=claim, intervals=[],
             items=[dict(id='e1', text='Ana is on the floor.'), dict(id='e2', text='Beth is dead.')],
             inventory=dict(parentClaim=claim, logic='all', eventPolicy='compatible_situation', components=components))
    v = dict(claim_id='', citations=[], handles=[], connections=[], components=[
        dict(component_id='c1', affirms=[], denies=[], unresolved=[]),
        dict(component_id='c2', affirms=[], denies=[], unresolved=['partial_evidence'])])
    source = add_source(r, v, 'e1', 'a')
    v['components'][0]['affirms'] = [dict(sources=[source], basis=dict(kind='direct', explanation='Explicit floor assertion.'))]
    return r, v


def add_source(r, v, evidence, prefix, *, roles=True, scope='world', occurrence=None):
    v['citations'].append(dict(id=prefix+'q', evidence_id=evidence,
                              quote=next(i['text'] for i in r['items'] if i['id']==evidence)))
    for suffix, kind in [('p', 'entity'), ('s', 'situation'), ('k', 'context')]:
        v['handles'].append(dict(id=prefix+suffix, kind=kind, citation=prefix+'q',
                                scope=scope if kind=='context' else None))
    if occurrence:
        v['handles'].append(dict(id=prefix+'o', kind=occurrence, citation=prefix+'q', scope=None))
    return dict(citation=prefix+'q', roles={'r1':prefix+'p'} if roles else {},
                situation=prefix+'s', context=prefix+'k', occurrence=prefix+'o' if occurrence else None)


def connect(v, relation, a, b, basis='direct_correspondence'):
    handles={h['id']:h for h in v['handles']}
    v['connections'].append(dict(id='edge'+str(len(v['connections'])), relation=relation, left=a, right=b,
                                citations=[handles[a]['citation'], handles[b]['citation']],
                                basis=basis, explanation='Explicitly corresponding mentions in this synthetic control.'))


def assess(r, v):
    v['claim_id']=p.model_input(r)['claim_id']
    return p.validate_proposal(r, json.dumps(v))


def statuses(result, field='status'):
    return [c[field] for c in result['components']]


class ConnectedTests(unittest.TestCase):
    def test_unknown_modifier_does_not_erase_floor(self):
        r,v=fixture()
        r['inventory']['components'][1]['assessment']['core']='The depicted person is a woman.'
        out=assess(r,v)
        self.assertEqual(statuses(out),['Supported','Unresolved'])
        self.assertNotIn('A woman',json.dumps(p.model_input(r)['components'][0]['roles']))

    def test_different_people_cannot_supply_one_persons_details(self):
        r,v=fixture();second=add_source(r,v,'e2','b')
        v['components'][1].update(affirms=[dict(sources=[second],basis=dict(kind='direct',explanation='Beth is dead.'))],unresolved=[])
        connect(v,'same_situation','as','bs');connect(v,'same_context','ak','bk')
        out=assess(r,v)
        self.assertEqual(statuses(out,'localStatus'),['Supported','Supported'])
        self.assertEqual(statuses(out),['Unresolved','Unresolved'])

    def test_one_person_distinct_predicates_need_not_be_one_event(self):
        r,v=fixture();r['items'][1]['text']='Ana wears a coat.'
        r['inventory']['components'][1]['assessment']['core']='The depicted person wears a coat.'
        second=add_source(r,v,'e2','b')
        v['components'][1].update(affirms=[dict(sources=[second],basis=dict(kind='direct',explanation='Explicit clothing.'))],unresolved=[])
        for kind,suffix in [('entity','p'),('situation','s'),('context','k')]:connect(v,'same_'+kind,'a'+suffix,'b'+suffix)
        self.assertEqual(statuses(assess(r,v)),['Supported','Supported'])

    def test_explicit_inequality_overrides_transitive_identity(self):
        r,v=fixture();second=add_source(r,v,'e2','b')
        v['handles'].append(dict(id='cp',kind='entity',citation='bq',scope=None))
        connect(v,'same_entity','ap','cp');connect(v,'same_entity','cp','bp');connect(v,'different_entity','ap','bp')
        out=assess(r,v)
        self.assertEqual(statuses(out),['Unresolved','Unresolved'])

    def test_complementary_speaker_and_asr_union(self):
        r,v=fixture();r['inventory']['logic']='atomic';r['inventory']['components']=r['inventory']['components'][:1]
        c=r['inventory']['components'][0];c['assessment'].update(core='A person says that the gate is closed.',mode='assertion')
        r['items']=[dict(id='e1',text='Ana speaks these words: the gate is closed.'),dict(id='e2',text='The gate is closed.')]
        v.update(citations=[],handles=[],connections=[],components=v['components'][:1])
        first=add_source(r,v,'e1','a',scope='assertion',occurrence='assertion')
        second=add_source(r,v,'e2','b',roles=False,scope='assertion',occurrence='assertion')
        for kind,suffix in [('situation','s'),('context','k'),('assertion','o')]:connect(v,'same_'+kind,'a'+suffix,'b'+suffix)
        v['components'][0]['affirms']=[dict(sources=[first,second],basis=dict(kind='direct',explanation='Annotation identifies speaker; corresponding ASR supplies words.'))]
        self.assertEqual(statuses(assess(r,v)),['Supported'])
        v['connections']=[e for e in v['connections'] if e['relation']!='same_assertion']
        self.assertEqual(statuses(assess(r,v)),['Unresolved'])

    def test_same_words_another_utterance_cannot_compose(self):
        r,v=fixture();r['inventory']['components'][0]['assessment']['mode']='assertion'
        v['handles'][2]['scope']='assertion'
        v['handles'].append(dict(id='ao',kind='assertion',citation='aq',scope=None))
        v['components'][0]['affirms'][0]['sources'][0]['occurrence']='ao'
        second=add_source(r,v,'e2','b',roles=False,scope='assertion',occurrence='assertion')
        v['components'][0]['affirms'][0]['sources'].append(second)
        connect(v,'same_situation','as','bs');connect(v,'same_context','ak','bk')
        connect(v,'different_assertion','ao','bo')
        self.assertEqual(statuses(assess(r,v)),['Unresolved','Unresolved'])

    def test_polarity_hidden_and_applied_after_same_core_assessment(self):
        r,v=fixture();negative=copy.deepcopy(r)
        negative['inventory']['components'][0]['assessment']['polarity']='negative'
        self.assertEqual(p.model_input(r),p.model_input(negative))
        self.assertEqual(statuses(assess(r,v)),['Supported','Unresolved'])
        self.assertEqual(statuses(assess(negative,copy.deepcopy(v))),['Contradicted','Unresolved'])
        proof=v['components'][0]['affirms'].pop();proof['basis']=dict(kind='explicit_negation',explanation='Explicit source negation of the whole core.')
        v['components'][0]['denies']=[proof]
        r['items'][0]['text']='Ana is not on the floor.'
        negative['items'][0]['text']=r['items'][0]['text'];v['citations'][0]['quote']=r['items'][0]['text']
        self.assertEqual(statuses(assess(r,v)),['Contradicted','Unresolved'])
        self.assertEqual(statuses(assess(negative,copy.deepcopy(v))),['Supported','Unresolved'])

    def test_denial_cannot_use_direct_compatible_information_for_either_polarity(self):
        for polarity in ('positive','negative'):
            r,v=fixture();r['inventory']['components'][0]['assessment'].update(core='An object is white and speckled.',polarity=polarity)
            v['components'][0]['denies']=v['components'][0]['affirms'];v['components'][0]['affirms']=[]
            with self.assertRaisesRegex(ValueError,'denial needs positive incompatibility'):
                assess(r,v)

    def test_exclusivity_is_fixed_to_complete_core(self):
        r,v=fixture();proof=v['components'][0]['affirms'].pop()
        proof['basis']=dict(kind='exclusive_value',axis='life_state',core_value='dead',source_value='alive',explanation='Opposite state.')
        v['components'][0]['denies']=[proof]
        with self.assertRaisesRegex(ValueError,'complete core'):assess(r,v)
        r['inventory']['components'][0]['assessment'].update(core='A person is dead.',exclusivity=dict(axis='life_state',coreValue='dead',restriction='Same person, time and context.'))
        self.assertEqual(statuses(assess(r,v)),['Contradicted','Unresolved'])

    def test_not_conjunction_stays_one_unknown_core(self):
        r,v=fixture();c=r['inventory']['components'][0]
        c['assessment'].update(core='The object is white and speckled.',polarity='negative')
        v['components'][0].update(affirms=[],denies=[],unresolved=['partial_evidence'])
        self.assertEqual(statuses(assess(r,v)),['Unresolved','Unresolved'])
        self.assertEqual(p.model_input(r)['components'][0]['core'],'The object is white and speckled.')

    def test_belief_context_cannot_become_world_death(self):
        r,v=fixture();v['handles'][2]['scope']='belief'
        self.assertEqual(statuses(assess(r,v)),['Unresolved','Unresolved'])

    def test_foreign_source_handle_is_rejected(self):
        r,v=fixture();add_source(r,v,'e2','b')
        v['components'][0]['affirms'][0]['sources'][0]['roles']['r1']='bp'
        with self.assertRaisesRegex(ValueError,'foreign record'):assess(r,v)

    def test_incomplete_role_union_remains_unresolved(self):
        r,v=fixture();v['components'][0]['affirms'][0]['sources'][0]['roles']={}
        self.assertEqual(statuses(assess(r,v)),['Unresolved','Unresolved'])

    def test_event_edges_cannot_merge_entity_handles(self):
        r,v=fixture();add_source(r,v,'e2','b');connect(v,'same_event','ap','bp')
        with self.assertRaisesRegex(ValueError,'heterogeneous'):assess(r,v)

    def test_connection_must_cite_both_endpoint_records(self):
        r,v=fixture();add_source(r,v,'e2','b');connect(v,'same_entity','ap','bp')
        v['connections'][0]['citations']=['aq']
        with self.assertRaisesRegex(ValueError,'both endpoints'):assess(r,v)

    def test_partial_population_and_foreign_quote_refused(self):
        r,v=fixture();v['components'].pop()
        with self.assertRaisesRegex(ValueError,'population'):assess(r,v)
        r,v=fixture();v['citations'][0]['quote']='The wrong person.'
        with self.assertRaisesRegex(ValueError,'quotation'):assess(r,v)

    def test_same_occurrence_policy_blocks_different_actions(self):
        r,v=fixture();r['inventory']['eventPolicy']='same_occurrence'
        self.assertEqual(statuses(assess(r,v)),['Unresolved','Unresolved'])

    def test_same_person_different_situation_does_not_establish_simultaneity(self):
        r,v=fixture();r['items'][1]['text']='Ana was dead in a later scene.'
        second=add_source(r,v,'e2','b')
        v['components'][1].update(affirms=[dict(sources=[second],basis=dict(kind='direct',explanation='Later death state.'))],unresolved=[])
        connect(v,'same_entity','ap','bp');connect(v,'same_context','ak','bk')
        out=assess(r,v)
        self.assertEqual(statuses(out,'localStatus'),['Supported','Supported'])
        self.assertEqual(statuses(out),['Unresolved','Unresolved'])

    def test_cross_scope_identity_does_not_promote_described_death(self):
        r,v=fixture();r['inventory']['logic']='atomic';r['inventory']['eventPolicy']='separate_scopes'
        r['inventory']['components']=r['inventory']['components'][:1]
        c=r['inventory']['components'][0]
        c['roles'].append(dict(id='r2',kind='entity',quote='is dead',argument='described referent'))
        c['assessment'].update(core='The depicted and described people are the same person.',mode='cross_scope_identity')
        v['components']=v['components'][:1]
        second=add_source(r,v,'e2','b',scope='assertion')
        second['roles']={'r2':'bp'}
        v['components'][0]['affirms'][0]['sources'].append(second)
        self.assertEqual(statuses(assess(r,v)),['Unresolved'])
        connect(v,'same_entity','ap','bp',basis='explicit_reference')
        self.assertEqual(statuses(assess(r,v)),['Supported'])
        c['assessment'].update(core='The depicted person is dead.',mode='world')
        self.assertEqual(statuses(assess(r,v)),['Unresolved'])

    def test_unknown_scope_cannot_resolve(self):
        r,v=fixture();r['inventory']['components'][0]['assessment']['mode']='unresolved'
        self.assertEqual(statuses(assess(r,v)),['Unresolved','Unresolved'])

    def test_unknown_normalization_with_both_sides_is_not_conflicted(self):
        for level in ('known','component','parent'):
            r,v=fixture()
            if level=='component':r['inventory']['components'][0]['assessment']['mode']='unresolved'
            elif level=='parent':r['inventory']['logic']='unresolved'
            r['items'][1]['text']='Ana is not on the floor.'
            second=add_source(r,v,'e2','b')
            for kind,suffix in [('entity','p'),('situation','s'),('context','k')]:
                connect(v,'same_'+kind,'a'+suffix,'b'+suffix)
            negative=dict(sources=[second],basis=dict(kind='explicit_negation',explanation='Explicitly conflicting floor assertion.'))
            v['components'][0]['denies']=[negative]
            out=assess(r,v)
            if level=='known':
                self.assertEqual(statuses(out),['Conflicted','Unresolved'])
                continue
            self.assertEqual(statuses(out),['Unresolved','Unresolved'])
            self.assertEqual(len(out['components'][0]['proposed_affirms']),1)
            self.assertEqual(len(out['components'][0]['proposed_denies']),1)


if __name__ == '__main__':
    unittest.main()
