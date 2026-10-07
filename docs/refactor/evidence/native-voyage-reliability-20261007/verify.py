"""Verify retained synthetic-fixture evidence, independently of a live CI status."""
from pathlib import Path
import gzip
import hashlib
import json
import re

HERE = Path(__file__).resolve().parent
CODE = '09f08b43e8be88c0efdf12a6efbfab13d1801b18'
BASE = '975f06761695fd23b10442d9cb27b12eee81eb34'


def read(name):
    return json.loads((HERE / name).read_text())


def raw(name):
    return gzip.decompress((HERE / name).read_bytes())


def clean(data):
    return re.sub(r'\x1b\[[0-9;]*[A-Za-z]', '', data.decode())


def totals(data):
    return [tuple(map(int, x)) for x in re.findall(
        r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)', clean(data))]


def digest(data):
    return hashlib.sha256(data).hexdigest()


gate = read('local-gate.json')
assert gate['source_sha'] == CODE and gate['base_sha'] == BASE
assert gate['clean_exact_clone_after_restoration'] and gate['formatting_last']
log = raw('platform-gate.log.gz')
assert digest(log) == gate['raw_log_sha256']
assert totals(log) == [(165, 0, 0, 165), (160, 0, 0, 160), (160, 0, 0, 160)]
assert gate['sum'] == {'passed': 485, 'failed': 0, 'errors': 0, 'skipped': 0}
assert read('platform-gate.log.meta.json')['exit_code'] == 0
assert 'WorkspaceVoyage external-winner checked fixture setup: 19869 ms' in clean(log)
assert len(re.findall(r'WorkspaceVoyageSuite finished: 0 failed, 0 ignored, 9 total', clean(log))) == 3

mutant = read('mutation.json')
assert mutant['candidate_sha'] == CODE and mutant['exit'] == 1
assert [mutant[k] for k in ['total', 'failed', 'errors', 'passed', 'ignored']] == [9, 1, 0, 8, 0]
assert mutant['compiled'] and mutant['accepting_control_and_siblings_passed']
mlog = raw('residual-source-fallback.log.gz')
assert digest(mlog) == mutant['log_sha256']
assert digest(raw('residual-source-fallback.patch.gz')) == mutant['patch_sha256']
assert totals(mlog) == [(9, 1, 0, 8)]
assert mutant['named_failure'] in clean(mlog) and 'munit.ComparisonFailException' in clean(mlog)
assert '295:    assertEquals(result.document, None)' in clean(mlog)
assert 'compiling 1 Scala source' in clean(mlog) and 'done compiling' in clean(mlog)
assert read('residual-source-fallback.log.meta.json')['exit_code'] == 1

profile = read('baseline-profile.json')
assert profile['base_sha'] == BASE
assert digest(raw('baseline-profile.patch.gz')) == profile['patch_sha256']
assert totals(raw('baseline-native.log.gz')) == [(9, 0, 0, 9)]
assert read('baseline-native.log.meta.json')['exit_code'] == 0
assert 'VOYAGE_PROFILE create-checked-archive 20393 ms' in clean(raw('baseline-native.log.gz'))
assert 'VOYAGE_PROFILE adapter-projection 227 ms' in clean(raw('baseline-native.log.gz'))
assert read('diagnosis.json')['runner_boundary'].startswith('Local baseline passed;')

hosted = read('hosted/source/receipt.json')
assert hosted['sha'] == CODE and hosted['conclusion'] == 'success'
assert hosted['totals']['cells'] == 80 and hosted['totals']['failed'] == hosted['totals']['errors'] == 0
assert len(hosted['jobs']) == 4
native = None
for kind in ['source', 'docs']:
    receipt = read(f'hosted/{kind}/receipt.json')
    state = read(f'hosted/{kind}/run.json')
    assert state['headSha'] == CODE and state['status'] == 'completed' and state['conclusion'] == 'success'
    assert receipt['sha'] == CODE and receipt['run'] == state['databaseId']
    assert digest((HERE / f'hosted/{kind}/logs.zip').read_bytes()) == receipt['archive_sha256']
    for job in receipt['jobs']:
        jlog = raw(f'hosted/{kind}/{job["job_id"]}.log.gz')
        assert len(jlog) == job['log_bytes'] and digest(jlog) == job['log_sha256']
        assert CODE in clean(jlog)
        observed = totals(jlog)
        assert [list(t) for t in observed] == job['sbt_totals']
        if kind == 'docs':
            assert 'verified 13 executable documentation examples' in clean(jlog)
            continue
        assert observed and sum(t[1] + t[2] for t in observed) == 0
        if 'rootNative' in job['name']:
            native = job
            assert len(observed) == 16 and job['passed'] == 2822
            assert 'WorkspaceVoyage external-winner checked fixture setup:' in clean(jlog)
            assert 'WorkspaceVoyageSuite' in clean(jlog) and '0 ignored, 9 total' in clean(jlog)
assert native is not None

names = set()
for line in (HERE / 'SHA256SUMS').read_text().splitlines():
    expected, name = line.split('  ', 1)
    assert name not in names
    names.add(name)
    assert digest((HERE / name).read_bytes()) == expected, name
actual = {str(p.relative_to(HERE)) for p in HERE.rglob('*') if p.is_file()}
assert names == actual - {'SHA256SUMS', 'validation.json'}, 'artifact coverage'
print(json.dumps({'code_sha': CODE, 'local_passed': 485, 'local_failed': 0, 'local_errors': 0,
                  'local_skipped': 0, 'compiled_mutant_failed': 1, 'mutant_siblings_passed': 8,
                  'hosted': hosted['totals'], 'native_passed': native['passed'],
                  'docs_examples': 13, 'verified_artifacts': len(names)}, indent=2))
