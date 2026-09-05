#!/usr/bin/env python3
"""Trace Film Festival decisions from saved outputs; never invent an unrecorded stage.

Per-unit output stays local. Summary is content-free. Historical posterior support is NOT a
nomination record. Source identities omit replay ordinals so cartoon removal cannot turn a
renumbered unchanged candidate into a different piece of evidence.
"""
import argparse
import collections
import csv
import hashlib
import json
import math
from pathlib import Path
import struct

import filmfest_gold_film as gold


def read_tsv(path):
    with Path(path).open(newline='', encoding='utf-8') as f:
        return list(csv.DictReader(f, delimiter='\t', quoting=csv.QUOTE_NONE))


def digest_value(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def ieee(value):
    if not isinstance(value, str) or len(value) != 18 or not value.startswith('0x'):
        raise ValueError('expected canonical IEEE-754 value')
    result = struct.unpack('>d', bytes.fromhex(value[2:]))[0]
    if not math.isfinite(result):
        raise ValueError('nonfinite voyage value')
    return result


def ref_key(ref):
    prefixes = {'Situation': 'sit:', 'Segment': 'seg:'}
    return prefixes[ref['type']] + ref['id']


def source_index(path):
    rows = read_tsv(path)
    if [int(r['segment']) for r in rows] != list(range(1, len(rows)+1)) or not rows:
        raise ValueError('source index must have consecutive nonempty replay ordinals')
    refs, groups = {}, collections.defaultdict(list)
    for r in rows:
        film = gold.gold_code(int(r['film'].split('.')[0]))
        identity = digest_value({k: v for k, v in r.items() if k != 'segment'})
        refs[f"sit:filmfest:seg:{int(r['segment']):04d}"] = {'identity': identity, 'film': film,
            'scene': int(r['scene_number']) if r['scene_number'] else None}
        if r['scene_number']:
            groups[int(r['scene_number'])].append((identity, film))
    for scene, members in groups.items():
        films = {f for _, f in members}
        if len(films) != 1:
            raise ValueError('source scene spans films')
        refs[f'seg:filmfest:scene:{scene:03d}'] = {
            'identity': digest_value(['scene', scene, sorted(members)]), 'film': films.pop(), 'scene': scene}
    return refs


def unique(rows, key):
    out = {}
    for row in rows:
        k = row[key]
        if k in out:
            raise ValueError(f'duplicate {key}: {k}')
        out[k] = row
    return out


def load_arm(directory, annotation, gold_rows, ranges):
    directory = Path(directory).resolve()
    annotation = Path(annotation).resolve()
    refs = source_index(annotation)
    scored, exclusions = gold.score(directory, gold_rows, ranges)
    eligible = {tuple(u['key']): u for u in scored}
    output, inputs = [], {str(annotation): gold.digest(annotation)}
    for report in sorted(directory.glob('recall-map-*.tsv')):
        sub = report.stem.removeprefix('recall-map-')
        vpath = Path(str(report)+'.voyage.json')
        spath = Path(str(report)+'.stages.json')
        voyage = json.loads(vpath.read_text())
        if (voyage['schema'], voyage['schemaVersion']) != ('storymodel4s.view.recall-voyage', 1):
            raise ValueError('unsupported voyage schema')
        for p in (report, vpath):
            inputs[str(p)] = gold.digest(p)
        units = unique(voyage['units'], 'ordinal')
        posteriors = unique(voyage['rows'], 'unit')
        decisions = unique(voyage['decisions'], 'unit')
        reports = unique(read_tsv(report), 'unit')
        if set(map(int, reports)) != set(units) or set(posteriors) != {u['id'] for u in units.values()} or set(decisions) != set(posteriors):
            raise ValueError('report/voyage unit population differs')
        traces = None
        if spath.exists():
            trace = json.loads(spath.read_text())
            if trace['schema'] != 'storymodel4s.bench.stage-trace/v1' or trace['refinementPasses'] != 0:
                raise ValueError('unsupported stage trace or refined costs')
            if trace.get('reportSha256') != gold.digest(report) or trace.get('sourceInputSha256') != gold.digest(annotation):
                raise ValueError('trace report or source checksum differs')
            temperature = gold.finite(trace['temperature'])
            if temperature <= 0: raise ValueError('trace temperature must be positive')
            traces = unique(trace['units'], 'unit')
            if set(traces) != set(units):
                raise ValueError('stage trace unit population differs')
            inputs[str(spath)] = gold.digest(spath)
        for ordinal, vu in sorted(units.items()):
            r = reports[str(ordinal)]
            if (r['recallText'] != vu['text'] or gold.finite(r['recallOnsetSeconds']) != ieee(vu['onset'])
                    or gold.finite(r['recallLastWordOnsetSeconds']) != ieee(vu['lastWordOnset'])):
                raise ValueError('report/voyage recall content or timing differs')
            key = (sub, str(ordinal))
            if key not in eligible:
                continue
            e = eligible[key]
            masses, external = collections.Counter(), collections.Counter()
            posterior_identity = []
            for cell in posteriors[vu['id']]['mass']:
                m, s = ieee(cell['mass']), cell['state']
                if m < 0:
                    raise ValueError('negative posterior mass')
                if s['type'] == 'External':
                    external[s['state']] += m
                    identity = 'external:' + s['state']
                else:
                    k = ref_key(s['ref'])
                    if k not in refs:
                        raise ValueError('posterior anchor absent from this source index')
                    masses[k] += m
                    identity = refs[k]['identity'] + ':' + digest_value({key:v for key,v in s.items() if key!='ref'})
                posterior_identity.append((identity, m))
            if not math.isclose(sum(masses.values()) + sum(external.values()), 1., abs_tol=1e-9):
                raise ValueError('posterior mass does not sum to one')
            d = decisions[vu['id']]
            chosen = ref_key(d['anchor']) if d.get('anchor') else None
            if chosen is not None and chosen not in refs:
                raise ValueError('decision anchor absent from this source index')
            def film(k): return refs[k]['film'] if k else None
            if film(chosen) != e['modelMovie']:
                raise ValueError('decision film disagrees with scored report')
            ordered = sorted((k for k in masses if masses[k] > 0), key=lambda k: (-masses[k], k))
            argmax = ordered[0] if ordered else None
            support = [k for k in ordered if masses[k] > 0]
            film_mass = collections.Counter()
            for k, m in masses.items(): film_mass[film(k)] += m
            row = {'participant': sub, 'unit': ordinal, 'signature': e['signature'],
                'goldFilm': e['goldMovie'], 'finalFilm': film(chosen), 'posteriorArgmaxFilm': film(argmax),
                'origin': d['origin'], 'finalRef': chosen,
                'finalIdentity': refs[chosen]['identity'] if chosen else None,
                'finalMass': masses.get(chosen, 0.) if chosen else None,
                'finalInPosteriorSupport': chosen in support,
                'goldInPosteriorSupport': e['goldMovie'] in {film(k) for k in support},
                'goldPosteriorMass': film_mass[e['goldMovie']], 'filmMass': dict(film_mass),
                'anchorMass': dict(masses),
                'externalMass': sum(external.values()), 'externalStates': dict(external),
                'posteriorSupportIdentity': sorted(refs[k]['identity'] for k in support),
                'posteriorIdentity': sorted(posterior_identity),
                'nominationAvailable': traces is not None,
                'goldInCandidates': None, 'goldLocalRank': None, 'localFilm': None,
                'localStateFilm': None, 'noFillFilm': None}
            if traces is not None:
                t = traces[ordinal]
                if t['unitId'] != vu['id'] or t['finalAnchor'] != chosen or t['posteriorAnchor'] != argmax:
                    raise ValueError('trace unit or decision differs from voyage')
                nominated = t['nominations']
                if any(n['ref'] not in refs for n in nominated):
                    raise ValueError('trace nomination outside source index')
                for n in nominated:
                    if n['rawScore'] is not None: gold.finite(n['rawScore'])
                    if not isinstance(n['rankWithinLevel'],int) or n['rankWithinLevel'] < 0:
                        raise ValueError('invalid nomination rank')
                row['goldInCandidates'] = any(film(n['ref']) == e['goldMovie'] for n in nominated)
                local, traced_post, traced_external = collections.Counter(), collections.Counter(), collections.Counter()
                unique(t['states'], 'state')
                costs = [gold.finite(s['cost']) for s in t['states']]
                if not costs: raise ValueError('empty trace state set')
                weights = [math.exp(-(c-min(costs))/temperature) for c in costs]
                norm = sum(weights)
                for state, weight in zip(t['states'],weights):
                    m = gold.finite(state['localMass'])
                    if not 0 <= m <= 1: raise ValueError('invalid local mass')
                    if not math.isclose(m,weight/norm,rel_tol=1e-10,abs_tol=1e-12):
                        raise ValueError('local mass disagrees with retained costs')
                    posterior = gold.finite(state['posteriorMass'])
                    if not 0 <= posterior <= 1: raise ValueError('invalid trace posterior mass')
                    if state['anchor'] is not None:
                        if state['anchor'] not in refs or state['anchor'] not in {n['ref'] for n in nominated}:
                            raise ValueError('local state was not nominated')
                        local[state['anchor']] += m
                        traced_post[state['anchor']] += posterior
                    else:
                        if not state['state'].startswith('ext:'): raise ValueError('unknown external trace state')
                        traced_external[state['state'][4:]] += posterior
                for actual, expected in ((traced_post,masses),(traced_external,external)):
                    if set(actual)!=set(expected) or any(not math.isclose(actual[k],expected[k],abs_tol=1e-12,rel_tol=1e-10) for k in actual):
                        raise ValueError('trace posterior differs from voyage')
                rank = sorted((k for k in local if local[k] > 0), key=lambda k: (-local[k], k))
                row['localFilm'] = film(rank[0]) if rank else None
                row['goldLocalRank'] = next((i+1 for i,k in enumerate(rank) if film(k)==e['goldMovie']), None)
                winner = min(t['states'], key=lambda s: (-s['localMass'], s['state']))
                row['localStateFilm'] = film(winner['anchor'])
                row['noFillFilm'] = film(t['noFillAnchor'])
                row['nominationIdentity'] = sorted({refs[n['ref']]['identity'] for n in nominated})
                row['localCostIdentity'] = sorted((refs[s['anchor']]['identity'] + ':' + s['state'].partition('/distorted/')[2] if s['anchor'] else s['state'],
                                                   s['cost']) for s in t['states'])
            output.append(row)
    if len(output) != len(eligible): raise ValueError('lost eligible units')
    return output, {'population': gold.recall_population(directory), 'inputs': inputs, 'exclusions': exclusions}


def summarize(rows):
    def group(rs):
        n = len(rs)
        result = {'eligible': n, 'posteriorSupportGold': sum(r['goldInPosteriorSupport'] for r in rs),
            'posteriorArgmaxCorrect': sum(r['posteriorArgmaxFilm']==r['goldFilm'] for r in rs),
            'finalCorrect': sum(r['finalFilm']==r['goldFilm'] for r in rs),
            'finalOutsidePosteriorSupport': sum(not r['finalInPosteriorSupport'] for r in rs),
            'origins': dict(collections.Counter(r['origin'] for r in rs))}
        for origin in sorted(result['origins']):
            selected = [r for r in rs if r['origin']==origin]
            result.setdefault('byOrigin', {})[origin] = {'units': len(selected),
                'correct': sum(r['finalFilm']==r['goldFilm'] for r in selected)}
        if all(r['nominationAvailable'] for r in rs):
            result.update(candidateGold=sum(r['goldInCandidates'] for r in rs),
                localCorrect=sum(r['localFilm']==r['goldFilm'] for r in rs),
                localStateCorrect=sum(r['localStateFilm']==r['goldFilm'] for r in rs),
                noFillCorrect=sum(r['noFillFilm']==r['goldFilm'] for r in rs))
        return result
    pairs = [('posteriorArgmaxFilm','finalFilm')]
    if all(r['nominationAvailable'] for r in rows):
        pairs += [('localFilm','posteriorArgmaxFilm'),('posteriorArgmaxFilm','noFillFilm'),
                  ('noFillFilm','finalFilm'),('localStateFilm','localFilm')]
    stage_changes = {}
    for before,after in pairs:
        participants = []
        for sub in sorted({r['participant'] for r in rows}):
            rs = [r for r in rows if r['participant']==sub]
            participants.append({'eligible':len(rs),
                'delta':sum(int(r[after]==r['goldFilm'])-int(r[before]==r['goldFilm']) for r in rs)})
        stage_changes[f'{before}__{after}'] = {
            'deltaPercentagePoints':gold.percent(sum(p['delta'] for p in participants),len(rows)),
            'participantBootstrap95':gold.cluster_ci(participants,'delta','eligible'),
            'corrections':sum(r[before]!=r['goldFilm'] and r[after]==r['goldFilm'] for r in rows),
            'regressions':sum(r[before]==r['goldFilm'] and r[after]!=r['goldFilm'] for r in rows)}
    return {'overall': group(rows), 'pairedStageChanges':stage_changes,
            'participants': {s: group([r for r in rows if r['participant']==s])
            for s in sorted({r['participant'] for r in rows})},
            'films': {str(f): group([r for r in rows if r['goldFilm']==f]) for f in sorted({r['goldFilm'] for r in rows})}}


def compare(a, b, sample_size=12):
    keyed = lambda rows: {(r['participant'],r['unit']): r for r in rows}
    aa, bb = keyed(a), keyed(b)
    if len(aa)!=len(a) or len(bb)!=len(b) or set(aa)!=set(bb):
        raise ValueError('paired eligible population differs')
    changes = []
    for key, x in sorted(aa.items()):
        y = bb[key]
        if (x['signature'],x['goldFilm']) != (y['signature'],y['goldFilm']):
            raise ValueError('paired content or gold differs')
        xc, yc = x['finalFilm']==x['goldFilm'], y['finalFilm']==y['goldFilm']
        if xc == yc: continue
        if x['nominationAvailable'] and y['nominationAvailable']:
            if x['nominationIdentity'] != y['nominationIdentity']: first = 'nomination'
            elif x['localCostIdentity'] != y['localCostIdentity']: first = 'local_cost'
            elif x['posteriorIdentity'] != y['posteriorIdentity']: first = 'posterior'
            else: first = 'decode_or_fill'
        elif x['posteriorSupportIdentity'] != y['posteriorSupportIdentity']:
            first = 'posterior_support; nomination/local_cost_unavailable'
        elif x['posteriorIdentity'] != y['posteriorIdentity']: first = 'posterior_mass; nomination/local_cost_unavailable'
        else: first = 'decode_or_fill; nomination/local_cost_unavailable'
        changes.append({'participant': key[0], 'unit': key[1], 'kind': 'correction' if yc else 'regression',
                        'firstObservedDivergence': first, 'beforeOrigin': x['origin'], 'afterOrigin': y['origin']})
    samples = []
    for kind in ('correction','regression'):
        pool = sorted((r for r in changes if r['kind']==kind), key=lambda r: digest_value([20260905,r['participant'],r['unit']]))
        samples.extend(pool[:sample_size])
    return {'counts': dict(collections.Counter(r['kind'] for r in changes)),
            'firstObservedDivergence': dict(collections.Counter(r['firstObservedDivergence'] for r in changes)),
            'sampleRule': '12 per direction; ascending SHA256([20260905,participant,unit]); fixed before prose inspection',
            'sample': samples, 'changes': changes}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('reference', type=Path)
    p.add_argument('gold', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--arm', nargs=3, action='append', metavar=('NAME','REPORT_DIRECTORY','SOURCE_INDEX'), required=True)
    a = p.parse_args()
    if len({x[0] for x in a.arm}) != len(a.arm): raise ValueError('duplicate arm name')
    a.output.mkdir(parents=True, exist_ok=False)
    labels, ranges = gold.load_gold(a.gold), gold.film_ranges(a.reference)
    results, receipts, summaries = {}, {}, {}
    for name, directory, annotation in a.arm:
        rows, receipt = load_arm(Path(directory).resolve(), Path(annotation).resolve(), labels, ranges)
        results[name], receipts[name], summaries[name] = rows, receipt, summarize(rows)
        (a.output/f'{name}.units.json').write_text(json.dumps(rows, indent=2, sort_keys=True)+'\n')
    if len({r['population'] for r in receipts.values()}) != 1:
        raise ValueError('complete recall populations differ')
    comparisons = {f'{a.arm[0][0]}__{name}': compare(results[a.arm[0][0]], results[name]) for name,_,_ in a.arm[1:]}
    receipt = {'schema': 'storymodel4s.filmfestival.diagnostics/v1', 'arms': receipts,
               'referenceSha256': gold.digest(a.reference), 'goldSha256': gold.digest(a.gold),
               'scriptSha256': gold.digest(__file__), 'goldScorerSha256': gold.digest(gold.__file__)}
    for name, value in [('summary',summaries),('comparisons',comparisons)]:
        (a.output/f'{name}.json').write_text(json.dumps(value, indent=2, sort_keys=True)+'\n')
    receipt['artifacts'] = {p.name:gold.digest(p) for p in sorted(a.output.glob('*.json'))}
    (a.output/'receipt.json').write_text(json.dumps(receipt,indent=2,sort_keys=True)+'\n')
    print(json.dumps({k:v['overall'] for k,v in summaries.items()}, indent=2))


if __name__ == '__main__': main()
