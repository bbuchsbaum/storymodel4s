from pathlib import Path
import hashlib,json,subprocess
repo=Path('/Users/bbuchsbaum/code/scala/storymodel4s');out=Path(__file__).parent
receipt=json.loads((repo/'.agent-work/release-workflow-20261014/distribution/artifact-readback.json').read_text())
dist=repo/'.agent-work/release-workflow-20261014/distribution/installed/storymodel4s-0.1-2c4b74f-SNAPSHOT';m=json.loads((dist/'manifest.json').read_text())
assert m['source']=='2c4b74f9ef069e1bc1f6678f8dababe2421ca929'
for f in m['files']:
 b=(dist/f['path']).read_bytes();assert len(b)==f['bytes'] and hashlib.sha256(b).hexdigest()==f['sha256'],f['path']
assert (dist/'classpath.txt').read_text().splitlines()==m['classpath']
assert {p.name for p in (dist/'lib').glob('*.jar')}==set(m['classpath'])
runtime=':'.join(str(dist/'lib'/n) for n in m['classpath'])
names=['scala3-compiler_3-3.7.4.jar','scala3-interfaces-3.7.4.jar','scala3-library_3-3.7.4.jar','tasty-core_3-3.7.4.jar','scala-library-2.13.16.jar','scala-asm-9.8.0-scala-1.jar','compiler-interface-1.10.7.jar','jline-reader-3.29.0.jar','jline-terminal-3.29.0.jar','jline-terminal-jni-3.29.0.jar']
cache=Path.home()/'Library/Caches/Coursier/v1'
paths=[next(cache.rglob(n)) for n in names];compiler=':'.join(map(str,paths))
classes=out/'classes';classes.mkdir(exist_ok=True)
opts=['java','-Xmx2g','-XX:ActiveProcessorCount=2']
commands=[opts+['-cp',compiler,'dotty.tools.dotc.Main','-classpath',runtime,'-d',str(classes),str(out/'ClaimLedgerProbe.scala')],opts+['-cp',str(classes)+':'+runtime,'externalledgerprobe.ClaimLedgerProbe']]
(out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
statuses=[]
for name,argv in zip(['compile','run'],commands):
 with (out/(name+'.log')).open('w') as f:
  result=subprocess.run(argv,stdout=f,stderr=subprocess.STDOUT);statuses.append({'phase':name,'exit':result.returncode})
 if result.returncode:break
r={'sourceSha':m['source'],'distributionManifestSha256':hashlib.sha256((dist/'manifest.json').read_bytes()).hexdigest(),'verifiedFiles':len(m['files']),'runtimeEntries':len(m['classpath']),'compiler':[{'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in paths],'commands':commands,'statuses':statuses,'files':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in out.iterdir() if p.is_file() and p.name!='receipt.json'}}
(out/'receipt.json').write_text(json.dumps(r,indent=2)+'\n')
print(json.dumps(statuses))
if len(statuses)==2:print((out/'run.log').read_text())
else:print((out/'compile.log').read_text())
