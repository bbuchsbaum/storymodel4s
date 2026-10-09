"""Archive complete job logs and bind exact published-head qualification."""
import gzip
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

SHA, directory = sys.argv[1:3]
GH = '/Users/bbuchsbaum/.local/bin/gh-bbuchsbaum'
REPO = 'bbuchsbaum/storymodel4s'
O = Path(directory)
E = Path(__file__).resolve().parent
archive = json.loads((E / 'archive.json').read_text())
ansi = re.compile(r'\x1b\[[0-?]*[ -/]*[@-~]')

def save(path, body):
    target = E / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(gzip.compress(body, mtime=0) if path.endswith('.gz') else body)
    global archive
    archive = [x for x in archive if x['path'] != path]
    archive.append({'path': path, 'raw_bytes': len(body), 'raw_sha256': hashlib.sha256(body).hexdigest()})

publication = {'published_sha': SHA, 'source_sha': 'cd740302cc681b62ac5287e2e829b9a3eb320d63', 'remote_verified': True, 'status': 'hosted_declared_pins_qualified', 'logs_basis': 'Complete original job logs; ANSI normalized only while parsing.'}
for slug in ['source', 'docs']:
    run = json.loads((O / f'{slug}-run.json').read_text())
    jobs = json.loads((O / f'{slug}-jobs.json').read_text())
    assert run['head_sha'] == SHA and run['status'] == 'completed' and run['conclusion'] == 'success'
    assert len(jobs['jobs']) == (4 if slug == 'source' else 1)
    if slug == 'source':
        expected = {f'Test (ubuntu-22.04, 3.7.4, temurin@{java}, {project})'
                    for java, project in [(17, 'rootJVM'), (21, 'rootJVM'),
                                          (17, 'rootJS'), (17, 'rootNative')]}
        assert {j['name'] for j in jobs['jobs']} == expected
    records = []
    for job in jobs['jobs']:
        assert job['status'] == 'completed' and job['conclusion'] == 'success'
        path = f'runs/hosted-{slug}-job-{job["id"]}.log.gz'
        cache = O / f'job-{job["id"]}.log'
        if not cache.exists():
            result = subprocess.run([GH, 'api', f'repos/{REPO}/actions/jobs/{job["id"]}/logs', '--allow-escape-sequences'], capture_output=True)
            assert result.returncode == 0, result.stderr.decode()
            cache.write_bytes(result.stdout)
        body = cache.read_bytes()
        save(path, body)
        text = ansi.sub('', body.decode())
        rows = [[int(v or 0) for v in row] for row in re.findall(r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)(?:, (?:Skipped|Ignored) (\d+))?', text)]
        record = {'id': job['id'], 'name': job['name'], 'url': job['html_url'], 'conclusion': job['conclusion'], 'log_archive': path, 'log_bytes': len(body), 'log_sha256': hashlib.sha256(body).hexdigest(), 'totals': rows}
        if slug == 'source':
            assert len(rows) == (24 if 'rootJVM' in job['name'] else 16)
            assert all(row[1] == row[2] == 0 and row[0] == row[3] + row[4] for row in rows)
            for suite in ['ReferenceMeasurementSuite', 'ReferenceIsolationSuite', 'LocalReferenceBoundarySuite']:
                assert suite in text, suite
            if 'rootJVM' in job['name']:
                assert 'LocalReferenceConsumerSuite' in text
        if slug == 'docs':
            for field, suffix in [('executable_examples', 'executable documentation examples'),
                ('built_pages', 'built pages'), ('sidebar_entries', 'unique sidebar entries'),
                ('internal_links', 'internal links')]:
                match = re.search(r'verified (\d+) ' + re.escape(suffix), text)
                assert match, suffix
                publication.setdefault('docs_checks', {})[field] = int(match.group(1))
            assert 'provenance court: all checks passed' in text
            publication['docs_checks']['provenance_court'] = 'all checks passed'
        records.append(record)
    meta = f'runs/hosted-{slug}-run.json'
    jobmeta = f'runs/hosted-{slug}-jobs.json'
    save(meta, (O / f'{slug}-run.json').read_bytes())
    save(jobmeta, (O / f'{slug}-jobs.json').read_bytes())
    publication[slug] = {'run': run['id'], 'url': run['html_url'], 'conclusion': run['conclusion'], 'metadata_archive': meta, 'jobs_metadata_archive': jobmeta, 'jobs': records}
publication['docs'].update(publication.pop('docs_checks'))
rows = [row for job in publication['source']['jobs'] for row in job['totals']]
publication['source'].update(task_totals=len(rows), passed=sum(r[3] for r in rows), failed=sum(r[1] for r in rows), errors=sum(r[2] for r in rows), skipped=sum(r[4] for r in rows))
assert len(rows) == 80
save('runs/hosted-controller-events.jsonl', (O / 'events.jsonl').read_bytes())
(E / 'archive.json').write_text(json.dumps(archive, indent=2) + '\n')
(E / 'publication.json').write_text(json.dumps(publication, indent=2) + '\n')
print(json.dumps({k: publication['source'][k] for k in ['task_totals', 'passed', 'failed', 'errors', 'skipped']}))
