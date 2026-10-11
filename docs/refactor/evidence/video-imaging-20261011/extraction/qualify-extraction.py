from pathlib import Path
import subprocess,json,re
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s');out=root/'.agent-work/video-imaging-20261011';clone=Path('/private/tmp/storymodel4s-s2b3-build')
sha='8014f86486680572346bc5a51acd8beb476a8089'
p='media/src/test/scala/storymodel4s/media/SampledExtractionSuite.scala'
assert (clone/p).read_bytes()==subprocess.check_output(['git','show',f'{sha}:{p}'],cwd=root)
subprocess.run(['git','restore','--source=HEAD','--',p],cwd=clone,check=True)
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
subprocess.run(['git','fetch','origin',sha],cwd=clone,check=True)
subprocess.run(['git','checkout','--detach',sha],cwd=clone,check=True)
source=clone/'media/src/main/scala/storymodel4s/media/sampledextraction.scala';original=source.read_text()
plan=[
('pts','ordinals == frames.selected.indices.toVector && pts == expected','ordinals == frames.selected.indices.toVector && expected == expected','wrong probe PTS refuses'),
('clock','clock == frames.index.timebase','clock == clock','equal tick numbers on a different probe clock'),
('geometry','shapes.forall(_ == frames.geometry)','shapes.isEmpty || shapes.nonEmpty','observed geometry refuses a same-area'),
('rotation','    "-noautorotate",\n','','display rotation cannot silently change'),
('copyts','    "-copyts",\n','','display rotation cannot silently change'),
('demuxer','    "-format_whitelist",\n    "mov,matroska,avi,mpegts,mpeg,ogg"','    "-nostats"','concat cannot substitute'),
('input','inputHash._1 == probe.input && inputHash._2 == probe.manifest.byteLength','inputHash._1 == inputHash._1','wrong input bytes refuse before attempting'),
('boundary','final class SampledExtraction private (','final class SampledExtraction (','SampledExtraction constructor')]
base=['sbt','-batch','-Dstorymodel4s.grakern.build=/private/tmp/storymodel4s-workflow-grakern-pin','set ThisBuild / tlFatalWarnings := true']
wrapper='/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py'
receipt={'producer':sha,'mutations':[],'gates':[]}
def save():(out/'extraction-qualification.json').write_text(json.dumps(receipt,indent=2)+'\n')
def run(name,commands):
 log=out/(name+'.log');p=subprocess.run(['python3',wrapper,'--log',str(log),'--cwd',str(clone),'--timeout','1800','--',*base,*commands],stdout=subprocess.DEVNULL)
 return p.returncode,log.read_text()
for name,old,new,witness in plan:
 assert original.count(old)==1,(name,original.count(old))
 try:
  source.write_text(original.replace(old,new))
  (out/('extraction-mutant-'+name+'.patch.txt')).write_text(subprocess.check_output(['git','diff','--',str(source.relative_to(clone))],cwd=clone,text=True))
  commands=['media/testOnly *SampledExtractionSuite *SampledExtractionBoundarySuite']
  if name=='boundary':commands=['media/Test/clean','set LocalProject("media") / Test / sources := (LocalProject("media") / Test / sources).value.filter(_.getName == "SampledExtractionBoundarySuite.scala")','media/testOnly *SampledExtractionBoundarySuite']
  code,log=run('extraction-mutant-'+name,commands)
  assert code!=0 and 'done compiling' in log and 'Compilation failed' not in log and any('==> X' in line and witness in line for line in log.splitlines()),name
  receipt['mutations'].append({'name':name,'compiled':True,'witness':witness,'totals':[l for l in log.splitlines() if 'Failed: Total' in l]});save();print('KILLED',name,flush=True)
 finally:source.write_text(original)
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
code,log=run('extraction-restored-scope',['media/test','scalafmtCheckAll','scalafmtSbtCheck'])
ts=[tuple(map(int,m)) for m in re.findall(r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)',log)]
receipt['gates'].append({'name':'media/test; scalafmtCheckAll; scalafmtSbtCheck','exit':code,'cells':len(ts),'totalFailedErrorsPassed':list(map(sum,zip(*ts))) if ts else []});save();print(json.dumps(receipt['gates'][-1]),flush=True);assert code==0
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
print('EXTRACTION_QUALIFIED',sha,flush=True)
