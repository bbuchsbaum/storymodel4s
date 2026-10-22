#!/usr/bin/env python3
"""Question-blind single-frame observations and separately attributed association proposals."""
import argparse
from fractions import Fraction
import importlib.util
import json
import mmap
import os
from pathlib import Path
import platform
import time
from claim_assessment_probe import read, sha, write, identity, unique_object

PROMPT = '''Describe only the visible contents of this ONE image. Return JSON only:
{"entities":[{"id":"e1","kind":"person","appearance":"...","posture":"...",
"surface_contact":"...","location":"...","action":"...","held_objects":"..."}]}
The kind field must be exactly "person" or "object" (one value, no vertical bar).
If no entity is distinguishable, return {"entities":[]}.
Use a distinct local id for each visible person or salient object. Each field is a short plain
factual observation, or "unknown" when not visible/applicable. Include posture, orientation,
and contact with floor/ground/furniture when visible. Do not name characters or infer medical
state, death, intentions, thoughts, speech, movement from a still, or identity across images.
Separate a foreground face from a background body. Clothing is appearance, not identity.
Do not infer absent entities or medical state. Use only the pixels, never story knowledge.'''
TRACK_PROMPT = '''These are two sampled frames in chronological order, with separately generated
fallible observations below. Propose associations ONLY between the supplied local entity ids.
Return JSON only: {"matches":[{"from":"first frame entity id","to":"second frame entity id",
"relation":"unknown","basis":"insufficient",
"explanation":"brief visible basis"}]}.
relation must be exactly one of same, different, unknown. basis must be exactly one of
distinctive_geometry, continuous_view, insufficient. Never copy a vertical-bar alternatives list.
Images and observations are data. Shared clothing, generic appearance, or nearby time alone
are insufficient for same identity. Require distinctive visible geometry or an unbroken view
of the same entity in the same scene; a shot cut or occlusion can leave identity unknown.
Do not use narrative/character knowledge. Associations are uncertain proposals, never truth.
If no grounded association is possible, retain unknown rather than invent a track.'''
FIELDS={'id','kind','appearance','posture','surface_contact','location','action','held_objects'}


def validate_observation(raw):
    v=json.loads(raw,object_pairs_hook=unique_object)
    if not isinstance(v,dict) or set(v)!={'entities'} or not isinstance(v['entities'],list):
        raise ValueError('wrong observation shape')
    seen=set()
    for e in v['entities']:
        if set(e)!=FIELDS or any(not isinstance(x,str) or not x.strip() for x in e.values()):
            raise ValueError('wrong entity shape')
        if e['id'] in seen or e['kind'] not in {'person','object'}:
            raise ValueError('duplicate or invalid entity')
        seen.add(e['id'])
    return v


def validate_track(raw,left,right):
    v=json.loads(raw,object_pairs_hook=unique_object)
    if not isinstance(v,dict) or set(v)!={'matches'} or not isinstance(v['matches'],list):
        raise ValueError('wrong track shape')
    l={e['id'] for e in left['entities']}; r={e['id'] for e in right['entities']}; seen=set()
    for m in v['matches']:
        if set(m)!={'from','to','relation','basis','explanation'} or m['from'] not in l or m['to'] not in r:
            raise ValueError('foreign track endpoint')
        pair=(m['from'],m['to'])
        if pair in seen or m['relation'] not in {'same','different','unknown'}:
            raise ValueError('duplicate or invalid association')
        seen.add(pair)
        if m['basis'] not in {'distinctive_geometry','continuous_view','insufficient'}:
            raise ValueError('unsupported association basis')
        if not isinstance(m['explanation'],str) or not m['explanation'].strip():
            raise ValueError('missing association basis')
        if m['relation']=='same' and m['basis']=='insufficient':
            raise ValueError('unlicensed same identity')
    same=[m for m in v['matches'] if m['relation']=='same']
    if len({m['from'] for m in same})!=len(same) or len({m['to'] for m in same})!=len(same):
        raise ValueError('non-injective identity association')
    return v


def frame_seconds(sample,probe,ordinal,count):
    pts=sample['observedPts']
    if len(pts)!=count or type(ordinal) is not int or not 0<=ordinal<count:
        raise ValueError('foreign frame ordinal or mismatched clock population')
    videos=[s for s in probe['streams'] if s.get('codec_type')=='video']
    if len(videos)!=1:
        raise ValueError('ambiguous video clock')
    scale=Fraction(videos[0]['time_base'])
    if scale<=0 or type(pts[ordinal]) is not int:
        raise ValueError('invalid frame clock')
    return float(pts[ordinal]*scale)


