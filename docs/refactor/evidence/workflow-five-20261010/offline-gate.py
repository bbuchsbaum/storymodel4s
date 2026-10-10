"""Serial exact offline-source mutation, source gate, Unicode reader and pinned consumer."""
import gzip,hashlib,json,re,subprocess,sys
from pathlib import Path
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s'); clone=Path('/private/tmp/storymodel4s-s2b3-build'); consumer=Path('/private/tmp/storyatlas4s-reference-consumer')
work=root/'.agent-work/workflow-five';sha=sys.argv[1];wrapper='/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py'
sbt=['sbt','-batch','-Dstorymodel4s.grakern.build=/Users/bbuchsbaum/code/scala/grakern','set ThisBuild / tlFatalWarnings := true']
def logged(name,cwd,command,expected=0):
 r=subprocess.run([sys.executable,wrapper,'--log',str(work/(name+'.log')),'--cwd',str(cwd),'--timeout','7200','--',*command],stdout=subprocess.DEVNULL)
 assert r.returncode==expected,(name,r.returncode)
 print(name+' exit '+str(r.returncode),flush=True)
def clean():
 assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=clone,text=True).strip()==sha
 assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
clean()
p=clone/'pipeline/src/main/scala/storymodel4s/pipeline/StudyPreparation.scala';original=p.read_text();needle='.checkedAtlas(generated, atlasBytes)';assert original.count(needle)==1
p.write_text(original.replace(needle,needle+'.orElse(Right(generated))'))
(work/'offline-atlas-mutation.patch').write_text(subprocess.check_output(['git','diff','--',str(p.relative_to(clone))],cwd=clone,text=True))
try:
 logged('offline-atlas-mutation',clone,sbt+['pipeline/testOnly storymodel4s.pipeline.OfflineSourceIntegrationSuite'],1)
 s=(work/'offline-atlas-mutation.log').read_text()
 assert 'Failed: Total 4, Failed 1, Errors 0, Passed 3' in s and 'foreign surface atlas and malformed narrative model refuse' in s and 'Compilation failed' not in s
finally:p.write_text(original)
clean()
logged('offline-final-checkAll',clone,sbt+['checkAll'])
clean()
witness=work/'offline-final-witness'
logged('offline-final-witness',clone,sbt+['pipeline/Test/runMain storymodel4s.pipeline.OfflineSourceExchangeWitness '+str(witness)])
logged('offline-final-reader',clone,[sys.executable,'examples/mapping/test_offline_source.py',str(witness/'exchange')])
reader=clone/'examples/mapping/test_offline_source.py';old=reader.read_text();needle='return raw[2 * start:2 * end].decode("utf-16-le")';assert old.count(needle)==1
reader.write_text(old.replace(needle,'return text[start:end]'))
try:
 logged('offline-reader-mutation',clone,[sys.executable,'examples/mapping/test_offline_source.py',str(witness/'exchange')],1)
 s=(work/'offline-reader-mutation.log').read_text();assert 'Ran 2 tests' in s and 'FAILED (failures=1)' in s and 'test_ascii_positive_control' in s and 'test_checked_exchange_preserves_unicode_coordinates_and_capabilities' in s
finally:reader.write_text(old)
clean()
logged('offline-final-consumer',consumer,['sbt','-batch','-Dstoryatlas4s.storymodel4s.build='+str(clone),'-Dstorymodel4s.grakern.build=/Users/bbuchsbaum/code/scala/grakern','set ThisBuild / tlFatalWarnings := true','compileAll','testAll','scalafmtCheckAll','scalafmtSbtCheck'])
clean()
(work/'offline-gate-complete.json').write_text(json.dumps({'sha':sha,'consumer':'90f682d1acddd759867098e90f6e3ef163244b0e','source':'checkAll','reader':True})+'\n')
