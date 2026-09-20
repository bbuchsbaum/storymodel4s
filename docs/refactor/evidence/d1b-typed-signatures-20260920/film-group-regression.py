"""Separate Film Festival point-group regression; never amends the frozen 67 court."""
from pathlib import Path
import argparse,importlib.util,json,re,subprocess
spec=importlib.util.spec_from_file_location('qualification',Path(__file__).with_name('qualify.py'))
q=importlib.util.module_from_spec(spec);spec.loader.exec_module(q)
parser=argparse.ArgumentParser();parser.add_argument('--candidate',required=True);expected=parser.parse_args().candidate
assert re.fullmatch('[0-9a-f]{40}',expected) and q.revision==expected
assert (q.repo/'.git').is_dir() and q.clean()
helper=Path(__file__).with_name('qualify.py')
helper_path='docs/refactor/evidence/d1b-typed-signatures-20260920/qualify.py'
assert helper.read_bytes()==subprocess.check_output(['git','show',expected+':'+helper_path],cwd=q.repo)
test_path='embed-bench/src/test/scala/storymodel4s/bench/filmfestival/FilmFestivalSuite.scala'
test_bytes=(q.repo/test_path).read_bytes()
assert test_bytes==subprocess.check_output(['git','show',expected+':'+test_path],cwd=q.repo)
assert Path(q.pin).is_absolute()
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=q.pin,text=True).strip()=='0329c43c88a0b71e9aa4456723bb16bac2fa3841'
assert not subprocess.check_output(['git','status','--porcelain'],cwd=q.pin)
suite='storymodel4s.bench.filmfestival.FilmFestivalSuite';selected=[('embedBench',[suite])]
reject='independent film blocks never acquire a fabricated world clock'
control='accepting control: an interval-only film group retains its exact extent'
names={reject,control,'requested ONNX fails closed unless both artifacts exist'}
path=q.repo/'embed-bench/src/main/scala/storymodel4s/bench/video/RecallToVideo.scala'
saved=path.read_bytes()
old='''              else
                PlaybackInstant
                  .on(axis, start)
                  .toOption
                  .map(at => groupRef(g.ordinal) -> MediaLocus.Instant(partId, at))'''
assert saved.decode().count(old)==1
mutant=saved.decode().replace(old,'              else None').encode()
record=dict(schema='d1b/film-group-regression/v1',codeRevision=expected,qualified=False,
 originalSha256=q.sha(saved),mutantSha256=q.sha(mutant),path=str(path.relative_to(q.repo)),
 testSourcePath=test_path,testSourceSha256=q.sha(test_bytes),qualificationHelperSha256=q.sha(helper.read_bytes()),
 runnerSha256=q.sha(Path(__file__).read_bytes()),runs={})
terminal=q.out/'film-group-regression.json';assert not terminal.exists()
def persist():terminal.write_text(json.dumps(record,indent=2)+'\n')
def run(label,mutated=False):
 q.clear_reports(selected)
 receipt=q.run(label,q.prefix+['embedBench/testOnly '+suite]+([] if mutated else ['scalafmtCheckAll']),False)
 reports,values=q.capture_reports(label,receipt,selected)
 row=dict(receipt=receipt,rawReceiptSha256=q.sha((q.out/(label+'.json')).read_bytes()),reports=reports,outcomes=values)
 record['runs'][label]=row;persist()
 assert set(values)=={suite} and set(values[suite])==names
 assert q.total_outcomes(values)==receipt['aggregateTestCounts']
 assert receipt['exitCode']==(1 if mutated else 0)
 assert values[suite]=={n:('failed' if mutated and n==reject else 'passed') for n in names}
 if mutated:
  assert re.search(r'compiling \d+ Scala sources? to '+re.escape(str(q.repo/'embed-bench/target/scala-3.7.4/classes')),q.verify_log(receipt))
 else:assert receipt['cleanBefore'] and receipt['cleanAfter'] and q.clean()
try:
 run('film-group-control')
 assert q.clean();record['baselineCleanBeforeMutation']=True
 try:
  path.write_bytes(mutant);run('film-group-deletion',True)
 finally:
  path.write_bytes(saved)
  record['restoredSha256']=q.sha(path.read_bytes());record['restoredClean']=q.clean();persist()
 assert record['restoredSha256']==record['originalSha256'] and record['restoredClean']
 run('film-group-restored')
 assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=q.repo,text=True).strip()==expected
 assert (q.repo/test_path).read_bytes()==test_bytes and helper.read_bytes()==subprocess.check_output(['git','show',expected+':'+helper_path],cwd=q.repo)
 record['qualified']=True
finally:persist()
