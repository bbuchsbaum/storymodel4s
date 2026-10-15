#!/usr/bin/env python3
"""Discriminate fixed Maven versions from dynamic selectors; remove the plus guard once."""
import hashlib
import json
import sys
import types
from pathlib import Path

source = Path(sys.argv[1]).read_text()
output = Path(sys.argv[2])
cases = {
    'fixed-release': ('1.0.0', True),
    'fixed-candidate': ('0.1.0-RC1', True),
    'fixed-native-composite': ('3.7.4+0.5.12', True),
    'snapshot': ('1.0-SNAPSHOT', False),
    'latest': ('latest.release', False),
    'interval': ('[1.0,2.0)', False),
    'star': ('1.*', False),
    'minor-wildcard-plus': ('1.+', False),
    'prefix-plus': ('1.2+', False),
    'release-plus': ('1.2.3+', False),
    'empty-metadata-component': ('1.2.3+4..5', False),
}

def run(code, name):
    module = types.ModuleType(name)
    sys.modules[name] = module
    exec(compile(code, name, 'exec'), module.__dict__)
    result = {}
    for label, (value, expected) in cases.items():
        try:
            module.reject_dynamic(value, label)
            admitted = True
        except module.PreflightError:
            admitted = False
        result[label] = {'admitted': admitted, 'expected': expected, 'passed': admitted == expected}
    return result

baseline = run(source, 'baseline_preflight')
assert all(row['passed'] for row in baseline.values()), baseline
needle = ' or ("+" in value and not FIXED_BUILD_METADATA.fullmatch(value))'
assert source.count(needle) == 1
mutant = run(source.replace(needle, ''), 'mutant_preflight')
killed = [label for label, row in mutant.items() if not row['passed']]
assert killed == ['minor-wildcard-plus', 'prefix-plus', 'release-plus', 'empty-metadata-component'], killed
receipt = {'sourceSha256': hashlib.sha256(source.encode()).hexdigest(), 'baseline': baseline,
           'mutation': 'delete plus-selector guard', 'mutantCompiled': True, 'mutant': mutant,
           'namedKills': killed, 'passingSiblings': 7}
output.write_text(json.dumps(receipt, indent=2) + '\n')
print('11 baseline controls passed; compiling mutant killed by four named controls; seven siblings passed')
