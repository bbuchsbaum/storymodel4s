"""Original synthetic protocol strings only; no narrative, media or participant material."""
import json
import unittest
import tempfile
from pathlib import Path
import claim_assessment_probe as probe
from claim_assessment_probe import payload, status, validate_proposal


class ClaimAssessmentProbeTests(unittest.TestCase):
    def setUp(self):
        self.request = {"question": "q", "items": [{"id": "e", "text": "signal=on; mode=stable"}]}

    def raw(self, **changes):
        value = dict(claim_id="q", support=[], counter=[], unresolved=["comparison_unavailable"])
        value.update(changes)
        return json.dumps(value)

    def test_literal_quote_is_bound_to_its_item_and_exact_span(self):
        p = validate_proposal(self.request, self.raw(support=[dict(evidence_id="e", quote="mode=stable")], unresolved=[]))
        self.assertEqual(p['support'], [dict(evidence="e", quote="mode=stable", start=11, end=22)])
        self.assertEqual(status(p), "Supported")

    def test_quote_cannot_borrow_another_item_or_fix_its_text(self):
        for item, quote in [("foreign", "signal=on"), ("e", "signal=off")]:
            with self.assertRaises(ValueError):
                validate_proposal(self.request, self.raw(support=[dict(evidence_id=item, quote=quote)]))

    def test_empty_and_ambiguous_quotes_are_refused(self):
        request = {"question": "q", "items": [{"id": "e", "text": "x x"}]}
        for quote in ["", " ", "x"]:
            with self.assertRaises(ValueError):
                validate_proposal(request, self.raw(support=[dict(evidence_id="e", quote=quote)]))

    def test_foreign_claim_is_refused(self):
        with self.assertRaises(ValueError):
            validate_proposal(self.request, self.raw(claim_id="another"))

    def test_duplicate_json_keys_and_unknown_reasons_are_refused(self):
        for raw in ['{"claim_id":"q","claim_id":"q","support":[],"counter":[],"unresolved":[]}',
                    self.raw(unresolved=["unsupported_new_ontology"]), self.raw(extra=0)]:
            with self.assertRaises(ValueError):
                validate_proposal(self.request, raw)

    def test_missing_evidence_requires_reason(self):
        with self.assertRaises(ValueError):
            validate_proposal(self.request, self.raw(unresolved=[]))
        p = validate_proposal(self.request, self.raw())
        self.assertEqual(status(p), "Unresolved")
        self.assertEqual(p['counter'], [])

    def test_context_unknown_retains_support_but_does_not_resolve(self):
        p = validate_proposal(self.request, self.raw(support=[dict(evidence_id="e", quote="signal=on")],
                                                    unresolved=["context_unresolved"]))
        self.assertEqual(status(p), "Unresolved")
        self.assertEqual(len(p['support']), 1)

    def test_support_and_counter_do_not_cancel(self):
        link = dict(evidence_id="e", quote="signal=on")
        p = validate_proposal(self.request, self.raw(support=[link], counter=[link], unresolved=[]))
        self.assertEqual(status(p), "Conflicted")
        self.assertEqual(len(p['support']), 1)
        self.assertEqual(len(p['counter']), 1)

    def test_unicode_offsets_use_utf16_for_scala(self):
        request = {"question": "q", "items": [{"id": "e", "text": "🔔 signal=on"}]}
        p = validate_proposal(request, self.raw(support=[dict(evidence_id="e", quote="signal=on")], unresolved=[]))
        self.assertEqual((p['support'][0]['start'], p['support'][0]['end']), (3, 12))

    def test_repeated_source_items_are_one_input_not_multiple_votes(self):
        item = dict(id="e", text="signal=on", kind="caption")
        targets = {i: dict(row=i, part="part-1", start=i, end=i+1, arms={"visual": [item]}) for i in (1, 2)}
        p = payload(dict(id="q", claim="signal=on", sourceRows=[1, 2]), "visual", targets)
        self.assertEqual(len(p['items']), 1)
        targets[2]['arms']['visual'] = [dict(item, text="changed")]
        with self.assertRaises(ValueError):
            payload(dict(id="q", claim="signal=on", sourceRows=[1, 2]), "visual", targets)

    def test_audio_word_timing_is_preserved_without_leaking_into_visual_arm(self):
        word = dict(word="signal", start=1.2, end=1.5, segment_index=3)
        item = dict(id="e", text="signal=on", kind="asr-segment")
        targets = {1: dict(row=1, part="part-1", start=1, end=2, centralWords=[word],
                           arms={"audio": [item], "audio_visual": [item], "visual": []})}
        question = dict(id="q", claim="signal=on", sourceRows=[1])
        for arm in ("audio", "audio_visual"):
            self.assertEqual(payload(question, arm, targets)['intervals'][0]['centralWords'], [word])
        self.assertNotIn('centralWords', payload(question, 'visual', targets)['intervals'][0])

    def test_consumed_files_must_belong_to_the_exact_frozen_experiment(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td).absolute()
            originals = root / 'original'; originals.mkdir()
            copy = root / 'copy'; copy.mkdir()
            for name in ('requests.json', 'questions.json'):
                (originals / name).write_text('{}')
                (copy / name).write_text('{}')
            freeze = dict(harness=dict(sha256=probe.sha(probe.__file__)), prompt=probe.PROMPT,
                          promptSha256=probe.hashlib.sha256(probe.PROMPT.encode()).hexdigest(),
                          inputs={str(originals / name): probe.sha(originals / name)
                                  for name in ('requests.json', 'questions.json')},
                          worker=dict(path=probe.__file__, sha256=probe.sha(probe.__file__)))
            for path in (originals, copy):
                (path / 'freeze.json').write_text(json.dumps(freeze))
            self.assertEqual(probe.verify_freeze(originals), freeze)
            with self.assertRaisesRegex(ValueError, 'consumed input'):
                probe.verify_freeze(copy)
            (originals / 'requests.json').write_text('{"changed":true}')
            with self.assertRaisesRegex(ValueError, 'consumed input'):
                probe.verify_freeze(originals)

    def test_duplicate_citations_and_item_ids_are_refused(self):
        link = dict(evidence_id="e", quote="signal=on")
        with self.assertRaises(ValueError):
            validate_proposal(self.request, self.raw(support=[link, link]))
        with self.assertRaises(ValueError):
            validate_proposal(dict(self.request, items=self.request['items']*2), self.raw())


if __name__ == "__main__":
    unittest.main()
