import datetime
import hashlib
import json
import os
import re
import signal
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

os.umask(0o077)
home = Path.home()
work = home / '.local/share/storymodel4s/checkpoints/20261006'
repo = home / 'code/scala/storyatlas4s'
candidate = 'd67dccfb7eec07e184ce3ef8fe6ccc16c3745523'
producer = '433aa1056f6aa5e88b63b4b665079e9959a266ae'
intaglio = '2fa5c682f4a95b8e73daad78dd9302d7aff46e11'
status = work / 'integrated-consumer-04-status.json'
assert not status.exists(), 'owned job already exists; observe rather than restart'
env = os.environ.copy()
env['PATH'] = str(home / '.local/bin') + ':/opt/homebrew/bin:' + str(home / '.cargo/bin') + ':' + env['PATH']
env['JAVA_HOME'] = subprocess.check_output(['/usr/libexec/java_home', '-v', '21']).decode().strip()
for key in list(env):
    if key.startswith(('STORYMODEL4S_', 'STORYATLAS4S_', 'OPENAI_', 'ANTHROPIC_', 'HF_')) or key in ('GH_TOKEN', 'GITHUB_TOKEN', 'GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN', 'JAVA_TOOL_OPTIONS', 'SBT_OPTS', 'HUGGING_FACE_HUB_TOKEN'):
        env.pop(key, None)
env['GIT_SSH_COMMAND'] = 'ssh -o BatchMode=yes -o StrictHostKeyChecking=yes'

def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

result = {'schema': 1, 'pid': os.getpid(), 'machine': subprocess.check_output(['scutil', '--get', 'LocalHostName']).decode().strip(), 'candidate': candidate, 'producer': producer, 'intaglio': intaglio, 'started_utc': now(), 'stages': [], 'private_data_used': False, 'state': 'waiting for owned producer gate'}
assert result['machine'] == 'BUC-GW01'

def save():
    temp = status.with_suffix('.tmp')
    temp.write_text(json.dumps(result, indent=2) + '\n')
    temp.replace(status)

def run_stage(name, command, timeout=7200):
    log = work / ('integrated-consumer-04-' + name + '.log')
    stage = {'name': name, 'command': command, 'log': str(log), 'started_utc': now()}
    result['stages'].append(stage)
    save()
    with log.open('xb') as output:
        process = subprocess.Popen(command, cwd=repo, env=env, stdout=output, stderr=output, start_new_session=True)
        stage['child_pid'] = process.pid
        save()
        try:
            stage['exit_code'] = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            stage['exit_code'] = 124
    body = log.read_bytes()
    stage['bytes'] = len(body)
    stage['sha256'] = hashlib.sha256(body).hexdigest()
    stage['totals'] = [dict(zip(('total', 'failed', 'errors', 'passed', 'skipped'), [int(v or 0) for v in values])) for values in re.findall(rb'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)(?:, Skipped (\d+))?', body)]
    stage['finished_utc'] = now()
    save()
    if stage['exit_code'] != 0:
        raise RuntimeError('stage failed: ' + name)

