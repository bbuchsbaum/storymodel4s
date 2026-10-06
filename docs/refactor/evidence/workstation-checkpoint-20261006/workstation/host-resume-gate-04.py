import datetime
import hashlib
import json
import os
import re
import signal
import subprocess
import time
from pathlib import Path

os.umask(0o077)
home = Path.home()
work = home / '.local/share/storymodel4s/checkpoints/20261006'
repo = home / 'code/scala/storymodel4s'
candidate = '433aa1056f6aa5e88b63b4b665079e9959a266ae'
status = work / 'integrated-producer-04-status.json'
assert not status.exists(), 'owned job already exists; resume observation rather than restarting'
env = os.environ.copy()
env['PATH'] = str(home / '.local/bin') + ':/opt/homebrew/bin:' + str(home / '.cargo/bin') + ':' + env['PATH']
env['JAVA_HOME'] = subprocess.check_output(['/usr/libexec/java_home', '-v', '21']).decode().strip()
removed = []
for key in list(env):
    if key.startswith(('STORYMODEL4S_', 'STORYATLAS4S_', 'OPENAI_', 'ANTHROPIC_', 'HF_')) or key in ('GH_TOKEN', 'GITHUB_TOKEN', 'GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN', 'JAVA_TOOL_OPTIONS', 'SBT_OPTS', 'HUGGING_FACE_HUB_TOKEN'):
        removed.append(key)
        env.pop(key, None)
env['GIT_SSH_COMMAND'] = 'ssh -o BatchMode=yes -o StrictHostKeyChecking=yes'

def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

result = {'schema': 1, 'pid': os.getpid(), 'machine': subprocess.check_output(['scutil', '--get', 'LocalHostName']).decode().strip(), 'candidate': candidate, 'started_utc': now(), 'stages': [], 'private_data_used': False, 'environment_removed_names': sorted(removed)}
assert result['machine'] == 'BUC-GW01'

def save():
    temp = status.with_suffix('.tmp')
    temp.write_text(json.dumps(result, indent=2) + '\n')
    temp.replace(status)

