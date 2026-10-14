#!/usr/bin/env python3
"""Execute conditional annotation/video mapping on the private, completed planned package.

All supplied content and command transcripts stay in the caller's private output directory.
The public receipt contains identities and counts only. This does not measure physical sync.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

repo = Path(__file__).resolve().parents[4]
bundle, input_root, delivery, output = (Path(p).resolve() for p in sys.argv[1:])
output.mkdir(parents=True, exist_ok=False)
inputs = output / 'input'
inputs.mkdir()
canonical = lambda value: json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
digest = lambda data: hashlib.sha256(data).hexdigest()
cli = ['sh', str(bundle / 'bin/storymodel4s')]
commands = []
environment = dict(os.environ, JAVA_TOOL_OPTIONS='-Xmx2g -XX:ActiveProcessorCount=2')


def run(arguments):
    result = subprocess.run(arguments, cwd=output, env=environment, capture_output=True, text=True)
    commands.append({'arguments': arguments, 'exit': result.returncode,
                     'stdout': result.stdout, 'stderr': result.stderr})
    (output / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
    if result.returncode != 0:
        raise RuntimeError(f'command {len(commands)} refused; private transcript retained')
    return result.stdout


# These are the already admitted source03 inputs, copied byte-for-byte; no new recall admission.
names = ['recall-source-03.csv', 'recall-lineage.json', 'verification.json',
         'recall-coverage.json', 'annotations.csv', 'parts.json']
for name in names:
    shutil.copyfile(input_root / name, inputs / name)
    assert (inputs / name).read_bytes() == (input_root / name).read_bytes()
annotation = canonical({'schemaVersion': 'prepared-timed-source/v1',
                        'csv': (inputs / 'annotations.csv').read_text(),
                        'parts': (inputs / 'parts.json').read_text()})
(inputs / 'annotations.json').write_text(annotation)
completion = json.loads((delivery / 'completion.json').read_text())
media = json.loads((delivery / 'source.json').read_text())
manifest = completion['packageManifestSha256']
assert digest((delivery / 'package/manifest.json').read_bytes()) == manifest
edition = media['edition']
# Study paths are confined to their input directory. Immutable hard links avoid copying media
# bytes while keeping that boundary intact; neither this driver nor replay mutates the package.
shutil.copytree(delivery / 'package', inputs / 'package', copy_function=os.link)
template = json.loads(run(cli + ['videoSource', 'join-template', str(inputs / 'annotations.json'),
                                str(inputs / 'package'), manifest, edition]))
correspondence = template['correspondenceTemplate']
correspondence['basis'] = (
    'Conditional published-part convention: owner-supplied Sherlock workbook seconds are '
    'interpreted from zero on the corresponding supplied Sherlock_part1_imovie.m4v and '
    'Sherlock_part2_imovie.m4v files, at 2500 film ticks per second. These declared '
    'coordinate endpoints are not independently measured audiovisual landmarks or '
    'participant-specific video/scanner trigger synchronization. No fitted offset or HRF shift.')
part_names = {'annotation-run-1': 'media-part-a', 'annotation-run-2': 'media-part-b'}
annotation_axes = {p['part']: p for p in template['annotationAxes']}
film_axes = {p['part']: p for p in template['filmAxes']}
for part in correspondence['parts']:
    annotation_axis = annotation_axes[part['annotationPart']]
    film = film_axes[part_names[part['annotationPart']]]
    assert annotation_axis['secondsPerTick'] == '1/1'
    assert film['secondsPerTick'] == '1/2500'
    points = [(int(annotation_axis[k]), int(annotation_axis[k]) * 2500) for k in ['start', 'end']]
    assert all(int(film['start']) <= y <= int(film['end']) for _, y in points)
    part.update(filmOccurrence=film['part'], filmAxis=film['axis'],
                landmarks=[{'annotationTick': str(x), 'filmTick': str(y)} for x, y in points])
correspondence_bytes = canonical(correspondence).encode()
(inputs / 'correspondence.json').write_bytes(correspondence_bytes)
run(cli + ['videoSource', 'join', str(inputs / 'annotations.json'), str(inputs / 'package'),
           manifest, edition, str(inputs / 'correspondence.json'), digest(correspondence_bytes),
           str(inputs / 'joined')])

study = json.loads((input_root / 'study.json').read_text())
configuration = {'profile': 'mapping-lexical-pair/v1', 'temperature': '1.0', 'perLevel': 20,
                 'tieBudget': 200, 'requested': ['Mapping'], 'networkRequests': 0, 'retries': 0}
for condition in ['a', 'av']:
    source = ({'format': 'checked-timed', 'path': 'annotations.json'} if condition == 'a' else
              {'format': 'checked-av', 'path': 'joined/source.json',
               'package': 'package', 'manifestSha256': manifest,
               'edition': edition, 'blend': '0.8'})
    current = dict(study, source=source)
    config = dict(configuration, profile=('mapping-lexical-pair/v1' if condition == 'a'
                                         else 'mapping-caption-pair/v1'))
    study_path, config_path = inputs / f'study-{condition}.json', inputs / f'mapping-{condition}.json'
    study_path.write_text(canonical(current))
    config_path.write_text(canonical(config))
    run(cli + ['mappingRun', 'exchange', str(study_path), str(config_path),
               str(output / f'exchange-{condition}')])
    run(cli + ['mappingWeight', str(study_path), str(config_path), '0/1,1/2,1/1',
               str(output / f'weighted-{condition}')])

sys.path.insert(0, str(repo / 'examples/mapping'))
from read_mapping import read_publication
from read_weighted_mapping import read_sweep

records = {}
receipts = {}
for condition in ['a', 'av']:
    directory = output / f'exchange-{condition}'
    publication = read_publication({p.name: p.read_text() for p in directory.iterdir()})
    assert len(publication['pairs']) == 1
    weighted = output / f'weighted-{condition}'
    _, members = read_sweep({p.name: p.read_text() for p in weighted.iterdir()})
    assert len(members) == 3
    records[condition] = {}
    receipts[condition] = {'pairs': 1, 'weighted_members': 3, 'members': {}}
    for member in ['reference', 'reconstruction']:
        files = list(directory.glob(f'*--{member}--mapping.json'))
        assert len(files) == 1
        record = json.loads(files[0].read_text())
        records[condition][member] = record
        statuses = {}
        for row in record['outcomes']:
            status = row['processing_status']['status']
            statuses[status] = statuses.get(status, 0) + 1
        receipts[condition]['members'][member] = {
            'record_sha256': digest(files[0].read_bytes()),
            'inventory_digest': record['inventory']['digest'],
            'outcomes': len(record['outcomes']), 'processing': statuses,
            'targets': len(record['source']['targets'])}
        bases = list(weighted.glob(f'base--*--{member}--mapping.json'))
        assert len(bases) == 1 and bases[0].read_bytes() == files[0].read_bytes()
first = records['a']['reference']
for condition in records.values():
    for record in condition.values():
        assert record['inventory'] == first['inventory']
        assert [t['target_id'] for t in record['source']['targets']] == [
            t['target_id'] for t in first['source']['targets']]
receipt = {'source': '2c4b74f9ef069e1bc1f6678f8dababe2421ca929',
           'scope': 'actual local captions and admitted source03; conditional published-part '
                    'correspondence; no measured synchronization, caption accuracy or calibration',
           'manifest': manifest, 'correspondence_sha256': digest(correspondence_bytes),
           'same_inventory_and_target_ids': True, 'separate_commands_base_equal': True,
           'conditions': receipts, 'command_count': len(commands),
           'command_receipt_sha256': digest((output / 'commands.json').read_bytes())}
(output / 'readback.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps(receipt, indent=2))
