"""Synthetic records distinguish missing evidence, wrong joins and real stage changes."""
import copy
import json
import math
from pathlib import Path
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import filmfest_diagnostics as diag
import filmfest_gold_film as gold


class DiagnosticsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.annotation = self.root/'source.tsv'
        self.annotation.write_text('segment\tpart_id\trun\tfilm\tscene_number\tcoarse_start_s\tstart_s\tend_s\tdescription\n'
            '1\trun-01\t1\t2. Film A\t1\t0\t0\t10\tSynthetic A\n'
            '2\trun-01\t1\t4. Film B\t2\t10\t10\t20\tSynthetic B\n')
        self.report = self.root/'recall-map-sub-01.tsv'
        self.report.write_text('unit\trecallText\trecallOnsetSeconds\trecallLastWordOnsetSeconds\tstartSeconds\tmediaPart\n'
            '0\tSynthetic recall\t0\t1\t10\trun-01\n')
        def bits(x): return '0x'+struct.pack('>d', x).hex()
        a = {'id':'filmfest:seg:0001','type':'Situation'}
        b = {'id':'filmfest:seg:0002','type':'Situation'}
        self.voyage = {'schema':'storymodel4s.view.recall-voyage','schemaVersion':1,
            'units':[{'ordinal':0,'id':'u0','text':'Synthetic recall','onset':bits(0.),'lastWordOnset':bits(1.)}],
            'rows':[{'unit':'u0','mass':[
                {'state':{'type':'Source','ref':a},'mass':bits(.8)},
                {'state':{'type':'External','state':'Intrusion'},'mass':bits(.2)}]}],
            'decisions':[{'unit':'u0','anchor':b,'origin':'decode_filled'}]}
        self.labels = {'sub-01':[{'start':0.,'end':2.,'movie':1}]}
        self.vpath = Path(str(self.report)+'.voyage.json')
        self.save()

    def save(self): self.vpath.write_text(json.dumps(self.voyage))

    def load(self):
        return diag.load_arm(self.root,self.annotation,self.labels,gold.film_ranges(self.annotation))[0]

    def test_fill_is_distinct_from_posterior_evidence_and_missing_nomination(self):
        r = self.load()[0]
        self.assertTrue(r['goldInPosteriorSupport'])
        self.assertEqual(r['posteriorArgmaxFilm'],1)
        self.assertEqual(r['finalFilm'],3)
        self.assertEqual(r['finalMass'],0.)
        self.assertFalse(r['finalInPosteriorSupport'])
        self.assertIsNone(r['goldInCandidates'])
        self.assertFalse(r['nominationAvailable'])

    def test_changed_voyage_content_fails_the_join(self):
        self.voyage['units'][0]['text'] = 'Different recall'
        self.save()
        with self.assertRaisesRegex(ValueError,'content or timing'): self.load()

    def test_duplicate_unit_fails_instead_of_overwriting(self):
        self.voyage['units'] *= 2
        self.save()
        with self.assertRaisesRegex(ValueError,'duplicate ordinal'): self.load()

    def test_nonfinite_mass_fails(self):
        self.voyage['rows'][0]['mass'][0]['mass'] = '0x7ff0000000000000'
        self.save()
        with self.assertRaisesRegex(ValueError,'nonfinite'): self.load()

    def test_zero_source_mass_is_not_a_source_prediction(self):
        self.voyage['rows'][0]['mass'][0]['mass'] = '0x0000000000000000'
        self.voyage['rows'][0]['mass'][1]['mass'] = '0x3ff0000000000000'
        self.save()
        r = self.load()[0]
        self.assertIsNone(r['posteriorArgmaxFilm'])
        self.assertFalse(r['goldInPosteriorSupport'])

    def trace(self):
        return {'schema':'storymodel4s.bench.stage-trace/v1','refinementPasses':0,'temperature':1.,
            'reportSha256':gold.digest(self.report),'sourceInputSha256':gold.digest(self.annotation),
            'units':[{'unit':0,'unitId':'u0','finalAnchor':'sit:filmfest:seg:0002',
                'posteriorAnchor':'sit:filmfest:seg:0001','noFillAnchor':'sit:filmfest:seg:0001',
                'nominations':[{'ref':'sit:filmfest:seg:0001','rankWithinLevel':0,'rawScore':.1}],
                'states':[{'state':'sit:filmfest:seg:0001','anchor':'sit:filmfest:seg:0001',
                           'cost':-math.log(.8),'localMass':.8,'posteriorMass':.8},
                          {'state':'ext:Intrusion','anchor':None,'cost':-math.log(.2),
                           'localMass':.2,'posteriorMass':.2}]}]}

    def test_valid_trace_joins_and_forged_binding_or_mass_is_refused(self):
        path = Path(str(self.report)+'.stages.json')
        valid = self.trace()
        path.write_text(json.dumps(valid))
        r = self.load()[0]
        self.assertTrue(r['goldInCandidates'])
        self.assertEqual(r['localFilm'],1)
        self.assertEqual(r['noFillFilm'],1)
        for mutate, error in [
            (lambda t:t.update(reportSha256='wrong'),'checksum'),
            (lambda t:t.update(sourceInputSha256='wrong'),'checksum'),
            (lambda t:t.update(temperature=float('nan')),'non-finite'),
            (lambda t:t['units'][0].update(unitId='wrong'),'unit or decision'),
            (lambda t:t['units'][0]['states'][0].update(cost=float('nan')),'non-finite'),
            (lambda t:t['units'][0]['states'][0].update(localMass=.1),'retained costs'),
            (lambda t:t['units'][0]['states'][0].update(posteriorMass=.1),'differs from voyage')]:
            with self.subTest(error=error):
                t = copy.deepcopy(valid); mutate(t); path.write_text(json.dumps(t))
                with self.assertRaisesRegex(ValueError,error): self.load()

    def test_equal_film_totals_do_not_hide_anchor_mass_divergence(self):
        a = self.load()
        b = copy.deepcopy(a)
        b[0]['finalFilm'] = 1
        b[0]['posteriorIdentity'] = [('changed anchor mass', .8)]
        self.assertEqual(a[0]['filmMass'], b[0]['filmMass'])
        change = diag.compare(a,b)['changes'][0]
        self.assertTrue(change['firstObservedDivergence'].startswith('posterior_mass;'))

    def test_renumbered_source_keeps_content_identity(self):
        old = diag.source_index(self.annotation)
        lines = self.annotation.read_text().splitlines()
        self.annotation.write_text(lines[0]+'\n'+lines[2].replace('2\trun-01','1\trun-01',1)+'\n')
        new = diag.source_index(self.annotation)
        self.assertEqual(old['sit:filmfest:seg:0002']['identity'],new['sit:filmfest:seg:0001']['identity'])
        self.assertNotEqual(old['sit:filmfest:seg:0001']['identity'],new['sit:filmfest:seg:0001']['identity'])

    def test_paired_changes_are_directional_and_population_order_invariant(self):
        a = self.load()
        b = copy.deepcopy(a)
        b[0]['finalFilm'] = 1
        result = diag.compare(a,b)
        self.assertEqual(result['counts'],{'correction':1})
        self.assertEqual(diag.compare(b,a)['counts'],{'regression':1})
        self.assertEqual(diag.compare(a[::-1],b[::-1]),result)
        b[0]['signature'] = 'changed'
        with self.assertRaisesRegex(ValueError,'content or gold'): diag.compare(a,b)


if __name__ == '__main__': unittest.main()
