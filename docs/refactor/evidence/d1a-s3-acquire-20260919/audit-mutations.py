from pathlib import Path
import datetime, hashlib, importlib.util, json, re, subprocess, xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s')
out=root/'data/study/d1a-s3-20260919'
rev='17194421f018e3780aafa4adf754fccf55398b61'
def sha(b): return hashlib.sha256(b).hexdigest()
def source(path): return subprocess.check_output(['git','show',rev+':'+path],cwd=root)
sp=importlib.util.spec_from_file_location('s3qualify',out/'qualify.py')
mod=importlib.util.module_from_spec(sp);sp.loader.exec_module(mod)
assert mod.revision==rev
inventory=json.loads((out/'guard-witness-inventory.json').read_text())
receipt=json.loads((out/'mutations.json').read_text())
assert receipt['codeRevision']==inventory['codeRevision']==rev
assert inventory['plannedMutationCount']==len(receipt['mutations'])==len(mod.cases)==18
assert len({c['id'] for c in receipt['mutations']})==18
assert [{k:v for k,v in c.items() if k!='change'} for c in mod.cases]==inventory['cases']
def check_run(r):
 assert r['codeRevision']==rev
 b=Path(r['logPath']).read_bytes(); assert sha(b)==r['logSha256']
 totals=[l for l in b.decode().splitlines() if l.startswith(('[info] Passed: Total','[info] Failed: Total','[error] Failed: Total'))]
 assert totals==r['testTotals']
 counts=dict.fromkeys(['Total','Failed','Errors','Passed','Skipped'],0)
 for line in totals:
  for k,v in re.findall(r'(Total|Failed|Errors|Passed|Skipped) (\d+)',line): counts[k]+=int(v)
 assert counts==r['aggregateTestCounts']
 assert counts['Total']==sum(counts[k] for k in ['Failed','Errors','Passed','Skipped'])
 return counts
rows=[]
for expected,r in zip(mod.cases,receipt['mutations']):
 assert all(r[k]==v for k,v in expected.items() if k!='change')
 assert r['exitCode']==1 and r['compiled'] and r['acceptingControlPassed']
 assert r['cleanBefore']==r['cleanAfter']==False
 original=source(r['path']);mutant=expected['change'](original.decode()).encode()
 assert sha(original)==r['originalSha256'] and sha(mutant)==r['mutantSha256'] and mutant!=original
 counts=check_run(r); assert counts['Failed']>0 and counts['Errors']==counts['Skipped']==0
 project,suites=mod.groups[r['group']]
 assert set(r['junitSha256'])==set(suites)
 assert r['command'][-1]==mod.command(r['group'])
 outcomes={};aggregate=dict.fromkeys(['Total','Failed','Errors','Passed','Skipped'],0)
 for suite,digest in r['junitSha256'].items():
  path=out/(r['id']+'--'+suite+'.xml'); b=path.read_bytes();assert sha(b)==digest
  x=ET.fromstring(b); assert x.attrib['name']==suite
  ts=datetime.datetime.fromisoformat(x.attrib['timestamp']).replace(tzinfo=ZoneInfo('America/Toronto')).timestamp()
  assert r['startedEpochSeconds']-1 <= ts <= r['startedEpochSeconds']+r['seconds']+1,(r['id'],ts)
  tc=x.findall('testcase'); actual=dict(Total=len(tc),Failed=sum(t.find('failure') is not None for t in tc),Errors=sum(t.find('error') is not None for t in tc),Skipped=sum(t.find('skipped') is not None for t in tc))
  actual['Passed']=actual['Total']-actual['Failed']-actual['Errors']-actual['Skipped']
  for k,v in [('Total','tests'),('Failed','failures'),('Errors','errors'),('Skipped','skipped')]: assert actual[k]==int(x.attrib[v])
  for k,v in actual.items():aggregate[k]+=v
  for t in tc:
   key=(suite,t.attrib['name']); assert key not in outcomes;outcomes[key]=t
 assert aggregate==counts
 assert outcomes[r['rejectSuite'],r['rejectingTest']].find('failure') is not None
 control=outcomes[r['controlSuite'],r['acceptingControl']]
 assert all(control.find(k) is None for k in ['failure','error','skipped'])
 failed=[dict(suite=s,name=n) for (s,n),t in outcomes.items() if t.find('failure') is not None]
 assert failed==r['failedTests']
 rows.append(dict(id=r['id'],originalSha256=sha(original),mutantSha256=sha(mutant),logSha256=r['logSha256'],junitSha256=r['junitSha256'],counts=counts,compiledNamedKillAndControl=True,freshReports=True,junitTimestampZone='America/Toronto'))
controls={}
for label in ['isolated-control','restored-control']:
 r=json.loads((out/(label+'.json')).read_text());counts=check_run(r)
 assert r['exitCode']==0 and r['cleanBefore'] and r['cleanAfter']
 assert counts==dict(Total=55,Failed=0,Errors=0,Passed=55,Skipped=0)
 controls[label]=dict(logSha256=r['logSha256'],counts=counts,receiptSha256=sha((out/(label+'.json')).read_bytes()))
 if label=='restored-control':assert r==receipt['restoredControl']
assert not subprocess.check_output(['git','status','--porcelain'],cwd=mod.repo)
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=mod.repo,text=True).strip()==rev
s0='pipeline/src/test/resources/golden/d1a-s0-text-parity.json'
assert sha(source(s0))=='cc201d9dd3e3576fabcd45677369f00c023ca5a455ea9d9e7ba694700c759fb3'
report=dict(codeRevision=rev,mutationReceiptSha256=sha((out/'mutations.json').read_bytes()),inventorySha256=sha((out/'guard-witness-inventory.json').read_bytes()),runnerSha256=sha((out/'qualify.py').read_bytes()),mutants=rows,controls=controls,cleanCloneAtAudit=True,s0Sha256=sha(source(s0)),findings=[],limitations=['Focused before/after controls independently reconciled to their captured logs; this audit does not claim archived per-suite control XML. Full final provider XML is a separate qualification.','Runtime mutation evidence is not empirical film or scientific recovery validation.'])
p=root/'data/study/film-foundation-goal-20260919/root-s3-settled18-audit.json'
assert not p.exists();p.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(dict(output=str(p),mutants=len(rows),controlTests=55,findings=[])))
