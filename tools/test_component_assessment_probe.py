import copy
import json
import unittest
import component_assessment_probe as p
from claim_assessment_probe import model_claim_id


def fixture():
    claim='A woman is on the floor and is dead.'
    cs=[dict(id='c1',text='A woman is on the floor.',quote='on the floor',facet='location',
             roles=[dict(id='r1',kind='entity',quote='A woman')]),
        dict(id='c2',text='A woman is dead.',quote='is dead',facet='attribute',
             roles=[dict(id='r1',kind='entity',quote='A woman')])]
    r=dict(question='private-label',claim=claim,intervals=[],items=[dict(id='s1',text='A woman lies on the floor.'),
             dict(id='s2',text='The woman is alive.')],inventory=dict(parentClaim=claim,logic='all',components=cs))
    def link(source='s1',side='support'):
        citation=dict(evidence_id=source,quote=r['items'][0 if source=='s1' else 1]['text'])
        bindings=[dict(role=role,disposition='matched',basis='direct_description',anchors=[citation],
                       explanation='The same described participant and event in the asserted context.')
                  for role in ('r1','event_scope','context_scope')]
        basis=dict(kind='direct',explanation='Explicitly described.') if side=='support' else dict(
            kind='exclusive_value',axis='life_state',claim_value='dead',source_value='alive',explanation='Same time.')
        return dict(citations=[citation],bindings=bindings,basis=basis)
    v=dict(claim_id=model_claim_id(r),joins=[dict(role=role,disposition='matched',basis='direct_description',anchors=[dict(evidence_id='s1',quote=r['items'][0]['text'])],explanation='Same source participant/event/context.') for role in ('r1','event_scope','context_scope')],components=[dict(component_id='c1',support=[link()],counter=[],unresolved=[]),
             dict(component_id='c2',support=[],counter=[],unresolved=['partial_evidence'])])
    return r,v,link


