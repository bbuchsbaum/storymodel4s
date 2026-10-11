from pathlib import Path
import json,subprocess,hashlib,sys
r=Path('/Users/bbuchsbaum/code/scala/storymodel4s');w=r/'.agent-work/video-imaging-20261011';o=w/'live-f0-output'
pin=json.loads((o/'completion.json').read_text())['packageManifestSha256']
cmds=[f'pipeline/runMain storymodel4s.pipeline.VideoSourceCli replay {o}/package {pin} live-worker-f0 {w}/live-f0-replay',f'pipeline/runMain storymodel4s.pipeline.mappingRun exchange {w}/live-f0-study.json {w}/journey-text-input/mapping.json {w}/live-f0-mapping']
a=['python3','/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log',str(w/'live-f0-consume.log'),'--cwd','/private/tmp/storymodel4s-s2b3-build','--timeout','600','--','sbt','-batch','-Dstorymodel4s.grakern.build=/private/tmp/storymodel4s-workflow-grakern-pin','set ThisBuild / tlFatalWarnings := true',*cmds]
p=subprocess.run(a,stdout=subprocess.DEVNULL);print('LIVE_CONSUME_EXIT',p.returncode,flush=True);assert p.returncode==0
assert (o/'source.json').read_bytes()==(w/'live-f0-replay/source.json').read_bytes()
sys.path.insert(0,str(r/'examples/mapping'));from read_mapping import read_publication
checked=read_publication({p.name:p.read_text() for p in (w/'live-f0-mapping').iterdir()})
print('LIVE_MAPPING_PAIRS',len(checked['pairs']),flush=True)
(w/'live-f0-readback.json').write_text(json.dumps({'producer':'13bdc9aad68bba8493577f79f212fa07f3b57f44','replaySourceIdentical':True,'mappingPairs':len(checked['pairs']),'scope':'actual workers on admitted synthetic F0; fixed external recall control; no caption quality or annotation-sidecar qualification'},indent=2)+'\n')
