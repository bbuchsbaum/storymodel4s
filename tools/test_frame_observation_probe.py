import json
import unittest
import frame_observation_probe as p

class FrameTests(unittest.TestCase):
    def observation(self):
        return dict(entities=[dict(id='e1',kind='person',appearance='pink clothing',posture='lying face down',
            surface_contact='floor',location='foreground',action='unknown',held_objects='unknown')])
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
