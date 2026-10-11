import subprocess, json
from pathlib import Path
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s')
clone=Path('/private/tmp/storymodel4s-s2b3-build')
out=root/'.agent-work/next-five-20261010'
wrapper='/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py'
sbt=['sbt','-batch','-Dstorymodel4s.grakern.build=/private/tmp/storymodel4s-workflow-grakern-pin','set ThisBuild / tlFatalWarnings := true']
assert subprocess.check_output(['git','rev-parse','--short=8','HEAD'],cwd=clone,text=True).strip()=='9b7e830c'
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
def run(name,commands):
 p=subprocess.run(['python3',wrapper,'--log',str(out/(name+'.log')),'--cwd',str(clone),'--timeout','1200','--',*sbt,*commands],stdout=subprocess.DEVNULL)
 print(name,p.returncode,flush=True)
 return p.returncode,(out/(name+'.log')).read_text()
rel=Path('pipeline/src/test/scala/storymodel4s/pipeline/TemporalProjectionConsumerContractSuite.scala')
assert not (clone/rel).exists()
(clone/rel).write_bytes((root/rel).read_bytes())
try:
 rc,log=run('temporal-consumer-red',['pipeline/testOnly *TemporalProjectionConsumerContractSuite'])
 assert rc!=0 and 'Failed: Total 2, Failed 1, Errors 0, Passed 1' in log and '[error] --' not in log
 assert '==> X' in log and 'a caller outside align can compile' in log
finally:(clone/rel).unlink()
print('RED_SEAM_CONFIRMED',flush=True)
rel=Path('pipeline/src/main/scala/storymodel4s/pipeline/MappingPipeline.scala')
p=clone/rel;original=p.read_text()
mutants=[('empty-inspect','batch.config.requested\n        .filterNot','batch.outcomes.flatMap(_.run.toVector.flatMap(_.config.requested))\n        .filterNot'),('empty-cli','batch.config.requested.exists(_ != MappingPipeline.Capability.Mapping)','batch.outcomes.flatMap(_.run).exists(_.config.requested.exists(_ != MappingPipeline.Capability.Mapping))')]
results=[]
for name,old,new in mutants:
 assert original.count(old)==1,name
 try:
  p.write_text(original.replace(old,new))
  (out/(name+'.patch.txt')).write_text(subprocess.check_output(['git','diff','--',str(rel)],cwd=clone,text=True))
  rc,log=run(name,['pipeline/testOnly *MappingPipelineSuite'])
  passed=rc!=0 and 'done compiling' in log and '[error] --' not in log and any('==> X' in line and 'zero and all-refused batches' in line for line in log.splitlines())
  assert passed,name
  results.append(dict(mutant=name,compiled=True,namedFailure=True,exit=rc,totals=[l for l in log.splitlines() if 'Failed: Total' in l]))
 finally:p.write_text(original)
(out/'readiness-mutations.json').write_text(json.dumps(results,indent=2)+'\n')
rc,_=run('readiness-restored',['pipeline/testOnly *MappingPipelineSuite'])
assert rc==0
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
