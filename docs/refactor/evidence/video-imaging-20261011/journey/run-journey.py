from pathlib import Path
import subprocess,json,hashlib,sys
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s');work=root/'.agent-work/video-imaging-20261011';clone=Path('/private/tmp/storymodel4s-s2b3-build');sha='13bdc9aad68bba8493577f79f212fa07f3b57f44'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=clone,text=True).strip()==sha
assert not subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True).strip()
t=work/'journey-text-input';a=root/'.agent-work/workflow-five/sherlock-workspace-input'
# Reuse the same previously admitted local development inputs, not a new split.
prior=json.loads((a/'preparation-receipt.json').read_text())
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
assert digest(a/'annotations.csv')==prior['preparedCsvSha256']
for entry in prior['recalls']:assert digest(a/(entry['source']+'.csv'))==entry['csvSha256']
commands=[]
for label,inputs in [('text',t),('sherlock-A',a)]:
 output=work/('journey-'+label);output.mkdir(exist_ok=True)
 commands += [f'pipeline/runMain storymodel4s.pipeline.studyPrepare prepare {inputs}/study.json {output}/prepared',f'pipeline/runMain storymodel4s.pipeline.mappingRun run {inputs}/study.json {inputs}/mapping.json {output}/paired',f'pipeline/runMain storymodel4s.pipeline.mappingRun exchange {inputs}/study.json {inputs}/mapping.json {output}/exchange',f'pipeline/runMain storymodel4s.pipeline.mappingWeight {inputs}/study.json {inputs}/mapping.json 0/1,1/2,1/1 {output}/weighted']
log=work/'journey-public-path.log'
args=['sbt','-batch','-Dstorymodel4s.grakern.build=/private/tmp/storymodel4s-workflow-grakern-pin','set ThisBuild / tlFatalWarnings := true',*commands]
result=subprocess.run(['python3','/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log',str(log),'--cwd',str(clone),'--timeout','3600','--',*args],stdout=subprocess.DEVNULL)
print('PUBLIC_JOURNEY_EXIT',result.returncode,flush=True)
assert result.returncode==0
sys.path.insert(0,str(root/'examples/mapping'))
from read_mapping import read_publication
from read_weighted_mapping import read_sweep
receipt={'producer':sha,'commands':commands,'scope':'local public input path; text uses admitted Boas source with fixed synthetic external recall control; Sherlock A reuses two prior admitted development inputs; no AV/bench parity/raw-BIDS qualification','arms':[]}
for label,inputs in [('text',t),('sherlock-A',a)]:
 output=work/('journey-'+label)
 def files(path):return {p.name:p.read_text() for p in path.iterdir()}
 paired=read_publication(files(output/'exchange'));base,members=read_sweep(files(output/'weighted'))
 arm={'arm':label,'pairedAttempts':len(paired['outcomes']),'pairs':len(paired['pairs']),'weightedAttempts':len(base['outcomes']),'weightedMembers':len(members),'inputStudySha256':digest(inputs/'study.json'),'files':[]}
 for p in sorted(output.rglob('*')):
  if p.is_file():arm['files'].append({'path':str(p.relative_to(output)),'bytes':p.stat().st_size,'sha256':digest(p)})
 receipt['arms'].append(arm)
 print('READBACK',label,arm['pairedAttempts'],arm['pairs'],arm['weightedMembers'],flush=True)
(work/'journey-qualification.json').write_text(json.dumps(receipt,indent=2)+'\n')
