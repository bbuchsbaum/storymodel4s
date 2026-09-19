#!/usr/bin/env python3
"""Frozen-support scene scoring. All outcomes retain the independently fixed denominator.

Commands: freeze-support, import-tsv, compare. Use --help for their explicit inputs.
No gold is read on import; comparisons never open gold or infer eligibility from predictions.
"""
import argparse
from collections import Counter
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from enum import Enum
import csv
import hashlib
import json
import math
from pathlib import Path
import random
import re
import statistics
import sys

ROOT = Path(__file__).resolve().parents[2]
RULE_PATH = ROOT / 'embed-bench/src/main/resources/storymodel4s/bench/sherlock/scene-coding-rule.json'
CONFIG_PATH = Path(__file__).with_name('scoring-config.json')
MANIFEST_SCHEMA = 'storymodel4s.bench.recall-unit-manifest/v1'
SUPPORT_SCHEMA = 'storymodel4s.bench.scene-evaluation-support/v1'
ARM_SCHEMA = 'storymodel4s.bench.scene-outcomes/v1'


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def text_sha(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def support_digest(support):
    """Content pin retained separately in the study ledger before arm comparisons."""
    return hashlib.sha256(json.dumps(support, sort_keys=True, separators=(',', ':'),
                                    ensure_ascii=False, allow_nan=False).encode('utf-8')).hexdigest()


def hash_value(value):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value) is not None


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'DUPLICATE_JSON_KEY: ' + key)
        result[key] = value
    return result


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'), object_pairs_hook=unique_object)


def write(path, value):
    path = Path(path)
    require(not path.exists(), 'OUTPUT_EXISTS')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, sort_keys=True, indent=2, allow_nan=False) + '\n')


def load_rule(path=RULE_PATH):
    rule = load(path)
    require(rule['schema'] == 'storymodel4s.bench.sherlock-scene-rule/v1', 'RULE_SCHEMA')
    require(isinstance(rule['trSeconds'], (int, float)) and not isinstance(rule['trSeconds'], bool)
            and math.isfinite(rule['trSeconds']) and rule['trSeconds'] > 0, 'RULE_TR')
    require(type(rule['sceneCount']) is int and rule['sceneCount'] > 0, 'RULE_SCENES')
    require(rule['intervalConvention'] == 'closed-first-sorted', 'RULE_INTERVAL_CONVENTION')
    re.compile(rule['participantPattern'])
    rows = rule['participants']
    require(rows and len({r['participant'] for r in rows}) == len(rows), 'RULE_PARTICIPANTS')
    subjects = []
    for row in rows:
        require(type(row['participant']) is int and row['participant'] > 0, 'RULE_PARTICIPANT')
        require(row['eligibility'] in ('eligible', 'excluded-inconsistent-clock', 'no-gold-source'), 'RULE_ELIGIBILITY')
        require((row['goldSubject'] is not None) == (row['eligibility'] == 'eligible'), 'RULE_SUBJECT_STATUS')
        if row['goldSubject'] is not None:
            require(type(row['goldSubject']) is int and row['goldSubject'] > 0, 'RULE_SUBJECT')
            subjects.append(row['goldSubject'])
    require(len(subjects) == len(set(subjects)), 'RULE_DUPLICATE_SUBJECT')
    return rule


RULE = load_rule()
TR = RULE['trSeconds']
PARTICIPANT_PATTERN = RULE['participantPattern']
ALIASES = {r['participant']: r for r in RULE['participants']}
EXCLUDED_NUMS = {n for n, r in ALIASES.items() if r['eligibility'] == 'excluded-inconsistent-clock'}
EXCLUDED = {f'NN{n:02d}' for n in EXCLUDED_NUMS}


def configure(descriptor_path):
    """Compatibility check only: a descriptor cannot silently override the shared rule."""
    import corpus_descriptor as cd
    rule = cd.gold_rule(cd.load(descriptor_path))
    require(rule == {'trSeconds': TR, 'excludedParticipants': sorted(EXCLUDED),
                     'participantPattern': PARTICIPANT_PATTERN}, 'DESCRIPTOR_RULE_DISAGREEMENT')
    return {'TR': TR, 'EXCLUDED': sorted(EXCLUDED), 'EXCLUDED_NUMS': sorted(EXCLUDED_NUMS)}