def run_stage(name, command, timeout=10800):
    log = work / ('integrated-producer-04-' + name + '.log')
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
    preceding = json.loads((work / 'integrated-producer-03-status.json').read_text())
    assert preceding['candidate'] == candidate and preceding['completed'] and preceding['exit_code'] == 1
    original = Path(preceding['stages'][-1]['log']).read_bytes()
    assert hashlib.sha256(original).hexdigest() == '1a161346dc1b595068c3468e198a5fa816fe466e6afdd021b2bf78f9738189d2'
    original_cells = [dict(zip(('total', 'failed', 'errors', 'passed', 'skipped'), [int(v or 0) for v in values])) for values in re.findall(rb'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)(?:, Skipped (\d+))?', original)]
    assert len(original_cells) == 45 and sum(c['passed'] for c in original_cells[:44]) == 7789
    assert all(c['failed'] == c['errors'] == c['skipped'] == 0 for c in original_cells[:44])
    assert original_cells[-1] == {'total': 160, 'failed': 2, 'errors': 0, 'passed': 158, 'skipped': 0}
    assert b'test timed out after 30 seconds' in original
    result['coverage_mode'] = 'unchanged-SHA passed prefix plus affected module rerun and unrun suffix'
    result['passed_prefix'] = {'status': str(work / 'integrated-producer-03-status.json'), 'raw_log_sha256': hashlib.sha256(original).hexdigest(), 'cells': original_cells[:44], 'passed': 7789, 'failed': 0, 'errors': 0, 'skipped': 0}
    result['state'] = 'waiting for available workstation CPU'
    result['resource_policy'] = {'one_minute_load_at_most': 8.0, 'five_minute_load_at_most': 10.0, 'stable_seconds': 120, 'maximum_wait_seconds': 7200}
    result['load_observations'] = []
    save()
    deadline = time.monotonic() + 7200
    stable_since = None
    while True:
        load = os.getloadavg()
        result['load_observations'].append({'utc': now(), 'averages': list(load)})
        available = load[0] <= 8 and load[1] <= 10
        if available and stable_since is None:
            stable_since = time.monotonic()
        elif not available:
            stable_since = None
        save()
        if stable_since is not None and time.monotonic() - stable_since >= 120:
            break
        assert time.monotonic() < deadline, 'workstation remained busy; no tests started'
        time.sleep(30)
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo).decode().strip() == candidate
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo).strip(), 'checkout is not clean'
    result['state'] = 'qualifying remaining checks'
    save()
    result['java'] = subprocess.check_output([env['JAVA_HOME'] + '/bin/java', '-version'], stderr=subprocess.STDOUT).decode().strip()
    result['node'] = subprocess.check_output(['node', '--version'], env=env).decode().strip()
    result['clang'] = subprocess.check_output(['clang', '--version'], env=env).decode().splitlines()[0]
    result['python'] = subprocess.check_output(['python3', '--version'], env=env).decode().strip()
    save()
    run_stage('full', ['sbt', '-batch', '-Dsbt.log.noformat=true', 'set ThisBuild / tlFatalWarnings := true', 'fixturesNative/test', 'lawsJVM/test', 'lawsJS/test', 'lawsNative/test', 'providerParser/test', 'providerAgent/test', 'embedGrakern/test', 'embedOnnx/test', 'embedBench/test', 'media/test', 'pipeline/test', 'corpusIntake/test', 'doc',
        'project alignNative', 'set nativeConfig ~= (_.withMode(scala.scalanative.build.Mode.releaseFast))', 'testOnly storymodel4s.align.AlignmentMathSuite storymodel4s.align.LocalEvidenceParityProbe storymodel4s.align.ChartedScoringParityProbe storymodel4s.align.NumericalPropagationSuite',
        'project codecNative', 'set nativeConfig ~= (_.withMode(scala.scalanative.build.Mode.releaseFast))', 'testOnly storymodel4s.codec.MappingCodecDigestSuite storymodel4s.codec.NumericalRecordCaptureSuite',
        'project fixturesNative', 'set nativeConfig ~= (_.withMode(scala.scalanative.build.Mode.releaseFast))', 'testOnly storymodel4s.fixtures.wog.WarOfTheGhostsCodecGoldenSuite storymodel4s.fixtures.wog.WarOfTheGhostsCodecGoldenResourceSuite storymodel4s.fixtures.wog.WogHsmmBaselineCaptureSuite',
        'project /', 'scalafmtCheckAll', 'scalafmtSbtCheck', 'githubWorkflowCheck', 'alias testNative'])
    result['final_head'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo).decode().strip()
    result['tracked_tree_clean'] = not subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo).strip()
    assert result['final_head'] == candidate and result['tracked_tree_clean']
    suffix = result['stages'][-1]['totals']
    assert len(suffix) == 15, 'expected twelve remaining full test cells plus three ReleaseFast courts'
    assert all(c['failed'] == c['errors'] == 0 for c in suffix)
    result['coverage_totals'] = {'cells': 44 + len(suffix), 'passed': 7789 + sum(c['passed'] for c in suffix), 'failed': 0, 'errors': 0, 'skipped': sum(c['skipped'] for c in suffix)}
    assert result['coverage_totals'] == {'cells': 59, 'passed': 9007, 'failed': 0, 'errors': 0, 'skipped': 6}, 'unexpected complete coverage totals'
    result['exit_code'] = 0
except Exception as error:
    result['exit_code'] = 1
    result['failure'] = str(error)
finally:
    result['state'] = 'completed'
    result['finished_utc'] = now()
    result['completed'] = True
    save()

if result['exit_code'] == 0:
    consumer = subprocess.run(['/usr/bin/python3', str(work / 'host-consumer-gate-04.py')], env=env)
    raise SystemExit(consumer.returncode)
raise SystemExit(result['exit_code'])
