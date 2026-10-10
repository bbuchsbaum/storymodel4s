"""Wait for owned development compilation, preserve it, then qualify the exact lambda candidate."""
import json,subprocess,sys,time,shutil
from pathlib import Path
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s');work=root/'.agent-work/workflow-five';clone=Path('/private/tmp/storymodel4s-s2b3-build');consumer=Path('/private/tmp/storyatlas4s-reference-consumer');sha=sys.argv[1]
wrapper='/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py'
sbt=['sbt','-batch','-Dstorymodel4s.grakern.build=/Users/bbuchsbaum/code/scala/grakern','set ThisBuild / tlFatalWarnings := true']
def logged(name,cwd,command,expected=0):
 r=subprocess.run([sys.executable,wrapper,'--log',str(work/(name+'.log')),'--cwd',str(cwd),'--timeout','7200','--',*command],stdout=subprocess.DEVNULL)
 assert r.returncode==expected,(name,r.returncode)
 print(name+' exit '+str(r.returncode),flush=True)
def clean():
 assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=clone,text=True).strip()==sha
 assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
deadline=time.monotonic()+10800
while True:
 try:m=json.loads((work/'workspace-compile-01.log.meta.json').read_text())
 except (FileNotFoundError,json.JSONDecodeError):m={}
 if m.get('exit_code') is not None:break
 if time.monotonic()>deadline:raise TimeoutError('Workspace development compilation still active')
 time.sleep(10)
# Preserve only this controller's known snapshot paths before restoring its owned checkout.
paths=json.loads((work/'workspace-snapshot-01/manifest.json').read_text())
for rel in paths:
 target=work/'workspace-compiled-01'/rel;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(clone/rel,target)
 tracked=subprocess.run(['git','ls-files','--error-unmatch',rel],cwd=clone,capture_output=True).returncode==0
 if tracked:subprocess.run(['git','restore','--',rel],cwd=clone,check=True)
 else:(clone/rel).unlink()
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
subprocess.run(['git','checkout','--detach',sha],cwd=clone,check=True)
clean()
with (work/'lambda-reference-scope.txt').open('w') as f:subprocess.run(['bash','tools/reference-scope.sh','08963013',sha],cwd=clone,stdout=f,check=True)
subprocess.run([sys.executable,str(root/'docs/refactor/evidence/workflow-five-20261010/mutation-driver.py'),'--clone',str(clone),'--out',str(work/'lambda-mutations'),'--sha',sha,'--spec',str(root/'docs/refactor/evidence/workflow-five-20261010/lambda-mutation-spec.json')],check=True)
clean()
logged('lambda-final-checkAll',clone,sbt+['checkAll'])
clean()
logged('lambda-final-reader',clone,[sys.executable,'examples/mapping/test_weighted_mapping.py',str(work/'lambda-final-checkAll.log')])
logged('lambda-final-consumer',consumer,['sbt','-batch','-Dstoryatlas4s.storymodel4s.build='+str(clone),'-Dstorymodel4s.grakern.build=/Users/bbuchsbaum/code/scala/grakern','set ThisBuild / tlFatalWarnings := true','compileAll','testAll','scalafmtCheckAll','scalafmtSbtCheck'])
clean()
(work/'lambda-gate-complete.json').write_text(json.dumps({'sha':sha,'consumer':'90f682d1acddd759867098e90f6e3ef163244b0e','source':'checkAll','reader':True})+'\n')
