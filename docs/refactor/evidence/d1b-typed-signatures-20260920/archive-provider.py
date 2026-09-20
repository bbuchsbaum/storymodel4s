"""Archive the exact qualified provider gate and executable documentation receipts."""
from pathlib import Path
import hashlib,json,re,subprocess,tarfile
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s');data=root/'data/study/d1b-20260920';dest=root/'docs/refactor/evidence/d1b-typed-signatures-20260920'
repo=Path('/private/tmp/storymodel4s-d1b-20260920')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
terminal=json.loads((data/'release-terminal.json').read_bytes());assert terminal['qualified']
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()==terminal['expectedCandidate']
assert not subprocess.check_output(['git','status','--porcelain'],cwd=repo)
paths={data/'release-terminal.json'}
for label,stage in terminal['stages'].items():
 assert stage['qualified'];raw=data/(label+'.json');log=data/(label+'.log')
 assert sha(raw)==stage['rawReceiptSha256'];receipt=json.loads(raw.read_bytes())
 assert sha(log)==receipt['logSha256'] and receipt['exitCode']==0 and receipt['cleanBefore'] and receipt['cleanAfter']
 paths.update([raw,log])
full=terminal['stages']['full-gate']
for report in full['junitReports']:
 p=data/'full-gate-junit'/report['path'];assert sha(p)==report['sha256'];paths.add(p)
manifest_path=repo/'docs-site/examples/manifest.json';manifest=json.loads(manifest_path.read_bytes());log=(data/'docs-examples.log').read_text()
examples=[]
for item in manifest['examples']:
 p=repo/'docs-site'/item['output'];raw=p.read_bytes()
 assert log.count('verified '+item['id']+': '+str(len(raw))+' output bytes')==1
 examples.append(dict(id=item['id'],expectedPath=item['output'],bytes=len(raw),sha256=sha(p)))
assert len(examples)==13 and log.count('verified 13 executable documentation examples')==1
court=dict(codeRevision=terminal['expectedCandidate'],examples=examples,manifestSha256=sha(manifest_path),logSha256=sha(data/'docs-examples.log'))
(data/'docs-example-output-court.json').write_text(json.dumps(court,indent=2)+'\n');paths.add(data/'docs-example-output-court.json')
archive=dest/'provider-evidence.tar.gz';assert not archive.exists()
with tarfile.open(archive,'w:gz') as tar:
 for p in sorted(paths):tar.add(p,arcname=str(p.relative_to(data)))
index=dict(archive=archive.name,sha256=sha(archive),bytes=archive.stat().st_size,memberCount=len(paths),members=[dict(path=str(p.relative_to(data)),bytes=p.stat().st_size,sha256=sha(p)) for p in sorted(paths)])
(dest/'provider-evidence-manifest.json').write_text(json.dumps(index,indent=2)+'\n')
for name in ['release-terminal.json','full-gate.json','format-last.json','docs-examples.json','docs-example-output-court.json']:(dest/name).write_bytes((data/name).read_bytes())
print(json.dumps(dict(candidate=terminal['expectedCandidate'],counts=full['junitCounts'],members=len(paths),archiveSha256=sha(archive))))