def participant_number(text):
    match = re.match(PARTICIPANT_PATTERN, text)
    number = int(match.group(1)) if match else None
    return number if number in ALIASES else None


def gold_subject_of(nn):
    return ALIASES.get(nn, {}).get('goldSubject')


def onset(value):
    if value is None or value == '':
        return None
    require(not isinstance(value, bool), 'INVALID_ONSET')
    try:
        result = Decimal(str(value))
    except InvalidOperation as error:
        raise ValueError('INVALID_ONSET') from error
    require(result.is_finite() and result >= 0 and math.isfinite(float(result)), 'INVALID_ONSET')
    return result


def load_gold(path):
    """Original inclusive, sorted-first overlap convention, with checked numeric input."""
    by_subject = {}
    with Path(path).open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream)
        require(reader.fieldnames == ['Subject', 'Scene', 'Onset', 'Offset'], 'GOLD_HEADER')
        for row in reader:
            require(None not in row and None not in row.values(), 'GOLD_ROW')
            subject, scene = int(row['Subject']), int(row['Scene'])
            lo, hi = onset(row['Onset']), onset(row['Offset'])
            require(subject > 0 and 1 <= scene <= RULE['sceneCount'] and lo is not None and
                    hi is not None and hi >= lo, 'GOLD_INTERVAL')
            start, end = float(lo) * TR, float(hi) * TR
            require(math.isfinite(start) and math.isfinite(end), 'GOLD_NONFINITE')
            by_subject.setdefault(subject, []).append((start, end, scene))
    for intervals in by_subject.values():
        intervals.sort()
    return by_subject


def scene_at(intervals, t):
    for lo, hi, scene in intervals:
        if lo <= t <= hi:
            return scene
    return None


def load_manifest(path):
    doc = load(path)
    require(doc['schema'] == MANIFEST_SCHEMA, 'MANIFEST_SCHEMA')
    require(hash_value(doc['sourceInputSha256']) and hash_value(doc['partitionSha256']), 'MANIFEST_INPUT_HASH')
    require(doc['ruleSha256'] == sha(RULE_PATH), 'MANIFEST_RULE_CHANGED')
    participants = doc['participants']
    require(participants and len({p['participant'] for p in participants}) == len(participants), 'MANIFEST_PARTICIPANTS')
    require(len({participant_number(p['participant']) for p in participants}) == len(participants), 'MANIFEST_ALIAS_DUPLICATE')
    for p in participants:
        require(participant_number(p['participant']) is not None, 'MANIFEST_PARTICIPANT')
        require(p['partition'] in ('development', 'untouchedTest'), 'MANIFEST_PARTITION')
        require(hash_value(p['recallSha256']), 'MANIFEST_RECALL_HASH')
        units = p['units']
        require(units and len({u['id'] for u in units}) == len(units), 'MANIFEST_UNIT_IDS')
        require([u['ordinal'] for u in units] == list(range(len(units))), 'MANIFEST_ORDINALS')
        for u in units:
            require(type(u['ordinal']) is int, 'MANIFEST_ORDINAL_TYPE')
            require(isinstance(u['id'], str) and bool(u['id']), 'MANIFEST_UNIT_ID')
            require(hash_value(u['textSha256']) and hash_value(u['reportTextSha256']), 'MANIFEST_TEXT_HASH')
            require(u['sourceInputSha256'] == doc['sourceInputSha256'], 'MANIFEST_UNIT_SOURCE')
            onset(u['onsetSeconds'])
    return doc


def population(manifest, partition):
    require(partition in ('development', 'untouchedTest', 'all'), 'UNKNOWN_PARTITION')
    result = [p for p in manifest['participants'] if partition == 'all' or p['partition'] == partition]
    require(result, 'EMPTY_POPULATION')
    return result


