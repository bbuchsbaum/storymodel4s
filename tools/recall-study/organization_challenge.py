#!/usr/bin/env python3
"""Fit on development only, then audit a frozen native synthetic challenge without changing defaults."""
import argparse
from collections import defaultdict
from functools import lru_cache
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess

from organization_diagnosis import cost_readback
from read_organization import check_result, load, number, verified_files

PLAN_PATH = 'docs/refactor/evidence/organization-challenge-20261014/PLAN.json'
MANIFEST_PATH = 'tools/recall-study/fixtures/organization-challenge/manifest.json'
FIT_PATH = 'docs/refactor/evidence/organization-challenge-20261014/FIT.json'
CHANNELS = ['lexical', 'minilm', 'repaired-minilm']
EXTERNALS = {'ext:' + x for x in ('Association', 'Commentary', 'SourceConsistentInference', 'Intrusion', 'Uninterpretable')}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def committed(revision, path):
    return subprocess.run(['git', 'show', revision + ':' + path], check=True, capture_output=True).stdout


def ancestor(before, after):
    subprocess.run(['git', 'merge-base', '--is-ancestor', before, after], check=True, capture_output=True)


@lru_cache(maxsize=None)
def edit_distance(a, b):
    """Slow independent recursive oracle for the tiny source-name vocabulary."""
    if not a or not b:
        return len(a) + len(b)
    return min(edit_distance(a[1:], b) + 1, edit_distance(a, b[1:]) + 1,
               edit_distance(a[1:], b[1:]) + (a[0] != b[0]))


def normalized(source, recall, channel):
    def token(s):
        match = re.match(r'^[A-Z][a-z]+\b', s)
        return match[0] if match else None
    names = set(filter(None, map(token, source)))
    out, reasons, edits = [], [], []
    for i, sentence in enumerate(recall):
        name, after, reason = token(sentence), sentence, 'unprocessed'
        if channel == 'repaired-minilm':
            reason = 'no-token'
            if name:
                candidates = [n for n in names if edit_distance(n, name) <= 1 or
                              (n.startswith(name) and len(name) * 2 >= len(n))]
                reason = ('exact-name' if name in names else 'unique-match' if len(candidates) == 1
                          else 'no-match' if not candidates else 'ambiguous-match')
                if reason == 'unique-match':
                    after = candidates[0] + sentence[len(name):]
        if after != sentence:
            edits.append({'index': i, 'beforeSha256': sha(sentence.encode()), 'afterSha256': sha(after.encode())})
        out.append(after)
        reasons.append(reason)
    return out, reasons, edits


def logsum(values):
    if not values:
        return -math.inf
    high = max(values)
    return high + math.log(math.fsum(math.exp(x - high) for x in values))


def log_distribution(states, positions, temperature):
    """Preserve state multiplicity, then aggregate five external states into one scored class."""
    if not math.isfinite(temperature) or temperature <= 0:
        raise ValueError('invalid temperature')
    admitted = [s for s in states if not s['excluded']]
    if not admitted or any(s['destination'] == 'ext:Unranked' for s in admitted):
        return None
    low = min(number(s['cost']) for s in admitted)
    logits = [-(number(s['cost']) - low) / temperature for s in admitted]
    denominator = logsum(logits)
    buckets = defaultdict(list)
    for s, value in zip(admitted, logits):
        dest = s['destination']
        if dest in EXTERNALS:
            key = 'external'
        elif dest in positions:
            key = str(positions[dest])
        else:
            raise ValueError('unexpected destination')
        buckets[key].append(value)
    return {key: logsum(buckets[key]) - denominator for key in ['0', '1', '2', '3', 'external']}


