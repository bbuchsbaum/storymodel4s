#!/usr/bin/env python3
"""Diagnose frozen score-conditional discrepancies without changing production inference."""
import argparse
from collections import Counter, defaultdict
import hashlib
import itertools
import json
import math
from pathlib import Path
import subprocess

from organization_recovery import evaluate, integer_counts, language_diagnostics
from read_organization import CATEGORIES, check_result, load, number, verified_files


def closed_form_pair(t, u, k, p):
    """Counts for two independent symmetric-confusion rows, derived by counting index pairs."""
    r = (1.0 - p) / (k - 1)
    a = p - r
    common = r * r * k * (k - 1) / 2
    backward = common + a * r * (k - 1 + t - u) + a * a * (u < t)
    forward = common + a * r * (k - 1 + u - t) + a * a * (u > t)
    same = k * r * r + 2 * a * r + a * a * (t == u)
    return [backward, forward, same]


def packet_diagnosis(baseline, tolerance):
    cases = []
    maximum = 0.0
    for case in baseline['experiments']:
        path, omitted, p = case['path'], case['omitted'], case['correct_mass']
        ordered = sorted(case['storage'])
        slots = {v: i for i, v in enumerate(ordered)}
        retained = [(a, b) for i, (a, b) in enumerate(zip(path, path[1:]))
                    if omitted not in (i, i + 1)]
        observed_truth = [sum(b < a for a, b in retained), sum(b > a for a, b in retained),
                          sum(b == a for a, b in retained)]
        expected = [0.0] * 3
        for a, b in retained:
            expected = [x + y for x, y in zip(expected, closed_form_pair(slots[a], slots[b], len(ordered), p))]
        error = max(abs(a - b) for a, b in zip(expected, case['counts']))
        maximum = max(maximum, error)
        if error > tolerance:
            raise ValueError('packet closed-form oracle disagrees')
        mapping = [a - b for a, b in zip(case['counts'], observed_truth)]
        coverage = [a - b for a, b in zip(observed_truth, case['truth_counts'])]
        if any(abs(a + b - c) > tolerance for a, b, c in zip(mapping, coverage, case['bias'])):
            raise ValueError('discrepancy decomposition failed')
        cases.append({**case, 'measurable_truth_counts': observed_truth,
                      'assignment_discrepancy': mapping, 'coverage_deficit': coverage,
                      'closed_form_counts': expected})
    groups = Counter(('missing' if c['omitted'] is not None else 'complete',
                      'point' if c['correct_mass'] == 1 else 'diffuse') for c in cases)
    return {'conditions': len(cases), 'distinct_statistical_configurations': len({(tuple(c['path']), c['correct_mass'], c['omitted']) for c in cases}),
            'maximum_closed_form_error': maximum,
            'groups': [{'coverage': a, 'mass': b, 'conditions': n} for (a, b), n in sorted(groups.items())],
            'interpretation': 'Deterministic score-conditional discrepancy; not an empirical failure rate or repeated-data bias estimate',
            'cases': cases}


def conditional_counts(rows):
    """Small independent enumeration over original adjacent destination distributions."""
    answer = [0.0] * 3
    for left, right in zip(rows, rows[1:]):
        for a, pa in enumerate(left):
            for b, pb in enumerate(right):
                answer[0 if b < a else 1 if b > a else 2] += pa * pb
    return answer


def bayes_rows(observed, channel):
    """Infer from observations and known channel, with no access to the latent truth."""
    k = len(channel)
    return [[channel[t][o] / sum(channel[s][o] for s in range(k)) for t in range(k)] for o in observed]


