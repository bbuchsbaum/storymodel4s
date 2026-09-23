import pathlib, subprocess, json, time
root=pathlib.Path('/private/tmp/storymodel-p1-integration-20260923')
files={name:root/name for name in ['align/src/main/scala/storymodel4s/align/mappingbinding.scala','align/src/main/scala/storymodel4s/align/wire.scala']}
original={name:p.read_text() for name,p in files.items()}
assert not subprocess.check_output(['git','status','--porcelain','--untracked-files=no'],cwd=root,text=True).strip()
base=['env','-u','STORYMODEL4S_GRAKERN_BUILD','sbt','-batch','-Dsbt.global.staging=/private/tmp/p1-closeout-sbt-staging-20260923','-Dstorymodel4s.grakern.build=/private/tmp/storymodel-p1-grakern-0329c43c','set ThisBuild / tlFatalWarnings := true']
binding='align/src/main/scala/storymodel4s/align/mappingbinding.scala'; wire='align/src/main/scala/storymodel4s/align/wire.scala'
scope1='    else if MappingSourceRender.scope(\n        view.nodes\n      ) != scopeDigest || source.scopeDigest != scopeDigest\n    then Left(MappingRefusal.BindingMismatch("scopeDigest"))\n'
scope2='    else if MappingSourceRender.scope(checkedView.nodes) != source.scopeDigest then\n      Left(MappingRefusal.BindingMismatch("scopeDigest"))\n'
rows=[]
try:
 for name,task in [('scope-binding-omitted','alignJVM/testOnly storymodel4s.align.MappingHistoricalSuite'),('semantic-cause-omitted','fixturesJVM/testOnly storymodel4s.fixtures.WorkspaceJoinSuite')]:
  for key,p in files.items(): p.write_text(original[key])
  if name.startswith('scope'):
   s=original[binding]; assert s.count(scope1)==1 and s.count(scope2)==1
   files[binding].write_text(s.replace(scope1,'').replace(scope2,''))
  else:
   a='optional(p.cause)'; b='field("cause", p.cause.getOrElse(""))'
   assert original[binding].count(a)==1 and original[wire].count(b)==1
   files[binding].write_text(original[binding].replace(a,'optional(None)'))
   files[wire].write_text(original[wire].replace(b,'field("cause", "")'))
  log=pathlib.Path('/private/tmp/admissibility-fixture-'+name+'.log')
  cmd=base+[task]; started=time.time()
  with log.open('w') as out:
   out.write('SHA=2981b6b09eea888c0bebf8ab2c47f11ee3ccff26\nCOMMAND='+json.dumps(cmd)+'\n');out.flush()
   r=subprocess.run(cmd,cwd=root,stdout=out,stderr=subprocess.STDOUT,timeout=300)
   out.write('\nEXIT='+str(r.returncode)+'\n')
  row={'mutant':name,'exit':r.returncode,'seconds':time.time()-started,'log':str(log),'command':cmd};rows.append(row);print(json.dumps(row),flush=True)
finally:
 for key,p in files.items(): p.write_text(original[key])
 pathlib.Path('/private/tmp/admissibility-fixture-mutants.json').write_text(json.dumps(rows,indent=2)+'\n')
print('RESTORED='+str(not subprocess.check_output(['git','status','--porcelain','--untracked-files=no'],cwd=root,text=True).strip()),flush=True)
