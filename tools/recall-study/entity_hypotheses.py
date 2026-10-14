#!/usr/bin/env python3
"""Development-only conditional identity scores, with unknown interpretation weights."""
import argparse
import itertools
import json
import math
from pathlib import Path
import re
import subprocess

from organization_challenge import (EXTERNALS, ancestor, committed, edit_distance,
                                    log_distribution, sha, verify_native_rows)
from read_organization import check_result, load, number, verified_files

PLAN = 'docs/refactor/evidence/entity-hypotheses-20261014/PLAN.json'
MANIFEST = 'tools/recall-study/fixtures/organization-challenge/manifest.json'
KEYS = ['0', '1', '2', '3', 'external']
CATEGORIES = ['Backward', 'Forward', 'SameTarget', 'External']
MODEL = '6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452'
TOKENIZER = 'be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037'


def token(sentence):
    match = re.match(r'^[A-Z][a-z]+\b', sentence)
    return match[0] if match else None


def hypotheses(source, sentence):
    """Candidate retrieval is not an exhaustive identity adjudication."""
    names, name = sorted(set(filter(None, map(token, source)))), token(sentence)
    if name is None:
        return [{'kind': 'unresolved', 'name': None, 'rendered': sentence}]
    if name in names:
        return [{'kind': 'known', 'name': name, 'rendered': sentence}]
    candidates = [n for n in names if edit_distance(name, n) <= 1 or
                  (n.startswith(name) and len(name) * 2 >= len(n))]
    return ([{'kind': 'unresolved', 'name': None, 'rendered': sentence},
             {'kind': 'distinct', 'name': None, 'rendered': sentence}] +
            [{'kind': 'known', 'name': n, 'rendered': n + sentence[len(name):]} for n in candidates])


def variants(source, original):
    result = [original]
    for row, sentence in enumerate(original):
        for h in hypotheses(source, sentence):
            candidate = original[:row] + [h['rendered']] + original[row + 1:]
            if candidate not in result:
                result.append(candidate)
    return result


def condition(states, positions, source, hypothesis, temperature):
    """Derived fixture compatibility, never an original native certificate."""
    kind = hypothesis['kind']
    if kind not in ('known', 'distinct', 'unresolved'):
        raise ValueError('unknown identity hypothesis')
    allowed = ({i for i, sentence in enumerate(source) if token(sentence) == hypothesis['name']}
               if kind == 'known' else set() if kind == 'distinct' else set(range(len(source))))
    if kind == 'known' and not allowed:
        raise ValueError('identity absent from source')
    for state in states:
        number(state['cost'])
        if state['destination'] not in positions and state['destination'] not in EXTERNALS | {'ext:Unranked'}:
            raise ValueError('unrecognized destination')
    kept = [s for s in states if s['destination'].startswith('ext:') or positions[s['destination']] in allowed]
    return log_distribution(kept, positions, temperature)


def masses(logs):
    return None if logs is None else {k: math.exp(v) for k, v in logs.items()}


def winners(row):
    if row is None:
        return []
    high = max(row.values())
    return [k for k in KEYS if abs(row[k] - high) <= 1e-10]


def robust_summary(rows):
    choices = [winners(row) for row in rows]
    robust = (choices[0][0] if choices and len(choices[0]) == 1 and
              all(c == choices[0] for c in choices) else None)
    return {'interpretation_weights': None, 'weight_status': 'unknown',
            'point_distribution': None, 'robust_winner': robust,
            'mass_ranges': ({k: [min(r[k] for r in rows), max(r[k] for r in rows)] for k in KEYS}
                            if rows and all(r is not None for r in rows) else None)}


def counts(rows):
    result = dict.fromkeys(CATEGORIES, 0.0)
    measured = 0
    for left, right in zip(rows, rows[1:]):
        if left is None or right is None:
            continue
        measured += 1
        for a, b in itertools.product(KEYS, repeat=2):
            kind = ('External' if 'external' in (a, b) else 'SameTarget' if a == b
                    else 'Forward' if int(b) > int(a) else 'Backward')
            result[kind] += left[a] * right[b]
    return {'counts': result, 'measured_adjacencies': measured,
            'unavailable_adjacencies': max(0, len(rows)-1) - measured}