save()
try:
    deadline = time.monotonic() + 14400
    while True:
        upstream = json.loads((work / 'integrated-producer-04-status.json').read_text())
        assert upstream['candidate'] == producer
        if upstream.get('completed'):
            assert upstream['exit_code'] == 0, 'producer gate failed; consumer remains unqualified'
            break
        assert time.monotonic() < deadline, 'producer wait timed out'
        time.sleep(10)
    result['state'] = 'qualifying'
    result['producer_gate_finished_utc'] = upstream['finished_utc']
    save()
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo).strip(), 'consumer checkout is not clean'
    run_stage('fetch', ['git', 'fetch', 'origin', 'codex/workstation-checkpoint-20261006'], 300)
    assert subprocess.check_output(['git', 'rev-parse', 'FETCH_HEAD'], cwd=repo).decode().strip() == candidate
    run_stage('fast-forward', ['git', 'merge', '--ff-only', candidate], 60)
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo).decode().strip() == candidate
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo).strip()
    producer_repo = home / 'code/scala/storymodel4s'
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=producer_repo).decode().strip() == producer
    fixture = producer_repo / 'docs/refactor/evidence/workspace-m1-packet-20260922/fixtures/bell.voyage.json'
    edition = 'target/edition'
    voyage = 'target/workstation-checkpoint/bell-voyage'
    assert not (repo / edition).exists() and not (repo / voyage).exists(), 'output already exists'
    result['java'] = subprocess.check_output([env['JAVA_HOME'] + '/bin/java', '-version'], stderr=subprocess.STDOUT).decode().strip()
    result['node'] = subprocess.check_output(['node', '--version'], env=env).decode().strip()
    run_stage('full', ['sbt', '-batch', '-Dsbt.log.noformat=true', 'set ThisBuild / tlFatalWarnings := true', 'compileAll', 'testAll', 'app/fastLinkJS', 'cli/run edition --out ' + edition, 'app/editionBundle', 'cli/run voyage --document ' + str(fixture) + ' --out ' + voyage, 'githubWorkflowGenerate', 'scalafmtCheckAll', 'scalafmtSbtCheck', 'githubWorkflowCheck'])
    outputs = []
    for path, receipt_name, count, bound in [(repo / edition, 'receipt.json', 32, 29), (repo / voyage, 'voyage-receipt.json', 4, 3)]:
        receipt = json.loads((path / receipt_name).read_text())
        assert receipt['storymodel4sRevision'] == producer and receipt['intaglioRevision'] == intaglio
        assert len(receipt['files']) == bound
        for entry in receipt['files']:
            assert hashlib.sha256((path / entry['file']).read_bytes()).hexdigest() == entry['sha256']
        files = sorted(p for p in path.rglob('*') if p.is_file())
        assert len(files) == count
        for svg in path.glob('*.svg'):
            ET.parse(svg)
        outputs.append({'output': str(path.relative_to(repo)), 'files': len(files), 'bytes': sum(p.stat().st_size for p in files), 'receipt_bound_files': bound, 'producer': receipt['storymodel4sRevision'], 'intaglio': receipt['intaglioRevision'], 'hashes': {str(p.relative_to(path)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}, 'svg_xml_valid': True})
    run_stage('javascript-syntax', ['node', '--check', 'target/edition/app.js'], 60)
    result['outputs'] = outputs
    result['loaded_sources'] = []
    expected = {producer, intaglio, '0329c43c88a0b71e9aa4456723bb16bac2fa3841', 'd55fe2f97196a76ab7879e1a12f1e92403aeba06', 'ea5d2d762f85f5a0f97ee188deb5fac0ef2bcbaf'}
    build_log = Path(result['stages'][2]['log']).read_text()
    for definition in sorted(set(re.findall(r'loading project definition from (.+)/project', build_log))):
        checkout = Path(definition)
        if (checkout / '.git').is_dir():
            head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=checkout).decode().strip()
            if head in expected:
                clean = not subprocess.check_output(['git', 'status', '--porcelain'], cwd=checkout).strip()
                assert clean
                result['loaded_sources'].append({'head': head, 'clean': clean, 'path': str(checkout)})
    assert expected.issubset({s['head'] for s in result['loaded_sources']}), 'not all declared source dependencies found'
    result['tracked_tree_clean'] = not subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo).strip()
    assert result['tracked_tree_clean']
    assert len(result['stages'][2]['totals']) == 10, 'expected ten consumer test cells'
    result['exit_code'] = 0
except Exception as error:
    result['exit_code'] = 1
    result['failure'] = str(error)
finally:
    result['state'] = 'completed'
    result['finished_utc'] = now()
    result['completed'] = True
    save()

raise SystemExit(result['exit_code'])