class ComponentTests(unittest.TestCase):
    def test_unknown_death_retains_floor(self):
        r,v,_=fixture(); o=p.validate_proposal(r,json.dumps(v))
        self.assertEqual([c['status'] for c in o['components']],['Supported','Unresolved'])
        self.assertFalse(o['summary']['allSupported'])

    def test_false_detail_is_not_conflict(self):
        r,v,link=fixture();v['components'][1].update(counter=[link('s2','counter')],unresolved=[])
        for j in v['joins']:j['anchors'].append(dict(evidence_id='s2',quote=r['items'][1]['text']))
        o=p.validate_proposal(r,json.dumps(v))
        self.assertEqual(o['summary']['componentCounts'],dict(Supported=1,Contradicted=1,Conflicted=0,Unresolved=0))
        self.assertFalse(o['summary']['withinComponentConflict'])

    def test_same_component_conflict(self):
        r,v,link=fixture();r['items'][1]['text']='The same woman is not on the floor.'
        counter=link('s2','counter');counter['basis']=dict(kind='explicit_negation',explanation='The source explicitly denies floor contact for the same woman.')
        v['components'][0]['counter']=[counter]
        for j in v['joins']:j['anchors'].append(dict(evidence_id='s2',quote=r['items'][1]['text']))
        self.assertEqual(p.validate_proposal(r,json.dumps(v))['components'][0]['status'],'Conflicted')

    def test_unresolved_join_retains_only_proposal(self):
        r,v,_=fixture();v['components'][0]['support'][0]['bindings'][0]['disposition']='unresolved'
        c=p.validate_proposal(r,json.dumps(v))['components'][0]
        self.assertEqual(c['status'],'Unresolved');self.assertEqual(c['support'],[])
        self.assertEqual(len(c['proposed_support']),1)

    def test_every_source_requires_bridge(self):
        r,v,_=fixture();v['components'][0]['support'][0]['citations'].append(dict(evidence_id='s2',quote=r['items'][1]['text']))
        self.assertEqual(p.validate_proposal(r,json.dumps(v))['components'][0]['status'],'Unresolved')

    def test_missing_scope_rejected(self):
        r,v,_=fixture();v['components'][0]['support'][0]['bindings'].pop()
        with self.assertRaisesRegex(ValueError,'required binding'):p.validate_proposal(r,json.dumps(v))

    def test_compatible_colors_not_declared_exclusive(self):
        r,v,link=fixture(); c=link('s2','counter');c['basis'].update(axis='color',claim_value='red',source_value='speckled')
        v['components'][0]['counter']=[c]
        with self.assertRaisesRegex(ValueError,'incompatibility'):p.validate_proposal(r,json.dumps(v))

    def test_parent_join_cannot_stitch_different_sources(self):
        r,v,link=fixture();v['components'][1].update(support=[link('s2')],unresolved=[])
        o=p.validate_proposal(r,json.dumps(v))
        self.assertEqual([c['localStatus'] for c in o['components']],['Supported','Supported'])
        self.assertEqual([c['status'] for c in o['components']],['Unresolved','Unresolved'])
        self.assertFalse(o['summary']['allSupported'])
        self.assertTrue(o['summary']['parentBindingUnresolved'])

    def test_same_record_multiple_people_still_need_join(self):
        r,v,link=fixture();r['items'][0]['text']='Ana is on the floor. Beth is dead.'
        v['components'][0]['support']=[link()]
        v['components'][1].update(support=[link()],unresolved=[])
        for j in v['joins']:j.update(disposition='unresolved',basis='unresolved',anchors=[dict(evidence_id='s1',quote=r['items'][0]['text'])])
        o=p.validate_proposal(r,json.dumps(v))
        self.assertEqual([c['localStatus'] for c in o['components']],['Supported','Supported'])
        self.assertEqual([c['status'] for c in o['components']],['Unresolved','Unresolved'])

    def test_atomic_opposing_links_need_entity_event_context_joins(self):
        r,v,link=fixture();r['inventory']['logic']='atomic'
        r['inventory']['components']=r['inventory']['components'][:1];v['components']=v['components'][:1]
        r['items'][1]['text']='Another woman is not on the floor.'
        counter=link('s2','counter');counter['basis']=dict(kind='explicit_negation',explanation='Negative floor assertion about another woman.')
        v['components'][0]['counter']=[counter]
        c=p.validate_proposal(r,json.dumps(v))['components'][0]
        self.assertEqual(c['localStatus'],'Conflicted');self.assertEqual(c['status'],'Unresolved')

    def test_partial_population_rejected(self):
        r,v,_=fixture();v['components'].pop()
        with self.assertRaisesRegex(ValueError,'population'):p.validate_proposal(r,json.dumps(v))

    def test_invented_track_rejected(self):
        r,v,_=fixture();v['components'][0]['support'][0]['bindings'][0]['basis']='continuous_visual_track'
        with self.assertRaisesRegex(ValueError,'track'):p.validate_proposal(r,json.dumps(v))

    def test_unknown_logic_cannot_become_supported(self):
        r,v,_=fixture();r['inventory']['logic']='unresolved'
        self.assertEqual(p.validate_proposal(r,json.dumps(v))['components'][0]['status'],'Unresolved')

    def test_duplicate_json_key_and_quote_ownership(self):
        r,v,_=fixture()
        with self.assertRaisesRegex(ValueError,'duplicate JSON'):p.validate_proposal(r,'{"claim_id":1,"claim_id":2}')
        v['components'][0]['support'][0]['citations'][0]['quote']='A man lies on the floor.'
        with self.assertRaisesRegex(ValueError,'quotation'):p.validate_proposal(r,json.dumps(v))

    def test_no_labels_cross_model_boundary(self):
        r,_,_=fixture();r['reference']='Supported';r['inventory']['secretLabel']='Supported'
        r['inventory']['components'][0]['roles'][0]['secretLabel']='Supported'
        text=json.dumps(p.model_input(r))
        self.assertNotIn('secretLabel',text);self.assertNotIn('private-label',text);self.assertNotIn('Supported',text)

if __name__=='__main__':unittest.main()
