#!/usr/bin/env python3
"""Compile targeted in-memory mutants and retain every named test outcome."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import types
import unittest

p=argparse.ArgumentParser();p.add_argument('output',type=Path);a=p.parse_args()
a.output.mkdir(exist_ok=False)
root=Path(__file__).resolve().parents[4]
source=root/'tools/recall-study/entity_hypotheses.py'
tests=root/'tools/recall-study/tests/test_entity_hypotheses.py'
sys.path.insert(0,str(source.parent))
original=source.read_text()
mutants=[
    ('identity-filter',"positions[s['destination']] in allowed",'True',
     ['test_identity_condition_preserves_state_multiplicity_and_external_states']),
    ('invent-weights',"'interpretation_weights': None","'interpretation_weights': [0.5, 0.5]",
     ['test_unknown_weights_never_become_uniform_or_a_point_distribution']),
    ('bridge-gap',"result = dict.fromkeys(CATEGORIES, 0.0)",
     "rows = [r for r in rows if r is not None]\n    result = dict.fromkeys(CATEGORIES, 0.0)",
     ['test_unavailable_interior_row_does_not_bridge_gap']),
    ('no-value-to-zero',"items, failures, verified_rows, max_error = {}, [], 0, None",
     "items, failures, verified_rows, max_error = {}, [], 0, 0.0",
     ['test_all_failed_reader_has_no_measured_error','test_all_unavailable_reader_has_no_measured_error'])]
receipt=[]
for name,before,after,expected in [('control',None,None,[])]+mutants:
    if before is not None and original.count(before)!=1:
        raise ValueError('mutation target changed')
    changed=original if before is None else original.replace(before,after)
    compiled=compile(changed,str(source),'exec')
    module=types.ModuleType('entity_hypotheses');module.__file__=str(source)
    sys.modules['entity_hypotheses']=module;exec(compiled,module.__dict__)
    spec=importlib.util.spec_from_file_location('probe_tests',tests)
    test_module=importlib.util.module_from_spec(spec);spec.loader.exec_module(test_module)
    output=io.StringIO()
    result=unittest.TextTestRunner(stream=output,verbosity=2).run(unittest.defaultTestLoader.loadTestsFromModule(test_module))
    failed=sorted(t.id().split('.')[-1] for t,_ in result.failures)
    raw=output.getvalue().encode();(a.output/(name+'.log')).write_bytes(raw)
    row={'name':name,'compiled':True,'tests':result.testsRun,'failed':failed,'errors':len(result.errors),
         'skipped':len(result.skipped),'passed':result.testsRun-len(result.failures)-len(result.errors)-len(result.skipped),
         'expected_failures':sorted(expected),'sha256':hashlib.sha256(raw).hexdigest()}
    receipt.append(row)
    if failed!=sorted(expected) or result.errors or result.skipped:
        raise AssertionError(row)
(a.output/'receipt.json').write_text(json.dumps({'source_sha256':hashlib.sha256(original.encode()).hexdigest(),
                                               'runs':receipt},indent=2)+'\n')
print(json.dumps(receipt,indent=2))
