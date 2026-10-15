from pathlib import Path
import subprocess,hashlib
root=Path.cwd();clone=root/'.agent-work/release-workflow-20261014/producer-check';work=root/'.agent-work/release-freeze-20261014'
p=clone/'embed-core/src/test/scala/storymodel4s/embed/SemanticViewIdentitySuite.scala';source=p.read_text()
changed=source.replace('    val components =', '''    val nul = new String(Array(0.toChar))
    assertEquals(nul.length, 1)
    assertEquals(nul.charAt(0).toInt, 0)
    assertEquals(nul.hashCode, 0)
    val components =''').replace('"\\u0000"','nul')
assert source!=changed
try:
 p.write_text(changed)
 result=subprocess.run(['python3','/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log',str(work/'promotion-native-runtime-nul-control.log'),'--cwd',str(clone),'--timeout','600','--head-bytes','400','--tail-bytes','2400','--','sbt','-batch','set ThisBuild / tlFatalWarnings := true','embedCoreNative/testOnly semanticviewprobe.SemanticViewIdentitySuite','embedCoreNative/test'])
finally:p.write_text(source)
(work/'promotion-native-runtime-nul-control-source.scala').write_text(changed)
print('Restored source',hashlib.sha256(p.read_bytes()).hexdigest());raise SystemExit(result.returncode)
