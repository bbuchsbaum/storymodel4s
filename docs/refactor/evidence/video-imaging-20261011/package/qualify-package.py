from pathlib import Path
import subprocess,json,re
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s');out=root/'.agent-work/video-imaging-20261011';clone=Path('/private/tmp/storymodel4s-s2b3-build')
sha='62f8eef340b5a41325dfa3ed2afe9e0b55412e17';source=clone/'media/src/main/scala/storymodel4s/media/captionpackage.scala'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=clone,text=True).strip()==sha
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
base=['sbt','-batch','-Dstorymodel4s.grakern.build=/private/tmp/storymodel4s-workflow-grakern-pin','set ThisBuild / tlFatalWarnings := true']
wrapper='/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py'
plan=[
('manifest','Checksum.ofBytes(owned) == expectedChecksum','true','caller pin binds metadata'),
('frame-bytes','checksum == expected','checksum == expected || expected == expected','changed raw frame bytes'),
('metadata-bytes','Checksum.ofBytes(value) == expected','Checksum.ofBytes(value) == expected || expected == expected','changed request metadata'),
('membership','part.request.extents.map(e => e.id -> e.ordinals) == expected','true','cross-part extent membership'),
('dedup','expected.flatMap(_._2).distinct.sorted','expected.flatMap(_._2).sorted','overlapping extents retain both proposals'),
('utf8','value.getBytes(StandardCharsets.UTF_8).sameElements(bytes)','true','malformed outcome UTF-8'),
('frame-input','frameEnvelope.fixtureId == manifest.fixtureId && frameEnvelope.inputSha256 == manifest.sha256','true','repinned frame and probe input identities'),
('probe-input','probeEnvelope.fixtureId == manifest.fixtureId && probeEnvelope.inputSha256 == manifest.sha256','true','repinned frame and probe input identities'),
('package-boundary','final class CaptionPackage private (','final class CaptionPackage (','CaptionPackage constructor'),
('part-boundary','final class Part private[CaptionPackage] (','final class Part (','CaptionPackage.Part constructor'),
('location-boundary','final class Location private[CaptionPackage] (','final class Location (','CaptionPackage.Location constructor')]
receipt=json.loads((out/'package-qualification.json').read_text())
def save():(out/'package-qualification.json').write_text(json.dumps(receipt,indent=2)+'\n')
def run(name,commands):
 log=out/(name+'-03.log');p=subprocess.run(['python3',wrapper,'--log',str(log),'--cwd',str(clone),'--timeout','3600','--',*base,*commands],stdout=subprocess.DEVNULL)
 return p.returncode,log.read_text()
original=source.read_text()
for name,old,new,witness in plan:
 if any(row['name']==name for row in receipt['mutations']):continue
 assert original.count(old)==1,(name,original.count(old))
 try:
  source.write_text(original.replace(old,new))
  patch=subprocess.check_output(['git','diff','--','media/src/main/scala/storymodel4s/media/captionpackage.scala'],cwd=clone,text=True)
  (out/('package-mutant-'+name+'.patch.txt')).write_text(patch)
  commands=['media/testOnly *CaptionPackageSuite *CaptionPackageBoundarySuite']
  if name.endswith('boundary'):
   commands=['media/Test/clean','set LocalProject("media") / Test / sources := (LocalProject("media") / Test / sources).value.filter(_.getName == "CaptionPackageBoundarySuite.scala")','media/testOnly *CaptionPackageBoundarySuite']
  code,log=run('package-mutant-'+name,commands)
  assert code!=0 and 'done compiling' in log and '[error] --' not in log and any('==> X' in line and witness in line for line in log.splitlines()),name
  row={'name':name,'compiled':True,'witness':witness,'totals':[line for line in log.splitlines() if 'Failed: Total' in line]}
  receipt['mutations'].append(row);save();print('KILLED',name,flush=True)
 finally:source.write_text(original)
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
scope=subprocess.run(['bash','tools/reference-scope.sh','a2a75021a4570a3c23e65a41d9476f456eacaabd',sha],cwd=clone,capture_output=True,text=True,check=True)
(out/'package-reference-scope.txt').write_text(scope.stdout)
code,log=run('package-final-gate',['checkAll'])
ts=[tuple(map(int,m)) for m in re.findall(r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)',log)]
receipt['gates'].append({'name':'checkAll','exit':code,'cells':len(ts),'totalFailedErrorsPassed':list(map(sum,zip(*ts))) if ts else []});save();print(json.dumps(receipt['gates'][-1]),flush=True);assert code==0
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
print('PACKAGE_QUALIFIED',sha,flush=True)
