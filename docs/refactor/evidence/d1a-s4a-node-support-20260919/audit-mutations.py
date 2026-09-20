"""Reconcile completed S4a mutation receipts to source, logs and archived JUnit; no builds."""
from pathlib import Path
import datetime
import hashlib
import importlib.util
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

root = Path('/Users/bbuchsbaum/code/scala/storymodel4s')
out = root / 'data/study/d1a-s4a-20260919'
revision = '94e0a9a229fe4224f9312220baae23c8e6e9ffa5'
destination = root / 'data/study/film-foundation-goal-20260919/root-s4a-settled36-audit.json'

def sha(data):
    return hashlib.sha256(data).hexdigest()

def source(path):
    return subprocess.check_output(['git', 'show', revision + ':' + path], cwd=root)

spec = importlib.util.spec_from_file_location('s4a_qualification_definition', out / 'qualify.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)  # Definitions only; its main guard prevents execution.
assert runner.revision == revision
inventory = json.loads((out / 'guard-witness-inventory.json').read_text())
receipt = json.loads((out / 'mutations.json').read_text())
assert receipt['codeRevision'] == inventory['codeRevision'] == revision
assert inventory['plannedMutationCount'] == len(receipt['mutations']) == len(runner.cases) == 36
assert len({case['id'] for case in receipt['mutations']}) == 36
assert [{k: v for k, v in case.items() if k != 'change'} for case in runner.cases] == inventory['cases']
assert sum(case['cleanRecompile'] for case in runner.cases) == 10

def check_run(record):
    assert record['codeRevision'] == revision
    log = Path(record['logPath'])
    assert log.parent == out and log.is_absolute()
    data = log.read_bytes()
    assert sha(data) == record['logSha256']
    text = data.decode()
    totals = [line for line in text.splitlines() if line.startswith(
        ('[info] Passed: Total', '[info] Failed: Total', '[error] Failed: Total'))]
    assert totals == record['testTotals']
    counts = dict.fromkeys(['Total', 'Failed', 'Errors', 'Passed', 'Skipped'], 0)
    for line in totals:
        for key, value in re.findall(r'(Total|Failed|Errors|Passed|Skipped) (\d+)', line):
            counts[key] += int(value)
    assert counts == record['aggregateTestCounts']
    assert counts['Total'] == sum(counts[key] for key in ['Failed', 'Errors', 'Passed', 'Skipped'])
    return counts, text

def inspect_xml(path, suite, record, expected_digest=None):
    data = path.read_bytes()
    digest = sha(data)
    if expected_digest is not None:
        assert digest == expected_digest
    tree = ET.fromstring(data)
    assert tree.attrib['name'] == suite
    working_directories = [prop.attrib['value'] for prop in tree.findall('properties/property')
                           if prop.get('name') == 'user.dir']
    assert len(working_directories) == 1, (path, working_directories)
    assert Path(working_directories[0]).resolve() == runner.repo.resolve(), (path, working_directories)
    moment = datetime.datetime.fromisoformat(tree.attrib['timestamp'])
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=ZoneInfo('America/Toronto'))
    timestamp = moment.timestamp()
    assert record['startedEpochSeconds'] - 1 <= timestamp <= record['startedEpochSeconds'] + record['seconds'] + 1, (path, timestamp)
    tests = tree.findall('testcase')
    counts = dict(Total=len(tests), Failed=sum(test.find('failure') is not None for test in tests),
                  Errors=sum(test.find('error') is not None for test in tests),
                  Skipped=sum(test.find('skipped') is not None for test in tests))
    counts['Passed'] = counts['Total'] - counts['Failed'] - counts['Errors'] - counts['Skipped']
    for key, attr in [('Total', 'tests'), ('Failed', 'failures'), ('Errors', 'errors'), ('Skipped', 'skipped')]:
        assert counts[key] == int(tree.attrib[attr])
    outcomes = {}
    for test in tests:
        key = (suite, test.attrib['name'])
        assert key not in outcomes
        outcomes[key] = test
    return counts, outcomes, digest

restored_receipt = json.loads((out / 'restored-control-clean.json').read_text())
all_suites = {suite for _, group_suites in runner.groups.values() for suite in group_suites}
assert len(all_suites) == 7
restored_xml = {suite: inspect_xml(out / ('restored-control-clean--' + suite + '.xml'), suite, restored_receipt)
                for suite in all_suites}
restored_membership = {suite: set(parsed[1]) for suite, parsed in restored_xml.items()}
assert sum(len(keys) for keys in restored_membership.values()) == 40

