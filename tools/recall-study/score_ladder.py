#!/usr/bin/env python3
"""Replay the fixed navigation-ladder development readout from saved reports.

Usage: score_ladder.py DATA_ROOT NEW_OUTPUT_DIRECTORY
The protocol is docs/plans/2026-09-05-navigation-ladder-readout.md. No inference or fitting.
"""
import argparse
import csv
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import statistics
import subprocess
import sys

import gold_scene
import score

ARMS = ('ladder-full', 'ladder-content', 'ladder-hierarchy', 'ladder-order',
        'ladder-causality', 'ladder-similarity', 'chosen-shuffled-dev', 'prior0-chosen-dev')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('data', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    data, output = args.data.resolve(), args.output.resolve()
    study = data / 'study/recall-to-video'
    root = Path(__file__).resolve().parents[2]
    inputs = {}

    def bind(path):
        inputs[str(path.relative_to(data))] = hashlib.sha256(path.read_bytes()).hexdigest()
        return path

    partition = json.loads(bind(study / 'partition.json').read_text())
    names = sorted(partition['development'])
    if len(names) != 11 or set(names) & set(partition['untouchedTest']):
        raise ValueError('expected eleven distinct development participants')
    reports, inventory = {}, {}
    for arm in ARMS:
        directory = study / arm
        for path in directory.glob('*.log'):
            bind(path)
        if (directory / 'parts.json').exists():
            bind(directory / 'parts.json')
        paths = sorted(directory.glob('recall-map-*.tsv'))
        if sorted(score.participant_of(str(p)) for p in paths) != names:
            raise ValueError(f'{arm}: changed development population')
        reports[arm] = {}
        for path in paths:
            rows = score.read_report(bind(path))
            if not rows or any(not r.get('recallOnsetSeconds') or
                               not r.get('group', '').split('.')[0].strip().isdigit() for r in rows):
                raise ValueError(f'{path}: incomplete scored rows')
            reports[arm][score.participant_of(str(path))] = rows
        metadata = {'reports': len(paths), 'units': sum(map(len, reports[arm].values()))}
        if metadata['units'] != 1744:
            raise ValueError(f'{arm}: changed unit population')
        if arm.startswith('ladder-'):
            rungs, scales, ledgers = set(), set(), set()
            for name in names:
                log = bind(directory / (name + '.log')).read_text()
                if '[error]' in log or '[success]' not in log:
                    raise ValueError(f'{arm}/{name}: unsuccessful inference log')
                for pattern, values in [('ladder rung: (.+)', rungs),
                                        ('ordering prior scale: (.+)', scales), ('layer use: (.+)', ledgers)]:
                    matches = re.findall(pattern, log)
                    if len(matches) != 1:
                        raise ValueError(f'{arm}/{name}: missing or repeated {pattern}')
                    values.update(matches)
                for suffix in ['.posterior.json', '.voyage.json']:
                    bind(directory / ('recall-map-' + name + '.tsv' + suffix))
            if len(rungs) != 1 or scales != {'1.5'} or len(ledgers) != 1:
                raise ValueError(f'{arm}: mixed run configuration')
            metadata.update(rung=next(iter(rungs)), priorScale=1.5, layerUse=next(iter(ledgers)))
        inventory[arm] = metadata

    full = reports['ladder-full']
    for arm in ARMS:
        for name in names:
            keys = ('unit', 'recallText', 'recallOnsetSeconds', 'recallLastWordOnsetSeconds')
            if [tuple(r[k] for k in keys) for r in reports[arm][name]] != [tuple(r[k] for k in keys) for r in full[name]]:
                raise ValueError(f'{arm}/{name}: changed recall units or order')
        inventory[arm]['byteIdenticalToFull'] = sum(
            (study / arm / f'recall-map-{n}.tsv').read_bytes() ==
            (study / 'ladder-full' / f'recall-map-{n}.tsv').read_bytes() for n in names)
    for control, reference in [('ladder-full', 'monofill-dev'),
                               ('chosen-shuffled-dev', 'blend080-lemmas-dev')]:
        inventory[control]['historicalReference'] = reference
        inventory[control]['byteIdenticalToReference'] = sum(
            (study / control / f'recall-map-{n}.tsv').read_bytes() ==
            bind(study / reference / f'recall-map-{n}.tsv').read_bytes() for n in names)

    # Complete the gold-free read before opening the scene gold.
    output.mkdir(parents=True, exist_ok=False)
    free = {arm: {n: score.score_participant(reports[arm][n]) for n in names} for arm in ARMS}
    if any(v is None for per in free.values() for v in per.values()):
        raise ValueError('a gold-free participant is unscorable')
    commands = [[sys.executable, str(root / 'tools/recall-study/agreement.py'),
                 *[x for arm in ARMS for x in (arm, str(study / arm))]]]
    with (output / 'agreement.log').open('w') as log:
        subprocess.run(commands[0], stdout=log, stderr=subprocess.STDOUT, check=True)

    goldpath = bind(data / 'sherlock/Sherlock_Recall_Scene_n50_Onsets.csv')
    gold = gold_scene.load_gold(goldpath)
    labeled = {arm: gold_scene.label(gold_scene.load_arm(study / arm), gold) for arm in ARMS}
    # A separate raw-CSV Decimal oracle checks every retained label, mapping and denominator.
    # Inclusive interval ends and first sorted match are the pre-registered legacy rule.
    with goldpath.open(encoding='utf-8-sig', newline='') as f:
        raw_gold = list(csv.DictReader(f))
    for arm in ARMS:
        oracle = {}
        for name in names:
            nn = int(name[2:4])
            if nn in (1, 5):
                continue
            subject = nn if nn <= 4 else nn - 1
            intervals = sorted((Decimal(r['Onset']) * Decimal('1.5'),
                                Decimal(r['Offset']) * Decimal('1.5'), int(r['Scene']))
                               for r in raw_gold if int(r['Subject']) == subject)
            pairs = []
            for row in reports[arm][name]:
                onset = Decimal(row['recallOnsetSeconds'])
                matches = [scene for lo, hi, scene in intervals if lo <= onset <= hi]
                if matches:
                    pairs.append((matches[0], int(row['group'].split('.')[0])))
            if pairs:
                oracle[name] = pairs
        if oracle != labeled[arm]:
            raise ValueError(f'{arm}: legacy scorer disagrees with raw interval oracle')
        if {n: [g for g, _ in pairs] for n, pairs in labeled[arm].items()} != {
                n: [g for g, _ in pairs] for n, pairs in labeled['ladder-full'].items()}:
            raise ValueError(f'{arm}: changed gold eligibility')

    outcomes, contrasts = {}, {}
    for arm in ARMS:
        per = labeled[arm]
        pooled = [pair for n in sorted(per) for pair in per[n]]
        outcomes[arm] = {'goldFree': score.aggregate(free[arm]), 'goldFreePerParticipant': free[arm],
                         'sceneExact': gold_scene.rate(pooled), 'withinOne': gold_scene.rate(pooled, 1),
                         'sceneExactCI95': gold_scene.cluster_boot(per, gold_scene.rate),
                         'goldUnits': len(pooled), 'goldParticipants': len(per)}
        if arm != 'ladder-full':
            contrasts[arm] = {}
            for tol, label in [(0, 'sceneExact'), (1, 'withinOne')]:
                diffs = {n: [gold_scene.rate(per[n], tol) - gold_scene.rate(labeled['ladder-full'][n], tol)]
                         for n in sorted(per)}
                contrasts[arm][label] = {'meanChangePP': statistics.mean(v[0] for v in diffs.values()),
                                         'ci95': gold_scene.cluster_boot(diffs, statistics.mean),
                                         'perParticipantChangePP': {n: v[0] for n, v in diffs.items()}}
    implementation = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in
                      [Path(__file__), *[root / 'tools/recall-study' / (n + '.py')
                                        for n in ['score', 'gold_scene', 'agreement']]]}
    result = {'schema': 'storymodel4s.navigation-ladder.readout/v1', 'development': names,
              'ladderReportedInferenceRevision': 'f4ee65b4c87a1ed0e48b873885de36a4056eb53d',
              'scoringRevision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
              'seed': 20260902, 'resamples': 2000, 'newExploratoryGoldContrasts': 7,
              'limitations': ['Historical shuffle preserves order and is an ineffective manipulation.',
                              'Historical prior-zero predates scene decode and fill; its contrast is confounded.',
                              'Every ladder rung retains scene-monotone decode and the shipped external prior.',
                              'Similarity and full are the same theta configuration.',
                              'Inference revision is recorded by the handoff; per-run logs bind observed settings.'],
              'inventory': inventory, 'outcomes': outcomes, 'contrastsVersusFull': contrasts,
              'inputsSha256': inputs, 'implementationSha256': implementation, 'commands': commands,
              'agreementLogSha256': hashlib.sha256((output / 'agreement.log').read_bytes()).hexdigest()}
    (output / 'results.json').write_text(json.dumps(result, indent=2, sort_keys=True, allow_nan=False) + '\n')
    for arm in ARMS:
        r = outcomes[arm]
        print(f"{arm}: {r['goldParticipants']} participants, {r['goldUnits']} gold units; "
              f"exact {r['sceneExact']:.2f}%, within-one {r['withinOne']:.2f}%")


if __name__ == '__main__':
    main()