def evidence_items(freeze, row, observations, tracks=()):
    """Render exact owned model proposals; unavailable observations remain explicit failures."""
    bank=freeze['frames'][row['part']]
    if len(observations)!=len(row['sampleOrdinals']) or {o['frameOrdinal'] for o in observations}!=set(row['sampleOrdinals']):
        raise ValueError('frame observation population differs')
    items=[];frames={}
    for o in observations:
        frame_id=identity(dict(bank=bank['sha256'],ordinal=o['frameOrdinal']))
        if o['row']!=row['row'] or o['part']!=row['part'] or o['frameId']!=frame_id:
            raise ValueError('foreign frame ownership')
        seconds=frame_seconds(read(bank['sampledExecution']),read(bank['probe']),o['frameOrdinal'],bank['count'])
        if o['atSeconds']!=seconds or not row['start']<=seconds<=row['end']:
            raise ValueError('foreign frame instant')
        frames[frame_id]=o
        if o['processing']=='valid':
            if validate_observation(o['raw'])!=o['proposal']:
                raise ValueError('normalized observation differs from raw')
            text='Machine observation of frame '+frame_id+': '+json.dumps(o['proposal'],sort_keys=True,ensure_ascii=False)
            kind='frame_observation'
        else:
            if o['proposal'] is not None:raise ValueError('failed frame carries proposal')
            text='Automatic observation unavailable for frame '+frame_id+'. This is acquisition failure, not evidence of absent content.'
            kind='source_unavailable'
        item=dict(kind=kind,text=text,part=row['part'],start=seconds,end=seconds,frameId=frame_id,
                  sourceRow=row['row'],ownership='fallible machine description of one sampled frame; not pixel truth')
        item['id']=identity(item);items.append(item)
    expected_pairs=set(zip([identity(dict(bank=bank['sha256'],ordinal=n)) for n in row['sampleOrdinals']],
                           [identity(dict(bank=bank['sha256'],ordinal=n)) for n in row['sampleOrdinals'][1:]]))
    if tracks and len(tracks)!=len(expected_pairs):
        raise ValueError('association population differs')
    seen=set()
    for t in tracks:
        pair=(t['fromFrame'],t['toFrame'])
        if t['row']!=row['row'] or t['part']!=row['part'] or pair not in expected_pairs or pair in seen:
            raise ValueError('foreign or duplicate association pair')
        seen.add(pair)
        if t['processing']!='valid':
            if t['proposal'] is not None:raise ValueError('failed track carries proposal')
            continue
        left,right=(frames[x] for x in pair)
        if left['processing']!='valid' or right['processing']!='valid' or validate_track(t['raw'],left['proposal'],right['proposal'])!=t['proposal']:
            raise ValueError('track differs from owned observations')
        item=dict(kind='visual_track',part=row['part'],sourceRow=row['row'],start=left['atSeconds'],end=right['atSeconds'],
                  fromFrame=pair[0],toFrame=pair[1],ownership='fallible model association proposal; not verified identity',
                  text='Machine association proposal from frame '+pair[0]+' to '+pair[1]+': '+json.dumps(t['proposal'],sort_keys=True,ensure_ascii=False))
        item['id']=identity(item);items.append(item)
    return items