def generative_control(k, units, p, infer=bayes_rows, tolerance=1e-10):
    """Exact finite Bayes control, checking each observation against full joint enumeration."""
    sequences = list(itertools.product(range(k), repeat=units))
    channel = [[p if a == b else (1 - p) / (k - 1) for b in range(k)] for a in range(k)]
    mean_truth, mean_inferred = [0.0] * 3, [0.0] * 3
    conditional = {truth: [0.0] * 3 for truth in sequences}
    total, maximum_conditional_error = 0.0, 0.0
    for observed in sequences:
        joints = {truth: math.prod(channel[t][o] for t, o in zip(truth, observed)) / len(sequences) for truth in sequences}
        observation_probability = math.fsum(joints.values())
        if observation_probability == 0:
            continue
        posterior = infer(observed, channel)
        if len(posterior) != units or any(len(row) != k for row in posterior):
            raise ValueError('conditional posterior shape failed')
        exact_rows = [[math.fsum(w for truth, w in joints.items() if truth[i] == t) / observation_probability for t in range(k)] for i in range(units)]
        if any(not math.isfinite(a) or abs(a - b) > tolerance for row, ref in zip(posterior, exact_rows) for a, b in zip(row, ref)):
            raise ValueError('observation-conditional Bayes marginals failed')
        inferred = conditional_counts(posterior)
        exact_counts = [math.fsum(w * integer_counts(truth)[c] for truth, w in joints.items()) / observation_probability for c in range(3)]
        maximum_conditional_error = max(maximum_conditional_error, max(abs(a - b) for a, b in zip(inferred, exact_counts)))
        if maximum_conditional_error > tolerance:
            raise ValueError('observation-conditional Bayes counts failed')
        if p == 1 and any(abs(a - b) > tolerance for a, b in zip(inferred, integer_counts(observed))):
            raise ValueError('perfect observation recovery failed')
        uninformative_counts = [(units - 1) * (k - 1) / (2 * k)] * 2 + [(units - 1) / k]
        if p == 1 / k and any(abs(a - b) > tolerance for a, b in zip(inferred, uninformative_counts)):
            raise ValueError('uninformative observation control failed')
        for truth, joint in joints.items():
            counts = integer_counts(truth)
            total += joint
            for c in range(3):
                mean_truth[c] += joint * counts[c]
                mean_inferred[c] += joint * inferred[c]
                conditional[truth][c] += joint * len(sequences) * inferred[c]
    max_conditional_discrepancy = max(max(abs(a - b) for a, b in zip(conditional[truth], integer_counts(truth))) for truth in sequences)
    return {'correct_probability': p, 'joint_cases': len(sequences) ** 2, 'observation_controls': len(sequences),
            'maximum_observation_conditional_error': maximum_conditional_error, 'total_probability': total,
            'population_truth': mean_truth, 'population_posterior_expected_counts': mean_inferred,
            'maximum_path_conditional_discrepancy': max_conditional_discrepancy}


def rank_interval(values, key, tolerance, smaller_is_better=False):
    """One-based diagnostic rank interval; absent targets have no rank."""
    if key not in values:
        return None
    value = values[key]
    better = sum(v < value - tolerance if smaller_is_better else v > value + tolerance for v in values.values())
    tied = sum(abs(v - value) <= tolerance for v in values.values())
    return {'first': better + 1, 'last': better + tied, 'population_size': len(values)}


def mass_diagnostics(result, path, tolerance):
    truth = integer_counts(path)
    summary = check_result(result, tolerance)
    base = language_diagnostics(result, path, truth)
    ranks = {number(r['position']): r['target'] for r in result['order']}
    details = []
    for index, (row, t) in enumerate(zip(result['rows'], path)):
        if row['status'] != 'Available':
            details.append({'unit_index': index, 'status': row['status']})
            continue
        masses = {v['destination']: number(v['mass']) for v in row['mass']}
        target = ranks[t]
        top = max(masses.values())
        # Tolerance defines diagnostic tie reporting only, never production selection.
        best = sorted(k for k, v in masses.items() if abs(v - top) <= tolerance)
        details.append({'unit_index': index, 'status': 'Available', 'true_source_position': t,
                        'true_destination_mass_rank': rank_interval(masses, target, tolerance),
                        'true_mass': masses.get(target, 0), 'best_destinations': best,
                        'true_is_tied_best': target in best, 'top_tie_size': len(best),
                        'external_mass': sum(v for k, v in masses.items() if k.startswith('ext:'))})
    return {**base, 'counts': summary['counts'], 'rows': details,
            'count_discrepancy': [summary['counts'][c] - n for c, n in zip(CATEGORIES[:3], truth)]}


