"""Serial exact-candidate witnesses, source gate, independent reader and pinned consumer."""
import json, subprocess, sys
from pathlib import Path
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s')
clone=Path('/private/tmp/storymodel4s-s2b3-build')
consumer=Path('/private/tmp/storyatlas4s-reference-consumer')
sha='efba130cfc867c566ea83ca19ebded0d4914c240'
work=root/'.agent-work/workflow-five'
wrapper='/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py'
def clean(path, revision):
 assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=path,text=True).strip()==revision
 assert not subprocess.check_output(['git','status','--porcelain'],cwd=path,text=True).strip()
def logged(name, cwd, command, timeout=7200):
 subprocess.run([sys.executable,wrapper,'--log',str(work/(name+'.log')),'--cwd',str(cwd),'--timeout',str(timeout),'--',*command],check=True,stdout=subprocess.DEVNULL)
 print(name+' passed',flush=True)
clean(clone,sha)
clean(consumer,'90f682d1acddd759867098e90f6e3ef163244b0e')
clean(Path('/Users/bbuchsbaum/code/scala/grakern'),'8efc5efade4e56c6e0b29745bd1b108d4c9a9d57')
subprocess.run([sys.executable,str(root/'docs/refactor/evidence/workflow-five-20261010/mutation-driver.py'),'--clone',str(clone),'--out',str(work/'exchange-final-mutations'),'--sha',sha],check=True)
clean(clone,sha)
scope=subprocess.run(['bash','tools/reference-scope.sh','07855d4dccf593b53432fe0f5c60b94fcfd1a542',sha],cwd=clone,capture_output=True,text=True)
(work/'exchange-final-scope.txt').write_text(scope.stdout+scope.stderr)
assert scope.returncode==0
logged('exchange-final-checkAll',clone,['sbt','-batch','-Dstorymodel4s.grakern.build=/Users/bbuchsbaum/code/scala/grakern','set ThisBuild / tlFatalWarnings := true','checkAll'])
clean(clone,sha)
logged('exchange-final-reader',clone,[sys.executable,'examples/mapping/test_paired_mapping.py',str(work/'exchange-final-checkAll.log')],120)
logged('exchange-final-consumer',consumer,['sbt','-batch','-Dstoryatlas4s.storymodel4s.build='+str(clone),'-Dstorymodel4s.grakern.build=/Users/bbuchsbaum/code/scala/grakern','set ThisBuild / tlFatalWarnings := true','compileAll','testAll','scalafmtCheckAll','scalafmtSbtCheck'],3600)
clean(clone,sha)
clean(consumer,'90f682d1acddd759867098e90f6e3ef163244b0e')
(work/'exchange-gate-complete.json').write_text(json.dumps({'sha':sha,'source':'checkAll','reader':True,'consumer':'90f682d1acddd759867098e90f6e3ef163244b0e'})+'\n')
