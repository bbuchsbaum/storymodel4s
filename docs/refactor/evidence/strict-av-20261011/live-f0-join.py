"""Public CLI integration on existing actual-worker F0, with deliberately synthetic annotations."""
from pathlib import Path
import hashlib,json,shutil,subprocess,sys
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s'); out=root/'.agent-work/strict-av-20261011'; fixture=out/'live-f0-join'; clone=Path('/private/tmp/storymodel4s-s2b3-build'); sha='29997f88d159de8b4dc2620a4ce23c071e9b51ca'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=clone,text=True).strip()==sha
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
assert not [x for x in subprocess.check_output(['ps','-axo','pid,comm'],text=True).splitlines() if x.strip().endswith('/java')]
fixture.mkdir(); source=root/'docs/refactor/evidence/video-imaging-20261011/video/live-f0/live-f0-output';shutil.copytree(source/'package',fixture/'package')
media=json.loads((source/'source.json').read_text());part=media['parts'][0]
canonical=lambda value:json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False)
hashtext=lambda s:hashlib.sha256(s.encode()).hexdigest()
rows='ordinal,text,part,onset,end,group,group_label,locations,characters\n'+''.join(f'{i+1},Anna arrived.,synthetic,{p["start"]},{p["end"]},,,,\n' for i,p in enumerate(part['proposals']))
a=canonical({'schemaVersion':'prepared-timed-source/v1','csv':rows,'parts':canonical([{'part':'synthetic',**{k:part[k] for k in ('start','end','numerator','denominator')}}])})
(fixture/'annotations.json').write_text(a)
base=['sbt','-batch','-Dstorymodel4s.grakern.build=/private/tmp/storymodel4s-workflow-grakern-pin','set ThisBuild / tlFatalWarnings := true']
def run(name,cmds):
 log=out/(name+'.log');p=subprocess.run(['python3','/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log',str(log),'--cwd',str(clone),'--timeout','600','--',*base,*cmds],stdout=subprocess.DEVNULL);assert p.returncode==0,(name,p.returncode);return log.read_text()
text=run('live-f0-template',[f'pipeline/runMain storymodel4s.pipeline.VideoSourceCli join-template {fixture}/annotations.json {fixture}/package {media["packageManifest"]} {media["edition"]}'])
start=text.index('{\n  "annotationAxes"');template=json.JSONDecoder().raw_decode(text[start:])[0];c=template['correspondenceTemplate'];c['basis']='Project synthetic control: annotations generated from F0 caption intervals, not an independent physical synchronization measurement.'
c['parts'][0].update(filmOccurrence=part['occurrence'],filmAxis=template['filmAxes'][0]['axis'],landmarks=[{'annotationTick':part[k],'filmTick':part[k]} for k in ('start','end')])
ct=canonical(c);(fixture/'correspondence.json').write_text(ct)
(fixture/'recall.txt').write_text('Anna arrived.')
study={'schemaVersion':'storymodel-study/v1','source':{'format':'checked-av','path':'joined/source.json','package':'package','manifestSha256':media['packageManifest'],'edition':media['edition'],'blend':'0.8'},'profile':'recall-baseline-lexical-words/v1','recalls':[{'participant':'project-synthetic-control','session':'s1','path':'recall.txt','timing':None,'admission':{'kind':'project-synthetic-control'}}]}
(fixture/'study.json').write_text(canonical(study));(fixture/'mapping.json').write_text(canonical({'profile':'mapping-caption-pair/v1','temperature':'1.0','perLevel':20,'tieBudget':200,'requested':['Mapping'],'networkRequests':0,'retries':0}))
study_a=dict(study,source={'format':'checked-timed','path':'annotations.json'});(fixture/'study-a.json').write_text(canonical(study_a));(fixture/'mapping-a.json').write_text((fixture/'mapping.json').read_text().replace('mapping-caption-pair/v1','mapping-lexical-pair/v1'))
run('live-f0-public-join',[f'pipeline/runMain storymodel4s.pipeline.VideoSourceCli join {fixture}/annotations.json {fixture}/package {media["packageManifest"]} {media["edition"]} {fixture}/correspondence.json {hashtext(ct)} {fixture}/joined',f'pipeline/runMain storymodel4s.pipeline.mappingRun exchange {fixture}/study-a.json {fixture}/mapping-a.json {fixture}/exchange-a',f'pipeline/runMain storymodel4s.pipeline.mappingRun exchange {fixture}/study.json {fixture}/mapping.json {fixture}/exchange',f'pipeline/runMain storymodel4s.pipeline.mappingWeight {fixture}/study.json {fixture}/mapping.json 0/1,1/2,1/1 {fixture}/weighted',f'pipeline/runMain storymodel4s.pipeline.studyWorkspace run {fixture}/study.json {fixture}/mapping.json {fixture}/cache {fixture}/workspace {sha}'])
subprocess.run(['python3',str(out/'read-live-f0.py'),str(fixture),str(out/'live-f0-join-readback.json')],check=True)
