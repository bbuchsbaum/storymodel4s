"""Pinned four-clone consumer gate, preserving raw/red evidence and linked app bytes."""
from pathlib import Path
import hashlib,json,os,re,subprocess,time,xml.etree.ElementTree as ET
root=Path(os.environ.get('D1B_ROOT','/Users/bbuchsbaum/code/scala/storymodel4s')).resolve()
out=Path(os.environ.get('D1B_OUTPUT',str(root/'data/study/d1b-20260920'))).resolve();out.mkdir(parents=True,exist_ok=True)
clone_root=Path(os.environ.get('D1B_CONSUMER_ROOT','/private/tmp/storymodel4s-film-consumer-20260919-9zqdg50z')).resolve()
repos={name:clone_root/name for name in ['storyatlas4s','storymodel4s','intaglio','grakern']};consumer=repos['storyatlas4s']
expected={'storyatlas4s':'42e451ce0577dcf5f40485776b2279d72b941cdf','storymodel4s':'850a6d645dd0d63e5c2d01cd03a41f1e3bd53e37',
 'intaglio':'4eb566d9208f474d64d61e778e084dee2ddbaa76','grakern':'0329c43c88a0b71e9aa4456723bb16bac2fa3841'}
def sha(b):return hashlib.sha256(b).hexdigest()
def state():
 return {name:dict(path=str(path),standalone=(path/'.git').is_dir(),revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=path,text=True).strip(),
  status=subprocess.check_output(['git','status','--porcelain'],cwd=path,text=True).strip()) for name,path in repos.items()}
def bound():
 actual=state();assert {k:v['revision'] for k,v in actual.items()}==expected
 assert all(v['standalone'] and not v['status'] for v in actual.values());return actual
log=out/'consumer-gate.log';receipt_path=out/'consumer-gate.json';assert not log.exists() and not receipt_path.exists()
receipt=dict(schema='d1b/consumer-gate/v1',qualified=False,expectedRevisions=expected,runnerSha256=sha(Path(__file__).read_bytes()),cwd=str(consumer))
def persist():receipt_path.write_text(json.dumps(receipt,indent=2)+'\n')
try:
 receipt['before']=bound();persist()
 pin=re.search(r'lazy val storymodel4sRevision = "([0-9a-f]+)"',(consumer/'build.sbt').read_text()).group(1)
 assert pin==expected['storymodel4s'],'consumer declared provider pin differs'
 tasks=[t for t in re.search(r'"testAll"\s*->\s*"([^"]+)"',(consumer/'build.sbt').read_text()).group(1).split(';') if t]
 assert tasks==['layoutJVM/test','layoutJS/test','intaglioJVM/test','intaglioJS/test','editionJVM/test','editionJS/test','cli/test','app/test']
 receipt['expectedTestTasks']=tasks
 command=['sbt','-batch','-Dstoryatlas4s.storymodel4s.build='+str(repos['storymodel4s']),
  '-Dstoryatlas4s.intaglio.build='+str(repos['intaglio']),'-Dstorymodel4s.grakern.build='+str(repos['grakern']),
  'clean','compileAll','testAll','scalafmtCheckAll','app/fastLinkJS']
 started=time.time();receipt.update(command=command,startedEpochSeconds=started);persist()
 with log.open('x') as stream:
  stream.write('COMMAND '+json.dumps(command)+'\n');stream.flush()
  result=subprocess.run(command,cwd=consumer,stdout=stream,stderr=subprocess.STDOUT)
  stream.write('\nCOMMAND_EXIT='+str(result.returncode)+'\n')
 receipt.update(exitCode=result.returncode,elapsedSeconds=time.time()-started,logPath=str(log),logSha256=sha(log.read_bytes()));persist()
 totals=[line for line in log.read_text().splitlines() if line.startswith(('[info] Passed: Total','[info] Failed: Total','[error] Failed: Total'))]
 counts=dict.fromkeys(('Total','Failed','Errors','Passed','Skipped','Ignored'),0)
 for line in totals:
  for key,value in re.findall(r'(Total|Failed|Errors|Passed|Skipped|Ignored) (\d+)',line):counts[key]+=int(value)
 witnesses={};xml_counts=dict.fromkeys(counts,0);problems=[]
 for task in tasks:
  project=task.split('/')[0];module=project.removesuffix('JVM').removesuffix('JS')
  backend='.jvm' if project.endswith('JVM') else '.js' if project.endswith('JS') else ''
  directory=consumer/module/backend;suites=[]
  for report in sorted((directory/'target/test-reports').glob('TEST-*.xml')):
   raw=report.read_bytes();relative=report.relative_to(consumer);target=out/'consumer-junit'/relative
   target.parent.mkdir(parents=True,exist_ok=True);assert not target.exists();target.write_bytes(raw)
   item=dict(path=str(relative),sha256=sha(raw),fresh=started<=report.stat().st_mtime<=started+receipt['elapsedSeconds']+2);suites.append(item)
   try:
    xml=ET.fromstring(raw);cases=xml.findall('testcase');item.update(suite=xml.attrib['name'],tests=len(cases))
    for case in cases:
     xml_counts['Total']+=1
     if case.find('failure') is not None:xml_counts['Failed']+=1
     elif case.find('error') is not None:xml_counts['Errors']+=1
     elif case.find('skipped') is not None:xml_counts['Skipped']+=1
     else:xml_counts['Passed']+=1
   except ET.ParseError as error:problems.append(str(relative)+': '+str(error))
  witnesses[task]=dict(suites=suites)
  if not suites:
   sources=[str(p.relative_to(consumer)) for d in [consumer/module/'src/test',directory/'src/test'] for p in d.rglob('*.scala')]
   witnesses[task]['testSourceFiles']=sorted(set(sources))
 receipt.update(testTotals=totals,aggregateTestCounts=counts,junitCounts=xml_counts,testTaskWitnesses=witnesses,xmlParseProblems=problems);persist()
 receipt['after']=bound()
 assert result.returncode==0,'raw consumer gate failed; available XML preserved'
 assert not problems and all(s['fresh'] for v in witnesses.values() for s in v['suites'])
 assert counts==xml_counts==dict(Total=245,Failed=0,Errors=0,Passed=245,Skipped=0,Ignored=0)
 assert {t for t,w in witnesses.items() if not w['suites']}=={'editionJVM/test','editionJS/test'}
 assert all(not w.get('testSourceFiles') for w in witnesses.values())
 app=consumer/'app/target/scala-3.7.4/storyatlas4s-app-fastopt/main.js'
 assert app.is_file() and started<=app.stat().st_mtime<=started+receipt['elapsedSeconds']+2
 receipt['productionAppLink']=dict(path=str(app),bytes=app.stat().st_size,sha256=sha(app.read_bytes()))
 receipt['sbtRuntimeFromLog']=next(line for line in log.read_text().splitlines() if 'welcome to sbt' in line)
 receipt['qualification']='Local exact-provider pin gate; consumer delta changes only build.sbt pin. No shell change or browser smoke; no remote publication/CI.'
 receipt['qualified']=True
except Exception as error:
 receipt['terminalError']=dict(kind=type(error).__name__,message=str(error));raise
finally:persist()
print(json.dumps(dict(qualified=receipt['qualified'],counts=receipt['aggregateTestCounts'])))
