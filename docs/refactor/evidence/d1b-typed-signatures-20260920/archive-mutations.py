"""Verify and freeze the completed D1B mutation court; never runs sbt."""
from pathlib import Path
import hashlib,importlib.util,json,subprocess,tarfile
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s')
data=root/'data/study/d1b-20260920'
dest=root/'docs/refactor/evidence/d1b-typed-signatures-20260920'
repo=Path('/private/tmp/storymodel4s-d1b-20260920')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def write(path,value):
 assert not path.exists(),path
 path.write_text(json.dumps(value,indent=2)+'\n')
manifest=json.loads((data/'mutations.json').read_bytes())
inventory=json.loads((data/'guard-witness-inventory.json').read_bytes())
rows=manifest['mutations'];expected={x['id']:x for x in inventory['cases']}
assert len(rows)==len(expected)==67 and {x['id'] for x in rows}==set(expected)
assert sum(x['cleanRecompile'] for x in rows)==8
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()==manifest['codeRevision']
assert not subprocess.check_output(['git','status','--porcelain'],cwd=repo)
assert sha(dest/'qualify.py')==inventory['runnerSha256']
spec=importlib.util.spec_from_file_location('qualify',dest/'qualify.py');q=importlib.util.module_from_spec(spec);spec.loader.exec_module(q)
binding=sha(data/'guard-witness-inventory.json');assert binding==manifest['bindingSha256']
_,control_names=q.checked_control('isolated-control',binding)
restored,_=q.checked_control('restored-control-clean',binding,control_names)
assert restored==manifest['restoredControl'] and restored['aggregateTestCounts']['Passed']==90
for row in rows:
 q.verify_completed(row,expected[row['id']],binding,control_names)
 assert sha(repo/row['path'])==row['originalSha256']==row['restoredSha256']
summary=dict(schema='d1b/mutation-terminal/v1',codeRevision=manifest['codeRevision'],planned=67,compiledKilled=67,
 freshMainAndProbeRecompiles=8,namedControlsPassed=67,terminalCloneClean=q.clean(),
 restoredControl=restored,manifestSha256=sha(data/'mutations.json'),inventorySha256=binding,
 restorationBasis='Each recorded mutation binds baseline cleanliness, exact original/mutant/restored bytes, a completed finally restoration receipt, raw command/log and JUnit outcomes; reverified before archive.',
 records=[{k:r[k] for k in ['id','path','originalSha256','mutantSha256','restoredSha256','baselineCleanBeforeMutation',
  'restoredCleanAfterMutation','rejectSuite','rejectingTest','controlSuite','acceptingControl','cleanRecompile',
  'logSha256','rawReceiptSha256','restorationSha256','reports']} for r in rows])
write(data/'mutation-terminal-summary.json',summary)
paths={data/x for x in ['mutations.json','guard-witness-inventory.json','mutation-terminal-summary.json']}
for label in ['isolated-control','restored-control-clean']+[Path(r['logPath']).stem for r in rows]:
 paths.update(data.glob(label+'*'))
assert all(p.is_file() for p in paths)
paths.add(dest/'qualify.py')
archive=dest/'mutation-evidence.tar.gz';assert not archive.exists()
with tarfile.open(archive,'w:gz') as tar:
 for path in sorted(paths):tar.add(path,arcname=path.name)
index=dict(archive=archive.name,sha256=sha(archive),bytes=archive.stat().st_size,memberCount=len(paths),
 members=[dict(path=p.name,bytes=p.stat().st_size,sha256=sha(p)) for p in sorted(paths)])
assert len({r['path'] for r in index['members']})==len(paths)
write(dest/'mutation-evidence-manifest.json',index)
for name in ['guard-witness-inventory.json','mutation-terminal-summary.json']:
 assert not (dest/name).exists();(dest/name).write_bytes((data/name).read_bytes())
print(json.dumps({k:v for k,v in index.items() if k!='members'},indent=2))
