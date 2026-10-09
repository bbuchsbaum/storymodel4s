"""Verify exact source, complete archives and assertion-bound compiled witnesses."""
import gzip
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path

E = Path(__file__).resolve().parent
R = E.parents[3]
SHA = 'cd740302cc681b62ac5287e2e829b9a3eb320d63'

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def raw(path):
    b = (E / path).read_bytes()
    return gzip.decompress(b) if path.endswith('.gz') else b

def totals(text):
    return [[int(v or 0) for v in row] for row in re.findall(
        r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)(?:, (?:Skipped|Ignored) (\d+))?', text)]

export = json.loads((E / 'export.json').read_text())
assert export['source_sha'] == SHA and export['clean']
for path, expected in export['changed_files_sha256'].items():
    b = subprocess.check_output(['git', 'show', SHA + ':' + path], cwd=R)
    assert digest(b) == expected, path
for record in json.loads((E / 'archive.json').read_text()):
    b = raw(record['path'])
    assert len(b) == record['raw_bytes'] and digest(b) == record['raw_sha256'], record['path']
mpath = E / 'mutations.json'
if mpath.exists():
    mutations = json.loads(mpath.read_text())
    assert len(mutations) == 17
    assert sum(m['kind'] == 'guard' for m in mutations) == 16
    assert sum(m['kind'] == 'independence-trap' for m in mutations) == 1
    for m in mutations:
        assert m['candidate_sha'] == SHA and m['compiled'] and m['qualified']
        prefix = 'runs/mutations/' + m['name']
        patch, log = raw(prefix + '.patch.gz'), raw(prefix + '.log.gz')
        meta = json.loads(raw(prefix + '.log.meta.json'))
        assert digest(patch) == m['patch_sha256'] and digest(log) == m['log_sha256']
        assert meta['exit_code'] == meta['child_returncode'] == m['exit'] == 1
        assert meta['reason'] == 'exited' and meta['log_bytes'] == len(log)
        assert meta['command'] == m['command'] and 'alignJVM/Test/clean' in m['command']
        text = log.decode()
        failures = [line for line in text.splitlines() if '==> X ' in line]
        assert failures == m['failures'] and len(failures) == 1
        assert m['named_failure'] in failures[0]
        assert any(kind in failures[0] for kind in ['munit.FailException',
            'munit.ComparisonFailException', 'AssertionError'])
        assert 'ClassCastException' not in failures[0]
        rows = totals(text)
        assert rows == m['totals'] and rows[-1][1:4] == [1, 0, 1]
        assert '[info] done compiling' in text and 'Compilation failed' not in text
        with tempfile.TemporaryDirectory(prefix='reference-mutation-verify-') as directory:
            p = Path(directory) / m['path']
            p.parent.mkdir(parents=True)
            p.write_bytes(subprocess.check_output(['git', 'show', SHA + ':' + m['path']], cwd=R))
            result = subprocess.run(['patch', '-p1', '-t'], cwd=directory, input=patch,
                                    capture_output=True)
            assert result.returncode == 0 and digest(p.read_bytes()) == m['source_sha256']
    print('Verified16 compiled guard kills and HSMM trap: each named assertion fails with one passing sibling.')
else:
    print('Exact source/archive verification passed; final mutation qualification pending.')
qp = E / 'qualification.json'
if qp.exists():
    q = json.loads(qp.read_text())
    assert q['source_sha'] == SHA
    for key in ['source', 'consumer']:
        receipt = q[key]
        log = raw(receipt['log_archive'])
        meta = json.loads(raw(receipt['metadata_archive']))
        assert digest(log) == receipt['log_sha256']
        assert meta['exit_code'] == meta['child_returncode'] == receipt['exit'] == 0
        assert meta['reason'] == 'exited' and meta['log_bytes'] == len(log)
        assert meta['command'] == receipt['command']
        assert meta['command'][-2:] == ['scalafmtCheckAll', 'scalafmtSbtCheck']
        rows = totals(log.decode())
        assert rows == receipt['totals']
        assert sum(r[1] + r[2] for r in rows) == 0
        assert sum(r[3] for r in rows) == receipt['passed']
        assert sum(r[4] for r in rows) == receipt['skipped']
        if key == 'source':
            plan = json.loads((E / 'reference-scope.json').read_text())
            assert plan['candidate_sha'] == SHA and meta['command'] == plan['command']
            assert len(rows) == len(plan['jvm_tasks']) + len(plan['portable_tasks']) == 44
        else:
            c = json.loads((E / 'consumer-export.json').read_text())
            assert receipt['consumer_sha'] == c['sha'] and c['clean']
            assert meta['cwd'] == c['clone']
            assert '-Dstoryatlas4s.storymodel4s.build=' + export['clone'] in meta['command']
    print('Verified complete restored scoped source and exact sibling consumer gate exits/totals.')
