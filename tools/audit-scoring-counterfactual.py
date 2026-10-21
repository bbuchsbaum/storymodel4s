#!/usr/bin/env python3
"""Read retained scalar stage exports in place; never read/copy participant text.

This is an arithmetic counterfactual on retained nominations and semantic distances,
not a new retrieval run, an executed Scala producer, or an accuracy estimate.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def normalize(costs, temperature):
    least = min(costs.values())
    weights = {key: math.exp(-(value - least) / temperature) for key, value in costs.items()}
    total = math.fsum(weights.values())
    return {key: value / total for key, value in weights.items()}


def audit(root):
    stages_path = root / 'report.tsv.stages.json'
    inventory_path = root / 'inventory.json'
    stages = json.loads(stages_path.read_text())
    targets = {t['id']: t for t in json.loads(inventory_path.read_text())['targets']}
    temperature = stages['temperature']
    weights = {'Semantic': 1.0, 'Propositional': 0.5, 'Entity': 0.4,
               'Sensory': 0.15, 'Granularity': 0.3, 'Distortion': 1.0}
    cost_errors, mass_errors, rows = [], [], []
    for unit in stages['units']:
        states = unit['states']
        original_mass = normalize({s['state']: s['cost'] for s in states}, temperature)
        mass_errors.extend(abs(original_mass[s['state']] - s['localMass']) for s in states)
        source = [s for s in states if s['anchor'] is not None]
        for state in source:
            terms = state['terms']
            if not set(terms) <= set(weights):
                raise ValueError('unrecognized cost term; baseline formula not applicable')
            eligible = set(terms) | ({'Sensory'} if 'Sensory' in state['missingTerms'] else set())
            # Frozen producer EA function prior is zero; retain other rows for mass checks only.
            raw = math.fsum(value * weights[term] for term, value in terms.items())
            scaled = raw * math.fsum(weights[t] for t in eligible) / math.fsum(weights[t] for t in terms)
            if unit['unit'] == 92:
                cost_errors.append(abs(scaled - state['cost']))
        detail = {s['anchor']: s['terms']['Semantic'] for s in source
                  if targets[s['anchor']]['level'] == 0 and 'Semantic' in s['terms']}
        if len(detail) != sum(targets[s['anchor']]['level'] == 0 for s in source):
            raise ValueError('unexpected duplicate modes or missing semantic observation')
        if unit['unit'] in (92, 93):
            mass = normalize(detail, temperature)
            order = sorted(detail, key=lambda ref: (detail[ref], ref))
            rows.append({'unit': unit['unit'], 'candidate_count': len(detail),
                         'original_strongest_source': min(source, key=lambda s: s['cost'])['anchor'],
                         'detail_semantic_order': [
                             {'target': ref, 'semantic_cost': detail[ref],
                              'conditional_mass': mass[ref], 'rank': i + 1,
                              'part': targets[ref]['part'],
                              'start_tick': targets[ref]['startTick'],
                              'end_tick': targets[ref]['endTick']}
                             for i, ref in enumerate(order)]})
    if max(cost_errors) > 1e-12 or max(mass_errors) > 1e-12:
        raise ValueError('historical arithmetic witness failed')
    return {'kind': 'retained-candidate-semantic-counterfactual/v1',
            'limitations': ['Not fresh retrieval or Scala producer execution',
                            'Not independent accuracy validation',
                            'Retained mixed-grain reranker already influenced these semantic costs',
                            'Source membership unestablished'],
            'inputs': {'stages_sha256': digest(stages_path), 'inventory_sha256': digest(inventory_path)},
            'unit_count': len(stages['units']), 'temperature': temperature,
            'max_u92_cost_replay_error': max(cost_errors),
            'max_all_unit_mass_replay_error': max(mass_errors), 'diagnostics': rows}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    args = parser.parse_args()
    print(json.dumps(audit(args.run), indent=2, sort_keys=True))
