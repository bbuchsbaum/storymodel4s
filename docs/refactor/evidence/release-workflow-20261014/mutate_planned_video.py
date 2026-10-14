import argparse, difflib, gzip, hashlib, json, re, subprocess, sys
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--clone', type=Path, required=True)
parser.add_argument('--candidate', required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--only')
args = parser.parse_args()
assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=args.clone, text=True).strip() == args.candidate
assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=args.clone, text=True).strip()
args.output.mkdir(parents=True, exist_ok=True)
mutations = [('planned-input-pin',
  'media/src/main/scala/storymodel4s/media/PlannedVideoAcquisition.scala',
  'observed == pinnedInput && observed == manifest.sha256',
  '(observed == pinnedInput || observed != pinnedInput) && observed == manifest.sha256',
  'media',
  'storymodel4s.probe.VideoAcquisitionSuite',
  'planned intervals refuse off-boundary, wrong pins and insufficient multi-frame samples',
  False,
  None),
 ('planned-timebase',
  'media/src/main/scala/storymodel4s/media/PlannedVideoAcquisition.scala',
  'index.timebase == plannedTimebase',
  '(index.timebase == plannedTimebase || index.timebase != plannedTimebase)',
  'media',
  'storymodel4s.probe.VideoAcquisitionSuite',
  'planned intervals refuse off-boundary, wrong pins and insufficient multi-frame samples',
  False,
  None),
 ('planned-byte-budget',
  'media/src/main/scala/storymodel4s/media/PlannedVideoAcquisition.scala',
  'BigInt(geometry.frameBytes) * shared.size <= BigInt(budget)',
  '(BigInt(geometry.frameBytes) * shared.size <= BigInt(budget) || BigInt(geometry.frameBytes) * '
  'shared.size > BigInt(budget))',
  'media',
  'storymodel4s.probe.VideoAcquisitionSuite',
  'planned input refusals leave no published or temporary package',
  False,
  None),
 ('planned-unknown-fields',
  'media/src/main/scala/storymodel4s/media/PlannedVideoAcquisition.scala',
  'j.asObject.exists(_.keys.toSet == names.toSet)',
  'j.asObject.exists(o => names.toSet.subsetOf(o.keys.toSet))',
  'media',
  'storymodel4s.probe.VideoAcquisitionSuite',
  'planned input refusals leave no published or temporary package',
  False,
  None),
 ('planned-sample-spread',
  'media/src/main/scala/storymodel4s/media/PlannedVideoAcquisition.scala',
  'i.toLong * (width - 1) / (selectedCount - 1)',
  '(i.toLong * (width - 1) + selectedCount - 2) / (selectedCount - 1)',
  'media',
  'storymodel4s.probe.VideoAcquisitionSuite',
  'planned intervals preserve exact support and share selected frames',
  False,
  None),
 ('planned-attachment-bytes',
  'media/src/main/scala/storymodel4s/media/captionpackage.scala',
  'present.traverse_(field => reader.textField(c, field))',
  'present.take(0).traverse_(field => reader.textField(c, field))',
  'media',
  'storymodel4s.probe.VideoAcquisitionSuite',
  'planned provenance files remain required and pinned during replay',
  False,
  None),
 ('planned-attachment-trio',
  'media/src/main/scala/storymodel4s/media/captionpackage.scala',
  'present.isEmpty || present.size == provenance.size',
  'present.isEmpty || present.size == provenance.size || present.nonEmpty',
  'media',
  'storymodel4s.probe.VideoAcquisitionSuite',
  'planned provenance files remain required and pinned during replay',
  False,
  None)]
if args.only: mutations=[m for m in mutations if m[0] in args.only.split(',')]
receipts=[]
for name, rel, old, new, project, suite, expected, clean, pattern in mutations:
 path=args.clone/rel; original=path.read_text()
 assert original.count(old)==1, (name, original.count(old))
 mutant=original.replace(old,new)
 patch=''.join(difflib.unified_diff(original.splitlines(True),mutant.splitlines(True),fromfile='a/'+rel,tofile='b/'+rel))
 (args.output/(name+'.patch.gz')).write_bytes(gzip.compress(patch.encode(),mtime=0))
 path.write_text(mutant)
 command=['sbt','-batch','set ThisBuild / tlFatalWarnings := true']
 if clean: command.append(project+'/Test/clean')
 test=project+'/testOnly '+suite
 if pattern: test+=' -- "--tests='+pattern+'"'
 command.append(test)
 log=args.output/(name+'.log')
 try:
  status=subprocess.run([sys.executable,'/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log',str(log),'--cwd',str(args.clone),'--timeout','1200','--',*command],capture_output=True,text=True)
  text=log.read_text()
  failures=[s for s in text.splitlines() if '==> X ' in s]
  totals=[list(map(int,m)) for m in re.findall(r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)',text)]
  valid=status.returncode==1 and len(failures)==1 and expected in failures[0] and totals and totals[-1][1:]==[1,0,totals[-1][3]] and totals[-1][3]>0 and 'Compilation failed' not in text
  receipts.append({'mutation':name,'candidate_sha':args.candidate,'path':rel,'command':command,'exit':status.returncode,'compiled':bool(totals and failures and 'Compilation failed' not in text),'qualified_single_failure':bool(valid),'totals':totals,'named_failure':expected,'failures':failures,'clean_test_recompile':clean,'test_selection':pattern,'source_sha256':hashlib.sha256(mutant.encode()).hexdigest(),'patch_file':name+'.patch.gz','patch_encoding':'gzip','patch_sha256':hashlib.sha256(patch.encode()).hexdigest(),'log_sha256':hashlib.sha256(log.read_bytes()).hexdigest()})
  (args.output/'mutations.json').write_text(json.dumps(receipts,indent=2)+'\n')
  print(name, 'KILLED' if valid else 'INCONCLUSIVE', totals, flush=True)
  if not valid: print(status.stdout[-3500:], flush=True);sys.exit(1)
 finally:
  path.write_text(original)
assert not subprocess.check_output(['git','status','--porcelain'],cwd=args.clone,text=True).strip()
print('Restored exact clean candidate after all mutations',flush=True)
