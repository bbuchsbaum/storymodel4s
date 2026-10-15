from pathlib import Path
import subprocess,hashlib
root=Path.cwd();clone=root/'.agent-work/release-workflow-20261014/producer-check';work=root/'.agent-work/release-freeze-20261014'
p=clone/'embed-core/src/test/scala/storymodel4s/embed/SemanticViewIdentitySuite.scala';source=p.read_text()
changed=source.replace('assertEquals(views.distinct.size,','''views.zipWithIndex.foreach { (view, index) =>
      println("HASH input " + index + " " + view.render.map(_.toInt).mkString(","))
      println("HASH result " + view.hashCode)
    }
    val seen = scala.collection.mutable.HashSet.empty[SemanticView]
    views.zipWithIndex.foreach { (view, index) =>
      println("ADD input " + index)
      println("ADD result " + seen.add(view))
    }
    println("DISTINCT input")
    assertEquals(views.distinct.size,''')
try:
 p.write_text(changed)
 result=subprocess.run(['python3','/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log',str(work/'promotion-native-hash-diagnostic.log'),'--cwd',str(clone),'--timeout','600','--head-bytes','400','--tail-bytes','2600','--','sbt','-batch','set ThisBuild / tlFatalWarnings := true','embedCoreNative/testOnly semanticviewprobe.SemanticViewIdentitySuite'])
finally:p.write_text(source)
(work/'promotion-native-hash-diagnostic-source.scala').write_text(changed)
print('Restored source',hashlib.sha256(p.read_bytes()).hexdigest());raise SystemExit(result.returncode)
