from pathlib import Path
import subprocess,json,hashlib
root=Path.cwd();clone=root/'.agent-work/release-workflow-20261014/producer-check';work=root/'.agent-work/release-freeze-20261014'
p=clone/'embed-core/src/test/scala/storymodel4s/embed/SemanticViewIdentitySuite.scala';source=p.read_text()
changed=source.replace('assertEquals(views.distinct.size,','println("PHASE views-created")\n    assertEquals(views.distinct.size,').replace('assertEquals(views.map(_.render).distinct.size,','println("PHASE values-distinct")\n    assertEquals(views.map(_.render).distinct.size,').replace('assertEquals(views.map(view => space(view).id).distinct.size, views.size)','println("PHASE renders-distinct")\n    assertEquals(views.map { view =>\n      println("SPACE " + view.render.map(_.toInt).mkString(","))\n      space(view).id\n    }.distinct.size, views.size)\n    println("PHASE ids-distinct")')
assert changed!=source
try:
 p.write_text(changed)
 result=subprocess.run(['python3','/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log',str(work/'promotion-native-identity-diagnostic.log'),'--cwd',str(clone),'--timeout','600','--head-bytes','400','--tail-bytes','2500','--','sbt','-batch','set ThisBuild / tlFatalWarnings := true','embedCoreNative/testOnly semanticviewprobe.SemanticViewIdentitySuite'])
finally:
 p.write_text(source)
(work/'promotion-native-identity-diagnostic-source.scala').write_text(changed)
print('Restored source',hashlib.sha256(p.read_bytes()).hexdigest())
raise SystemExit(result.returncode)
