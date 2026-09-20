"""Exact clean provider release; preserve raw/red evidence before qualifying it."""
from pathlib import Path
import argparse,importlib.util,json,re,subprocess,time,xml.etree.ElementTree as ET
spec=importlib.util.spec_from_file_location('qualification',Path(__file__).with_name('qualify.py'))
q=importlib.util.module_from_spec(spec);spec.loader.exec_module(q)
args=argparse.ArgumentParser();args.add_argument('--candidate',required=True);expected=args.parse_args().candidate
assert re.fullmatch('[0-9a-f]{40}',expected),'explicit full candidate SHA required'
assert Path(q.pin).is_absolute(),'D1B_GRAKERN must be absolute so checks and sbt/docs resolve the same dependency'
GR='0329c43c88a0b71e9aa4456723bb16bac2fa3841'
terminal=q.out/'release-terminal.json';assert not terminal.exists(),terminal
record=dict(schema='d1b/release-terminal/v1',expectedCandidate=expected,expectedGrakern=GR,
 runnerSha256=q.sha(Path(__file__).read_bytes()),startedEpochSeconds=time.time(),qualified=False,stages={})
def persist():terminal.write_text(json.dumps(record,indent=2)+'\n')
def state(path):
 path=Path(path).resolve()
 return dict(path=str(path),standalone=(path/'.git').is_dir(),
  revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=path,text=True).strip(),
  clean=not subprocess.check_output(['git','status','--porcelain'],cwd=path))
def bound():
 actual=dict(provider=state(q.repo),grakern=state(q.pin))
 assert actual['provider']['standalone'] and actual['grakern']['standalone'],'standalone clones required'
 assert actual['provider']['revision']==expected and actual['grakern']['revision']==GR,'immutable revision mismatch'
 assert actual['provider']['clean'] and actual['grakern']['clean'],'dirty candidate/dependency'
 return actual
def stage(label,command,validate):
 entry=dict(qualified=False,command=command);record['stages'][label]=entry;persist()
 try:
  entry['before']=bound();persist()
  raw=q.run(label,command,False);entry['rawCommandReceipt']=raw
  entry['rawReceiptSha256']=q.sha((q.out/(label+'.json')).read_bytes());persist()
  # Validation snapshots available evidence even when the command itself is red.
  validate(raw,entry)
  entry['after']=bound()
  assert raw['exitCode']==0 and raw['cleanBefore'] and raw['cleanAfter'],'raw gate failed or changed tree'
  entry['qualified']=True
 except Exception as error:
  entry['postValidationError']=dict(kind=type(error).__name__,message=str(error))
  raise
 finally:persist()
def full(raw,entry):
 reports=[];counts=dict(Total=0,Failed=0,Errors=0,Passed=0,Skipped=0,Ignored=0);problems=[]
 for report in sorted(q.repo.glob('**/target/test-reports/TEST-*.xml')):
  relative=report.relative_to(q.repo);data=report.read_bytes()
  target=q.out/'full-gate-junit'/relative;target.parent.mkdir(parents=True,exist_ok=True)
  assert not target.exists();target.write_bytes(data)
  row=dict(path=str(relative),sha256=q.sha(data),fresh=raw['startedEpochSeconds']<=report.stat().st_mtime<=raw['startedEpochSeconds']+raw['seconds']+2)
  reports.append(row)
  try:
   tree=ET.fromstring(data);cases=tree.findall('testcase');row['tests']=len(cases);row['timestamp']=tree.attrib.get('timestamp')
   row['skipped']=[c.attrib['name'] for c in cases if c.find('skipped') is not None]
   for case in cases:
    counts['Total']+=1
    if case.find('failure') is not None:counts['Failed']+=1
    elif case.find('error') is not None:counts['Errors']+=1
    elif case.find('skipped') is not None:counts['Skipped']+=1
    else:counts['Passed']+=1
  except ET.ParseError as error:problems.append(str(relative)+': '+str(error))
 entry['junitReports']=reports;entry['junitCounts']=counts;entry['xmlParseProblems']=problems;persist()
 build=(q.repo/'build.sbt').read_text()
 def declared(name):return re.findall(r'"(\w+)"',re.search(r'val '+name+r' = List\((.*?)\)',build,re.S).group(1))
 tasks=[m+p+'/test' for m in declared('allModules') for p in declared('allPlatforms')]+[m+'/test' for m in declared('jvmOnlyModules')]
 entry['expectedTasks']=tasks;entry['reportedTaskTotals']=raw['testTotals'];persist()
 assert raw['exitCode']==0,'full gate command failed; available XML preserved'
 assert len(tasks)==len(raw['testTotals'])==56,'test alias population mismatch'
 entry['taskTotalsInAliasOrder']=dict(zip(tasks,raw['testTotals']))
 assert reports and not problems and all(r['fresh'] for r in reports),'missing/stale/malformed XML'
 assert counts==raw['aggregateTestCounts'] and counts['Failed']==counts['Errors']==0,'JUnit/log outcomes differ or failed'
 log=Path(raw['logPath']).read_text();runtime=[line for line in log.splitlines() if 'welcome to sbt' in line]
 assert len(runtime)==1;entry['sbtRuntimeFromLog']=runtime[0]
 frozen='pipeline/src/test/resources/golden/d1a-s0-text-parity.json'
 entry['frozenS0Sha256']=q.sha((q.repo/frozen).read_bytes())
 assert entry['frozenS0Sha256']=='cc201d9dd3e3576fabcd45677369f00c023ca5a455ea9d9e7ba694700c759fb3'
def no_extra_validation(raw,entry):pass
try:
 record['before']=bound();assert q.revision==expected;persist()
 stage('scope',['bash','tools/reference-scope.sh','7c91ae43',expected],no_extra_validation)
 stage('full-gate',q.prefix+['clean','compileAll','testAll'],full)
 stage('format-last',q.prefix+['scalafmtCheckAll','scalafmtSbtCheck'],no_extra_validation)
 stage('docs-examples',['env','STORYMODEL4S_GRAKERN_BUILD='+q.pin,'npm','--prefix','docs-site','run','verify:examples'],no_extra_validation)
 record['after']=bound();record['qualified']=True
except Exception as error:
 record['terminalError']=dict(kind=type(error).__name__,message=str(error));raise
finally:
 record['finishedEpochSeconds']=time.time();persist()
