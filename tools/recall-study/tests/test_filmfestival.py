"""Synthetic, non-corpus fixtures: no participant or source prose is embedded here."""
import csv
import importlib.util
from pathlib import Path
import tempfile
import unittest


def module(name, path):
    spec=importlib.util.spec_from_file_location(name,path)
    m=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


ROOT=Path(__file__).resolve().parents[3]
gold=module('gold',ROOT/'tools/recall-study/filmfest_gold_film.py')
agreement=module('agreement',ROOT/'tools/recall-study/agreement.py')
experiment=module('experiment',ROOT/'tools/corpus/filmfest_experiment.py')
annotation=module('annotation',ROOT/'tools/corpus/filmfest_annotation.py')


class FilmFestivalTests(unittest.TestCase):
    def test_both_cartoon_durations_survive(self):
        ranges=[(11,'first',0,20),(1,'film',20,120),(11,'second',120,159)]
        self.assertEqual(gold.durations_by_code(ranges),{11:59,1:100})
        self.assertEqual(sum(gold.durations_by_code(ranges).values()),159)

    def test_boundary_belongs_to_next_film(self):
        self.assertEqual(gold.film_at(20,[(11,'intro',0,20),(1,'film',20,100)])[0],1)

    def test_cluster_bootstrap_retains_participant_dependence(self):
        rows=[{'correct':0,'eligible':100},{'correct':100,'eligible':100}]
        self.assertEqual(gold.cluster_ci(rows,'correct','eligible'),[0.,100.])
        self.assertEqual(gold.cluster_ci(rows,'correct','eligible'),gold.cluster_ci(list(reversed(rows)),'correct','eligible'))
        bigger=[{k:v*10 for k,v in r.items()} for r in rows]
        self.assertEqual(gold.cluster_ci(rows,'correct','eligible'),gold.cluster_ci(bigger,'correct','eligible'))

    def test_unanchored_stays_in_eligible_denominator(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'recall-map-sub-01.tsv'
            fields=['unit','recallText','recallOnsetSeconds','recallLastWordOnsetSeconds','startSeconds','mediaPart']
            with p.open('w',newline='') as f:
                w=csv.writer(f,delimiter='\t');w.writerow(fields)
                w.writerow(['u1','synthetic alpha',0,1,10,'run-01'])
                w.writerow(['u2','synthetic beta',2,3,'',''])
            units,counts=gold.score(d,{'sub-01':[{'movie':1,'start':0,'end':4}]},[(1,'film',0,100)])
            s=gold.summarize(units,counts,[(1,'film',0,100)])
            self.assertEqual(s['correctPerEligiblePct'],50.)
            self.assertEqual(s['anchorCoveragePct'],50.)
            self.assertEqual(s['correctPerAnchorPct'],100.)
            self.assertEqual(counts['unitsEligible'],2)

    def test_pairing_refuses_different_recall_identity(self):
        a=[{'key':('s','u'),'signature':'first','sub':'s','goldMovie':1,'modelMovie':1}]
        b=[dict(a[0],signature='different')]
        with self.assertRaisesRegex(ValueError,'identical'):
            gold.compare(a,b)
        b=[dict(a[0],modelMovie=2)]
        self.assertEqual(gold.compare(a,b)['deltaCorrectPerEligiblePp'],-100.)

    def test_population_includes_excluded_units(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'recall-map-sub-01.tsv'
            header='unit\trecallText\trecallOnsetSeconds\trecallLastWordOnsetSeconds\n'
            p.write_text(header+'u1\tsynthetic one\t0\t1\n'+'u2\tsynthetic excluded\t2\t3\n')
            before=gold.recall_population(d)
            p.write_text(header+'u1\tsynthetic one\t0\t1\n')
            self.assertNotEqual(before,gold.recall_population(d))

    def test_population_preserves_literal_quotes_and_rows(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'recall-map-sub-01.tsv'
            header='unit\trecallText\trecallOnsetSeconds\trecallLastWordOnsetSeconds\n'
            p.write_text(header+'u1\t"synthetic quote"\t0\t1\n')
            quoted=gold.recall_population(d)
            p.write_text(header+'u1\tsynthetic quote\t0\t1\n')
            self.assertNotEqual(quoted,gold.recall_population(d))
            p.write_text(header+'u1\t"unclosed synthetic quote\t0\t1\n'+'u2\tsecond unit\t2\t3\n')
            both=gold.recall_population(d)
            p.write_text(header+'u1\t"unclosed synthetic quote\t0\t1\n')
            self.assertNotEqual(both,gold.recall_population(d))

    def test_gold_reader_keeps_rows_after_an_unclosed_literal_quote(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'gold.tsv'
            p.write_text('participant\trun\tmovie\tstart_s\tend_s\ttext\n'
                         'sub-01\trun-01\t1\t0\t2\t"unclosed synthetic quote\n'
                         'sub-01\trun-01\t2\t2\t4\tsynthetic next\n')
            rows=gold.load_gold(p)['sub-01']
            self.assertEqual([r['movie'] for r in rows],[1,2])

    def test_gold_overlap_and_single_word_boundaries(self):
        rows=[{'start':0,'end':2,'movie':1},{'start':2,'end':8,'movie':2}]
        self.assertEqual(gold.best_gold((1,5),rows)['movie'],2)
        point=gold.best_gold((2,2),rows)
        self.assertIsNotNone(point)
        self.assertEqual(point['movie'],2)

    def test_unknown_part_refuses_instead_of_empty_success(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d)/'recall-map-sub.tsv').write_text('recallText\tmediaPart\tstartSeconds\nsynthetic\trun-01\t5\n')
            with self.assertRaisesRegex(ValueError,'parts.json'):
                agreement.load(d)

    def test_no_text_pairs_is_undefined_not_perfect_agreement(self):
        with tempfile.TemporaryDirectory() as d:
            for name in ('one','two'):
                (Path(d)/f'recall-map-{name}.tsv').write_text('recallText\tmediaPart\tstartSeconds\nshort\tmedia-part-a\t5\n')
            with self.assertRaisesRegex(ValueError,'no comparable text pairs'):
                agreement.main(['agreement','synthetic',d])

    def test_nonfinite_offsets_refused(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d)/'parts.json').write_text('{"run-01":NaN}')
            with self.assertRaisesRegex(ValueError,'finite'):
                agreement.part_offsets(d)

    def test_constant_anchor_wins_agreement_without_accuracy(self):
        a=[agreement.toks('synthetic violet copper timber lantern')]
        pairs=agreement.pairs_for(a,a,agreement.idf_of(a+a))
        self.assertEqual(len(pairs),1)
        self.assertEqual([abs(5-5) for _ in pairs],[0])
        self.assertNotEqual(gold.film_at(5,[(1,'one',0,10),(2,'two',10,20)])[0],2)

    def test_medoid_uses_consensus_and_is_permutation_invariant(self):
        texts=['violet copper timber','violet copper timber','unrelated metal']
        self.assertEqual(experiment.medoid(texts),'violet copper timber')
        self.assertEqual(experiment.medoid(texts),experiment.medoid(texts[::-1]))
        with self.assertRaises(ValueError): experiment.medoid([])

    def test_literal_tsv_preserves_quoted_source_for_scala(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'source.tsv'
            row=dict.fromkeys(experiment.COLUMNS,'')
            row['description']='"Synthetic quote" and  double space.'
            experiment.write_table(p,[row])
            raw=p.read_text().splitlines()[1].split('\t')[-1]
            self.assertEqual(raw,row['description'])
            self.assertEqual(experiment.read(p,'\t')[0]['description'],row['description'])

    def test_annotation_decimal_is_minutes_seconds(self):
        self.assertEqual(annotation.minsec_to_seconds(6.1000000000000005,'synthetic'),370)
        self.assertEqual(annotation.minsec_to_seconds(1.6,'synthetic'),120)
        with self.assertRaises(ValueError): annotation.minsec_to_seconds(1.99,'synthetic')


if __name__ == '__main__':
    unittest.main()