def cost_readback(artifact, tolerance):
    """Independently normalize all admitted states and collapse faithful/distorted destinations."""
    rows = artifact['result']['rows']
    costs = artifact['costs']
    if len(costs) != len(rows):
        raise ValueError('cost row inventory changed')
    temperature = number(artifact['temperature'])
    maximum = 0.0
    limits = []
    cost_ranks = []
    rank_targets = {number(r['position']): r['target'] for r in artifact['result']['order']}
    for index, (row, cost_row) in enumerate(zip(rows, costs)):
        if row['unit'] != cost_row['unit'] or row['status'] != 'Available':
            raise ValueError('cost row binding or availability mismatch')
        states = cost_row['states']
        if len({s['state'] for s in states}) != len(states):
            raise ValueError('duplicate priced state')
        admitted = [s for s in states if not s['excluded']]
        low = min(number(s['cost']) for s in admitted)
        weights = [math.exp(-(number(s['cost']) - low) / temperature) for s in admitted]
        den = math.fsum(weights)
        reconstructed = defaultdict(float)
        limit = defaultdict(float)
        minimum_states = [s for s in admitted if number(s['cost']) == low]
        for s, w in zip(admitted, weights):
            reconstructed[s['destination']] += w / den
        for s in minimum_states:
            limit[s['destination']] += 1 / len(minimum_states)
        actual = {v['destination']: number(v['mass']) for v in row['mass']}
        error = max(abs(actual.get(k, 0) - reconstructed.get(k, 0)) for k in actual.keys() | reconstructed.keys())
        maximum = max(maximum, error)
        if error > tolerance:
            raise ValueError('emitted costs do not reproduce destination masses')
        limits.append(dict(limit))
        if 'path' in artifact:
            source_costs = {}
            for state in admitted:
                key = state['destination']
                if not key.startswith('ext:'):
                    source_costs[key] = min(source_costs.get(key, math.inf), number(state['cost']))
            target = rank_targets[artifact['path'][index]]
            competitors = [v for key, v in source_costs.items() if key != target]
            cost_ranks.append({'unit_index': index, 'true_source_cost_rank': rank_interval(source_costs, target, tolerance, True),
                               'true_cost': source_costs.get(target),
                               'best_other_minus_true_cost': min(competitors) - source_costs[target] if target in source_costs and competitors else None})
    positions = {r['target']: number(r['position']) for r in artifact['result']['order']}
    limit_counts = {c: 0.0 for c in CATEGORIES}
    from read_organization import classify
    for a, b in zip(limits, limits[1:]):
        for x, px in a.items():
            for y, py in b.items():
                limit_counts[classify(x, y, positions)] += px * py
    return {'maximum_mass_readback_error': maximum, 'zero_temperature_limit_counts': limit_counts,
            'zero_temperature_limit_rows': limits, 'source_cost_ranks': cost_ranks}