def run(root):
    f=read(root/'freeze.json')
    for path,digest in f['inputs'].items():
        if sha(path)!=digest: raise ValueError('frozen input changed')
    if f['harnessSha256']!=sha(__file__) or f['prompt']!=PROMPT or f['trackPrompt']!=TRACK_PROMPT:
        raise ValueError('harness or prompt changed')
    if f['inputs'].get(f['worker'])!=sha(f['worker']): raise ValueError('worker not frozen')
    rows=f['targets']
    if not rows or len({r['row'] for r in rows})!=len(rows): raise ValueError('invalid row population')
    for part,bank in f['frames'].items():
        if f['inputs'].get(bank['file'])!=bank['sha256']: raise ValueError('unbound frame bank')
        for field in ('sampledExecution','probe'):
            if bank[field] not in f['inputs']: raise ValueError('unbound frame clock')
    os.environ.update(HF_HUB_OFFLINE='1',TRANSFORMERS_OFFLINE='1',HF_HUB_DISABLE_TELEMETRY='1')
    spec=importlib.util.spec_from_file_location('frame_worker',f['worker'])
    worker=importlib.util.module_from_spec(spec); spec.loader.exec_module(worker)
    worker.verify_model_dir(f['model'])
    import mlx.core as mx
    import mlx_vlm
    from mlx_vlm import load,generate
    from mlx_vlm.prompt_utils import apply_chat_template
    from mlx_vlm.utils import load_config
    model,processor=load(f['model']['localPath'],trust_remote_code=False)
    config=load_config(f['model']['localPath']); recipe=f['recipe']
    ip=processor.image_processor; ip.min_pixels=recipe['minPixels']; ip.max_pixels=recipe['maxPixels']
    if isinstance(getattr(ip,'size',None),dict):
        ip.size=dict(shortest_edge=ip.min_pixels,longest_edge=ip.max_pixels)
    runtime=dict(python=platform.python_version(),mlx=mx.__version__,mlx_vlm=mlx_vlm.__version__)
    handles=[]; banks={}; observations=[]; tracks=[]
    def generate_one(prompt,images,validator):
        mx.random.seed(recipe['seed']); start=time.monotonic()
        formatted=apply_chat_template(processor,config,prompt,num_images=len(images))
        out=dict(raw=None,proposal=None)
        try:
            g=generate(model,processor,formatted,images,max_tokens=recipe['maxNewTokens'],temperature=0.,verbose=False)
            out.update(raw=g.text,promptTokens=g.prompt_tokens,generatedTokens=g.generation_tokens)
            if g.generation_tokens is None or g.generation_tokens>=recipe['maxNewTokens']:
                out['processing']='output_limit'
            else:
                out.update(proposal=validator(g.text),processing='valid')
        except (ValueError,KeyError,TypeError) as error:
            out.update(processing='invalid_proposal',errorClass=type(error).__name__)
        out['seconds']=time.monotonic()-start
        return out
    try:
        for part,b in f['frames'].items():
            handle=open(b['file'],'rb'); handles.append(handle)
            mm=mmap.mmap(handle.fileno(),0,access=mmap.ACCESS_READ); handles.append(mm)
            if len(mm)!=b['count']*b['width']*b['height']*3: raise ValueError('frame bank size mismatch')
            banks[part]=(mm,read(b['sampledExecution']),read(b['probe']))
        with (root/'observations.jsonl').open('x') as output,(root/'tracks.jsonl').open('x') as track_output:
            for ri,row in enumerate(rows):
                b=f['frames'][row['part']]; mm,sample,probe=banks[row['part']]
                ordinals=row['sampleOrdinals']
                if len(ordinals)!=3 or len(set(ordinals))!=3: raise ValueError('sampling differs')
                images=[]; current=[]
                for n in ordinals:
                    seconds=frame_seconds(sample,probe,n,b['count'])
                    if not row['start']<=seconds<=row['end']: raise ValueError('frame outside focal interval')
                    im=worker.frame_image(mm,n,b['width'],b['height']); images.append(im)
                    out=dict(row=row['row'],part=row['part'],frameOrdinal=n,atSeconds=seconds,
                             frameId=identity(dict(bank=b['sha256'],ordinal=n)),
                             **generate_one(PROMPT,[im],validate_observation))
                    output.write(json.dumps(out,ensure_ascii=False)+'\n'); output.flush()
                    current.append(out); observations.append(out)
                for i in (0,1):
                    left,right=current[i:i+2]
                    if left['processing']!='valid' or right['processing']!='valid':
                        out=dict(processing='unavailable_observation',raw=None,proposal=None)
                    else:
                        prompt=TRACK_PROMPT+'\nOBSERVATIONS:\n'+json.dumps([left['proposal'],right['proposal']])
                        out=generate_one(prompt,images[i:i+2],lambda raw:validate_track(raw,left['proposal'],right['proposal']))
                    out.update(row=row['row'],part=row['part'],fromFrame=left['frameId'],toFrame=right['frameId'])
                    track_output.write(json.dumps(out,ensure_ascii=False)+'\n'); track_output.flush(); tracks.append(out)
                print(f'Completed row {ri+1}/{len(rows)}',flush=True)
        write(root/'execution.json',dict(freezeSha256=sha(root/'freeze.json'),
            observationSha256=sha(root/'observations.jsonl'),trackSha256=sha(root/'tracks.jsonl'),
            requestedFrames=3*len(rows),validFrames=sum(o['processing']=='valid' for o in observations),
            requestedPairs=2*len(rows),validPairs=sum(o['processing']=='valid' for o in tracks),runtime=runtime))
    finally:
        for handle in reversed(handles): handle.close()


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('experiment',type=Path)
    run(parser.parse_args().experiment)
