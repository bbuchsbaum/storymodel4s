import argparse,difflib,hashlib,json,re,subprocess,sys
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--clone',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
sha='71a79818b225f33dfb6af46273d1e2293f55cbf9'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=a.clone,text=True).strip()==sha
assert not subprocess.check_output(['git','status','--porcelain'],cwd=a.clone,text=True).strip()
a.out.mkdir(parents=True,exist_ok=True)
mutations=[
('raw-to-half','embed-bench/src/main/scala/storymodel4s/bench/channels.scala','Distances.cosineEstimate(a, b).map(_.value)','Distances.cosineEstimate(a, b).map(_.value / 2.0)','concrete raw cosine and weighted prices match independent scalar oracles','^(concrete raw cosine|historical overrides).*'),
('leaf-render-override','embed-bench/src/main/scala/storymodel4s/bench/video/RecallToVideo.scala','segments.map(s => leafRef(s.ordinal) -> s.embedText.getOrElse(s.text))','segments.map(s => leafRef(s.ordinal) -> s.text)','historical overrides empty overrides group labels and lexical-only extras are exact','^(historical overrides|default values).*'),
('group-render-override','embed-bench/src/main/scala/storymodel4s/bench/video/RecallToVideo.scala','groupsInOrder.map(g => groupRef(g.ordinal) -> g.embedText.getOrElse(g.label))','groupsInOrder.map(g => groupRef(g.ordinal) -> g.label)','historical overrides empty overrides group labels and lexical-only extras are exact','^(historical overrides|default values).*'),
('blend-default','embed-bench/src/main/scala/storymodel4s/bench/video/HistoricalVideoDefaults.scala','val blendAlpha: Double = 0.8','val blendAlpha: Double = 1.0','default values and full ladder are the frozen recipe rather than a copied alternate','^(default values|historical over-one).*'),
('candidate-default','embed-bench/src/main/scala/storymodel4s/bench/video/HistoricalVideoDefaults.scala','val candidatesPerLevel: Int = 8','val candidatesPerLevel: Int = 9','default values and full ladder are the frozen recipe rather than a copied alternate','^(default values|historical over-one).*'),
('eligible-weight-scale','align/src/main/scala/storymodel4s/align/cost.scala','presentCost * (wEligible / wPresent)','presentCost','concrete raw cosine and weighted prices match independent scalar oracles','^(concrete raw cosine|historical overrides).*')]
results=[]
for name,rel,old,new,expected,pattern in mutations:
 path=a.clone/rel;original=path.read_text();assert original.count(old)==1,(name,original.count(old));mutant=original.replace(old,new)
 patch=''.join(difflib.unified_diff(original.splitlines(True),mutant.splitlines(True),fromfile='a/'+rel,tofile='b/'+rel));(a.out/(name+'.patch')).write_text(patch);path.write_text(mutant)
 command=['sbt','-batch','-Dstorymodel4s.grakern.build=/Users/bbuchsbaum/code/scala/grakern','set ThisBuild / tlFatalWarnings := true','embedBench/testOnly storymodel4s.bench.video.HistoricalEmbeddingParitySuite -- "--tests='+pattern+'"']
 log=a.out/(name+'.log')
 try:
  status=subprocess.run([sys.executable,'/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log',str(log),'--cwd',str(a.clone),'--timeout','1200','--',*command],capture_output=True,text=True)
  text=log.read_text();failures=[s for s in text.splitlines() if '==> X ' in s]
  rows=[[int(v or 0) for v in m] for m in re.findall(r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)(?:, (?:Ignored|Skipped) (\d+))?',text)]
  valid=status.returncode==1 and len(failures)==1 and expected in failures[0] and rows and rows[-1][1]==1 and rows[-1][2]==0 and rows[-1][3]>0 and 'Compilation failed' not in text
  results.append({'name':name,'candidate_sha':sha,'path':rel,'command':command,'exit':status.returncode,'compiled':bool(rows and failures),'qualified':bool(valid),'totals':rows,'named_failure':expected,'failures':failures,'test_selection':pattern,'source_sha256':hashlib.sha256(mutant.encode()).hexdigest(),'patch_sha256':hashlib.sha256(patch.encode()).hexdigest(),'log_sha256':hashlib.sha256(log.read_bytes()).hexdigest()})
  (a.out/'mutations.json').write_text(json.dumps(results,indent=2)+'\n');print(name,'KILLED' if valid else 'INCONCLUSIVE',rows,flush=True)
  if not valid:print(status.stdout[-3500:],flush=True);sys.exit(1)
 finally:path.write_text(original)
assert not subprocess.check_output(['git','status','--porcelain'],cwd=a.clone,text=True).strip()
print('All six mutations qualified; exact candidate restored',flush=True)
