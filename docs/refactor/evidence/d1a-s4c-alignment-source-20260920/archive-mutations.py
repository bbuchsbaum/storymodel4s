"""Freeze terminal mutation receipts, including rejected historical attempts."""
from pathlib import Path
import hashlib,json,subprocess,tarfile,time,xml.etree.ElementTree as ET
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s');data=root/'data/study/d1a-s4c-20260920';dest=root/'docs/refactor/evidence/d1a-s4c-alignment-source-20260920'
repo=Path('/private/tmp/storymodel4s-d1a-s4c-20260920')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
manifest=json.loads((data/'mutations.json').read_text());rows=manifest['mutations'];inventory=json.loads((data/'guard-witness-inventory.json').read_text());expected={x['id']:x for x in inventory['cases']}
assert len(rows)==len(expected)==24
assert {x['id'] for x in rows}==set(expected)
assert sum(x['cleanRecompile'] for x in rows)==14
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()==manifest['codeRevision']
assert not subprocess.check_output(['git','status','--porcelain'],cwd=repo)
records=[]
for row in rows:
    inv=expected[row['id']]
    assert row['originalSha256']==inv['originalSha256']==row['restoredSha256']==sha(repo/row['path'])
    assert row['mutantSha256']==inv['mutantSha256']
    assert row['baselineCleanBeforeMutation'] and row['restoredCleanAfterMutation']
    log=Path(row['logPath']);assert sha(log)==row['logSha256']
    label=log.stem
    outcomes={}
    for suite,digest in row['junitSha256'].items():
        p=data/(label+'--'+suite+'.xml');assert sha(p)==digest
        for t in ET.fromstring(p.read_bytes()).findall('testcase'):outcomes[(suite,t.attrib['name'])]=t
    reject=outcomes[(row['rejectSuite'],row['rejectingTest'])];control=outcomes[(row['controlSuite'],row['acceptingControl'])]
    assert reject.find('failure') is not None
    assert all(control.find(k) is None for k in ['failure','error','skipped'])
    if row['cleanRecompile']:
        assert any(x.endswith('/Compile/clean') for x in row['command'])
        assert any(x.endswith('/Test/clean') for x in row['command'])
        assert '/classes' in log.read_text() and '/test-classes' in log.read_text()
    records.append({k:row[k] for k in ['id','path','originalSha256','mutantSha256','restoredSha256','baselineCleanBeforeMutation','restoredCleanAfterMutation','rejectSuite','rejectingTest','controlSuite','acceptingControl','cleanRecompile','logSha256','junitSha256']})
restored=manifest['restoredControl'];assert restored['exitCode']==0 and restored['cleanBefore'] and restored['cleanAfter']
assert restored['aggregateTestCounts']==dict(Total=26,Failed=0,Errors=0,Passed=26,Skipped=0)
assert sha(Path(restored['logPath']))==restored['logSha256']
reports=list(data.glob('restored-control-clean--*.xml'));assert len(reports)==7
cases=[t for p in reports for t in ET.fromstring(p.read_bytes()).findall('testcase')]
assert len(cases)==26 and all(all(t.find(k) is None for k in ['failure','error','skipped']) for t in cases)
summary=dict(schema='d1a-s4c-mutation-terminal/v1',codeRevision=manifest['codeRevision'],planned=24,compiledKilled=24,
    freshMainAndProbeRecompiles=14,namedControlsPassed=24,terminalCloneClean=True,restoredControl=restored,
    restorationBasis='Per-case baseline clean assertion, finally restores original bytes, and post-case hash/clean assertion; independent terminal hashes rechecked.',
    historicalAttemptsExcluded='Seven Test-only-clean probe attempts plus one uncompilable private-base constructor attempt; retained in archive.',
    manifestSha256=sha(data/'mutations.json'),runnerSha256=sha(data/'qualify.py'),records=records)
(data/'mutation-terminal-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
for name in ['qualify.py','hsmm-parity.py','guard-witness-inventory.json','mutation-terminal-summary.json']:(dest/name).write_bytes((data/name).read_bytes())
paths=[p for p in data.iterdir() if p.is_file() and p.suffix in ['.json','.xml','.log','.py','.txt']]
archive=dest/'mutation-evidence.tar.gz';assert not archive.exists()
with tarfile.open(archive,'w:gz') as tar:
    for p in sorted(paths):tar.add(p,arcname=p.name)
index=dict(archive=archive.name,sha256=sha(archive),bytes=archive.stat().st_size,memberCount=len(paths),
    members=[dict(path=p.name,bytes=p.stat().st_size,sha256=sha(p)) for p in sorted(paths)])
(dest/'mutation-evidence-manifest.json').write_text(json.dumps(index,indent=2)+'\n')
print(json.dumps({k:v for k,v in index.items() if k!='members'},indent=2))
