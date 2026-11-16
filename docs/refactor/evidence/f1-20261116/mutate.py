#!/usr/bin/env python3
"""Run three named compiled F1 mutations in an expendable standalone clone."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--clone', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
root = a.clone.resolve()
if not (root / '.git').is_dir():
    raise SystemExit('requires a standalone clone, not a linked worktree')
if a.output.exists():
    raise SystemExit('output must be new')
a.output.mkdir(parents=True)
path = root / 'document/src/main/scala/storymodel4s/document/filmInput.scala'
original = path.read_text()
mutations = [
    ('ignore-parser-origin',
     'chart.provenance.origin != ChartOrigin.Parser(binding.parser)', 'false',
     'matching expectation cannot disguise a different parser or producing request'),
    ('ignore-sentence-containment', 'sentence.span.contains(ref.span)', 'true',
     'alignment outside its declared sentence is refused by the alignment guard'),
    ('omit-producing-call-identity', 'call(b.producingCall)', '"omitted-producing-call"',
     'selecting a different producing receipt changes input identity even for the same request'),
]
results = []
try:
    for name, before, after, test in mutations:
        if original.count(before) != 1:
            raise RuntimeError(f'expected exactly one mutation site: {name}')
        path.write_text(original.replace(before, after))
        log = a.output / f'{name}.log'
        with log.open('w') as out:
            result = subprocess.run(
                ['sbt', 'set ThisBuild / tlFatalWarnings := true',
                 'documentJVM/testOnly storymodel4s.document.FilmInputAssociationSuite'],
                cwd=root, stdout=out, stderr=subprocess.STDOUT, check=False)
        text = log.read_text()
        totals = re.findall(r'Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)', text)
        if not totals:
            raise RuntimeError(f'no test totals: {name}')
        total, failed, errors, passed = map(int, totals[-1])
        compiled = 'done compiling' in text and not re.search(r'\[error\].*(?:Compilation failed|compileIncremental)', text)
        if result.returncode == 0 or not compiled or failed != 1 or errors != 0 or passed != total - 1 or test not in text:
            raise RuntimeError(f'mutation was not a single named compiled assertion failure: {name}')
        results.append(dict(mutation=name, compiled=compiled, exit=result.returncode,
                            failed_test=test, total=total, failed=failed, passed=passed,
                            log_sha256=hashlib.sha256(log.read_bytes()).hexdigest()))
        print(json.dumps(results[-1]), flush=True)
finally:
    path.write_text(original)
    (a.output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