def freeze_support(manifest_path, gold_path, partition):
    manifest = load_manifest(manifest_path)
    selected = population(manifest, partition)
    gold = load_gold(gold_path)
    rows = []
    for p in selected:
        alias = ALIASES[participant_number(p['participant'])]
        for unit in p['units']:
            time = onset(unit['onsetSeconds'])
            reason, scene = alias['eligibility'], None
            if reason == 'eligible':
                if time is None:
                    reason = 'missing-timing'
                elif alias['goldSubject'] not in gold:
                    reason = 'uncoded-subject'
                else:
                    scene = scene_at(gold[alias['goldSubject']], float(time))
                    reason = 'eligible' if scene is not None else 'outside-coded-intervals'
            rows.append({'participant': p['participant'], 'id': unit['id'],
                         'goldScene': scene, 'exclusion': None if scene is not None else reason})
    return {'schema': SUPPORT_SCHEMA, 'unitManifestSha256': sha(manifest_path),
            'ruleSha256': sha(RULE_PATH), 'goldSha256': sha(gold_path), 'partition': partition,
            'participants': [p['participant'] for p in selected], 'rows': rows,
            'eligibilityPolicy': 'gold-and-frozen-unit-timing-only', 'unitWeight': 1}


class OutcomeKind(str, Enum):
    LABEL = 'label'
    ABSTENTION = 'abstention'
    INVALID = 'invalid'
    PROVIDER_FAILURE = 'provider-failure'


@dataclass(frozen=True)
class Outcome:
    kind: OutcomeKind
    scene: int | None = None
    reason: str | None = None

    @classmethod
    def read(cls, row):
        kind = OutcomeKind(row['kind'])
        scene, reason = row.get('scene'), row.get('reason')
        if kind == OutcomeKind.LABEL:
            require(type(scene) is int and 1 <= scene <= RULE['sceneCount'], 'INVALID_LABEL_ENCODING')
        else:
            require(scene is None, 'NONLABEL_HAS_SCENE')
        if kind in (OutcomeKind.INVALID, OutcomeKind.PROVIDER_FAILURE):
            require(isinstance(reason, str) and bool(reason.strip()), 'MISSING_OUTCOME_REASON')
        return cls(kind, scene, reason)


def legacy_outcome(row):
    explicit = row.get('outcome') or ''
    group = (row.get('group') or '').split('.')[0].strip()
    if explicit and explicit != 'label':
        require(not group, 'NONLABEL_HAS_PREDICTION')
        result = {'kind': explicit, 'reason': row.get('outcomeReason')}
    elif not group:
        result = {'kind': 'abstention'}
    elif group.isdigit() and 1 <= int(group) <= RULE['sceneCount']:
        result = {'kind': 'label', 'scene': int(group)}
    else:
        result = {'kind': 'invalid', 'reason': 'unparseable-or-out-of-range-scene'}
    Outcome.read(result)
    return result


def load_arm(directory, manifest_path=None, partition=None):
    """Checked legacy TSV import. Ordinal + text hash + onset resolve its absent stable IDs."""
    require(manifest_path is not None and partition is not None, 'UNIT_MANIFEST_REQUIRED')
    manifest = load_manifest(manifest_path)
    selected = population(manifest, partition)
    paths = sorted(Path(directory).glob('recall-map-*.tsv'))
    expected = {'recall-map-' + p['participant'] + '.tsv' for p in selected}
    require({p.name for p in paths} == expected, 'PARTICIPANT_FILE_SET_MISMATCH')
    # An unrecognised TSV is an unexpected participant artifact too, not a silently skipped file.
    require({p.name for p in Path(directory).glob('*.tsv')} == expected, 'UNEXPECTED_PARTICIPANT_FILE')
    result = []
    for p in selected:
        path = Path(directory) / ('recall-map-' + p['participant'] + '.tsv')
        with path.open(encoding='utf-8', newline='') as stream:
            reader = csv.DictReader(stream, delimiter='\t', quoting=csv.QUOTE_NONE)
            require(reader.fieldnames and len(reader.fieldnames) == len(set(reader.fieldnames)) and
                    {'unit', 'recallText', 'recallOnsetSeconds', 'group'} <= set(reader.fieldnames), 'ARM_HEADER')
            rows = list(reader)
        require(all(None not in r and None not in r.values() for r in rows), 'ARM_MALFORMED_ROW')
        require([int(r['unit']) for r in rows] == [u['ordinal'] for u in p['units']], 'UNIT_SET_OR_ORDER_MISMATCH')
        units = []
        for row, unit in zip(rows, p['units']):
            require(text_sha(row['recallText']) == unit['reportTextSha256'], 'UNIT_TEXT_CHANGED')
            require(onset(row['recallOnsetSeconds']) == onset(unit['onsetSeconds']), 'UNIT_ONSET_CHANGED')
            require(not row.get('unitId') or row['unitId'] == unit['id'], 'UNIT_ID_CHANGED')
            units.append({**{k: unit[k] for k in ('id', 'ordinal', 'textSha256', 'onsetSeconds')},
                          'outcome': legacy_outcome(row)})
        result.append({'participant': p['participant'], 'units': units,
                       'legacyReportSha256': sha(path)})
    return {'schema': ARM_SCHEMA, 'unitManifestSha256': sha(manifest_path),
            'sourceInputSha256': manifest['sourceInputSha256'], 'participants': result,
            'identityPolicy': 'legacy-ordinal-normalized-text-and-onset-join'}