def evaluate_diagnosis(manifest, baseline_results, plan_path, results):
    frozen = load(Path(manifest).read_text())
    plan_bytes = Path(plan_path).read_bytes()
    plan = load(plan_bytes.decode())
    tolerance = number(plan['tolerance'])
    baseline = evaluate(manifest, baseline_results)
    _, baseline_files = verified_files(baseline_results)
    baseline_language = [load(v) for k, v in baseline_files.items() if k.startswith('language-')]
    receipt, files = verified_files(results)
    meta = receipt['metadata']
    if receipt['schemaVersion'] != 'organization-diagnosis-results/v1':
        raise ValueError('wrong diagnosis schema')
    if meta['planSha256'] != hashlib.sha256(plan_bytes).hexdigest() or meta['frozenManifestSha256'] != hashlib.sha256(Path(manifest).read_bytes()).hexdigest():
        raise ValueError('diagnosis input identity changed')
    for field, name in [('modelSha256', 'model_sha256'), ('tokenizerSha256', 'tokenizer_sha256')]:
        if meta[field] != plan['model'][name]:
            raise ValueError('model artifact pin changed')
    subprocess.run(['git', 'merge-base', '--is-ancestor', meta['planCommit'], meta['producerCommit']], check=True, capture_output=True)
    committed_plan = subprocess.run(['git', 'show', meta['planCommit'] + ':docs/refactor/evidence/organization-diagnosis-20261013/PLAN.json'], check=True, capture_output=True).stdout
    if committed_plan != plan_bytes or meta['planCommit'] == meta['producerCommit']:
        raise ValueError('plan does not precede producer')
    expected = list(itertools.product(frozen['language']['paths'], frozen['language']['quality'], plan['channels'], plan['temperatures']))
    if set(files) != {f'diagnosis-{i}.json' for i in range(len(expected))} or meta['cases'] != len(expected):
        raise ValueError('missing, extra or dropped diagnostic conditions')
    language = []
    baseline_matches = 0
    for i, condition in enumerate(expected):
        artifact = load(files[f'diagnosis-{i}.json'])
        if (artifact['path'], artifact['quality'], artifact['channel'], number(artifact['temperature'])) != condition:
            raise ValueError('diagnostic condition substituted')
        row = {k: artifact[k] for k in ['path', 'quality', 'channel', 'temperature', 'status']}
        if artifact['status'] == 'failed':
            language.append({**row, 'reason': artifact['reason']})
            continue
        if artifact['status'] != 'complete':
            raise ValueError('unknown diagnostic outcome')
        result = artifact['result']
        if (len(result['rows']) != len(artifact['path']) or result['orderKind'] != 'TextDiscourseRanks'
                or result['axis'] != 'Discourse'
                or {number(r['position']) for r in result['order']} != set(range(len(frozen['language']['sentences'])))):
            raise ValueError('frozen language inventory changed')
        diagnostics = mass_diagnostics(result, artifact['path'], tolerance)
        costs = cost_readback(artifact, tolerance)
        if artifact['channel'] == 'lexical' and number(artifact['temperature']) in frozen['language']['temperatures']:
            prior = next(r for r in baseline['language'] if (r['path'], r['quality'], r['temperature']) == (artifact['path'], artifact['quality'], number(artifact['temperature'])))
            if prior['status'] != 'complete' or any(abs(prior['counts'][c] - diagnostics['counts'][c]) > tolerance for c in CATEGORIES):
                raise ValueError('original lexical baseline no longer reproduced')
            original = next(r for r in baseline_language if (r['truth'], r['quality'], number(r['temperature'])) == (artifact['path'], artifact['quality'], number(artifact['temperature'])))
            if artifact['result'] != original['result']:
                raise ValueError('original lexical rows or metadata changed')
            baseline_matches += 1
        language.append({**row, **diagnostics, **costs})
    contrasts = []
    for channel, quality, temperature in itertools.product(plan['channels'], frozen['language']['quality'], plan['temperatures']):
        pair = [next(r for r in language if (r['channel'], r['quality'], number(r['temperature']), r['path']) == (channel, quality, temperature, path)) for path in frozen['language']['paths'][:2]]
        ready = all(r['status'] == 'complete' for r in pair)
        contrasts.append({'channel': channel, 'quality': quality, 'temperature': temperature,
                          'retained_reversal_contrast': (pair[1]['counts']['Backward'] - pair[0]['counts']['Backward']) / 3 if ready else None,
                          'zero_temperature_limit_contrast': (pair[1]['zero_temperature_limit_counts']['Backward'] - pair[0]['zero_temperature_limit_counts']['Backward']) / 3 if ready else None})
    g = plan['generative_control']
    control = [generative_control(g['destinations'], g['units'], p) for p in g['correct_probabilities']]
    for c in control:
        if abs(c['total_probability'] - 1) > tolerance or max(abs(a - b) for a, b in zip(c['population_truth'], c['population_posterior_expected_counts'])) > tolerance:
            raise ValueError('known calibrated population control failed')
    return {'schema': 'organization-diagnosis-report/v1', 'provenance': meta,
            'packet': packet_diagnosis(baseline, tolerance), 'language': language, 'contrasts': contrasts,
            'generative_control': control,
            'dependence_control': {'marginals': [[0.5, 0.5], [0.5, 0.5]], 'same_target_independent': 0.5,
                                   'same_target_shared_draw': 1.0, 'same_target_opposite_draw': 0.0,
                                   'interpretation': 'Analytical joint-distribution examples, not an executed production joint-readout test'},
            'totals': {'language_conditions': len(language), 'language_failures': sum(r['status'] == 'failed' for r in language),
                       'original_lexical_conditions_reproduced': baseline_matches},
            'interpretation': 'Exploratory diagnostic controls; no selected default, human efficacy or calibration claim'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--baseline-results', type=Path, required=True)
    p.add_argument('--plan', type=Path, required=True)
    p.add_argument('--results', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    report = evaluate_diagnosis(a.manifest, a.baseline_results, a.plan, a.results)
    a.output.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(json.dumps(report['totals'], sort_keys=True))


if __name__ == '__main__':
    main()
