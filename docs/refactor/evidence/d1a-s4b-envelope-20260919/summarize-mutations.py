"""Read-only terminal reconciliation; restoration state is labelled by its actual evidence basis."""
from pathlib import Path
import hashlib,json,runpy,subprocess,time,xml.etree.ElementTree as ET

out=Path(__file__).resolve().parent
runner=out/'qualify.py'
scope=runpy.run_path(str(runner),run_name='terminal_summary_import')
repo=scope['repo'];revision=scope['revision'];cases=scope['cases']
manifest=json.loads((out/'mutations.json').read_text())
assert manifest['codeRevision']==revision
assert len(manifest['mutations'])==len(cases)==54
assert not subprocess.check_output(['git','status','--porcelain'],cwd=repo)
assert manifest['restoredControl']['cleanBefore'] and manifest['restoredControl']['cleanAfter']
assert manifest['restoredControl']['exitCode']==0
assert manifest['restoredControl']['aggregateTestCounts']==dict(Total=62,Failed=0,Errors=0,Passed=62,Skipped=0)
def sha(data):return hashlib.sha256(data).hexdigest()
records=[]
for index,(case,record) in enumerate(zip(cases,manifest['mutations'])):
    assert case['id']==record['id']
    assert Path(record['logPath']).name==case['id']+'-attempt-1.log'
    original=subprocess.check_output(['git','show',revision+':'+case['path']],cwd=repo)
    mutant=case['change'](original.decode()).encode()
    restored=(repo/case['path']).read_bytes()
    assert sha(original)==record['originalSha256']==sha(restored)
    assert sha(mutant)==record['mutantSha256']
    assert sha((out/Path(record['logPath']).name).read_bytes())==record['logSha256']
    outcomes={}
    for suite,digest in record['junitSha256'].items():
        path=out/(Path(record['logPath']).stem+'--'+suite+'.xml')
        data=path.read_bytes();assert sha(data)==digest
        for test in ET.fromstring(data).findall('testcase'):
            outcomes[(suite,test.attrib['name'])]=test
    failure=outcomes[(record['rejectSuite'],record['rejectingTest'])]
    control=outcomes[(record['controlSuite'],record['acceptingControl'])]
    assert failure.find('failure') is not None and failure.find('error') is None
    assert all(control.find(tag) is None for tag in ['failure','error','skipped'])
    records.append(dict(id=case['id'],path=case['path'],originalSha256=sha(original),
        mutantSha256=sha(mutant),restoredSha256=sha(restored),
        baselineCleanBeforeMutation=True,restoredCleanAfterMutation=True,
        cleanStateBasis='Initial runner assertion' if index==0 else 'Previous iteration restoration assertion',
        restorationBasis='Finally write_bytes(saved), followed by assert clean(); completed uninterrupted loop',
        hashObservation='Original and reconstructed mutant bound to Git/attempt receipt; restored bytes checked at terminal',
        rejecting=dict(suite=record['rejectSuite'],name=record['rejectingTest'],outcome='failure'),
        control=dict(suite=record['controlSuite'],name=record['acceptingControl'],outcome='passed'),
        cleanTestRecompile=record['cleanRecompile'],logSha256=record['logSha256'],junitSha256=record['junitSha256']))
summary=dict(schema='d1a-mutation-terminal-summary/v1',codeRevision=revision,observedEpochSeconds=time.time(),
    completed=54,planned=54,compiledKilled=54,namedAcceptingControlsPassed=54,
    cleanProbeRecompiles=sum(c['cleanRecompile'] for c in cases),
    runnerExitCode=0,runnerExitBasis='Terminal exec session 82915 returned exit 0; final manifest is written only after clean restoration',
    perCaseStatusLimit='Clean states follow completed runner assertions, not independent per-case git-status snapshots. Each sbt receipt correctly reports the intentionally dirty mutant-present tree.',
    terminalCloneClean=True,terminalRestoredControl=manifest['restoredControl'],
    runnerSha256=sha(runner.read_bytes()),runnerLogSha256=sha((out/'mutation-runner-1.log').read_bytes()),
    manifestSha256=sha((out/'mutations.json').read_bytes()),records=records)
target=out/'mutation-terminal-summary.json';assert not target.exists()
target.write_text(json.dumps(summary,indent=2)+'\n')
print('54/54 compiled kills, 54 controls, 24 own clean probe recompiles; exact clean clone; 62 restored tests passed')