def count_ranges(options):
    if any(not row for row in options):
        raise ValueError('empty hypothesis inventory')
    assignments = [counts(rows) for rows in itertools.product(*options)]
    complete = all(a['unavailable_adjacencies'] == 0 for a in assignments)
    intervals = {k: [min(a['counts'][k] for a in assignments), max(a['counts'][k] for a in assignments)]
                 for k in CATEGORIES}
    return {'joint_interpretation_weights': None, 'weight_status': 'unknown',
            'point_counts': None, 'assignment_count': len(assignments),
            'ranges': intervals if complete else None,
            'measured_adjacency_ranges': intervals,
            'measured_adjacency_range': [min(a['measured_adjacencies'] for a in assignments),
                                         max(a['measured_adjacencies'] for a in assignments)],
            'complete': complete,
            'interpretation': 'Extrema of conditional expected counts over complete identity assignments; not confidence intervals or bounds on realized truth'}


def score(logs, label):
    if logs is None:
        return {'available': False}
    row = masses(logs)
    if label['kind'] == 'point':
        key = str(label['target'])
        loss = -logs[key]
        return {'available': True, 'nll': loss if math.isfinite(loss) else None,
                'infinite_nll': not math.isfinite(loss),
                'brier': math.fsum((row[k] - (k == key)) ** 2 for k in KEYS),
                'true_mass': row[key], 'unique_correct': winners(row) == [key],
                'winners': winners(row)}
    value = math.fsum(row[str(t)] for t in label['targets'])
    return {'available': True, 'set_mass': value, 'outside_set_mass': 1-value,
            'within_set_max': max(row[str(t)] for t in label['targets'])/value if value else None,
            'winners': winners(row)}


def inventory(manifest):
    groups = [g for g in manifest['groups'] if g['split'] == 'development']
    if len(groups) != 4 or any(len(g['source']) != 4 or len(g['cases']) != 7 for g in groups):
        raise ValueError('development inventory mismatch')
    return [(g, c, v) for g in groups for c in g['cases'] for v in variants(g['source'], c['recall'])]


def state_signature(item, row):
    return sorted((s['destination'], number(s['cost']), s['excluded']) for s in item['costs'][row]['states'])


