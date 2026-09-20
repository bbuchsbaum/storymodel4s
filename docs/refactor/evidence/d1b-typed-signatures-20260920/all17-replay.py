"""Pinned gold-free all17 local-ONNX replay, followed by exact exported-anchor parity."""
from pathlib import Path
import csv,hashlib,io,json,os,platform,subprocess,sys,time
EXPECTED='94baf6e4ea94dd9238e377d3434ddc405332af17139b898d3c6a20710c21ce62'
INPUTS='aae2fed17c0b989205c29b6e9aa95054c4f7665429dc7a19e91367ec743fde92'
ROW_DIGEST='59e8118472c91e07506910ab27b4cbf4a30ea9f0ce8703a6f44f66b4531dd8dc'
BASELINE='42b9ec1558272487192829fc6f381965cff858764cb83aed96d35559a0ceadca'
GR='0329c43c88a0b71e9aa4456723bb16bac2fa3841'
def sha(b):return hashlib.sha256(b).hexdigest()
def head(repo):return subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
def clean(repo):return not subprocess.check_output(['git','status','--porcelain'],cwd=repo)
def need(ok,reason):
 if not ok:raise AssertionError(reason)
def file_record(path):return dict(path=str(path),bytes=path.stat().st_size,sha256=sha(path.read_bytes()))
def write(path,value):path.write_text(json.dumps(value,indent=2)+'\n')
def project_reports(out,inputs):
 buf=io.StringIO(newline='');writer=csv.writer(buf,delimiter='\t',lineterminator='\n')
 fields=['unit','mapAnchor','mediaPart','startSeconds','endSeconds']
 writer.writerow(['participant']+fields);counts={}
 for item in inputs:
  p=out/('recall-map-'+item['participant']+'.tsv')
  with p.open(newline='') as stream:rows=list(csv.DictReader(stream,delimiter='\t'))
  counts[item['participant']]=len(rows)
  for row in rows:writer.writerow([item['participant']]+[row[f] for f in fields])
 raw=buf.getvalue().encode();return raw,counts