def validate_arm(arm, manifest_path, selected):
    manifest = load_manifest(manifest_path)
    require(arm['schema'] == ARM_SCHEMA, 'ARM_SCHEMA')
    require(arm['unitManifestSha256'] == sha(manifest_path) and
            arm['sourceInputSha256'] == manifest['sourceInputSha256'], 'ARM_INPUT_IDENTITY')
    parts = arm['participants']
    require(len(parts) == len(selected) and {p['participant'] for p in parts} ==
            {p['participant'] for p in selected}, 'ARM_PARTICIPANT_SET')
    by_name = {p['participant']: p for p in parts}
    outcomes = {}
    for p in selected:
        units = by_name[p['participant']]['units']
        require(len(units) == len(p['units']) and len({u['id'] for u in units}) == len(units) and
                {u['id'] for u in units} == {u['id'] for u in p['units']}, 'ARM_UNIT_SET')
        by_id = {u['id']: u for u in units}
        for expected in p['units']:
            actual = by_id[expected['id']]
            require(type(actual['ordinal']) is int and actual['ordinal'] == expected['ordinal'] and actual['textSha256'] == expected['textSha256']
                    and onset(actual['onsetSeconds']) == onset(expected['onsetSeconds']), 'ARM_UNIT_IDENTITY')
            outcomes[p['participant'], expected['id']] = Outcome.read(actual['outcome'])
    return outcomes


def validate_support(support, manifest_path):
    manifest = load_manifest(manifest_path)
    require(support['schema'] == SUPPORT_SCHEMA, 'SUPPORT_SCHEMA')
    require(support['unitManifestSha256'] == sha(manifest_path) and support['ruleSha256'] == sha(RULE_PATH), 'SUPPORT_INPUT_IDENTITY')
    require(hash_value(support['goldSha256']) and support['unitWeight'] == 1 and
            support['eligibilityPolicy'] == 'gold-and-frozen-unit-timing-only', 'SUPPORT_POLICY')
    selected = population(manifest, support['partition'])
    require(support['participants'] == [p['participant'] for p in selected], 'SUPPORT_PARTICIPANTS')
    expected = [(p['participant'], u['id']) for p in selected for u in p['units']]
    require([(r['participant'], r['id']) for r in support['rows']] == expected, 'SUPPORT_UNIT_SET')
    for p in selected:
        alias = ALIASES[participant_number(p['participant'])]
        by_id = {u['id']: u for u in p['units']}
        for row in (r for r in support['rows'] if r['participant'] == p['participant']):
            scene, reason = row['goldScene'], row['exclusion']
            if scene is not None:
                require(type(scene) is int and 1 <= scene <= RULE['sceneCount'] and reason is None
                        and alias['eligibility'] == 'eligible' and onset(by_id[row['id']]['onsetSeconds']) is not None,
                        'SUPPORT_ELIGIBILITY')
            else:
                if alias['eligibility'] != 'eligible':
                    allowed = {alias['eligibility']}
                elif onset(by_id[row['id']]['onsetSeconds']) is None:
                    allowed = {'missing-timing'}
                else:
                    allowed = {'uncoded-subject', 'outside-coded-intervals'}
                require(reason in allowed, 'SUPPORT_EXCLUSION')
    return selected


