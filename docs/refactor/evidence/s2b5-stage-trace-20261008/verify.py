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
qpath = E / 'qualification.json'
if qpath.exists():
    q = json.loads(qpath.read_text())
    assert q['source_sha'] == SHA
    s = q['source']
    log = raw(s['log_archive'])
    meta = json.loads(raw(s['metadata_archive']))
    assert digest(log) == s['log_sha256'] and meta['exit_code'] == s['exit'] == 0
    assert meta['command'] == s['command'] and meta['command'][-2:] == ['scalafmtCheckAll', 'scalafmtSbtCheck']
    rows = totals(log.decode())
    assert rows == s['totals'] and len(rows) == 44
    assert sum(r[3] for r in rows) == s['passed']
    assert sum(r[4] for r in rows) == s['skipped']
    assert sum(r[1] + r[2] for r in rows) == 0
    print('Verified exact source, archives, nine compiled Scala kills, two Python kills and complete scoped gate.')
else:
    print('Verified exact source, archives, nine compiled Scala kills and two Python kills; gate receipt pending.')
