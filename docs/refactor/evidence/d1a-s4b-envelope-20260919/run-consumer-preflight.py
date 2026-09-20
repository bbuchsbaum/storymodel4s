from pathlib import Path
import hashlib,json,subprocess,sys,time,re
repo=Path('/private/tmp/storymodel4s-film-consumer-20260919-9zqdg50z/storyatlas4s');out=Path('/Users/bbuchsbaum/code/scala/storymodel4s/data/study/d1a-s4b-20260919')
label=sys.argv[1];tasks=sys.argv[2:];log=out/(label+'.log');assert not log.exists()
def git(*a):return subprocess.check_output(['git',*a],cwd=repo,text=True).strip()
revision=git('rev-parse','HEAD');before=git('status','--porcelain');assert not before
cmd=['sbt','-batch','-Dstoryatlas4s.storymodel4s.build=/private/tmp/storymodel4s-film-consumer-20260919-9zqdg50z/storymodel4s','-Dstoryatlas4s.intaglio.build=/private/tmp/storymodel4s-film-consumer-20260919-9zqdg50z/intaglio','-Dstorymodel4s.grakern.build=/private/tmp/storymodel4s-film-consumer-20260919-9zqdg50z/grakern',*tasks];started=time.time()
with log.open('w') as f:r=subprocess.run(cmd,cwd=repo,stdout=f,stderr=subprocess.STDOUT)
lines=log.read_text().splitlines();totals=[s for s in lines if s.startswith(('[info] Passed: Total','[error] Failed: Total'))];counts=dict.fromkeys(['Total','Failed','Errors','Passed','Skipped'],0)
for s in totals:
 for k,v in re.findall(r'(Total|Failed|Errors|Passed|Skipped) (\d+)',s):counts[k]+=int(v)
receipt=dict(codeRevision=revision,command=cmd,cwd=str(repo),startedEpochSeconds=started,seconds=time.time()-started,exitCode=r.returncode,cleanBefore=not before,cleanAfter=not git('status','--porcelain'),logPath=str(log),logSha256=hashlib.sha256(log.read_bytes()).hexdigest(),testTotals=totals,aggregateTestCounts=counts)
(out/(label+'.json')).write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2));sys.exit(r.returncode)