def scoring_config(path=CONFIG_PATH):
    cfg = load(path)
    require(cfg['schema'] == 'storymodel4s.bench.scene-scoring-config/v1', 'CONFIG_SCHEMA')
    require(cfg['primary'] == 'participant-average-paired-difference' and
            cfg['secondary'] == 'pooled-unit-weighted-paired-difference' and
            cfg['bootstrapMethod'] == 'paired-participant-cluster-percentile' and
            cfg['unitWeight'] == 1 and cfg['confidence'] == 0.95 and
            cfg['percentileIndices'] == 'floor(alpha/2*n),floor((1-alpha/2)*n)-1', 'CONFIG_ESTIMAND')
    require(type(cfg['seed']) is int and type(cfg['resamples']) is int and cfg['resamples'] >= 40, 'CONFIG_RESAMPLING')
    return cfg


def rate(pairs, tol=0):
    return (100.0 * sum(p is not None and abs(g - p) <= tol for g, p in pairs) / len(pairs)) if pairs else None


def cluster_boot(per_part, fn, seed=None, n=None):
    """Compatibility helper; fixed config defaults and explicit undefined singleton interval."""
    cfg = scoring_config()
    seed = cfg['seed'] if seed is None else seed
    n = cfg['resamples'] if n is None else n
    names = sorted(per_part)
    if len(names) < 2:
        return (None, None)
    rng = random.Random(seed)
    values = sorted(fn([x for name in rng.choices(names, k=len(names)) for x in per_part[name]]) for _ in range(n))
    return values[int(.025 * n)], values[int(.975 * n) - 1]


def label(*args, **kwargs):
    raise ValueError('FROZEN_SUPPORT_REQUIRED: use freeze-support and compare')


