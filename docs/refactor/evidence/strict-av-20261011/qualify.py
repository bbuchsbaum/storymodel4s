from pathlib import Path
import subprocess,json,re,sys
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s'); out=root/'.agent-work/strict-av-20261011'; clone=Path('/private/tmp/storymodel4s-s2b3-build')
sha='29997f88d159de8b4dc2620a4ce23c071e9b51ca'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=clone,text=True).strip()==sha
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
assert not [x for x in subprocess.check_output(['ps','-axo','pid,comm'],text=True).splitlines() if x.strip().endswith('/java')]
codec='codec/src/main/scala/storymodel4s/codec/boundcaptionsource.scala'; scoring='align/src/main/scala/storymodel4s/align/captionscoring.scala'; mapping='pipeline/src/main/scala/storymodel4s/pipeline/CaptionMapping.scala'
plan=[
('pin',codec,'Checksum.ofText(correspondence) == expected','Checksum.ofText(correspondence).hex.length == expected.hex.length','wrong pins or axes'),
('axes',codec,'a == source.primaryAxis.id.value && b == target.primaryAxis.id.value','a.nonEmpty && b.nonEmpty','wrong pins or axes'),
('domain',codec,'value >= low && value <= high','value >= low || value <= high','fractional projection and out-of-domain'),
('fractional',codec,'n % d == 0 && (n / d).isValidLong','(n / d).isValidLong','fractional projection and out-of-domain'),
('exact-target',codec,'}) == 1','}) >= 0','shifted exact endpoints'),
('provenance',scoring,'Some(correspondence)','Some(Checksum.ofText(correspondence.hex.take(0)))','changed correspondence basis'),
('package',mapping,'actual.artifact == joined.media.artifact','actual.artifact.nonEmpty && joined.media.artifact.nonEmpty','a genuine different package'),
('portable-boundary',codec,'final class Imported private[BoundCaptionSourceCodec] (','final class Imported (','constructor'),
('registration-boundary',mapping,'final class PackageRegistered private[CaptionMapping] (','final class PackageRegistered (','constructor')]
base=['sbt','-batch','-Dstorymodel4s.grakern.build=/private/tmp/storymodel4s-workflow-grakern-pin','set ThisBuild / tlFatalWarnings := true']
wrapper='/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py'
receipt={'producer':sha,'mutations':[],'gates':[]}
def save():(out/'qualification.json').write_text(json.dumps(receipt,indent=2)+'\n')
def run(name,commands,cwd=clone,extra=[]):
 log=out/(name+'.log')
 p=subprocess.run(['python3',wrapper,'--log',str(log),'--cwd',str(cwd),'--timeout','3600','--',*base,*extra,*commands],stdout=subprocess.DEVNULL)
 return p.returncode,log.read_text()
for name,path,old,new,witness in plan:
 source=clone/path;original=source.read_text();assert original.count(old)==1,(name,original.count(old))
 try:
  source.write_text(original.replace(old,new));(out/('mutant-'+name+'.patch.txt')).write_text(subprocess.check_output(['git','diff','--',path],cwd=clone,text=True))
  commands=['pipeline/testOnly *StrictCaptionJoinSuite']
  if name=='portable-boundary':
   commands=['codecJVM/Test/clean','set LocalProject("codecJVM") / Test / sources := (LocalProject("codecJVM") / Test / sources).value.filter(_.getName == "BoundCaptionSourceBoundarySuite.scala")','codecJVM/testOnly *BoundCaptionSourceBoundarySuite']
  if name=='registration-boundary':
   commands=['pipeline/Test/clean','set LocalProject("pipeline") / Test / sources := (LocalProject("pipeline") / Test / sources).value.filter(_.getName == "PackageCaptionBoundarySuite.scala")','pipeline/testOnly *PackageCaptionBoundarySuite']
  code,log=run('mutant-'+name,commands)
  totals=[tuple(map(int,m)) for m in re.findall(r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)',log)]
  assert code!=0 and 'done compiling' in log and 'Compilation failed' not in log and any('==> X' in line and witness in line for line in log.splitlines()),(name,code)
  assert totals and sum(t[3] for t in totals)>0 and sum(t[2] for t in totals)==0,(name,totals)
  receipt['mutations'].append({'name':name,'compiled':True,'witness':witness,'totals':totals});save();print('KILLED',name,flush=True)
 finally:source.write_text(original)
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
for name,commands,cwd,extra in [
('restored-checkAll',['alignJVM/Test/clean','codecJVM/Test/clean','pipeline/Test/clean','set LocalProject("pipeline") / Test / javaOptions += "-Dstorymodel4s.strictAV.fixture='+str(out/'synthetic-workspace.json')+'"','checkAll'],clone,[]),
('storyatlas',['compileAll','testAll','scalafmtCheckAll','scalafmtSbtCheck'],Path('/private/tmp/storyatlas4s-study-workflow'),['-Dstoryatlas4s.storymodel4s.build='+str(clone),'-Dstoryatlas.surfaceWorkspace.fixture='+str(out/'synthetic-workspace.json')])]:
 code,log=run(name,commands,cwd,extra)
 ts=[tuple(map(int,m)) for m in re.findall(r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)',log)]
 receipt['gates'].append({'name':name,'exit':code,'cells':len(ts),'totalFailedErrorsPassed':list(map(sum,zip(*ts))) if ts else []});save();print(json.dumps(receipt['gates'][-1]),flush=True);assert code==0
 assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
print('QUALIFIED',sha,flush=True)
