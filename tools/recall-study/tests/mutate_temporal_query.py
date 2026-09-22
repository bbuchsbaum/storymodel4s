#!/usr/bin/env python3
"""Run compiling conditional temporal query counterexamples in this isolated checkout; restore source bytes.

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
    mutants = [('replace-union-with-hull', 'align/src/main/scala/storymodel4s/align/temporalquery.scala', 'val duration = s.intervals.map(i => BigInt(i.endExclusive) - BigInt(i.start)).sum', 'val duration = BigInt(s.bounds._2) - BigInt(s.bounds._1)', 'alignJVM/testOnly *TemporalQuerySuite'), ('round-large-ticks', 'align/src/main/scala/storymodel4s/align/temporalquery.scala', 'BigInt(i.endExclusive.min(j.endExclusive)) - BigInt(i.start.max(j.start))', 'BigInt(i.endExclusive.min(j.endExclusive).toDouble.toLong) - BigInt(i.start.max(j.start).toDouble.toLong)', 'alignJVM/testOnly *TemporalQuerySuite'), ('renormalize-row', 'align/src/main/scala/storymodel4s/align/temporalquery.scala', 'new Component(alternative, weight, location)', 'new Component(alternative, weight / entries.map(_._2).sum, location)', 'alignJVM/testOnly *TemporalQuerySuite'), ('hide-external', 'align/src/main/scala/storymodel4s/align/temporalquery.scala', 'ExternalState.values.toVector.flatMap', 'ExternalState.values.toVector.filterNot(_ == ExternalState.Intrusion).flatMap', 'alignJVM/testOnly *TemporalQuerySuite'), ('ignore-clipped-occurrence', 'align/src/main/scala/storymodel4s/align/temporalquery.scala', 'support.nodes.exists(_.excluded.nonEmpty)', 'support.nodes.exists(n => n.excluded.nonEmpty && false)', 'alignJVM/testOnly *TemporalQuerySuite'), ('admit-other-coordinates', 'align/src/main/scala/storymodel4s/align/temporalquery.scala', '!support.nodes.forall(n => oneAxis(n.ref))', '!support.nodes.forall(n => oneAxis(n.ref)) && false', 'alignJVM/testOnly *TemporalQuerySuite'), ('discard-omitted-weight', 'align/src/main/scala/storymodel4s/align/temporalquery.scala', 'ordered.drop(k).map(_.component.weight).sum', 'ordered.drop(k).map(_.component.weight).sum * 0.0', 'alignJVM/testOnly *TemporalQuerySuite'), ('trust-wire-mass', 'codec/src/main/scala/storymodel4s/codec/temporalquery.scala', 'exact(json, toJson(result), "canonical-record")', 'exact(toJson(result), toJson(result), "canonical-record")', 'codecJVM/testOnly *TemporalQueryCodecSuite'), ('decimal-mixture-decoder', 'codec/src/main/scala/storymodel4s/codec/temporalquery.scala', 'field[Double](j, "interval_share")', 'field[String](j, "interval_share").map(_.toDouble)', 'codecJVM/testOnly *TemporalQueryCodecSuite'), ('platform-dependent-id', 'align/src/main/scala/storymodel4s/align/temporalquery.scala', 'CanonicalDouble.render(share)', 'CanonicalDouble.render(share) + share.toString', 'codecJVM/testOnly *TemporalQueryCodecSuite')]
    expected_failures = {'replace-union-with-hull': 'known disconnected support bounds and uniform mass retain unavailable and external mass', 'round-large-ticks': 'large adjacent ticks remain distinct before ratio arithmetic', 'renormalize-row': 'admitted row discrepancy is retained and abstention does not discard measures', 'hide-external': 'known disconnected support bounds and uniform mass retain unavailable and external mass', 'ignore-clipped-occurrence': 'selecting one of repeated occurrences never reallocates a clipped target weight', 'admit-other-coordinates': 'native and composed duplicates cannot imply occurrence mass', 'discard-omitted-weight': 'disjoint bins and top-k retain cropped omitted external and unresolved mass', 'trust-wire-mass': 'wire mass domains axis unknown fields and duplicate keys cannot fabricate authority', 'decimal-mixture-decoder': 'mixture policies roundtrip canonical bits and have portable identity witnesses', 'platform-dependent-id': 'mixture policies roundtrip canonical bits and have portable identity witnesses'}
    originals = {name: (root / name).read_bytes() for name in sorted({m[1] for m in mutants})}
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
        failures = [line for line in output.splitlines() if line.startswith('==> X ')]
        expected = expected_failures.get(name)
        named_failure = expected is not None and any(re.sub(r'[^A-Za-z0-9 ]', '', expected) in re.sub(r'[^A-Za-z0-9 ]', '', line) for line in failures)
        killed = result.returncode != 0 and tested and named_failure and any(int(f) > 0 for _, f, _, _ in totals)
        receipt = dict(name=name, head=head, command=command, exit=result.returncode,
                       elapsed_seconds=time.time()-started, totals=totals, killed=killed,
                       expected_failure=expected, failure_lines=failures, **extra)
        (args.out / f'{name}.json').write_text(json.dumps(receipt, indent=2) + '\n')
        receipts.append(receipt)
        (args.out / 'receipts.json').write_text(json.dumps(receipts, indent=2) + '\n')
        print(json.dumps({'name': name, 'exit': result.returncode, 'killed': killed}), flush=True)
        return receipt, tested

    baseline, tested = run('before', ['alignJVM/testOnly *TemporalQuerySuite', 'codecJVM/testOnly *TemporalQueryCodecSuite'], {})
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
            tasks = [task]
            receipt, _ = run(name, tasks, dict(file=filename,
                original_sha256=hashlib.sha256(before).hexdigest(),
                mutant_sha256=hashlib.sha256(mutated).hexdigest()))
        finally:
            path.write_bytes(before)
        if not receipt['killed']:
            raise RuntimeError(f'{name}: survived or failed without its named compiling test failure')
    after, tested = run('after', ['alignJVM/testOnly *TemporalQuerySuite', 'codecJVM/testOnly *TemporalQueryCodecSuite'], {})
    assert after['exit'] == 0 and tested
    assert all((root / name).read_bytes() == data for name, data in originals.items())


if __name__ == '__main__':
    main()