def read_data(manifest_path, plan_path, root, split, fit_path=None):
    manifest_bytes, plan_bytes = Path(manifest_path).read_bytes(), Path(plan_path).read_bytes()
    manifest, plan = load(manifest_bytes.decode()), load(plan_bytes.decode())
    if plan['manifest_sha256'] != sha(manifest_bytes):
        raise ValueError('frozen input mismatch')
    receipt, files = verified_files(root)
    meta = receipt['metadata']
    if (receipt['schemaVersion'] != 'organization-content-challenge-results/v1' or
            meta['split'] != split or meta['planSha256'] != sha(plan_bytes) or
            meta['manifestSha256'] != sha(manifest_bytes) or
            meta['modelSha256'] != plan['model']['model_sha256'] or
            meta['tokenizerSha256'] != plan['model']['tokenizer_sha256']):
        raise ValueError('producer input binding mismatch')
    ancestor(meta['planCommit'], meta['producerCommit'])
    if (meta['planCommit'] == meta['producerCommit'] or
            committed(meta['planCommit'], PLAN_PATH) != plan_bytes or
            committed(meta['planCommit'], MANIFEST_PATH) != manifest_bytes):
        raise ValueError('plan must be committed before producer')
    fit = None
    if split == 'heldout':
        if fit_path is None:
            raise ValueError('heldout requires committed development fit')
        fit_bytes = Path(fit_path).read_bytes()
        fit = load(fit_bytes.decode())
        ancestor(meta['producerCommit'], meta['fitCommit'])
        if (meta['fitCommit'] == meta['producerCommit'] or
                committed(meta['fitCommit'], FIT_PATH) != fit_bytes or
                meta['fitSha256'] != sha(fit_bytes) or fit['plan_sha256'] != sha(plan_bytes) or
                fit['producer_commit'] != meta['producerCommit']):
            raise ValueError('heldout fit binding mismatch')
    elif split != 'development' or fit_path is not None:
        raise ValueError('development fitting cannot consume heldout or supplied fit')
    groups = [g for g in manifest['groups'] if g['split'] == split]
    expected = []
    for group in groups:
        for case in group['cases']:
            for channel in plan['channels']:
                temperatures = [plan['stock_temperature']]
                if fit and fit['temperatures'][channel] not in temperatures:
                    temperatures.append(fit['temperatures'][channel])
                expected.extend((group, case, channel, t) for t in temperatures)
    if set(files) != {f'challenge-{i}.json' for i in range(len(expected))} or meta['cases'] != len(expected):
        raise ValueError('challenge inventory mismatch')
    if {p.name for p in Path(root).glob('*.json')} != set(files) | {'manifest.json'}:
        raise ValueError('unexpected challenge artifact')
    items = []
    for i, (group, case, channel, temperature) in enumerate(expected):
        item = load(files[f'challenge-{i}.json'])
        if (item['group'], item['condition'], item['split'], item['channel'], number(item['temperature'])) != (group['id'], case['condition'], split, channel, temperature):
            raise ValueError('condition substitution')
        processed, reasons, edits = normalized(group['source'], case['recall'], channel)
        if (item['sourceSha256'] != sha(' '.join(group['source']).encode()) or
                item['originalSha256'] != sha(' '.join(case['recall']).encode()) or
                item['processedSha256'] != sha(' '.join(processed).encode()) or
                item['normalizationReasons'] != reasons or item['edits'] != edits):
            raise ValueError('normalization provenance mismatch')
        item['labels'] = case['labels']
        if item['status'] == 'complete':
            result = item['result']
            if (len(result['rows']) != len(case['recall']) or result['orderKind'] != 'TextDiscourseRanks' or
                    result['axis'] != 'Discourse' or sorted(number(r['position']) for r in result['order']) != [0, 1, 2, 3]):
                raise ValueError('source or unit inventory mismatch')
            check_result(result, plan['tolerance'])
            if (len(item['costs']) != len(result['rows']) or
                    [r['unit'] for r in item['costs']] != [r['unit'] for r in result['rows']]):
                raise ValueError('row cost identity mismatch')
            verify_native_rows(item, plan['tolerance'])
            correspondence = [{'index': j, 'unit': row['unit'],
                               'originalClauseSha256': sha(case['recall'][j].encode()),
                               'processedClauseSha256': sha(processed[j].encode()),
                               'unitClauseSha256': sha(processed[j].strip().removesuffix('.').encode())}
                              for j, row in enumerate(result['rows'])]
            if item['unitCorrespondence'] != correspondence:
                raise ValueError('native clause correspondence mismatch')
        elif item['status'] != 'failed':
            raise ValueError('unknown outcome')
        items.append(item)
    return plan, meta, items


def verify_native_rows(item, tolerance):
    available = [(r, c) for r, c in zip(item['result']['rows'], item['costs']) if r['status'] == 'Available']
    item['verified_rows'] = len(available)
    item['maximum_mass_readback_error'] = None
    if available:
        subset = {**item, 'result': {**item['result'], 'rows': [r for r, _ in available]},
                  'costs': [c for _, c in available]}
        item['maximum_mass_readback_error'] = cost_readback(subset, tolerance)['maximum_mass_readback_error']


def scored_rows(item, temperature):
    if item['status'] != 'complete':
        return [None] * len(item['labels'])
    positions = {r['target']: int(number(r['position'])) for r in item['result']['order']}
    return [log_distribution(c['states'], positions, temperature) if row['status'] == 'Available' else None
            for c, row in zip(item['costs'], item['result']['rows'])]


