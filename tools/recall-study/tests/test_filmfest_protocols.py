"""Synthetic source/annotation contracts; no fixture claims independent human evidence."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'corpus'))
import filmfest_annotation_packet as packet
import filmfest_source_court as court
import filmfest_experiment as source
import test_filmfest_diagnostics as diagnostic_fixtures


class SourceCourtTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        annotation=self.root/'filmfestival/derived/annotation-JL.tsv'
        annotation.parent.mkdir(parents=True)
        rows=[]
        for slug,ordinal in source.FILMS.items():
            if slug=='cmiyc_long': continue
            for i,text in enumerate(('Synthetic alpha action','Synthetic beta action')):
                rows.append(dict(segment=len(rows)+1,part_id='run-01',run=1,film=f'{ordinal}. {slug}',
                    scene_number='',coarse_start_s='',start_s=i*15,end_s=(i+1)*15,description=text))
            clean=self.root/f'filmfestival/textdata/Cleaned Data/Description_Cleaned_Data/{slug}_description_cleaned.csv'
            mapped=self.root/f'filmfestival/textdata/Analysis Data/Description/{slug}_consensus_mapped_to_neuro.csv'
            clean.parent.mkdir(parents=True,exist_ok=True); mapped.parent.mkdir(parents=True,exist_ok=True)
            clean.write_text('phase_type,counterbalance,description_stop,onset,offset,description_content\n'
                'test,0,1,0,30,Synthetic alpha observed\ntest,0,1,0,30,Synthetic beta observed\n')
            mapped.write_text('counterbalance,description_stop,onset,offset,filmfest_onset30,filmfest_offset\n0,1,0,30,0,30\n')
        source.write_table(annotation,rows)
        self.record=court.inventory(self.root)
        self.path=self.root/'correspondence.json'

    def reviewed_fixture(self):
        evidence=self.root/'synthetic-evidence.txt'; evidence.write_text('Synthetic test record, not a human review.')
        self.record.update(status='independently-reviewed',reviewer='synthetic-test-control',
            basis='verified-film-correspondence',evidencePath=str(evidence),evidenceSha256=source.sha(evidence))
        for w in self.record['windows']: w.update(status='verified',coderStart=0,coderEnd=30)
        self.path.write_text(json.dumps(self.record))

    def test_unreviewed_inventory_cannot_become_matched_inputs(self):
        self.reviewed_fixture()
        self.record['status']='unreviewed'
        self.path.write_text(json.dumps(self.record))
        with self.assertRaisesRegex(ValueError,'correspondence is required'):
            court.prepare(self.root,self.path,self.root/'out',[1,2])
        self.assertFalse((self.root/'out').exists())

    def test_factorial_holds_windows_and_budget_fixed_and_nests_candidates(self):
        self.reviewed_fixture()
        receipt=court.prepare(self.root,self.path,self.root/'out',[1,2])
        self.assertEqual(len(receipt['windows']),5)
        self.assertEqual(len(receipt['outputs']),8)
        for name,entry in receipt['outputs'].items():
            budget=int(name[-1]); self.assertEqual(entry['candidates'],5*budget)
        for origin in ('jl','crowd'):
            for recipe in ('medoid','sha'):
                one=source.read(self.root/f'out/{origin}-{recipe}-b1.tsv','\t')
                two=source.read(self.root/f'out/{origin}-{recipe}-b2.tsv','\t')
                self.assertEqual([r['description'] for r in one],[r['description'] for r in two[::2]])
                self.assertTrue(all(not r['scene_number'] for r in one))

    def test_evidence_and_input_changes_refuse_before_writing(self):
        self.reviewed_fixture()
        Path(self.record['evidencePath']).write_text('changed')
        with self.assertRaisesRegex(ValueError,'evidence missing or changed'):
            court.prepare(self.root,self.path,self.root/'out',[1])

    def test_source_selection_is_permutation_invariant(self):
        texts=['alpha beta','alpha beta','alpha gamma','delta omega']
        for recipe in ('medoid','sha'):
            self.assertEqual(court.select(texts,recipe,2),court.select(texts[::-1],recipe,2))


class AnnotationProtocolTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name); self.packet=self.root/'packet.json'; self.answers=self.root/'answers.json'
        self.packet.write_text(json.dumps({'schema':'storymodel4s.filmfestival.annotation-packet/v1',
            'cases':[{'caseId':'a'}],'sourceEvidence':[{'ref':'source1'},{'ref':'source2'}]}))
        self.valid={'schema':'storymodel4s.filmfestival.annotation-answers/v1',
            'packetSha256':source.sha(self.packet),'annotator':'synthetic-test-control',
            'independenceAttested':True,'basis':'source-descriptions',
            'answers':[{'caseId':'a','phenomena':['ambiguity','merge','external'],
                'interpretations':[{'anchors':['source1','source2'],'external':None},
                                   {'anchors':[],'external':'Association'}],
                'evidenceNote':'Synthetic ambiguous merged-source or external control.'}]}

    def validate(self,data):
        self.answers.write_text(json.dumps(data)); return packet.validate_answers(self.packet,self.answers)

    def test_alternatives_and_joint_anchors_survive_intake(self):
        _,a=self.validate(self.valid)
        self.assertEqual(a['answers'][0]['interpretations'],self.valid['answers'][0]['interpretations'])

    def test_unanswered_and_nonindependent_records_never_count_as_labels(self):
        for mutate in (lambda a:a.update(independenceAttested=False),
                       lambda a:a['answers'][0].update(phenomena=[]),
                       lambda a:a.update(answers=[]),
                       lambda a:a.update(packetSha256='wrong')):
            a=copy.deepcopy(self.valid); mutate(a)
            with self.assertRaises(ValueError): self.validate(a)

    def test_insufficient_evidence_is_preserved_without_fabricated_source(self):
        a=copy.deepcopy(self.valid)
        a['answers'][0].update(phenomena=['insufficient_evidence'],interpretations=[])
        self.validate(a)
        a['answers'][0]['interpretations']=[{'anchors':['source1'],'external':None}]
        with self.assertRaisesRegex(ValueError,'cannot carry accepted anchors'): self.validate(a)

    def test_duplicate_interpretations_cannot_manufacture_ambiguity(self):
        a=copy.deepcopy(self.valid)
        a['answers'][0]['interpretations']*=2
        with self.assertRaisesRegex(ValueError,'duplicate interpretations'): self.validate(a)

    def test_packet_refuses_source_or_recall_changed_after_diagnostics(self):
        fixture=diagnostic_fixtures.DiagnosticsTests(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
        d=fixture.root/'diagnostics'; d.mkdir()
        rows,receipt=packet.diag.load_arm(fixture.root,fixture.annotation,fixture.labels,
                                       packet.gold.film_ranges(fixture.annotation))
        (d/'jl-all.units.json').write_text(json.dumps(rows))
        (d/'comparisons.json').write_text(json.dumps({'before__after':{'sample':[
            {'participant':'sub-01','unit':0,'kind':'correction'}]}}))
        (d/'receipt.json').write_text(json.dumps({'schema':'storymodel4s.filmfestival.diagnostics/v1',
            'arms':{'jl-all':receipt},'artifacts':{p.name:source.sha(p) for p in d.glob('*.json')}}))
        result=packet.prepare(d,fixture.root,fixture.annotation,fixture.root/'valid-packet')
        self.assertEqual(result['cases'],1)
        original=fixture.report.read_text()
        fixture.report.write_text(original.replace('Synthetic recall','Different recall'))
        with self.assertRaisesRegex(ValueError,'report differs'):
            packet.prepare(d,fixture.root,fixture.annotation,fixture.root/'bad-report')
        fixture.report.write_text(original)
        fixture.annotation.write_text(fixture.annotation.read_text().replace('Synthetic A','Different source'))
        with self.assertRaisesRegex(ValueError,'source differs'):
            packet.prepare(d,fixture.root,fixture.annotation,fixture.root/'bad-source')


if __name__=='__main__': unittest.main()
