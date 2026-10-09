"""Reconcile immutable source, complete archives, named compiled kills and bound gate totals."""
import ast
import gzip
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path

E = Path(__file__).resolve().parent
R = E.parents[3]
SHA = '95ba0e4d5c05742cea5983f0bfddbe65acfc66cc'

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def obj(path):
    return subprocess.check_output(['git', 'show', SHA + ':' + path], cwd=R)

def raw(path):
    b = (E / path).read_bytes()
    return gzip.decompress(b) if path.endswith('.gz') else b

def totals(text):
    return [[int(v or 0) for v in row] for row in re.findall(
        r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)(?:, (?:Skipped|Ignored) (\d+))?', text)]

export = json.loads((E / 'export.json').read_text())
assert export['source_sha'] == SHA and export['clean']
for path, value in export['changed_files_sha256'].items():
    assert digest(obj(path)) == value, path
for item in json.loads((E / 'archive.json').read_text()):
    b = raw(item['path'])
    assert len(b) == item['raw_bytes'] and digest(b) == item['raw_sha256'], item['path']
mutants = json.loads((E / 'mutations.json').read_text())
assert len(mutants) == 9
for m in mutants:
    assert m['candidate_sha'] == SHA and m['compiled'] and m['qualified']
    prefix = 'runs/mutations/' + m['name']
    patch = raw(prefix + '.patch.gz')
    log = raw(prefix + '.log.gz')
    meta = json.loads(raw(prefix + '.log.meta.json'))
    assert digest(patch) == m['patch_sha256'] and digest(log) == m['log_sha256']
    assert meta['exit_code'] == meta['child_returncode'] == m['exit'] == 1
    assert meta['command'] == m['command'] and meta['reason'] == 'exited'
    assert 'embedBench/Test/clean' in meta['command'] and meta['log_bytes'] == len(log)
    text = log.decode()
    failures = [line for line in text.splitlines() if '==> X ' in line]
    assert failures == m['failures'] and len(failures) == 1 and m['named_failure'] in failures[0]
    rows = totals(text)
    assert rows == m['totals'] and rows[-1][1:4] == [1, 0, 1]
    assert '[info] done compiling' in text and 'Compilation failed' not in text
    with tempfile.TemporaryDirectory(prefix='trace-mutation-verify-') as directory:
        target = Path(directory) / m['path']
        target.parent.mkdir(parents=True)
        target.write_bytes(obj(m['path']))
        p = subprocess.run(['patch', '-p1', '-t'], cwd=directory, input=patch, capture_output=True)
        assert p.returncode == 0 and digest(target.read_bytes()) == m['source_sha256']
python_mutants = json.loads((E / 'python-mutations.json').read_text())
assert len(python_mutants) == 2
for m in python_mutants:
    assert m['candidate_sha'] == SHA and m['qualified'] and m['exit'] == 1
    log, patch = raw(m['log_archive']), raw(m['patch_archive'])
    assert digest(log) == m['log_sha256'] and digest(patch) == m['patch_sha256']
    text = log.decode()
    assert 'Ran 2 tests' in text and 'FAILED (failures=1)' in text and 'AssertionError' in text
    assert m['named_failure'] in text and 'CAPTURE_EXIT=1' in text
    with tempfile.TemporaryDirectory(prefix='trace-python-verify-') as directory:
        target = Path(directory) / m['path']
        target.parent.mkdir(parents=True)
        target.write_bytes(obj(m['path']))
        p = subprocess.run(['patch', '-p1', '-t'], cwd=directory, input=patch, capture_output=True)
        assert p.returncode == 0
        ast.parse(target.read_text())
controls = json.loads((E / 'python-controls.json').read_text())
assert controls['source_sha'] == SHA
assert [r['passed'] for r in controls['runs']] == [12, 15, 4]
for control in controls['runs']:
    text = raw(control['log_archive']).decode()
    assert control['exit'] == 0
    assert re.search(r'Ran ' + str(control['passed']) + r' tests? in ', text)
    assert '\nOK\n' in text and 'FAILED (' not in text
focused = raw('runs/storymodel4s-s2b5-review-fixes-clean-smoke.log.gz').decode()
focused_meta = json.loads(raw('runs/storymodel4s-s2b5-review-fixes-clean-smoke.log.meta.json'))
assert focused_meta['exit_code'] == focused_meta['child_returncode'] == 0
assert 'embedBench/Test/clean' in focused_meta['command']
assert totals(focused) == [[32, 0, 0, 32, 0]]
qpath = E / 'qualification.json'
if qpath.exists():
    q = json.loads(qpath.read_text())
    assert q['source_sha'] == SHA
    s = q['source']
    plan = json.loads((E / 'reference-scope.json').read_text())
    assert plan['candidate_sha'] == SHA
    expected_tasks = plan['jvm_tasks'] + plan['portable_tasks']
    rows, tasks = [], []
    for attempt in s['attempts']:
        assert attempt['source_sha'] == SHA
        log = raw(attempt['log_archive'])
        meta = json.loads(raw(attempt['metadata_archive']))
        assert digest(log) == attempt['log_sha256']
        assert meta['exit_code'] == meta['child_returncode'] == attempt['exit']
        assert meta['reason'] == 'exited' and meta['log_bytes'] == len(log)
        assert meta['command'] == attempt['command']
        assert meta['cwd'] == export['clone']
        assert 'set ThisBuild / tlFatalWarnings := true' in meta['command']
        assert meta['command'][2] == plan['command'][2]  # same dependency override
        observed = totals(log.decode())
        assert observed == attempt['all_totals']
        assert 'Compilation failed' not in log.decode()
        selected = [observed[i] for i in attempt['selected_indices']]
        assert len(selected) == len(attempt['selected_tasks'])
        assert all(r[1] == r[2] == 0 and r[0] == r[3] + r[4] for r in selected)
        rows.extend(selected)
        tasks.extend(attempt['selected_tasks'])
    initial, continuation, tail = s['attempts']
    assert initial['exit'] == 1 and initial['command'] == plan['command']
    assert initial['selected_indices'] == list(range(32))
    assert initial['selected_tasks'] == expected_tasks[:32]
    assert initial['all_totals'][-1] == [755, 3, 0, 752, 0]
    assert len(initial['all_totals']) == 33
    failed_text = raw(initial['log_archive']).decode()
    failed_tests = [line for line in failed_text.splitlines() if '==> X ' in line]
    assert len(failed_tests) == 3 and all('MappingHistoricalSuite' in x and
        'test timed out after 30 seconds' in x for x in failed_tests)
    assert continuation['exit'] == 1
    assert continuation['command'][:4] == plan['command'][:4]
    assert continuation['selected_indices'] == list(range(6))
    assert continuation['selected_tasks'] == expected_tasks[32:38]
    assert continuation['command'][-14:-2] == expected_tasks[32:]
    assert continuation['command'][-2:] == ['scalafmtCheckAll', 'scalafmtSbtCheck']
    assert len(continuation['all_totals']) == 7
    assert continuation['all_totals'][-1] == [160, 1, 0, 159, 0]
    failed_tests = [line for line in raw(continuation['log_archive']).decode().splitlines()
        if '==> X ' in line]
    assert len(failed_tests) == 1 and 'WorkspaceClockSuite' in failed_tests[0]
    assert 'test timed out after 30 seconds' in failed_tests[0]
    assert tail['exit'] == 0 and tail['command'][:4] == plan['command'][:4]
    assert tail['selected_indices'] == list(range(6))
    assert tail['selected_tasks'] == expected_tasks[38:]
    assert tail['command'][-8:-2] == expected_tasks[38:]
    assert tail['command'][-2:] == ['scalafmtCheckAll', 'scalafmtSbtCheck']
    assert len(tail['all_totals']) == 6
    assert tasks == expected_tasks and len(set(tasks)) == 44
    assert rows == s['totals'] and len(rows) == 44
    assert sum(r[3] for r in rows) == s['passed']
    assert sum(r[4] for r in rows) == s['skipped']
    assert sum(r[1] + r[2] for r in rows) == 0
    print('Verified exact source, archives, nine compiled Scala kills, two Python kills and 44 unique scoped tasks; both timeout attempts retained, final Native tail green.')
else:
    print('Verified exact source, archives, nine compiled Scala kills and two Python kills; gate receipt pending.')
