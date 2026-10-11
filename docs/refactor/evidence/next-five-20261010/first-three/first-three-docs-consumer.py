import subprocess, json, os
from pathlib import Path
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s')
clone=Path('/private/tmp/storymodel4s-s2b3-build')
consumer=Path('/private/tmp/storyatlas4s-study-workflow')
out=root/'.agent-work/next-five-20261010'
wrapper='/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py'
sha=subprocess.check_output(['git','rev-parse','9b7e830c'],cwd=root,text=True).strip()
consumer_sha='ac2ca6cb85fdb8a959390e0e3d16e7af87c3e16d'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=clone,text=True).strip()==sha
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=consumer,text=True).strip()==consumer_sha
subprocess.run(['git','diff','--exit-code','HEAD'],cwd=consumer,check=True)
env=os.environ.copy();env['STORYMODEL4S_GRAKERN_BUILD']='/private/tmp/storymodel4s-workflow-grakern-pin'
def run(name,cwd,args,timeout=1800):
 p=subprocess.run(['python3',wrapper,'--log',str(out/(name+'.log')),'--cwd',str(cwd),'--timeout',str(timeout),'--',*args],env=env,stdout=subprocess.DEVNULL)
 print(name,p.returncode,flush=True)
 assert p.returncode==0,name
run('first-three-npm-ci',clone/'docs-site',['npm','ci'])
for name in ['verify:examples','check','build','verify:navigation','verify:verbatim','verify:figures','verify:provenance']:
 run('first-three-docs-'+name.replace(':','-'),clone/'docs-site',['npm','run',name])
sbt=['sbt','-batch','-Dstorymodel4s.grakern.build=/private/tmp/storymodel4s-workflow-grakern-pin','set ThisBuild / tlFatalWarnings := true']
witness=out/'first-three-witness'
run('first-three-witness',clone,sbt+['pipeline/Test/runMain storymodel4s.pipeline.StudyWorkspaceWitness '+str(witness)+' '+sha])
run('first-three-witness-reader',clone,['python3','examples/mapping/test_workspace.py',str(witness)])
fixtures=json.loads((witness/'workspace-fixtures.json').read_text());assert fixtures['producerRevision']==sha
csbt=['sbt','-batch','-Dstoryatlas4s.storymodel4s.build='+str(clone),'-Dstorymodel4s.grakern.build=/private/tmp/storymodel4s-workflow-grakern-pin','set ThisBuild / tlFatalWarnings := true']
run('first-three-consumer',consumer,csbt+['-Dstoryatlas.surfaceWorkspace.fixture='+fixtures['text'],'compileAll','testAll','scalafmtCheckAll','scalafmtSbtCheck'],3600)
run('first-three-consumer-timed',consumer,csbt+['-Dstoryatlas.surfaceWorkspace.fixture='+fixtures['timed'],'shellJVM/testOnly storyatlas4s.shell.SurfaceWorkspaceControllerSuite'])
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
subprocess.run(['git','diff','--exit-code','HEAD'],cwd=consumer,check=True)
(out/'first-three-qualification.json').write_text(json.dumps({'producer':sha,'consumer':consumer_sha,'consumerSource':'explicit local source override; consumer pin not changed','grakern':'0329c43c88a0b71e9aa4456723bb16bac2fa3841','docsExamples':14,'browser':'not run; prose/code examples only'},indent=2)+'\n')
