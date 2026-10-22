import json
import unittest
import tempfile
from pathlib import Path
from copy import deepcopy
import frame_observation_probe as p

class FrameTests(unittest.TestCase):
    def observation(self):
        return dict(entities=[dict(id='e1',kind='person',appearance='pink clothing',posture='lying face down',
            surface_contact='floor',location='foreground',action='unknown',held_objects='unknown')])
    def render_fixture(self,d):
        sample=Path(d)/'sample.json';probe=Path(d)/'probe.json'
        sample.write_text(json.dumps(dict(observedPts=[0,1,2])))
        probe.write_text(json.dumps(dict(streams=[dict(codec_type='video',time_base='1/2')])))
        bank=dict(sha256='a'*64,count=3,sampledExecution=str(sample),probe=str(probe))
        freeze=dict(frames={'part':bank});row=dict(row=7,part='part',start=0,end=1,sampleOrdinals=[0,1,2])
        observations=[dict(row=7,part='part',frameOrdinal=i,atSeconds=i/2,
            frameId=p.identity(dict(bank=bank['sha256'],ordinal=i)),processing='valid',
            raw=json.dumps(self.observation()),proposal=self.observation()) for i in range(3)]
        return freeze,row,observations
    def test_render_preserves_exact_instants_and_attribution(self):
        with tempfile.TemporaryDirectory() as d:
            f,r,obs=self.render_fixture(d);items=p.evidence_items(f,r,obs)
            self.assertEqual([i['start'] for i in items],[0,.5,1])
            self.assertTrue(all(i['start']==i['end'] for i in items))
            self.assertTrue(all('fallible' in i['ownership'] for i in items))
    def test_render_rejects_forged_frame_or_proposal(self):
        with tempfile.TemporaryDirectory() as d:
            f,r,obs=self.render_fixture(d);bad=deepcopy(obs);bad[0]['frameId']='foreign'
            with self.assertRaisesRegex(ValueError,'ownership'):p.evidence_items(f,r,bad)
            bad=deepcopy(obs);bad[0]['proposal']['entities'][0]['posture']='standing'
            with self.assertRaisesRegex(ValueError,'differs from raw'):p.evidence_items(f,r,bad)
            bad=deepcopy(obs);bad[0]['atSeconds']=.2
            with self.assertRaisesRegex(ValueError,'instant'):p.evidence_items(f,r,bad)
    def test_render_failure_is_not_absence(self):
        with tempfile.TemporaryDirectory() as d:
            f,r,obs=self.render_fixture(d);obs[0].update(processing='invalid_proposal',proposal=None)
            item=p.evidence_items(f,r,obs)[0]
            self.assertEqual(item['kind'],'source_unavailable')
            self.assertIn('not evidence of absent content',item['text'])
    def test_render_requires_full_frame_population(self):
        with tempfile.TemporaryDirectory() as d:
            f,r,obs=self.render_fixture(d)
            with self.assertRaisesRegex(ValueError,'population'):p.evidence_items(f,r,obs[:2])

    def test_fields_preserved(self):
        v=self.observation();self.assertEqual(p.validate_observation(json.dumps(v)),v)
    def test_separate_handles(self):
        v=self.observation();v['entities'].append(dict(v['entities'][0]))
        with self.assertRaisesRegex(ValueError,'duplicate'):p.validate_observation(json.dumps(v))
    def test_clock_is_pts_not_sample_ordinal(self):
        s=dict(observedPts=[275000,276200,277400]);pr=dict(streams=[dict(codec_type='video',time_base='1/2500')])
        self.assertEqual(p.frame_seconds(s,pr,1,3),110.48)
        with self.assertRaises(ValueError):p.frame_seconds(s,pr,3,3)
        with self.assertRaises(ValueError):p.frame_seconds(s,pr,1,4)
    def test_no_foreign_track_endpoint(self):
        v=dict(matches=[dict(**{'from':'e2','to':'e1'},relation='same',basis='continuous_view',explanation='visible')])
        with self.assertRaisesRegex(ValueError,'endpoint'):p.validate_track(json.dumps(v),self.observation(),self.observation())
    def test_unlicensed_same_identity(self):
        v=dict(matches=[dict(**{'from':'e1','to':'e1'},relation='same',basis='insufficient',explanation='same coat')])
        with self.assertRaisesRegex(ValueError,'unlicensed'):p.validate_track(json.dumps(v),self.observation(),self.observation())
    def test_ambiguous_association_preserved(self):
        v=dict(matches=[dict(**{'from':'e1','to':'e1'},relation='unknown',basis='insufficient',explanation='occluded')])
        self.assertEqual(p.validate_track(json.dumps(v),self.observation(),self.observation()),v)
    def test_identity_cannot_merge_two_entities(self):
        left=self.observation();left['entities'].append(dict(left['entities'][0],id='e2'))
        v=dict(matches=[dict(**{'from':x,'to':'e1'},relation='same',basis='continuous_view',explanation='visible') for x in ('e1','e2')])
        with self.assertRaisesRegex(ValueError,'non-injective'):p.validate_track(json.dumps(v),left,self.observation())

if __name__=='__main__':unittest.main()
