#!/usr/bin/env python3
"""Run compiling timing counterexamples in this isolated checkout; restore source bytes.

Run with no other sbt process in the checkout. Compilation failures never count as kills.
The final unmutated tests must pass. Receipts bind HEAD, source bytes, command and totals.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--grakern', required=True)
    parser.add_argument('--staging', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    args.out.mkdir(parents=True, exist_ok=False)
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    timing = 'recall/src/main/scala/storymodel4s/recall/timing.scala'
    codec = 'codec/src/main/scala/storymodel4s/codec/recalltiming.scala'
    rt, ct = 'recallJVM/testOnly *RecallTiming*', 'codecJVM/testOnly *RecallTiming*'
    mutants = [
        ('permit-package-forgery', timing, 'final class RecallTiming private (',
         'final class RecallTiming private[recall] (', rt),
        ('admit-duplicate-word', timing, 'duplicate.orElse(foreign)',
         'duplicate.filter(_ => observations.isEmpty).orElse(foreign)', rt),
        ('ignore-entry-clock', timing, 'if e.clock != clock then',
         'if e.clock != clock && observations.isEmpty then', rt),
        ('admit-zero-interval', timing, 'compare(start, end) >= 0', 'compare(start, end) > 0', rt),
        ('float-rounding', timing, 'ExactRational.of(n.toLong, d.toLong)',
         'ExactRational.of(n.toDouble.toLong, d.toLong)', rt),
        ('reject-before-reduction', timing, 'if n.isValidLong && d.isValidLong then',
         'if numerator.isValidLong && denominator.isValidLong && n.isValidLong && d.isValidLong then', rt),
        ('substitute-last-for-first', timing, 'available.headOption,', 'available.lastOption,', rt),
        ('invent-intervals-from-onsets', timing,
         'members.count(_.observation.isInstanceOf[Observation.Interval])',
         'members.count(e => onset(e.observation).isDefined)', rt),
        ('discard-duplicate-json-key', codec, 'uniqueObjectKeys(text)', 'uniqueObjectKeys("{}")', ct),
        ('trust-carried-diagnostics', codec,
         'requireEqual(json, toJson(result), "canonical-record")',
         'requireEqual(toJson(result), toJson(result), "canonical-record")', ct),
    ]
    originals = {name: (root / name).read_bytes() for name in (timing, codec)}
    for name, data in originals.items():
        committed = subprocess.check_output(['git', 'show', f'{head}:{name}'], cwd=root)
        if committed != data:
            raise RuntimeError(f'{name}: commit source before mutation')
    receipts = []

    def run(name, tasks, extra):
        command = ['sbt', '-batch', f'-Dsbt.global.staging={args.staging}',
                   f'-Dstorymodel4s.grakern.build={args.grakern}', *tasks]
        started = time.time()
        log = args.out / f'{name}.log'
        with log.open('wb') as stream:
            result = subprocess.run(command, cwd=root, stdout=stream, stderr=subprocess.STDOUT)
        output = log.read_text()
        totals = re.findall(r'Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)', output)
        tested = bool(totals) and 'Compilation failed' not in output
        killed = result.returncode != 0 and tested and any(int(f) > 0 for _, f, _, _ in totals)
        receipt = dict(name=name, head=head, command=command, exit=result.returncode,
                       elapsed_seconds=time.time()-started, totals=totals, killed=killed, **extra)
        (args.out / f'{name}.json').write_text(json.dumps(receipt, indent=2) + '\n')
        receipts.append(receipt)
        (args.out / 'receipts.json').write_text(json.dumps(receipts, indent=2) + '\n')
        print(json.dumps({'name': name, 'exit': result.returncode, 'killed': killed}), flush=True)
        return receipt, tested

    baseline, tested = run('before', [rt, ct], {})
    if baseline['exit'] != 0 or not tested:
        raise RuntimeError('baseline failed')
    for name, filename, original, replacement, task in mutants:
        path, before = root / filename, originals[filename]
        source = before.decode()
        if source.count(original) != 1:
            raise RuntimeError(f'{name}: expected one mutation site, found {source.count(original)}')
        mutated = source.replace(original, replacement).encode()
        try:
            path.write_bytes(mutated)
            tasks = ['recallJVM/Test/clean', task] if name == 'permit-package-forgery' else [task]
            receipt, _ = run(name, tasks, dict(file=filename,
                original_sha256=hashlib.sha256(before).hexdigest(),
                mutant_sha256=hashlib.sha256(mutated).hexdigest()))
        finally:
            path.write_bytes(before)
        if not receipt['killed']:
            raise RuntimeError(f'{name}: survived or failed without a compiling test failure')
    after, tested = run('after', [rt, ct], {})
    assert after['exit'] == 0 and tested
    assert all((root / name).read_bytes() == data for name, data in originals.items())


if __name__ == '__main__':
    main()
