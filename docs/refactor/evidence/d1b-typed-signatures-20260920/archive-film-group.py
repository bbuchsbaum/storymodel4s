"""Archive historical attempts and the separately qualified point-group regression."""
from pathlib import Path
import hashlib,json,subprocess,tarfile
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s');data=root/'data/study/d1b-20260920'
dest=root/'docs/refactor/evidence/d1b-typed-signatures-20260920';repo=Path('/private/tmp/storymodel4s-d1b-20260920')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
terminal=json.loads((data/'film-group-3/film-group-regression.json').read_bytes())
assert terminal['qualified'] and terminal['restoredClean']
assert terminal['originalSha256']==terminal['restoredSha256']==sha(repo/terminal['path'])
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()==terminal['codeRevision']
assert not subprocess.check_output(['git','status','--porcelain'],cwd=repo)
assert sha(dest/'film-group-regression.py')==terminal['runnerSha256']
assert sha(dest/'qualify.py')==terminal['qualificationHelperSha256']
assert sha(repo/terminal['testSourcePath'])==terminal['testSourceSha256']
for label,row in terminal['runs'].items():
 receipt=json.loads((data/'film-group-3'/(label+'.json')).read_bytes())
 assert receipt==row['receipt'] and sha(data/'film-group-3'/(label+'.json'))==row['rawReceiptSha256']
 assert sha(Path(receipt['logPath']))==receipt['logSha256']
 for report in row['reports'].values():assert sha(data/'film-group-3'/report['path'])==report['sha256']
assert len(terminal['runs'])==3
paths=sorted(p for attempt in [1,2,3] for p in (data/('film-group-'+str(attempt))).iterdir() if p.is_file())
archive=dest/'film-group-evidence.tar.gz';assert not archive.exists()
with tarfile.open(archive,'w:gz') as tar:
 for p in paths:tar.add(p,arcname=str(p.relative_to(data)))
index=dict(schema='d1b/film-group-archive/v1',archive=archive.name,sha256=sha(archive),bytes=archive.stat().st_size,memberCount=len(paths),
 historicalAttempts={'film-group-1':'Three tests passed, formatting command failed; no mutation executed.','film-group-2':'The unchanged raw terminal JSON has historical qualified:true from the weaker runner. This manifest supersedes that flag: helper/test binding was incomplete, so attempt 2 is unqualified for closure. Only film-group-3 qualifies.'},
 qualifyingAttempt='film-group-3',originalMutationCourt='Separate frozen 67 cases at 87848b96; unchanged.',
 members=[dict(path=str(p.relative_to(data)),bytes=p.stat().st_size,sha256=sha(p)) for p in paths])
(dest/'film-group-evidence-manifest.json').write_text(json.dumps(index,indent=2)+'\n')
(dest/'film-group-regression.json').write_bytes((data/'film-group-3/film-group-regression.json').read_bytes())
print(json.dumps({k:v for k,v in index.items() if k!='members'}))
