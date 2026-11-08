import copy
import json
import unittest
import claim_assessment_probe as v1
import claim_protocol_v2 as v2


class ProtocolEquivalence(unittest.TestCase):
    def setUp(self):
        self.request = dict(question="synthetic", arm="visual", identity="fixture",
                            items=[dict(id="a", text="TOKEN_A TOKEN_B")])
        self.value = dict(claim_id=v1.model_claim_id(self.request), support=[], counter=[],
                          unresolved=[{"reason": "partial_evidence"}])

    def test_exact_object_equals_string(self):
        raw, proposal, count = v2.canonicalize(self.request, json.dumps(self.value))
        expected = dict(self.value, unresolved=["partial_evidence"])
        self.assertEqual(proposal, v1.validate_proposal(self.request, json.dumps(expected)))
        self.assertEqual(json.loads(raw), expected)
        self.assertEqual(count, 1)

    def test_ownership_and_shape_guards_survive(self):
        mutations = [dict(claim_id="foreign"), dict(extra=True),
                     dict(unresolved=[{"reason": "partial_evidence", "extra": True}]),
                     dict(unresolved=[{"reason": "invented"}]),
                     dict(unresolved=[{"reason": []}]),
                     dict(unresolved=[{"reason": "partial_evidence"}, "partial_evidence"]),
                     dict(support=[dict(evidence_id="foreign", quote="TOKEN_A")]),
                     dict(support=[dict(evidence_id="a", quote="TOKEN_C")])]
        for mutation in mutations:
            with self.subTest(mutation=mutation), self.assertRaises((ValueError, TypeError)):
                v2.canonicalize(self.request, json.dumps(dict(self.value, **mutation)))

    def test_duplicate_keys_refused(self):
        raw = json.dumps(self.value).replace('"reason": "partial_evidence"',
                                            '"reason":"partial_evidence","reason":"partial_evidence"')
        with self.assertRaises(ValueError):
            v2.canonicalize(self.request, raw)

    def test_replay_preserves_raw_and_processing_failure(self):
        output = dict(question="synthetic", arm="visual", requestIdentity="fixture",
                      raw=json.dumps(self.value), processing="invalid_proposal", proposal=None)
        before = copy.deepcopy(output)
        result = v2.replay(self.request, output)
        self.assertEqual(output, before)
        self.assertEqual(result["originalRaw"], before["raw"])
        self.assertEqual(result["originalProcessing"], "invalid_proposal")
        self.assertEqual(result["processing"], "valid")
        output["processing"] = "output_limit_or_unknown"
        self.assertEqual(v2.replay(self.request, output)["processing"], "output_limit_or_unknown")


if __name__ == "__main__":
    unittest.main()