def compare(manifest_path, support, arm_a, arm_b, config_path=CONFIG_PATH, *, support_sha256):
    require(support_digest(support) == support_sha256, 'FROZEN_SUPPORT_CHANGED')
    selected = validate_support(support, manifest_path)
    arms = [validate_arm(arm, manifest_path, selected) for arm in (arm_a, arm_b)]
    cfg = scoring_config(config_path)
    support_by_key = {(r['participant'], r['id']): r for r in support['rows']}
    participants, eligible = [], {}
    for p in selected:
        keys = [(p['participant'], u['id']) for u in p['units']]
        rows = [support_by_key[k] for k in keys]
        gold_keys = [k for k in keys if support_by_key[k]['goldScene'] is not None]
        def adjacent_count(predicate):
            return sum(predicate(a) and predicate(b) for a, b in zip(keys, keys[1:]))
        timing = {k: onset(u['onsetSeconds']) is not None for k, u in zip(keys, p['units'])}
        summary = {'participant': p['participant'], 'inputUnits': len(keys), 'goldEligibleUnits': len(gold_keys),
                   'timingEligibleUnits': sum(timing.values()), 'transcriptOrderEligibleUnits': len(keys),
                   'exclusions': dict(sorted(Counter(r['exclusion'] for r in rows if r['exclusion']).items())),
                   'transitionCoverage': {'adjacentInputPairs': max(0, len(keys) - 1),
                                          'timedPairs': adjacent_count(timing.get),
                                          'goldEligiblePairs': adjacent_count(lambda k: support_by_key[k]['goldScene'] is not None)},
                   'arms': []}
        pairs = []
        for outcomes in arms:
            scored = [(support_by_key[k]['goldScene'], outcomes[k].scene) for k in gold_keys]
            pairs.append(scored)
            labels = sum(outcomes[k].kind == OutcomeKind.LABEL for k in keys)
            summary['arms'].append({'outcomes': {kind.value: sum(outcomes[k].kind == kind for k in keys) for kind in OutcomeKind},
                                   'goldEligibleOutcomes': {kind.value: sum(outcomes[k].kind == kind for k in gold_keys) for kind in OutcomeKind},
                                   'predictionCoverage': labels / len(keys),
                                   'goldEligiblePredictionCoverage': sum(pred is not None for _, pred in scored) / len(scored) if scored else None,
                                   'labelledAdjacentPairs': adjacent_count(lambda k: outcomes[k].kind == OutcomeKind.LABEL),
                                   'sceneExact': rate(scored), 'sceneWithinOne': rate(scored, 1),
                                   'sceneDistanceAmongLabelledEligible': statistics.median(abs(g - pred) for g, pred in scored if pred is not None)
                                   if any(pred is not None for _, pred in scored) else None})
        summary['exactDifferencePoints'] = rate(pairs[1]) - rate(pairs[0]) if gold_keys else None
        summary['primaryParticipation'] = 'eligible' if gold_keys else 'no-gold-eligible-units'
        participants.append(summary)
        if gold_keys:
            eligible[p['participant']] = pairs
    names = sorted(eligible)
    def differences(pick, tol):
        primary = statistics.mean(rate(eligible[n][1], tol) - rate(eligible[n][0], tol) for n in pick)
        a = [x for n in pick for x in eligible[n][0]]
        b = [x for n in pick for x in eligible[n][1]]
        return primary, rate(b, tol) - rate(a, tol)
    series = {}
    for tol, name in [(0, 'sceneExact'), (1, 'sceneWithinOne')]:
        observed = differences(names, tol) if names else (None, None)
        boot = []
        if len(names) >= 2:
            rng = random.Random(cfg['seed'])
            boot = [differences(rng.choices(names, k=len(names)), tol) for _ in range(cfg['resamples'])]
        cis = []
        for index in range(2):
            values = sorted(v[index] for v in boot)
            cis.append([values[int(.025 * len(values))], values[int(.975 * len(values)) - 1]] if values else [None, None])
        series[name] = {'primaryParticipantAverageDifferencePoints': observed[0],
                        'secondaryPooledUnitWeightedDifferencePoints': observed[1],
                        'primaryCI95': cis[0], 'secondaryCI95': cis[1],
                        'ciStatus': 'estimated' if boot else 'insufficient-participants',
                        'perParticipantDifferences': {n: differences([n], tol)[0] for n in names},
                        'leaveOneParticipantOut': [{'omitted': n, 'primaryDifferencePoints': differences([k for k in names if k != n], tol)[0]
                                                    if len(names) > 1 else None} for n in names]}
    return {'schema': 'storymodel4s.bench.scene-comparison/v1', 'unitManifestSha256': sha(manifest_path),
            'evaluationSupportSha256': support_sha256,
            'configuration': cfg, 'configurationSha256': sha(config_path), 'partition': support['partition'],
            'eligibleParticipants': len(names), 'inputParticipants': len(selected), 'participants': participants,
            'series': series, 'transitionPolicy': 'original adjacent units only; no gap bridging',
            'qualification': 'fixed-support localization comparison; not behavioral-recovery or calibration evidence'}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    freeze = sub.add_parser('freeze-support')
    freeze.add_argument('manifest', type=Path); freeze.add_argument('gold', type=Path); freeze.add_argument('out', type=Path)
    freeze.add_argument('--partition', choices=['development', 'untouchedTest', 'all'], required=True)
    imp = sub.add_parser('import-tsv')
    imp.add_argument('manifest', type=Path); imp.add_argument('directory', type=Path); imp.add_argument('out', type=Path)
    imp.add_argument('--partition', choices=['development', 'untouchedTest', 'all'], required=True)
    cmp = sub.add_parser('compare')
    for name in ('manifest', 'support', 'arm_a', 'arm_b', 'out'):
        cmp.add_argument(name, type=Path)
    cmp.add_argument('--config', type=Path, default=CONFIG_PATH)
    cmp.add_argument('--support-sha256', required=True,
                     help='canonical content hash frozen separately in the study ledger')
    args = parser.parse_args(argv)
    try:
        require(not args.out.exists(), 'OUTPUT_EXISTS')
        if args.command == 'freeze-support':
            result = freeze_support(args.manifest, args.gold, args.partition)
        elif args.command == 'import-tsv':
            result = load_arm(args.directory, args.manifest, args.partition)
        else:
            result = compare(args.manifest, load(args.support), load(args.arm_a), load(args.arm_b),
                             args.config, support_sha256=args.support_sha256)
            result['inputReceipts'] = {name: sha(getattr(args, name)) for name in ('manifest', 'support', 'arm_a', 'arm_b')}
        write(args.out, result)
        if args.command == 'freeze-support':
            print(json.dumps({'supportSha256': support_digest(result), 'fileSha256': sha(args.out)}))
        return 0
    except (ValueError, KeyError, TypeError, OSError) as error:
        print('REFUSED: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
