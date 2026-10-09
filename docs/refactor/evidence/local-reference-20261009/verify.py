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

publication_path = E / 'publication.json'
if publication_path.exists():
    publication = json.loads(publication_path.read_text())
    published_sha = publication['published_sha']
    assert published_sha == '38cf9c7f0f879c883a307cbeb54d51c67f6c1476'
    assert publication['source_sha'] == SHA and publication['remote_verified']
    assert not subprocess.check_output(['git', 'diff', '--name-only', SHA, published_sha,
        '--', '*.scala', 'build.sbt', 'project', '.github/workflows', 'tools/recall-study'], cwd=R)
    ansi = re.compile(r'\x1b\[[0-?]*[ -/]*[@-~]')
    hosted_rows = []
    for slug in ['source', 'docs']:
        receipt = publication[slug]
        run = json.loads(raw(receipt['metadata_archive']))
        jobs = json.loads(raw(receipt['jobs_metadata_archive']))['jobs']
        assert run['head_sha'] == published_sha and run['id'] == receipt['run']
        assert run['status'] == 'completed' and run['conclusion'] == 'success'
        assert {j['id'] for j in jobs} == {j['id'] for j in receipt['jobs']}
        assert len(jobs) == (4 if slug == 'source' else 1)
        if slug == 'source':
            expected = {f'Test (ubuntu-22.04, 3.7.4, temurin@{java}, {project})'
                        for java, project in [(17, 'rootJVM'), (21, 'rootJVM'),
                                              (17, 'rootJS'), (17, 'rootNative')]}
            assert {j['name'] for j in jobs} == expected
        for recorded in receipt['jobs']:
            job = next(j for j in jobs if j['id'] == recorded['id'])
            assert job['run_id'] == run['id'], 'hosted job run'
            assert job['head_sha'] == published_sha, 'hosted job head'
            assert job['run_attempt'] == run['run_attempt'], 'hosted job attempt'
            assert job['name'] == recorded['name'] and job['conclusion'] == 'success'
            assert job['status'] == 'completed'
            log = raw(recorded['log_archive'])
            assert len(log) == recorded['log_bytes'] and digest(log) == recorded['log_sha256']
            text = ansi.sub('', log.decode())
            assert re.search(r'\[command\]/usr/bin/git log -1 --format=%H\r?\n\S+Z ' + re.escape(published_sha) + r'(?:\r?\n|$)', text), 'checkout SHA'
            rows = totals(text)
            assert rows == recorded['totals']
            if slug == 'docs':
                for field, suffix in [('executable_examples', 'executable documentation examples'),
                    ('built_pages', 'built pages'), ('sidebar_entries', 'unique sidebar entries'),
                    ('internal_links', 'internal links')]:
                    assert f"verified {receipt[field]} {suffix}" in text
                assert 'provenance court: all checks passed' in text
            if slug == 'source':
                assert len(rows) == (24 if 'rootJVM' in job['name'] else 16)
                assert all(r[1] == r[2] == 0 and r[0] == r[3] + r[4] for r in rows)
                hosted_rows.extend(rows)
                suites = {'storymodel4s.align.ReferenceMeasurementSuite': 14,
                          'storymodel4s.align.ReferenceIsolationSuite': 7,
                          'storymodel4s.probes.LocalReferenceBoundarySuite': 21}
                if 'rootJVM' in job['name']:
                    suites['storymodel4s.bench.video.LocalReferenceConsumerSuite'] = 3
                assert set(recorded['reference_suites']) == set(suites)
                for suite, expected in suites.items():
                    found = [[int(v) for v in row] for row in re.findall(
                        r'Test run ' + re.escape(suite) +
                        r' finished: (\d+) failed, (\d+) ignored, (\d+) total', text)]
                    assert found == [[0, 0, expected]], 'suite completion ' + suite + ': ' + str(found)
                    assert recorded['reference_suites'][suite] == {'failed': 0, 'ignored': 0, 'passed': expected}
    source = publication['source']
    assert len(hosted_rows) == source['task_totals'] == 80
    assert sum(r[3] for r in hosted_rows) == source['passed']
    assert sum(r[4] for r in hosted_rows) == source['skipped']
    assert source['failed'] == source['errors'] == 0
    print('Verified exact published-head four-job matrix, complete raw job logs and hosted totals.')

court_path = E / 'hosted-receipt-court.json'
if court_path.exists():
    court = json.loads(court_path.read_text())
    assert court['published_sha'] == '38cf9c7f0f879c883a307cbeb54d51c67f6c1476'
    assert court['old_tooling_sha'] == 'eb240ffa7a11576525938b96491a2c94027aeba0'
    for path, expected in court['script_sha256'].items():
        assert digest((E / path).read_bytes()) == expected, path
    assert len(court['cases']) == 12
    assert {c['tool'] for c in court['cases']} == {'collect-hosted.py', 'verify.py'}
    assert {c['case'] for c in court['cases']} == {'wrong-run', 'wrong-head', 'wrong-attempt',
        'wrong-checkout', 'wrong-suite-count', 'missing-suite-completion'}
    tools = {'collect-hosted.py', 'verify.py'}
    cases = {'wrong-run', 'wrong-head', 'wrong-attempt', 'wrong-checkout',
             'wrong-suite-count', 'missing-suite-completion'}
    assert {(c['tool'], c['case']) for c in court['cases']} == {(t, c) for t in tools for c in cases}
    assert all(c['old_exit'] == 0 and c['current_exit'] == 1 for c in court['cases'])
    observations = [json.loads(line) for line in raw('runs/hosted-receipt-court.jsonl').decode().splitlines()]
    assert len(observations) == 26
    expected_observations = {(t, v, c) for t in tools for v in ['old', 'current'] for c in cases}
    expected_observations.update((t, 'current', None) for t in tools)
    assert {(o['tool'], o['version'], o['case']) for o in observations} == expected_observations
    for c in court['cases']:
        old = next(o for o in observations if o['tool'] == c['tool'] and o['case'] == c['case'] and o['version'] == 'old')
        current = next(o for o in observations if o['tool'] == c['tool'] and o['case'] == c['case'] and o['version'] == 'current')
        assert old['exit'] == 0 and current['exit'] == 1
        assert 'AssertionError: ' + c['named_assertion'] in current['stderr']
    assert all(o['exit'] == 0 for o in observations if o['case'] is None)
    print('Verified two clean hosted-receipt controls and twelve named refusals with old-tooling positive controls.')
