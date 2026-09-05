#!/usr/bin/env python3
"""Run named scorer mutants in isolated copies; require the intended assertion to fail."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[3]
G='tools/recall-study/filmfest_gold_film.py'
A='tools/recall-study/agreement.py'
E='tools/corpus/filmfest_experiment.py'
MUTANTS=[
 ('parse-gold-quotes-as-csv', G, 'def load_gold(path):\n    by = collections.defaultdict(list)\n    with open(path, newline="", encoding="utf-8") as fh:\n        for r in csv.DictReader(fh, delimiter="\\t", quoting=csv.QUOTE_NONE):', 'def load_gold(path):\n    by = collections.defaultdict(list)\n    with open(path, newline="", encoding="utf-8") as fh:\n        for r in csv.DictReader(fh, delimiter="\\t"):', 'test_gold_reader_keeps_rows_after_an_unclosed_literal_quote'),
 ('empty-pair-success', A, 'if total == 0:', 'if total < 0:', 'test_no_text_pairs_is_undefined_not_perfect_agreement'),
 ('parse-literal-quotes-as-csv',G,'sub = path.stem.removeprefix("recall-map-")\n        with path.open(newline="", encoding="utf-8") as fh:\n            for r in csv.DictReader(fh, delimiter="\\t", quoting=csv.QUOTE_NONE):',
  'sub = path.stem.removeprefix("recall-map-")\n        with path.open(newline="", encoding="utf-8") as fh:\n            for r in csv.DictReader(fh, delimiter="\\t"):',
  'test_population_preserves_literal_quotes_and_rows'),
 ('overwrite-cartoon-duration',G,'durations[code] += hi - lo','durations[code] = hi - lo','test_both_cartoon_durations_survive'),
 ('closed-film-end',G,'lo <= t < hi','lo <= t <= hi','test_boundary_belongs_to_next_film'),
 ('drop-unanchored',G,'                code, name = None, None','                if not r.get("startSeconds"): continue\n                code, name = None, None','test_unanchored_stays_in_eligible_denominator'),
 ('ignore-cluster-resampling',G,'sample = rng.choices(rows, k=len(rows))','sample = rows','test_cluster_bootstrap_retains_participant_dependence'),
 ('ignore-unit-signature',G,'(a[k]["signature"],a[k]["goldMovie"]) != (b[k]["signature"],b[k]["goldMovie"])','a[k]["goldMovie"] != b[k]["goldMovie"]','test_pairing_refuses_different_recall_identity'),
 ('drop-point-gold',G,'return next((g for g in gold_rows if g["start"] <= a0 < g["end"]), None)','return None','test_gold_overlap_and_single_word_boundaries'),
 ('admit-nan-offset',A,'if not parts or any(not math.isfinite(v) or v < 0 for v in parts.values()):','if not parts:','test_nonfinite_offsets_refused'),
 ('medoid-arbitrary-last',E,'return min(zip(texts, values), key=lambda p: (-p[1], hashlib.sha256(p[0].encode()).hexdigest()))[0]','return texts[-1]','test_medoid_uses_consensus_and_is_permutation_invariant'),
]
results=[]
for name,path,old,new,test in MUTANTS:
    with tempfile.TemporaryDirectory(prefix='filmfestival-mutant-') as d:
        root=Path(d)
        for rel in ('tools/corpus','tools/recall-study'):
            shutil.copytree(ROOT/rel,root/rel,ignore=shutil.ignore_patterns('__pycache__'))
        f=root/path
        source=f.read_text()
        if source.count(old)!=1:
            raise ValueError(f'{name}: mutation site count {source.count(old)}')
        f.write_text(source.replace(old,new))
        cmd=[sys.executable,'-m','unittest',f'test_filmfestival.FilmFestivalTests.{test}','-v']
        run=subprocess.run(cmd,cwd=root/'tools/recall-study/tests',text=True,capture_output=True)
        killed=run.returncode!=0 and f'FAIL: {test}' in run.stderr
        results.append({'mutant':name,'test':test,'exit':run.returncode,'killedByAssertion':killed})
        if not killed:
            print(run.stdout+run.stderr)
            raise RuntimeError(f'{name}: not killed by the intended assertion')
print(json.dumps({'mutants':results,'killed':len(results),'total':len(MUTANTS)},indent=2))
