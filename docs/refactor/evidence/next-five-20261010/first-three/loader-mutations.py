import json, pathlib, subprocess, sys
repo=pathlib.Path('/private/tmp/storymodel4s-s2b3-build')
out=pathlib.Path('/Users/bbuchsbaum/code/scala/storymodel4s/.agent-work/next-five-20261010')
rel='pipeline/src/main/scala/storymodel4s/pipeline/AdmittedStoryInput.scala'
p=repo/rel
original=p.read_text()
mutations=[('digest', 'digest == Checksum.ofText(body)', 'digest == Checksum.ofText(body) || body.nonEmpty', 'exact body hash refuses'),('plain-header','!recognized, (), Refusal.RequiresAdmittedMode','(!recognized || text.nonEmpty), (), Refusal.RequiresAdmittedMode','plain mode refuses'),('duplicate-header','!previous.exists(_._1 == key)','(!previous.exists(_._1 == key) || value.nonEmpty)','header boundary, required fields')]
results=[]
for name,old,new,witness in mutations:
 assert original.count(old)==1,(name,original.count(old))
 try:
  p.write_text(original.replace(old,new))
  patch=subprocess.check_output(['git','diff','--',rel],cwd=repo,text=True)
  (out/('loader-mutant-'+name+'.patch.txt')).write_text(patch)
  log=out/('loader-mutant-'+name+'.log')
  r=subprocess.run(['python3','/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log',str(log),'--cwd',str(repo),'--timeout','600','--','sbt','-Dstorymodel4s.grakern.build=/private/tmp/storymodel4s-workflow-grakern-pin','set ThisBuild / tlFatalWarnings := true','pipeline/testOnly *AdmittedStoryInputSuite'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
  text=log.read_text()
  result={'mutant':name,'exit':r.returncode,'compiled': 'done compiling' in text and '[error] --' not in text,'namedFailure': any(witness in line and '==> X' in line for line in text.splitlines()),'totals':[line for line in text.splitlines() if 'Failed: Total' in line]}
  results.append(result);print(json.dumps(result),flush=True)
  assert r.returncode!=0 and result['compiled'] and result['namedFailure'] and result['totals'],result
 finally: p.write_text(original)
(out/'loader-mutations.json').write_text(json.dumps(results,indent=2)+'\n')