def read_native(root, manifest_path=MANIFEST, plan_path=PLAN):
    manifest_bytes, plan_bytes = Path(manifest_path).read_bytes(), Path(plan_path).read_bytes()
    manifest, plan = load(manifest_bytes.decode()), load(plan_bytes.decode())
    if plan['split'] != 'development' or plan['manifest_sha256'] != sha(manifest_bytes):
        raise ValueError('frozen input mismatch')
    receipt, files = verified_files(root)
    meta = receipt['metadata']
    if (receipt['schemaVersion'] != 'entity-hypothesis-native/v1' or meta['planSha256'] != sha(plan_bytes) or
            meta['manifestSha256'] != sha(manifest_bytes) or meta['modelSha256'] != MODEL or meta['tokenizerSha256'] != TOKENIZER):
        raise ValueError('native input mismatch')
    ancestor(meta['planCommit'], meta['producerCommit'])
    if (meta['planCommit'] == meta['producerCommit'] or committed(meta['planCommit'], PLAN) != plan_bytes or
            committed(meta['producerCommit'], MANIFEST) != manifest_bytes):
        raise ValueError('plan freeze mismatch')
    expected = inventory(manifest)
    if (set(files) != {f'variant-{i}.json' for i in range(len(expected))} or meta['cases'] != len(expected) or
            {p.name for p in Path(root).glob('*.json')} != set(files) | {'manifest.json'}):
        raise ValueError('native variant inventory mismatch')
    items, failures, verified_rows, max_error = {}, [], 0, None
    for i, (group, case, processed) in enumerate(expected):
        item = load(files[f'variant-{i}.json'])
        if (item['group'] != group['id'] or item['condition'] != case['condition'] or
                item['sourceSha256'] != sha(' '.join(group['source']).encode()) or
                item['originalSha256'] != sha(' '.join(case['recall']).encode()) or
                item['processedSha256'] != sha(' '.join(processed).encode()) or
                item['processedClauseSha256'] != [sha(s.encode()) for s in processed]):
            raise ValueError('native variant correspondence mismatch')
        key = (group['id'], case['condition'], tuple(processed))
        items[key] = item
        if item['status'] == 'failed':
            failures.append({'file': f'variant-{i}.json', 'reason': item['reasonSha256']})
            continue
        if item['status'] != 'complete':
            raise ValueError('unknown native status')
        result = item['nativeResult']
        check_result(result)
        if (result['orderKind'] != 'TextDiscourseRanks' or result['axis'] != 'Discourse' or
                sorted(number(x['position']) for x in result['order']) != [0,1,2,3] or len(result['rows']) != 4 or
                [r['unit'] for r in result['rows']] != [r['unit'] for r in item['costs']]):
            raise ValueError('native row/order inventory mismatch')
        correspondence = [{'index': j, 'unit': row['unit'],
                           'originalClauseSha256': sha(case['recall'][j].encode()),
                           'processedClauseSha256': sha(processed[j].encode()),
                           'unitClauseSha256': sha(processed[j].strip().removesuffix('.').encode())}
                          for j,row in enumerate(result['rows'])]
        if item['unitCorrespondence'] != correspondence:
            raise ValueError('native clause binding mismatch')
        probe = {'result': result, 'costs': item['costs'], 'temperature': .15}
        verify_native_rows(probe, 1e-10)
        verified_rows += probe['verified_rows']
        if probe['verified_rows']:
            error = probe['maximum_mass_readback_error']
            max_error = error if max_error is None else max(max_error, error)
    # Single-row renderings must not change other rows' content costs. This licenses
    # recombining their local conditional rows, not a new native joint certificate.
    unchanged = 0
    for group,case,processed in expected:
        item = items[group['id'],case['condition'],tuple(processed)]
        base = items[group['id'],case['condition'],tuple(case['recall'])]
        if processed == case['recall'] or item['status'] != 'complete' or base['status'] != 'complete':
            continue
        for row,(a,b) in enumerate(zip(processed,case['recall'])):
            if a == b:
                if state_signature(item,row) != state_signature(base,row):
                    raise ValueError('local row factorization failed')
                unchanged += 1
    return manifest,plan,meta,items,{'native_variants':len(expected),'failures':failures,
                                   'verified_rows':verified_rows,'maximum_mass_error':max_error,
                                   'unchanged_row_cost_parities':unchanged}


