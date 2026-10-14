#!/usr/bin/env python3
"""Local compiled controls for the pinned Scala 3 compatibility analyzer; never publishes."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


def baseline_guard(version, baselines):
    """Post-1.0 candidates require the first stable baseline; no silent empty comparison."""
    match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)(?:[-+].*)?', version)
    if not match:
        raise ValueError('unrecognized release version')
    numbers = tuple(map(int, match.groups()))
    if numbers > (1, 0, 0) and '1.0.0' not in baselines:
        raise ValueError('post-baseline release requires 1.0.0')


def main():
    out = Path(sys.argv[1]).resolve()
    out.mkdir(parents=True, exist_ok=False)
    cache = Path(os.environ.get('COURSIER_CACHE', Path.home() / 'Library/Caches/Coursier/v1'))

    def jar(name):
        hits = sorted(cache.rglob(name))
        if not hits:
            raise FileNotFoundError(f'Pinned cached dependency unavailable: {name}')
        # Multiple repository mirrors are fine only if their bytes agree.
        hashes = {hashlib.sha256(p.read_bytes()).hexdigest() for p in hits}
        if len(hashes) != 1:
            raise ValueError(f'Conflicting cached bytes: {name}')
        return str(hits[0])

    scala = [jar('scala3-library_3-3.7.4.jar'), jar('scala-library-2.13.16.jar')]
    compiler = [jar(n) for n in ['scala3-compiler_3-3.7.4.jar', 'scala3-interfaces-3.7.4.jar',
        'tasty-core_3-3.7.4.jar', 'scala-asm-9.8.0-scala-1.jar', 'compiler-interface-1.10.7.jar',
        'jline-reader-3.29.0.jar', 'jline-terminal-3.29.0.jar', 'jline-terminal-jni-3.29.0.jar']] + scala
    mima = [jar(n) for n in ['tasty-mima_3-1.4.0.jar', 'tasty-mima-interface-1.4.0.jar',
                             'tasty-query_3-1.6.1.jar']] + scala
    receipts = []

    def run(label, args, expected=0):
        result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (out / (label + '.log')).write_bytes(result.stdout)
        receipts.append({'name': label, 'exit': result.returncode,
                         'logSha256': hashlib.sha256(result.stdout).hexdigest()})
        if result.returncode != expected:
            raise RuntimeError(f'{label}: exit {result.returncode}; see retained log')
        return result.stdout.decode()

    baseline_guard('1.0.0', [])
    baseline_guard('1.0.1', ['1.0.0'])
    for version in ['1.0.1', '1.1.0']:
        try:
            baseline_guard(version, [])
        except ValueError as e:
            assert str(e) == 'post-baseline release requires 1.0.0'
        else:
            raise AssertionError('empty baseline was accepted')
    try:
        with tempfile.TemporaryDirectory(prefix='storymodel4s-mima-') as scratch:
            work = Path(scratch)
            run('compile-runner', ['javac', '-cp', os.pathsep.join(mima), '-d', str(work),
                                  str(Path(__file__).with_name('Runner.java'))])
            for name, method in [('old', 'def keep(n: Int): String = n.toString'),
                                 ('same', 'def keep(n: Int): String = n.toString'),
                                 ('mutant', 'def changed(n: Long): String = n.toString')]:
                src = work / (name + '.scala')
                src.write_text('package rehearsal\nclass StableApi { ' + method + ' }\n')
                classes = work / name
                classes.mkdir()
                run('compile-' + name, ['java', '-Xmx1g', '-XX:ActiveProcessorCount=2', '-cp',
                    os.pathsep.join(compiler), 'dotty.tools.dotc.Main', '-classpath', os.pathsep.join(scala),
                    '-d', str(classes), str(src)])
                run('jar-' + name, ['jar', 'cf', str(work / (name + '.jar')), '-C', str(classes), '.'])
            command = ['java', '-Xmx1g', '-XX:ActiveProcessorCount=2',
                       '-Dmima.jars=' + os.pathsep.join(mima),
                       '-Danalysis.dependencies=' + os.pathsep.join(scala),
                       '-cp', os.pathsep.join([str(work)] + mima), 'Runner', str(work / 'old.jar')]
            run('unchanged', command + [str(work / 'same.jar')])
            result = run('removed-stable-method', command + [str(work / 'mutant.jar')], 1)
            assert result.strip() == 'MissingTermMember rehearsal.StableApi.keep', result
        receipt = {'status': 'passed', 'analyzer': 'TASTy MiMa 1.4.0', 'scala': '3.7.4',
                   'baselineControls': ['first-empty-allowed', 'later-1.0.0-accepted',
                                        '1.0.1-empty-refused', '1.1.0-empty-refused'],
                   'filters': [], 'scope': 'local Scala 3 rehearsal; no published baseline or platform compatibility claim',
                   'commands': receipts, 'dependencies': {Path(p).name: hashlib.sha256(Path(p).read_bytes()).hexdigest()
                                                         for p in sorted(set(compiler + mima))}}
        (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print('PASS: unchanged contract, named removed method, and empty-baseline controls')
    finally:
        (out / 'commands.json').write_text(json.dumps(receipts, indent=2) + '\n')


if __name__ == '__main__':
    main()
