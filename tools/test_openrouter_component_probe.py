import json
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
import openrouter_component_probe as p
import claim_assessment_probe as base
from test_component_assessment_probe import fixture

class RemoteTests(unittest.TestCase):
    def test_same_model_recipe_and_output_limit(self):
        r,_,_=fixture(); b=p.body(r)
        self.assertEqual(b['model'],p.remote.MODEL)
        self.assertEqual(b['max_tokens'],8192)
        self.assertFalse(b['provider']['allow_fallbacks'])
        self.assertEqual(b['provider']['max_price']['request'],0)
    def test_foreign_model_rejected(self):
        r,v,_=fixture()
        self.assertEqual(p.interpret(r,dict(model='other',provider='Fireworks'))['processing'],'foreign_provider_or_model')
    def test_rate_limit_preserves_entire_population(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);r,_,_=fixture();r['identity']=base.identity(r)
            rows=[dict(condition=str(i),request=r) for i in range(3)]
            base.write(root/'requests.json',dict(executions=rows))
            paths=[Path(p.__file__),Path(p.protocol.__file__),Path(base.__file__),Path(p.remote.__file__),root/'requests.json']
            base.write(root/'freeze.json',dict(files={str(x.absolute()):base.sha(x) for x in paths},budgetUsd=5,priorCostBoundUsd=0))
            with patch.dict(p.os.environ,{'OPENROUTER_API_KEY':'synthetic'}),patch.object(p,'execute',return_value=dict(attempts=1,processing='http_failure',httpStatus=429)) as execute:
                p.run(root)
            self.assertEqual(execute.call_count,1)
            out=[json.loads(x) for x in (root/'outputs.jsonl').read_text().splitlines()]
            self.assertEqual(len(out),3)
            self.assertEqual([x['attempts'] for x in out],[1,0,0])
    def test_long_completion_never_parsed(self):
        r,v,_=fixture();response=dict(model=p.remote.MODEL,provider='Fireworks',choices=[dict(finish_reason='length',message=dict(content=json.dumps(v)))])
        self.assertIsNone(p.interpret(r,response)['proposal'])

if __name__=='__main__':unittest.main()
