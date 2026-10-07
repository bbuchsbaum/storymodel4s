"""Verify retained synthetic ONNX evidence without relying on a live CI status."""
from pathlib import Path
import gzip
import hashlib
import json
import re
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
CODE = 'd7c0d97a37a6a1ae375f7f12f250e8f7317f591f'
BASE = 'acc5cca1f0d62418c79baa96c09612b21153e758'
SOURCE = 'embed-onnx/src/main/scala/storymodel4s/embed/onnx/OnnxSentenceEmbedder.scala'


def read(name):
    return json.loads((HERE / name).read_text())


def raw(name):
    return gzip.decompress((HERE / name).read_bytes())


def clean(data):
    return re.sub(r'\x1b\[[0-9;]*[A-Za-z]', '', data.decode())


def digest(data):
    return hashlib.sha256(data).hexdigest()


def totals(data):
    return [list(map(int, x)) for x in re.findall(
        r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)', clean(data))]


def sums(rows):
    return dict(passed=sum(t[3] for t in rows), failed=sum(t[1] for t in rows),
                errors=sum(t[2] for t in rows), skipped=sum(t[0] - sum(t[1:]) for t in rows))


def metadata(name, data, exit_code):
    meta = read(name + '.log.meta.json')
    assert meta['exit_code'] == meta['child_returncode'] == exit_code
    assert meta['log_bytes'] == len(data)
    assert meta['reason'] == 'exited' and meta['duration_seconds'] > 0
    return meta


def main_compiled(text):
    return bool(re.search(r'compiling 1 Scala source to .*embed-onnx/target/[^\n]*/classes', text))


def tests_compiled(text):
    return bool(re.search(r'compiling 3 Scala sources to .*embed-onnx/target/[^\n]*/test-classes', text))


def patched_source(original, patch):
    """Apply the retained unified hunk bodies with exact context, without editing the checkout."""
    lines = original.decode().splitlines(keepends=True)
    diff = patch.decode().splitlines(keepends=True)
    assert diff[0] == '--- a/' + SOURCE + '\n'
    assert diff[1] == '+++ b/' + SOURCE + '\n'
    output, cursor, i = [], 0, 2
    while i < len(diff):
        hunk = re.fullmatch(r'@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@[^\n]*\n', diff[i])
        assert hunk, diff[i]
        start = int(hunk[1]) - 1
        assert start >= cursor
        output.extend(lines[cursor:start]); cursor = start; i += 1
        removed = added = 0
        while i < len(diff) and not diff[i].startswith('@@ '):
            line = diff[i]; assert line[0] in ' +-'
            if line[0] in ' -':
                assert lines[cursor] == line[1:], (cursor, line)
                cursor += 1; removed += 1
            if line[0] in ' +':
                output.append(line[1:]); added += 1
            i += 1
        assert removed == int(hunk[2] or '1') and added == int(hunk[4] or '1')
    output.extend(lines[cursor:])
    return ''.join(output).encode()


original = subprocess.run(['git', 'show', CODE + ':' + SOURCE], cwd=ROOT,
                          capture_output=True, check=True).stdout
assert b'final class RecordedBatch private[OnnxSentenceEmbedder]' in original

gate = read('local-gate.json')
assert gate['source_sha'] == CODE and gate['base_sha'] == BASE and gate['exit'] == 0
assert gate['clean_exact_clone_after_restoration'] and gate['cold_onnx_compile_and_test']
log = raw('platform-gate-clean.log.gz'); text = clean(log)
assert digest(log) == gate['raw_log_sha256']
assert totals(log) == gate['sbt_totals'] and len(totals(log)) == gate['cells'] == 23
assert sums(totals(log)) == gate['sum'] == dict(passed=5198, failed=0, errors=0, skipped=2)
assert main_compiled(text) and tests_compiled(text)
for suite, count in [('OnnxRecordedBatchSuite', 10), ('OnnxRecordedBatchCourtSuite', 6)]:
    assert re.search(suite + r' finished: 0 failed, 0 ignored, ' + str(count) + r' total', text)
meta = metadata('platform-gate-clean', log, 0)
assert meta['command'][-2:] == ['scalafmtCheckAll', 'scalafmtSbtCheck']
assert 'embedOnnx/clean' in meta['command'] and 'set ThisBuild / tlFatalWarnings := true' in meta['command']
assert meta['command'][:8] == ['env', '-u', 'STORYMODEL4S_ONNX_MODEL', '-u',
                               'STORYMODEL4S_ONNX_TOKENIZER', '-u', 'STORYMODEL4S_DATA_ROOT', 'sbt']
assert meta['command'][7:] == gate['command'] == read('gate-command.json')['argv']
for key, rows in [('jvm', totals(log)[:11]), ('js', totals(log)[11:17]), ('native', totals(log)[17:])]:
    expected = gate['platforms'][key]
    assert expected['cells'] == len(rows)
    assert expected['passed'] == sums(rows)['passed'] and expected['skipped'] == sums(rows)['skipped']

prior = read('incremental-probe-recovery.json'); priorlog = raw('platform-gate.log.gz')
assert prior['candidate_sha'] == CODE and prior['exit'] == 1 and prior['source_restored_clean']
assert digest(priorlog) == prior['raw_log_sha256'] and totals(priorlog)[-1] == [22, 2, 0, 20]
metadata('platform-gate', priorlog, 1)
assert not tests_compiled(clean(priorlog))
for name in ['record fromProduct is closed', 'record product mirror is closed']:
    assert any(name in line for line in clean(priorlog).splitlines() if line.startswith('==> X'))

discovery = read('constructor-discovery.json'); dlog = raw('constructor.log.gz')
assert discovery['candidate_sha'] == CODE and discovery['exit'] == 1
assert digest(dlog) == discovery['log_sha256'] and totals(dlog) == discovery['totals'] == [[6, 2, 0, 4]]
assert main_compiled(clean(dlog)) and tests_compiled(clean(dlog))
metadata('constructor', dlog, 1)
dpatch = raw('constructor.patch.gz')
assert digest(dpatch) == discovery['patch_sha256']
assert digest(patched_source(original, dpatch)) == discovery['source_sha256']
assert len(re.findall(r'^==> X ', clean(dlog), re.M)) == 2
for key in ['named_failure', 'coaffected_named_failure']:
    assert any(discovery[key] in line for line in clean(dlog).splitlines() if line.startswith('==> X'))

mutations = read('mutations.json')
assert len(mutations) == len({m['mutation'] for m in mutations}) == 9
for m in mutations:
    name = m['mutation']; mlog = raw(name + '.log.gz'); mtext = clean(mlog)
    assert m['candidate_sha'] == CODE and m['compiled'] and m['killed'] and m['exit'] == 1
    assert digest(mlog) == m['log_sha256'] and totals(mlog) == m['totals'] == [m['expected']]
    assert m['expected'][1:3] == [1, 0]
    failures = [line for line in mtext.splitlines() if line.startswith('==> X')]
    assert len(failures) == 1 and m['named_failure'] in failures[0]
    assert re.search(r'munit\.(?:ComparisonFail|Fail)Exception', failures[0])
    ignored = sum(int(x) for x in re.findall(r'(?:Passed|Failed): Total[^\n]*, Ignored (\d+)', mtext))
    assert ignored == m['filter_ignored']
    mmeta = metadata(name, mlog, 1); assert mmeta['command'] == m['argv']
    patch = raw(name + '.patch.gz')
    assert digest(patch) == m['patch_sha256'] and digest(patched_source(original, patch)) == m['source_sha256']
    if name == 'constructor-scoped':
        assert m['source_sha256'] == discovery['source_sha256']
        assert m['compilation_receipt'] == 'constructor-discovery.json' and not main_compiled(mtext)
    else:
        assert main_compiled(mtext)
    if m['clean_test_recompile']:
        assert tests_compiled(mtext) and 'embedOnnx/Test/clean' in m['argv']
    assert 'done compiling' in mtext

courts = raw('courts-final.log.gz')
assert totals(courts) == [[16, 0, 0, 16]] and tests_compiled(clean(courts))
metadata('courts-final', courts, 0)

for kind in ['source', 'docs']:
    receipt = read(f'hosted/{kind}/receipt.json'); state = read(f'hosted/{kind}/run.json')
    assert state['headSha'] == CODE and state['status'] == 'completed' and state['conclusion'] == 'success'
    assert receipt['sha'] == CODE and receipt['run'] == state['databaseId'] and receipt['conclusion'] == 'success'
    archive = HERE / f'hosted/{kind}/logs.zip'
    assert digest(archive.read_bytes()) == receipt['archive_sha256']
    assert archive.stat().st_size == receipt['archive_bytes']
    with zipfile.ZipFile(archive) as z:
        bodies = [z.read(n) for n in z.namelist() if not n.endswith('/')]
    for job in receipt['jobs']:
        jlog = raw(f'hosted/{kind}/{job["job_id"]}.log.gz')
        assert jlog in bodies and len(jlog) == job['log_bytes'] and digest(jlog) == job['log_sha256']
        assert CODE in clean(jlog) and job['conclusion'] == 'success'
        assert totals(jlog) == job['sbt_totals'] and len(totals(jlog)) == job['cells']
        assert sums(totals(jlog)) == {k: job[k] for k in ['passed', 'failed', 'errors', 'skipped']}
        if kind == 'docs':
            assert job['examples'] == 13 and 'verified 13 executable documentation examples' in clean(jlog)
        elif 'rootNative' in job['name']:
            assert job['cells'] == 16 and job['passed'] == 2822
            assert 'WorkspaceVoyageSuite' in clean(jlog) and '0 ignored, 9 total' in clean(jlog)
    aggregate = {k: sum(j[k] for j in receipt['jobs']) for k in ['cells', 'passed', 'failed', 'errors', 'skipped']}
    assert aggregate == receipt['totals']
hosted = read('hosted/source/receipt.json')
assert len(hosted['jobs']) == 4 and hosted['totals'] == dict(cells=80, passed=12754, failed=0, errors=0, skipped=12)

handoff = read('handoff.json'); publication = read('code-publication.json')
assert handoff['code_sha'] == publication['candidate_sha'] == CODE
assert set(publication['git_refs'].values()) == set(publication['buc']['source_refs'].values()) == {CODE}
assert publication['buc']['source_clean'] and not publication['buc']['data_or_credentials_moved']
assert handoff['closure']['exit'] == 0 and handoff['closure']['readback_status'] == 'closed'
assert handoff['closure']['canonical_note']['text'] == handoff['closure']['note']
assert handoff['final_states'] == dict(open=112, closed=310)
assert handoff['final_release_counts'] == {'release-1.0': 26, 'release-1.1': 24, 'release-later': 62}
assert read('doctor.json')['ok']
assert all(not read('fsck.json')[k] for k in ['bad_filename', 'bad_hash', 'bad_json', 'bad_op_shape'])
assert read('preservation.json')['consumer_preserved_paths'] == 1177
assert read('preservation.json')['consumer_mismatches'] == 0

names = set()
for line in (HERE / 'SHA256SUMS').read_text().splitlines():
    expected, name = line.split('  ', 1)
    assert name not in names; names.add(name)
    assert digest((HERE / name).read_bytes()) == expected, name
actual = {str(p.relative_to(HERE)) for p in HERE.rglob('*') if p.is_file()}
assert names == actual - {'SHA256SUMS', 'validation.json'}, 'artifact coverage'
result = dict(code_sha=CODE, local=gate['sum'], compiled_named_mutant_kills=9,
              hosted=hosted['totals'], docs_examples=13, child_closed=True, parent_complete=False,
              verified_artifacts=len(names))
if (HERE / 'validation.json').exists():
    assert read('validation.json') == result
print(json.dumps(result, indent=2))
