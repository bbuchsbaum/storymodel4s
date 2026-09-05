#!/usr/bin/env python3
"""Prepare a matched-source factorial only from an independently recorded correspondence.

Inventory emits the released 30-second window proposal and a blank correspondence worksheet.
Those numbers are not verified film-cut equivalence. Preparation checks recorded evidence/input
hashes and bounds; scientific correspondence remains the named reviewer's judgment. No gold read.
"""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path

import filmfest_experiment as source


def finite(value):
    x = float(value)
    if not math.isfinite(x): raise ValueError('nonfinite correspondence bound')
    return x


def window_key(row):
    return f"{finite(row['counterbalance']):g}:{finite(row['description_stop']):g}"


def inventory(data):
    annotation = data/'filmfestival/derived/annotation-JL.tsv'
    coder = source.read(annotation,'\t')
    inputs = {str(annotation.relative_to(data)):source.sha(annotation)}
    windows, excluded = [], collections.Counter()
    for slug, ordinal in source.FILMS.items():
        if slug == 'cmiyc_long':
            excluded['knownLongerCutFilms'] += 1
            continue
        clean = data/f'filmfestival/textdata/Cleaned Data/Description_Cleaned_Data/{slug}_description_cleaned.csv'
        mapped = data/f'filmfestival/textdata/Analysis Data/Description/{slug}_consensus_mapped_to_neuro.csv'
        for path in (clean,mapped): inputs[str(path.relative_to(data))] = source.sha(path)
        groups = collections.defaultdict(list)
        for r in source.read(clean):
            if r['phase_type']=='test': groups[window_key(r)].append(r)
        rows = [r for r in coder if int(r['film'].split('.')[0])==ordinal]
        lo = min(int(r['start_s']) for r in rows)
        hi = max(int(r['end_s'] or r['start_s']) for r in rows)
        seen = set()
        for m in source.read(mapped):
            key = window_key(m)
            if key in seen: raise ValueError('duplicate released window')
            seen.add(key)
            clean_rows = [r for r in groups[key] if
                (finite(r['onset']),finite(r['offset']))==(finite(m['onset']),finite(m['offset']))]
            excluded['responsesWithConflictingWindowBounds'] += len(groups[key])-len(clean_rows)
            onset,offset = finite(m['filmfest_onset30']),finite(m['filmfest_offset'])
            same_duration = math.isclose(offset-onset,finite(m['offset'])-finite(m['onset']),abs_tol=1e-9)
            if not same_duration: excluded['mappedThirtySecondWindowDiffersFromActualDuration'] += 1
            windows.append({'film':slug,'window':key,'crowdOnset':finite(m['onset']),
                'crowdOffset':finite(m['offset']),'publishedCoderStartProposal':onset,
                'publishedCoderEndProposal':offset,'annotationBounds':[lo,hi],
                'proposalWithinAnnotation':lo<=onset<offset<=hi,
                'proposalDurationMatchesActualWindow':same_duration,
                'coderStart':None,'coderEnd':None,
                'crowdDescriptions':len(clean_rows), 'status':'unreviewed'})
        if seen != set(groups): raise ValueError('released mapping/window population differs')
    return {'schema':'storymodel4s.filmfestival.correspondence/v1','status':'unreviewed',
        'reviewer':None,'basis':None,'evidencePath':None,'evidenceSha256':None,
        'inputs':inputs,'windows':windows,'exclusions':dict(excluded),
        'claim':'released clock proposals only; exact media correspondence unestablished'}


def select(texts, recipe, budget):
    remaining = sorted(set(texts),key=lambda t:hashlib.sha256(t.encode()).hexdigest())
    if len(remaining)<budget: raise ValueError('insufficient distinct source descriptions')
    out = []
    for _ in range(budget):
        if recipe=='medoid':
            # Preserve the original response multiplicity for consensus weights.
            chosen = source.medoid([t for t in texts if t in remaining])
        elif recipe=='sha': chosen = remaining[0]
        else: raise ValueError('unknown source aggregation recipe')
        out.append(chosen); remaining.remove(chosen)
    return out


