"""Archive complete job logs and bind exact published-head qualification."""
import gzip
import hashlib
import json
import re
import subprocess
from pathlib import Path

SHA = '655c0b3921539ad0c2cdf46181f0711480379f12'
GH = '/Users/bbuchsbaum/.local/bin/gh-bbuchsbaum'
REPO = 'bbuchsbaum/storymodel4s'
O = Path('/private/tmp/storymodel4s-s2b5-hosted')
E = Path('/Users/bbuchsbaum/code/scala/storymodel4s/docs/refactor/evidence/s2b5-stage-trace-20261008')
archive = json.loads((E / 'archive.json').read_text())
ansi = re.compile(r'\x1b\[[0-?]*[ -/]*[@-~]')

def save(path, body):
    target = E / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(gzip.compress(body, mtime=0) if path.endswith('.gz') else body)
    global archive
    archive = [x for x in archive if x['path'] != path]
    archive.append({'path': path, 'raw_bytes': len(body), 'raw_sha256': hashlib.sha256(body).hexdigest()})

publication = {'published_sha': SHA, 'source_sha': '95ba0e4d5c05742cea5983f0bfddbe65acfc66cc', 'remote_verified': True, 'status': 'hosted_declared_pins_qualified', 'logs_basis': 'Complete original job logs; ANSI normalized only while parsing.'}
for slug in ['source', 'docs']:
    run = json.loads((O / f'{slug}-run.json').read_text())
    jobs = json.loads((O / f'{slug}-jobs.json').read_text())
    assert run['head_sha'] == SHA and run['status'] == 'completed' and run['conclusion'] == 'success'
    assert len(jobs['jobs']) == (4 if slug == 'source' else 1)
    records = []
    for job in jobs['jobs']:
        assert job['status'] == 'completed' and job['conclusion'] == 'success'
        path = f'runs/hosted-{slug}-job-{job["id"]}.log.gz'
        result = subprocess.run([GH, 'api', f'repos/{REPO}/actions/jobs/{job["id"]}/logs', '--allow-escape-sequences'], capture_output=True)
        assert result.returncode == 0, result.stderr.decode()
        save(path, result.stdout)
        text = ansi.sub('', result.stdout.decode())
        rows = [[int(v or 0) for v in row] for row in re.findall(r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)(?:, (?:Skipped|Ignored) (\d+))?', text)]
        record = {'id': job['id'], 'name': job['name'], 'url': job['html_url'], 'conclusion': job['conclusion'], 'log_archive': path, 'log_bytes': len(result.stdout), 'log_sha256': hashlib.sha256(result.stdout).hexdigest(), 'totals': rows}
        if slug == 'source':
            assert len(rows) == (24 if 'rootJVM' in job['name'] else 16)
            assert all(row[1] == row[2] == 0 and row[0] == row[3] + row[4] for row in rows)
            if 'rootJVM' in job['name']:
                for suite in ['StageTraceOriginSuite', 'StageTraceBoundarySuite', 'StageTraceSuite', 'HistoricalEmbeddingParitySuite']:
                    assert suite in text, suite
        records.append(record)
    meta = f'runs/hosted-{slug}-run.json'
    jobmeta = f'runs/hosted-{slug}-jobs.json'
    save(meta, (O / f'{slug}-run.json').read_bytes())
    save(jobmeta, (O / f'{slug}-jobs.json').read_bytes())
    publication[slug] = {'run': run['id'], 'url': run['html_url'], 'conclusion': run['conclusion'], 'metadata_archive': meta, 'jobs_metadata_archive': jobmeta, 'jobs': records}
rows = [row for job in publication['source']['jobs'] for row in job['totals']]
publication['source'].update(task_totals=len(rows), passed=sum(r[3] for r in rows), failed=sum(r[1] for r in rows), errors=sum(r[2] for r in rows), skipped=sum(r[4] for r in rows))
assert len(rows) == 80
save('runs/hosted-controller-events.jsonl', (O / 'events.jsonl').read_bytes())
(E / 'archive.json').write_text(json.dumps(archive, indent=2) + '\n')
(E / 'publication-655c.json').write_text(json.dumps(publication, indent=2) + '\n')
print(json.dumps({k: publication['source'][k] for k in ['task_totals', 'passed', 'failed', 'errors', 'skipped']}))
