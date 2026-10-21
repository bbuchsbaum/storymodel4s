"""Synthetic wire probes; no network calls or narrative fixtures."""
import copy
import json
import unittest
import openrouter_claim_probe as remote
import claim_assessment_probe as v1


class RemoteClaimBoundary(unittest.TestCase):
    def setUp(self):
        self.request = dict(question="synthetic-supported", claim="TOKEN_A", intervals=[],
                            items=[dict(id="e", text="TOKEN_A")], commonLabel="hidden-label")
        self.raw = json.dumps(dict(claim_id=v1.model_claim_id(self.request), support=[], counter=[],
                                   unresolved=["comparison_unavailable"]))
        self.response = dict(model=remote.MODEL, provider="Fireworks",
                             choices=[dict(finish_reason="stop", message=dict(content=self.raw))])

    def test_model_input_and_route_are_explicit(self):
        value = remote.body(self.request)
        self.assertNotIn("tools", value)
        self.assertEqual(value['provider']['only'], ['fireworks'])
        self.assertFalse(value['provider']['allow_fallbacks'])
        self.assertTrue(value['provider']['zdr'])
        self.assertNotIn('hidden-label', value['messages'][0]['content'])
        self.assertNotIn('synthetic-supported', value['messages'][0]['content'])
        self.assertGreater(remote.cost_bound(value), 4096 * 1.2 / 1e6)

    def test_alternate_provider_or_model_is_not_accepted(self):
        self.assertEqual(remote.interpret(self.request, self.response)['processing'], 'valid')
        for change in [dict(model='other'), dict(provider='other')]:
            value = remote.interpret(self.request, dict(self.response, **change))
            self.assertEqual(value['processing'], 'foreign_provider_or_model')
            self.assertIsNone(value['proposal'])

    def test_truncated_or_tool_response_does_not_become_a_claim(self):
        for choice in [dict(finish_reason='length', message=dict(content=self.raw)),
                       dict(finish_reason='stop', message=dict(content=self.raw, tool_calls=[{}]))]:
            response = dict(self.response, choices=[choice])
            self.assertEqual(remote.interpret(self.request, response)['processing'], 'unfinished_or_nontext_response')

    def test_foreign_claim_and_missing_quote_still_fail(self):
        for mutation in [dict(claim_id='foreign'), dict(support=[dict(evidence_id='e', quote='TOKEN_B')])]:
            response = copy.deepcopy(self.response)
            response['choices'][0]['message']['content'] = json.dumps(dict(json.loads(self.raw), **mutation))
            self.assertEqual(remote.interpret(self.request, response)['processing'], 'invalid_proposal')


if __name__ == '__main__':
    unittest.main()