records = []
for expected, record in zip(runner.cases, receipt['mutations']):
    assert all(record[key] == value for key, value in expected.items() if key != 'change')
    assert record['exitCode'] == 1 and record['compiled'] and record['acceptingControlPassed']
    assert record['cleanBefore'] == record['cleanAfter'] == False
    original = source(record['path'])
    mutant = expected['change'](original.decode()).encode()
    assert sha(original) == record['originalSha256']
    assert sha(mutant) == record['mutantSha256'] and mutant != original
    counts, log_text = check_run(record)
    assert counts['Failed'] > 0 and counts['Errors'] == counts['Skipped'] == 0
    project, suites = runner.groups[record['group']]
    assert set(record['junitSha256']) == set(suites)
    tasks = ([project + '/Test/clean'] if record['cleanRecompile'] else []) + [runner.command(record['group'])]
    assert record['command'] == runner.prefix + tasks
    if record['cleanRecompile']:
        assert re.search(r'compiling \d+ Scala sources? to .*story/\.jvm/target/scala-[^/]+/test-classes', log_text)
    label = Path(record['logPath']).stem
    assert re.fullmatch(re.escape(record['id']) + r'-attempt-[1-9]\d*', label)
    attempt_path = out / (label + '.json')
    attempt_receipt = json.loads(attempt_path.read_text())
    assert all(key in record and record[key] == value for key, value in attempt_receipt.items())
    assert set(attempt_receipt) == {'codeRevision', 'command', 'exitCode', 'cleanBefore', 'cleanAfter',
                                   'startedEpochSeconds', 'seconds', 'logPath', 'logSha256',
                                   'testTotals', 'aggregateTestCounts'}
    outcomes = {}
    aggregate = dict.fromkeys(counts, 0)
    for suite, digest in record['junitSha256'].items():
        values, tests, _ = inspect_xml(out / (label + '--' + suite + '.xml'), suite, record, digest)
        assert set(tests) == restored_membership[suite], (label, suite, 'testcase population differs from restored control')
        for key, value in values.items():
            aggregate[key] += value
        assert not set(tests).intersection(outcomes)
        outcomes.update(tests)
    assert aggregate == counts
    assert outcomes[record['rejectSuite'], record['rejectingTest']].find('failure') is not None
    control = outcomes[record['controlSuite'], record['acceptingControl']]
    assert all(control.find(kind) is None for kind in ['failure', 'error', 'skipped'])
    failed = [dict(suite=suite, name=name) for (suite, name), test in outcomes.items() if test.find('failure') is not None]
    assert failed == record['failedTests']
    records.append(dict(id=record['id'], attempt=label, originalSha256=sha(original), mutantSha256=sha(mutant),
                        attemptReceiptSha256=sha(attempt_path.read_bytes()),
                        logSha256=record['logSha256'], junitSha256=record['junitSha256'], counts=counts,
                        namedKillAndControlReconciled=True, freshReports=True, cloneDirectoryBound=True,
                        testcasePopulationMatchesRestored=True, cleanProbeRecompile=record['cleanRecompile']))

controls = {}
for label in ['isolated-control', 'restored-control-clean']:
    control_receipt = json.loads((out / (label + '.json')).read_text())
    counts, _ = check_run(control_receipt)
    assert control_receipt['exitCode'] == 0 and control_receipt['cleanBefore'] and control_receipt['cleanAfter']
    assert counts == dict(Total=40, Failed=0, Errors=0, Passed=40, Skipped=0)
    tasks = ([] if label == 'isolated-control' else ['coreJVM/Test/clean', 'storyJVM/Test/clean', 'codecJVM/Test/clean']) + runner.control_tasks()
    assert control_receipt['command'] == runner.prefix + tasks
    controls[label] = dict(logSha256=control_receipt['logSha256'], counts=counts,
                           receiptSha256=sha((out / (label + '.json')).read_bytes()))
    if label == 'restored-control-clean':
        assert control_receipt == receipt['restoredControl']
        aggregate = dict.fromkeys(counts, 0)
        saved_xml = {}
        for suite in sorted(all_suites):
            values, _, digest = restored_xml[suite]
            for key, value in values.items():
                aggregate[key] += value
            saved_xml[suite] = digest
        assert aggregate == counts
        controls[label]['junitSha256'] = saved_xml

assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=runner.repo, text=True).strip() == revision
assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=runner.repo)
s0 = 'pipeline/src/test/resources/golden/d1a-s0-text-parity.json'
assert sha(source(s0)) == 'cc201d9dd3e3576fabcd45677369f00c023ca5a455ea9d9e7ba694700c759fb3'
report = dict(codeRevision=revision, mutationReceiptSha256=sha((out / 'mutations.json').read_bytes()),
              inventorySha256=sha((out / 'guard-witness-inventory.json').read_bytes()),
              runnerSha256=sha((out / 'qualify.py').read_bytes()), mutants=records, controls=controls,
              cleanCloneAtAudit=True, s0Sha256=sha(source(s0)), findings=[],
              limitations=['Initial control reconciled to its captured log; per-suite control XML is archived for the clean restored run.',
                           'This verifies local mutation evidence, not the full provider, docs, consumer or scientific recovery gates.'])
assert not destination.exists(), destination
destination.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(dict(output=str(destination), mutants=len(records), cleanProbeRecompiles=10, controlTests=40, findings=[])))
