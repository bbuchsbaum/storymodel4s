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
portable = 'align/src/main/scala/storymodel4s/align/surfaceembedding.scala'
onnx = 'embed-onnx/src/main/scala/storymodel4s/embed/onnx/OnnxMappingRegistration.scala'
mutations = [
 ('unit-helper-door', 'align/src/main/scala/storymodel4s/align/content.scala',
  'private[align] def canonicalUnit(', 'def canonicalUnit(',
  'alignJVM', 'surfaceembeddingconsumer.SurfaceEmbeddingBoundarySuite',
  'canonical unit projection helper is package private', True, None),
 ('target-helper-door', 'align/src/main/scala/storymodel4s/align/content.scala',
  'private[align] def canonicalTarget(', 'def canonicalTarget(',
  'alignJVM', 'surfaceembeddingconsumer.SurfaceEmbeddingBoundarySuite',
  'canonical target projection helper is package private', True, None),
 ('endpoint-association', portable,
  'documents.map(_._1) == context.documents.map(_._2)',
  'documents.map(_._1) == documents.map(_._1)',
  'alignJVM', 'storymodel4s.align.SurfaceEmbeddingSuite',
  'complete ordered endpoint association refuses missing extra and reordered keys', False, None),
 ('vector-geometry', portable, 'v.dimension == space.dimension', 'v.dimension == v.dimension',
  'alignJVM', 'storymodel4s.align.SurfaceEmbeddingSuite',
  'endpoint vectors must match their checked role geometry', False, None),
 ('empty-input-policy', portable, 'text.nonEmpty || outcome == Right(Estimate.Ineligible)',
  'text.isEmpty || text.nonEmpty || outcome == Right(Estimate.Ineligible)',
  'alignJVM', 'storymodel4s.align.SurfaceEmbeddingSuite',
  'literal empty text must be ineligible and cannot acquire a vector', False, None),
 ('conflicting-endpoints', portable,
  'documents.groupMap(_._1)(_._2).values.forall(_.distinct.size <= 1)',
  'documents.groupMap(_._1)(_._2).values.forall(_.distinct.size >= 0)',
  'alignJVM', 'storymodel4s.align.SurfaceEmbeddingSuite',
  'identical rendered keys refuse conflicting outcomes rather than insertion order', False, None),
 ('shared-surface-context', 'align/src/main/scala/storymodel4s/align/evidence.scala',
  'strict.surfaceContext != costModel.surfaceSession.map(_.context) ||\n      ', '',
  'alignJVM', 'storymodel4s.align.SurfaceEmbeddingSuite',
  'matching numerical tables cannot substitute a different source text context', False, None),
 ('registration-context', onnx,
  'if expectedContext != context then Left(OnnxMappingRefusal.Context)\n      else if expectedConfig != config',
  'if expectedConfig != config',
  'embedOnnx', 'onnxmappingconsumer.OnnxMappingRegistrationSuite',
  'exact registration replay binds source bytes beyond equal view fields and payloads', False,
  '^(exact registration replay binds source bytes|registration replay refuses changed configuration).*'),
 ('registration-configuration', onnx,
  'else if expectedConfig != config then Left(OnnxMappingRefusal.Configuration)\n      ', '',
  'embedOnnx', 'onnxmappingconsumer.OnnxMappingRegistrationSuite',
  'registration replay refuses changed configuration model and provider', False, None),
 ('raw-replay-door', onnx, '\n  private def replay(\n', '\n  def replay(\n',
  'embedOnnx', 'onnxmappingconsumer.OnnxMappingBoundarySuite',
  'raw recording replay cannot bypass bound registration context and configuration', True, None),
 ('executed-constructor-door', onnx,
  'final class Registered private[OnnxMappingRegistration] (', 'final class Registered (',
  'embedOnnx', 'onnxmappingconsumer.OnnxMappingBoundarySuite',
  'registered constructor is closed outside the defining package', True, '^(?!registered apply).*'),
 ('executed-apply-door', onnx,
  'final class Registered private[OnnxMappingRegistration] (', 'final class Registered (',
  'embedOnnx', 'onnxmappingconsumer.OnnxMappingBoundarySuite',
  'registered apply is closed outside the defining package', True, '^(?!registered constructor).*'),
]
if args.only: mutations=[m for m in mutations if m[0] in args.only.split(',')]
receipts=[]
for name, rel, old, new, project, suite, expected, clean, pattern in mutations:
 path=args.clone/rel; original=path.read_text()
 assert original.count(old)==1, (name, original.count(old))
 mutant=original.replace(old,new)
 patch=''.join(difflib.unified_diff(original.splitlines(True),mutant.splitlines(True),fromfile='a/'+rel,tofile='b/'+rel))
 (args.output/(name+'.patch')).write_text(patch)
 path.write_text(mutant)
 command=['sbt','-batch','-Dstorymodel4s.grakern.build=/Users/bbuchsbaum/code/scala/grakern','set ThisBuild / tlFatalWarnings := true']
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