def main():
 repo,data,out,grakern,support_capture=(Path(x).resolve() for x in sys.argv[1:])
 need(all(not any(c.isspace() for c in str(p)) for p in [repo,data,out,grakern]),'task paths contain whitespace')
 need(clean(repo) and clean(grakern) and head(grakern)==GR,'candidate/dependency not clean exact pin')
 need(not out.exists(),'fresh output directory required')
 frozenpath=repo/'docs/refactor/evidence/sherlock-baseline-20260919/manifest.json'
 need(sha(frozenpath.read_bytes())==BASELINE,'frozen NN03 config manifest changed')
 frozen=json.loads(frozenpath.read_bytes())
 inputpath=Path(__file__).with_name('all17-inputs.json')
 need(sha(inputpath.read_bytes())==INPUTS,'immutable recall inventory changed')
 inputs=json.loads(inputpath.read_bytes())
 need(len(inputs)==17 and len({r['participant'] for r in inputs})==17 and [r['participant'] for r in inputs]==sorted(r['participant'] for r in inputs),'wrong participant inventory')
 capture=json.loads(support_capture.read_bytes())
 expected_rows=frozen['inventory']['rowLoci']
 receipts=[];input_files={}
 for key in ['annotation','model','tokenizer','repairRecord','recallLineage']:
  item=frozen['inputs'][key];path=(data if item['base']=='data' else repo)/item['path']
  need(path.stat().st_size==item['bytes'] and sha(path.read_bytes())==item['sha256'],'changed '+key)
  input_files[key]=file_record(path)
 for item in inputs:
  path=data/'sherlock/recall'/(item['participant']+'.csv')
  need(path.stat().st_size==item['bytes'] and sha(path.read_bytes())==item['sha256'],'changed recall '+item['participant'])
  input_files[item['participant']]=file_record(path)
 env={k:v for k,v in os.environ.items() if not k.startswith('STORYMODEL4S_')}
 env.update(frozen['configuration']['environment'])
 for k in frozen['configuration']['unset']:env.pop(k,None)
 env.update(ORT_DISABLE_TELEMETRY='1',STORYMODEL4S_ONNX_MODEL=str(data/'models/onnx/model.onnx'),
  STORYMODEL4S_ONNX_TOKENIZER=str(data/'models/onnx/tokenizer.json'))
 need('STORYMODEL4S_SCENE_CODING' not in env and env['STORYMODEL4S_PRIOR_SCALE']=='1.5','wrong config')
 need('val onnxRuntimeV = \"1.29.0\"' in (repo/'build.sbt').read_text(),'ONNX version changed')
 out.mkdir();revision=head(repo)
 # Execute the exact committed verifier afresh; an older receipt cannot bypass new checks.
 verifier=repo/'docs/refactor/evidence/d1b-typed-signatures-20260920/support-parity.py'
 support_receipt=out/'fresh-support-parity.json';support_log=out/'fresh-support-parity.log'
 verify_command=[sys.executable,str(verifier),str(repo),input_files['annotation']['path'],str(support_capture),str(support_receipt)]
 with support_log.open('x') as stream:
  checked=subprocess.run(verify_command,stdout=stream,stderr=subprocess.STDOUT)
 need(checked.returncode==0,'fresh typed-support comparison failed; no replay launched')
 parity=json.loads(support_receipt.read_bytes())
 need(parity['captureSha256']==sha(support_capture.read_bytes()) and parity['summary']['rowLociSha256']==ROW_DIGEST and
  parity['summary']['exactPhysicalAndScoringComparison'] is True,'fresh typed-support receipt mismatch')
 verifier_receipt=dict(command=verify_command,exitCode=checked.returncode,verifier=file_record(verifier),log=file_record(support_log))
 manifest=dict(schema='d1b/all17-replay/v1',codeRevision=revision,cwd=str(repo),cleanBefore=True,
  grakernRevision=head(grakern),frozenManifestSha256=BASELINE,expectedProjectionSha256=EXPECTED,
  runnerSha256=sha(Path(__file__).read_bytes()),typedSupportCapture=file_record(support_capture),typedSupportParity=file_record(support_receipt),freshSupportVerification=verifier_receipt,inputInventorySha256=sha(inputpath.read_bytes()),inputs=input_files,
  environment={k:v for k,v in env.items() if k.startswith('STORYMODEL4S_') or k=='ORT_DISABLE_TELEMETRY'},
  unset=frozen['configuration']['unset'],clearedInheritedStorymodelOverrides=True,
  host=dict(system=platform.system(),release=platform.release(),machine=platform.machine()),
  onnxRuntimeDeclaredInExactBuild='1.29.0',
  buildInputs={p:sha((repo/p).read_bytes()) for p in ['.jvmopts','build.sbt','project/build.properties','project/plugins.sbt']},
  qualification='Development-only local ONNX engineering replay; no gold/scoring input. Original all17 launcher/revision unestablished; comparison is to frozen output projection.',runs=receipts)
 write(out/'manifest.json',manifest)
 for item in inputs:
  participant=item['participant'];report=out/('recall-map-'+participant+'.tsv');recall=data/'sherlock/recall'/(participant+'.csv')
  cmd=['sbt','-batch','-Dstorymodel4s.grakern.build='+str(grakern),
   'embedBench/runMain storymodel4s.bench.sherlock.sherlockRecallMap '+str(data/'sherlock/Sherlock_Segments_1000_NN_2017.tsv')+' '+str(recall)+' '+str(report)]
  need(clean(repo) and head(repo)==revision,'candidate drift')
  log=out/(participant+'.log');start=time.time()
  with log.open('x') as f:
   f.write('COMMAND '+json.dumps(cmd)+'\n');f.flush()
   r=subprocess.run(cmd,cwd=repo,env=env,stdout=f,stderr=subprocess.STDOUT)
   f.write('\nCOMMAND_EXIT='+str(r.returncode)+'\n')
  entry=dict(participant=participant,command=cmd,exitCode=r.returncode,startedEpochSeconds=start,
   seconds=time.time()-start,cleanAfter=clean(repo),revisionAfter=head(repo),log=file_record(log))
  receipts.append(entry);write(out/'manifest.json',manifest)
  need(r.returncode==0 and entry['cleanAfter'] and entry['revisionAfter']==revision,'mapper failed or drifted: '+participant)
  artifacts=[report]+[Path(str(report)+suffix) for suffix in ['.posterior.json','.stages.json','.voyage.json','.clock-repair.json']]
  need(all(p.exists() and p.stat().st_mtime>=start for p in artifacts),'missing or stale mapper artifact')
  entry['artifacts']={p.name:file_record(p) for p in artifacts}
  clock=json.loads(Path(str(report)+'.clock-repair.json').read_bytes())
  need(clock['schema']=='storymodel4s.bench.clock-repair' and clock['schemaVersion']==1,'clock schema')
  need(clock['reportSha256']==sha(report.read_bytes()),'clock not bound to actual report')
  need(clock['recordSha256']==input_files['repairRecord']['sha256'] and
   clock['annotationSha256']==input_files['annotation']['sha256'],'clock input mismatch')
  need(clock['sourceFingerprint']==capture['viewFingerprint'],'clock not bound to typed source view')
  expected=[{k:r[k] for k in ['row','part','startTick','endTick']} for r in expected_rows]
  actual=[{k:r[k] for k in ['row','part','startTick','endTick']} for r in clock['rows']]
  need(actual==expected,'clock all-row coordinates differ')
  need(all(r['receiptId'] in {v['receiptId'] for v in clock['repairs']} for r in clock['rows']),'row repair receipt missing')
  with report.open(newline='') as stream:report_rows=list(csv.DictReader(stream,delimiter='\t'))
  need(len(report_rows)==item['expectedRows'],'participant row population changed')
  entry['clockBinding']=dict(all1000RowsExact=True,row13PointRetained=True,sourceFingerprint=clock['sourceFingerprint'],reportSha256=clock['reportSha256'])
  welcome=[line for line in log.read_text().splitlines() if 'welcome to sbt' in line]
  need(len(welcome)==1,'missing actual sbt runtime');entry['sbtRuntimeFromLog']=welcome[0]
  write(out/'manifest.json',manifest);print(participant,'PASS',flush=True)
 need(len(receipts)==17,'incomplete replay')
 need(sorted(p.name for p in out.glob('recall-map-*.tsv'))==['recall-map-'+v['participant']+'.tsv' for v in inputs],'report file population mismatch')
 raw,counts=project_reports(out,inputs);(out/'anchor-projection.tsv').write_bytes(raw)
 manifest['projection']=dict(sha256=sha(raw),rows=sum(counts.values()),perParticipant=counts,exactEqual=sha(raw)==EXPECTED)
 manifest['cleanAfter']=clean(repo);manifest['grakernCleanAfter']=clean(grakern);write(out/'manifest.json',manifest)
 need(manifest['cleanAfter'] and manifest['grakernCleanAfter'] and head(repo)==revision and head(grakern)==GR,'final candidate/dependency drift')
 need(sum(counts.values())==2577 and sha(raw)==EXPECTED,'all17 anchor projection differs')
 # Change one endpoint while preserving the full participant/row population.
 changed_rows=list(csv.reader(io.StringIO(raw.decode()),delimiter='\t'));changed_rows[1][-1]='-1'
 changed_buffer=io.StringIO(newline='');csv.writer(changed_buffer,delimiter='\t',lineterminator='\n').writerows(changed_rows)
 changed=changed_buffer.getvalue().encode();need(sha(changed)!=EXPECTED,'comparator failed')
 manifest['projection']['comparatorFalsifier']=dict(originalAccepted=True,changedEndpointRejected=True,mutantSha256=sha(changed))
 write(out/'manifest.json',manifest);print('ALL17 EXACT',EXPECTED,flush=True)
if __name__=='__main__':main()
