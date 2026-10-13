#!/usr/bin/env python3
"""Recheck admitted sub-03 and media bytes without reading voxel values or prose."""
import argparse
import csv
from datetime import datetime, timezone
import gzip
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(4 * 1024 * 1024):
            h.update(block)
    return h.hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('dataset', type=Path)
    p.add_argument('media_root', type=Path)
    p.add_argument('output', type=Path)
    args = p.parse_args()
    repo = Path(__file__).resolve().parents[4]
    prior_dir = repo / 'docs/data/sherlock/openneuro-acquisition-20260922'
    prior_path = prior_dir / 'verification.json'
    assert digest(prior_path) == 'f562e860e23c67f182ad466372d10a2ceb7673e31cbe5bf64aa702bc590f9f35'
    prior = json.loads(prior_path.read_text())
    root = args.dataset.resolve(strict=True)
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    assert head == prior['commit'] == 'fc91edbbe1b8117f96b40bb4d1f5df43aa1afdce'
    pins = {r['file']: r for r in prior['annexFiles'] + prior['trackedMetadata']}
    old_headers = json.loads((prior_dir / 'development-headers.json').read_text())
    old_runs = {r['task']: r for r in old_headers['runs']}
    checked = []

    def check(name, annex=False):
        path = root / name
        target = path.resolve(strict=True)
        assert target.is_relative_to(root), name
        if annex:
            assert target.is_relative_to(root / '.git/annex/objects'), name
        sha = digest(path)
        assert sha == pins[name]['sha256'], name
        assert path.stat().st_size == pins[name]['bytes'], name
        checked.append({'file': name, 'sha256': sha, 'bytes': path.stat().st_size})
        return path

    readme_path = check('README')
    readme = readme_path.read_text()
    # Parse only the three crop tables; exclude all scene/participant outcomes.
    markers = ['Movie data: Scan 1', 'Movie data: Scan 2', 'Recall data: One scan',
               'Scene number and Onset/Offset of each MOVIE']
    crop = {}
    for task, start, end in zip(['sherlockPart1', 'sherlockPart2', 'freerecall'], markers, markers[1:]):
        section = readme.split(start, 1)[1].split(end, 1)[0]
        matches = re.findall(r'^\s*3\s+(-?\d+)\s+(-?\d+)\s*$', section, re.MULTILINE)
        assert len(matches) == 1, task
        crop[task] = tuple(map(int, matches[0]))
    runs = []
    for task in ['freerecall', 'sherlockPart1', 'sherlockPart2']:
        old = old_runs[task]
        image = check(old['image'], annex=True)
        with gzip.open(image, 'rb') as stream:
            header = stream.read(348)
        assert hashlib.sha256(header).hexdigest() == old['header348Sha256']
        assert struct.unpack('<i', header[:4])[0] == 348 and header[344:348] == b'n+1\0'
        dims = struct.unpack('<8h', header[40:56])
        pixdim = struct.unpack('<8f', header[76:108])
        assert list(dims) == old['dimensions'] and list(pixdim) == old['pixdim']
        sidecar = check(old['sidecar'])
        events = check(old['events'])
        with events.open(newline='') as stream:
            rows = list(csv.DictReader(stream, delimiter='\t'))
        assert rows == old['taskEvents']
        assert json.loads(sidecar.read_text())['RepetitionTime'] == pixdim[4] == 1.5
        assert header[123] & 0x38 == 8 and struct.unpack('<f', header[136:140])[0] == 0
        beginning, end = crop[task]
        runs.append({'task': task, 'storedVolumes': dims[4], 'trSeconds': '3/2',
                     'header348Sha256': old['header348Sha256'], 'taskEvents': rows,
                     'releaseCropPrescription': {'beginningVolumes': beginning,
                         'endVolumes': end, 'resultLength': dims[4] - beginning - end,
                         'status': 'prescribed-not-applied',
                         'zeroBasedIndexEquation': 'stored_index = selected_index + beginningVolumes'},
                     'scannerToMediaBinding': 'unestablished',
                     'scannerToTranscriptBinding': 'unestablished',
                     'appliedPreprocessingHistory': 'unknown'})
    timebase = json.loads((repo / 'docs/data/sherlock/timebase-repair.json').read_text())
    media = []
    for number, part in enumerate(timebase['presentationEditionIdentity']['parts'], 1):
        path = args.media_root / f'Sherlock_part{number}_imovie.m4v'
        sha = digest(path)
        assert sha == part['sha256'] and path.stat().st_size == part['byteLength']
        media.append({'partId': part['partId'], 'sha256': sha, 'bytes': path.stat().st_size})
    result = {'schema': 'sherlock-development-input-recheck/v1',
              'checkedUtc': datetime.now(timezone.utc).isoformat(),
              'dataset': 'ds001132', 'release': '1.0.0', 'revision': head,
              'participant': 'sub-03', 'priorReceiptSha256': digest(prior_path),
              'files': checked, 'runs': runs, 'media': media,
              'scope': 'Selected development input byte/header recheck and release crop prescriptions only. '
                       'No voxel decoding, applied crop, participant alias admission, physical timing '
                       'join, semantic weight export, other participant payload or held-out analysis.'}
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(json.dumps({'status': 'pass', 'datasetFiles': len(checked), 'developmentRuns': len(runs),
                      'mediaParts': len(media), 'output': str(args.output)}))


if __name__ == '__main__':
    main()
