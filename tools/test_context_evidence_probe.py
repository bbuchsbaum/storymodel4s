"""Synthetic falsifiers for focal scope, source identity and optional evidence."""
import copy
import unittest

import claim_assessment_probe as v1
import context_evidence_probe as context


def target(row, start, end, part='part-a', visual='TOKEN_A', human='TOKEN_H', segments=None):
    return dict(row=row, part=part, start=start, end=end, visual=visual, human=human,
                audio=dict(segments=segments or [], centralWords=[]))


def request(targets, row=1):
    t = next(t for t in targets if t['row'] == row)
    value = dict(question='synthetic-hidden-reference', arm='audio_visual', claim='TOKEN_C',
                 intervals=[{k:t[k] for k in ['row', 'part', 'start', 'end']} | dict(centralWords=[])] ,
                 items=sorted(context.target_items(t), key=lambda item:item['id']))
    value['identity'] = v1.identity(value)
    return value


class ContextEvidenceBoundaries(unittest.TestCase):
    def test_context_cannot_expand_focal_scope_or_cross_parts(self):
        targets = [target(1, 100, 110), target(2, 70, 80), target(3, 130, 140),
                   target(4, 80, 90, part='part-b'), target(5, 0, 70)]
        original = request(targets)
        result, receipt = context.augment(original, targets, 'context_auto', radius=30)
        self.assertEqual(result['intervals'], original['intervals'])
        self.assertEqual(receipt['windows'], [dict(part='part-a', start=70, end=140)])
        added = [i for i in result['items'] if i['id'] not in {x['id'] for x in original['items']}]
        self.assertEqual({(i['start'],i['end']) for i in added}, {(70,80),(130,140)})
        self.assertTrue(all(i['evidenceRole']=='surrounding_context' for i in added))
        self.assertTrue(all('not direct depiction' in i['ownership'] for i in added))

    def test_shared_asr_is_not_duplicated_and_text_is_never_clipped(self):
        segment = dict(index=0, text='FULL TOKEN BEFORE AND AFTER', start_seconds=95,
                       end_seconds=125, suspect=False, diagnostics=[])
        targets = [target(1,100,110,segments=[segment]), target(2,110,120,segments=[segment])]
        result, _ = context.augment(request(targets), targets, 'context_auto', radius=1)
        asr = [i for i in result['items'] if i['kind']=='asr-segment']
        self.assertEqual(len(asr),1)
        self.assertEqual((asr[0]['text'],asr[0]['start'],asr[0]['end']), ('FULL TOKEN BEFORE AND AFTER',95,125))

    def test_human_evidence_is_optional_and_disagreement_is_retained(self):
        targets = [target(1,100,110,visual='DOOR OPEN',human='DOOR CLOSED'), target(2,120,130,human='')]
        original = request(targets)
        automatic, _ = context.augment(original, targets, 'context_auto')
        manual, _ = context.augment(original, targets, 'focal_human')
        self.assertFalse(any(i['kind']=='manual-annotation' for i in automatic['items']))
        self.assertEqual({i['text'] for i in manual['items']}, {'DOOR OPEN','DOOR CLOSED'})
        annotation = next(i for i in manual['items'] if i['kind']=='manual-annotation')
        self.assertIn('fallible',annotation['ownership'])
        self.assertEqual(manual['intervals'],original['intervals'])

    def test_selection_does_not_depend_on_claim_and_model_input_hides_labels(self):
        targets = [target(1,100,110),target(2,120,130)]
        a = request(targets); b = copy.deepcopy(a)
        b.update(claim='UNRELATED TOKEN',question='other-hidden-label')
        b['identity']=v1.identity({k:v for k,v in b.items() if k!='identity'})
        x,_ = context.augment(a,targets,'context_human'); y,_ = context.augment(b,targets,'context_human')
        self.assertEqual(x['items'],y['items'])
        self.assertEqual(x['intervals'],y['intervals'])
        self.assertNotIn('question',v1.model_input(x))
        self.assertNotIn('synthetic-hidden-reference',str(v1.model_input(x)))

    def test_focal_source_mutation_and_nonfinite_coordinates_are_refused(self):
        targets = [target(1,100,110)]
        original = request(targets)
        changed = copy.deepcopy(targets);changed[0]['visual']='DIFFERENT'
        with self.assertRaisesRegex(ValueError,'baseline evidence'):
            context.augment(original,changed,'context_auto')
        for value in [float('nan'),float('inf'),-1]:
            with self.assertRaises(ValueError):context.windows(original['intervals'],{1:targets[0]},value)
        with self.assertRaisesRegex(ValueError,'duplicate source row'):
            context.augment(original,targets+targets,'context_auto')


if __name__=='__main__':
    unittest.main()
