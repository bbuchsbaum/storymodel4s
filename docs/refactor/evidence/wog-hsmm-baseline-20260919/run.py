"""Capture immutable pre-migration WOG HSMM bytes for each backend separately."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET

out = Path(__file__).resolve().parent
manifest_path = out / 'manifest.json'
manifest = json.loads(manifest_path.read_text())
repo = Path(manifest['repository'])
grakern = Path(manifest['grakernRepository'])
suite = 'storymodel4s.fixtures.wog.WogHsmmBaselineCaptureSuite'
control = 'storymodel4s.fixtures.wog.WarOfTheGhostsCodecGoldenSuite'

def clean():
    return not subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo)

for platform in ('JVM', 'JS', 'Native'):
    assert clean()
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip() == manifest['instrumentedRevision']
    log = out / (platform.lower() + '.log')
    assert not log.exists(), 'Retain existing attempts; do not overwrite a capture.'
    command = ['sbt', '-batch', '-Dstorymodel4s.grakern.build=' + str(grakern),
               'fixtures' + platform + '/testOnly ' + suite + ' ' + control]
    started = time.time()
    print('START', platform, flush=True)
    with log.open('w') as stream:
        stream.write('COMMAND ' + json.dumps(command) + '\n')
        stream.flush()
        process = subprocess.run(command, cwd=repo, stdout=stream, stderr=subprocess.STDOUT)
        stream.write('\nCOMMAND_EXIT=' + str(process.returncode) + '\n')
    text = log.read_text()
    totals = [line for line in text.splitlines()
              if line.startswith(('[info] Passed: Total', '[info] Failed: Total'))]
    receipt = {'command': command, 'cwd': str(repo), 'exitCode': process.returncode,
               'startedEpochSeconds': started, 'elapsedSeconds': time.time() - started,
               'cleanBefore': True, 'cleanAfter': clean(), 'testTotals': totals,
               'logSha256': hashlib.sha256(log.read_bytes()).hexdigest(), 'logBytes': log.stat().st_size}
    manifest['platforms'][platform] = receipt
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    assert process.returncode == 0, (platform, 'failed; inspect retained log')
    assert receipt['cleanAfter']
    assert len(totals) == 1 and 'Total 3, Failed 0, Errors 0, Passed 3' in totals[0], totals
    marker = 'WOG_HSMM_BASELINE_JSON='
    emitted = [line.split(marker, 1)[1] for line in text.splitlines() if line.startswith(marker)]
    assert len(emitted) == 1, (platform, 'missing or duplicate capture')
    encoded = emitted[0].encode('utf-8')
    parsed = json.loads(encoded)
    target = out / (platform.lower() + '.json')
    target.write_bytes(encoded)
    report_dir = repo / 'fixtures' / ('.' + platform.lower()) / 'target/test-reports'
    reports = [report_dir / ('TEST-' + name + '.xml') for name in (suite, control)]
    cases = [case for report in reports for case in ET.parse(report).getroot().findall('testcase')]
    assert len(cases) == 3 and all(
        case.find('failure') is None and case.find('error') is None and case.find('skipped') is None
        for case in cases
    ), platform
    receipt.update({'artifact': target.name, 'sha256': hashlib.sha256(encoded).hexdigest(),
                    'bytes': len(encoded), 'topLevelKeys': sorted(parsed),
                    'passedTests': [case.attrib['name'] for case in cases]})
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    print('PASS', platform, receipt['sha256'], receipt['bytes'], 'bytes', flush=True)

digests = {name: entry['sha256'] for name, entry in manifest['platforms'].items()}
manifest['crossPlatformObservedDigestsEqual'] = len(set(digests.values())) == 1
manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
print('COMPLETE', json.dumps(digests), flush=True)