def evaluate(root, manifest_path=MANIFEST, plan_path=PLAN):
    manifest,plan,meta,items,validation = read_native(root,manifest_path,plan_path)
    cases=[]
    for group in manifest['groups']:
        if group['split'] != 'development':
            continue
        for case in group['cases']:
            source,original=group['source'],case['recall']
            baseline=items[group['id'],case['condition'],tuple(original)]
            for temperature in plan['temperatures']:
                rows,baseline_rows,options=[],[],[]
                for row,sentence in enumerate(original):
                    def native_logs(item,hypothesis):
                        if item['status'] != 'complete' or item['nativeResult']['rows'][row]['status'] != 'Available':
                            return None
                        positions={r['target']:int(number(r['position'])) for r in item['nativeResult']['order']}
                        return condition(item['costs'][row]['states'],positions,source,hypothesis,temperature)
                    base_logs=native_logs(baseline,{'kind':'unresolved','name':None})
                    baseline_rows.append(masses(base_logs))
                    outputs=[]
                    for h in hypotheses(source,sentence):
                        processed=original[:row]+[h['rendered']]+original[row+1:]
                        native=items[group['id'],case['condition'],tuple(processed)]
                        logs=native_logs(native,h)
                        outputs.append({'kind':h['kind'],'identity_sha256':sha(h['name'].encode()) if h['name'] else None,
                                        'rendered_clause_sha256':sha(h['rendered'].encode()),
                                        'native_variant_sha256':sha(' '.join(processed).encode()),
                                        'mass':masses(logs),'evaluation':score(logs,case['labels'][row])})
                    option=[o['mass'] for o in outputs];options.append(option)
                    rows.append({'index':row,'original_clause_sha256':sha(sentence.encode()),
                                 'label':case['labels'][row], 'baseline_mass':masses(base_logs),
                                 'baseline_evaluation':score(base_logs,case['labels'][row]),
                                 'hypotheses':outputs,**robust_summary(option)})
                truth=({'Backward':0,'Forward':3,'SameTarget':0,'External':0}
                       if all(l['kind']=='point' and l['target']==i for i,l in enumerate(case['labels'])) else None)
                cases.append({'group':group['id'],'condition':case['condition'],'temperature':temperature,
                              'rows':rows,'baseline_counts':counts(baseline_rows),'conditional_organization':count_ranges(options),
                              'point_source_path_truth':truth})
    return {'schema':'entity-hypothesis-evaluation/v1','authority':'development-only diagnostic assumptions',
            'native_metadata':meta,'validation':validation,'cases':cases,
            'promotion':'rejected: interpretation weights and exhaustive semantic identity are not established'}


def produce(args):
    if subprocess.check_output(['git','status','--porcelain']).strip():
        raise ValueError('native launch requires clean standalone checkout')
    head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    pb,mb=Path(args.plan).read_bytes(),Path(args.manifest).read_bytes()
    plan=load(pb.decode())
    if committed(args.plan_commit,PLAN)!=pb or plan['split']!='development' or plan['manifest_sha256']!=sha(mb):
        raise ValueError('launch plan mismatch')
    ancestor(args.plan_commit,head)
    if args.plan_commit==head or committed(head,MANIFEST)!=mb:
        raise ValueError('plan must precede producer')
    for path,digest in [(args.model,MODEL),(args.tokenizer,TOKENIZER)]:
        if sha(Path(path).read_bytes())!=digest:
            raise ValueError('asset checksum mismatch')
    if Path(args.output).exists():
        raise ValueError('refuse overwriting experiment')
    receipt={'head':head,'plan_commit':args.plan_commit,'plan_sha256':sha(pb),'manifest_sha256':sha(mb),
             'split':'development','expected_variants':len(inventory(load(mb.decode())))}
    with Path(args.output+'.preflight.json').open('x') as f:
        json.dump(receipt,f,indent=2);f.write('\n')
    argv=[str(Path(p).resolve()) for p in [args.manifest,args.plan,args.output,args.model,args.tokenizer]]+[head,args.plan_commit]
    if any(re.search(r'[^a-zA-Z0-9_./:-]',a) for a in argv):
        raise ValueError('unsupported sbt argument character')
    subprocess.run(['sbt','-batch','set ThisBuild / tlFatalWarnings := true',
                    'pipeline/Test/runMain storymodel4s.pipeline.EntityHypothesisWitness '+' '.join(argv)],check=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['produce','evaluate'])
    parser.add_argument('--manifest',default=MANIFEST)
    parser.add_argument('--plan',default=PLAN)
    parser.add_argument('--results')
    parser.add_argument('--model')
    parser.add_argument('--tokenizer')
    parser.add_argument('--plan-commit')
    parser.add_argument('--output',required=True)
    args=parser.parse_args()
    if args.action=='produce':
        produce(args)
    else:
        result=evaluate(args.results,args.manifest,args.plan)
        with Path(args.output).open('x') as f:
            json.dump(result,f,indent=2,allow_nan=False);f.write('\n')
        print(json.dumps(result['validation']))


if __name__=='__main__':
    main()
