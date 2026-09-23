import pathlib,subprocess,json,time
root=pathlib.Path('/private/tmp/storymodel-p1-integration-20260923')
p=root/'align/src/main/scala/storymodel4s/align/mapping.scala'; original=p.read_text()
sha=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
assert not subprocess.check_output(['git','status','--porcelain','--untracked-files=no'],cwd=root,text=True).strip()
base=['env','-u','STORYMODEL4S_GRAKERN_BUILD','sbt','-batch','-Dsbt.global.staging=/private/tmp/p1-closeout-sbt-staging-20260923','-Dstorymodel4s.grakern.build=/private/tmp/storymodel-p1-grakern-0329c43c','set ThisBuild / tlFatalWarnings := true']
rows=[]
cases=[('foreign-link-guard-omitted',[('    val foreignLink = links.exists(l => foreign(l.derivation))\n',''),('foreignRaw || foreignPosterior || foreignLink','foreignRaw || foreignPosterior')],'alignJVM/testOnly storymodel4s.align.MappingOutcomeSuite'),('supported-decode-as-gapfill',[('if values.contains(destination) then DecisionOrigin.StructuredDecode(p)','if values.contains(destination) then DecisionOrigin.GapFill(p)')],'alignJVM/testOnly storymodel4s.align.MappingHistoricalSuite'),('restored',[],'alignJVM/testOnly storymodel4s.align.MappingOutcomeSuite storymodel4s.align.MappingHistoricalSuite')]
try:
 for name,replacements,task in cases:
  s=original
  for a,b in replacements:
   assert s.count(a)==1,(name,a)
   s=s.replace(a,b)
  p.write_text(s)
  log=pathlib.Path('/private/tmp/admissibility-fixture-second-'+name+'.log'); cmd=base+[task]; started=time.time()
  with log.open('w') as out:
   out.write('SHA='+sha+'\nCOMMAND='+json.dumps(cmd)+'\nPATCH='+json.dumps(replacements)+'\n');out.flush()
   r=subprocess.run(cmd,cwd=root,stdout=out,stderr=subprocess.STDOUT,timeout=300)
   out.write('\nEXIT='+str(r.returncode)+'\n')
  row={'mutant':name,'exit':r.returncode,'seconds':time.time()-started,'log':str(log),'command':cmd,'source_sha':sha}; rows.append(row); print(json.dumps(row),flush=True)
finally:
 p.write_text(original)
 pathlib.Path('/private/tmp/admissibility-fixture-second-mutants.json').write_text(json.dumps(rows,indent=2)+'\n')
print('RESTORED='+str(not subprocess.check_output(['git','status','--porcelain','--untracked-files=no'],cwd=root,text=True).strip()),flush=True)
