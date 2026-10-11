import subprocess,json
from pathlib import Path
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s');clone=Path('/private/tmp/storymodel4s-caption-nextfive')
out=root/'.agent-work/next-five-20261010'
sha=subprocess.check_output(['git','rev-parse','codex/caption-nextfive-qualified'],cwd=root,text=True).strip()
if not clone.exists(): subprocess.run(['git','clone','--shared','--no-checkout',str(root),str(clone)],check=True)
subprocess.run(['git','checkout','--detach',sha],cwd=clone,check=True)
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
wrapper='/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py'
sbt=['sbt','-batch','-Dstorymodel4s.grakern.build=/private/tmp/storymodel4s-workflow-grakern-pin','set ThisBuild / tlFatalWarnings := true']
def run(name,args):
 p=subprocess.run(['python3',wrapper,'--log',str(out/(name+'.log')),'--cwd',str(clone),'--timeout','2400','--',*args],stdout=subprocess.DEVNULL)
 print(name,p.returncode,flush=True);return p.returncode,(out/(name+'.log')).read_text()
rc,_=run('caption-worker-schema-formatted',['python3','media/worker-vlm/test_caption_request_schema.py']);assert rc==0
rc,_=run('caption-scope-formatted',['bash','tools/reference-scope.sh','9b7e830c',sha]);assert rc==0
rc,_=run('caption-gate-formatted',sbt+['media/test','pipeline/test','scalafmtCheckAll','scalafmtSbtCheck']);assert rc==0
source=clone/'media/src/main/scala/storymodel4s/media/caption.scala';original=source.read_text()
mutants=[('selection','request.frameIdentity.exists(_ != frames.identity)','request.frameIdentity.exists(_ => false)','a v2 request binds the selected frame identity'),('legacy','request.frameIdentity.isEmpty && frames.isInstanceOf[SampledFrameSet]','false && request.frameIdentity.isEmpty && frames.isInstanceOf[SampledFrameSet]','an unbound v1 request refuses sampled frames'),('extent','case Seq(a, b) => b <= a','case Seq(a, b) => b < a','malformed extents refuse at their checked construction boundary')]
results=[]
for name,old,new,witness in mutants:
 assert original.count(old)==1,name
 try:
  source.write_text(original.replace(old,new))
  (out/('caption-mutant-'+name+'.patch.txt')).write_text(subprocess.check_output(['git','diff','--',str(source.relative_to(clone))],cwd=clone,text=True))
  rc,log=run('caption-mutant-'+name,sbt+['media/testOnly *SampledCaptionConsumerSuite'])
  assert rc!=0 and 'done compiling' in log and '[error] --' not in log and any('==> X' in l and witness in l for l in log.splitlines()),name
  results.append({'mutant':name,'compiled':True,'namedFailure':witness,'exit':rc,'totals':[l for l in log.splitlines() if 'Failed: Total' in l]})
 finally:source.write_text(original)
rc,_=run('caption-restored',sbt+['media/testOnly *SampledCaptionConsumerSuite *CaptionExtentBoundarySuite']);assert rc==0
(out/'caption-mutations.json').write_text(json.dumps(results,indent=2)+'\n')
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
