import pathlib, subprocess, json
root=pathlib.Path('/private/tmp/storymodel-p1-integration-20260923')
p=root/'fixtures/src/test/scala/storymodel4s/fixtures/WorkspaceJoinSuite.scala'; original=p.read_text()
needle='Some("changed semantic annotation")'; assert original.count(needle)==1
base=['env','-u','STORYMODEL4S_GRAKERN_BUILD','sbt','-batch','-Dsbt.global.staging=/private/tmp/p1-closeout-sbt-staging-20260923','-Dstorymodel4s.grakern.build=/private/tmp/storymodel-p1-grakern-0329c43c','set ThisBuild / tlFatalWarnings := true']
try:
 p.write_text(original.replace(needle,'None'))
 cmd=base+['fixturesJVM/testOnly storymodel4s.fixtures.WorkspaceJoinSuite']
 with open('/private/tmp/admissibility-fixture-foreign-neutralized.log','w') as out:
  out.write('SHA=2981b6b09eea888c0bebf8ab2c47f11ee3ccff26\nCOMMAND='+json.dumps(cmd)+'\n');out.flush()
  r=subprocess.run(cmd,cwd=root,stdout=out,stderr=subprocess.STDOUT,timeout=300); out.write('\nEXIT='+str(r.returncode)+'\n')
 print('COUNTERFACTUAL_EXIT='+str(r.returncode),flush=True)
finally:p.write_text(original)
cmd=base+['alignJVM/testOnly storymodel4s.align.MappingHistoricalSuite','fixturesJVM/testOnly storymodel4s.fixtures.WorkspaceJoinSuite']
with open('/private/tmp/admissibility-fixture-restored.log','w') as out:
 out.write('SHA=2981b6b09eea888c0bebf8ab2c47f11ee3ccff26\nCOMMAND='+json.dumps(cmd)+'\n');out.flush()
 r=subprocess.run(cmd,cwd=root,stdout=out,stderr=subprocess.STDOUT,timeout=300);out.write('\nEXIT='+str(r.returncode)+'\n')
print('RESTORED_EXIT='+str(r.returncode),flush=True)
assert not subprocess.check_output(['git','status','--porcelain','--untracked-files=no'],cwd=root,text=True).strip()
