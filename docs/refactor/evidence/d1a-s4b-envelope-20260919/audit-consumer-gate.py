"""Read-only reconciliation of an exact storyatlas4s consumer gate; snapshot its evidence."""
from pathlib import Path
import argparse,hashlib,json,re,subprocess,xml.etree.ElementTree as ET
p=argparse.ArgumentParser()
p.add_argument('--receipt',type=Path,required=True)
p.add_argument('--provider-revision',required=True)
p.add_argument('--consumer-revision',required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
sha=lambda b:hashlib.sha256(b).hexdigest()
r=json.loads(a.receipt.read_text());consumer=Path(r['cwd']).resolve();clone_root=consumer.parent
assert consumer==(clone_root/'storyatlas4s').resolve()
# One second accommodates subsecond receipt clocks versus filesystem timestamps.
run_start=r['startedEpochSeconds'];run_end=run_start+r['elapsedSeconds']
assert run_end>=run_start
def from_this_run(path):
 return run_start-1<=path.stat().st_mtime<=run_end+1
assert r['exitCode']==0
assert r['before']==r['after']
expected=dict(storymodel4s=a.provider_revision,storyatlas4s=a.consumer_revision,intaglio='4eb566d9208f474d64d61e778e084dee2ddbaa76',grakern='0329c43c88a0b71e9aa4456723bb16bac2fa3841')
assert set(r['before'])==set(expected)
for name,revision in expected.items():
 clone=clone_root/name
 assert r['before'][name]==dict(revision=revision,status='')
 assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=clone,text=True).strip()==revision
 assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone)
command=r['command']
bindings=[('storyatlas4s.storymodel4s.build','storymodel4s'),('storyatlas4s.intaglio.build','intaglio'),('storymodel4s.grakern.build','grakern')]
expected_command=['sbt','-batch']+['-D'+prop+'='+str(clone_root/name) for prop,name in bindings]+['clean','compileAll','testAll','scalafmtCheckAll','app/fastLinkJS']
assert command==expected_command
log=Path(r['logPath']).read_bytes();assert sha(log)==r['logSha256']
text=log.decode();assert 'COMMAND_EXIT=0' in text and 'COMMAND '+json.dumps(command) in text
totals=[s for s in text.splitlines() if s.startswith(('[info] Passed: Total','[info] Failed: Total','[error] Failed: Total'))]
assert totals==r['testTotals']
def counts(line):
 c=dict.fromkeys(['Total','Failed','Errors','Passed','Skipped'],0)
 for k,v in re.findall(r'(Total|Failed|Errors|Passed|Skipped) (\d+)',line):c[k]+=int(v)
 return c
aggregate=counts('\n'.join(totals));assert aggregate==r['aggregateTestCounts']
assert aggregate['Total']>0 and aggregate['Passed']==aggregate['Total'] and aggregate['Failed']==aggregate['Errors']==aggregate['Skipped']==0
build=(consumer/'build.sbt').read_text()
tasks=[t for t in re.search(r'"testAll"\s*->\s*"([^"]+)"',build).group(1).split(';') if t]
assert tasks==r['expectedTestTasks']
assert set(r['testTaskWitnesses'])==set(tasks)
snapshot=a.output.with_suffix('')
assert not a.output.exists() and not snapshot.exists()
rows=[];snapshots=[];nonempty=[]
for task in tasks:
 project=task.split('/')[0];module=project.removesuffix('JVM').removesuffix('JS')
 backend='.jvm' if project.endswith('JVM') else '.js' if project.endswith('JS') else ''
 directory=consumer/module/backend
 reports=sorted((directory/'target/test-reports').glob('TEST-*.xml'))
 c=dict.fromkeys(aggregate,0);suites=[]
 for f in reports:
  assert from_this_run(f),(str(f),'XML outside recorded run window')
  b=f.read_bytes();x=ET.fromstring(b);tc=x.findall('testcase')
  actual=dict(Total=len(tc),Failed=sum(t.find('failure') is not None for t in tc),Errors=sum(t.find('error') is not None for t in tc),Skipped=sum(t.find('skipped') is not None for t in tc))
  actual['Passed']=actual['Total']-actual['Failed']-actual['Errors']-actual['Skipped']
  for k,v in [('Total','tests'),('Failed','failures'),('Errors','errors'),('Skipped','skipped')]:assert actual[k]==int(x.attrib[v])
  for k,v in actual.items():c[k]+=v
  suites.append(dict(suite=x.attrib['name'],**{k:int(x.attrib[k]) for k in ['tests','failures','errors','skipped']}))
  rel=Path(project)/f.name;snapshots.append((rel,b))
 if reports:nonempty.append(c)
 else:
  # Check portable and selected platform test sources; absence of XML alone is insufficient.
  sources=[f for base in {consumer/module/'src/test',directory/'src/test'} for f in base.rglob('*') if f.suffix in {'.scala','.java'}]
  assert not sources,(task,sources)
 witness=r['testTaskWitnesses'][task]
 assert witness['testCount']==c['Total'] and witness['suites']==suites
 rows.append(dict(task=task,counts=c,suites=[dict(path=str(rel),sha256=sha(b)) for rel,b in snapshots if rel.parts[0]==project],emptyTaskSourceChecked=not reports))
assert nonempty==[counts(line) for line in totals]
assert {k:sum(row['counts'][k] for row in rows) for k in aggregate}==aggregate
linked=list((consumer/'app/target').glob('scala-*/storyatlas4s-app-fastopt/main.js'))
assert len(linked)==1 and from_this_run(linked[0])
mainjs=linked[0].read_bytes();assert len(mainjs)>0
for rel,b in snapshots:
 dest=snapshot/rel;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(b)
report=dict(scope='Independent exact-provider consumer gate, per-task fresh JUnit and production link reconciliation',actualRevisions=expected,command=command,receiptSha256=sha(a.receipt.read_bytes()),logSha256=sha(log),tasks=rows,aggregateTestCounts=aggregate,allCloneStatesVerifiedClean=True,artifactWindow=dict(start=run_start,end=run_end,toleranceSeconds=1),snapshotDirectory=str(snapshot),linkedMainJs=str(linked[0]),linkedMainJsSha256=sha(mainjs),findings=[],limits='Local compilation/tests/linking only; no browser interaction or remote publication.')
a.output.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(dict(output=str(a.output),tasks=len(tasks),counts=aggregate,linkedMainJsSha256=sha(mainjs))))
