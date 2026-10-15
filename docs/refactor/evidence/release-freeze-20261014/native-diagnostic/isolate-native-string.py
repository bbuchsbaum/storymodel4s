from pathlib import Path
import subprocess,hashlib
root=Path.cwd();clone=root/'.agent-work/release-workflow-20261014/producer-check';work=root/'.agent-work/release-freeze-20261014'
p=clone/'embed-core/src/test/scala/storymodel4s/embed/SemanticViewIdentitySuite.scala';source=p.read_text()
changed=source.replace('assertEquals(views.distinct.size,', 'println("RAW empty " + "".hashCode)\n    println("RAW nul begin")\n    println("RAW nul result " + "\\u0000".hashCode)\n    println("CASE nul begin")\n    println("CASE nul result " + Tuple2("", "\\u0000").hashCode)\n    println("ENUM nul begin")\n    println("ENUM nul result " + SemanticView.Custom("", "\\u0000").hashCode)\n    assertEquals(views.distinct.size,')
try:
 p.write_text(changed)
 result=subprocess.run(['python3','/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log',str(work/'promotion-native-string-hash-control.log'),'--cwd',str(clone),'--timeout','600','--head-bytes','400','--tail-bytes','2600','--','sbt','-batch','set ThisBuild / tlFatalWarnings := true','embedCoreNative/testOnly semanticviewprobe.SemanticViewIdentitySuite'])
finally:p.write_text(source)
(work/'promotion-native-string-hash-control-source.scala').write_text(changed)
print('Restored source',hashlib.sha256(p.read_bytes()).hexdigest());raise SystemExit(result.returncode)
