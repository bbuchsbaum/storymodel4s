#!/usr/bin/env python3
"""Validate the selected task receipts; never turn a failed attempt into a green run."""
from pathlib import Path
import gzip
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parent
BINDINGS = json.loads((ROOT / 'receipt-bindings.json').read_text())
SHA = BINDINGS['final_source_sha']
TOTAL = re.compile(r'^\[(info|error)\] (Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)(?:, (?:Skipped|Ignored) (\d+))?$', re.M)

def summaries(text):
    values = []
    for match in TOTAL.finditer(text):
        _, status, total, failed, errors, passed, skipped = match.groups()
        row = dict(total=int(total), passed=int(passed), failed=int(failed), errors=int(errors), skipped=int(skipped or 0))
        # sbt sometimes omits the label for ignored tests; preserve the accounting difference.
        if not skipped:
            row['skipped'] = row['total'] - row['passed'] - row['failed'] - row['errors']
        assert row['skipped'] >= 0
        row['status'] = status
        values.append(row)
    return values

def raw(path):
    data = path.read_bytes()
    return gzip.decompress(data) if path.suffix == '.gz' else data

selected = {}
receipts = []

def add(label, path, tasks, omit=(), meta=None):
    data = raw(path)
    binding = BINDINGS['receipts'][label]
    assert hashlib.sha256(data).hexdigest() == binding['expected_raw_sha256'], label
    assert len(binding['source_sha']) == 40 and binding['source_binding']
    if binding['source_sha'] != SHA:
        assert binding['reuse_reason'], label
    totals = summaries(data.decode())
    assert len(totals) <= len(tasks), (label, len(totals), len(tasks))
    if meta is not None:
        assert meta['exit_code'] == 0, (label, meta['exit_code'])
        assert 'set ThisBuild / tlFatalWarnings := true' in meta['command']
        assert len(totals) == len(tasks), (label, len(totals), len(tasks))
    receipt = dict(label=label, path=str(path.relative_to(ROOT.parent)), raw_sha256=hashlib.sha256(data).hexdigest(), completed_summaries=len(totals), source_sha=binding['source_sha'], source_binding=binding['source_binding'], reuse_reason=binding['reuse_reason'])
    receipts.append(receipt)
    for task, row in zip(tasks, totals):
        if task in omit:
            continue
        assert row['status'] == 'Passed' and row['failed'] == row['errors'] == 0, (task, row)
        assert task not in selected, task
        selected[task] = dict(**row, receipt=label)

author = ROOT.parent / 'admissibility-seal-20260923/slice-gate-280a9931.log.gz'
author_text = raw(author).decode()
author_tasks = re.search(r'^TASKS=48: (.*)$', author_text, re.M).group(1).split('; ')
add('author280a9931-unaffected-tasks', author, author_tasks,
    {'alignJVM/test','alignJS/test','alignNative/test','fixturesJVM/test','fixturesJS/test','fixturesNative/test','embedBench/test','embedGrakern/test','media/test'})
for label, basename, omitted in [
    ('pinned713d0175','pinned-jvm',()),
    ('fixture-jvm2981b6b0','admissibility-fixture-jvm-2981b6b0',('alignJVM/test',)),
    ('fixture-jvm27033f47','admissibility-fixture-second-jvm',()),
    ('platforms27033f47','admissibility-final-platforms-27033f47',()),
]:
    # The pinned receipt uses the short filename; the other run_logged receipts retain '.log'.
    meta_path = ROOT / (basename + ('.meta.json' if basename == 'pinned-jvm' else '.log.meta.json'))
    meta = json.loads(meta_path.read_text())
    tasks = [v for v in meta['command'] if v.endswith('/test')]
    add(label, ROOT / (basename + '.log.gz'), tasks, omitted, meta)

portable = ['acquire','align','amrInterop','codec','core','corpus','document','embedCore','fixtures','interview','laws','proposition','recall','story','view']
expected = {module+platform+'/test' for module in portable for platform in ['JVM','JS','Native']}
expected |= {module+'/test' for module in ['corpusIntake','embedBench','embedGrakern','media','pipeline','providerAgent']}
assert set(selected) == expected, {'missing': sorted(expected-set(selected)), 'extra': sorted(set(selected)-expected)}
totals = {key: sum(row[key] for row in selected.values()) for key in ['total','passed','failed','errors','skipped']}
assert totals['total'] == totals['passed'] + totals['skipped']
result = dict(schema='storymodel4s.scoped-qualification.v1', source_sha=SHA,
              qualification='composed task coverage, not one full checkAll run; source equivalence separately reviewed',
              task_count=len(selected), totals=totals,
              tasks=dict(sorted(selected.items())), receipts=receipts)
print(json.dumps(result, indent=2))