def prepare(data, correspondence, output, budgets):
    if not budgets or len(set(budgets))!=len(budgets) or any(b not in (1,2) for b in budgets):
        raise ValueError('declare distinct budgets from 1 and 2')
    current = inventory(data)
    record = json.loads(correspondence.read_text())
    if (record.get('schema')!=current['schema'] or record.get('status')!='independently-reviewed'
            or not str(record.get('reviewer') or '').strip()
            or record.get('basis')!='verified-film-correspondence'):
        raise ValueError('independent verified-film correspondence is required before matched preparation')
    if record.get('inputs')!=current['inputs']: raise ValueError('correspondence source inputs changed')
    evidence = Path(record['evidencePath'])
    if not evidence.is_absolute() or not evidence.is_file() or source.sha(evidence)!=record['evidenceSha256']:
        raise ValueError('correspondence evidence missing or changed')
    current_windows = {(w['film'],w['window']):w for w in current['windows']}
    selected_windows, rejected = [], collections.Counter()
    coder = source.read(data/'filmfestival/derived/annotation-JL.tsv','\t')
    arms = {f'{origin}-{recipe}-b{budget}':[] for origin in ('jl','crowd')
            for recipe in ('medoid','sha') for budget in budgets}
    seen = set()
    for w in record['windows']:
        key = (w['film'],w['window'])
        if key in seen or key not in current_windows: raise ValueError('duplicate or unknown correspondence window')
        seen.add(key)
        proposal = current_windows[key]
        if w.get('status')!='verified':
            rejected['unverifiedWindows'] += 1; continue
        if (w['crowdOnset'],w['crowdOffset'])!=(proposal['crowdOnset'],proposal['crowdOffset']):
            raise ValueError('crowd interval changed in correspondence')
        start,end = finite(w['coderStart']),finite(w['coderEnd'])
        lo,hi = proposal['annotationBounds']
        if not lo<=start<end<=hi: raise ValueError('correspondence leaves annotation film extent')
        film_rows = [r for r in coder if int(r['film'].split('.')[0])==source.FILMS[w['film']]]
        matched = [r for r in film_rows if
            start <= int(r['start_s']) < int(r['end_s'] or r['start_s']) <= end]
        raw = source.read(data/f"filmfestival/textdata/Cleaned Data/Description_Cleaned_Data/{w['film']}_description_cleaned.csv")
        crowd = [r['description_content'] for r in raw if r['phase_type']=='test' and window_key(r)==w['window']
                 and (finite(r['onset']),finite(r['offset']))==(w['crowdOnset'],w['crowdOffset'])]
        pools = {'jl':[r['description'] for r in matched], 'crowd':crowd}
        if any(len(set(ts))<max(budgets) for ts in pools.values()):
            rejected['insufficientDistinctDescriptionsForLargestBudget'] += 1; continue
        selected_windows.append({'film':w['film'],'window':w['window'],'coderStart':start,'coderEnd':end,
            'poolSizes':{origin:len(ts) for origin,ts in pools.items()}})
        for origin, texts in pools.items():
            for recipe in ('medoid','sha'):
                choices = select(texts,recipe,max(budgets))
                for budget in budgets:
                    # Whole-film loci: this factorial scores film identity, not window localization.
                    arms[f'{origin}-{recipe}-b{budget}'].extend(dict(film_rows[0],scene_number='',
                        coarse_start_s='',start_s=lo,end_s=hi,description=t) for t in choices[:budget])
    if seen != set(current_windows): raise ValueError('correspondence must account for every proposed window')
    if not selected_windows: raise ValueError('no eligible verified matched windows')
    output.mkdir(parents=True,exist_ok=False)
    outputs = {}
    for arm,rows in arms.items():
        path = output/f'{arm}.tsv'; source.write_table(path,rows)
        outputs[arm] = {'sha256':source.sha(path),'candidates':len(rows)}
    receipt = {'schema':'storymodel4s.filmfestival.matched-source-inputs/v1',
        'correspondenceSha256':source.sha(correspondence),'evidenceSha256':source.sha(evidence),
        'reviewer':record['reviewer'],'basis':record['basis'],'inputs':current['inputs'],
        'windows':selected_windows,'excluded':dict(rejected),'budgets':budgets,'outputs':outputs,
        'goldRead':False,'estimand':'film identity; source origin x aggregation x candidate budget',
        'limits':'human correspondence judgment is recorded, not proved by hashing; coder rows must be wholly inside the verified window; semantic coverage can still differ'}
    (output/'receipt.json').write_text(json.dumps(receipt,indent=2,sort_keys=True)+'\n')
    return receipt


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('data',type=Path); p.add_argument('output',type=Path)
    p.add_argument('--correspondence',type=Path)
    p.add_argument('--budgets',type=int,nargs='+',default=[1,2])
    a=p.parse_args()
    if a.correspondence:
        print(json.dumps(prepare(a.data.resolve(),a.correspondence.resolve(),a.output.resolve(),a.budgets),indent=2))
    else:
        with a.output.open('x') as f: json.dump(inventory(a.data.resolve()),f,indent=2,sort_keys=True)