def fit_temperatures(items, grid):
    if not items or any(item['split'] != 'development' for item in items):
        raise ValueError('fit accepts development only')
    answer, traces = {}, {}
    for channel in CHANNELS:
        observations = [i for i in items if i['channel'] == channel]
        if not observations:
            raise ValueError('missing development channel')
        trace = []
        for t in grid:
            losses = []
            for item in observations:
                for label, row in zip(item['labels'], scored_rows(item, t)):
                    if label['kind'] == 'point':
                        if row is None:
                            raise ValueError('unavailable development row prevents fit')
                        losses.append(-row[str(label['target'])])
            if not losses:
                raise ValueError('no development point labels')
            nll = math.fsum(losses) / len(losses)
            trace.append({'temperature': t, 'nll': nll if math.isfinite(nll) else None,
                          'infinite_rows': sum(not math.isfinite(v) for v in losses), 'rows': len(losses)})
        finite = [v for v in trace if v['nll'] is not None]
        if not finite:
            raise ValueError('all development objectives are nonfinite')
        answer[channel] = min(finite, key=lambda v: (v['nll'], v['temperature']))['temperature']
        traces[channel] = trace
    return answer, traces


def summarize(item, temperature):
    points, ambiguous = [], []
    for index, (label, logs) in enumerate(zip(item['labels'], scored_rows(item, temperature))):
        row = {'index': index, 'label': label, 'available': logs is not None}
        if logs is not None:
            masses = {k: math.exp(v) for k, v in logs.items()}
            if label['kind'] == 'point':
                key = str(label['target'])
                loss = -logs[key]
                best = max(masses.values())
                winners = [k for k, p in masses.items() if abs(best - p) <= 1e-10]
                row.update(nll=loss if math.isfinite(loss) else None, infinite_nll=not math.isfinite(loss),
                           brier=math.fsum((p - (k == key)) ** 2 for k, p in masses.items()),
                           true_mass=masses[key], unique_correct=winners == [key],
                           tied_best=key in winners and len(winners) > 1, top_classes=winners,
                           external_mass=masses['external'])
            else:
                values = [masses[str(t)] for t in label['targets']]
                total = math.fsum(values)
                row.update(set_mass=total, outside_set_mass=1-total,
                           within_set_max=max(values)/total if total else None)
        (points if label['kind'] == 'point' else ambiguous).append(row)
    return {'group': item['group'], 'condition': item['condition'], 'channel': item['channel'],
            'temperature': temperature, 'status': item['status'], 'edits': item['edits'],
            'normalization_reasons': item['normalizationReasons'], 'point_rows': points, 'ambiguous_rows': ambiguous,
            'count_discrepancy': ([number(item['result']['counts'][k]) - n for k, n in zip(['Backward', 'Forward', 'SameTarget'], [0,3,0])]
                                  if item['status'] == 'complete' and item['condition'] in ['exact','paraphrase','truncation','typo'] else None)}


def aggregate(cases):
    rows = [r for c in cases for r in c['point_rows']]
    available = [r for r in rows if r['available']]
    complete = len(rows) == len(available) and bool(rows)
    finite = complete and not any(r['infinite_nll'] for r in available)
    return {'expected_rows': len(rows), 'available_rows': len(available),
            'infinite_nll_rows': sum(r['infinite_nll'] for r in available),
            'nll': math.fsum(r['nll'] for r in available)/len(rows) if finite else None,
            'brier': math.fsum(r['brier'] for r in available)/len(rows) if complete else None,
            'unique_correct': sum(r['unique_correct'] for r in available),
            'tied_best': sum(r['tied_best'] for r in available)}


def preflight_fit(fit_bytes, fit_commit, producer, plan_bytes):
    fit = load(fit_bytes.decode())
    ancestor(producer, fit_commit)
    if (fit_commit == producer or committed(fit_commit, FIT_PATH) != fit_bytes or
            fit['producer_commit'] != producer or fit['plan_sha256'] != sha(plan_bytes)):
        raise ValueError('fit must already be committed before heldout launch')


