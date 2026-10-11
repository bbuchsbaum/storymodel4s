from pathlib import Path
import subprocess,json,re
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s');out=root/'.agent-work/video-imaging-20261011';clone=Path('/private/tmp/storymodel4s-s2b3-build')
sha='13bdc9aad68bba8493577f79f212fa07f3b57f44'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=clone,text=True).strip()==sha
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
codec='codec/src/main/scala/storymodel4s/codec/mediacaptionsource.scala';media='media/src/main/scala/storymodel4s/media/videoacquisition.scala'
originals={p:(clone/p).read_text() for p in [codec,media]}
plan=[
('order',codec,'.sortBy(c => (c._2.captions.head.support.start, c._1))','','caption request order cannot reverse'),
('first-endpoint',codec,'(s.ordinal != 0 || s.pts == start)','(s.ordinal >= 0)','sampled endpoint ordinals must agree'),
('last-endpoint',codec,'(s.ordinal != count - 1 || s.duration.exists(d => BigInt(s.pts) + d == BigInt(end)))','(s.ordinal < count)','sampled endpoint ordinals must agree'),
('caption-hull',codec,'BigInt(until) == BigInt(last.pts) + duration','BigInt(until) == BigInt(until) && duration > 0L','changing a caption hull'),
('coverage',codec,'.distinct.sorted.map(samples(_).ordinal)','.distinct.sorted','native film clocks, Draft provenance'),
('boundary',codec,'final class Imported private[MediaCaptionSourceCodec] (','final class Imported (','Imported media source constructor'),
('model',media,'observed == expected','observed == observed && expected == expected','pinned model bytes and decode budget'),
('budget',media,'BigInt(geometry.frameBytes) * index.entries.size <= BigInt(budget)','BigInt(budget) > 0','pinned model bytes and decode budget')]
base=['sbt','-batch','-Dstorymodel4s.grakern.build=/private/tmp/storymodel4s-workflow-grakern-pin','set ThisBuild / tlFatalWarnings := true']
wrapper='/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py'
receipt={'producer':sha,'mutations':[],'gates':[]}
def save():(out/'video-qualification.json').write_text(json.dumps(receipt,indent=2)+'\n')
def run(name,commands,cwd=clone,extra=[]):
 log=out/(name+'.log')
 if name.startswith('video-mutant-') and log.exists():
  return json.loads(Path(str(log)+'.meta.json').read_text())['exit_code'],log.read_text()
 p=subprocess.run(['python3',wrapper,'--log',str(log),'--cwd',str(cwd),'--timeout','3600','--',*base,*extra,*commands],stdout=subprocess.DEVNULL)
 return p.returncode,log.read_text()
for name,path,old,new,witness in plan:
 source=clone/path;original=originals[path]
 assert original.count(old)==1,(name,original.count(old))
 try:
  source.write_text(original.replace(old,new))
  (out/('video-mutant-'+name+'.patch.txt')).write_text(subprocess.check_output(['git','diff','--',path],cwd=clone,text=True))
  commands=['media/testOnly *VideoAcquisitionSuite'] if path==media else ['codecJVM/testOnly *MediaCaptionSourceSuite *MediaCaptionSourceBoundarySuite']
  if name=='boundary':commands=['codecJVM/Test/clean','set LocalProject("codecJVM") / Test / sources := (LocalProject("codecJVM") / Test / sources).value.filter(_.getName == "MediaCaptionSourceBoundarySuite.scala")','codecJVM/testOnly *MediaCaptionSourceBoundarySuite']
  code,log=run('video-mutant-'+name,commands)
  assert code!=0 and 'done compiling' in log and 'Compilation failed' not in log and any('==> X' in line and witness in line for line in log.splitlines()),(name,code)
  receipt['mutations'].append({'name':name,'compiled':True,'witness':witness,'totals':[l for l in log.splitlines() if 'Failed: Total' in l]});save();print('KILLED',name,flush=True)
 finally:source.write_text(original)
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
for name,commands,cwd,extra in [
('video-restored-checkAll',['checkAll'],clone,[]),
('video-workspace-export',['set LocalProject("pipeline") / Test / javaOptions += "-Dstorymodel4s.videoWorkspace.fixture='+str(out/'video-workspace.json')+'"','pipeline/testOnly *VideoSourceSuite'],clone,[]),
('video-storyatlas',['compileAll','testAll','scalafmtCheckAll','scalafmtSbtCheck'],Path('/private/tmp/storyatlas4s-study-workflow'),['-Dstoryatlas4s.storymodel4s.build='+str(clone),'-Dstoryatlas.surfaceWorkspace.fixture='+str(out/'video-workspace.json')])]:
 code,log=run(name,commands,cwd,extra)
 ts=[tuple(map(int,m)) for m in re.findall(r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)',log)]
 receipt['gates'].append({'name':name,'exit':code,'cells':len(ts),'totalFailedErrorsPassed':list(map(sum,zip(*ts))) if ts else []});save();print(json.dumps(receipt['gates'][-1]),flush=True);assert code==0
 assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
print('VIDEO_QUALIFIED',sha,flush=True)
