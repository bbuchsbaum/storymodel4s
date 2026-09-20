"""Exact candidate consumer court; standalone clones, immutable sibling pins."""
from pathlib import Path
import hashlib,json,os,re,subprocess,time,xml.etree.ElementTree as ET
root=Path(os.environ.get('D1A_S4B_ROOT','/Users/bbuchsbaum/code/scala/storymodel4s'))
out=Path(os.environ.get('D1A_S4B_OUTPUT',str(root/'data/study/d1a-s4b-20260919')))
out.mkdir(parents=True,exist_ok=True)
clone_root=Path(os.environ.get('D1A_S4B_CONSUMER_ROOT','/private/tmp/storymodel4s-film-consumer-20260919-9zqdg50z'))
repos={name:clone_root/name for name in ['storyatlas4s','storymodel4s','intaglio','grakern']}
consumer=repos['storyatlas4s']
def state():
    return {name:dict(revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=path,text=True).strip(),status=subprocess.check_output(['git','status','--porcelain'],cwd=path,text=True).strip()) for name,path in repos.items()}
before=state()
assert all(not x['status'] for x in before.values())
command=['sbt','-batch',
    '-Dstoryatlas4s.storymodel4s.build='+str(repos['storymodel4s']),
    '-Dstoryatlas4s.intaglio.build='+str(repos['intaglio']),
    '-Dstorymodel4s.grakern.build='+str(repos['grakern']),
    'clean','compileAll','testAll','scalafmtCheckAll','app/fastLinkJS']
log=out/'consumer-gate.log'
assert not log.exists()
started=time.time()
with log.open('w') as stream:
    stream.write('COMMAND '+json.dumps(command)+'\n');stream.flush()
    result=subprocess.run(command,cwd=consumer,stdout=stream,stderr=subprocess.STDOUT)
    stream.write('\nCOMMAND_EXIT='+str(result.returncode)+'\n')
totals=[line for line in log.read_text().splitlines() if line.startswith(('[info] Passed: Total','[error] Failed: Total'))]
counts=dict.fromkeys(('Total','Failed','Errors','Passed','Skipped'),0)
for line in totals:
    for key,value in re.findall(r'(Total|Failed|Errors|Passed|Skipped) (\d+)',line): counts[key]+=int(value)
tasks=[t for t in re.search(r'"testAll"\s*->\s*"([^"]+)"',(consumer/'build.sbt').read_text()).group(1).split(';') if t]
witnesses={}
for task in tasks:
    project=task.split('/')[0]
    module=project.removesuffix('JVM').removesuffix('JS')
    backend='.jvm' if project.endswith('JVM') else '.js' if project.endswith('JS') else ''
    directory=consumer/module/backend
    suites=[]
    for report in sorted((directory/'target/test-reports').glob('TEST-*.xml')):
        assert report.stat().st_mtime >= started,report
        xml=ET.parse(report).getroot()
        suites.append(dict(suite=xml.attrib['name'],**{k:int(xml.attrib.get(k,0)) for k in ['tests','failures','errors','skipped']}))
    witnesses[task]=dict(suites=suites,testCount=sum(s['tests'] for s in suites))
    if not suites:
        sources=list((consumer/module/'src/test').rglob('*.scala'))
        witnesses[task]['testSourceFiles']=[str(p.relative_to(consumer)) for p in sources]
        assert not sources,(task,sources)
after=state()
receipt=dict(schema='d1a-consumer-gate/v1',command=command,cwd=str(consumer),startedEpochSeconds=started,elapsedSeconds=time.time()-started,exitCode=result.returncode,before=before,after=after,expectedTestTasks=tasks,testTotals=totals,aggregateTestCounts=counts,testTaskWitnesses=witnesses,logPath=str(log),logSha256=hashlib.sha256(log.read_bytes()).hexdigest(),qualification='Local exact-provider candidate gate; no remote publication or executed CI.')
(out/'consumer-gate.json').write_text(json.dumps(receipt,indent=2)+'\n')
assert result.returncode==0
assert after==before
assert sum(x['testCount'] for x in witnesses.values())==counts['Total']
assert counts['Total']>0 and counts['Failed']==counts['Errors']==counts['Skipped']==0
print(json.dumps(receipt,indent=2))