def produce(args):
    """Checked launch, so freeze verification precedes even opening the native model."""
    if args.split not in ('development','heldout') or not all([args.model,args.tokenizer,args.plan_commit]):
        raise ValueError('production requires split, model, tokenizer and plan commit')
    if subprocess.run(['git','status','--porcelain'],check=True,capture_output=True).stdout.strip():
        raise ValueError('native producer requires a clean standalone checkout')
    head = subprocess.run(['git','rev-parse','HEAD'],check=True,capture_output=True,text=True).stdout.strip()
    plan_bytes, manifest_bytes = Path(args.plan).read_bytes(), Path(args.manifest).read_bytes()
    plan = load(plan_bytes.decode())
    if (committed(args.plan_commit, PLAN_PATH) != plan_bytes or
            committed(args.plan_commit, MANIFEST_PATH) != manifest_bytes or
            plan['manifest_sha256'] != sha(manifest_bytes)):
        raise ValueError('launch inputs differ from frozen edition')
    ancestor(args.plan_commit, head)
    fit_commit, producer = '-', head
    if args.split == 'heldout':
        if args.fit is None:
            raise ValueError('heldout launch requires fit')
        fit_bytes = Path(args.fit).read_bytes()
        producer = load(fit_bytes.decode())['producer_commit']
        fit_commit = head
        preflight_fit(fit_bytes, fit_commit, producer, plan_bytes)
        changes = subprocess.run(['git','diff','--name-only',producer,head],check=True,capture_output=True,text=True).stdout.splitlines()
        if any(not p.startswith(('docs/', '.mote/')) for p in changes):
            raise ValueError('producer code changed after development freeze')
    elif args.fit is not None:
        raise ValueError('development launch cannot use a fit')
    for flag,key in [(args.model,'model_sha256'),(args.tokenizer,'tokenizer_sha256')]:
        if sha(Path(flag).read_bytes()) != plan['model'][key]:
            raise ValueError('model asset mismatch')
    if Path(args.output).exists():
        raise ValueError('refuse overwriting prior experiment')
    receipt = {'schema':'organization-challenge-preflight/v1','split':args.split,
               'head':head,'producer':producer,'plan_commit':args.plan_commit,
               'plan_sha256':sha(plan_bytes),'manifest_sha256':sha(manifest_bytes),
               'fit_commit':fit_commit,'fit_sha256':sha(Path(args.fit).read_bytes()) if args.fit else None}
    preflight = Path(args.output + '.preflight.json')
    with preflight.open('x') as out:
        json.dump(receipt,out,indent=2); out.write('\n')
    argv = [str(Path(p).resolve()) for p in (args.manifest,args.plan,args.output,args.model,args.tokenizer)]
    argv += [producer,args.plan_commit,args.split,str(Path(args.fit).resolve()) if args.fit else '-',fit_commit]
    if any(re.search(r'[^a-zA-Z0-9_./:-]', a) for a in argv):
        raise ValueError('unsupported sbt argument character')
    command = 'pipeline/Test/runMain storymodel4s.pipeline.OrganizationChallengeWitness ' + ' '.join(argv)
    print(json.dumps({'preflight':str(preflight),**receipt}),flush=True)
    subprocess.run(['sbt','-batch','set ThisBuild / tlFatalWarnings := true',command],check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['produce', 'fit', 'evaluate'])
    parser.add_argument('--manifest', default=MANIFEST_PATH)
    parser.add_argument('--plan', default=PLAN_PATH)
    parser.add_argument('--results')
    parser.add_argument('--split', choices=['development','heldout'])
    parser.add_argument('--model')
    parser.add_argument('--tokenizer')
    parser.add_argument('--plan-commit')
    parser.add_argument('--fit', default=None)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    if args.action == 'produce':
        produce(args)
        return
    split = 'development' if args.action == 'fit' else 'heldout'
    plan, meta, items = read_data(args.manifest, args.plan, args.results, split, args.fit)
    if args.action == 'fit':
        temperatures, trace = fit_temperatures(items, plan['temperature_grid'])
        report = {'schema':'organization-content-challenge-fit/v1', 'producer_commit':meta['producerCommit'],
                  'plan_sha256':meta['planSha256'], 'manifest_sha256':meta['manifestSha256'],
                  'development_manifest_sha256':sha((Path(args.results)/'manifest.json').read_bytes()),
                  'temperatures':temperatures, 'grid':trace,
                  'stock': {ch:aggregate([summarize(i, plan['stock_temperature']) for i in items if i['channel']==ch]) for ch in CHANNELS}}
    else:
        fit = load(Path(args.fit).read_text())
        cases = []
        for item in items:
            for mode, t in [('stock', plan['stock_temperature']), ('fitted', fit['temperatures'][item['channel']])]:
                if number(item['temperature']) == t:
                    cases.append({**summarize(item,t), 'mode':mode})
        summary = []
        for channel in CHANNELS:
            for mode in ['stock','fitted']:
                selected = [c for c in cases if c['channel']==channel and c['mode']==mode]
                summary.append({'channel':channel,'mode':mode,**aggregate(selected),
                                'by_condition':{condition:aggregate([c for c in selected if c['condition']==condition]) for condition in sorted({c['condition'] for c in selected})}})
        report = {'schema':'organization-content-challenge-heldout/v1', 'metadata':meta,
                  'heldout_manifest_sha256':sha((Path(args.results)/'manifest.json').read_bytes()),
                  'conditions':len(items),'failed_conditions':sum(i['status']=='failed' for i in items),
                  'verified_native_rows':sum(i.get('verified_rows',0) for i in items),
                  'maximum_native_mass_readback_error':max((i['maximum_mass_readback_error'] for i in items if i.get('maximum_mass_readback_error') is not None),default=None),
                  'summary':summary,'cases':cases}
    with Path(args.output).open('x') as out:
        json.dump(report,out,indent=2,allow_nan=False)
        out.write('\n')
    print(json.dumps({'output':args.output,'action':args.action,'conditions':len(items)}))


if __name__ == '__main__':
    main()
