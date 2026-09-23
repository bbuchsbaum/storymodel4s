#!/usr/bin/env python3
"""Run compiling mapping exchange counterexamples in this isolated checkout; restore source bytes.

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
    parser.add_argument('--suite', choices=['record', 'workspace'], default='record')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    args.out.mkdir(parents=True, exist_ok=False)
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    mutants = [('ignore-file-content', 'codec/src/main/scala/storymodel4s/codec/mappingexchange.scala', 'supplied(name) == text', '(supplied(name) == text || supplied(name) != text)', 'codecJVM/testOnly *MappingExchangeSuite'), ('replace-invalid-utf16', 'codec/src/main/scala/storymodel4s/codec/mappingexchange.scala', '!validUtf16(text)', '(!validUtf16(text) && validUtf16(text))', 'codecJVM/testOnly *MappingExchangeSuite'), ('omit-failed-outcome', 'codec/src/main/scala/storymodel4s/codec/mappingexchange.scala', '"decisions",\n        outcomes,', '"decisions",\n        outcomes.filter(o => field(field(o, "processing_status"), "status").asString.contains("complete")),', 'codecJVM/testOnly *MappingExchangeSuite')]
    expected_failures = {'ignore-file-content': 'rehashed table corruption still refuses against the checked record', 'replace-invalid-utf16': 'direct quoted Unicode identifiers roundtrip and unmatched UTF16 refuses before byte hashing', 'omit-failed-outcome': 'all outcomes, unassigned words, exact support and original decisions roundtrip'}
    baseline_task = 'codecJVM/testOnly *MappingExchangeSuite'
    if args.suite == 'workspace':
        baseline_task = 'pipeline/testOnly *WorkspaceMappingExportSuite'
        filename = 'pipeline/src/main/scala/storymodel4s/pipeline/MappingExchangeBuild.scala'
        mutants = [
            ('inspection-allows-export', filename, '.permitsExport(', '.permitsInspection(', baseline_task),
            ('implicit-first-policy', filename, '.policy(policyId)', '.policies.headOption', baseline_task),
        ]
        expected_failures = {
            'inspection-allows-export': 'valid inspection-only workspace refuses before output creation',
            'implicit-first-policy': 'unknown policy is refused without silently selecting the first record',
        }
    originals = {name: (root / name).read_bytes() for name in sorted({m[1] for m in mutants})}
    for name, data in originals.items():
        committed = subprocess.check_output(['git', 'show', f'{head}:{name}'], cwd=root)
        if committed != data:
            raise RuntimeError(f'{name}: commit source before mutation')
    receipts = []

    def run(name, tasks, extra):
        command = ['sbt', '-J-Xmx2G', '-batch', f'-Dsbt.global.staging={args.staging}',
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

    baseline, tested = run('before', [baseline_task], {})
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
    after, tested = run('after', [baseline_task], {})
    assert after['exit'] == 0 and tested
    assert all((root / name).read_bytes() == data for name, data in originals.items())


if __name__ == '__main__':
    main()
