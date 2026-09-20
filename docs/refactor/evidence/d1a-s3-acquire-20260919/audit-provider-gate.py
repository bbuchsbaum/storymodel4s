"""Independent provider receipt/JUnit reconciliation; no builds or repository mutations."""
import argparse, hashlib, json, re, shutil, subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--receipt', required=True, type=Path)
parser.add_argument('--clone', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
receipt = json.loads(args.receipt.read_text())
revision = receipt['codeRevision']
def git(*parts):
    return subprocess.check_output(['git', *parts], cwd=args.clone, text=True).strip()
def sha(data):
    return hashlib.sha256(data).hexdigest()
def counts(line):
    value = dict.fromkeys(['Total', 'Failed', 'Errors', 'Passed', 'Skipped'], 0)
    value.update({k: int(v) for k, v in re.findall(r'(Total|Failed|Errors|Passed|Skipped) (\d+)', line)})
    return value
assert git('rev-parse', 'HEAD') == revision
assert not git('status', '--porcelain')
assert receipt['exitCode'] == 0 and receipt['cleanBefore'] and receipt['cleanAfter']
assert receipt['command'][-3:] == ['clean', 'compileAll', 'testAll']
log = Path(receipt['logPath'])
assert log.is_absolute(), 'Pass a receipt with an absolute retained log path'
assert sha(log.read_bytes()) == receipt['logSha256']
build = git('show', revision + ':build.sbt')
def declared_list(name):
    match = re.search(r'val ' + name + r' = List\((.*?)\)', build, re.S)
    assert match, name
    return re.findall(r'"(\w+)"', match.group(1))
modules, platforms, jvm = (declared_list(n) for n in ['allModules', 'allPlatforms', 'jvmOnlyModules'])
tasks = [m + p + '/test' for m in modules for p in platforms] + [m + '/test' for m in jvm]
paths = dict(re.findall(r'lazy val (\w+) = (?:crossProject\([^\n]+\)\s+\.crossType\([^\n]+\)|project)\s+\.in\(file\("([^"]+)"\)\)', build))
assert set(modules + jvm) <= paths.keys()
assert list(receipt['taskTotalsInAliasOrder']) == tasks
actual_lines = [l for l in log.read_text().splitlines() if l.startswith(('[info] Passed: Total', '[info] Failed: Total', '[error] Failed: Total'))]
assert actual_lines == receipt['testTotals'] == list(receipt['taskTotalsInAliasOrder'].values())
assert len(actual_lines) == len(tasks)
assert not args.output.exists(), args.output
snapshot = args.output.with_suffix('')
assert not snapshot.exists(), snapshot
snapshot.mkdir(parents=True)
# macOS records file creation. Other platforms use captured run duration as a documented approximation.
created = receipt.get('startedEpochSeconds', getattr(log.stat(), 'st_birthtime', log.stat().st_mtime - receipt['seconds'] - 2))
ended = created + receipt['seconds']
time_basis = 'recorded start and elapsed duration' if 'startedEpochSeconds' in receipt else ('log file creation plus recorded duration' if hasattr(log.stat(), 'st_birthtime') else 'log modification minus recorded duration minus 2 seconds')
all_counts = dict.fromkeys(['Total', 'Failed', 'Errors', 'Passed', 'Skipped'], 0)
records, skipped = [], []
for task, line in zip(tasks, actual_lines):
    project = task.split('/')[0]
    backend = next((p for p in platforms if project.endswith(p)), None)
    module = project[:-len(backend)] if backend else project
    directory = args.clone / paths[module]
    if backend: directory /= '.' + backend.lower()
    reports = sorted((directory / 'target/test-reports').glob('TEST-*.xml'))
    assert reports, task
    destination = snapshot / task.replace('/', '-')
    destination.mkdir()
    record = {'task': task, 'suites': []}
    observed = dict.fromkeys(['Total', 'Failed', 'Errors', 'Passed', 'Skipped'], 0)
    for report in reports:
        assert created - 1 <= report.stat().st_mtime <= ended + 1, (task, 'JUnit outside recorded run window', str(report))
        data = report.read_bytes()
        tree = ET.fromstring(data)
        testcases = tree.findall('testcase')
        values = {key: int(tree.get(attr, '0')) for key, attr in [('Total', 'tests'), ('Failed', 'failures'), ('Errors', 'errors'), ('Skipped', 'skipped')]}
        assert values['Total'] == len(testcases), report
        for key, tag in [('Failed', 'failure'), ('Errors', 'error'), ('Skipped', 'skipped')]:
            assert values[key] == sum(t.find(tag) is not None for t in testcases), report
        values['Passed'] = values['Total'] - values['Failed'] - values['Errors'] - values['Skipped']
        for key in observed: observed[key] += values[key]
        for test in testcases:
            if test.find('skipped') is not None: skipped.append({'task': task, 'suite': tree.get('name'), 'test': test.get('name')})
        saved = destination / report.name
        shutil.copyfile(report, saved)
        record['suites'].append({'name': tree.get('name'), 'file': str(saved.relative_to(snapshot)), 'sha256': sha(data)})
    assert observed == counts(line), (task, observed, counts(line))
    record['counts'] = observed
    records.append(record)
    for key in all_counts: all_counts[key] += observed[key]
assert all_counts == receipt['aggregateTestCounts']
assert all_counts['Failed'] == all_counts['Errors'] == 0
pin = args.clone / 'pipeline/src/test/resources/golden/d1a-s0-text-parity.json'
pin_sha = sha(pin.read_bytes())
assert pin_sha == 'cc201d9dd3e3576fabcd45677369f00c023ca5a455ea9d9e7ba694700c759fb3'
result = {'codeRevision': revision, 'command': receipt['command'], 'receiptSha256': sha(args.receipt.read_bytes()), 'logSha256': receipt['logSha256'], 'taskCount': len(tasks), 'aggregateTestCounts': all_counts, 's0GoldenSha256': pin_sha, 'cleanCloneObserved': True, 'freshnessTimeBasis': time_basis, 'artifactWindow': {'start': created, 'end': ended, 'toleranceSeconds': 1}, 'snapshotDirectory': str(snapshot), 'tasks': records, 'skippedTests': skipped, 'qualification': 'Independent local provider receipt reconciliation; no consumer, formatting, docs, CI or scientific qualification.'}
args.output.write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({k: v for k, v in result.items() if k not in ['tasks', 'skippedTests']}, indent=2))
