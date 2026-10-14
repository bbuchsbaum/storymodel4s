import argparse, difflib, hashlib, json, re, subprocess, sys
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
mutations = [('timed-full-identity', 'align/src/main/scala/storymodel4s/align/surfacerender.scala', 'sourceIdentity == c.sourceIdentity && queries == c.queries', '(sourceIdentity == c.sourceIdentity || sourceIdentity != c.sourceIdentity) && queries == c.queries', 'alignJVM', 'storymodel4s.probes.TimedSurfaceRenderingSuite', 'non-rendered timed declarations remain context identity even with the same view and payload', False, None), ('timed-unicode', 'align/src/main/scala/storymodel4s/align/surfacerender.scala', 'if unicode(text) then Right(()) else Left(SurfaceRenderingRefusal.InvalidUnicode)', 'if unicode(text) || !unicode(text) then Right(()) else Left(SurfaceRenderingRefusal.InvalidUnicode)', 'alignJVM', 'storymodel4s.probes.TimedSurfaceRenderingSuite', 'unpaired Unicode in a timed rendering payload is refused', False, None), ('nomination-cut', 'embed-onnx/src/main/scala/storymodel4s/embed/onnx/OnnxMappingRegistration.scala', '.generate(session, effective, config.selectedLevel)', '.generate(session, effective, None)', 'embedOnnx', 'onnxmappingconsumer.OnnxMappingRegistrationSuite', 'selected cut precedes nomination accounting and pricing while retaining every document', False, None), ('replay-cut-identity', 'embed-onnx/src/main/scala/storymodel4s/embed/onnx/OnnxMappingRegistration.scala', 'pricingSignature(pricing),\n      selectedLevel', 'pricingSignature(pricing),\n      None', 'embedOnnx', 'onnxmappingconsumer.OnnxMappingRegistrationSuite', 'selection remains replay configuration even when a flat source gives identical evidence', False, '^(selection remains replay configuration|exact replay after encoder closure).*'), ('batch-provider-identity', 'pipeline/src/main/scala/storymodel4s/pipeline/MappingPipeline.scala', '++ prepared.outcomes.map(_.attempt.hex) ++ providerBinding', '++ prepared.outcomes.map(_.attempt.hex) ++ providerBinding.take(0)', 'pipeline', 'storymodel4s.pipeline.MappingSettingsSuite', 'key rotation changes execution and batch identity while relocation preserves them', False, None), ('local-file-boundary', 'pipeline/src/main/scala/storymodel4s/pipeline/MappingSettings.scala', '!relative.isAbsolute && path.startsWith(base) &&\n          Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&\n          path.toRealPath().startsWith(base)', 'Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)', 'pipeline', 'storymodel4s.pipeline.MappingSettingsSuite', 'missing short and external keys refuse and diagnostics never echo secret text', False, None)]
if args.only: mutations=[m for m in mutations if m[0] in args.only.split(',')]
receipts=[]
for name, rel, old, new, project, suite, expected, clean, pattern in mutations:
 path=args.clone/rel; original=path.read_text()
 assert original.count(old)==1, (name, original.count(old))
 mutant=original.replace(old,new)
 patch=''.join(difflib.unified_diff(original.splitlines(True),mutant.splitlines(True),fromfile='a/'+rel,tofile='b/'+rel))
 (args.output/(name+'.patch')).write_text(patch)
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
  receipts.append({'mutation':name,'candidate_sha':args.candidate,'path':rel,'command':command,'exit':status.returncode,'compiled':bool(totals and failures and 'Compilation failed' not in text),'qualified_single_failure':bool(valid),'totals':totals,'named_failure':expected,'failures':failures,'clean_test_recompile':clean,'test_selection':pattern,'source_sha256':hashlib.sha256(mutant.encode()).hexdigest(),'patch_sha256':hashlib.sha256(patch.encode()).hexdigest(),'log_sha256':hashlib.sha256(log.read_bytes()).hexdigest()})
  (args.output/'mutations.json').write_text(json.dumps(receipts,indent=2)+'\n')
  print(name, 'KILLED' if valid else 'INCONCLUSIVE', totals, flush=True)
  if not valid: print(status.stdout[-3500:], flush=True);sys.exit(1)
 finally:
  path.write_text(original)
assert not subprocess.check_output(['git','status','--porcelain'],cwd=args.clone,text=True).strip()
print('Restored exact clean candidate after all mutations',flush=True)
