"""Run the final full gate only after all named mutants are killed and source is restored."""
import json,subprocess,sys,time
from pathlib import Path
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s');clone=Path('/private/tmp/storymodel4s-s2b3-build');work=root/'.agent-work/five-beads';sha='fa80dfa388d6470dfde51322f8e1035b6d8521a4'
deadline=time.monotonic()+3600
while time.monotonic()<deadline:
 p=work/'integration-mutations/mutations.json'
 try: rows=json.loads(p.read_text())
 except (FileNotFoundError,json.JSONDecodeError): rows=[]
 if any(not r['qualified'] for r in rows): raise RuntimeError('Mutation inconclusive; final gate not submitted')
 if len(rows)==15 and not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip():break
 time.sleep(10)
else:raise TimeoutError('Mutations did not complete')
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=clone,text=True).strip()==sha
scope=subprocess.run(['bash','tools/reference-scope.sh','908b935ca55fb6a4bc0f1e10b16a5b42c93adff4',sha],cwd=clone,capture_output=True,text=True)
(work/'final-scope.txt').write_text(scope.stdout+scope.stderr);assert scope.returncode==0
command=['sbt','-batch','-Dstorymodel4s.grakern.build=/Users/bbuchsbaum/code/scala/grakern','set ThisBuild / tlFatalWarnings := true','coreJVM/Test/clean','alignJVM/Test/clean','pipeline/Test/clean','checkAll','githubWorkflowCheck']
print('15 compiled mutation witnesses green; exact restored full checkAll starting',flush=True)
sys.exit(subprocess.run([sys.executable,'/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log',str(work/'final-checkAll.log'),'--cwd',str(clone),'--timeout','7200','--',*command]).returncode)
