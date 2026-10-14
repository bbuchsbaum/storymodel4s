#!/usr/bin/env python3
"""Bind published scene analysis indices to released raw indices, without inventing a trigger."""
import argparse
import hashlib
import json
from pathlib import Path
import re


def interval(start, end, crop, retained, concat_prefix=0):
    values = [start, end, crop, retained, concat_prefix]
    if any(not isinstance(v, int) or isinstance(v, bool) for v in values):
        raise ValueError('indices must be integers')
    start, end = start-concat_prefix, end-concat_prefix
    if not (crop >= 0 and retained > 0 and 1 <= start <= end <= retained):
        raise ValueError('scene outside its released run')
    return [start-1+crop, end+crop]


def controls():
    assert interval(1,26,20,946) == [20,46]
    assert interval(27,35,20,946) == [46,55]
    assert interval(892,945,20,946) == [911,965]
    assert interval(1,25,20,1030) == [20,45]
    assert interval(947,971,20,1030,946) == [20,45]
    assert interval(1947,1976,20,1030,946) == [1020,1050]
    assert len(range(*interval(27,35,20,946))) == 9
    negatives=[(0,1,20,946,0),(1,947,20,946,0),(2,1,20,946,0),
               (1,1,-1,946,0),(1947,1976,20,1030,0),(1.0,26,20,946,0)]
    for args in negatives:
        try:
            interval(*args)
        except ValueError:
            continue
        raise AssertionError('invalid index accepted')
    return {'positive':7,'refused':len(negatives)}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('dataset_root',type=Path)
    parser.add_argument('primary_sources',type=Path)
    parser.add_argument('verification',type=Path)
    parser.add_argument('output',type=Path)
    args=parser.parse_args()
    from scipy.io import loadmat
    sources=json.loads((args.primary_sources/'sources.json').read_text())
    for row in sources:
        data=(args.primary_sources/row['path']).read_bytes()
        if len(data)!=row['bytes'] or hashlib.sha256(data).hexdigest()!=row['sha256']:
            raise ValueError('primary source byte mismatch')
    verification_bytes=args.verification.read_bytes()
    verification=json.loads(verification_bytes)
    readme=(args.dataset_root/'README').read_bytes()
    expected=next(v for v in verification['files'] if v['file']=='README')
    if hashlib.sha256(readme).hexdigest()!=expected['sha256']:
        raise ValueError('release README mismatch')
    text=readme.decode()
    movie=text.split('Scene number and Onset/Offset of each MOVIE scene')[1].split('Scene number (matching the movie)')[0]
    tables=movie.split('Movie Scan ')[1:]
    if len(tables)!=2:
        raise ValueError('movie table inventory changed')
    ev=loadmat(args.primary_sources/'sherlock_allsubs_events.mat',simplify_cells=True)['ev']['s3']
    out=[]
    for part,table in enumerate(tables,1):
        rows=[tuple(map(int,m)) for m in re.findall(r'^\s*(\d+)\s+(\d+)\s+(\d+)\s*$',table,re.M)]
        original=ev[f'fullSL{part}']
        seconds=ev[f'fullSL{part}_s']
        if [(a,b) for _,a,b in rows]!=[(int(a),int(b)) for a,b in original]:
            raise ValueError('release and primary scene annotations disagree')
        run=next(v for v in verification['runs'] if v['task']==f'sherlockPart{part}')
        crop=run['releaseCropPrescription']
        if crop['beginningVolumes']!=20 or crop['resultLength']!=[946,1030][part-1]:
            raise ValueError('development crop prescription changed')
        covered=set()
        entries=[]
        for (scene,start,end),(secstart,secend) in zip(rows,seconds):
            raw=interval(start,end,20,crop['resultLength'])
            covered.update(range(start-1,end))
            entries.append({'scene_in_part':scene,'released_scene_tr_inclusive':[start,end],
                            'published_media_annotation_seconds':[float(secstart),float(secend)],
                            'raw_zero_based_half_open':raw})
        out.append({'part':part,'task':run['task'],'stored_volumes':run['storedVolumes'],
                    'retained_volumes':crop['resultLength'],'crop':crop,
                    'concatenated_prefix_volumes':0 if part==1 else 946,
                    'unlabelled_selected_indices':[i for i in range(crop['resultLength']) if i not in covered],
                    'scenes':entries})
    result={'schema':'sherlock-released-scene-crosswalk/v1','authority':'published-release-analysis-convention',
            'dataset':verification['dataset'],'revision':verification['revision'],'participant':'sub-03',
            'verification_sha256':hashlib.sha256(verification_bytes).hexdigest(),
            'sources':sources,'controls':controls(),'runs':out,
            'raw_index_equation':'raw_zero_based = scene_one_based - 1 + 20 (part local)',
            'hrf':'20-volume prescription already includes reported 3-volume lag; do not add it again',
            'physical_media_origin':{'status':'conditional-inference-not-observed','candidate_seconds':25.5,
                                     'equation':'20*1.5 - 3*1.5',
                                     'assumptions':['crop consists of 17 calibration volumes plus 3 HRF volumes',
                                                    'exact full-media zero equals the scene annotation zero'],
                                     'trigger_record':'unavailable'},
            'existing_derivative_history':'unknown; no array was cropped or lag shifted',
            'recall_word_clock':'unchanged; published raw scan-relative columns do not inherit this movie adjustment'}
    with args.output.open('x') as f:
        json.dump(result,f,indent=2);f.write('\n')
    print(json.dumps({'scenes':sum(len(r['scenes']) for r in out),'controls':result['controls'],
                      'unlabelled_counts':[len(r['unlabelled_selected_indices']) for r in out],
                      'physical_trigger':'unverified'}))


if __name__=='__main__':
    main()
