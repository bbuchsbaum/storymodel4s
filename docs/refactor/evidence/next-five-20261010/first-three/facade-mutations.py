import json, pathlib, subprocess
repo=pathlib.Path('/private/tmp/storymodel4s-s2b3-build')
out=pathlib.Path('/Users/bbuchsbaum/code/scala/storymodel4s/.agent-work/next-five-20261010')
mutations=[('written-validation','pipeline/src/main/scala/storymodel4s/pipeline/StoryBuild.scala','StoryValidator.validate(written)','StoryValidator.validate(compilation.draft)','StoryBuildSuite','a written invalid draft cannot inherit'),('invalid-exit','pipeline/src/main/scala/storymodel4s/pipeline/StoryBuild.scala','if !summary.validated','if !summary.validated && summary.errors < 0','StoryBuildSuite','a written invalid draft cannot inherit'),('config-redaction','pipeline/src/main/scala/storymodel4s/pipeline/MappingPipeline.scala','json <- parser.parse(text).left.map(_ => "invalid configuration")','json <- parser.parse(text).left.map(_.message)','FacadeFailureSuite','invalid path and malformed JSON')]
results=[]
for name,rel,old,new,suite,witness in mutations:
 p=repo/rel;original=p.read_text();assert original.count(old)==1,(name,original.count(old))
 try:
  p.write_text(original.replace(old,new))
  (out/('facade-mutant-'+name+'.patch.txt')).write_text(subprocess.check_output(['git','diff','--',rel],cwd=repo,text=True))
  log=out/('facade-mutant-'+name+'.log')
  r=subprocess.run(['python3','/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log',str(log),'--cwd',str(repo),'--timeout','900','--','sbt','-Dstorymodel4s.grakern.build=/private/tmp/storymodel4s-workflow-grakern-pin','set ThisBuild / tlFatalWarnings := true','pipeline/testOnly *'+suite],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
  text=log.read_text()
  result={'mutant':name,'exit':r.returncode,'compiled':'done compiling' in text and '[error] --' not in text,'namedFailure':any(witness in l and '==> X' in l for l in text.splitlines()),'totals':[l for l in text.splitlines() if 'Failed: Total' in l]}
  results.append(result);print(json.dumps(result),flush=True)
  assert r.returncode!=0 and result['compiled'] and result['namedFailure'] and result['totals'],result
 finally:p.write_text(original)
(out/'facade-mutations.json').write_text(json.dumps(results,indent=2)+'\n')
