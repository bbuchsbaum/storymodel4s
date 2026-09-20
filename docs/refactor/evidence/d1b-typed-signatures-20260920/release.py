"""Exact clean provider release with immediate fresh JUnit preservation."""
from pathlib import Path
import importlib.util,json,re,subprocess,xml.etree.ElementTree as ET
spec=importlib.util.spec_from_file_location('qualification',Path(__file__).with_name('qualify.py'))
q=importlib.util.module_from_spec(spec);spec.loader.exec_module(q)
assert q.clean()
q.run('scope',['bash','tools/reference-scope.sh','7c91ae43',q.revision])
receipt=q.run('full-gate',q.prefix+['clean','compileAll','testAll'])
assert receipt['cleanBefore'] and receipt['cleanAfter']
build=(q.repo/'build.sbt').read_text()
def declared(name):return re.findall(r'"(\w+)"',re.search(r'val '+name+r' = List\((.*?)\)',build,re.S).group(1))
tasks=[m+p+'/test' for m in declared('allModules') for p in declared('allPlatforms')]+[m+'/test' for m in declared('jvmOnlyModules')]
assert len(tasks)==len(receipt['testTotals'])==56
receipt['taskTotalsInAliasOrder']=dict(zip(tasks,receipt['testTotals']))
receipt['sbtRuntimeFromLog']=next(line for line in Path(receipt['logPath']).read_text().splitlines() if 'welcome to sbt' in line)
reports=[];counts=dict(Total=0,Failed=0,Errors=0,Passed=0,Skipped=0,Ignored=0)
for report in sorted(q.repo.glob('**/target/test-reports/TEST-*.xml')):
 relative=report.relative_to(q.repo)
 assert receipt['startedEpochSeconds']<=report.stat().st_mtime<=receipt['startedEpochSeconds']+receipt['seconds']+2,('stale report',relative)
 raw=report.read_bytes();tree=ET.fromstring(raw);cases=tree.findall('testcase')
 for case in cases:
  counts['Total']+=1
  if case.find('failure') is not None:counts['Failed']+=1
  elif case.find('error') is not None:counts['Errors']+=1
  elif case.find('skipped') is not None:counts['Skipped']+=1
  else:counts['Passed']+=1
 target=q.out/'full-gate-junit'/relative;target.parent.mkdir(parents=True,exist_ok=True);assert not target.exists();target.write_bytes(raw)
 reports.append(dict(path=str(relative),sha256=q.sha(raw),tests=len(cases),timestamp=tree.attrib.get('timestamp'),
  skipped=[case.attrib['name'] for case in cases if case.find('skipped') is not None]))
assert counts==receipt['aggregateTestCounts'] and counts['Failed']==counts['Errors']==0
receipt['junitCounts']=counts;receipt['junitReports']=reports
frozen='pipeline/src/test/resources/golden/d1a-s0-text-parity.json'
assert q.sha((q.repo/frozen).read_bytes())=='cc201d9dd3e3576fabcd45677369f00c023ca5a455ea9d9e7ba694700c759fb3'
receipt['frozenS0Sha256']=q.sha((q.repo/frozen).read_bytes())
(q.out/'full-gate.json').write_text(json.dumps(receipt,indent=2)+'\n')
for label,command in [('format-last',q.prefix+['scalafmtCheckAll','scalafmtSbtCheck']),
 ('docs-examples',['env','STORYMODEL4S_GRAKERN_BUILD='+q.pin,'npm','--prefix','docs-site','run','verify:examples'])]:
 r=q.run(label,command);assert r['cleanBefore'] and r['cleanAfter']
