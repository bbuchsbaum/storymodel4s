"""Verify the retained checked-rendering receipts against exact git source and raw logs."""
from pathlib import Path
import gzip
import hashlib
import json
import re
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]

def read(name):
    return json.loads((HERE / name).read_text())

def digest(data):
    return hashlib.sha256(data).hexdigest()

def raw(name):
    return gzip.decompress((HERE / name).read_bytes())

def clean(data):
    return re.sub(r'\x1b\[[0-9;]*[A-Za-z]', '', data.decode())

def totals(data):
    return [list(map(int, row)) for row in re.findall(
        r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)', clean(data))]

def sums(rows):
    return dict(passed=sum(r[3] for r in rows), failed=sum(r[1] for r in rows),
                errors=sum(r[2] for r in rows), skipped=sum(r[0] - sum(r[1:]) for r in rows))

def patched(original, patch, path):
    lines = original.decode().splitlines(True)
    diff = patch.decode().splitlines(True)
    assert diff[:2] == [f'--- a/{path}\n', f'+++ b/{path}\n']
    output, cursor, i = [], 0, 2
    while i < len(diff):
        h = re.fullmatch(r'@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@[^\n]*\n', diff[i])
        assert h, diff[i]
        start = int(h[1]) - 1
        assert start >= cursor
        output.extend(lines[cursor:start]); cursor = start; i += 1
        removed = added = 0
        while i < len(diff) and not diff[i].startswith('@@'):
            tag, body = diff[i][0], diff[i][1:]
            if tag in ' -':
                assert lines[cursor] == body
                cursor += 1; removed += 1
            if tag in ' +':
                output.append(body); added += 1
            assert tag in ' +-'; i += 1
        assert removed == int(h[2] or 1) and added == int(h[4] or 1)
    output.extend(lines[cursor:])
    return ''.join(output).encode()

export = read('export.json'); code = export['candidate_sha']
assert code == 'b06b72a1b8744bfd3ee07b827a85f8219fd91b0a' and export['clean_before_gate']
for path, expected in export['files_sha256'].items():
    original = subprocess.check_output(['git', 'show', f'{code}:{path}'], cwd=ROOT)
    assert digest(original) == expected

mutations = read('mutations.json')
assert len(mutations) == len({m['mutation'] for m in mutations}) == 13
for m in mutations:
    name = m['mutation']; log = raw(name + '.log.gz'); patch = raw(name + '.patch.gz')
    assert m['candidate_sha'] == code and m['exit'] == 1 and m['compiled']
    assert digest(log) == m['log_sha256'] and digest(patch) == m['patch_sha256']
    original = subprocess.check_output(['git', 'show', f'{code}:{m["path"]}'], cwd=ROOT)
    assert digest(patched(original, patch, m['path'])) == m['source_sha256']
    t = totals(log); assert t == m['totals'] and len(t) == 1
    assert t[0][1:3] == [1, 0] and t[0][3] > 0
    failures = [line for line in clean(log).splitlines() if line.startswith('==> X')]
    assert failures == m['failures'] and len(failures) == 1
    assert m['named_failure'] in failures[0]
    assert re.search(r'munit\.(?:ComparisonFail|Fail)Exception', failures[0])
    assert re.search(r'compiling [12] Scala sources? to .*align/\.jvm/target/[^\n]*/classes', clean(log))
    assert 'done compiling' in clean(log)
    if m['clean_test_recompile']:
        assert 'alignJVM/Test/clean' in m['argv']
        assert re.search(r'compiling \d+ Scala sources to .*align/\.jvm/target/[^\n]*/test-classes', clean(log))
    meta = read(name + '.log.meta.json')
    assert meta['command'] == m['argv'] and meta['exit_code'] == meta['child_returncode'] == 1
    assert meta['reason'] == 'exited' and meta['log_bytes'] == len(log)

smoke = raw('smoke-03.log.gz')
assert totals(smoke) == [[42, 0, 0, 42]]
qualification = read('qualification.json')
assert qualification['code_sha'] == code
for name, expected in [('platform-gate', qualification['source']), ('consumer-gate', qualification['consumer'])]:
    log = raw(name + '.log.gz'); meta = read(name + '.log.meta.json')
    assert meta['exit_code'] == meta['child_returncode'] == 0 and meta['reason'] == 'exited'
    assert meta['log_bytes'] == len(log) and digest(log) == expected['log_sha256']
    assert totals(log) == expected['totals'] and sums(totals(log)) == expected['sum']
    assert expected['sum']['failed'] == expected['sum']['errors'] == 0
    assert meta['command'][-2:] == ['scalafmtCheckAll', 'scalafmtSbtCheck']
    if name == 'platform-gate':
        assert len(totals(log)) == 6
        assert all(f'align{p}/clean' in meta['command'] for p in ['JVM', 'JS', 'Native'])
        for platform in ['jvm', 'js', 'native']:
            assert re.search(r'compiling \d+ Scala sources to .*align/\.' + platform + r'/target/[^\n]*/test-classes', clean(log))
    else:
        assert '-Dstoryatlas4s.storymodel4s.build=/private/tmp/storymodel4s-s2b2-build' in meta['command']
        assert expected['consumer_sha'] == read('consumer-preservation.json')['head']
assert qualification['source_restored_clean'] and qualification['consumer_primary_preserved']

names = set()
for line in (HERE / 'SHA256SUMS').read_text().splitlines():
    expected, name = line.split('  ', 1)
    assert name not in names; names.add(name)
    assert digest((HERE / name).read_bytes()) == expected, name
actual = {str(p.relative_to(HERE)) for p in HERE.rglob('*') if p.is_file()}
assert names == actual - {'SHA256SUMS', 'validation.json'}
result = dict(code_sha=code, compiled_named_mutation_kills=13,
              source=qualification['source']['sum'], consumer=qualification['consumer']['sum'],
              verified_artifacts=len(names), parent_complete=False, hosted_refreshed=False)
if (HERE / 'validation.json').exists():
    assert read('validation.json') == result
print(json.dumps(result, indent=2))
