#!/usr/bin/env python3
"""Independently read pinned planned selections and actual worker coverage, without caption text."""
from bisect import bisect_left
import csv
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import sys

job_path, annotations_path, delivery, output = map(Path, sys.argv[1:])
assert not output.exists()
job = json.loads(job_path.read_text())
package = delivery / 'package'
manifest_bytes = (package / 'manifest.json').read_bytes()
manifest = json.loads(manifest_bytes)
completion = json.loads((delivery / 'completion.json').read_text())


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()
assert manifest_sha == completion['packageManifestSha256']
assert sha(delivery / 'source.json') == completion['sourceSha256']
assert (package / 'plan.json').read_bytes() == job_path.read_bytes()
assert manifest['edition'] == job['edition']
assert len(manifest['parts']) == len(job['parts']) == 2
for name, expected in manifest['files'].items():
    path = package / name
    assert path.resolve().is_relative_to(package.resolve()) and not path.is_symlink()
    assert sha(path) == expected, name
actual_names = {str(p.relative_to(package)) for p in package.rglob('*') if p.is_file()}
assert actual_names == set(manifest['files']) | {'manifest.json'}

with annotations_path.open(newline='') as stream:
    annotations = list(csv.DictReader(stream))
assert len(annotations) == 1000
part_names = {'media-part-a': 'annotation-run-1', 'media-part-b': 'annotation-run-2'}
records = []
all_expected = []
offset = 0
for index, (plan, part) in enumerate(zip(job['parts'], manifest['parts'])):
    assert plan['occurrence'] == part['occurrence']
    root = package / f'part-{index}'
    probe = json.loads((root / 'probe.json').read_text())
    stream = next(s for s in probe['streams'] if s['index'] == plan['streamIndex'])
    assert stream['codec_type'] == 'video' and stream['time_base'] == plan['timebase']
    packets = sorted((p for p in probe['packets'] if p['stream_index'] == plan['streamIndex']),
                     key=lambda p: int(p['pts']))
    pts = [int(p['pts']) for p in packets]
    assert len(pts) == len(set(pts)) == int(stream['nb_frames'])
    assert sha(root / 'media') == plan['inputSha256']
    rate = Fraction(plan['timebase'])
    expected_intervals = {}
    point_rows = []
    for row in annotations:
        if row['part'] != part_names[plan['occurrence']]:
            continue
        start = Fraction(row['onset']) / rate
        # The admitted annotation CSV leaves end blank for an onset-only point.
        end = Fraction(row['end']) / rate if row['end'] else start
        assert start.denominator == end.denominator == 1
        ordinal = int(row['ordinal'])
        if start == end:
            point_rows.append(ordinal)
        else:
            expected_intervals[f'annotation-segment-{ordinal:04d}'] = (int(start), int(end))
    assert {e['id']: (int(e['start']), int(e['endExclusive'])) for e in plan['extents']} == expected_intervals
    selected = []
    for extent in plan['extents']:
        start, end = int(extent['start']), int(extent['endExclusive'])
        left, right = bisect_left(pts, start), bisect_left(pts, end)
        available = list(range(left, right))
        assert available and pts[left] == start
        assert pts[right - 1] + int(packets[right - 1]['duration']) == end
        count = min(extent['samplesPerExtent'], len(available))
        # Quantile ranks on the actual presentation inventory, with the documented lower tie.
        ranks = ([0] if count == 1 else
                 [int(Fraction(i, count - 1) * (len(available) - 1)) for i in range(count)])
        chosen = [available[i] for i in ranks]
        selected.append({'id': extent['id'], 'presentationOrdinals': chosen})
    union = sorted({p for e in selected for p in e['presentationOrdinals']})
    realized = json.loads((root / 'realized-selections.json').read_text())
    assert realized['extents'] == selected
    assert realized['sharedPresentationOrdinals'] == union == part['selectedPresentationOrdinals']
    execution = json.loads((root / 'sampled-execution.json').read_text())
    assert execution['selectedPresentationOrdinals'] == union
    assert execution['observedPts'] == [pts[i] for i in union]
    request = json.loads((root / 'caption-request.json').read_text())
    outcomes = json.loads((root / 'caption-outcome.json').read_text())
    indices = {ordinal: position for position, ordinal in enumerate(union)}
    expected = [{'id': e['id'], 'ordinals': [indices[i] for i in e['presentationOrdinals']]}
                for e in selected]
    assert request['extents'] == expected
    assert [{k: e[k] for k in ['id', 'ordinals']} for e in outcomes['extents']] == expected
    assert all(e['text'].strip() for e in outcomes['extents'])
    assert outcomes['coverage'] == {'observed': len(expected), 'requested': len(expected)}
    assert outcomes['requestId'] == request['requestId']
    assert outcomes['offline'] == {'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1'}
    frames = request['frames']
    assert frames['count'] == len(union)
    assert frames['sha256'] == manifest['files'][frames['file']]
    assert (root / 'frames.bgr').stat().st_size == len(union) * frames['width'] * frames['height'] * 3
    all_expected.extend({'id': e['id'], 'ordinals': [i + offset for i in e['ordinals']]}
                        for e in expected)
    offset += len(union)
    records.append({'occurrence': plan['occurrence'], 'input_sha256': plan['inputSha256'],
                    'presentation_frames': len(pts), 'selected_frames': len(union),
                    'requested_extents': len(expected), 'observed_extents': len(expected),
                    'point_annotations_without_interval_caption': point_rows,
                    'caption_outcome_sha256': sha(root / 'caption-outcome.json')})
assert all_expected == manifest['extents']
assert sum(r['observed_extents'] for r in records) == 999
assert [n for r in records for n in r['point_annotations_without_interval_caption']] == [13]
receipt = {'source': '2c4b74f9ef069e1bc1f6678f8dababe2421ca929',
           'package_manifest_sha256': manifest_sha, 'plan_sha256': sha(job_path),
           'files_verified': len(manifest['files']), 'parts': records,
           'selected_frames': offset, 'worker_extents': 999,
           'qualification': 'Independent file hashes, frame inventory/ranks, exact annotation '
                            'interval plan and worker coverage; no caption-accuracy or physical '
                            'synchronization inference.'}
output.write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps(receipt, indent=2))
