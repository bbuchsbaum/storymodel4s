#!/usr/bin/env python3
"""Run compiling scanner crosswalk counterexamples in this isolated checkout; restore source bytes.

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
    mutants = [('compress-crop-clock', 'corpus-intake/src/main/scala/storymodel4s/corpus/intake/ScannerCrosswalk.scala', 'run.sample(stored).map(Some(_))', 'run.sample(index).map(Some(_))', 'corpusIntake/testOnly *ScannerCrosswalkSuite'), ('pad-with-acquisition', 'corpus-intake/src/main/scala/storymodel4s/corpus/intake/ScannerCrosswalk.scala', 'case Slot.Padding(_)          => Right(None)', 'case Slot.Padding(_)          => run.sample(0).map(Some(_))', 'corpusIntake/testOnly *ScannerCrosswalkSuite'), ('ignore-reference-clock', 'corpus-intake/src/main/scala/storymodel4s/corpus/intake/ScannerCrosswalk.scala', 'time.reference != reference.digest', 'time.reference != reference.digest && false', 'corpusIntake/testOnly *ScannerCrosswalkSuite'), ('extrapolate-point', 'corpus-intake/src/main/scala/storymodel4s/corpus/intake/ScannerCrosswalk.scala', '!domain.contains(time.seconds)', '!domain.contains(time.seconds) && false', 'corpusIntake/testOnly *ScannerCrosswalkSuite'), ('borrow-primary-timebase', 'corpus-intake/src/main/scala/storymodel4s/corpus/intake/ScannerCrosswalk.scala', 'm.identity == mapping && m.relation.targetAxis == bundle.primaryAxis.id', 'm.identity == mapping', 'corpusIntake/testOnly *ScannerCrosswalkSuite'), ('malformed-positive-scale', 'corpus-intake/src/main/scala/storymodel4s/corpus/intake/ScannerCrosswalk.scala', 'value.denominator > 0L && BigInt(value.numerator).gcd(BigInt(value.denominator)) == 1', 'value.numerator > 0L || (value.denominator > 0L && BigInt(value.numerator).gcd(BigInt(value.denominator)) == 1)', 'corpusIntake/testOnly *ScannerCrosswalkSuite'), ('escape-media-domain', 'corpus-intake/src/main/scala/storymodel4s/corpus/intake/ScannerCrosswalk.scala', 'compare(domain.start, start) >= 0 && compare(domain.endExclusive, end) <= 0', '(compare(domain.start, start) >= 0 && compare(domain.endExclusive, end) <= 0) || true', 'corpusIntake/testOnly *ScannerCrosswalkSuite'), ('trust-layout-times', 'corpus-intake/src/main/scala/storymodel4s/corpus/intake/ScannerCrosswalkJson.scala', 'exact(j, layoutToJson(value))', 'exact(layoutToJson(value), layoutToJson(value))', 'corpusIntake/testOnly *ScannerCrosswalkJsonSuite'), ('publish-partial-artifact', 'pipeline/src/main/scala/storymodel4s/pipeline/ScannerSampleBuild.scala', 'write(staging, bytes)', 'write(path, bytes)', 'pipeline/testOnly *ScannerSampleBuildSuite'), ('round-fractional-ticks', 'corpus-intake/src/main/scala/storymodel4s/corpus/intake/ScannerCrosswalk.scala', 'd <= 0 || n % d != 0', 'd <= 0 || (n % d != 0 && false)', 'corpusIntake/testOnly *ScannerCrosswalkSuite')]
    expected_failures = {'compress-crop-clock': 'crop padding drop and censor preserve original stored time and explicit missingness', 'pad-with-acquisition': 'crop padding drop and censor preserve original stored time and explicit missingness', 'ignore-reference-clock': 'clock and run identities bind the join even when all numeric times agree', 'extrapolate-point': 'negative offsets nonunit scales inverse landmarks and half-open validity are exact', 'borrow-primary-timebase': 'a native-to-native composition cannot borrow the primary playback timebase', 'malformed-positive-scale': 'malformed package-internal rationals cannot establish positive clocks or ordered samples', 'escape-media-domain': 'media timebase is exact and an occurrence declaration cannot extend into a scan break', 'trust-layout-times': 'foreign context tampered sample times missingness authority and duplicate fields refuse', 'publish-partial-artifact': 'malformed jobs and partial output cannot publish an artifact', 'round-fractional-ticks': 'media timebase is exact and an occurrence declaration cannot extend into a scan break'}
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

    baseline, tested = run('before', ['corpusIntake/testOnly *ScannerCrosswalkSuite', 'corpusIntake/testOnly *ScannerCrosswalkJsonSuite', 'pipeline/testOnly *ScannerSampleBuildSuite'], {})
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
    after, tested = run('after', ['corpusIntake/testOnly *ScannerCrosswalkSuite', 'corpusIntake/testOnly *ScannerCrosswalkJsonSuite', 'pipeline/testOnly *ScannerSampleBuildSuite'], {})
    assert after['exit'] == 0 and tested
    assert all((root / name).read_bytes() == data for name, data in originals.items())


if __name__ == '__main__':
    main()
