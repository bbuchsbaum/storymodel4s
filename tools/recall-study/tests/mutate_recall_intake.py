#!/usr/bin/env python3
"""Run compiling production intake and support counterexamples in this isolated checkout; restore source bytes.

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
    mutants = [('ignore-byte-pin', 'corpus-intake/src/main/scala/storymodel4s/corpus/intake/RecallTimingIntake.scala', 'Checksum.ofBytes(snapshot) == expected', 'Checksum.ofBytes(snapshot) == expected || snapshot.nonEmpty', 'corpusIntake/testOnly *RecallTimingIntakeSuite'), ('use-historical-column', 'corpus-intake/src/main/scala/storymodel4s/corpus/intake/RecallTimingIntake.scala', 'cells(columns.onset)', 'cells(1)', 'corpusIntake/testOnly *RecallTimingIntakeSuite'), ('ignore-word-spans', 'corpus-intake/src/main/scala/storymodel4s/corpus/intake/RecallTimingIntake.scala', 'spans == inventory.words.map(_.span)', 'spans == inventory.words.map(_.span) || spans.nonEmpty', 'corpusIntake/testOnly *RecallTimingIntakeSuite'), ('discard-excluded-records', 'corpus-intake/src/main/scala/storymodel4s/corpus/intake/RecallTimingIntake.scala', 'new Result(timing, records, columns, snapshot.length)', 'new Result(timing, records.filter(_.binding != RecordBinding.ExcludedBlankWord), columns, snapshot.length)', 'corpusIntake/testOnly *RecallTimingIntakeSuite'), ('erase-grandchildren', 'align/src/main/scala/storymodel4s/align/temporalsupport.scala', 'pending = next.toList ::: pending.tail', 'pending = pending.tail', 'alignJVM/testOnly *TemporalSupportSuite'), ('admit-cycle', 'align/src/main/scala/storymodel4s/align/temporalsupport.scala', 'Either.cond(!cycle, (), Refusal.CyclicHierarchy)', 'Either.cond(true, (), Refusal.CyclicHierarchy)', 'alignJVM/testOnly *TemporalSupportSuite'), ('erase-occurrence-provenance', 'align/src/main/scala/storymodel4s/align/temporalsupport.scala', 'Either.cond(valid, (), Refusal.OccurrenceEvidenceMismatch)', 'Either.cond(valid || evidence.anchors.length > 0, (), Refusal.OccurrenceEvidenceMismatch)', 'alignJVM/testOnly *TemporalSupportSuite'), ('include-exclusive-point', 'align/src/main/scala/storymodel4s/align/temporalsupport.scala', 'support.points.filter(p => window.contains(p.at))', 'support.points.filter(p => p.at >= window.start && p.at <= window.endExclusive)', 'alignJVM/testOnly *TemporalSupportSuite'), ('trust-wire-geometry', 'codec/src/main/scala/storymodel4s/codec/temporalsupport.scala', 'exact(json, toJson(result), "canonical-record")', 'exact(toJson(result), toJson(result), "canonical-record")', 'codecJVM/testOnly *TemporalSupportCodecSuite'), ('write-final-marker-directly', 'pipeline/src/main/scala/storymodel4s/pipeline/RecallTimingBuild.scala', 'write(pending, (printer.print(manifest) + "\\n").getBytes(StandardCharsets.UTF_8))', 'write(output.resolve("manifest.json"), (printer.print(manifest) + "\\n").getBytes(StandardCharsets.UTF_8))', 'pipeline/testOnly *RecallTimingBuildSuite')]
    expected_failures = {'ignore-byte-pin': 'byte pins full transcript graph and exact parsed spans', 'use-historical-column': 'explicit clock selection preserves all records words and exact missingness', 'ignore-word-spans': 'byte pins full transcript graph and exact parsed spans', 'discard-excluded-records': 'explicit clock selection preserves all records words and exact missingness', 'erase-grandchildren': 'transitive unlocated descendants survive located intermediate groups', 'admit-cycle': 'self-parent and two-node cycles refuse before descendant traversal', 'erase-occurrence-provenance': 'overlapping part mappings cannot turn foreign or mixed evidence into a selected occurrence', 'include-exclusive-point': 'repeated occurrences partition supplied unions and point boundaries', 'trust-wire-geometry': 'query wire rejects forged geometry completeness unknown fields and duplicate keys', 'write-final-marker-directly': 'interrupted completion-marker write leaves no final manifest'}
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
        named_failure = expected is not None and any(expected in line for line in failures)
        killed = result.returncode != 0 and tested and named_failure and any(int(f) > 0 for _, f, _, _ in totals)
        receipt = dict(name=name, head=head, command=command, exit=result.returncode,
                       elapsed_seconds=time.time()-started, totals=totals, killed=killed,
                       expected_failure=expected, failure_lines=failures, **extra)
        (args.out / f'{name}.json').write_text(json.dumps(receipt, indent=2) + '\n')
        receipts.append(receipt)
        (args.out / 'receipts.json').write_text(json.dumps(receipts, indent=2) + '\n')
        print(json.dumps({'name': name, 'exit': result.returncode, 'killed': killed}), flush=True)
        return receipt, tested

    baseline, tested = run('before', ['corpusIntake/testOnly *RecallTimingIntakeSuite', 'alignJVM/testOnly *TemporalSupportSuite', 'codecJVM/testOnly *TemporalSupportCodecSuite', 'pipeline/testOnly *RecallTimingBuildSuite'], {})
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
    after, tested = run('after', ['corpusIntake/testOnly *RecallTimingIntakeSuite', 'alignJVM/testOnly *TemporalSupportSuite', 'codecJVM/testOnly *TemporalSupportCodecSuite', 'pipeline/testOnly *RecallTimingBuildSuite'], {})
    assert after['exit'] == 0 and tested
    assert all((root / name).read_bytes() == data for name, data in originals.items())


if __name__ == '__main__':
    main()
